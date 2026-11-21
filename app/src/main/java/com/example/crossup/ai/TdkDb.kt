package com.example.crossup.ai

import android.content.Context
import android.util.Log
import com.example.crossup.game.TR
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.ln

/** Bir sözlük kelimesi ve tanımına göre aldığı puan. */
class TdkHit(val word: String, val score: Double)

/**
 * TDK sözlüğü. Önce telefondaki yüklenmiş kopyaya (filesDir/tdk.txt), yoksa assets/tdk.txt'ye bakar.
 * Açılışta bir kez belleğe yüklenir.
 * Dosya biçimi: her satır "KELİME<TAB>kök1 kök2 kök3 ..." (kökler aksansız, ilk 5 harf).
 * Kelimeler harf sayısına göre gruplanır; arama sadece ilgili uzunluktaki gruba bakar.
 */
class TdkDb private constructor(private val ctx: Context) {

    private class Bucket {
        val words = ArrayList<String>()
        val stems = ArrayList<IntArray>()
    }

    private var buckets: Array<Bucket> = emptyArray()
    private var stemId: HashMap<String, Int> = HashMap()
    private var idf: DoubleArray = DoubleArray(0)

    @Volatile var isLoaded = false; private set
    @Volatile var wordCount = 0; private set
    @Volatile var lastError = ""; private set

    companion object {
        private const val TAG = "TdkDb"
        const val ASSET = "tdk.txt"
        const val MAX_LEN = 12
        const val STEM_LEN = 5

        @Volatile private var inst: TdkDb? = null
        fun get(ctx: Context): TdkDb =
            inst ?: synchronized(this) {
                inst ?: TdkDb(ctx.applicationContext).also { inst = it }
            }

        // Python betiğindeki (tdk_hazirla.py) liste ile birebir aynı olmalı.
        private val STOP = hashSetOf(
            "bir", "ve", "ile", "olan", "olarak", "icin", "gibi", "cok", "daha", "her",
            "bu", "su", "ise", "ama", "veya", "ya", "ki", "mi", "kadar", "gore", "ait",
            "olma", "etme", "yapma", "yapan", "edilen", "olup", "oldugu", "icinde",
            "uzerinde", "sonra", "once", "bkz", "bak", "ilgili", "bazi", "pek", "hem",
            "tum", "butun", "ayni", "diger", "bunun", "sunun", "onun"
        )

        private val PAREN = Regex("\\([^)]*\\)")

        /** Küçük harfe çevirir (Türkçe kurallarıyla) ve aksanları siler: ş->s, ı->i, ğ->g ... */
        fun fold(s: String): String {
            val low = s.lowercase(TR)
            val sb = StringBuilder(low.length)
            for (ch in low) {
                sb.append(
                    when (ch) {
                        'ç' -> 'c'
                        'ğ' -> 'g'
                        'ı' -> 'i'
                        'ö' -> 'o'
                        'ş' -> 's'
                        'ü' -> 'u'
                        'â' -> 'a'
                        'î' -> 'i'
                        'û' -> 'u'
                        else -> ch
                    }
                )
            }
            return sb.toString()
        }

        /** Bir metinden anahtar kökleri çıkarır (Python betiğindeki 'kokler' ile aynı kural). */
        fun stems(text: String): Set<String> {
            val folded = fold(PAREN.replace(text, " "))
            val out = LinkedHashSet<String>()
            val cur = StringBuilder()
            fun flush() {
                if (cur.isNotEmpty()) {
                    val t = cur.toString()
                    if (t.length >= 3 && t !in STOP) out.add(t.take(STEM_LEN))
                    cur.setLength(0)
                }
            }
            for (ch in folded) {
                if (ch in 'a'..'z') cur.append(ch) else flush()
            }
            flush()
            return out
        }
    }

    private fun localFile() = File(ctx.filesDir, "tdk.txt")

