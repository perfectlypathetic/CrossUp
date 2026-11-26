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
import kotlin.math.ceil
import kotlin.math.floor
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
 *
 * YENİ (v3 + "#" eki): Ölçüm gösterdi ki 256 bitlik parmak izi, kırpma 1-2 piksel kaysa bile
 * (tek bir sapma piksel mürekkep kutusunu değiştirir, bütün bitler kayar) aynı resmi farklı resimden
 * ayıramıyordu. Bu yüzden anahtarın sonuna "#<400 hane>" eklenir: 20x20 yumuşak (bulanık) mürekkep haritası.
 * Uç noktalar kırpılarak (yüzde 2-98) hesaplanır, karşılaştırırken ±2 hücre kaydırma payı vardır.
 * Eski kayıtlar (eki olmayan) bozulmaz; eski yöntemle eşleşmeye devam eder ve aynı ipucu tekrar öğrenilince
 * yeni parmak iziyle yükseltilir.
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

        /**
         * Bu farka kadar (256 bit + en/boy cezası) aynı ipucu sayılır.
         * ÖLÇÜM (bulut dosyasındaki 212 kayıt): aynı resmin iki görünümü arasındaki fark 11-24 (ALA 19, N 23, SAÇ 24...),
         * farklı ipuçları arasındaki en küçük fark 31. Eski sınırlar (21 ve 18) aynı resmi kaçırıyordu; bu yüzden
         * resim her çıktığında yeniden "bilinmiyor" oluyordu.
         */
        const val MAX_DIST = 27
        /** 1-2 harflik cevaplarda hata çok pahalı: biraz daha sıkı, ama ölçülen en büyük aynı-resim farkının (N=23) üstünde. */
        const val MAX_DIST_SHORT = 26
        /** Yeni parmak izi ızgarası (DG x DG) ve uzunluğu. */
        private const val DG = 20
        private const val DLEN = DG * DG
        private const val SHIFT = 2
        private const val BIGD = 1e9

        /**
         * Yeni parmak izi mesafesi (0 = aynı, 1 = tamamen farklı) bu sınıra kadar aynı ipucu sayılır.
         * ÖLÇÜM (örnek ekran, 22 ipucu, 1-2 piksel kayma/boyut değişimi + gece ışığı/karartma):
         * aynı resim en çok 0.155, farklı ipuçları en az 0.229. Sınır ikisinin arasında.
         */
        const val DESC_LIMIT = 0.19
        /** 1-2 harflik cevaplarda daha sıkı. */
        const val DESC_LIMIT_SHORT = 0.17
        /** Kayıt sırasında sınırın bu kadarı içindeki anahtar "aynı ipucu" sayılıp birleştirilir. */
        const val MERGE_RATIO = 0.37
        /** En yakın iki farklı kayıt arasında oran farkı en az bu kadar olmalı, yoksa (ve cevapları farklıysa) belirsizdir. */
        const val AMBIGUOUS_RATIO = 0.2

        @Volatile private var inst: ClueDb? = null
        fun get(ctx: Context): ClueDb =
            inst ?: synchronized(this) {
                inst ?: ClueDb(ctx.applicationContext).also { inst = it }
            }

        private fun isValid(k: String) = k.length > BITS + 1 && k[BITS] == '@'

        /** Anahtarın en/boy sayısı ("@" ile "#" arası). */
        private fun aspectOf(k: String): Int? {
            val e = k.indexOf('#').let { if (it < 0) k.length else it }
            return k.substring(BITS + 1, e).toIntOrNull()
        }

        /** Yeni parmak izi (400 hane); eski biçimli anahtarlarda yok. */
        private fun descOf(k: String): String? {
            val i = k.indexOf('#')
            return if (i >= 0 && k.length - i - 1 == DLEN) k.substring(i + 1) else null
        }

        private fun decode(d: String) = IntArray(DLEN) { (d[it] - '0').coerceIn(0, 9) }

        /** Log için kısa ipucu kimliği: parmak izinin özeti + en/boy. */
        fun shortId(k: String): String =
            if (!isValid(k)) k else Integer.toHexString(k.hashCode()) + "@" + (aspectOf(k) ?: 0)
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
        val oldKey = "$bits@$aspect"
        val desc = descriptor(px, w, x0, y0, iw, ih, br, bg, bb)
        return if (desc != null) "$oldKey#$desc" else oldKey
    }

    /** Histogramdan yüzdelik (numpy "linear" ile aynı). */
    private fun percentile(hist: IntArray, n: Int, q: Double): Double {
        val p = q * (n - 1)
        val lo = p.toInt()
        val frac = p - lo
        fun at(r: Int): Int {
            var c = 0
            for (i in hist.indices) { c += hist[i]; if (c > r) return i }
            return hist.size - 1
        }
        val a = at(lo)
        val b = at(minOf(lo + 1, n - 1))
        return a + (b - a) * frac
    }

    /**
     * YENİ PARMAK İZİ: kaymaya/boyuta dayanıklı 20x20 yumuşak mürekkep haritası ("0".."9" ile 400 hane).
     * 1) Arka plandan farkı (brightness'a göre ölçeklenmiş) "mürekkep yoğunluğu" yapılır.
     * 2) Yoğun piksellerin kutusu yüzde 2-98 ile kırpılarak bulunur (tek tük aykırı piksel kutuyu bozamaz).
     * 3) Kutu 20x20'ye alan ortalamasıyla küçültülür, hafifçe bulanıklaştırılır.
     */
    private fun descriptor(px: IntArray, w: Int, x0: Int, y0: Int, iw: Int, ih: Int, br: Int, bg: Int, bb: Int): String? {
        val n = iw * ih
        val d = IntArray(n)
        val hist = IntArray(766)
        for (y in 0 until ih) for (x in 0 until iw) {
            val p = px[(y + y0) * w + (x + x0)]
            val v = abs(((p shr 16) and 0xFF) - br) + abs(((p shr 8) and 0xFF) - bg) + abs((p and 0xFF) - bb)
            d[y * iw + x] = v
            hist[v]++
        }
        val scale = maxOf(percentile(hist, n, 0.97), 60.0)
        val soft = FloatArray(n) { minOf(d[it] / scale, 1.0).toFloat() }

        val xh = IntArray(iw)
        val yh = IntArray(ih)
        var cnt = 0
        for (y in 0 until ih) for (x in 0 until iw) {
            if (soft[y * iw + x] > 0.35f) { xh[x]++; yh[y]++; cnt++ }
        }
        if (cnt < 20) return null
        val bx0 = percentile(xh, cnt, 0.02).toInt()
        val bx1 = minOf(percentile(xh, cnt, 0.98).toInt() + 1, iw)
        val by0 = percentile(yh, cnt, 0.02).toInt()
        val by1 = minOf(percentile(yh, cnt, 0.98).toInt() + 1, ih)
        val sw = bx1 - bx0
        val sh = by1 - by0
        if (sw < 2 || sh < 2) return null

        // Alan ortalamasıyla DG x DG'ye küçült (kesirli piksel ağırlıklarıyla).
        val out = DoubleArray(DLEN)
        for (j in 0 until DG) {
            val ys = by0 + j * sh.toDouble() / DG
            val ye = by0 + (j + 1) * sh.toDouble() / DG
            for (i in 0 until DG) {
                val xs = bx0 + i * sw.toDouble() / DG
                val xe = bx0 + (i + 1) * sw.toDouble() / DG
                var tot = 0.0
                var ws = 0.0
                for (yy in floor(ys).toInt() until ceil(ye).toInt()) {
                    if (yy >= ih) continue
                    val wy = minOf(yy + 1.0, ye) - maxOf(yy.toDouble(), ys)
                    if (wy <= 0.0) continue
                    for (xx in floor(xs).toInt() until ceil(xe).toInt()) {
                        if (xx >= iw) continue
                        val wx = minOf(xx + 1.0, xe) - maxOf(xx.toDouble(), xs)
                        if (wx <= 0.0) continue
                        tot += soft[yy * iw + xx] * wy * wx
                        ws += wy * wx
                    }
                }
                out[j * DG + i] = if (ws > 0.0) tot / ws else 0.0
            }
        }

        // Hafif bulanıklık (yatay sonra dikey), kenarlar uzatılır.
        fun at(src: DoubleArray, yy: Int, xx: Int) = src[yy.coerceIn(0, DG - 1) * DG + xx.coerceIn(0, DG - 1)]
        val k0 = 0.239
        val k1 = 0.522
        val hz = DoubleArray(DLEN)
        for (y in 0 until DG) for (x in 0 until DG) {
            hz[y * DG + x] = k0 * at(out, y, x - 1) + k1 * at(out, y, x) + k0 * at(out, y, x + 1)
        }
        val bl = DoubleArray(DLEN)
        for (y in 0 until DG) for (x in 0 until DG) {
            bl[y * DG + x] = k0 * at(hz, y - 1, x) + k1 * at(hz, y, x) + k0 * at(hz, y + 1, x)
        }
        val sb = StringBuilder(DLEN)
        for (v in bl) sb.append('0' + floor(v * 9 + 0.5).toInt().coerceIn(0, 9))
        return sb.toString()
    }

    /** Eski yöntem (256 bit + en/boy cezası). */
    private fun oldDist(a: String, b: String): Int {
        if (!isValid(a) || !isValid(b)) return BIG
        var d = 0
        for (i in 0 until BITS) if (a[i] != b[i]) d++
        val aa = aspectOf(a) ?: return BIG
        val bb = aspectOf(b) ?: return BIG
        return d + abs(aa - bb) / 5
    }

    /** Yeni yöntem: 20x20 haritalar, ±SHIFT hücre kaydırma payı, içeriğe göre normalleştirilmiş fark (0..1). */
    private fun descDist(a: IntArray, b: IntArray): Double {
        var best = Double.MAX_VALUE
        for (dy in -SHIFT..SHIFT) for (dx in -SHIFT..SHIFT) {
            var sa = 0
            var sm = 0
            val ys = maxOf(0, dy); val ye = DG + minOf(0, dy)
            val xs = maxOf(0, dx); val xe = DG + minOf(0, dx)
            for (y in ys until ye) {
                val ra = y * DG
                val rb = (y - dy) * DG
                for (x in xs until xe) {
                    val p = a[ra + x]
                    val q = b[rb + x - dx]
                    sa += abs(p - q)
                    sm += maxOf(p, q)
                }
            }
            val v = if (sm == 0) 1.0 else sa.toDouble() / sm
            if (v < best) best = v
        }
        return best
    }

    /** Kayıtların çözülmüş yeni parmak izleri (yoksa boş dizi). Sadece kayıt anahtarları için önbelleğe alınır. */
    private val recCache = HashMap<String, IntArray>()
    private fun recDecoded(k: String): IntArray =
        recCache.getOrPut(k) { descOf(k)?.let { decode(it) } ?: IntArray(0) }

    /**
     * Eşleşme ORANI: 1.0 = tam sınırda, küçük = daha benzer. İki tarafta da yeni parmak izi varsa o,
     * yoksa eski yöntem kullanılır (her biri kendi sınırına bölünür).
     */
    private fun score(qKey: String, qd: IntArray?, k: String, short: Boolean): Double {
        val rd = recDecoded(k)
        if (qd != null && rd.isNotEmpty()) {
            return descDist(qd, rd) / (if (short) DESC_LIMIT_SHORT else DESC_LIMIT)
        }
        val d = oldDist(qKey, k)
        if (d >= BIG) return BIGD
        return d.toDouble() / (if (short) MAX_DIST_SHORT else MAX_DIST)
    }

    /** Anahtara en yakın kayıt (kelime uzunluğuna BAKMADAN); oran maxRatio'dan büyükse null. */
    private fun nearestKey(key: String, maxRatio: Double): String? {
        val qd = descOf(key)?.let { decode(it) }
        var best: String? = null
        var bs = BIGD
        for (k in map.keys) {
            if (k == key) return k
            val s = score(key, qd, k, false)
            if (s < bs) { bs = s; best = k }
        }
        return if (best != null && bs <= maxRatio) best else null
    }

    /**
     * Eski biçimli (yeni parmak izi olmayan) kaydı, aynı ipucunun yeni parmak iziyle yükseltir.
     * Yeni anahtarı döner (yükseltme yoksa aynı anahtar).
     */
    private fun upgrade(target: String, incoming: String): String {
        if (target == incoming || descOf(incoming) == null || descOf(target) != null) return target
        if (map.containsKey(incoming)) return target
        val set = map.remove(target) ?: return target
        recCache.remove(target)
        map[incoming] = set
        return incoming
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
        val short = length <= 2
        val qd = descOf(key)?.let { decode(it) }
        var best: String? = null; var bs = BIGD
        var second: String? = null; var ss = BIGD
        for (k in map.keys) {
            val sc = score(key, qd, k, short)
            if (sc < bs) { second = best; ss = bs; best = k; bs = sc }
            else if (sc < ss) { second = k; ss = sc }
        }
        val id = shortId(key)
        val how = if (qd != null) "yeni" else "eski"
        val b = best
        if (b == null) {
            GameLog.d(TAG, "get $id uzunluk=$length: veritabanı boş → boş")
            return emptyList()
        }
        if (bs > 1.0) {
            GameLog.d(TAG, "get $id uzunluk=$length: en yakın kayıt ${shortId(b)} oran=${GameLog.f(bs)} > 1 (sınır) [$how] → bulunamadı")
            return emptyList()
        }
        val words = map[b]!!.filter { it.length == length }
        if (words.isEmpty()) {
            GameLog.d(TAG, "get $id uzunluk=$length: en yakın ${shortId(b)} oran=${GameLog.f(bs)} ama bu uzunlukta cevabı yok (kayıtlı: ${map[b]!!.joinToString(",")}) → boş")
            return emptyList()
        }
        val s = second
        if (s != null && ss <= 1.0 && ss - bs < AMBIGUOUS_RATIO) {
            val other = map[s]!!.filter { it.length == length }
            if (other.isNotEmpty() && other.toSet() != words.toSet()) {
                GameLog.w(TAG, "get $id uzunluk=$length: BELİRSİZ en yakın ${shortId(b)}=${GameLog.f(bs)} (${words.joinToString(",")}) ikinci ${shortId(s)}=${GameLog.f(ss)} (${other.joinToString(",")}) → boş")
                return emptyList()
            }
        }
        GameLog.d(TAG, "get $id uzunluk=$length: BULUNDU ${shortId(b)} oran=${GameLog.f(bs)} [$how] ikinci=${if (s == null) "yok" else GameLog.f(ss)} → ${words.joinToString(",")}")
        return words
    }

    @Synchronized
    fun put(key: String, word: String) {
        if (!isValid(key) || word.isEmpty()) return
        val near = nearestKey(key, MERGE_RATIO)
        var k = near ?: key
        var upgraded = false
        if (near != null) {
            val up = upgrade(near, key)
            if (up != near) { k = up; upgraded = true }
        }
        val added = map.getOrPut(k) { HashSet() }.add(word)
        if (added || upgraded) {
            save()
            GameLog.d(TAG, "put ${shortId(key)} → \"$word\": ${if (near == null) "YENİ ipucu kaydı" else "mevcut kayıt ${shortId(near)} ile birleşti${if (upgraded) " (yeni parmak iziyle yükseltildi)" else ""}"}, ${if (added) "kelime EKLENDİ" else "kelime zaten vardı"} (toplam kelime=${map.values.sumOf { it.size }})")
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
        var changed = false
        for ((k, words) in imported) {
            val near = nearestKey(k, MERGE_RATIO)
            var target = near ?: k
            if (near != null) {
                val up = upgrade(near, k)
                if (up != near) { target = up; changed = true }
            }
            val set = map.getOrPut(target) { HashSet() }
            for (w in words) if (set.add(w)) added++
        }
        if (added > 0 || changed) save()
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
