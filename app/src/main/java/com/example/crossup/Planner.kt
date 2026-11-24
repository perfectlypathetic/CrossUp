package com.example.crossup.game

import com.example.crossup.parser.*

/** Boş hücre sayısı bunun altına inince "son 4 tur" başlar. */
const val ENDGAME_EMPTY_LIMIT = 12

data class Placement(val letter: Char, val cell: CellPos, val p: Double)

/** Rafta olup bu tur oynanmayan bir harf ve NEDEN oynanmadığı. */
data class HeldLetter(val letter: Char, val reason: String)

/**
 * @param swap          doluysa bu tur oynamak yerine bu harfler değiştirilmeli
 * @param held          oynanmayan harflerin nedenleri (bilgi eksikliği mi, strateji mi?)
 * @param unknownCells  hiçbir yuvadan bilgi gelmeyen (kelimesi bilinmeyen) boş hücreler
 * @param lines         ekranda gösterilecek açıklama satırları
 */
data class Plan(
    val placements: List<Placement>,
    val expectedValue: Double,
    val pass: Boolean,
    val swap: List<Char> = emptyList(),
    val held: List<HeldLetter> = emptyList(),
    val unknownCells: List<CellPos> = emptyList(),
    val lines: List<String> = emptyList()
) {
    override fun toString(): String =
        if (swap.isNotEmpty()) "DEĞİŞTİR " + swap.joinToString("")
        else if (pass) "PAS (EV=%.2f)".format(expectedValue)
        else placements.joinToString { "${it.letter}→(${it.cell.r + 1},${it.cell.c + 1}) %${(it.p * 100).toInt()}" } +
            "  EV=%.2f".format(expectedValue)
}

/**
 * @param endgame            "Son4" butonlarıyla son 4 tur başlatıldı mı
 * @param opponentMovesAfterMe bu hamleden sonra rakibin oynayacağı hamle sayısı (0 ise son hamle)
 * @param myScore / oppScore  izlenen puanlar
 * @param poolSize           çekilebilecek (torbada kalan) harf sayısı tahmini; 0 ise harf değiştirilemez
 * @param oppRackSize        rakibin rafındaki harf sayısı tahmini
 * @param seq                son 4 turda BENİM bir sonraki hamlemden itibaren sıra (true = ben)
 */
class TurnContext(
    val endgame: Boolean,
    val myMovesLeft: Int,
    val opponentMovesAfterMe: Int,
    val myScore: Int = 0,
    val oppScore: Int = 0,
    val poolSize: Int = 0,
    val oppRackSize: Int = 6,
    val seq: List<Boolean> = emptyList()
)

class PlannerConfig(
    val oppKnows: Double = 0.85,        // rakibin kelimeyi bildiği varsayımı
    val unknownHold: Double = 0.25,     // kelimesi bilinmeyen hücre için rakibin harfe sahip olma çarpanı
    val bonus5: Int = 5,
    val bonus6: Int = 6,
    val maxUnknownForSwap: Int = 2,     // bundan fazla bilinmeyen hücre varsa harf değiştirme önerilmez
    val minUsefulP: Double = 0.55,      // bu ihtimalin altı "düşük ihtimal" sayılır
    val leadEntryBonus: Double = 1.0    // son 4 ele önde girmenin değeri
)

/**
 * Son 4 tur için TAM BİLGİLİ minimax çözücü.
 * Sonda toplam harf sayısı = boş hücre sayısı olduğundan, rakibin harfleri
 * (boş hücrelerin harfleri - benim rafım) olarak kesin bilinir.
 * Hamle: o turda elindeki harflerden istediğin alt kümeyi doğru yerlere koymak (ya da pas).
 * Puan: harf başına +1, kelimeyi bitiren +uzunluk, tek turda 5/6 harf +5/+6.
 * Değer = (benim puanım - rakibin puanı) farkı; ben maksimize, rakip minimize eder.
 */
