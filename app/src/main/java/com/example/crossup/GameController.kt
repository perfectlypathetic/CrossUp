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
    /** Benim bir sonraki hamlemden itibaren kalan sıra (true = ben). Çözücü bunu kullanır. */
    val seqFromMyNext: List<Boolean> get() {
        val rest = seq.drop(idx)
        val i = rest.indexOf(true)
        return if (i < 0) emptyList() else rest.drop(i)
    }
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
    val rackMissing: Int,    // tanınamayan RAF karosu sayısı
    val info: String = ""    // raf arama teşhisi
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
    /** Harfi GEVŞEK eşleşmeyle okunmuş (emin olunmayan) hücreler: bunlardan kelime öğrenilmez. */
    private val relaxedCells = HashSet<CellPos>()
    // NOT: Aşağıdaki üç önbellek yuva konumuna değil, İPUCU GÖRÜNTÜSÜNÜN parmak izine göre tutulur.
    // Izgara okuması bir an şaşsa / yeni oyun başlasa bile aynı ipucu bir daha sorulmaz / okunmaz.
    private val candCache = ConcurrentHashMap<String, List<Candidate>>()
    /** İpucu görüntüsünden okunan yazı (OCR). Sadece DOLU sonuçlar kalıcı tutulur. */
    private val ocrCache = ConcurrentHashMap<String, String>()
    /** OCR'ın yazı bulamadığı (resimli) ipuçları; sadece bu oyun için, yeni oyunda sıfırlanır. */
    private val ocrEmpty: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** AI'a bir kez sorulmuş ipuçları (görüntü parmak izi + uzunluk). */
    private val asked: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Son AI hatası (sadece "hata oldu mu" bilgisi için). */
    @Volatile private var lastAiError = ""
    /** Bu analizde TDK ile çözülen ipucu sayısı. */
    private var tdkHits = 0
    /** Teşhis satırları (DEBUG_TDK açıkken). */
    private val tdkDebug = ArrayList<String>()

    // ---------- Skor takibi (iki analiz arasındaki tahta farkından) ----------
    private var myScore = 0
    private var oppScore = 0
    private var prevFilled: Set<CellPos>? = null
    private var prevRack = ""
    private var prevPlan: Plan? = null
    private var lastSlotKeys: Set<String> = emptySet()

    fun scoreLabel(): String = "Skor: ben $myScore – rakip $oppScore"

    /** Elle skor düzeltme (rakibin yanlış harfleri gibi görünmeyen şeyler için). */
    fun adjustScore(me: Boolean, delta: Int): String {
        if (me) myScore += delta else oppScore += delta
        return scoreLabel()
    }

    /** Yeni oyun: skorları ve tur sırasını sıfırlar. */
    fun resetGame(): String {
        myScore = 0; oppScore = 0
        prevFilled = null; prevPlan = null; prevRack = ""
        turns.reset()
        return "Sıfırlandı. " + scoreLabel()
    }

    /**
     * İki analiz arasında tahtaya eklenen hücrelerden puanları günceller.
     * Raf değiştiyse ve önceki planımdaki hücreler dolduysa onlar benimdir; kalanlar rakibindir.
     * Rakibin yanlış koyduğu (geri giden) harfler görünmez, gerekirse elle düzelt.
     */
    private fun trackScores(board: ParsedBoard, st: GameState, rack: List<Char>): String {
        val filledNow: Set<CellPos> = board.cellsOf(CellKind.FILLED).toSet()
        val pf = prevFilled
        val oldPlan = prevPlan
        val rackNow = rack.sorted().joinToString("")
        var msg = ""
        if (pf != null) {
            val newCells = filledNow.filter { it !in pf }
            if (newCells.isNotEmpty()) {
                val iPlayed = oldPlan != null && oldPlan.placements.isNotEmpty() && rackNow != prevRack
                val mine = HashSet<CellPos>()
                if (iPlayed && oldPlan != null) {
                    for (pl in oldPlan.placements) {
                        if (pl.cell in newCells) {
                            val k = st.known[pl.cell]
                            if (k == null || k.uppercaseChar() == pl.letter.uppercaseChar()) mine.add(pl.cell)
                        }
                    }
                }
                val opp = HashSet<CellPos>()
                for (c in newCells) if (c !in mine) opp.add(c)
                var dMine = mine.size
                var dOpp = opp.size
                for (slot in board.slots) {
                    var touched = false
                    var oppTouched = false
                    var complete = true
                    for (c in slot.cells) {
                        if (c !in filledNow) complete = false
                        if (c in mine) touched = true
                        if (c in opp) { touched = true; oppTouched = true }
                    }
                    if (complete && touched) {
                        if (oppTouched) dOpp += slot.length else dMine += slot.length
                    }
                }
                if (mine.size >= 6) dMine += 6 else if (mine.size >= 5) dMine += 5
                if (opp.size >= 6) dOpp += 6 else if (opp.size >= 5) dOpp += 5
                myScore += dMine
                oppScore += dOpp
                msg = "son hamleler: ben +$dMine, rakip +$dOpp"
            }
        }
        prevFilled = filledNow
        prevRack = rackNow
        prevPlan = null
        return msg
    }

    /** AI'a sorulması gereken yuva. */
    private class Pending(val slot: WordSlot, val key: String, val pattern: String, val crop: Bitmap)

    private fun slotKey(s: WordSlot) = "${s.dir}${s.clueCell.r},${s.clueCell.c}:${s.length}"

    /** Önbellek anahtarı: ipucu görüntüsünün parmak izi + cevap uzunluğu. */
    private fun clueId(ckey: String, length: Int) = "$ckey#$length"

    /** Tahtayı okumadan önce night light / ekran filtresini geri alır (filtre yoksa aynen döner). */
    private fun prep(bmp: Bitmap): Bitmap = bmp.normalizeTint()

    /** Yeni oyun mu? İpucu hücrelerinin düzeni büyük ölçüde değiştiyse her şeyi sıfırla. */
    private fun syncSignature(board: ParsedBoard) {
        val keys: Set<String> = board.slots.map { slotKey(it) }.toSet()
        val sig = board.slots.joinToString("|") { slotKey(it) }
        if (sig != signature) {
            val denom = maxOf(1, maxOf(keys.size, lastSlotKeys.size))
            val overlap = keys.intersect(lastSlotKeys).size.toDouble() / denom
            val newGame = signature.isNotEmpty() && overlap < 0.5
            // Harfler konuma bağlı olduğu için yeni düzende sıfırlanır. İpucu önbellekleri
            // (görüntüye bağlı) silinmez; sadece "yazı yok" listesi temizlenir.
            signature = sig; lastSlotKeys = keys
            letterCache.clear(); relaxedCells.clear(); ocrEmpty.clear()
            if (newGame) {
                turns.reset()
                myScore = 0; oppScore = 0
                prevFilled = null; prevPlan = null; prevRack = ""
            }
        }
    }

    /**
     * HARFLERİ TANI: Sadece harf okur (raf + tahta). Tanıyamadıklarını kullanıcıya sorar.
     * AI kullanmaz, plan çıkarmaz.
     */
    suspend fun recognizeLetters(rawBmp: Bitmap, ask: AskLetter?, progress: (String) -> Unit = {}): LetterResult {
        val bmp = prep(rawBmp)
        progress("Izgara aranıyor…")
        val board = parser.parse(BitmapPixelSource(bmp)) ?: error("Izgara bulunamadı (oyun ekranı açık mı?)")
        syncSignature(board)

        val filled = board.cellsOf(CellKind.FILLED)
        val need = filled.filter { it !in letterCache }
        progress("Harfler okunuyor…")
        val reading = letters.read(bmp, board, need, ask, includeRack = true, relaxBoard = true)
        for (p in need) reading.letters[p]?.let { letterCache[p] = it }
        relaxedCells.addAll(letters.lastRelaxed)

        return LetterResult(
            rack = reading.rack,
            boardFilled = filled.size,
            boardKnown = filled.count { it in letterCache },
            unresolved = letters.lastUnresolved,
            rackMissing = letters.lastUnresolvedRack,
            info = letters.lastRackInfo
        )
    }

    /**
     * ÖĞREN: Analiz yapmaz, plan çıkarmaz, ipucu çözdürmez, rafı okumaz.
     * Sadece tahtadaki harfleri okur ve tamamen dolu yuvaları veritabanına kaydeder.
     */
    suspend fun learn(rawBmp: Bitmap, ask: AskLetter?, progress: (String) -> Unit = {}): LearnResult {
        val bmp = prep(rawBmp)
        progress("Izgara aranıyor…")
        val board = parser.parse(BitmapPixelSource(bmp)) ?: error("Izgara bulunamadı (oyun ekranı açık mı?)")
        syncSignature(board)

        val filled = board.cellsOf(CellKind.FILLED)
        // Daha önce gevşek eşleşmeyle okunmuş harfler güvenilmez: öğrenmeden önce sıkı şekilde yeniden okunur.
        for (p in filled) if (p in relaxedCells) { letterCache.remove(p); relaxedCells.remove(p) }
        val need = filled.filter { it !in letterCache }
        progress("Harfler okunuyor…")
        // Öğrenirken sıkı eşleşme: yanlış harften yanlış kelime kaydedilmesin.
        val reading = letters.read(bmp, board, need, ask, includeRack = false, relaxBoard = false)
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
    suspend fun analyze(rawBmp: Bitmap, progress: (String) -> Unit = {}): Analysis {
        val bmp = prep(rawBmp)
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
        val reading = letters.read(bmp, board, need, null, includeRack = true, relaxBoard = true)   // null: kullanıcıya sorma
        for (p in need) reading.letters[p]?.let { letterCache[p] = it }
        relaxedCells.addAll(letters.lastRelaxed)
        for (p in filled) letterCache[p]?.let { st.known[p] = it }

        // Raf tamamen okunamadıysa plan yapılamaz. Bir kısmı okunduysa kalan harflerle devam edilir
        // ("Harfleri tanı" butonuna basmak zorunlu değil).
        if (reading.rack.isEmpty())
            error("Raftaki harfler okunamadı.\n[${letters.lastRackInfo}]")
        val rackUnread = letters.lastUnresolvedRack

        // Skor takibi: önceki analizden bu yana tahtaya eklenenler.
        val scoreMsg = trackScores(board, st, reading.rack)
        st.myScore = myScore
        st.oppScore = oppScore

        val deadline = System.currentTimeMillis() + AI_TIME_LIMIT_MS

        // ÖĞRENME: tamamen dolmuş yuvaların ipucu görüntüsünü ve cevabını telefona kaydet.
        for (s in board.slots) {
            // Gevşek eşleşmeyle okunmuş (emin olunmayan) harf içeren yuvalardan öğrenme.
            if (s.cells.all { it in st.known && it !in relaxedCells }) {
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
            val pattern = s.cells.map { st.known[it] ?: '?' }.joinToString("")
            val crop = bmp.crop(s.clueRect)
            val ckey = db.key(crop)                 // ipucu görüntüsünün parmak izi (bir kez hesaplanır)
            val key = clueId(ckey, s.length)
            val local = localCands(s, key, ckey, pattern, crop)
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
        // Torba / rakip rafı tahmini: oyunda toplam harf sayısı = boş hücre sayısı.
        val emptyCount = st.emptyCells().size
        val myTiles = reading.rack.size + rackUnread
        val oppRack = if (myTiles < 6) maxOf(0, emptyCount - myTiles) else minOf(6, maxOf(0, emptyCount - myTiles))
        val pool = if (myTiles < 6) 0 else maxOf(0, emptyCount - myTiles - oppRack)

        val ctx = if (turns.endgame)
            TurnContext(true, turns.myTurnsLeft.coerceAtLeast(1), turns.oppAfterNextMine,
                myScore, oppScore, pool, oppRack, turns.seqFromMyNext)
        else
            TurnContext(false, 99, 1, myScore, oppScore, pool, oppRack, emptyList())

        val plan = Planner(st).plan(reading.rack, ctx)
        prevPlan = plan

        val solved = board.slots.count { st.emptyIn(it) == 0 || !st.cands[it].isNullOrEmpty() }

        // Ekranda gösterilecek kısa not: sadece işe yarar şeyler.
        val note = buildString {
            append(scoreLabel())
            if (scoreMsg.isNotEmpty()) append(" ($scoreMsg)")
            append("\nBoş hücre: $emptyCount · torba ≈ $pool · rakip rafı ≈ $oppRack")
            if (turns.endgame) append("\n").append(turns.label)
            if (rackUnread > 0) {
                if (isNotEmpty()) append("\n")
                append("Rafta $rackUnread karo okunamadı, kalan ${reading.rack.size} harfle planlandı.")
            }
            if (!turns.endgame && st.inEndgame()) append("\nBoş hücre azaldı: sıra kimdeyse 'Son4: ben' / 'Son4: rakip' butonuna bas (kesin hesap için gerekli).")
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
                append("[TDK teşhis] sözlük: ")
                if (tdk.ready) append("yüklü")
                else append("YÜKLÜ DEĞİL (${tdk.loadError.ifEmpty { "sebep bilinmiyor" }})")
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
    private suspend fun localCands(s: WordSlot, key: String, ckey: String, pattern: String, crop: Bitmap): List<Candidate>? {
        fun fits(w: String) = w.indices.all { pattern[it] == '?' || pattern[it] == w[it] }

        // 1) Bellek: kayıtlı adaylar, tahtadaki yeni harflere göre elenir.
        candCache[key]?.let { c ->
            val f = c.filter { fits(it.word) }
            if (f.isNotEmpty() || key in asked) return f   // AI zaten sorulduysa bir daha sorma
        }

        // 2) Telefondaki veritabanı
        val hit = db.get(ckey, s.length).filter { fits(it) }
        if (hit.isNotEmpty()) {
            if (DEBUG_TDK) tdkDebug.add("${s.length}h → kayıtlı veritabanından: ${hit.take(3).joinToString(",")}")
            return hit.map { Candidate(it, 1.0) }.also { candCache[key] = it }
        }

        // 3) TDK sözlüğü: ipucu yazısı bir kez okunur (OCR), her seferinde güncel desenle aranır.
        //    Güven düşükse / resimli ipucuysa null döner.
        if (tdk.ready && key !in asked) {
            var text = ocrCache[ckey]
            if (text == null) {
                text = if (ckey in ocrEmpty) "" else tdk.readClue(crop)
                if (text.isEmpty()) ocrEmpty.add(ckey) else ocrCache[ckey] = text
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
