package com.example.crossup.parser

import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Cross-Up ızgara okuyucu (saf Kotlin, Android'e bağımlı değil).
 *
 * Gerçek bir ekran görüntüsü üzerinde Python'da doğrulanan algoritmanın Kotlin karşılığı:
 *  1. Izgara sınırları: üst satır (tamamı yeşil ipucu satırı) ve sol sütun üzerinden bulunur.
 *  2. Hücre türü: hücre kenarındaki 10 px'lik halkanın rengine göre
 *       yeşil -> CLUE, beyaz -> EMPTY, diğer -> FILLED (harf konmuş; harfi okumak ayrı adım).
 *  3. Ok algılama: siyah üçgenler ipucu hücresinin hemen dışında, komşu hücrenin kenarında durur.
 *       sağ ok -> ACROSS, alt ok -> DOWN.
 *  4. Bölünmüş (iki ipucu üst üste) hücre: sağ okun dikey konumu üst yarıdaysa
 *       yatay ipucu üst yarı, dikey ipucu alt yarıdır.
 *  5. Her ok için komşu hücrelerden bir sonraki ipucu hücresine / kenara kadar yürünür -> kelime yuvası.
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
    val darkMax: Int = 90               // ok üçgeni: R,G,B hepsi bunun altında
)

class GridParser(private val cfg: ParserConfig = ParserConfig()) {

    /** Izgara bulunamazsa null döner (ör. oyun ekranı açık değil). */
    fun parse(src: PixelSource): ParsedBoard? {
        // 1) Üst satır: genişliğin yarısından fazlası yeşil olan ilk satır.
        var top = -1
        for (y in 0 until src.height) {
            var g = 0
            var x = 0
            while (x < src.width) { if (isGreen(src.rgb(x, y))) g++; x += 3 }
            if (g * 3 > src.width / 2) { top = y; break }
        }
        if (top < 0) return null

        var left = Int.MAX_VALUE
        var right = -1
        for (x in 0 until src.width) if (isGreen(src.rgb(x, top))) {
            if (x < left) left = x
            if (x > right) right = x
        }
        right += 1
        val cw = (right - left) / cfg.cols.toDouble()
        if (cw < 20) return null

        // 2) Alt sınır: sol sütunda yeşil bandı, uzun bir boşluğa kadar izle.
        var lastGreen = top
        for (y in top until src.height) {
            var any = false
            for (x in left + 8..left + 20) if (isGreen(src.rgb(x, y))) { any = true; break }
            if (any) lastGreen = y else if (y - lastGreen > cw * 0.5) break
        }
        val bottom = lastGreen + 1
        val rows = ((bottom - top) / cw).roundToInt()
        if (rows < 2) return null
        val ch = (bottom - top) / rows.toDouble()

        fun rect(r: Int, c: Int) = Rect(
            (left + c * cw).roundToInt(), (top + r * ch).roundToInt(),
            (left + (c + 1) * cw).roundToInt(), (top + (r + 1) * ch).roundToInt()
        )
        val rects = List(rows) { r -> List(cfg.cols) { c -> rect(r, c) } }

        // 3) Hücre türleri.
        val kinds = List(rows) { r -> List(cfg.cols) { c -> classify(src, rects[r][c]) } }

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

    // ---- yardımcılar ----

    private fun halves(rc: Rect): Pair<Rect, Rect> {
        val mid = (rc.y0 + rc.y1) / 2
        return Rect(rc.x0 + 3, rc.y0 + 3, rc.x1 - 3, mid - 1) to Rect(rc.x0 + 3, mid + 1, rc.x1 - 3, rc.y1 - 3)
    }

    private fun inset(rc: Rect) = Rect(rc.x0 + 3, rc.y0 + 3, rc.x1 - 3, rc.y1 - 3)

    private fun isGreen(p: Int) = near(p, cfg.clueGreen, cfg.greenTol)
    private fun isWhite(p: Int) = near(p, cfg.white, cfg.whiteTol)
    private fun isDark(p: Int) =
        maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) < cfg.darkMax

    private fun near(p: Int, ref: Int, tol: Int): Boolean =
        abs(((p shr 16) and 0xFF) - ((ref shr 16) and 0xFF)) +
        abs(((p shr 8) and 0xFF) - ((ref shr 8) and 0xFF)) +
        abs((p and 0xFF) - (ref and 0xFF)) < tol
}