class EndgameSolver(
    private val letterOf: CharArray,
    private val slotMasks: IntArray,
    private val slotLens: IntArray,
    private val seq: BooleanArray,
    myTiles: List<Char>,
    private val bonus5: Int,
    private val bonus6: Int
) {
    class RootMove(val taken: Int, val value: Int, val gain: Int, val n: Int)

    private val nc = letterOf.size
    private val letters: List<Char> = letterOf.toList().distinct()
    private val nl = letters.size
    private val lidx = IntArray(nc) { letters.indexOf(letterOf[it]) }
    private val myCnt = IntArray(nl)
    private val oppCnt = IntArray(nl)
    private val memo = HashMap<Long, Int>()
    val rootMoves = ArrayList<RootMove>()

    init {
        val total = IntArray(nl)
        for (i in 0 until nc) total[lidx[i]]++
        for (t in myTiles) {
            val k = letters.indexOf(t)
            if (k >= 0) myCnt[k]++
        }
        for (k in 0 until nl) oppCnt[k] = total[k] - myCnt[k]
    }

    private fun key(mask: Int, idx: Int): Long {
        var k = mask.toLong()
        k = (k shl 4) or idx.toLong()
        for (i in 0 until nl) k = (k shl 3) or (myCnt[i].toLong() and 7L)
        return k
    }

    private fun points(taken: Int, n: Int, mask: Int): Int {
        if (n == 0) return 0
        var pts = n
        val after = mask and taken.inv()
        for (s in slotMasks.indices) {
            val sm = slotMasks[s]
            if ((sm and taken) != 0 && (sm and after) == 0) pts += slotLens[s]
        }
        if (n >= 6) pts += bonus6 else if (n == 5) pts += bonus5
        return pts
    }

    private fun solve(mask: Int, idx: Int): Int {
        if (idx >= seq.size || mask == 0) return 0
        val k = key(mask, idx)
        val hit = memo[k]
        if (hit != null) return hit
        val isMe = seq[idx]
        val start = if (isMe) Int.MIN_VALUE else Int.MAX_VALUE
        val v = search(0, mask, idx, 0, 0, isMe, start)
        memo[k] = v
        return v
    }

    private fun search(pos: Int, mask: Int, idx: Int, taken: Int, n: Int, isMe: Boolean, best0: Int): Int {
        if (pos == nc) {
            val gain = points(taken, n, mask)
            val child = solve(mask and taken.inv(), idx + 1)
            val v = (if (isMe) gain else -gain) + child
            if (idx == 0) rootMoves.add(RootMove(taken, v, gain, n))
            return if (isMe) maxOf(best0, v) else minOf(best0, v)
        }
        var best = search(pos + 1, mask, idx, taken, n, isMe, best0)
        if (((mask shr pos) and 1) == 1) {
            val li = lidx[pos]
            val cnt = if (isMe) myCnt else oppCnt
            if (cnt[li] > 0) {
                cnt[li]--
                best = search(pos + 1, mask, idx, taken or (1 shl pos), n + 1, isMe, best)
                cnt[li]++
            }
        }
        return best
    }

    /** Sıra bende (seq[0] = true) iken tüm hamleleri dener; rootMoves dolar. */
    fun solveRoot(mask: Int): Int {
        rootMoves.clear()
        return search(0, mask, 0, 0, 0, true, Int.MIN_VALUE)
    }

    /** En iyi hamle: önce değer, eşitse anlık puan, eşitse çok harf. */
    fun bestRoot(): RootMove? {
        var best: RootMove? = null
        for (m in rootMoves) {
            val b = best
            if (b == null || m.value > b.value ||
                (m.value == b.value && (m.gain > b.gain || (m.gain == b.gain && m.n > b.n)))
            ) best = m
        }
        return best
    }

    fun valueOf(taken: Int): Int? {
        for (m in rootMoves) if (m.taken == taken) return m.value
        return null
    }
}

private class Ev(val total: Double, val risk: Double)

