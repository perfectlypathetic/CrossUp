package com.example.crossup.ai

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** İpucu görüntüsü parmak izi -> cevap kelimeler. Telefonda kalıcı saklanır. */
class ClueDb(ctx: Context) {
    private val file = File(ctx.filesDir, "clues.json")
    private val map = HashMap<String, MutableSet<String>>()

    init {
        runCatching {
            val o = JSONObject(file.readText())
            for (k in o.keys()) {
                val a = o.getJSONArray(k)
                map[k] = (0 until a.length()).map { a.getString(it) }.toMutableSet()
            }
        }
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

    private fun save() {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, JSONArray(v.toList()))
        file.writeText(o.toString())
    }
}
