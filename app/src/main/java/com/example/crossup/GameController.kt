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

    /** Sırayı bir adım ilerletir. Son 4 başlamadıysa veya bittiyse false döner. */
    fun advance(): Boolean {
        if (!endgame || idx >= seq.size) return false
        idx++
        return true
    }

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
    val note: String,
    val solvedSlots: Int = 0,
    val totalSlots: Int = 0
)

/** "Öğren" butonunun sonucu. */
class LearnResult(
    val fullSlots: Int,      // tamamen dolu yuva sayısı
    val added: Int,          // veritabanına yeni eklenen kelime sayısı
    val unreadCells: Int,    // harfi okunamayan dolu hücre sayısı
    val total: Int           // veritabanındaki toplam kelime
)

/** "Harfleri tanı" butonunun sonucu. */
class LetterResult(
    val rack: List<Char>,    // okunan raf
    val boardFilled: Int,    // tahtadaki dolu hücre sayısı
    val boardKnown: Int,     // bunlardan harfi bilinenler
    val unresolved: Int,     // tanınamayan karo sayısı (raf + tahta)
    val rackMissing: Int     // tanınamayan RAF karosu sayısı
)

class GameController(apiKey: String, ctx: Context) {
    companion object {
        /** Üst üste bu kadar AI hatasında o analizde AI'a sormayı bırak (kota/ağ sorunu). */
        const val MAX_AI_FAILS = 5
        /** Bir analizde AI ile ipucu çözmeye ayrılan toplam süre. */
        const val AI_TIME_LIMIT_MS = 120_000L
        /** Aynı anda en fazla bu kadar AI isteği (kota hatasını önlemek için düşük tutulur). */
        const val AI_PARALLEL = 4
    }

    private val parser = GridParser()
    private val solver = GeminiClueSolver(apiKey)
    private val letters = LetterReader(ctx)
    private val db = ClueDb.get(ctx)
    val turns = TurnTracker()

    private var signature = ""
    private val letterCache = HashMap<CellPos, Char>()
    private val candCache = ConcurrentHashMap<String, List<Candidate>>()
    /** AI'a bir kez sorulmuş yuvalar. Oyun boyunca tekrar sorulmaz. */
    private val asked: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Son AI hatası (sadece "hata oldu mu" bilgisi için). */
    @Volatile private var lastAiError = ""

    private fun slotKey(s: WordSlot) = "${s.dir}${s.clueCell.r},${s.clueCell.c}:${s.length}"

    /** Yeni oyun mu? İpucu hücrelerinin düzeni değiştiyse her şeyi sıfırla. */
    private fun syncSignature(board: ParsedBoard) {
        val sig = board.slots.joinToString("|") { slotKey(it) }
        if (sig != signature) {
            signature = sig; letterCache.clear(); candCache.clear(); asked.clear(); turns.reset()
        }
    }