/**
 * Strateji:
 *  - SON 4 TUR: tüm hücrelerin harfi güvenle biliniyorsa kesin (minimax) hesap.
 *  - NORMAL FAZ: beklenen puan + rakibin kelime tamamlama riski (torbadaki harflerden
 *    hipergeometrik tahminle) + skora göre risk ayarı + harf değiştirme / pas kararı.
 *  - Oynanmayan her harf için NEDEN açıklanır (bilgi eksik mi, stratejik mi).
 */
class Planner(private val st: GameState, private val cfg: PlannerConfig = PlannerConfig()) {
    private val inf = Inference(st)
    private val empties: List<CellPos> = st.emptyCells()
    private val baseRefined: Map<WordSlot, List<Candidate>> = inf.refine(st.known)
    private val baseDist: Map<CellPos, Map<Char, Double>> = empties.associateWith { inf.cellDist(it, baseRefined) }
    private val guess = HashMap<CellPos, Char>()
    private val guessP = HashMap<CellPos, Double>()

    init {
        for (c in empties) {
            val d = baseDist[c]
            if (d == null || d.isEmpty()) continue
            var bl = ' '
            var bp = 0.0
            for ((l, p) in d) {
                if (p > bp) { bp = p; bl = l }
            }
            if (bp >= 0.6) { guess[c] = bl; guessP[c] = bp }
        }
    }

    // ------------------------------------------------------------------ yardımcılar

    private fun slotLabel(s: WordSlot): String {
        val arrow = if (s.dir == Dir.ACROSS) "→" else "↓"
        return "(${s.clueCell.r + 1},${s.clueCell.c + 1})$arrow${s.length}"
    }

    private fun cellLabel(c: CellPos): String = "(${c.r + 1},${c.c + 1})"

    private fun choose(n: Int, k: Int): Double {
        if (k < 0 || k > n) return 0.0
        val kk = minOf(k, n - k)
        var r = 1.0
        for (i in 1..kk) r = r * (n - kk + i) / i
        return r
    }

    /** Toplam u harften c tanesi "istenen" ise, d çekilişte en az need tanesinin gelme olasılığı. */
    private fun atLeast(u: Int, c: Int, draws: Int, need: Int): Double {
        if (need <= 0) return 1.0
        val d = minOf(draws, u)
        if (c < need || d < need) return 0.0
        val total = choose(u, d)
        if (total <= 0.0) return 0.0
        var s = 0.0
        for (k in need..minOf(c, d)) s += choose(c, k) * choose(u - c, d - k) / total
        return s.coerceIn(0.0, 1.0)
    }

    private fun removeLetters(rack: List<Char>, used: List<Char>): List<Char> {
        val m = rack.toMutableList()
        for (u in used) m.remove(u)
        return m
    }

    /** Görülmemiş (rakipte veya torbada olabilecek) harflerin sayımı. */
    private fun unseenCounts(placed: Set<CellPos>, rackAfter: List<Char>): Map<Char, Int> {
        val m = HashMap<Char, Int>()
        for (c in empties) {
            if (c in placed) continue
            val g = guess[c] ?: continue
            m[g] = (m[g] ?: 0) + 1
        }
        for (l in rackAfter) {
            val v = (m[l] ?: 0) - 1
            if (v > 0) m[l] = v else m.remove(l)
        }
        return m
    }

    private fun oppCompleteProb(rem: List<CellPos>, unseen: Map<Char, Int>, u: Int, draws: Int): Double {
        var unknown = 0
        val need = HashMap<Char, Int>()
        for (c in rem) {
            val g = guess[c]
            if (g == null) unknown++ else need[g] = (need[g] ?: 0) + 1
        }
        var p = 1.0
        for ((l, n) in need) p *= atLeast(u, unseen[l] ?: 0, draws, n)
        if (unknown > 0) p *= Math.pow(cfg.unknownHold, unknown.toDouble())
        return p * cfg.oppKnows
    }

