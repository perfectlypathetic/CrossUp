package com.example.crossup.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.crossup.GameLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Onay bekleyen kelimelerin ve yarım kalmış bulmacaların KALICI deposu.
 *
 * Her şey telefonun dosyalarına yazılır (servisi kapatsan, uygulama kapansa, şarj bitse de kaybolmaz):
 *   files/pending/pending.json        -> bekleyen kelimeler / yuvalar
 *   files/pending/clue_*.png          -> ipucu resimleri (ekranda göstermek için)
 *   files/pending/shot_*.jpg          -> bulmacanın tam ekran görüntüsü
 * Bulmaca bitip (veya "Bitir" denip) iş tamamlanınca ekran görüntüsü ve ipucu resimleri SİLİNİR.
 */

/** Onay listesindeki bir yuva. word dolu = tahtadan okunmuş kelime (onay bekliyor); boş = çözülmemiş yuva. */
data class PendingItem(
    val id: String,
    var key: String,          // ClueDb parmak izi (kaydederken kullanılır)
    val length: Int,
    var pattern: String,      // tahtada görünen harfler, bilinmeyen '_'
    var word: String,         // tahtadan okunan tam kelime ("" = bilinmiyor)
    val arrow: String,        // → veya ↓
    val row: Int,             // ipucu hücresinin satırı (1'den başlar)
    val col: Int,             // ipucu hücresinin sütunu (1'den başlar)
    val clueFile: String,     // ipucu resminin dosya adı
    var typed: String = "",   // kullanıcının yarım bıraktığı yazı (kaybolmasın diye saklanır)
    var checked: Boolean = true
)

class PendingSession(
    val id: String,
    val createdAt: Long,
    var updatedAt: Long,
    var slotIds: List<String>,
    var shotFile: String,
    val items: MutableList<PendingItem> = ArrayList(),
    val dismissed: MutableSet<String> = HashSet()
)

/** Analiz/Öğren sırasında tahtadan çıkan bir aday kayıt. */
class PendingEntry(
    val id: String,
    val key: String,
    val length: Int,
    val pattern: String,
    val word: String,
    val clue: Bitmap,
    val arrow: String,
    val row: Int,
    val col: Int
)

class PendingStore private constructor(ctx: Context) {
    private val dir = File(ctx.filesDir, "pending").also { it.mkdirs() }
    private val file = File(dir, "pending.json")
    private val tmp = File(dir, "pending.json.tmp")
    private val bak = File(dir, "pending.json.bak")
    private val sessions = ArrayList<PendingSession>()

    companion object {
        private const val TAG = "Pending"
        private const val PREF = "p"
        private const val KEY_CONFIRM = "confirmWords"
        /** İki bulmacanın aynı sayılması için yuva düzeninin en az bu kadarı örtüşmeli. */
        private const val MATCH_RATIO = 0.8

        @Volatile private var inst: PendingStore? = null
        fun get(ctx: Context): PendingStore =
            inst ?: synchronized(this) {
                inst ?: PendingStore(ctx.applicationContext).also { inst = it }
            }

        /** Ayar: yeni kelimeler kaydedilmeden önce onay istensin mi? (varsayılan: evet) */
        fun confirmEnabled(ctx: Context): Boolean =
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_CONFIRM, true)