    /**
     * HARFLERİ TANI: Sadece harf okur (raf + tahta). Tanıyamadıklarını kullanıcıya sorar.
     * AI kullanmaz, plan çıkarmaz.
     */
    suspend fun recognizeLetters(bmp: Bitmap, ask: AskLetter?, progress: (String) -> Unit = {}): LetterResult {
        progress("Izgara aranıyor…")
        val board = parser.parse(BitmapPixelSource(bmp)) ?: error("Izgara bulunamadı (oyun ekranı açık mı?)")
        syncSignature(board)

        val filled = board.cellsOf(CellKind.FILLED)
        val need = filled.filter { it !in letterCache }
        progress("Harfler okunuyor…")
        val reading = letters.read(bmp, board, need, ask, includeRack = true)
        for (p in need) reading.letters[p]?.let { letterCache[p] = it }

        return LetterResult(
            rack = reading.rack,
            boardFilled = filled.size,
            boardKnown = filled.count { it in letterCache },
            unresolved = letters.lastUnresolved,
            rackMissing = letters.lastUnresolvedRack
        )
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

    /**
     * ANALİZ: Harf sormaz (sadece kayıtlı şablonlarla okur).
     * Sadece RAFTAKİ harfler okunamazsa durur. Tahtadaki tanınmayan hücreler yok sayılır.
     * İpuçları oyun boyunca en fazla bir kez AI'a sorulur.
     */
    suspend fun analyze(bmp: Bitmap, progress: (String) -> Unit = {}): Analysis {
        progress("Izgara aranıyor…")
        val board = parser.parse(BitmapPixelSource(bmp)) ?: error("Izgara bulunamadı (oyun ekranı açık mı?)")
        progress("Harfler okunuyor…")

        syncSignature(board)
        lastAiError = ""

        val st = GameState(board)
        val filled = board.cellsOf(CellKind.FILLED)
        val need = filled.filter { it !in letterCache }
        val reading = letters.read(bmp, board, need, null, includeRack = true)   // null: kullanıcıya sorma
        for (p in need) reading.letters[p]?.let { letterCache[p] = it }
        for (p in filled) letterCache[p]?.let { st.known[p] = it }

        if (letters.lastUnresolvedRack > 0)
            error("Raftaki ${letters.lastUnresolvedRack} harf tanınamadı. 'Harfleri tanı' butonuna bas, sonra Analiz.")
        if (reading.rack.isEmpty())
            error("Raftaki harfler okunamadı. 'Harfleri tanı' butonuna bas, sonra Analiz.")

        val deadline = System.currentTimeMillis() + AI_TIME_LIMIT_MS

        // ÖĞRENME: tamamen dolmuş yuvaların ipucu görüntüsünü ve cevabını telefona kaydet.
        for (s in board.slots) {
            if (s.cells.all { it in st.known }) {
                db.put(db.key(bmp.crop(s.clueRect)), s.cells.map { st.known[it]!! }.joinToString(""))
            }
        }

        // İpucu çözümü: sadece henüz bitmemiş yuvalar.
        val todo = board.slots.filter { st.emptyIn(it) > 0 }.sortedBy { st.emptyIn(it) }
        val done = AtomicInteger(0)
        val dbHits = AtomicInteger(0)
        val aiCalls = AtomicInteger(0)
        val aiFails = AtomicInteger(0)
        progress("Raf: ${reading.rack.joinToString("")} — ipuçları çözülüyor 0/${todo.size}")
        val sem = Semaphore(AI_PARALLEL)
        val results = coroutineScope {
            todo.map { s ->
                async {
                    sem.withPermit {
                        val c = candsFor(s, st, bmp, deadline, dbHits, aiCalls, aiFails)
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

        val solved = board.slots.count { st.emptyIn(it) == 0 || !st.cands[it].isNullOrEmpty() }

        // Ekranda gösterilecek kısa not: sadece işe yarar şeyler.
        val note = buildString {
            if (turns.endgame) append(turns.label)
            if (!turns.endgame && st.inEndgame()) append("Boş hücre azaldı: 'Son4' butonlarından birine bas.")
            if (turns.endgame && !turns.nextIsMine && !turns.finished) append(" (rakibin hamlesini bekle)")
            if (lastAiError.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("Yapay zeka cevap vermedi (kota dolmuş olabilir). Sonuç eksik, biraz sonra tekrar Analiz'e bas.")
            }
        }
        return Analysis(board, reading.rack, plan, note, solved, board.slots.size)
    }

    /**
     * Sıra: 1) bellek  2) telefondaki veritabanı  3) yapay zeka (SON ÇARE, yuva başına en fazla BİR KEZ).
     */
    private suspend fun candsFor(
        s: WordSlot, st: GameState, bmp: Bitmap, deadline: Long,
        dbHits: AtomicInteger, aiCalls: AtomicInteger, aiFails: AtomicInteger
    ): List<Candidate> {
        val key = slotKey(s)
        val pattern = s.cells.map { st.known[it] ?: '?' }.joinToString("")
        fun fits(w: String) = w.indices.all { pattern[it] == '?' || pattern[it] == w[it] }

        // 1) Bellek: kayıtlı adaylar, tahtadaki yeni harflere göre elenir.
        candCache[key]?.let { c ->
            val f = c.filter { fits(it.word) }
            if (f.isNotEmpty() || key in asked) return f   // AI zaten sorulduysa bir daha sorma
        }

        val crop = bmp.crop(s.clueRect)

        // 2) Telefondaki veritabanı (anında, kotasız)
        val hit = db.get(db.key(crop), s.length).filter { fits(it) }
        if (hit.isNotEmpty()) {
            dbHits.incrementAndGet()
            return hit.map { Candidate(it, 1.0) }.also { candCache[key] = it }
        }

        // 3) Yapay zeka: bu yuva için hiç sorulmadıysa
        if (key in asked) return emptyList()
        if (aiFails.get() >= MAX_AI_FAILS) return emptyList()   // kota/ağ sorunu: israf etme, sonraki analizde dener
        val left = deadline - System.currentTimeMillis()
        if (left < 3000) return emptyList()

        return withTimeoutOrNull(left) {
            try {
                aiCalls.incrementAndGet()
                solver.solve(crop.toPng(), s.length, pattern).also {
                    candCache[key] = it
                    asked.add(key)          // başarılı: bu oyunda bir daha sorulmaz
                    aiFails.set(0)
                }
            } catch (e: Exception) {
                aiFails.incrementAndGet()   // başarısız: asked'e eklenmez, sonraki analizde yeniden denenir
                lastAiError = (e.message ?: e.javaClass.simpleName).take(160)
                emptyList()
            }
        } ?: emptyList()
    }
}