    /** Hamleden sonra rakibin tamamlayabileceği kelimelerin beklenen puanı. */
    private fun totalRisk(placed: Set<CellPos>, rackAfter: List<Char>, ctx: TurnContext): Double {
        if (ctx.opponentMovesAfterMe <= 0) return 0.0
        val unseen = unseenCounts(placed, rackAfter)
        var u = empties.size - placed.size - rackAfter.size
        val sumUnseen = unseen.values.sum()
        if (u < sumUnseen) u = sumUnseen
        if (u < 1) u = 1
        val draws = minOf(ctx.oppRackSize, u)
        var risk = 0.0
        for (slot in st.board.slots) {
            val rem = ArrayList<CellPos>()
            for (c in slot.cells) if (!st.isFilled(c) && c !in placed) rem.add(c)
            if (rem.isEmpty() || rem.size > draws) continue
            risk += slot.length * oppCompleteProb(rem, unseen, u, draws)
        }
        return risk
    }

    /** Öndeysen savunmacı, gerideysen cesur ol. */
    private fun riskMul(ctx: TurnContext): Double {
        val lead = ctx.myScore - ctx.oppScore
        return (1.0 + lead / 40.0).coerceIn(0.75, 1.35)
    }

    private fun evalFull(pl: List<Placement>, rack: List<Char>, ctx: TurnContext): Ev {
        var ev = 0.0
        for (x in pl) ev += 2 * x.p - 1
        val cells = HashMap<CellPos, Placement>()
        for (x in pl) cells[x.cell] = x
        for (slot in st.board.slots) {
            val before = st.emptyIn(slot)
            if (before == 0) continue
            var mineCount = 0
            var prod = 1.0
            for (c in slot.cells) {
                val x = cells[c]
                if (x != null) { mineCount++; prod *= x.p }
            }
            if (mineCount > 0 && mineCount == before) ev += slot.length * prod
        }
        if (pl.size >= 5) {
            var all = 1.0
            for (x in pl) all *= x.p
            ev += (if (pl.size >= 6) cfg.bonus6 else cfg.bonus5) * all
        }
        val placed: Set<CellPos> = cells.keys
        val rackAfter = removeLetters(rack, pl.map { it.letter })
        val riskAfter = totalRisk(placed, rackAfter, ctx)
        val riskBefore = totalRisk(emptySet<CellPos>(), rack, ctx)
        val risk = riskMul(ctx) * (riskAfter - riskBefore)
        ev -= risk
        // Son 4 ele önde girmek önemli.
        if (!ctx.endgame && pl.isNotEmpty()) {
            val e0 = empties.size
            if (e0 >= ENDGAME_EMPTY_LIMIT && e0 - pl.size < ENDGAME_EMPTY_LIMIT) {
                val leadAfter = ctx.myScore - ctx.oppScore + ev
                if (leadAfter > 0) ev += cfg.leadEntryBonus else if (leadAfter < 0) ev -= cfg.leadEntryBonus
            }
        }
        return Ev(ev, risk)
    }

    /** Torbadan gelecek taze bir harfin ortalama faydası (harf değiştirme kararı için). */
    private fun freshValue(rack: List<Char>): Double {
        val unseen = unseenCounts(emptySet<CellPos>(), rack)
        val tot = unseen.values.sum()
        if (tot <= 0) return 0.0
        var v = 0.0
        for ((l, n) in unseen) {
            var mp = 0.0
            for (c in empties) {
                val p = baseDist[c]?.get(l) ?: 0.0
                if (p > mp) mp = p
            }
            v += (n.toDouble() / tot) * maxOf(0.0, 2 * mp - 1)
        }
        return v
    }

    // ------------------------------------------------------------------ ana giriş

    fun plan(rack: List<Char>, ctx: TurnContext): Plan {
        val r = rack.map { it.uppercaseChar() }
        if (ctx.endgame && ctx.seq.isNotEmpty()) {
            val ep = planEndgame(r, ctx)
            if (ep != null) return ep
        }
        return planHeuristic(r, ctx)
    }

