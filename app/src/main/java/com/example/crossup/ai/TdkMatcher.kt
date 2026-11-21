package com.example.crossup.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import com.example.crossup.game.Candidate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * TDK ile çevrimdışı ipucu çözücü.
 *  0) "... SİMGESİ" ipuçları: element tablosundan (OKSİJEN -> O, AZOT -> N ...).
 *  1) readClue: ipucu görüntüsünü ML Kit ile okur (yazı yoksa boş döner).
 *  2) candidates: yazıdan kök çıkarır, uzunluğa ve desene uyan kelimeleri tanım örtüşmesine göre sıralar.
 * Güven düşükse candidates null döner.
 */
class TdkMatcher(ctx: Context) {
    private val db = TdkDb.get(ctx)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** Son candidates() çağrısının sonucu (teşhis için): neden çözüldü / neden elendi. */
    @Volatile var lastReason: String = ""
        private set

    companion object {
        private const val TAG = "TdkMatcher"

        /** En iyi adayın puanı bundan düşükse güvenme (null dön). */
        const val MIN_TOP = 3.0
        /** 1-2 harflik cevaplarda tanım eşleşmesi çok gürültülü: daha yüksek puan şart. */
        const val MIN_TOP_SHORT = 7.0
        /** En iyi puanın bu oranından düşük adaylar listeye girmez. */
        const val KEEP_RATIO = 0.5
        /** En fazla kaç aday döner. */
        const val MAX_CANDS = 10
        /** En iyi puana yakın (>= %90) bu kadardan çok aday varsa belirsizdir (null dön). */
        const val MAX_TIED = 30
        /** OCR'dan en az bu kadar harf okunmalı, yoksa resimli ipucu sayılır. */
        const val MIN_LETTERS = 4
        /** OCR öncesi görüntü bu yüksekliğe (piksel) büyütülür (en çok 4 kat). */
        private const val TARGET_H = 360f

        /** Element adı (aksansız, küçük harf) -> sembol. Semboller bilerek büyük harfle yazıldı. */
        private val ELEMENTS: List<Pair<String, String>> = listOf(
            "hidrojen" to "H", "helyum" to "HE", "lityum" to "LI", "berilyum" to "BE", "bor" to "B",
            "karbon" to "C", "azot" to "N", "oksijen" to "O", "flor" to "F", "neon" to "NE",
            "sodyum" to "NA", "magnezyum" to "MG", "aluminyum" to "AL", "silisyum" to "SI",
            "fosfor" to "P", "kukurt" to "S", "klor" to "CL", "argon" to "AR", "potasyum" to "K",
            "kalsiyum" to "CA", "titanyum" to "TI", "vanadyum" to "V", "krom" to "CR", "mangan" to "MN",
            "demir" to "FE", "kobalt" to "CO", "nikel" to "NI", "bakir" to "CU", "cinko" to "ZN",
            "galyum" to "GA", "germanyum" to "GE", "arsenik" to "AS", "selenyum" to "SE", "brom" to "BR",
            "kripton" to "KR", "zirkonyum" to "ZR", "molibden" to "MO", "gumus" to "AG", "kadmiyum" to "CD",
            "kalay" to "SN", "antimon" to "SB", "iyot" to "I", "ksenon" to "XE", "baryum" to "BA",
            "volfram" to "W", "platin" to "PT", "altin" to "AU", "civa" to "HG", "kursun" to "PB",
            "bizmut" to "BI", "radyum" to "RA", "radon" to "RN", "uranyum" to "U", "plutonyum" to "PU",
            "kurcatovyum" to "KU"
        ).sortedByDescending { it.first.length }
    }

    /** Sözlüğü yükler (ilk seferde ~0,5 sn). Başarılıysa true. */
    suspend fun prepare(): Boolean = withContext(Dispatchers.IO) { db.ensureLoaded() }

    val ready: Boolean get() = db.isLoaded

    /** Sözlük yüklenemediyse sebebi (teşhis için). */
    val loadError: String get() = db.lastError

