package com.example.crossup.ai

import android.content.Context
import android.graphics.Bitmap
import com.example.crossup.game.TR
import com.example.crossup.parser.CellPos
import com.example.crossup.parser.ParsedBoard
import com.example.crossup.parser.Rect
import com.example.crossup.parser.crop
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

/** Bilinmeyen harf için kullanıcıya sorar. (kırpılmış görüntü, tahmin, kalan bilinmeyen sayısı) -> harf veya null (atla). */
typealias AskLetter = suspend (Bitmap, Char?, Int) -> Char?

const val TR_ALPHABET = "ABCÇDEFGĞHIİJKLMNOÖPRSŞTUÜVYZ"

/**
 * Yapay zekasız harf okuyucu.
 * Sıra: 1) şablon eşleştirme  2) ML Kit tahmini (sadece öneri; tüm harfler kayıtlıysa yapılmaz)  3) kullanıcıya sor.
 * Sadece kullanıcının onayladığı harfler şablon olarak kaydedilir.
 */
class LetterReader(ctx: Context) {
    private val db = LetterDb.get(ctx)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Son read() çağrısında harfi bulunamayan karo sayısı (raf + tahta). */
    @Volatile var lastUnresolved: Int = 0
        private set

    /**
     * Son read() çağrısında harfi bulunamayan RAF karosu sayısı (bunlar önemli).
     * Raf hiç bulunamadıysa veya bir karodan harf çıkarılamadıysa da sayılır;
     * böylece "Raf tamam" yanlışlıkla görünmez.
     */
    @Volatile var lastUnresolvedRack: Int = 0
        private set

    /** Son read() çağrısında harfi bulunamayan TAHTA hücresi sayısı (analizi engellemez). */
    @Volatile var lastUnresolvedBoard: Int = 0
        private set

    /** Son raf aramasının teşhis metni (panelde gösterilir). */
    @Volatile var lastRackInfo: String = ""
        private set

    private class Item(val rackIdx: Int, val cell: CellPos?, val crop: Bitmap, val fp: Fp) {
        var ch: Char? = null
        var skipped = false
    }

    suspend fun read(
        bmp: Bitmap, board: ParsedBoard, need: List<CellPos>,
        ask: AskLetter?, includeRack: Boolean
    ): Reading {
        val items = ArrayList<Item>()
        var rackMissing = 0   // rafta olup okunamayan / hiç bulunamayan karolar

        if (includeRack) {
            val tiles = locateRack(bmp, board)
            if (tiles.isEmpty()) rackMissing = 1
            var noFp = 0
            tiles.forEachIndexed { i, r ->
                val crop = inner(bmp, r, 0.075)
                val fp = LetterFp.of(crop)
                if (fp != null) items.add(Item(i, null, crop, fp)) else { rackMissing++; noFp++ }
            }
            lastRackInfo += "; harfi çıkarılamayan karo=$noFp"
        } else {
            lastRackInfo = ""
        }
        for (p in need) {
            val crop = inner(bmp, board.cellRects[p.r][p.c], 0.125)
            val fp = LetterFp.of(crop)
            if (fp != null) items.add(Item(-1, p, crop, fp))
        }

        // 1) şablon eşleştirme
        for (item in items) item.ch = db.match(item.fp)

        // 2-3) eşleşmeyenler için tahmin + soru
        val skippedFps = ArrayList<Fp>()
        while (true) {
            val next = items.firstOrNull { it.ch == null && !it.skipped } ?: break

            val again = db.match(next.fp)          // önceki cevaplar yüzünden artık eşleşebilir
            if (again != null) { next.ch = again; continue }

            if (skippedFps.any { LetterDb.dist(it, next.fp) <= 20 } || ask == null) {
                next.skipped = true
                continue
            }

            val remaining = items.count { it.ch == null && !it.skipped }
            val guess = guess(next.crop)
            val answer = ask(next.crop, guess, remaining)
            if (answer == null) {
                skippedFps.add(next.fp)
                next.skipped = true
            } else {
                db.learn(next.fp, answer)
                next.ch = answer
            }
        }

        lastUnresolved = items.count { it.ch == null } + rackMissing
        lastUnresolvedRack = items.count { it.rackIdx >= 0 && it.ch == null } + rackMissing
        lastUnresolvedBoard = items.count { it.rackIdx < 0 && it.ch == null }

        val rack = items.filter { it.rackIdx >= 0 && it.ch != null }
            .sortedBy { it.rackIdx }.map { it.ch!! }
        val letters = HashMap<CellPos, Char>()
        for (item in items) {
            val c = item.cell; val ch = item.ch
            if (c != null && ch != null) letters[c] = ch
        }
        return Reading(rack, letters)
    }

    private fun inner(bmp: Bitmap, r: Rect, frac: Double): Bitmap {
        val dx = ((r.x1 - r.x0) * frac).toInt()
        val dy = ((r.y1 - r.y0) * frac).toInt()
        return bmp.crop(Rect(r.x0 + dx, r.y0 + dy, r.x1 - dx, r.y1 - dy))
    }

