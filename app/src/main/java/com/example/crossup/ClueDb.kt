package com.example.crossup.ai

import android.content.Context
import android.graphics.Bitmap
import com.example.crossup.GameLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * İpucu görüntüsü parmak izi -> cevap kelimeler. Telefonda kalıcı saklanır.
 * - Tek örnek (singleton): uygulama ve servis aynı veriyi kullanır.
 * - Güvenli yazma: önce .tmp'ye yaz, eskiyi .bak yap, sonra yerine koy.
 * - Bozuk dosya silinmez, "clues3.corrupt.*.json" adıyla saklanır.
 *
 * SÜRÜM 3 (parmak izi yenilendi):
 * Eski parmak izi (v2) bütün hücreyi 16x16'ya küçültüyordu. Yazılı ipuçlarında yazı hücrenin ortasında
 * küçük bir leke olduğu için FARKLI ipuçları (ör. "DEMİR SİMGESİ" ile "MİDE YARASI") aynı veya çok
 * yakın parmak izi veriyordu. Sonuç: yanlış ipucuna ait cevaplar birbirine karışıyordu
 * (tek harflilerde S,D,M gibi). Yeni parmak izi sadece YAZININ/RESMİN kapladığı kutuyu alır
 * (yazının hücre içindeki yeri önemsizleşir) ve en/boy oranını da içerir.
 * Eski (v2) kayıtlar bilerek yüklenmez; dosyası silinmeden "clues.v2-eski.json" adıyla saklanır.
 */
class ClueDb private constructor(ctx: Context) {
    private val dir = ctx.filesDir
    private val file = File(dir, "clues3.json")
    private val tmp = File(dir, "clues3.json.tmp")
    private val bak = File(dir, "clues3.json.bak")
    private val map = HashMap<String, MutableSet<String>>()

    /** Son içe aktarmada atlanan eski biçimli (v2) kayıt sayısı. */
    @Volatile var lastSkipped: Int = 0
        private set

    companion object {
        private const val N = 16
        private const val BITS = N * N
        private const val BIG = Int.MAX_VALUE / 4
        private const val BLANK = "BLANK"
        private const val TAG = "ClueDb"

        /** Bu farka kadar (256 bit + en/boy cezası) aynı ipucu sayılır. Ölçüm: aynı ipucu en çok ~16-22, farklı ipuçları en az ~26-30. */
        const val MAX_DIST = 21
        /** 1-2 harflik cevaplarda hata çok pahalı: daha sıkı. Kaçırırsa TDK/OCR devreye girer. */
        const val MAX_DIST_SHORT = 18
        /** Kayıt sırasında bu kadar yakın anahtar "aynı ipucu" sayılıp birleştirilir (daha uzaksa ayrı kayıt açılır). */
        const val MERGE_DIST = 10
        /** En yakın iki farklı kayıt arasında en az bu fark olmalı, yoksa (ve cevapları farklıysa) belirsizdir. */
        const val AMBIGUOUS_GAP = 6

        @Volatile private var inst: ClueDb? = null
        fun get(ctx: Context): ClueDb =
            inst ?: synchronized(this) {
                inst ?: ClueDb(ctx.applicationContext).also { inst = it }
            }

        private fun isValid(k: String) = k.length > BITS + 1 && k[BITS] == '@'

        /** Log için kısa ipucu kimliği: parmak izinin özeti + en/boy. */
        fun shortId(k: String): String =
            if (!isValid(k)) k else Integer.toHexString(k.hashCode()) + "@" + k.substring(BITS + 1)
    }

