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

    private fun parse(text: String): HashMap<String, MutableSet<String>> {
        val o = JSONObject(text)
        val r = HashMap<String, MutableSet<String>>()
        for (k in o.keys()) {
            val a = o.getJSONArray(k)
            r[k] = (0 until a.length()).map { a.getString(it) }.toMutableSet()
        }
        return r
    }

    /** 16x16 gri küçültme -> ortalamaya göre 256 bitlik iz. Benzer görüntüler benzer iz verir. */
    fun key(clue: Bitmap): String {
        val s = Bitmap.createScaledBitmap(clue, 16, 16, true)
        val g = IntArray(256) { i ->
            val p = s.getPixel(i % 16, i / 16)
            ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3
        }
        val mean = g.average()
        return String(CharArray(256) { if (g[it] > mean) '1' else '0' })
    }

    private fun dist(a: String, b: String): Int {
        var d = 0
        for (i in a.indices) if (a[i] != b[i]) d++
        return d
    }

    @Synchronized
    fun get(key: String, length: Int): List<String> {
        val best = map.keys.minByOrNull { dist(it, key) } ?: return emptyList()
        if (dist(best, key) > 20) return emptyList()      // 256 bitte 20 veya daha az fark = aynı ipucu
        return map[best]!!.filter { it.length == length }
    }

    @Synchronized
    fun put(key: String, word: String) {
        val near = map.keys.minByOrNull { dist(it, key) }
        val k = if (near != null && dist(near, key) <= 20) near else key
        if (map.getOrPut(k) { HashSet() }.add(word)) save()
    }

    /** Toplam kayıtlı kelime sayısı. */
    @Synchronized
    fun size(): Int = map.values.sumOf { it.size }

    /** Yedek almak için tüm veriyi yazar. */
    @Synchronized
    fun exportTo(out: OutputStream) {
        out.write(toJson().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Yedekten birleştirerek yükler (var olanı silmez). Yeni eklenen kelime sayısını döner. */
    @Synchronized
    fun importFrom(inp: InputStream): Int {
        val imported = parse(inp.readBytes().toString(Charsets.UTF_8))
        var added = 0
        for ((k, words) in imported) {
            val near = map.keys.minByOrNull { dist(it, k) }
            val target = if (near != null && dist(near, k) <= 20) near else k
            val set = map.getOrPut(target) { HashSet() }
            for (w in words) if (set.add(w)) added++
        }
        if (added > 0) save()
        return added
    }

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