    /**
     * ML Kit ile tek harf tahmini (sadece öneri).
     * Tüm harflerin şablonu zaten kayıtlıysa tahmin yapılmaz (null döner).
     */
    private suspend fun guess(crop: Bitmap): Char? {
        if (db.hasAll(TR_ALPHABET)) return null
        return try {
            val big = Bitmap.createScaledBitmap(crop, crop.width * 2, crop.height * 2, true)
            val text = suspendCancellableCoroutine<String> { cont ->
                recognizer.process(InputImage.fromBitmap(big, 0))
                    .addOnSuccessListener { t -> if (cont.isActive) cont.resume(t.text) }
                    .addOnFailureListener { if (cont.isActive) cont.resume("") }
            }
            text.trim().uppercase(TR).firstOrNull { it in TR_ALPHABET }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    // ---------- raf konumu ----------

    private fun differs(p: Int, bg: Int): Boolean =
        abs(((p shr 16) and 0xFF) - ((bg shr 16) and 0xFF)) +
        abs(((p shr 8) and 0xFF) - ((bg shr 8) and 0xFF)) +
        abs((p and 0xFF) - (bg and 0xFF)) > 30

    /** Koyu mürekkep (harf/karo çerçevesi). */
    private fun isInk(p: Int): Boolean =
        maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) < 100

    /** Tahtanın altındaki raf karolarını bulur (soldan sağa). Önce arka plan farkıyla, olmazsa koyu mürekkeple. */
    private fun locateRack(bmp: Bitmap, board: ParsedBoard): List<Rect> {
        val first = board.cellRects[0][0]
        val cw = first.x1 - first.x0
        val bottom = board.cellRects.last()[0].y1
        val h = bmp.height
        val info = StringBuilder("ekran ${bmp.width}x$h, tahta alt=$bottom, hücre=$cw")
        val yStart = bottom + 6
        val yEnd = minOf(h - 1, bottom + (cw * 1.6).toInt())
        if (yStart >= yEnd) {
            lastRackInfo = info.append("; raf alanı ekranın dışında").toString()
            return emptyList()
        }
        var tiles = locateByBackground(bmp, cw, yStart, yEnd, info)
        if (tiles.isEmpty()) {
            info.append("; yedek yöntem")
            tiles = locateByInk(bmp, cw, yStart, yEnd, info)
        }
        lastRackInfo = info.append("; bulunan karo=${tiles.size}").toString()
        return tiles
    }

    /** Yöntem 1: arka plandan farklı bölgeler. */
    private fun locateByBackground(bmp: Bitmap, cw: Int, yStart: Int, yEnd: Int, info: StringBuilder): List<Rect> {
        val w = bmp.width

        // Karoların dikey aralığı: arka plandan farklı piksel oranı yüksek satırlar.
        var top = -1; var bot = -1
        for (y in yStart..yEnd) {
            val bg = bmp.getPixel(3, y)
            var n = 0; var d = 0
            var x = 0
            while (x < w) {
                n++
                if (differs(bmp.getPixel(x, y), bg)) d++
                x += 4
            }
            if (d.toDouble() / n > 0.06) {
                if (top < 0) top = y
                bot = y
            } else if (top >= 0 && y - bot > 12) break
        }
        info.append("; bant=$top..$bot")
        if (top < 0 || bot - top < cw / 2) return emptyList()

        // Karo yüksekliği yaklaşık hücre kadardır; taşmışsa kes.
        if (bot - top > cw * 1.25) bot = top + (cw * 1.1).toInt()

        // Karonun üst yarısındaki iki çizgide arka plandan farklı bölgeler (birleştirilir).
        val lines = listOf(0.18, 0.30).map { top + ((bot - top) * it).toInt() }
        val bgs = lines.map { bmp.getPixel(3, it) }
        fun inkAt(x: Int): Boolean {
            for (i in lines.indices) if (differs(bmp.getPixel(x, lines[i]), bgs[i])) return true
            return false
        }

        val runs = ArrayList<Rect>()
        var start = -1; var lastD = -100
        fun close() {
            if (start >= 0) {
                val len = lastD - start + 1
                if (len >= cw * 0.6 && len <= cw * 1.4) runs.add(Rect(start, top, lastD + 1, bot + 1))
                start = -1
            }
        }
        for (x in 0 until w) {
            if (inkAt(x)) {
                if (start < 0) start = x
                lastD = x
            } else if (start >= 0 && x - lastD > 4) close()
        }
        close()
        return runs
    }

    /**
     * Yöntem 2 (yedek): koyu mürekkep (harf + karo çerçevesi) ile rafın yerini bulur,
     * karoları eşit aralıklı ve ekrana ortalı varsayar (karo ≈ hücre boyu, aralık ≈ %17).
     */
    private fun locateByInk(bmp: Bitmap, cw: Int, yStart: Int, yEnd: Int, info: StringBuilder): List<Rect> {
        val w = bmp.width; val h = bmp.height
        var top = -1
        for (y in yStart..yEnd) {
            var n = 0; var x = 0
            while (x < w) { if (isInk(bmp.getPixel(x, y))) n++; x += 2 }
            if (n >= 12) { top = y; break }
        }
        if (top < 0) { info.append("; koyu piksel yok"); return emptyList() }
        val tileW = cw * 0.985
        val gap = cw * 0.17
        val bot = minOf(h - 1, top + (cw * 0.99).toInt())

        var xMin = w; var xMax = -1
        for (y in top..bot step 2) {
            var x = 0
            while (x < w) {
                if (isInk(bmp.getPixel(x, y))) { if (x < xMin) xMin = x; if (x > xMax) xMax = x }
                x += 2
            }
        }
        if (xMax < xMin) { info.append("; koyu piksel yok"); return emptyList() }

        val width = xMax - xMin + 1
        val n = (((width + gap) / (tileW + gap)) + 0.2).roundToInt().coerceIn(1, 8)
        val center = (xMin + xMax) / 2.0
        val total = n * tileW + (n - 1) * gap
        val x0 = center - total / 2
        info.append("; mürekkep y=$top x=$xMin..$xMax n=$n")
        return List(n) { i ->
            val a = (x0 + i * (tileW + gap)).roundToInt()
            Rect(a, top, a + tileW.roundToInt(), bot + 1)
        }
    }
}
