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
    }

    private val parser = GridParser()
    private val solver = GeminiClueSolver(apiKey)
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
     * ÖĞREN: Analiz yapmaz, plan çıkarmaz, ipucu çözdürmez, rafı
