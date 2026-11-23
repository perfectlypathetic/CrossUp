package com.example.crossup.ai

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Harf görüntüsünün parmak izi: 16x16 mürekkep maskesi + en/boy oranı. */
class Fp(val bits: String, val aspect: Float)

object LetterFp {
    private const val N = 16

    /**
     * Kırpılmış harf görüntüsünden parmak izi çıkarır. Harf yoksa null.
     * Kırpmanın kenarına değen koyu parçalar (ok üçgeni, hücre çizgisi, komşu hücre) silinir;
     * küçük lekeler harf sayılmaz.
     */
    fun of(bmp: Bitmap): Fp? {
        val w = bmp.width; val h = bmp.height
        if (w < 8 || h < 8) return null
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val lum = IntArray(w * h)
        var mn = 255; var sum = 0L
        for (i in px.indices) {
            val p = px[i]
            val l = (((p shr 16) and 0xFF) * 30 + ((p shr 8) and 0xFF) * 59 + (p and 0xFF) * 11) / 100
            lum[i] = l
            if (l < mn) mn = l
            sum += l
        }
        val mean = sum.toDouble() / px.size
        if (mean - mn < 60) return null                 // kontrast yok: harf yok
        val thr = mn + (mean - mn) * 0.5                // koyu mürekkep eşiği

        // Mürekkep maskesi.
        val ink = BooleanArray(w * h) { lum[it] < thr }

        // Kenara değen mürekkep parçalarını sil (kenardan başlayıp yayılarak).
        val stack = IntArray(w * h)
        var sp = 0
        fun seed(i: Int) {
            if (ink[i]) { ink[i] = false; stack[sp++] = i }
        }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w; val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }

        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1; var cnt = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (ink[y * w + x]) {
                cnt++
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        if (cnt < 30 || x1 - x0 < 5 || y1 - y0 < 6) return null
        if ((y1 - y0 + 1) < h * 0.3) return null        // harf kırpmanın en az %30'u kadar uzun olmalı

        val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
        val sb = StringBuilder(N * N)
        for (j in 0 until N) for (i in 0 until N) {
            val sx0 = x0 + i * bw / N
            val sx1 = maxOf(sx0 + 1, x0 + (i + 1) * bw / N)
            val sy0 = y0 + j * bh / N
            val sy1 = maxOf(sy0 + 1, y0 + (j + 1) * bh / N)
            var on = 0; var tot = 0
            for (y in sy0 until sy1) for (x in sx0 until sx1) {
                tot++
                if (ink[y * w + x]) on++
            }
            sb.append(if (on * 2 > tot) '1' else '0')
        }
        return Fp(sb.toString(), bw.toFloat() / bh)
    }
}

/**
 * Harf şablonları: parmak izi -> harf. Telefonda kalıcı saklanır.
 * Aynı harf için birden fazla şablon tutulabilir (raf ve tahta boyutları farklı olabilir).
 */
class LetterDb private constructor(ctx: Context) {
    private class T(val fp: Fp, val ch: Char)

    private val file = File(ctx.filesDir, "letters.json")
    private val tmp = File(ctx.filesDir, "letters.json.tmp")
    private val list = ArrayList<T>()

    companion object {
        /** Bu farktan (256 bit + en/boy cezası) büyükse eşleşme sayılmaz. */
        const val MAX_DIST = 28
        /** En iyi harf ile ikinci en iyi FARKLI harf arasında en az bu fark olmalı. */
        const val MARGIN = 8

        @Volatile private var inst: LetterDb? = null
        fun get(ctx: Context): LetterDb =
            inst ?: synchronized(this) {
                inst ?: LetterDb(ctx.applicationContext).also { inst = it }
            }

        fun dist(a: Fp, b: Fp): Int {
            var d = 0
            val n = minOf(a.bits.length, b.bits.length)
            for (i in 0 until n) if (a.bits[i] != b.bits[i]) d++
            return d + (abs(a.aspect - b.aspect) * 40f).toInt()
        }
    }

    init {
        runCatching {
            if (file.exists()) {
                val a = JSONArray(file.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    list.add(T(Fp(o.getString("b"), o.getDouble("a").toFloat()), o.getString("c")[0]))
                }
            }
        }
    }

    /** Eşleşen harfi döner; emin değilse null. */
    @Synchronized
    fun match(fp: Fp, maxDist: Int = MAX_DIST, margin: Int = MARGIN): Char? {
        if (list.isEmpty()) return null
        val per = HashMap<Char, Int>()
        for (t in list) {
            val d = dist(fp, t.fp)
            val old = per[t.ch]
            if (old == null || d < old) per[t.ch] = d
        }
        val sorted = per.entries.sortedBy { it.value }
        val best = sorted[0]
        val second = sorted.getOrNull(1)?.value ?: Int.MAX_VALUE
        return if (best.value <= maxDist && second - best.value >= margin) best.key else null
    }

    /** Biraz gevşek eşleşme (tahtadaki harfler için): eşik ve fark şartı daha esnek. */
    fun matchRelaxed(fp: Fp): Char? = match(fp, 40, 3)

    /**
     * En yakın şablonun harfi. Fark şartı yok; sadece mesafe sınırı var.
     * Raf karoları her zaman harftir, bu yüzden raf için kullanılır.
     */
    @Synchronized
    fun nearest(fp: Fp, maxDist: Int): Char? {
        var bestCh: Char? = null
        var best = Int.MAX_VALUE
        for (t in list) {
            val d = dist(fp, t.fp)
            if (d < best) { best = d; bestCh = t.ch }
        }
        return if (bestCh != null && best <= maxDist) bestCh else null
    }

    /** 29 harfin hepsi kayıtlı mı? (Öyleyse artık kullanıcıya harf sorulmaz.) */
    fun isComplete(): Boolean = hasAll(TR_ALPHABET)

    /** Verilen alfabenin TÜM harfleri için en az bir şablon kayıtlı mı? */
    @Synchronized
    fun hasAll(alphabet: String): Boolean {
        val have = HashSet<Char>()
        for (t in list) have.add(t.ch)
        return alphabet.all { it in have }
    }

    /** Doğrulanmış harfi şablon olarak kaydeder (çok benzer şablon varsa eklemez). */
    @Synchronized
    fun learn(fp: Fp, ch: Char) {
        if (add(fp, ch)) save()
    }

    private fun add(fp: Fp, ch: Char): Boolean {
        if (list.any { it.ch == ch && dist(it.fp, fp) <= 4 }) return false
        list.add(T(fp, ch))
        return true
    }

    @Synchronized
    fun size(): Int = list.size

    /** Bulut eşitleme için tüm şablonları JSON olarak verir. */
    @Synchronized
    fun snapshot(): JSONArray = toJsonArray()

    /** JSON'daki şablonları birleştirerek ekler. Yeni eklenen şablon sayısını döner. */
    @Synchronized
    fun mergeJson(a: JSONArray): Int {
        var added = 0
        for (i in 0 until a.length()) {
            runCatching {
                val o = a.getJSONObject(i)
                val fp = Fp(o.getString("b"), o.getDouble("a").toFloat())
                if (add(fp, o.getString("c")[0])) added++
            }
        }
        if (added > 0) save()
        return added
    }

    private fun toJsonArray(): JSONArray {
        val a = JSONArray()
        for (t in list) a.put(JSONObject().put("b", t.fp.bits).put("a", t.fp.aspect.toDouble()).put("c", t.ch.toString()))
        return a
    }

    private fun save() {
        runCatching {
            val json = toJsonArray().toString()
            tmp.writeText(json)
            if (!tmp.renameTo(file)) file.writeText(json)
        }
    }
}
