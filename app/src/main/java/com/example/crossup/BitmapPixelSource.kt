package com.example.crossup.parser

import android.graphics.Bitmap
import com.example.crossup.GameLog
import java.io.ByteArrayOutputStream

class BitmapPixelSource(private val bmp: Bitmap) : PixelSource {
    override val width get() = bmp.width
    override val height get() = bmp.height
    override fun rgb(x: Int, y: Int): Int {
        if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return 0xFFFFFF
        return bmp.getPixel(x, y) and 0xFFFFFF
    }
}

fun Bitmap.crop(r: Rect): Bitmap {
    val x0 = r.x0.coerceIn(0, width - 1); val y0 = r.y0.coerceIn(0, height - 1)
    val x1 = r.x1.coerceIn(x0 + 1, width); val y1 = r.y1.coerceIn(y0 + 1, height)
    return Bitmap.createBitmap(this, x0, y0, x1 - x0, y1 - y0)
}

fun Bitmap.toPng(): ByteArray = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
fun Bitmap.toJpeg(q: Int = 85): ByteArray = ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

/**
 * Night light / mavi ışık filtresi / ekran karartması düzeltmesi.
 *
 * Bu filtreler her renk kanalını ayrı bir oranla çarpar (ör. beyaz -> 185,150,148).
 * Izgara okuyucu ve harf okuyucu sabit renklere baktığı için filtre açıkken "ızgara bulunamadı" der.
 * Çözüm: ızgaranın solundaki boş kenar (normalde saf beyaz) kaç renkteyse, tüm ekranı o orana
 * bölerek filtreyi geri alırız. Filtre yoksa bitmap aynen döner (hiçbir şey değişmez).
 */
fun Bitmap.normalizeTint(): Bitmap {
    val w = width; val h = height
    if (w < 16 || h < 16) return this

    val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
    var y = h * 12 / 100
    val yEnd = h * 72 / 100
    while (y < yEnd) {
        val p = getPixel(3, y)
        rs.add((p shr 16) and 0xFF); gs.add((p shr 8) and 0xFF); bs.add(p and 0xFF)
        y += 4
    }
    if (rs.isEmpty()) return this
    rs.sort(); gs.sort(); bs.sort()
    val mr = rs[rs.size / 2]; val mg = gs[gs.size / 2]; val mb = bs[bs.size / 2]

    // Kenar zaten (neredeyse) beyazsa filtre yok. Çok koyuysa (koyu tema vb.) dokunma.
    if (mr >= 246 && mg >= 246 && mb >= 246) {
        GameLog.d("Tint", "renk filtresi yok (sol kenar rengi $mr,$mg,$mb)")
        return this
    }
    if (minOf(mr, mg, mb) < 120) {
        GameLog.w("Tint", "sol kenar çok koyu ($mr,$mg,$mb): düzeltme yapılmadı (koyu tema / farklı ekran?)")
        return this
    }

    val gr = minOf(2.2f, 255f / mr)
    val gg = minOf(2.2f, 255f / mg)
    val gb = minOf(2.2f, 255f / mb)
    GameLog.i("Tint", "renk filtresi düzeltiliyor: sol kenar=$mr,$mg,$mb kazanç=${GameLog.f(gr.toDouble())},${GameLog.f(gg.toDouble())},${GameLog.f(gb.toDouble())}")

    val px = IntArray(w * h)
    getPixels(px, 0, w, 0, 0, w, h)
    for (i in px.indices) {
        val p = px[i]
        val r = minOf(255, (((p shr 16) and 0xFF) * gr + 0.5f).toInt())
        val g = minOf(255, (((p shr 8) and 0xFF) * gg + 0.5f).toInt())
        val b = minOf(255, ((p and 0xFF) * gb + 0.5f).toInt())
        px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}