    /**
     * Sözlük dosyasını açar: 1) telefona yüklenmiş kopya  2) assets/tdk.txt  3) assets'te adı "tdk" ile başlayan dosya.
     * Hiçbiri yoksa assets içinde ne olduğunu hata mesajına yazar.
     */
    private fun openAsset(): InputStream {
        val local = localFile()
        if (local.exists() && local.length() > 0) return local.inputStream()
        try {
            return ctx.assets.open(ASSET)
        } catch (e: IOException) {
            val names = try { ctx.assets.list("")?.toList() ?: emptyList() } catch (x: Exception) { emptyList() }
            val alt = names.firstOrNull { it.startsWith("tdk", ignoreCase = true) }
            if (alt != null) return ctx.assets.open(alt)
            val found = if (names.isEmpty()) "assets klasörü boş/yok" else names.take(8).joinToString(",")
            throw IOException("assets'te tdk.txt yok. Bulunanlar: $found")
        }
    }

    /**
     * Kullanıcının seçtiği tdk.txt dosyasını telefona kopyalar ve yükler.
     * Arka plan iş parçacığında çağır. Başarılıysa true.
     */
    @Synchronized
    fun installFrom(inp: InputStream): Boolean {
        return try {
            val f = localFile()
            val t = File(ctx.filesDir, "tdk.txt.tmp")
            t.outputStream().use { out -> inp.copyTo(out) }
            if (t.length() == 0L) throw IOException("seçilen dosya boş")
            f.delete()
            if (!t.renameTo(f)) {
                t.copyTo(f, overwrite = true)
                t.delete()
            }
            isLoaded = false
            ensureLoaded()
        } catch (e: Throwable) {
            lastError = (e.message ?: e.javaClass.simpleName).take(120)
            false
        }
    }

    /** Sözlüğü yükler (ilk çağrıda). Dosya okuduğu için arka plan iş parçacığında çağır. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (isLoaded) return true
        try {
            val bk = Array(MAX_LEN + 1) { Bucket() }
            val ids = HashMap<String, Int>()
            val dfList = ArrayList<Int>()
            var n = 0

            openAsset().bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val word = line.substring(0, tab)
                    if (word.length !in 1..MAX_LEN) continue
                    val parts = line.substring(tab + 1).trim().split(' ')
                    val tmp = IntArray(parts.size)
                    var k = 0
                    for (p in parts) {
                        if (p.isEmpty()) continue
                        var id = ids[p]
                        if (id == null) {
                            id = dfList.size
                            ids[p] = id
                            dfList.add(0)
                        }
                        dfList[id] = dfList[id] + 1
                        tmp[k++] = id
                    }
                    bk[word.length].words.add(word)
                    bk[word.length].stems.add(tmp.copyOf(k))
                    n++
                }
            }

            if (n == 0) throw IOException("tdk.txt okundu ama içinde kelime yok")

            val total = n.toDouble()
            idf = DoubleArray(dfList.size) { ln(1.0 + total / dfList[it]) }
            buckets = bk
            stemId = ids
            wordCount = n
            isLoaded = true
            lastError = ""
            Log.i(TAG, "TDK yüklendi: $n kelime, ${dfList.size} kök")
            return true
        } catch (e: Throwable) {   // OutOfMemoryError dahil
            lastError = (e.message ?: e.javaClass.simpleName).take(120)
            Log.w(TAG, "TDK yüklenemedi: $lastError")
            return false
        }
    }

    private fun fits(w: String, pattern: String): Boolean {
        if (w.length != pattern.length) return false
        for (i in w.indices) {
            val p = pattern[i]
            if (p != '?' && p != w[i]) return false
        }
        return true
    }

    /**
     * Verilen uzunluk ve desene (ör. "A??E?") uyan kelimeleri, tanımlarının
     * sorgu köklerini ne kadar içerdiğine göre puanlar. En yüksek puan başta.
     */
    fun search(length: Int, pattern: String, query: Set<String>): List<TdkHit> {
        if (!isLoaded || length !in 1..MAX_LEN || query.isEmpty()) return emptyList()
        val qw = HashMap<Int, Double>()
        for (q in query) stemId[q]?.let { qw[it] = idf[it] }
        if (qw.isEmpty()) return emptyList()

        val b = buckets[length]
        val out = ArrayList<TdkHit>()
        for (i in b.words.indices) {
            val w = b.words[i]
            if (!fits(w, pattern)) continue
            var s = 0.0
            for (id in b.stems[i]) {
                val x = qw[id]
                if (x != null) s += x
            }
            if (s > 0.0) out.add(TdkHit(w, s))
        }
        out.sortByDescending { it.score }
        return out
    }
}