    init {
        // Eski (v2) dosyayı yükleme; sadece yan tarafa al.
        runCatching {
            val old = File(dir, "clues.json")
            if (old.exists()) {
                val target = File(dir, "clues.v2-eski.json")
                if (!target.exists()) old.renameTo(target) else old.delete()
            }
            File(dir, "clues.json.bak").let { if (it.exists()) it.delete() }
        }

        var ok = false
        if (file.exists()) {
            ok = runCatching { map.putAll(parse(file.readText())) }.isSuccess
            if (!ok) runCatching {
                file.renameTo(File(dir, "clues3.corrupt.${System.currentTimeMillis()}.json"))
            }
        }
        if (!ok && bak.exists()) runCatching { map.putAll(parse(bak.readText())) }
        if (file.exists() && !ok) GameLog.w(TAG, "ana dosya (clues3.json) okunamadı/bozuk, yedeğe bakıldı")
        GameLog.i(TAG, "yüklendi: ${map.size} ipucu kaydı, ${map.values.sumOf { it.size }} kelime (ana dosya ok=$ok, atlanan eski kayıt=$lastSkipped)")
    }

    private fun parse(text: String): HashMap<String, MutableSet<String>> = parseObj(JSONObject(text))

    /** Geçerli (v3) anahtarları okur; eski biçimli anahtarlar atlanır ve sayılır. */
    private fun parseObj(o: JSONObject): HashMap<String, MutableSet<String>> {
        val r = HashMap<String, MutableSet<String>>()
        var skipped = 0
        for (k in o.keys()) {
            if (!isValid(k)) { skipped++; continue }
            val a = o.getJSONArray(k)
            r[k] = (0 until a.length()).map { a.getString(it) }.toMutableSet()
        }
        lastSkipped = skipped
        return r
    }

    /**
     * Parmak izi = "<256 bit>@<en/boy*100>".
     * 1) Arka plan rengi (kenarların ortancası) bulunur, ondan belirgin farklı pikseller "mürekkep" sayılır.
     * 2) Hücre çizgisi gibi kenara değen parçalar silinir (yazı/resim kalır).
     * 3) Mürekkebin kapladığı en küçük kutu 16x16'ya bölünür; ortalamadan yoğun bloklar 1 olur.
     * Hiç mürekkep yoksa "BLANK" döner (kaydedilmez, aranmaz).
     */
    fun key(clue: Bitmap): String {
        val w = clue.width
        val h = clue.height
        if (w < 12 || h < 12) return BLANK
        val px = IntArray(w * h)
        clue.getPixels(px, 0, w, 0, 0, w, h)

        val m = (minOf(w, h) * 6 / 100).coerceAtLeast(2)
        val x0 = m; val x1 = w - m          // [x0, x1)
        val y0 = m; val y1 = h - m
        val iw = x1 - x0; val ih = y1 - y0
        if (iw < 8 || ih < 8) return BLANK

        // Arka plan: iç kutunun kenar piksellerinin kanal bazında ortancası.
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        fun addBorder(x: Int, y: Int) {
            val p = px[y * w + x]
            rs.add((p shr 16) and 0xFF); gs.add((p shr 8) and 0xFF); bs.add(p and 0xFF)
        }
        for (x in x0 until x1) { addBorder(x, y0); addBorder(x, y1 - 1) }
        for (y in y0 until y1) { addBorder(x0, y); addBorder(x1 - 1, y) }
        rs.sort(); gs.sort(); bs.sort()
        val br = rs[rs.size / 2]; val bg = gs[gs.size / 2]; val bb = bs[bs.size / 2]

        // Mürekkep maskesi (iç kutu).
        val ink = BooleanArray(iw * ih)
        var inkCount = 0
        for (y in 0 until ih) for (x in 0 until iw) {
            val p = px[(y + y0) * w + (x + x0)]
            val d = abs(((p shr 16) and 0xFF) - br) + abs(((p shr 8) and 0xFF) - bg) + abs((p and 0xFF) - bb)
            if (d > 90) { ink[y * iw + x] = true; inkCount++ }
        }
        if (inkCount < 20) return BLANK

        // Kenara değen parçaları sil (bölünmüş hücrenin ayırıcı çizgisi, komşu hücre kenarı...).
        val cleaned = ink.copyOf()
        val stack = IntArray(iw * ih)
        var sp = 0
        fun seed(i: Int) { if (cleaned[i]) { cleaned[i] = false; stack[sp++] = i } }
        for (x in 0 until iw) { seed(x); seed((ih - 1) * iw + x) }
        for (y in 0 until ih) { seed(y * iw); seed(y * iw + iw - 1) }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % iw; val y = i / iw
            if (x > 0) seed(i - 1)
            if (x < iw - 1) seed(i + 1)
            if (y > 0) seed(i - iw)
            if (y < ih - 1) seed(i + iw)
        }
        var cleanCount = 0
        for (b in cleaned) if (b) cleanCount++
        // Resimli ipucunda her şey kenara bağlı olabilir: o zaman temizlenmemiş maskeyi kullan.
        val use = if (cleanCount >= 20 && cleanCount * 4 >= inkCount) cleaned else ink