    /** İpucu görüntüsündeki yazıyı okur. Okunamazsa boş metin döner. */
    suspend fun readClue(crop: Bitmap): String {
        if (!db.isLoaded) return ""
        return try {
            val big = enlarge(crop)
            val raw = suspendCancellableCoroutine<String> { cont ->
                recognizer.process(InputImage.fromBitmap(big, 0))
                    .addOnSuccessListener { t -> if (cont.isActive) cont.resume(t.text) }
                    .addOnFailureListener { if (cont.isActive) cont.resume("") }
            }
            raw.replace("-\n", "").replace('\n', ' ').trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
    }

    /** "OKSİJENİN SİMGESİ" gibi ipuçları için element sembolü. Uymazsa null. */
    private fun elementSymbol(clueText: String, length: Int, pattern: String): String? {
        val compact = TdkDb.fold(clueText).filter { it in 'a'..'z' }
        if (!compact.contains("simge") && !compact.contains("sembol")) return null
        for ((name, sym) in ELEMENTS) {
            if (compact.startsWith(name)) {
                if (sym.length != length) return null
                for (i in sym.indices) if (pattern[i] != '?' && pattern[i] != sym[i]) return null
                return sym
            }
        }
        return null
    }

    /**
     * OCR metninden aday üretir.
     * null  = güven düşük / yazı yok / TDK'da uygun kelime yok.
     * liste = en olası adaylar (weight: en iyiye göre göreli).
     */
    fun candidates(clueText: String, length: Int, pattern: String): List<Candidate>? {
        lastReason = ""
        if (!db.isLoaded) { lastReason = "sözlük yüklü değil"; return null }
        if (clueText.count { it.isLetter() } < MIN_LETTERS) {
            lastReason = "yazı okunamadı (resimli?)"; return null
        }

        elementSymbol(clueText, length, pattern)?.let { sym ->
            lastReason = "ÇÖZÜLDÜ (element): $sym"
            return listOf(Candidate(sym, 1.0))
        }

        val q = TdkDb.stems(clueText)
        if (q.isEmpty()) { lastReason = "anahtar kelime yok"; return null }

        val hits = db.search(length, pattern, q)
        if (hits.isEmpty()) { lastReason = "sözlükte eşleşme yok"; return null }

        val top = hits[0].score
        val minTop = if (length <= 2) MIN_TOP_SHORT else MIN_TOP
        if (top < minTop) {
            lastReason = "düşük güven (" + String.format("%.1f", top) + ") en iyi: " + hits.first().word
            Log.d(TAG, "düşük güven: \"" + clueText + "\" -> " + hits.first().word)
            return null
        }
        val tied = hits.count { it.score >= top * 0.9 }
        if (tied > MAX_TIED) {
            lastReason = "belirsiz ($tied eşit aday)"
            Log.d(TAG, "belirsiz ($tied eşit aday): \"" + clueText + "\"")
            return null
        }

        val keep = hits.filter { it.score >= top * KEEP_RATIO }.take(MAX_CANDS)
        lastReason = "ÇÖZÜLDÜ: " + keep.take(3).joinToString(",") { it.word }
        Log.d(TAG, "\"" + clueText + "\" -> " + keep.joinToString { it.word + "(" + String.format("%.1f", it.score) + ")" })
        return keep.map {
            val r = it.score / top
            Candidate(it.word, (r * r).coerceAtLeast(0.05))
        }
    }

    /** Küçük yazıyı ML Kit okuyabilsin diye büyütür ve kenarlarına boşluk ekler. */
    private fun enlarge(src: Bitmap): Bitmap {
        val scale = (TARGET_H / src.height.toFloat()).coerceIn(1f, 4f)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val pad = 24
        val out = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val p = src.getPixel(minOf(1, src.width - 1), minOf(1, src.height - 1))
        canvas.drawColor((0xFF shl 24) or (p and 0xFFFFFF))
        canvas.drawBitmap(
            src, null,
            android.graphics.Rect(pad, pad, pad + w, pad + h),
            Paint(Paint.FILTER_BITMAP_FLAG)
        )
        return out
    }
}
