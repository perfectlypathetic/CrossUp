package com.example.crossup.game

import android.graphics.Bitmap
import com.example.crossup.ai.GeminiBoardReader
import com.example.crossup.ai.GeminiClueSolver
import com.example.crossup.parser.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Son 4 tur sırası. Kullanıcı butonla başlatır; her turdan sonra "ilerle" ile bir adım atlanır. */
class TurnTracker {
    var endgame = false; private set
    private var seq: List<Boolean> = emptyList()   // true = ben
    private var idx = 0

    fun start(meFirst: Boolean) {
        seq = if (meFirst) listOf(true, false, true, false) else listOf(false, true, false, true)
        idx = 0; endgame = true
    }
    fun advance() { if (idx < seq.size) idx++ }
    fun reset() { endgame = false; seq = emptyList(); idx = 0 }

    val finished get() = endgame && idx >= seq.size
    val nextIsMine get() = seq.getOrNull(idx) == true
    val myTurnsLeft get() = seq.drop(idx).count { it }
    /** Benim bir sonraki hamlemden sonra rakibin kalan hamle sayısı. */
    val oppAfterNextMine: Int get() {
        val rest = seq.drop(idx); val i = rest.indexOf(true)
        return if (i < 0) 0 else rest.drop(i + 1).count { !it }
    }
    val label get() = if (!endgame) "normal faz" else if (finished) "oyun bitti" else
        "son4: sıra ${if (nextIsMine) "BENDE" else "RAKİPTE"} (${idx + 1}/4)"
}

class Analysis(
    val board: ParsedBoard, val rack: List<Char>, val plan: Plan,
    val note: String
)

class GameController(apiKey: String) {
    private val parser = GridParser()
    private val solver = GeminiClueSolver(apiKey)
    private val reader = GeminiBoardReader(apiKey)
    val turns = TurnTracker()

    private var signature = ""
    private val letterCache = HashMap<CellPos, Char>()
    private val candCache = HashMap<WordSlot, List<Candidate>>()

    suspend fun analyze(bmp: Bitmap): Analysis {
        val board = parser.parse(BitmapPixelSource(bmp)) ?: error("Izgara bulunamadı (oyun ekranı açık mı?)")

        // Yeni oyun mu? İpucu hücrelerinin düzeni değiştiyse her şeyi sıfırla.
        val sig = board.slots.joinToString("|") { "${it.dir}${it.clueCell.r},${it.clueCell.c}:${it.length}" }
        if (sig != signature) { signature = sig; letterCache.clear(); candCache.clear(); turns.reset() }

        val st = GameState(board)
        val filled = board.cellsOf(CellKind.FILLED)
        val need = filled.filter { it !in letterCache }
        val reading = reader.read(bmp.toJpeg(), board, need)
        for (p in need) reading.letters[p]?.let { letterCache[p] = it }
        for (p in filled) letterCache[p]?.let { st.known[p] = it }
        if (reading.rack.isEmpty()) error("Raftaki harfler okunamadı")

        // İpucu çözümü: sadece henüz bitmemiş yuvalar, en fazla 4 paralel istek.
        val sem = Semaphore(4)
        coroutineScope {
            board.slots.filter { st.emptyIn(it) > 0 }.map { s ->
                async { sem.withPermit { st.cands[s] = candsFor(s, st, bmp) } }
            }.awaitAll()
        }

        val ctx = if (turns.endgame)
            TurnContext(true, turns.myTurnsLeft.coerceAtLeast(1), turns.oppAfterNextMine)
        else Phase.context(st, 99, 1).let { TurnContext(false, it.myMovesLeft, it.opponentMovesAfterMe) }

        val plan = Planner(st).plan(reading.rack, ctx)
        val note = buildString {
            append(turns.label)
            if (!turns.endgame && st.inEndgame()) append(" — boş hücre ${st.emptyCells().size}, son 4 turu başlat!")
            if (turns.endgame && !turns.nextIsMine && !turns.finished) append(" (rakibin hamlesini bekle)")
        }
        return Analysis(board, reading.rack, plan, note)
    }

    private suspend fun candsFor(s: WordSlot, st: GameState, bmp: Bitmap): List<Candidate> {
        val pattern = s.cells.map { st.known[it] ?: '?' }.joinToString("").let { it }
        fun fits(w: String) = w.indices.all { pattern[it] == '?' || pattern[it] == w[it] }
        candCache[s]?.let { c -> if (c.any { fits(it.word) }) return c }
        return try {
            solver.solve(bmp.crop(s.clueRect).toPng(), s.length, pattern).also { candCache[s] = it }
        } catch (e: Exception) { emptyList() }
    }
}