    // ------------------------------------------------------------------ SON 4: kesin hesap

    private fun planEndgame(rack: List<Char>, ctx: TurnContext): Plan? {
        val e = empties.size
        if (e == 0 || e > 14 || rack.isEmpty() || !ctx.seq[0]) return null

        val letterOf = CharArray(e)
        for (i in 0 until e) {
            val c = empties[i]
            val g = guess[c] ?: return null
            if ((guessP[c] ?: 0.0) < 0.75) return null
            letterOf[i] = g
        }
        // Rafım, boş hücrelerin harfleriyle tutarlı olmalı (rakibin harfleri = kalanlar).
        val need = HashMap<Char, Int>()
        for (ch in letterOf) need[ch] = (need[ch] ?: 0) + 1
        val have = HashMap<Char, Int>()
        for (ch in rack) have[ch] = (have[ch] ?: 0) + 1
        for ((ch, n) in have) if (n > (need[ch] ?: 0)) return null

        val cellIdx = HashMap<CellPos, Int>()
        for (i in 0 until e) cellIdx[empties[i]] = i
        val sm = ArrayList<Int>()
        val sl = ArrayList<Int>()
        for (slot in st.board.slots) {
            var m = 0
            for (c in slot.cells) {
                val i = cellIdx[c]
                if (i != null) m = m or (1 shl i)
            }
            if (m != 0) { sm.add(m); sl.add(slot.length) }
        }

        val solver = EndgameSolver(
            letterOf, sm.toIntArray(), sl.toIntArray(), ctx.seq.toBooleanArray(),
            rack, cfg.bonus5, cfg.bonus6
        )
        solver.solveRoot((1 shl e) - 1)
        val best = solver.bestRoot() ?: return null

        val diffNow = ctx.myScore - ctx.oppScore
        val finalDiff = diffNow + best.value
        val placements = ArrayList<Placement>()
        for (i in 0 until e) {
            if (((best.taken shr i) and 1) == 1) placements.add(Placement(letterOf[i], empties[i], 1.0))
        }

        // Oynanmayan harflerin nedeni: koysaydım sonuç ne olurdu?
        val usedBy = HashMap<Char, Int>()
        for (pl in placements) usedBy[pl.letter] = (usedBy[pl.letter] ?: 0) + 1
        val held = ArrayList<HeldLetter>()
        for ((ch, n) in have) {
            val left = n - (usedBy[ch] ?: 0)
            if (left <= 0) continue
            var alt = Int.MIN_VALUE
            var altCell = -1
            for (i in 0 until e) {
                if (letterOf[i] != ch) continue
                if (((best.taken shr i) and 1) == 1) continue
                val v = solver.valueOf(best.taken or (1 shl i)) ?: continue
                if (v > alt) { alt = v; altCell = i }
            }
            val reason: String
            if (altCell < 0) {
                reason = "SAKLA: koyabileceği boş hücre kalmadı"
            } else {
                val d = best.value - alt
                val rc = cellLabel(empties[altCell])
                reason = if (d > 0) "STRATEJİK SAKLA: ${rc} hücresine koyarsan son fark $d puan düşer"
                else "SAKLA: ${rc} hücresine koysan da fark değişmez, saklamak daha güvenli"
            }
            for (k in 0 until left) held.add(HeldLetter(ch, reason))
        }

        val lines = ArrayList<String>()
        lines.add("SON4 KESİN HESAP (${ctx.seq.size} tur, rakip her şeyi bilse bile):")
        val sign = if (finalDiff > 0) "+" else ""
        val verdict = if (finalDiff > 0) "KAZANIRSIN" else if (finalDiff < 0) "GERİDE KALIRSIN" else "BERABERE"
        lines.add("  Beklenen son fark: $sign$finalDiff → $verdict")
        if (placements.isEmpty()) lines.add("  Bu tur PAS en iyisi (rakibi harf oynamaya zorla).")
        for (h in held) lines.add("  ${h.letter}: ${h.reason}")

        return Plan(placements, best.value.toDouble(), placements.isEmpty(), emptyList(), held, emptyList(), lines)
    }

