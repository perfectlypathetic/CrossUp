package com.example.crossup.game

import com.example.crossup.parser.*

data class Placement(val letter: Char, val cell: CellPos, val p: Double)

data class Plan(val placements: List<Placement>, val expectedValue: Double, val pass: Boolean) {
    override fun toString() =
        if (pass) "PAS (EV=%.2f)".format(expectedValue)
        else placements.joinToString { "${it.letter}→(${it.cell.r + 1},${it.cell.c + 1}) %${(it.p * 100).toInt()}" } +
             "  EV=%.2f".format(expectedValue)
}

/**
 * @param opponentMovesAfterMe bu hamleden sonra rakibin oynayacağı hamle sayısı.
 *        0 ise (oyunun son hamlesi) rakibe kelime "hediye etme" riski yoktur.
 */
class TurnContext(val endgame: Boolean, val myMovesLeft: Int, val opponentMovesAfterMe: Int)

class PlannerConfig(
    val oppHold1: Double = 0.50,   // 1 harfi kalan kelimeyi rakibin tamamlama olasılığı
    val oppHold2: Double = 0.15,   // 2 harfi kalan kelimeyi tamamlama olasılığı
    val bonus5: Int = 5,
    val bonus6: Int = 6
)

/**
 * Puanlama: doğru harf +1, yanlış -1  => harf başına 2p-1.
 * Kelimeyi tamamlayan hamle: +kelime uzunluğu. Tek turda 5/6 doğru: +5/+6.
 * Savunma: rakibe 1-2 harflik kolay tamamlama bırakmak cezalandırılır.
 */
class Planner(private val st: GameState, private val cfg: PlannerConfig = PlannerConfig()) {
    private val inf = Inference(st)

    fun plan(rack: List<Char>, ctx: TurnContext): Plan {
        val base = HashMap<CellPos, Char>(st.known)
        val assumed = HashMap<CellPos, Char>()
        val left = rack.map { it.uppercaseChar() }.toMutableList()
        val chosen = ArrayList<Placement>()
        val empties = st.emptyCells()

        // Açgözlü seçim: her adımda en yüksek olasılıklı (harf, hücre); seçileni "doğru" varsayıp yeniden çıkarım.
        while (left.isNotEmpty()) {
            val refined = inf.refine(base + assumed)
            var best: Placement? = null
            for (cell in empties) {
                if (cell in assumed) continue
                val d = inf.cellDist(cell, refined)
                for (l in left.distinct()) {
                    val p = d[l] ?: 0.0
                    if (best == null || p > best.p) best = Placement(l, cell, p)
                }
            }
            if (best == null || best.p <= 0.0) break
            chosen.add(best); assumed[best.cell] = best.letter; left.remove(best.letter)
        }

        // En iyi önek uzunluğunu EV'ye göre seç (5.-6. harf bonus yüzünden değerli olabilir).
        var bestK = 0; var bestEv = 0.0
        for (k in 1..chosen.size) {
            val ev = evaluate(chosen.take(k), ctx)
            if (ev > bestEv) { bestEv = ev; bestK = k }
        }
        return if (bestK == 0) Plan(emptyList(), 0.0, true)
        else Plan(chosen.take(bestK), bestEv, false)
    }

    private fun giveaway(remaining: Int, ctx: TurnContext): Double {
        if (ctx.opponentMovesAfterMe == 0) return 0.0
        return when (remaining) { 1 -> cfg.oppHold1; 2 -> cfg.oppHold2; else -> 0.0 }
    }

    fun evaluate(pl: List<Placement>, ctx: TurnContext): Double {
        var ev = pl.sumOf { 2 * it.p - 1 }
        val cells = pl.associateBy { it.cell }
        for (slot in st.board.slots) {
            val before = st.emptyIn(slot)
            val mine = slot.cells.filter { it in cells }
            if (before == 0 || mine.isEmpty()) continue
            val after = before - mine.size
            if (after == 0) ev += slot.length * mine.fold(1.0) { a, c -> a * cells[c]!!.p }
            else ev -= slot.length * (giveaway(after, ctx) - giveaway(before, ctx))
        }
        if (pl.size >= 5) {
            val all = pl.fold(1.0) { a, x -> a * x.p }
            ev += (if (pl.size >= 6) cfg.bonus6 else cfg.bonus5) * all
        }
        return ev
    }
}

/**
 * Tur bağlamı: boş hücre < 12 olunca son 4 tur başlar (2 senin, 2 rakibin; Pas bir turu harcar).
 * oppMovesAfterMyMove: bu hamlenden sonra rakibin kalan hamle sayısı (normal fazda 1 say).
 */
object Phase {
    fun context(st: GameState, myMovesLeft: Int, oppMovesAfterMyMove: Int) =
        TurnContext(st.inEndgame(), myMovesLeft, oppMovesAfterMyMove)
}
