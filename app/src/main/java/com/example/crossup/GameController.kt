package com.example.crossup.game

import android.content.Context
import android.graphics.Bitmap
import com.example.crossup.ai.ClueDb
import com.example.crossup.ai.GeminiBoardReader
import com.example.crossup.ai.GeminiClueSolver
import com.example.crossup.parser.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

class GameController(apiKey: String, ctx: Context) {
    private val parser = GridParser()
    private val solver = GeminiClueSolver(apiKey)
    private val reader = GeminiBoardReader(apiKey)
    private val db = ClueDb(ctx)
    val turns = TurnTracker()

    private var signature = ""
    private val letterCache = HashMap<CellPos, Char>()
    private val candCache = ConcurrentHashMap<WordSlot, List<Candidate>>()

    suspend fun analyze(bmp: Bitmap, progress: (String) -> Unit = {}): Analysis {
        // Yapay zekaya ayrılan toplam süre sınırı (ms). Bu süreden sonra yeni ipucu sorulmaz.
        val deadline = System.currentTimeMillis() + 30_000

        progress("Izgara aranıyor…")
        val board = parser.parse(BitmapPixelSource(bmp)) ?: error("Izgara bulunamadı (oyun ekranı açık mı?)")
        progress("Izgara ${board.rows}x${board.cols}, ${board.slots.size} yuva. Harfler okunuyor…")

        // Yeni oyun mu? İpucu hücrelerinin düzeni değiştiyse her şeyi sıfırla.
        val sig = board.slots.joinToString("|") { "${it.dir}${it.clueCell.r},${it.clueCell.c}:${it.length}" }
        if (sig != signature) { signature = sig; letterCache.clear(); candCache.clear(); turns.reset() }

        val st = GameState(board)
        val filled = board.cellsOf(CellKind.FILLED)
        val need = filled.filter { it !in letterCache }
        val jpeg = bmp.toJpeg()
        var reading = reader.read(jpeg, board, need)
        if (reading.rack.isEmpty()) {
            progress("Raf okunamadı, tekrar deneniyor…")
            reading = reader.read(jpeg, board, need)
        }
        for (p in need) reading.letters[p]?.let { letterCache[p] = it }
        for (p in filled) letterCache[p]?.let { st.known[p] = it }
        if (reading.rack.isEmpty()) error("Raftaki harfler okunamadı")

        // ÖĞRENME: tamamen dolmuş yuvaların ipucu görüntüsünü ve cevabını telefona kaydet.
        for (s in board.slots) {
            if (s.cells.all { it in st.known }) {
                db.put(db.key(bmp.crop(s.clueRect)), s.cells.map { st.known[it]!! }.joinToString(""))
            }
        }

        // İpucu çözümü: sadece henüz bitmemiş yuvalar, en fazla 8 paralel istek.
        val todo = board.slots.filter { st.emptyIn(it) > 0 }
        val done = AtomicInteger(0)
        progress("Raf: ${reading.rack.joinToString("")} — ipuçları çözülüyor 0/${todo.size}")
        val sem = Semaphore(8)
        val results = coroutineScope {
            todo.map { s ->
                async {
                    sem.withPermit {
                        val c = candsFor(s, st, bmp, deadline)
                        progress("İpuçları çözülüyor ${done.incrementAndGet()}/${todo.size}")
                        s to c
                    }
                }
            }.awaitAll()
        }
        for ((s, c) in results) st.cands[s] = c      // tek thread'de yaz

        progress("Hamle planlanıyor…")
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

    private suspend fun candsFor(s: WordSlot, st: GameState, bmp: Bitmap, deadline: Long): List<Candidate> {
        val pattern = s.cells.map { st.known[it] ?: '?' }.joinToString("")
        fun fits(w: String) = w.indices.all { pattern[it] == '?' || pattern[it] == w[it] }
        candCache[s]?.let { c -> if (c.any { fits(it.word) }) return c }

        val crop = bmp.crop(s.clueRect)

        // 1) Önce telefondaki veritabanına bak (anında).
        val hit = db.get(db.key(crop), s.length).filter { fits(it) }
        if (hit.isNotEmpty()) return hit.map { Candidate(it, 1.0) }.also { candCache[s] = it }

        // 2) Yoksa yapay zekaya sor, ama süre bittiyse atla.
        val left = deadline - System.currentTimeMillis()
        if (left < 3000) return emptyList()
        return withTimeoutOrNull(left) {
            try {
                solver.solve(crop.toPng(), s.length, pattern).also { candCache[s] = it }
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }
}