    // ------------------------------------------------------------------ NORMAL FAZ (ve belirsiz son4)

    private fun planHeuristic(rack: List<Char>, ctx: TurnContext): Plan {
        // 1) Açgözlü zincir: her adımda en olası (harf, hücre); seçileni "doğru" varsayıp yeniden çıkarım.
        val base = HashMap<CellPos, Char>(st.known)
        val assumed = HashMap<CellPos, Char>()
        val left = rack.toMutableList()
        val chosen = ArrayList<Placement>()
        while (left.isNotEmpty() && chosen.size < 6) {
            val refined = inf.refine(base + assumed)
            var best: Placement? = null
            for (cell in empties) {
                if (cell in assumed) continue
                val d = inf.cellDist(cell, refined)
                for (l in left.distinct()) {
                    val p = d[l] ?: 0.0
                    val b0 = best
                    if (b0 == null || p > b0.p) best = Placement(l, cell, p)
                }
            }
            val b = best
            if (b == null || b.p <= 0.05) break
            chosen.add(b)
            assumed[b.cell] = b.letter
            left.remove(b.letter)
        }

        // 2) Zincirin TÜM alt kümelerini değerlendir (bazı harfleri saklamak daha iyi olabilir).
        val k = chosen.size
        var bestSub: List<Placement> = emptyList()
        var bestEv = 0.0
        for (m in 1 until (1 shl k)) {
            val sub = ArrayList<Placement>()
            for (i in 0 until k) if (((m shr i) and 1) == 1) sub.add(chosen[i])
            val ev = evalFull(sub, rack, ctx)
            if (ev.total > bestEv) { bestEv = ev.total; bestSub = sub }
        }

        // 3) Bilgi durumu
        val unknownCells = empties.filter { baseDist[it].isNullOrEmpty() }
        val unknownSlots = st.board.slots.filter { st.emptyIn(it) > 0 && st.cands[it].isNullOrEmpty() }
        val conflictSlots = st.board.slots.filter {
            st.emptyIn(it) > 0 && !st.cands[it].isNullOrEmpty() && baseRefined[it].isNullOrEmpty()
        }

        // 4) Her rafta harf için en iyi hücre ihtimali
        val bestP = HashMap<Char, Double>()
        val bestCell = HashMap<Char, CellPos>()
        for (l in rack.distinct()) {
            for (c in empties) {
                val p = baseDist[c]?.get(l) ?: 0.0
                if (p > (bestP[l] ?: 0.0)) { bestP[l] = p; bestCell[l] = c }
            }
        }

        // 5) Harf değiştirme: ölü harfler çoksa ve oyun bu turdan az değer veriyorsa.
        var swap: List<Char> = emptyList()
        if (!ctx.endgame && ctx.poolSize >= 2 && unknownCells.size <= cfg.maxUnknownForSwap) {
            val used = bestSub.map { it.letter }.toMutableList()
            val dead = ArrayList<Char>()
            for (l in rack) {
                if (used.remove(l)) continue
                if ((bestP[l] ?: 0.0) < 0.2) dead.add(l)
            }
            if (dead.size >= 2) {
                val gain = dead.size * freshValue(rack) * 0.7
                if (gain > bestEv + 0.3) swap = dead.take(ctx.poolSize)
            }
        }
        val finalPl: List<Placement> = if (swap.isNotEmpty()) emptyList() else bestSub

        // 6) Oynanmayan harflerin nedenleri
        val held = ArrayList<HeldLetter>()
        val usedL = finalPl.map { it.letter }.toMutableList()
        val swapLeft = swap.toMutableList()
        for (l in rack) {
            if (usedL.remove(l)) continue
            if (swapLeft.remove(l)) continue
            val reason = if (swap.isNotEmpty()) "bu tur oynanmıyor (diğer harfler değiştiriliyor)"
            else explainHold(l, finalPl, rack, ctx, bestP[l] ?: 0.0, bestCell[l], unknownCells.size)
            held.add(HeldLetter(l, reason))
        }

        // 7) Ekran satırları
        val lines = ArrayList<String>()
        if (ctx.endgame) lines.add("Son4: kesin hesap yapılamadı (bazı hücrelerin harfi belirsiz), tahminle oynanıyor.")
        if (swap.isNotEmpty()) {
            lines.add("Torbada ≈${ctx.poolSize} harf var; ${swap.joinToString(" ")} harflerini değiştir.")
        } else if (finalPl.isEmpty()) {
            lines.add(
                if (unknownSlots.isNotEmpty()) "PAS nedeni: kelimesi bilinmeyen yuvalar var, güvenli hamle bulunamadı."
                else "PAS nedeni: artı beklentili hamle yok."
            )
        }
        for (h in held) lines.add("  ${h.letter}: ${h.reason}")
        if (unknownSlots.isNotEmpty()) {
            lines.add(
                "Kelimesi BULUNAMAYAN yuva (${unknownSlots.size}): " +
                    unknownSlots.take(4).joinToString(" ") { slotLabel(it) } +
                    (if (unknownSlots.size > 4) " …" else "") + "  [sarı noktalı hücreler]"
            )
        }
        if (conflictSlots.isNotEmpty()) {
            lines.add(
                "Adayı tahtayla ÇELİŞEN yuva (${conflictSlots.size}): " +
                    conflictSlots.take(4).joinToString(" ") { slotLabel(it) }
            )
        }

        return Plan(finalPl, if (swap.isNotEmpty()) 0.0 else bestEv, finalPl.isEmpty() && swap.isEmpty(),
            swap, held, unknownCells, lines)
    }

