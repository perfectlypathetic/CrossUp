package com.example.crossup.game

import com.example.crossup.parser.*

val TR: java.util.Locale = java.util.Locale("tr", "TR")

/** Bir kelime yuvası için aday cevap ve (göreli) olasılık ağırlığı. */
data class Candidate(val word: String, val weight: Double)

/**
 * Tahtanın mantıksal durumu.
 *  - known: tahtada duran DOĞRU harfler (yanlışlar zaten geri gidiyor, tahtada kalmaz).
 *  - cands: her yuva için ipucu çözücünün verdiği adaylar.
 * Kullanıcı veya rakip doğru harf koyunca known güncellenir; rakibin hamlesi
 * iki ekran görüntüsünün farkından (yeni dolan hücreler) çıkarılır.
 */
class GameState(
    val board: ParsedBoard,
    var myScore: Int = 0,
    var oppScore: Int = 0
) {
    val known = HashMap<CellPos, Char>()
    val cands = HashMap<WordSlot, List<Candidate>>()

    /** Dolu sayılan hücreler: harfi okunmuş olanlar + FILLED türündekiler. */
    fun isFilled(p: CellPos) = p in known || board.kinds[p.r][p.c] == CellKind.FILLED

    fun emptyCells(): List<CellPos> = board.cellsOf(CellKind.EMPTY).filter { it !in known }

    fun emptyIn(slot: WordSlot) = slot.cells.count { !isFilled(it) }

    fun slotsAt(p: CellPos) = board.slots.filter { p in it.cells }

    /** Oyun sonu fazı: boş hücre sayısı 12'nin altına inince son 4 tur başlar. */
    fun inEndgame() = emptyCells().size < 12
}