        fun setConfirmEnabled(ctx: Context, on: Boolean) {
            ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CONFIRM, on).apply()
            GameLog.i(TAG, "kelime onayı ayarı: ${if (on) "AÇIK" else "KAPALI"}")
        }

        private fun safe(id: String) = id.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
    }

    init {
        var ok = false
        if (file.exists()) {
            ok = runCatching { load(file.readText()) }.isSuccess
            if (!ok) runCatching { file.renameTo(File(dir, "pending.corrupt.${System.currentTimeMillis()}.json")) }
        }
        if (!ok && bak.exists()) runCatching { load(bak.readText()) }
        GameLog.i(TAG, "yüklendi: ${sessions.size} bekleyen bulmaca, ${sessions.sumOf { it.items.size }} yuva (ana dosya ok=$ok)")
    }

    // ---------- okuma ----------

    @Synchronized fun sessionCount(): Int = sessions.size
    @Synchronized fun itemCount(): Int = sessions.sumOf { it.items.size }

    /** Kopya döner (başka iş parçacığı değiştirse de güvenle gezilir). Eskiden yeniye sıralı. */
    @Synchronized
    fun snapshot(): List<PendingSession> = sessions.sortedBy { it.createdAt }.map { copyOf(it) }

    fun shotFile(s: PendingSession): File = File(dir, s.shotFile)

    fun clueBitmap(it: PendingItem): Bitmap? =
        runCatching { BitmapFactory.decodeFile(File(dir, it.clueFile).absolutePath) }.getOrNull()

    private fun copyOf(s: PendingSession) = PendingSession(
        s.id, s.createdAt, s.updatedAt, s.slotIds.toList(), s.shotFile,
        s.items.map { it.copy() }.toMutableList(), s.dismissed.toMutableSet()
    )

    // ---------- yazma ----------

    /**
     * Tahtadan çıkan adayları bu bulmacanın bekleme kaydına yazar (yoksa yeni kayıt açar).
     * Aynı bulmaca tekrar analiz edilince kayıt güncellenir; ekran görüntüsü en son hâliyle yenilenir.
     * Aday kalmadıysa (hepsi zaten kayıtlıysa) o bulmacanın kaydı silinir.
     * Geriye kayıttaki yuva sayısı döner.
     */
    @Synchronized
    fun record(slotIds: List<String>, entriesIn: List<PendingEntry>, shot: Bitmap?): Int {
        val m = findMatch(slotIds)
        val entries = if (m == null) entriesIn else entriesIn.filter { it.id !in m.dismissed }
        if (entries.isEmpty()) {
            if (m != null) removeSession(m)
            return 0
        }
        val now = System.currentTimeMillis()
        val s = m ?: PendingSession(now.toString(), now, now, slotIds, "").also { sessions.add(it) }
        val old = s.items.associateBy { it.id }
        val fresh = ArrayList<PendingItem>()
        for (e in entries) {
            val ex = old[e.id]
            if (ex != null) {
                ex.key = e.key; ex.pattern = e.pattern; ex.word = e.word
                if (!File(dir, ex.clueFile).exists()) writeClue(ex.clueFile, e.clue)
                fresh.add(ex)
            } else {
                val cf = "clue_${s.id}_${safe(e.id)}.png"
                writeClue(cf, e.clue)
                fresh.add(PendingItem(e.id, e.key, e.length, e.pattern, e.word, e.arrow, e.row, e.col, cf))
            }
        }
        val keepIds = fresh.map { it.id }.toSet()
        for (o in s.items) if (o.id !in keepIds) File(dir, o.clueFile).delete()
        s.items.clear(); s.items.addAll(fresh)
        s.slotIds = slotIds
        s.updatedAt = now
        if (shot != null) {
            s.shotFile = "shot_${s.id}.jpg"
            writeShot(s.shotFile, shot)
        }
        save()
        GameLog.i(TAG, "kayıt güncellendi: bulmaca=${s.id} yuva=${fresh.size} (kelime=${fresh.count { it.word.isNotEmpty() }}, çözülmemiş=${fresh.count { it.word.isEmpty() }})")
        return fresh.size
    }

    /** Bir yuvayı listeden çıkarır (kaydedildi veya atıldı). dismiss=true ise aynı bulmacada tekrar sorulmaz. */
    @Synchronized
    fun removeItem(sessionId: String, itemId: String, dismiss: Boolean) {
        val s = sessions.firstOrNull { it.id == sessionId } ?: return
        val it = s.items.firstOrNull { x -> x.id == itemId } ?: return
        File(dir, it.clueFile).delete()
        s.items.remove(it)
        if (dismiss) s.dismissed.add(itemId)
        if (s.items.isEmpty()) removeSession(s) else save()
    }

    /** Bulmacanın kaydını, ekran görüntüsünü ve ipucu resimlerini tamamen siler. */
    @Synchronized
    fun finishSession(sessionId: String) {
        val s = sessions.firstOrNull { it.id == sessionId } ?: return
        removeSession(s)
    }

    /** Kullanıcının yarım bıraktığı yazıları saklar (ekran kapansa kaybolmasın). */
    @Synchronized
    fun updateDrafts(sessionId: String, drafts: List<Triple<String, String, Boolean>>) {
        val s = sessions.firstOrNull { it.id == sessionId } ?: return
        for ((id, typed, checked) in drafts) {
            val it = s.items.firstOrNull { x -> x.id == id } ?: continue
            it.typed = typed; it.checked = checked
        }
        save()
    }

    // ---------- iç işler ----------

    private fun findMatch(slotIds: List<String>): PendingSession? {
        val mine = slotIds.toSet()
        var best: PendingSession? = null
        var bestRatio = 0.0
        for (s in sessions) {
            val theirs = s.slotIds.toSet()
            val denom = maxOf(1, maxOf(mine.size, theirs.size))
            val ratio = mine.intersect(theirs).size.toDouble() / denom
            if (ratio >= MATCH_RATIO && ratio > bestRatio) { best = s; bestRatio = ratio }
        }
        return best
    }

    private fun removeSession(s: PendingSession) {
        for (it in s.items) File(dir, it.clueFile).delete()
        if (s.shotFile.isNotEmpty()) File(dir, s.shotFile).delete()
        sessions.remove(s)
        save()
        GameLog.i(TAG, "bulmaca kaydı silindi: ${s.id} (ekran görüntüsü ve ipucu resimleri de silindi)")
    }

    private fun writeClue(name: String, bmp: Bitmap) {
        runCatching {
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { GameLog.e(TAG, "ipucu resmi yazılamadı", it) }
    }

    private fun writeShot(name: String, bmp: Bitmap) {
        runCatching {
            FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        }.onFailure { GameLog.e(TAG, "ekran görüntüsü yazılamadı", it) }
    }

    private fun load(text: String) {
        val root = JSONObject(text)
        val arr = root.getJSONArray("sessions")
        val loaded = ArrayList<PendingSession>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val slotIds = o.getJSONArray("slotIds").let { a -> (0 until a.length()).map { a.getString(it) } }
            val s = PendingSession(
                o.getString("id"), o.getLong("created"), o.getLong("updated"),
                slotIds, o.optString("shot", "")
            )
            o.optJSONArray("dismissed")?.let { a -> for (j in 0 until a.length()) s.dismissed.add(a.getString(j)) }
            val items = o.getJSONArray("items")
            for (j in 0 until items.length()) {
                val x = items.getJSONObject(j)
                s.items.add(PendingItem(
                    x.getString("id"), x.getString("key"), x.getInt("len"), x.getString("pattern"),
                    x.getString("word"), x.getString("arrow"), x.getInt("row"), x.getInt("col"),
                    x.getString("clue"), x.optString("typed", ""), x.optBoolean("checked", true)
                ))
            }
            loaded.add(s)
        }
        sessions.clear(); sessions.addAll(loaded)
    }

    private fun toJson(): String {
        val arr = JSONArray()
        for (s in sessions) {
            val o = JSONObject()
            o.put("id", s.id).put("created", s.createdAt).put("updated", s.updatedAt).put("shot", s.shotFile)
            o.put("slotIds", JSONArray(s.slotIds))
            o.put("dismissed", JSONArray(s.dismissed.toList()))
            val items = JSONArray()
            for (it in s.items) {
                items.put(JSONObject()
                    .put("id", it.id).put("key", it.key).put("len", it.length).put("pattern", it.pattern)
                    .put("word", it.word).put("arrow", it.arrow).put("row", it.row).put("col", it.col)
                    .put("clue", it.clueFile).put("typed", it.typed).put("checked", it.checked))
            }
            o.put("items", items)
            arr.put(o)
        }
        return JSONObject().put("v", 1).put("sessions", arr).toString()
    }

    private fun save() {
        runCatching {
            val json = toJson()
            tmp.writeText(json)
            if (file.exists()) runCatching { file.copyTo(bak, overwrite = true) }
            if (!tmp.renameTo(file)) file.writeText(json)
        }.onFailure { GameLog.e(TAG, "bekleme listesi kaydedilemedi", it) }
    }
}
