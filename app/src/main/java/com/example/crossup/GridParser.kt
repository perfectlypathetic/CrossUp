package com.example.crossup.parser

import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Cross-Up ızgara okuyucu (saf Kotlin, Android'e bağımlı değil).
 *
 *  1. Izgara sınırları: ipucu yeşili olan her yerin en sol / en alt noktasından bulunur.
 *     SAĞ kenar: köşede resimli ipucu varsa yeşil görünmez; bu yüzden ızgara ekranda
 *     ortalı kabul edilir (sağ kenar = ekran genişliği - sol kenar).
 *  2. Hücre türü: kenar halkasının rengine göre yeşil -> CLUE, beyaz -> EMPTY, diğer -> FILLED.
 *  2a. İlk satır ve ilk sütun HER ZAMAN ipucu hücresidir (resimli olsalar bile).
 *  2b. Ortadaki resimli ipucu hücreleri: yanında ok (üçgen) varsa CLUE yapılır.
 *  3. Ok algılama, bölünmüş hücreler ve kelime yuvaları önceki sürümle aynı.
 */

interface PixelSource {
    val width: Int
    val height: Int
    /** 0xRRGGBB (alfa yok sayılır). */
    fun rgb(x: Int, y: Int): Int
}

enum class CellKind { CLUE, EMPTY, FILLED }
enum class Dir { ACROSS, DOWN }

data class CellPos(val r: Int, val c: Int)
data class Rect(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

/** Bir kelime yuvası; clueRect OCR veya görsel model için kırpılacak bölgedir. */
data class WordSlot(
    val dir: Dir,
    val clueCell: CellPos,
    val clueRect: Rect,
    val cells: List<CellPos>
) {
    val length get() = cells.size
}

class ParsedBoard(
    val rows: Int,
    val cols: Int,
    val kinds: List<List<CellKind>>,
    val cellRects: List<List<Rect>>,
    val slots: List<WordSlot>
) {
    fun cellsOf(kind: CellKind): List<CellPos> =
        (0 until rows).flatMap { r -> (0 until cols).map { c -> CellPos(r, c) } }
            .filter { kinds[it.r][it.c] == kind }
}

class ParserConfig(
    val cols: Int = 7,
    val clueGreen: Int = 0xD7F1CA,      // ipucu hücresi arka planı
    val white: Int = 0xFEFEFE,          // boş hücre
    val greenTol: Int = 40,
    val whiteTol: Int = 25,
    val darkMax: Int = 90,              // ok üçgeni: R,G,B hepsi bunun altında
    val busyRingMin: Double = 45.0      // resimli hücre: halkadaki renk dağılımı bundan büyükse "resim"
)

class GridParser(private val cfg: ParserConfig = ParserConfig()) {

    /** Izgara bulunamazsa null döner (ör. oyun ekranı açık değil). */
    fun parse(src: PixelSource): ParsedBoard? {
        // 1) Üst satır: genişliğin en az %30'u yeşil olan ilk satır (köşede resim olsa da bulunur).
        var top = -1
        for (y in 0 until src.height) {
            var g = 0
            var x = 0
            while (x < src.width) { if (isGreen(src.rgb(x, y))) g++; x += 3 }
            if (g * 10 > src.width) { top = y; break }
        }
        if (top < 0) return null

        // 2) Sol/sağ/alt sınır: üstten aşağı, yeşil görülen her yeri tara; uzun bir boşlukta dur.
        var minX = Int.MAX_VALUE
        var maxX = -1
        var lastGreen = top
        val gap = (src.width * 0.22).toInt()
        var yy = top
        while (yy < src.height) {
            // Satırda yeterince yeşil yoksa (birkaç tek tük piksel: buton kenarı, rozet, yazı kenarı)
            // bu satır ızgaraya ait sayılmaz. Bu, tahtanın altını yanlış hesaplamayı önler.
            var cnt = 0
            var rMin = Int.MAX_VALUE
            var rMax = -1
            var x = 0
            while (x < src.width) {
                if (isGreen(src.rgb(x, yy))) {
                    cnt++
                    if (x < rMin) rMin = x
                    if (x > rMax) rMax = x
                }
                x += 2
            }
            if (cnt >= 10) {
                lastGreen = yy
                if (rMin < minX) minX = rMin
                if (rMax > maxX) maxX = rMax
            } else if (yy - lastGreen > gap) break
            yy += 2
        }
        if (maxX < 0) return null

        val left = minX
        val expectedRight = src.width - left          // ızgara ekranda ortalı
        var right = maxX + 1
        if (right < expectedRight - 8) right = expectedRight   // sağ köşe resimliydi, yeşil görünmedi
        val cw = (right - left) / cfg.cols.toDouble()
        if (cw < 20) return null

        val bottom = lastGreen + 2
        val rows = ((bottom - top) / cw).roundToInt()
        if (rows < 2) return null
        val ch = cw                                    // hücreler kare

        fun rect(r: Int, c: Int) = Rect(
            (left + c * cw).roundToInt(), (top + r * ch).roundToInt(),
            (left + (c + 1) * cw).roundToInt(), (top + (r + 1) * ch).roundToInt()
        )
        val rects = List(rows) { r -> List(cfg.cols) { c -> rect(r, c) } }

        // 3) Hücre türleri.
        val kinds = List(rows) { r -> MutableList(cfg.cols) { c -> classify(src, rects[r][c]) } }

        // 3a) İlk satır ve ilk sütun her zaman ipucu hücresidir (resimli olsalar bile).
        for (r in 0 until rows) for (c in 0 until cfg.cols) {
            if (r == 0 || c == 0) kinds[r][c] = CellKind.CLUE
        }

        // 3b) Ortadaki resimli ipucu hücrelerini (FILLED sanılanları) CLUE yap.
        //     Ölçüt: yanında/altında boyutu uygun bir ok üçgeni var, ok açık bir hücreye bakıyor,
        //     ve (üçgen gibi sivriliyor VEYA kenar halkası resimli).
        for (r in 0 until rows) for (c in 0 until cfg.cols) {
            if (kinds[r][c] != CellKind.FILLED) continue
            val rc = rects[r][c]
            val h = rc.y1 - rc.y0
            val w = rc.x1 - rc.x0
            val busy = ringIsBusy(src, rc)

            val rightOpen = c + 1 < cfg.cols && kinds[r][c + 1] != CellKind.CLUE
            val downOpen = r + 1 < rows && kinds[r + 1][c] != CellKind.CLUE

            val hasRight = rightOpen && isArrowSize(rightArrowRows(src, rc), h) &&
                (busy || rightArrowTapers(src, rc))
            val hasDown = downOpen && isArrowSize(downArrowCols(src, rc), w) &&
                (busy || downArrowTapers(src, rc))

            if (hasRight || hasDown) kinds[r][c] = CellKind.CLUE
        }

        // 4) Oklar ve kelime yuvaları.
        val slots = ArrayList<WordSlot>()
        for (r in 0 until rows) for (c in 0 until cfg.cols) {
            if (kinds[r][c] != CellKind.CLUE) continue
            val rc = rects[r][c]

            val rightOpen = c + 1 < cfg.cols && kinds[r][c + 1] != CellKind.CLUE
            val downOpen = r + 1 < rows && kinds[r + 1][c] != CellKind.CLUE
            val ratioRight = if (rightOpen) rightArrowRatio(src, rc) else null
            val ratioDown = if (downOpen) downArrowPresent(src, rc) else false

            // İki ipucu üst üste mi? Sağ okun yüksekliğinden anlaşılır.
            val (acrossRect, downRect) = when {
                ratioRight != null && ratioDown && ratioRight < 0.4 -> halves(rc).let { it.first to it.second }
                ratioRight != null && ratioDown && ratioRight > 0.6 -> halves(rc).let { it.second to it.first }
                else -> inset(rc) to inset(rc)
            }

            if (ratioRight != null) {
                val cells = ArrayList<CellPos>()
                var cc = c + 1
                while (cc < cfg.cols && kinds[r][cc] != CellKind.CLUE) { cells.add(CellPos(r, cc)); cc++ }
                if (cells.isNotEmpty()) slots.add(WordSlot(Dir.ACROSS, CellPos(r, c), acrossRect, cells))
            }
            if (ratioDown) {
                val cells = ArrayList<CellPos>()
                var rr = r + 1
                while (rr < rows && kinds[rr][c] != CellKind.CLUE) { cells.add(CellPos(rr, c)); rr++ }
                if (cells.isNotEmpty()) slots.add(WordSlot(Dir.DOWN, CellPos(r, c), downRect, cells))
            }
        }
        return ParsedBoard(rows, cfg.cols, kinds, rects, slots)
    }

    // ---- hücre sınıflandırma ----

    private fun classify(src: PixelSource, rc: Rect): CellKind {
        var n = 0; var g = 0; var w = 0
        fun sample(x0: Int, y0: Int, x1: Int, y1: Int) {
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val p = src.rgb(x, y); n++
                    if (isGreen(p)) g++ else if (isWhite(p)) w++
                    x += 2
                }
                y += 2
            }
        }
        val m0 = 6; val m1 = 16
        sample(rc.x0 + m0, rc.y0 + m0, rc.x1 - m0, rc.y0 + m1)       // üst şerit
        sample(rc.x0 + m0, rc.y1 - m1, rc.x1 - m0, rc.y1 - m0)       // alt şerit
        sample(rc.x0 + m0, rc.y0 + m0, rc.x0 + m1, rc.y1 - m0)       // sol şerit
        sample(rc.x1 - m1, rc.y0 + m0, rc.x1 - m0, rc.y1 - m0)       // sağ şerit
        return when {
            g * 2 > n -> CellKind.CLUE
            w * 2 > n -> CellKind.EMPTY
            else -> CellKind.FILLED
        }
    }

    /** Hücrenin kenar halkasındaki renkler çok mu dağınık? (resim: evet, düz renkli harf karosu: hayır) */
    private fun ringIsBusy(src: PixelSource, rc: Rect): Boolean {
        val px = ArrayList<Int>()
        fun sample(x0: Int, y0: Int, x1: Int, y1: Int) {
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) { px.add(src.rgb(x, y)); x += 3 }
                y += 3
            }
        }
        val m0 = 6; val m1 = 16
        sample(rc.x0 + m0, rc.y0 + m0, rc.x1 - m0, rc.y0 + m1)
        sample(rc.x0 + m0, rc.y1 - m1, rc.x1 - m0, rc.y1 - m0)
        sample(rc.x0 + m0, rc.y0 + m0, rc.x0 + m1, rc.y1 - m0)
        sample(rc.x1 - m1, rc.y0 + m0, rc.x1 - m0, rc.y1 - m0)
        if (px.isEmpty()) return false

        var mr = 0.0; var mg = 0.0; var mb = 0.0
        for (p in px) { mr += (p shr 16) and 0xFF; mg += (p shr 8) and 0xFF; mb += p and 0xFF }
        mr /= px.size; mg /= px.size; mb /= px.size
        var dev = 0.0
        for (p in px) {
            dev += abs(((p shr 16) and 0xFF) - mr) + abs(((p shr 8) and 0xFF) - mg) + abs((p and 0xFF) - mb)
        }
        return dev / px.size > cfg.busyRingMin
    }

    // ---- ok algılama ----

    /** Hücrenin sağ kenarının hemen dışındaki siyah üçgenin dikey konumu (0..1); yoksa null. */
    private fun rightArrowRatio(src: PixelSource, rc: Rect): Double? {
        val h = rc.y1 - rc.y0
        var sum = 0.0; var count = 0
        for (y in rc.y0 + 8 until rc.y1 - 8) {
            var dark = false
            for (x in rc.x1 + 2..rc.x1 + 15) if (isDark(src.rgb(x, y))) { dark = true; break }
            if (dark) { sum += (y - rc.y0); count++ }
        }
        return if (count < 6) null else (sum / count) / h
    }

    private fun downArrowPresent(src: PixelSource, rc: Rect): Boolean {
        var count = 0
        for (x in rc.x0 + 8 until rc.x1 - 8) {
            var dark = false
            for (y in rc.y1 + 2..rc.y1 + 15) if (isDark(src.rgb(x, y))) { dark = true; break }
            if (dark) count++
        }
        return count >= 6
    }

    /** Sağ kenarın dışında koyu piksel içeren satır sayısı. */
    private fun rightArrowRows(src: PixelSource, rc: Rect): Int {
        var count = 0
        for (y in rc.y0 + 8 until rc.y1 - 8) {
            for (x in rc.x1 + 2..rc.x1 + 15) if (isDark(src.rgb(x, y))) { count++; break }
        }
        return count
    }

    /** Alt kenarın dışında koyu piksel içeren sütun sayısı. */
    private fun downArrowCols(src: PixelSource, rc: Rect): Int {
        var count = 0
        for (x in rc.x0 + 8 until rc.x1 - 8) {
            for (y in rc.y1 + 2..rc.y1 + 15) if (isDark(src.rgb(x, y))) { count++; break }
        }
        return count
    }

    /**
     * Sağdaki koyu şekil üçgen gibi sivriliyor mu? Tabanı (kenara yakın) geniş, ucu (uzak) dar olmalı.
     * Karo çerçevesi veya komşu resim ise her iki yerde de aynı genişlikte koyu olur.
     */
    private fun rightArrowTapers(src: PixelSource, rc: Rect): Boolean {
        var base = 0; var far = 0
        for (y in rc.y0 + 8 until rc.y1 - 8) {
            var b = false; var f = false
            for (x in rc.x1 + 2..rc.x1 + 4) if (isDark(src.rgb(x, y))) { b = true; break }
            for (x in rc.x1 + 11..rc.x1 + 15) if (isDark(src.rgb(x, y))) { f = true; break }
            if (b) base++
            if (f) far++
        }
        return base >= 6 && far * 2 <= base
    }

    /** Alttaki koyu şekil üçgen gibi sivriliyor mu? (rightArrowTapers'ın dikey hali) */
    private fun downArrowTapers(src: PixelSource, rc: Rect): Boolean {
        var base = 0; var far = 0
        for (x in rc.x0 + 8 until rc.x1 - 8) {
            var b = false; var f = false
            for (y in rc.y1 + 2..rc.y1 + 4) if (isDark(src.rgb(x, y))) { b = true; break }
            for (y in rc.y1 + 11..rc.y1 + 15) if (isDark(src.rgb(x, y))) { f = true; break }
            if (b) base++
            if (f) far++
        }
        return base >= 6 && far * 2 <= base
    }

    /** Üçgen boyutunda mı? Çok küçük (gürültü) veya çok büyük (karo çerçevesi/yazı) olmamalı. */
    private fun isArrowSize(count: Int, len: Int) = count >= 6 && count <= len * 0.4

    // ---- yardımcılar ----

    private fun halves(rc: Rect): Pair<Rect, Rect> {
        val mid = (rc.y0 + rc.y1) / 2
        return Rect(rc.x0 + 3, rc.y0 + 3, rc.x1 - 3, mid - 1) to Rect(rc.x0 + 3, mid + 1, rc.x1 - 3, rc.y1 - 3)
    }

    private fun inset(rc: Rect) = Rect(rc.x0 + 3, rc.y0 + 3, rc.x1 - 3, rc.y1 - 3)

    /** Ipucu yeşili: renge yakın VE gerçekten yeşilimsi (açık gri butonlar yeşil sayılmaz). */
    private fun isGreen(p: Int): Boolean {
        if (!near(p, cfg.clueGreen, cfg.greenTol)) return false
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        return g - r >= 8 && g - b >= 15
    }
    private fun isWhite(p: Int) = near(p, cfg.white, cfg.whiteTol)
    private fun isDark(p: Int) =
        maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) < cfg.darkMax

    private fun near(p: Int, ref: Int, tol: Int): Boolean =
        abs(((p shr 16) and 0xFF) - ((ref shr 16) and 0xFF)) +
        abs(((p shr 8) and 0xFF) - ((ref shr 8) and 0xFF)) +
        abs((p and 0xFF) - (ref and 0xFF)) < tol
}
