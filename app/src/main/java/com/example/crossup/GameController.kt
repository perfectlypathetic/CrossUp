package com.example.crossup.game

import android.content.Context
import android.graphics.Bitmap
import com.example.crossup.ai.AskLetter
import com.example.crossup.ai.ClueDb
import com.example.crossup.ai.ClueQuery
import com.example.crossup.ai.GeminiClueSolver
import com.example.crossup.ai.LetterReader
import com.example.crossup.ai.TdkMatcher
import com.example.crossup.parser.*
import java.util.concurrent.ConcurrentHashMap

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
        /** Bir analizde AI ile ipucu çözmeye ayrılan toplam süre. */
        const val AI_TIME_LIMIT_MS = 120_000L

        /**
         * Yapay zeka şalteri. false = AI hiç kullanılmaz (kota harcanmaz, API anahtarı gerekmez).
         * Tekrar açmak istersen true yap.
         */
        const val USE_AI = false

        /**
         * TDK teşhisi. true = analiz notunda her ipucu için OCR'ın okuduğu yazı ve
         * sözlüğün neden çözdüğü / çözemediği görünür. Sorun çözülünce false yap.
         */
        const val DEBUG_TDK = true
    }

    private val parser = GridParser()
    private val solver by lazy { GeminiClueSolver(apiKey) }
    private val letters = LetterReader(ctx)
    private val db = ClueDb.get(ctx)
    private val tdk = TdkMatcher(ctx)
    val turns = TurnTracker()

    private var signature = ""
    private val letterCache = HashMap<CellPos, Char>()
    private val candCache = ConcurrentHashMap<String, List<Candidate>>()
    /** İpucu görüntüsünden okunan yazı (OCR). Oyun boyunca bir kez okunur; boş = yazı yok / resimli. */
    private val ocrCache = ConcurrentHashMap<String, String>()
    /** AI'a bir kez sorulmuş yuvalar. Oyun boyunca tekrar sorulmaz. */
    private val asked: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Son AI hatası (sadece "hata oldu mu" bilgisi için). */
    @Volatile private var lastAiError = ""
    /** Bu analizde TDK ile çözülen ipucu sayısı. */
    private var tdkHits = 0
    /** Teşhis satırları (DEBUG_TDK açıkken). */
    private val tdkDebug = ArrayList<String>()

    /** AI'a sorulması gereken yuva. */
    private class Pending(val slot: WordSlot, val key: String, val pattern: String, val crop: Bitmap)

    private fun slotKey(s: WordSlot) = "${s.dir}${s.clueCell.r},${s.clueCell.c}:${s.length}"

    /** Yeni oyun mu? İpucu hücrelerinin düzeni değiştiyse her şeyi sıfırla. */
    private fun syncSignature(board: ParsedBoard) {
        val sig = board.slots.joinToString("|") { slotKey(it) }
        if (sig != signature) {
            signature = sig; letterCache.clear(); candCache.clear(); ocrCache.clear(); asked.clear(); turns.reset()
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
     * İpuçları: önce bellek, telefondaki veritabanı ve TDK sözlüğü.
     * USE_AI açıksa kalanlar TEK TOPLU istekle AI'a sorulur; kapalıysa çözümsüz kalır.
     */
    suspend fun analyze(bmp: Bitmap, progress: (String) -> Unit = {}): Analysis {
        progress("Izgara aranıyor…")
        val board = parser.parse(BitmapPixelSource(bmp)) ?: error("Izgara bulunamadı (oyun ekranı açık mı?)")
        progress("Harfler okunuyor…")

        syncSignature(board)
        lastAiError = ""
        tdkHits = 0
        tdkDebug.clear()
        var unsolvedNoAi = 0

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
        progress("Raf: ${reading.rack.joinToString("")} — ${todo.size} ipucu kontrol ediliyor")

        // TDK sözlüğünü yükle (ilk seferde kısa sürer, sonra anında).
        if (!tdk.ready) {
            progress("TDK sözlüğü yükleniyor…")
            tdk.prepare()
        }

        // 1. aşama: bellek + telefondaki veritabanı + TDK (kotasız).
        val results = HashMap<WordSlot, List<Candidate>>()
        val pending = ArrayList<Pending>()
        for (s in todo) {
            val key = slotKey(s)
            val pattern = s.cells.map { st.known[it] ?: '?' }.joinToString("")
            val crop = bmp.crop(s.clueRect)
            val local = localCands(s, key, pattern, crop)
            if (local != null) results[s] = local else pending.add(Pending(s, key, pattern, crop))
        }
        if (tdkHits > 0) progress("TDK ile $tdkHits ipucu çözüldü, ${pending.size} ipucu kaldı")

        // 2. aşama: kalanlar (SON ÇARE: AI). Şalter kapalıysa AI'a hiç sorulmaz.
        if (pending.isNotEmpty()) {
            if (!USE_AI) {
                unsolvedNoAi = pending.size
                for (p in pending) results[p.slot] = emptyList()
            } else if (deadline - System.currentTimeMillis() < 3000) {
                lastAiError = "süre doldu"
                for (p in pending) results[p.slot] = emptyList()
            } else {
                progress("Yapay zekaya ${pending.size} ipucu toplu soruluyor…")
                val answers = solver.solveBatch(
                    pending.map { ClueQuery(it.crop.toPng(), it.slot.length, it.pattern) }, deadline
                )
                var missing = 0
                pending.forEachIndexed { i, p ->
                    val a = answers.getOrNull(i)
                    if (a != null) {
                        candCache[p.key] = a
                        asked.add(p.key)        // cevap geldi: bu oyunda bir daha sorulmaz
                        results[p.slot] = a
                    } else {
                        missing++               // asked'e eklenmez, sonraki analizde yeniden denenir
                        results[p.slot] = emptyList()
                    }
                }
                if (missing > 0) lastAiError = solver.lastError.ifEmpty { "bazı ipuçları için cevap gelmedi" }
            }
        }
        for ((s, c) in results) st.cands[s] = c

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
            if (tdkHits > 0) {
                if (isNotEmpty()) append("\n")
                append("TDK ile çözülen: $tdkHits ipucu")
            }
            if (unsolvedNoAi > 0) {
                if (isNotEmpty()) append("\n")
                append("$unsolvedNoAi ipucu çözülemedi (yapay zeka kapalı).")
            }
            if (lastAiError.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append("Yapay zeka cevap vermedi (kota dolmuş olabilir). Sonuç eksik, biraz sonra tekrar Analiz'e bas.")
            }
            if (DEBUG_TDK) {
                if (isNotEmpty()) append("\n")
                append("[TDK teşhis] sözlük: ${if (tdk.ready) "yüklü" else "YÜKLÜ DEĞİL"}")
                for (line in tdkDebug.take(8)) append("\n").append(line)
                if (tdkDebug.size > 8) append("\n… +${tdkDebug.size - 8} ipucu daha")
            }
        }
        return Analysis(board, reading.rack, plan, note, solved, board.slots.size)
    }

    /**
     * AI'sız çözüm: 1) bellek  2) telefondaki veritabanı  3) TDK sözlüğü.
     * Liste dönerse yuva çözülmüştür (boş olabilir). null dönerse AI'a sorulmalıdır.
     */
    private suspend fun localCands(s: WordSlot, key: String, pattern: String, crop: Bitmap): List<Candidate>? {
        fun fits(w: String) = w.indices.all { pattern[it] == '?' || pattern[it] == w[it] }

        // 1) Bellek: kayıtlı adaylar, tahtadaki yeni harflere göre elenir.
        candCache[key]?.let { c ->
            val f = c.filter { fits(it.word) }
            if (f.isNotEmpty() || key in asked) return f   // AI zaten sorulduysa bir daha sorma
        }

        // 2) Telefondaki veritabanı
        val hit = db.get(db.key(crop), s.length).filter { fits(it) }
        if (hit.isNotEmpty()) {
            if (DEBUG_TDK) tdkDebug.add("${s.length}h → kayıtlı veritabanından: ${hit.take(3).joinToString(",")}")
            return hit.map { Candidate(it, 1.0) }.also { candCache[key] = it }
        }

        // 3) TDK sözlüğü: ipucu yazısı bir kez okunur (OCR), her seferinde güncel desenle aranır.
        //    Güven düşükse / resimli ipucuysa null döner.
        if (tdk.ready && key !in asked) {
            var text = ocrCache[key]
            if (text == null) {
                text = tdk.readClue(crop)
                ocrCache[key] = text
            }
            val t = tdk.candidates(text, s.length, pattern)
            if (DEBUG_TDK) tdkDebug.add("${s.length}h \"${text.take(40)}\" → ${tdk.lastReason}")
            if (t != null) {
                tdkHits++
                return t
            }
        } else if (DEBUG_TDK && !tdk.ready) {
            tdkDebug.add("${s.length}h → sözlük yüklü değil")
        }

        // 4) AI zaten sorulduysa tekrar sorma.
        if (key in asked) return emptyList()
        return null
    }
}