        var bx0 = iw; var by0 = ih; var bx1 = -1; var by1 = -1
        for (y in 0 until ih) for (x in 0 until iw) {
            if (use[y * iw + x]) {
                if (x < bx0) bx0 = x
                if (x > bx1) bx1 = x
                if (y < by0) by0 = y
                if (y > by1) by1 = y
            }
        }
        if (bx1 < bx0 || by1 < by0) return BLANK
        val bw = bx1 - bx0 + 1
        val bh = by1 - by0 + 1

        val cov = DoubleArray(BITS)
        for (j in 0 until N) {
            val sy0 = by0 + j * bh / N
            val sy1 = maxOf(sy0 + 1, by0 + (j + 1) * bh / N)
            for (i in 0 until N) {
                val sx0 = bx0 + i * bw / N
                val sx1 = maxOf(sx0 + 1, bx0 + (i + 1) * bw / N)
                var on = 0; var tot = 0
                for (y in sy0 until minOf(sy1, ih)) for (x in sx0 until minOf(sx1, iw)) {
                    tot++
                    if (use[y * iw + x]) on++
                }
                cov[j * N + i] = if (tot > 0) on.toDouble() / tot else 0.0
            }
        }
        val mean = cov.average()
        val bits = String(CharArray(BITS) { if (cov[it] > mean) '1' else '0' })
        val aspect = ((bw.toDouble() / bh) * 100).roundToInt().coerceIn(1, 9999)
        return "$bits@$aspect"
    }

    private fun dist(a: String, b: String): Int {
        if (!isValid(a) || !isValid(b)) return BIG
        var d = 0
        for (i in 0 until BITS) if (a[i] != b[i]) d++
        val aa = a.substring(BITS + 1).toIntOrNull() ?: return BIG
        val bb = b.substring(BITS + 1).toIntOrNull() ?: return BIG
        return d + abs(aa - bb) / 5
    }

    /** Anahtara en yakın kayıt (kelime uzunluğuna BAKMADAN); maxDist'ten uzaksa null. */
    private fun nearestKey(key: String, maxDist: Int): String? {
        var best: String? = null
        var bestD = BIG
        for (k in map.keys) {
            val d = dist(k, key)
            if (d < bestD) { bestD = d; best = k }
        }
        return if (best != null && bestD <= maxDist) best else null
    }

    /**
     * Bu ipucuna ait kayıtlı cevaplar (verilen uzunlukta).
     * Önce TÜM kayıtlar arasında en yakın ipucu bulunur; o ipucunda bu uzunlukta cevap yoksa boş döner
     * (başka bir ipucunun cevabına kaymaz). İki farklı ipucu eşit derecede yakınsa belirsiz sayılır.
     */
    @Synchronized
    fun get(key: String, length: Int): List<String> {
        if (!isValid(key)) {
            GameLog.d(TAG, "get: anahtar geçersiz/BLANK (uzunluk=$length) → boş")
            return emptyList()
        }
        var best: String? = null; var bd = BIG
        var second: String? = null; var sd = BIG
        for (k in map.keys) {
            val d = dist(k, key)
            if (d < bd) { second = best; sd = bd; best = k; bd = d }
            else if (d < sd) { second = k; sd = d }
        }
        val limit = if (length <= 2) MAX_DIST_SHORT else MAX_DIST
        val id = shortId(key)
        val b = best
        if (b == null) {
            GameLog.d(TAG, "get $id uzunluk=$length: veritabanı boş → boş")
            return emptyList()
        }
        if (bd > limit) {
            GameLog.d(TAG, "get $id uzunluk=$length: en yakın kayıt ${shortId(b)} mesafe=$bd > sınır=$limit → bulunamadı")
            return emptyList()
        }
        val words = map[b]!!.filter { it.length == length }
        if (words.isEmpty()) {
            GameLog.d(TAG, "get $id uzunluk=$length: en yakın ${shortId(b)} mesafe=$bd ama bu uzunlukta cevabı yok (kayıtlı: ${map[b]!!.joinToString(",")}) → boş")
            return emptyList()
        }
        val s = second
        if (s != null && sd <= limit && sd - bd < AMBIGUOUS_GAP) {
            val other = map[s]!!.filter { it.length == length }
            if (other.isNotEmpty() && other.toSet() != words.toSet()) {
                GameLog.w(TAG, "get $id uzunluk=$length: BELİRSİZ en yakın ${shortId(b)}=$bd (${words.joinToString(",")}) ikinci ${shortId(s)}=$sd (${other.joinToString(",")}) → boş")
                return emptyList()
            }
        }
        GameLog.d(TAG, "get $id uzunluk=$length: BULUNDU ${shortId(b)} mesafe=$bd ikinci=${if (s == null) "yok" else sd.toString()} → ${words.joinToString(",")}")
        return words
    }

    @Synchronized
    fun put(key: String, word: String) {
        if (!isValid(key) || word.isEmpty()) return
        val near = nearestKey(key, MERGE_DIST)
        val k = near ?: key
        val added = map.getOrPut(k) { HashSet() }.add(word)
        if (added) {
            save()
            GameLog.d(TAG, "put ${shortId(key)} → \"$word\": ${if (near == null) "YENİ ipucu kaydı" else "mevcut kayıt ${shortId(near)} ile birleşti"}, kelime EKLENDİ (toplam kelime=${map.values.sumOf { it.size }})")
        }
    }

    /** Toplam kayıtlı kelime sayısı. */
    @Synchronized
    fun size(): Int = map.values.sumOf { it.size }

    /** Bulut eşitleme için tüm veriyi JSON olarak verir. */
    @Synchronized
    fun snapshot(): JSONObject = JSONObject(toJson())

    /** JSON'daki kelimeleri birleştirerek ekler (var olanı silmez). Eski biçimli (v2) kayıtlar atlanır. */
    @Synchronized
    fun mergeJson(o: JSONObject): Int {
        val imported = parseObj(o)
        var added = 0
        for ((k, words) in imported) {
            val target = nearestKey(k, MERGE_DIST) ?: k
            val set = map.getOrPut(target) { HashSet() }
            for (w in words) if (set.add(w)) added++
        }
        if (added > 0) save()
        GameLog.i(TAG, "buluttan birleştirme: +$added kelime (toplam ${map.values.sumOf { it.size }}, atlanan eski kayıt=$lastSkipped)")
        return added
    }

    /** Yedek almak için tüm veriyi yazar. */
    @Synchronized
    fun exportTo(out: OutputStream) {
        out.write(toJson().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Yedekten birleştirerek yükler (var olanı silmez). Yeni eklenen kelime sayısını döner. */
    @Synchronized
    fun importFrom(inp: InputStream): Int =
        mergeJson(JSONObject(inp.readBytes().toString(Charsets.UTF_8)))

    private fun toJson(): String {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, JSONArray(v.toList()))
        return o.toString()
    }

    private fun save() {
        runCatching {
            val json = toJson()
            tmp.writeText(json)
            if (file.exists()) runCatching { file.copyTo(bak, overwrite = true) }
            if (!tmp.renameTo(file)) file.writeText(json)
        }.onFailure { GameLog.e(TAG, "kaydetme hatası", it) }
    }
}
