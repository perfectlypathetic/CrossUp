package com.example.crossup.game

import com.example.crossup.parser.*

/**
 * Aday kelimeler + bilinen/varsayılan harflerden hücre başına harf olasılığı çıkarır.
 * Kesişen yuvalar birbirini budar (arc-consistency, 3 geçiş).
 */
class Inference(private val st: GameState) {

    private fun fits(slot: WordSlot, w: String, fixed: Map<CellPos, Char>): Boolean {
        if (w.length != slot.length) return false
        for (i in slot.cells.indices) {
            val f = fixed[slot.cells[i]]
            if (f != null && f != w[i]) return false
        }
        return true
    }

    /** fixed: known + (planlayıcının "doğru" varsaydığı harfler). */
    fun refine(fixed: Map<CellPos, Char>): Map<WordSlot, List<Candidate>> {
        var cur: Map<WordSlot, List<Candidate>> = st.board.slots.associateWith { s ->
            (st.cands[s] ?: emptyList()).filter { fits(s, it.word.uppercase(TR), fixed) }
        }
        repeat(3) {
            val allowed = HashMap<CellPos, Set<Char>>()
            for ((s, list) in cur) {
                if (list.isEmpty()) continue
                for (i in s.cells.indices) {
                    val set = list.map { it.word.uppercase(TR)[i] }.toSet()
                    allowed[s.cells[i]] = allowed[s.cells[i]]?.intersect(set) ?: set
                }
            }
            cur = cur.mapValues { (s, list) ->
                list.filter { c ->
                    val w = c.word.uppercase(TR)
                    s.cells.indices.all { i -> allowed[s.cells[i]]?.contains(w[i]) ?: true }
                }
            }
        }
        return cur
    }

    /** Hücre için harf olasılık dağılımı; hiçbir yuvadan bilgi yoksa boş map. */
    fun cellDist(p: CellPos, refined: Map<WordSlot, List<Candidate>>): Map<Char, Double> {
        var dist: Map<Char, Double>? = null
        for (s in st.slotsAt(p)) {
            val list = refined[s] ?: continue
            if (list.isEmpty()) continue
            val i = s.cells.indexOf(p)
            val total = list.sumOf { it.weight }
            val m = HashMap<Char, Double>()
            for (c in list) m.merge(c.word.uppercase(TR)[i], c.weight / total, Double::plus)
            dist = if (dist == null) m else {
                val prod = dist.keys.intersect(m.keys).associateWith { dist!![it]!! * m[it]!! }
                val z = prod.values.sum()
                if (z <= 0) m else prod.mapValues { it.value / z }
            }
        }
        return dist ?: emptyMap()
    }
}
