package com.example.crossup.ai

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * İpucu görüntüsü parmak izi -> cevap kelimeler. Telefonda kalıcı saklanır.
 * - Tek örnek (singleton): uygulama ve servis aynı veriyi kullanır.
 * - Güvenli yazma: önce .tmp'ye yaz, eskiyi .bak yap, sonra yerine koy.
 * - Bozuk dosya silinmez, "clues.corrupt.*.json" adıyla saklanır.
 */
class ClueDb private constructor(ctx: Context) {
    private val dir = ctx.filesDir
    private val file = File(dir, "clues.json")
    private val tmp = File(dir, "clues.json.tmp")
    private val bak = File(dir, "clues.json.bak")
    private val map = HashMap<String, MutableSet<String>>()

    companion object {
        /** 256 bitte bu kadar veya daha az fark = aynı ipucu. */
        const val MAX_DIST = 22
        private const val N = 16

        @Volatile private var inst: ClueDb? = null
        fun get(ctx: Context): ClueDb =
            inst ?: synchronized(this) {
                inst ?: ClueDb(ctx.applicationContext).also { inst = it }
            }
    }

    init {
        var ok = false
        if (file.exists()) {
            ok = runCatching { map.putAll(parse(file.readText())) }.isSuccess
            if (!ok) runCatching {
                file.renameTo(File(dir, "clues.corrupt.${System.currentTimeMillis()}.json"))
            }
        }
        if (!ok && bak.exists()) runCatching { map.putAll(parse(bak.readText())) }
    }

    private fun parse(text: String): HashMap<String, MutableSet<String>> = parseObj(JSONObject(text))

    private fun parseObj(o: JSONObject): HashMap<String, MutableSet<String>> {
        val r = HashMap<String, MutableSet<String>>()
        for (k in o.keys()) {
            val a = o.getJSONArray(k)
            r[k] = (0 until a.length()).map { a.getString(it) }.toMutableSet()
        }
        return r
    }

    /**
     * 16x16 parlaklık haritası (her blok için GERÇEK ortalama, piksel atlamaz) -> ortalamaya göre 256 bitlik iz.
     * Parlaklık/solukluk değişimi (bilinen ipuçlarının üstüne gelen soluk katman) sonucu pek değiştirmez.
     * Kenarlardan %6 kırpılır (hücre çizgisi/arka plan etkisi azalsın).
     */
    fun key(clue: Bitmap): String {
        val w = clue.width
        val h = clue.height
        val px = IntArray(w * h)
        clue.getPixels(px, 0, w, 0, 0, w, h)

        val mx = (w * 6 / 100).coerceAtMost(w / 4)
        val my = (h * 6 / 100).coerceAtMost(h / 4)
        val iw = w - 2 * mx
        val ih = h - 2 * my

        val lum = DoubleArray(N * N)
        for (j in 0 until N) {
            val y0 = my + j * ih / N
            val y1 = maxOf(y0 + 1, my + (j + 1) * ih / N)
            for (i in 0 until N) {
                val x0 = mx + i * iw / N
                val x1 = maxOf(x0 + 1, mx + (i + 1) * iw / N)
                var sum = 0L
                var n = 0
                for (y in y0 until minOf(y1, h)) {
                    for (x in x0 until minOf(x1, w)) {
                        val p = px[y * w + x]
                        sum += (((p shr 16) and 0xFF) * 30 + ((p shr 8) and 0xFF) * 59 + (p and 0xFF) * 11) / 100
                        n++
                    }
                }
                lum[j * N + i] = if (n > 0) sum.toDouble() / n else 255.0
            }
        }
        val mean = lum.average()
        return String(CharArray(N * N) { if (lum[it] > mean) '1' else '0' })
    }

    private fun dist(a: String, b: String): Int {
        if (a.length != b.length) return Int.MAX_VALUE / 2
        var d = 0
        for (i in a.indices) if (a[i] != b[i]) d++
        return d
    }

    /** Anahtara en yakın kayıt (ilgili uzunlukta cevabı olanlar arasında); yeterince yakın değilse null. */
    private fun nearest(key: String, length: Int?): String? {
        var best: String? = null
        var bestD = Int.MAX_VALUE
        for ((k, v) in map) {
            if (length != null && v.none { it.length == length }) continue
            val d = dist(k, key)
            if (d < bestD) { bestD = d; best = k }
        }
        return if (best != null && bestD <= MAX_DIST) best else null
    }

    @Synchronized
    fun get(key: String, length: Int): List<String> {
        val best = nearest(key, length) ?: return emptyList()
        return map[best]!!.filter { it.length == length }
    }

    @Synchronized
    fun put(key: String, word: String) {
        val k = nearest(key, null) ?: key
        if (map.getOrPut(k) { HashSet() }.add(word)) save()
    }

    /** Toplam kayıtlı kelime sayısı. */
    @Synchronized
    fun size(): Int = map.values.sumOf { it.size }

    /** Bulut eşitleme için tüm veriyi JSON olarak verir. */
    @Synchronized
    fun snapshot(): JSONObject = JSONObject(toJson())

    /** JSON'daki kelimeleri birleştirerek ekler (var olanı silmez). Yeni eklenen kelime sayısını döner. */
    @Synchronized
    fun mergeJson(o: JSONObject): Int {
        val imported = parseObj(o)
        var added = 0
        for ((k, words) in imported) {
            val target = nearest(k, null) ?: k
            val set = map.getOrPut(target) { HashSet() }
            for (w in words) if (set.add(w)) added++
        }
        if (added > 0) save()
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
        }
    }
}
