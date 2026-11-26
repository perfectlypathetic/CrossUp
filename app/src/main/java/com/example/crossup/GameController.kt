package com.example.crossup.game

import android.content.Context
import android.graphics.Bitmap
import com.example.crossup.GameLog
import com.example.crossup.ai.AskLetter
import com.example.crossup.ai.ClueDb
import com.example.crossup.ai.ClueQuery
import com.example.crossup.ai.GeminiClueSolver
import com.example.crossup.ai.LetterReader
import com.example.crossup.ai.LetterDb
import com.example.crossup.ai.TdkMatcher
import com.example.crossup.parser.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Kullanıcının kalemle yazdığı NOTLAR (tahmin harfleri). Oyunun kendisine yazılmaz, sadece
 * ekranda bizim katmanda görünür. Analiz bunları "kullanıcının tahmini" diye kullanır.
 */
class NoteBook {
    private val m = ConcurrentHashMap<CellPos, Char>()
    val size get() = m.size
    fun isEmpty() = m.isEmpty()
    fun get(p: CellPos): Char? = m[p]
    fun put(p: CellPos, ch: Char) { m[p] = ch }
    fun remove(p: CellPos) { m.remove(p) }
    fun clear() { m.clear() }
    fun snapshot(): Map<CellPos, Char> = HashMap(m)

    /** Artık boş olmayan (oyunda dolmuş) veya tahta dışında kalan hücrelerin notlarını siler. */
    fun prune(board: ParsedBoard): Int {
        var n = 0
        for (p in m.keys.toList()) {
            val ok = p.r in 0 until board.rows && p.c in 0 until board.cols &&
                board.kinds[p.r][p.c] == CellKind.EMPTY
            if (!ok) { m.remove(p); n++ }
        }
        return n
    }
}

/** Son 4 tur sırası. Kullanıcı butonla başlatır; her turdan sonra "ilerle" ile bir adım atlanır. */
class TurnTracker {
    var endgame = false; private set
    private var seq: List<Boolean> = emptyList()   // true = ben
    private var idx = 0

    fun start(meFirst: Boolean) {
        seq = if (meFirst) listOf(true, false, true, false) else listOf(false, true, false, true)
        idx = 0; endgame = true
        GameLog.i("Turns", "Son4 başlatıldı: ilk sıra ${if (meFirst) "BENDE" else "RAKİPTE"} → sıra=${seq.joinToString("") { if (it) "B" else "R" }}")
    }

    /** Sırayı bir adım ilerletir. Son 4 başlamadıysa veya bittiyse false döner. */
    fun advance(): Boolean {
        if (!endgame || idx >= seq.size) return false
        idx++
        GameLog.i("Turns", "sıra ilerletildi → $label")
        return true
    }

    fun reset() { endgame = false; seq = emptyList(); idx = 0; GameLog.i("Turns", "tur takibi sıfırlandı") }

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

