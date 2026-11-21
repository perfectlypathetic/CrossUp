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

/** Bilinmeyen harf için kullanıcıya sorar. (kırpılmış görüntü, tahmin, kalan bilinmeyen sayısı) -> harf veya null (atla). */
typealias AskLetter = suspend (Bitmap, Char?, Int) -> Char?

const val TR_ALPHABET = "ABCÇDEFGĞHIİJKLMNOÖPRSŞTUÜVYZ"

/**
 * Yapay zekasız harf okuyucu.
 * Sıra: 1) şablon eşleştirme  2) ML Kit tahmini (sadece öneri)  3) kullanıcıya sor.
 * Sadece kullanıcının onayladığı harfler şablon olarak kaydedilir.
 */
class LetterReader(ctx: Context) {
    private val db = LetterDb.get(ctx)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private class Item(val rackIdx: Int, val cell: CellPos?, val crop: Bitmap, val fp: Fp) {
        var ch: Char? = null
        var skipped = false
    }

    suspend fun read(
        bmp: Bitmap, board: ParsedBoard, need: List<CellPos>,
        ask: AskLetter?, includeRack: Boolean
    ): Reading {
        val items = ArrayList<Item>()

        if (includeRack) {
            val tiles = locateRack(bmp, board)
            tiles.forEachIndexed { i, r ->
                val crop = inner(bmp, r, 0.075)
                val fp = LetterFp.of(crop)
                if (fp != null) items.add(Item(i, null, crop, fp))
            }
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

    /** ML Kit ile tek harf tahmini (sadece öneri). */
    private suspend fun guess(crop: Bitmap): Char? = try {
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

    // ---------- raf konumu ----------

    private fun differs(p: Int, bg: Int): Boolean =
        abs(((p shr 16) and 0xFF) - ((bg shr 16) and 0xFF)) +
        abs(((p shr 8) and 0xFF) - ((bg shr 8) and 0xFF)) +
        abs((p and 0xFF) - (bg and 0xFF)) > 30

    /** Tahtanın altındaki raf karolarını bulur (soldan sağa). */
    private fun locateRack(bmp: Bitmap, board: ParsedBoard): List<Rect> {
        val first = board.cellRects[0][0]
        val cw = first.x1 - first.x0
        val bottom = board.cellRects.last()[0].y1
        val w = bmp.width; val h = bmp.height
        val yStart = bottom + 6
        val yEnd = minOf(h - 1, bottom + (cw * 1.6).toInt())
        if (yStart >= yEnd) return emptyList()

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
        if (top < 0 || bot - top < cw / 2) return emptyList()

        // Karoların yatay aralıkları: karonun alt kısmından geçen çizgide arka plandan farklı bölgeler.
        val ly = top + ((bot - top) * 0.88).toInt()
        val bg = bmp.getPixel(3, ly)
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
            if (differs(bmp.getPixel(x, ly), bg)) {
                if (start < 0) start = x
                lastD = x
            } else if (start >= 0 && x - lastD > 4) close()
        }
        close()
        return runs
    }
}
