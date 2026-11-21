package com.example.crossup.game

import android.content.Context
import android.graphics.Bitmap
import com.example.crossup.ai.AskLetter
import com.example.crossup.ai.ClueDb
import com.example.crossup.ai.GeminiClueSolver
import com.example.crossup.ai.LetterReader
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

/** "Öğren" butonunun sonucu. */
class LearnResult(
    val fullSlots: Int,      // tamamen dolu yuva sayısı
    val added: Int,          // veritabanına yeni eklenen kelime sayısı
    val unreadCells: Int,    // harfi okunamayan dolu hücre sayısı
    val total: Int           // veritabanındaki toplam kelime
)

class GameController(apiKey: String, ctx: Context) {
    companion object {
        /** Bir analizde yapay zekaya en fazla kaç ipucu sorulsun (kota koruması). */
        const val MAX_AI_PER_ANALYSIS = 12
        /** Üst üste bu kadar AI hatasında o analizde AI'a sormayı bırak. */
        const val MAX_AI_FAILS = 2
    }

    private val parser = GridParser()
    private val solver = GeminiClueSolver(apiKey)
    private val letters = LetterReader(ctx)
    private val db = ClueDb.get(ctx)
    val turns = TurnTracker()

    private var signature = ""
    private val letterCache = HashMap<CellPos, Char>()
    private val candCache = ConcurrentHashMap<WordSlot, List<Candidate>>()
    /** Bu yuva için AI'a en son hangi desenle soruldu. Aynı desenle tekrar sorulmaz. */
    private val asked = ConcurrentHashMap<WordSlot, String>()

    /** Yeni oyun mu? İpucu hücrelerinin düzeni değiştiyse her şeyi sıfırla. */
    private fun syncSignature(board: ParsedBoard) {
        val sig = board.slots.joinToString("|") { "${it.dir}${it.clueCell.r},${it.clueCell.c}:${it.length}" }
        if (sig != signature) {
            signature = sig; letterCache.clear(); candCache.clear(); asked.clear(); turns.reset()
        }
    }

    /**
     * ÖĞREN: Analiz yapmaz, plan çıkarmaz, ipucu çözdürmez, rafı okumaz.
     * Sadece tahtadaki harfleri okur ve tamamen dolu yuvaları veritabanına kaydeder.
     */
    suspend fun learn(bmp: Bitmap, ask: AskLetter?, progress: (String) -> Unit = {}): LearnResult {
        progress("Izgara aranıyor…")
        val board = parser.parse(BitmapPixelSource(bmp)) ?: error("Izgara bulunamadı (oyun ekranı açık mı?)")
        syncSignature(board)

        val filled = board.cellsOf(CellKind.FILLED)
        val need = filled.filter { it !in letterCache }
        progress("Harfler okunuyor…")
        val reading = letters.read(bmp, board, need, ask, includeRack = false)
        for (p in need) reading.letters[p]?.let { letterCache[p] = it }

        val known = HashMap<CellPos, Char>()
        for (p in filled) letterCache[p]?.let { known[p] = it }

        progress("Veritabanına kaydediliyor…")
        val before = db.size()
        var full = 0
        for (s in board.slots) {
            if (s.cells.all { it in known }) {
                full++
                db.put(db.key(bmp.crop(s.clueRect)), s.cells.map { known[it]!! }.joinToString(""))
            }
        }
        val after = db.size()
        val unread = filled.count { it !in known }
        return LearnResult(full, after - before, unread, after)
    }