        /** Notla uyuşan adayların ağırlığı bununla çarpılır (kullanıcının tahmini = güçlü ipucu, kesin değil). */
        const val NOTE_BOOST = 3.0
    }

    private val parser = GridParser()
    private val solver by lazy { GeminiClueSolver(apiKey) }
    private val letters = LetterReader(ctx)
    private val db = ClueDb.get(ctx)
    private val tdk = TdkMatcher(ctx)
    val turns = TurnTracker()

    /** Kullanıcının kalem notları (tahmin harfleri). */
    val notes = NoteBook()
    /** false ise notlar ekranda kalır ama analizde KULLANILMAZ. */
    @Volatile var useNotes = true

    /** Okunmuş (tahtada duran) harf; bilinmiyorsa null. Not klavyesi kelime desenini göstermek için kullanır. */
    fun letterAt(p: CellPos): Char? = letterCache[p]

        init {
            GameLog.i("Game", "GameController oluşturuldu: USE_AI=$USE_AI DEBUG_TDK=$DEBUG_TDK apiAnahtarı=${if (apiKey.isBlank()) "yok" else "var"} | kayıtlı kelime=${db.size()} | harf şablonu=${LetterDb.get(ctx).size()} | TDK yüklü=${tdk.ready}")
        }

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

    // ---------- Rakibin eli takibi ----------
    // KURAL: oynayan kişiye harfler hemen tamamlanmaz. Rakip oynadıktan sonra elinde (6 - oynadığı)
    // harf KALIR; sırası tekrar gelince eksikler torbadan rastgele verilir.
    /** Rakibin son turda tahtaya koyduğu harf sayısı. -1 = bilinmiyor (ilk analiz). */
    private var oppLastPlayed = -1
    /** Kullanıcının "Rkp değiştirdi" tuşuyla bildirdiği, bir sonraki Analiz'de kullanılacak sayı. */
    private var oppSwapPending = 0
    /** Son turda rakibin değiştirdiği harf sayısı (Analiz'de uygulanmış hali). */
    private var oppSwapApplied = 0
    /** Bu analizde yeni bir tur algılandı mı (tahtaya harf eklendi veya raf değişti). */
    private var turnChanged = false
    /** Bu analizde rakibin tahtaya koyduğu harf sayısı (rakip harf koyduysa değiştirmiş olamaz: ya koyar ya değiştirir). */
    private var oppCellsNow = 0
    private var runNo = 0

    /**
     * Rakip son turunda harf değiştirdiyse (tahtada görünmez) her basışta +1; 6'dan sonra sıfırlanır.
     * KURAL: harf değiştirmek o turun hamlesidir; rakip değiştirdiyse harf koymamıştır, sıra bana geçmiştir.
     */
    fun addOppSwap(): String {
        oppSwapPending = if (oppSwapPending >= 6) 0 else oppSwapPending + 1
        GameLog.i("Game", "BUTON etkisi: rakip harf değiştirdi → bekleyen değişim=$oppSwapPending (bir sonraki Analiz'de uygulanır)")
        return if (oppSwapPending == 0) "Rakip harf değiştirmedi olarak işaretlendi."
        else "Rakip son turda $oppSwapPending harf değiştirdi (hamlesi buydu, harf koymadı). (Şimdi Analiz'e bas.)"
    }

    fun scoreLabel(): String = "Skor: ben $myScore – rakip $oppScore"

    /** Elle skor düzeltme (rakibin yanlış harfleri gibi görünmeyen şeyler için). */
    fun adjustScore(me: Boolean, delta: Int): String {
        if (me) myScore += delta else oppScore += delta
        GameLog.i("Score", "elle skor düzeltme: ${if (me) "ben" else "rakip"} ${if (delta > 0) "+" else ""}$delta → ${scoreLabel()}")
        return scoreLabel()
    }

    /** Yeni oyun: skorları ve tur sırasını sıfırlar. */
    fun resetGame(): String {
        GameLog.section("YENİ OYUN (elle sıfırlama)")
        GameLog.i("Game", "sıfırlanmadan önce: ${scoreLabel()} | ${turns.label}")
        myScore = 0; oppScore = 0
        prevFilled = null; prevPlan = null; prevRack = ""
        oppLastPlayed = -1; oppSwapPending = 0; oppSwapApplied = 0
        turns.reset()
        notes.clear()
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
        turnChanged = false
        oppCellsNow = 0
        GameLog.i("Score", "skor takibi: önceki doluluk=${pf?.size ?: "yok (ilk analiz)"} şimdi=${filledNow.size} raf=$rackNow önceki raf=${prevRack.ifEmpty { "yok" }} önceki plan=${oldPlan ?: "yok"}")
        if (pf != null) {
            val newCells = filledNow.filter { it !in pf }
            GameLog.i("Score", "yeni dolan hücreler (${newCells.size}): ${newCells.joinToString(" ") { "(${it.r + 1},${it.c + 1})" }}")
            if (newCells.isNotEmpty()) {
                val iPlayed = oldPlan != null && oldPlan.placements.isNotEmpty() && rackNow != prevRack
                GameLog.i("Score", "ben oynadım mı=$iPlayed (önceki plan yerleştirme=${oldPlan?.placements?.size ?: 0}, raf değişti=${rackNow != prevRack})")
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
                GameLog.i("Score", "hücre sahipliği: benim=[${mine.joinToString(" ") { "(${it.r + 1},${it.c + 1})" }}] rakibin=[${opp.joinToString(" ") { "(${it.r + 1},${it.c + 1})" }}]")
                // YENİ PUANLAMA: koyulan her harf +1; bir kelimeyi bitiren hamlede o kelimedeki
                // harfler +2'şer (kelimenin tamamı için ayrıca puan yok).
                // Kelimeyi bitiren: o kelimeye rakip dokunduysa rakip, yoksa ben.
                val finMine = HashSet<CellPos>()
                val finOpp = HashSet<CellPos>()
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
                        if (oppTouched) {
                            for (c in slot.cells) if (c in opp) finOpp.add(c)
                        } else {
                            for (c in slot.cells) if (c in mine) finMine.add(c)
                        }
                        GameLog.i("Score", "yuva TAMAMLANDI ${slot.dir} ipucu(${slot.clueCell.r + 1},${slot.clueCell.c + 1}) uzunluk=${slot.length} → ${if (oppTouched) "rakip" else "ben"} bitirdi (bitiren harflerin her biri +$FINISH_LETTER_POINTS)")
                    }
                }
                var dMine = mine.size + finMine.size * (FINISH_LETTER_POINTS - 1)
                var dOpp = opp.size + finOpp.size * (FINISH_LETTER_POINTS - 1)
                if (mine.size >= 6) dMine += BONUS_6_LETTERS else if (mine.size >= 5) dMine += BONUS_5_LETTERS
                if (opp.size >= 6) dOpp += BONUS_6_LETTERS else if (opp.size >= 5) dOpp += BONUS_5_LETTERS
                myScore += dMine
                oppScore += dOpp
                msg = "son hamleler: ben +$dMine, rakip +$dOpp"
                GameLog.i("Score", "SKOR GÜNCELLENDİ: ben +$dMine (harf=${mine.size}, bitiren harf=${finMine.size}), rakip +$dOpp (harf=${opp.size}, bitiren harf=${finOpp.size}) → ben=$myScore rakip=$oppScore | rakibin son turda koyduğu harf=${opp.size}")
                oppLastPlayed = opp.size       // rakibin elinde kalan harf = 6 - bu sayı
                oppCellsNow = opp.size
                turnChanged = true
            } else if (prevRack.isNotEmpty() && rackNow != prevRack) {
                if (oldPlan != null && oldPlan.swap.isNotEmpty()) {
                    // Ben harf değiştirdim: bu benim hamlemdi, sıra rakibe geçti. Rakibin ne yaptığı henüz bilinmiyor
                    // (yeni harf yok); rakibin elini etkileyen bir şey değişmedi.
                    GameLog.i("Score", "tahtaya yeni harf yok, rafım değişti ve planım HARF DEĞİŞTİRMEKTİ → kendi değiştirmem (hamlem bitti, sıra rakipte)")
                } else {
                    // Tahtaya yeni harf gelmedi ama rafım değişti: yeni tur, rakip harf koymadı (pas/değiştirme).
                    GameLog.i("Score", "tahtaya yeni harf yok ama raf değişti → rakip harf koymadı (pas/değiştirme)")
                    oppLastPlayed = 0
                    turnChanged = true
                }
            }
        }
        GameLog.i("Score", "takip sonucu: turnChanged=$turnChanged skor ben=$myScore rakip=$oppScore rakipSonOynadığı=$oppLastPlayed")
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
    private fun prep(bmp: Bitmap): Bitmap {
        lastFilterWarn = detectFilter(bmp)
        return bmp.normalizeTint()
    }

    /** Son yakalamada renk filtresi şüphesi varsa uyarı metni (yoksa boş). */
    @Volatile private var lastFilterWarn = ""

    /**
     * Ekran renk filtresi (gece ışığı, Twilight, ekstra karartma, göz konforu, okuma modu, renk ters çevirme) tespiti.
     * Telefona bağlı değil: oyunun KENDİ renklerine bakar. Oyunun beyaz hücresi ve ipucu yeşili
     * ekranda hiç (veya çok az) bulunmuyorsa renkler değişmiş demektir.
     */
    private fun detectFilter(bmp: Bitmap): String {
        return try {
            val step = 7
            var n = 0; var white = 0; var green = 0
            var y = 0
            while (y < bmp.height) {
                var x = 0
                while (x < bmp.width) {
                    val p = bmp.getPixel(x, y)
                    val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                    n++
                    if (abs(r - 254) + abs(g - 254) + abs(b - 254) <= 24) white++
                    if (abs(r - 0xD7) + abs(g - 0xF1) + abs(b - 0xCA) <= 39) green++
                    x += step
                }
                y += step
            }
            val wf = if (n > 0) white.toDouble() / n else 0.0
            val gf = if (n > 0) green.toDouble() / n else 0.0
            val bad = wf < 0.01 && gf < 0.005
            GameLog.i("Filter", "renk kontrolü: beyaz=${GameLog.f(wf * 100)}% yeşil=${GameLog.f(gf * 100)}% → ${if (bad) "FİLTRE ŞÜPHESİ" else "temiz"}")
            if (bad) "⚠ Ekran renk filtresi açık görünüyor (gece ışığı / Twilight / ekstra karartma / göz konforu / okuma modu / renk ters çevirme). " +
                "Okuma hataları olabilir; filtreyi kapatıp tekrar dene."
            else ""
        } catch (e: Throwable) { "" }
    }

    private fun slotName(s: WordSlot) = "${if (s.dir == Dir.ACROSS) "→" else "↓"}(${s.clueCell.r + 1},${s.clueCell.c + 1})×${s.length}"

    private fun parseBoard(bmp: Bitmap): ParsedBoard {
        val t0 = System.currentTimeMillis()
        val board = parser.parse(BitmapPixelSource(bmp))
        if (board == null) {
            GameLog.e("Game", "IZGARA BULUNAMADI (ekran ${bmp.width}x${bmp.height}) - oyun ekranı açık mı?")
            error("Izgara bulunamadı (oyun ekranı açık mı?)" + (if (lastFilterWarn.isNotEmpty()) "\n" + lastFilterWarn else ""))
        }
        GameLog.i("Game", "ızgara ${System.currentTimeMillis() - t0}ms'de okundu: ${board.rows}x${board.cols}, ${board.slots.size} yuva")
        return board
    }

    private fun candsText(c: List<Candidate>?): String =
        if (c.isNullOrEmpty()) "ADAY YOK" else c.take(6).joinToString(",") { it.word + "(" + GameLog.f(it.weight) + ")" } + (if (c.size > 6) " …+${c.size - 6}" else "")

    /** Yeni oyun mu? İpucu hücrelerinin düzeni büyük ölçüde değiştiyse her şeyi sıfırla. */
    private fun syncSignature(board: ParsedBoard) {
        val keys: Set<String> = board.slots.map { slotKey(it) }.toSet()
        val sig = board.slots.joinToString("|") { slotKey(it) }
        if (sig != signature) {
            val denom = maxOf(1, maxOf(keys.size, lastSlotKeys.size))
            val overlap = keys.intersect(lastSlotKeys).size.toDouble() / denom
            val newGame = signature.isNotEmpty() && overlap < 0.5
            GameLog.i("Game", "ızgara imzası değişti: yuva=${keys.size} önceki yuva=${lastSlotKeys.size} örtüşme=${GameLog.f(overlap)} → ${if (signature.isEmpty()) "ilk okuma" else if (newGame) "YENİ OYUN algılandı: skor/tur/rakip eli sıfırlandı" else "aynı oyun (okuma farkı), harf önbelleği temizlendi"}")
            // Harfler konuma bağlı olduğu için yeni düzende sıfırlanır. İpucu önbellekleri
            // (görüntüye bağlı) silinmez; sadece "yazı yok" listesi temizlenir.
            signature = sig; lastSlotKeys = keys
            letterCache.clear(); relaxedCells.clear(); ocrEmpty.clear()
            if (newGame) {
                turns.reset()
                myScore = 0; oppScore = 0
                prevFilled = null; prevPlan = null; prevRack = ""
                oppLastPlayed = -1; oppSwapPending = 0; oppSwapApplied = 0
                notes.clear()
            }
        }
    }

    /**
     * NOT MODU için: sadece ızgarayı okur (harf okumaz, analiz yapmaz).
     * Oyunda dolmuş hücrelerin eski notlarını siler.
     */
    fun readGrid(rawBmp: Bitmap): ParsedBoard {
        GameLog.section("IZGARA OKU (not modu)")
        val bmp = prep(rawBmp)
        val board = parseBoard(bmp)
        syncSignature(board)
        val removed = notes.prune(board)
        GameLog.i("Notes", "ızgara okundu: ${board.rows}x${board.cols}, not=${notes.size} (oyunda dolduğu için silinen=$removed)")
        return board
    }

    /**
     * Kullanıcı notlarını adaylara katar:
     *  - Notla uyan aday varsa ağırlığı NOTE_BOOST ile çarpılır.
     *  - Kelime notlarla tamamen yazılmışsa ve uyan aday yoksa, kullanıcının kelimesi aday olarak eklenir.
     *  - Kısmi not sözlük adaylarıyla çelişiyorsa sözlüğe dokunulmaz (sadece sayılır).
     * Dönen metin analiz notuna eklenir.
     */
    private fun applyNotes(st: GameState, board: ParsedBoard): String {
        notes.prune(board)
        val snap = notes.snapshot()
        if (snap.isEmpty()) return ""
        if (!useNotes) {
            GameLog.i("Notes", "notlar var (${snap.size}) ama analizde KAPALI")
            return "Notların (${snap.size} harf) bu analizde kullanılmadı (kapalı)."
        }
        var touched = 0; var boosted = 0; var added = 0; var conflict = 0
        val conflictNames = ArrayList<String>()
        for (s in board.slots) {
            if (st.emptyIn(s) == 0) continue
            val basePat = s.cells.map { st.known[it] ?: '?' }
            val userPat = s.cells.mapIndexed { i, p -> if (basePat[i] != '?') basePat[i] else (snap[p] ?: '?') }
            if (userPat == basePat) continue            // bu yuvada not yok
            touched++
            fun fits(w: String): Boolean {
                val u = w.uppercase(TR)
                return u.length == userPat.size && userPat.indices.all { userPat[it] == '?' || userPat[it] == u[it] }
            }
            val cur = st.cands[s].orEmpty()
            val match = cur.filter { fits(it.word) }
            val complete = userPat.none { it == '?' }
            if (match.isNotEmpty()) {
                st.cands[s] = cur.map { if (fits(it.word)) Candidate(it.word, it.weight * NOTE_BOOST) else it }
                boosted++
                GameLog.i("Notes", "${slotName(s)} notla uyan ${match.size} aday güçlendirildi (desen=${userPat.joinToString("")})")
            } else if (complete) {
                val w = userPat.joinToString("")
                val top = cur.maxOfOrNull { it.weight } ?: 1.0
                st.cands[s] = cur + Candidate(w, top)
                added++
                GameLog.i("Notes", "${slotName(s)} senin kelimen aday olarak eklendi: $w (ağırlık=${GameLog.f(top)})")
            } else if (cur.isNotEmpty()) {
                conflict++; conflictNames.add(slotName(s))
                GameLog.i("Notes", "${slotName(s)} kısmi not (${userPat.joinToString("")}) sözlük adaylarıyla çelişiyor")
            }
        }
        return buildString {
            append("Notların: ${snap.size} harf, $touched yuvada")
            if (boosted > 0) append(" · $boosted yuvada tahminin sözlükle uyuştu (güçlendirildi)")
            if (added > 0) append(" · $added yuvada kendi kelimen aday eklendi")
            if (conflict > 0) append(" · $conflict yuvada sözlükle ÇELİŞİYOR: ${conflictNames.take(4).joinToString(" ")}")
        }
    }

    /**
     * HARFLERİ TANI: Sadece harf okur (raf + tahta). Tanıyamadıklarını kullanıcıya sorar.
     * AI kullanmaz, plan çıkarmaz.
     */
    suspend fun recognizeLetters(rawBmp: Bitmap, ask: AskLetter?, progress: (String) -> Unit = {}): LetterResult {
        GameLog.section("HARFLERİ TANI (GameController)")
        val bmp = prep(rawBmp)
        progress("Izgara aranıyor…")
        val board = parseBoard(bmp)
        syncSignature(board)

        val filled = board.cellsOf(CellKind.FILLED)
        val need = filled.filter { it !in letterCache }
        progress("Harfler okunuyor…")
        val reading = letters.read(bmp, board, need, ask, includeRack = true, relaxBoard = true)
        for (p in need) reading.letters[p]?.let { letterCache[p] = it }
        relaxedCells.addAll(letters.lastRelaxed)
        GameLog.i("Game", "tahta (okunan harflerle):\n" + board.dump(letterCache))

        val lr = LetterResult(
            rack = reading.rack,
            boardFilled = filled.size,
            boardKnown = filled.count { it in letterCache },
            unresolved = letters.lastUnresolved,
            rackMissing = letters.lastUnresolvedRack,
            info = letters.lastRackInfo
        )
        GameLog.i("Game", "HARFLERİ TANI sonucu: raf=${lr.rack.joinToString("")} rafEksik=${lr.rackMissing} tahtaDolu=${lr.boardFilled} tahtaBilinen=${lr.boardKnown} çözülemeyen=${lr.unresolved} bilgi=[${lr.info}]")
        return lr
    }

    /**
     * ÖĞREN: Analiz yapmaz, plan çıkarmaz, ipucu çözdürmez, rafı okumaz.
     * Sadece tahtadaki harfleri okur ve tamamen dolu yuvaları veritabanına kaydeder.
     */
    suspend fun learn(rawBmp: Bitmap, ask: AskLetter?, progress: (String) -> Unit = {}): LearnResult {
        GameLog.section("ÖĞREN (GameController)")
        val bmp = prep(rawBmp)
        progress("Izgara aranıyor…")
        val board = parseBoard(bmp)
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
        GameLog.i("Learn", "tahta (okunan harflerle):\n" + board.dump(known))

        progress("Veritabanına kaydediliyor…")
        val before = db.size()
        var full = 0
        for (s in board.slots) {
            if (s.cells.all { it in known }) {
                full++
                val word = s.cells.map { known[it]!! }.joinToString("")
                val ck = db.key(bmp.crop(s.clueRect))
                GameLog.d("Learn", "yuva ${slotName(s)} dolu → öğren: \"$word\" ipucu=${ClueDb.shortId(ck)}")
                db.put(ck, word)
            }
        }
        val after = db.size()
        val unread = filled.count { it !in known }
        GameLog.i("Learn", "ÖĞREN sonucu: dolu yuva=$full yeni kelime=${after - before} okunamayan hücre=$unread toplam kayıt=$after")
        return LearnResult(full, after - before, unread, after)
    }

    /**
     * ANALİZ: Harf sormaz (sadece kayıtlı şablonlarla okur).
     * Sadece RAFTAKİ harfler okunamazsa durur. Tahtadaki tanınmayan hücreler yok sayılır.
     * İpuçları: önce bellek, telefondaki veritabanı ve TDK sözlüğü.
     * USE_AI açıksa kalanlar TEK TOPLU istekle AI'a sorulur; kapalıysa çözümsüz kalır.
     */
    suspend fun analyze(rawBmp: Bitmap, progress: (String) -> Unit = {}): Analysis {
        runNo++
        val tAll = System.currentTimeMillis()
        GameLog.section("ANALİZ #$runNo (GameController)")
        GameLog.i("Game", "analiz başlıyor | ${scoreLabel()} | tur: ${turns.label} | rakipSonOynadığı=$oppLastPlayed rakipDeğiştirdi(uygulanan)=$oppSwapApplied bekleyen=$oppSwapPending | önbellek: harf=${letterCache.size} aday=${candCache.size} ocr=${ocrCache.size} | ${GameLog.mem()}")
        val bmp = prep(rawBmp)
        val filterWarn = lastFilterWarn
        progress("Izgara aranıyor…")
        val board = parseBoard(bmp)
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
        GameLog.i("Game", "tahta (okunan harflerle):\n" + board.dump(st.known))

        // Raf tamamen okunamadıysa plan yapılamaz. Bir kısmı okunduysa kalan harflerle devam edilir
        // ("Harfleri tanı" butonuna basmak zorunlu değil).
        if (reading.rack.isEmpty()) GameLog.e("Game", "RAF OKUNAMADI: ${letters.lastRackInfo}")
        if (reading.rack.isEmpty())
            error("Raftaki harfler okunamadı.\n[${letters.lastRackInfo}]")
        val rackUnread = letters.lastUnresolvedRack
        GameLog.i("Game", "raf: ${reading.rack.joinToString("")} (okunamayan raf karosu=$rackUnread) | tahtada dolu=${filled.size} harfi bilinen=${filled.count { it in st.known }} gevşek okunan=${relaxedCells.size}")

        // Skor takibi: önceki analizden bu yana tahtaya eklenenler.
        val scoreMsg = trackScores(board, st, reading.rack)
        if (oppSwapPending > 0) {
            if (oppCellsNow > 0) {
                // Rakip tahtaya harf koyduysa aynı turda harf değiştirmiş olamaz (hamle ya biri ya diğeri).
                GameLog.w("Game", "rakip $oppCellsNow harf koymuş; 'harf değiştirdi' işareti ($oppSwapPending) yok sayıldı")
                oppSwapApplied = 0
            } else {
                // Rakibin hamlesi harf değiştirmekti: tahtaya harf koymadı, sıra bana geçti.
                oppSwapApplied = oppSwapPending
                oppLastPlayed = 0
                turnChanged = true
            }
            oppSwapPending = 0
        }
        else if (turnChanged) oppSwapApplied = 0
        GameLog.d("Game", "rakip değiştirme: uygulanan=$oppSwapApplied bekleyen=$oppSwapPending turnChanged=$turnChanged")
        st.myScore = myScore
        st.oppScore = oppScore

        val deadline = System.currentTimeMillis() + AI_TIME_LIMIT_MS

        // ÖĞRENME: tamamen dolmuş yuvaların ipucu görüntüsünü ve cevabını telefona kaydet.
        for (s in board.slots) {
            // Gevşek eşleşmeyle okunmuş (emin olunmayan) harf içeren yuvalardan öğrenme.
            if (s.cells.all { it in st.known && it !in relaxedCells }) {
                val word = s.cells.map { st.known[it]!! }.joinToString("")
                val ck = db.key(bmp.crop(s.clueRect))
                GameLog.d("AutoLearn", "yuva ${slotName(s)} tamamen dolu → kaydedilecek: \"$word\" ipucu=${ClueDb.shortId(ck)}")
                db.put(ck, word)
            }
        }

        // İpucu çözümü: sadece henüz bitmemiş yuvalar.
        val todo = board.slots.filter { st.emptyIn(it) > 0 }.sortedBy { st.emptyIn(it) }
        progress("Raf: ${reading.rack.joinToString("")} — ${todo.size} ipucu kontrol ediliyor")
        GameLog.i("Game", "çözülecek yuva: ${todo.size}/${board.slots.size} (tamamen dolu: ${board.slots.size - todo.size})")

        // TDK sözlüğünü yükle (ilk seferde kısa sürer, sonra anında).
        if (!tdk.ready) {
            progress("TDK sözlüğü yükleniyor…")
            tdk.prepare()
            GameLog.i("Game", "TDK sözlüğü hazırlandı: ${tdk.ready} ${tdk.loadError}")
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
        GameLog.i("Game", "1. aşama (bellek/veritabanı/TDK): çözülen=${results.size} AI'a kalan=${pending.size} TDK ile çözülen=$tdkHits")
        if (tdkHits > 0) progress("TDK ile $tdkHits ipucu çözüldü, ${pending.size} ipucu kaldı")

        // 2. aşama: kalanlar (SON ÇARE: AI). Şalter kapalıysa AI'a hiç sorulmaz.
        if (pending.isNotEmpty()) {
            if (!USE_AI) {
                unsolvedNoAi = pending.size
                GameLog.w("Game", "AI kapalı: ${pending.size} ipucu çözümsüz bırakıldı: " + pending.joinToString(" ") { slotName(it.slot) })
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
        val noteInfo = applyNotes(st, board)
        GameLog.i("Game", "yuva adayları (nihai):\n" + board.slots.joinToString("\n") { s ->
            "  ${slotName(s)} desen=${s.cells.map { st.known[it] ?: '?' }.joinToString("")} boş=${st.emptyIn(s)} → " + (if (st.emptyIn(s) == 0) "(dolu)" else candsText(st.cands[s]))
        })

        progress("Hamle planlanıyor…")
        // Torba / rakip eli tahmini. Oyunda toplam harf sayısı = boş hücre sayısı.
        // Rakibin eli: son turda oynamadığı harfler (6 - oynadığı - değiştirdiği); sırası gelince
        // eksikleri torbadan rastgele çeker. Torba = benim dışımdaki harfler - rakibin eli.
        val emptyCount = st.emptyCells().size
        val myTiles = reading.rack.size + rackUnread
        val rest = maxOf(0, emptyCount - myTiles)                  // rakip eli + torba
        val histKnown = oppLastPlayed >= 0 || oppSwapApplied > 0
        val heldEst = if (histKnown) (6 - maxOf(0, oppLastPlayed) - oppSwapApplied).coerceIn(0, 6) else 0
        val oppHeld: Int       // rakibin şu an elinde tuttuğu harf sayısı
        val pool: Int          // torbada kalan harf sayısı
        if (myTiles < 6) {
            // Rafım dolmadıysa torba bitmiştir; kalan her harf rakipte.
            oppHeld = minOf(6, rest); pool = 0
        } else if (histKnown) {
            oppHeld = minOf(heldEst, rest); pool = rest - oppHeld
        } else {
            // Geçmiş bilinmiyor (ilk analiz): eski varsayım, rakip eli dolu sayılır.
            oppHeld = 0; pool = maxOf(0, rest - 6)
        }
        val oppFresh = minOf(6 - oppHeld, pool)                    // rakibin sırası gelince çekeceği yeni harf
        GameLog.i("Game", "torba/rakip eli tahmini: boşHücre=$emptyCount benimTaş=$myTiles (okunamayan=$rackUnread) kalan(rakipEli+torba)=$rest geçmişBilinir=$histKnown tahminiElde=$heldEst → rakipElinde=$oppHeld torba=$pool rakipYeniÇeker=$oppFresh")

        val ctx = if (turns.endgame)
            TurnContext(true, turns.myTurnsLeft.coerceAtLeast(1), turns.oppAfterNextMine,
                myScore, oppScore, pool, 6, turns.seqFromMyNext, oppHeld)
        else
            TurnContext(false, 99, 1, myScore, oppScore, pool, 6, emptyList(), oppHeld)
            GameLog.i("Game", "TurnContext: endgame=${ctx.endgame} benimKalanHamle=${ctx.myMovesLeft} sonrasıRakipHamle=${ctx.opponentMovesAfterMe} skor=${ctx.myScore}-${ctx.oppScore} torba=${ctx.poolSize} rakipElinde=${ctx.oppHeld} sıra=${ctx.seq.joinToString("") { if (it) "B" else "R" }}")

        val tPlan = System.currentTimeMillis()
        val plan = Planner(st).plan(reading.rack, ctx)
        GameLog.i("Game", "PLAN (${System.currentTimeMillis() - tPlan}ms): $plan\nsatırlar:\n" + plan.lines.joinToString("\n"))
        prevPlan = plan

        val solved = board.slots.count { st.emptyIn(it) == 0 || !st.cands[it].isNullOrEmpty() }

        // Ekranda gösterilecek kısa not: sadece işe yarar şeyler.
        val note = buildString {
            if (filterWarn.isNotEmpty()) append(filterWarn).append("\n")
            append(scoreLabel())
            if (scoreMsg.isNotEmpty()) append(" ($scoreMsg)")
            append("\nBoş hücre: $emptyCount · torba ≈ $pool")
            if (histKnown || myTiles < 6) {
                append(" · rakip elinde ≈ $oppHeld harf tutuyor, sırası gelince ≈ $oppFresh yeni harf çeker")
                if (oppSwapApplied > 0) append(" (rakip $oppSwapApplied harf değiştirmiş)")
            } else {
                append(" · rakip eli bilinmiyor (ilk analiz; bir sonraki turdan itibaren takip edilir)")
            }
            if (turns.endgame) append("\n").append(turns.label)
            if (noteInfo.isNotEmpty()) append("\n").append(noteInfo)
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
        GameLog.i("Game", "ANALİZ tamam: ${System.currentTimeMillis() - tAll}ms | çözülen yuva=$solved/${board.slots.size} | ${GameLog.mem()}\nekranda gösterilecek not:\n$note")
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
            GameLog.d("Clue", "${slotName(s)} desen=$pattern ipucu=${ClueDb.shortId(ckey)}: bellekte ${c.size} aday, desene uyan ${f.size}${if (key in asked) " (AI sorulmuş)" else ""}")
            if (f.isNotEmpty() || key in asked) return f   // AI zaten sorulduysa bir daha sorma
        }

        // 2) Telefondaki veritabanı
        val hit = db.get(ckey, s.length).filter { fits(it) }
        GameLog.d("Clue", "${slotName(s)} desen=$pattern ipucu=${ClueDb.shortId(ckey)}: veritabanı → ${if (hit.isEmpty()) "bulunamadı" else hit.joinToString(",")}")
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
            GameLog.i("Clue", "${slotName(s)} desen=$pattern ipucu=${ClueDb.shortId(ckey)} OCR=\"${text.take(60)}\" → ${if (t == null) "TDK ÇÖZEMEDİ (${tdk.lastReason})" else "TDK çözdü: " + t.take(5).joinToString(",") { it.word }}")
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



