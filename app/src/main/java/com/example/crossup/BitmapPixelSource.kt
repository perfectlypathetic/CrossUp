package com.example.crossup.parser

import android.graphics.Bitmap
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