    suspend fun analyze(bmp: Bitmap, ask: AskLetter?, progress: (String) -> Unit = {}): Analysis {
        progress("Izgara aranıyor…")
        val board = parser.parse(BitmapPixelSource(bmp)) ?: error("Izgara bulunamadı (oyun ekranı açık mı?)")
        progress("Izgara ${board.rows}x${board.cols}, ${board.slots.size} yuva. Harfler okunuyor…")

        syncSignature(board)

        val st = GameState(board)
        val filled = board.cellsOf(CellKind.FILLED)
        val need = filled.filter { it !in letterCache }
        val reading = letters.read(bmp, board, need, ask, includeRack = true)
        for (p in need) reading.letters[p]?.let { letterCache[p] = it }
        for (p in filled) letterCache[p]?.let { st.known[p] = it }
        if (reading.rack.isEmpty()) error("Raftaki harfler okunamadı (raf bulunamadı veya harfler atlandı)")

        // Yapay zekaya ayrılan süre sınırı: harf okuma (kullanıcı cevabı dahil) bittikten sonra başlar.
        val deadline = System.currentTimeMillis() + 30_000

        // ÖĞRENME: tamamen dolmuş yuvaların ipucu görüntüsünü ve cevabını telefona kaydet.
        for (s in board.slots) {
            if (s.cells.all { it in st.known }) {
                db.put(db.key(bmp.crop(s.clueRect)), s.cells.map { st.known[it]!! }.joinToString(""))
            }
        }

        // İpucu çözümü: sadece henüz bitmemiş yuvalar. Harfi çok dolu (bilgisi değerli) yuvalar önce.
        val todo = board.slots.filter { st.emptyIn(it) > 0 }.sortedBy { st.emptyIn(it) }
        val done = AtomicInteger(0)
        val dbHits = AtomicInteger(0)
        val aiCalls = AtomicInteger(0)
        val aiFails = AtomicInteger(0)
        val aiBudget = AtomicInteger(MAX_AI_PER_ANALYSIS)
        progress("Raf: ${reading.rack.joinToString("")} — ipuçları çözülüyor 0/${todo.size}")
        val sem = Semaphore(8)
        val results = coroutineScope {
            todo.map { s ->
                async {
                    sem.withPermit {
                        val c = candsFor(s, st, bmp, deadline, dbHits, aiCalls, aiFails, aiBudget)
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
            append("\nDB:${dbHits.get()} AI:${aiCalls.get()} (kayıtlı: ${db.size()})")
        }
        return Analysis(board, reading.rack, plan, note)
    }

    /**
     * Sıra: 1) bellek  2) telefondaki veritabanı  3) yapay zeka (SON ÇARE).
     */
    private suspend fun candsFor(
        s: WordSlot, st: GameState, bmp: Bitmap, deadline: Long,
        dbHits: AtomicInteger, aiCalls: AtomicInteger, aiFails: AtomicInteger, aiBudget: AtomicInteger
    ): List<Candidate> {
        val pattern = s.cells.map { st.known[it] ?: '?' }.joinToString("")
        fun fits(w: String) = w.indices.all { pattern[it] == '?' || pattern[it] == w[it] }

        // 1) Bellek
        candCache[s]?.let { c -> if (c.any { fits(it.word) }) return c }

        val crop = bmp.crop(s.clueRect)

        // 2) Telefondaki veritabanı (anında, kotasız)
        val hit = db.get(db.key(crop), s.length).filter { fits(it) }
        if (hit.isNotEmpty()) {
            dbHits.incrementAndGet()
            return hit.map { Candidate(it, 1.0) }.also { candCache[s] = it }
        }

        // 3) Yapay zeka: sadece gerçekten gerekiyorsa
        if (asked[s] == pattern) return emptyList()          // aynı soruyu zaten sorduk, yeni bilgi yok
        if (aiFails.get() >= MAX_AI_FAILS) return emptyList() // kota/ağ sorunu: israf etme
        val left = deadline - System.currentTimeMillis()
        if (left < 3000) return emptyList()
        if (aiBudget.decrementAndGet() < 0) return emptyList() // bu analizin AI hakkı doldu, sonraki analizde sorulur

        return withTimeoutOrNull(left) {
            try {
                aiCalls.incrementAndGet()
                solver.solve(crop.toPng(), s.length, pattern).also {
                    candCache[s] = it
                    asked[s] = pattern
                    aiFails.set(0)
                }
            } catch (e: Exception) {
                aiFails.incrementAndGet()
                emptyList()
            }
        } ?: emptyList()
    }
}