    /** Harf neden oynanmadı? Bilgi eksikliği ile stratejiyi ayırır. */
    private fun explainHold(
        l: Char, plan: List<Placement>, rack: List<Char>, ctx: TurnContext,
        p: Double, cell: CellPos?, unknownCount: Int
    ): String {
        if (p <= 0.0 || cell == null) {
            return if (unknownCount > 0)
                "BİLGİ YOK: kelimesi bulunamayan $unknownCount hücre var, bu harf oraya ait olabilir (strateji değil)"
            else "hiçbir aday kelimede yok (oynanamaz)"
        }
        val rc = cellLabel(cell)
        val pct = (p * 100).toInt()
        if (p < cfg.minUsefulP) return "DÜŞÜK İHTİMAL: $rc için %$pct, riske değmez"
        if (plan.any { it.cell == cell }) return "$rc hücresi başka harfle dolduruluyor, bu harf sonra"
        val baseEv = evalFull(plan, rack, ctx)
        val withIt = ArrayList<Placement>(plan)
        withIt.add(Placement(l, cell, p))
        val ev = evalFull(withIt, rack, ctx)
        val d = ev.total - baseEv.total
        val riskUp = ev.risk - baseEv.risk
        if (riskUp > 0.25) {
            val rs = "%.1f".format(riskUp)
            return "STRATEJİK SAKLA: $rc %$pct ama rakibe kelime tamamlatır (risk +$rs)"
        }
        val ds = "%.1f".format(d)
        return "STRATEJİK SAKLA: $rc %$pct ama koyarsan beklenen puan $ds"
    }
}

/**
 * Tur bağlamı yardımcısı (eski kodla uyum için tutuldu).
 */
object Phase {
    fun context(st: GameState, myMovesLeft: Int, oppMovesAfterMyMove: Int) =
        TurnContext(st.inEndgame(), myMovesLeft, oppMovesAfterMyMove)
}
