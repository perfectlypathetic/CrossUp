package com.example.crossup.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bulut eşitleme: kullanıcının seçtiği tek bir dosyaya (ör. Google Drive'da) kelimeleri ve harf şablonlarını yazar.
 * Hep birleştirir: önce dosyayı okuyup yerel veriye ekler, sonra birleşmiş halini geri yazar.
 */
object CloudSync {
    private const val PREF = "p"
    private const val KEY_URI = "sync_uri"
    private const val KEY_MSG = "sync_msg"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun uri(ctx: Context): Uri? = prefs(ctx).getString(KEY_URI, null)?.let { Uri.parse(it) }

    fun lastMessage(ctx: Context): String = prefs(ctx).getString(KEY_MSG, "") ?: ""

    /** Seçilen dosyayı hatırlar ve kalıcı erişim izni alır. */
    fun setUri(ctx: Context, uri: Uri, flags: Int) {
        val rw = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, rw) }
        prefs(ctx).edit().putString(KEY_URI, uri.toString()).apply()
    }

    fun disconnect(ctx: Context) {
        prefs(ctx).edit().remove(KEY_URI).remove(KEY_MSG).apply()
    }

    private fun buildJson(ctx: Context): String =
        JSONObject()
            .put("v", 3)
            .put("clues", ClueDb.get(ctx).snapshot())
            .put("letters", LetterDb.get(ctx).snapshot())
            .toString()

    /** Metni yerel veriye birleştirir. Eski yedek biçimini (sadece kelimeler) de tanır. Eklenen kayıt sayısını döner. */
    private fun mergeText(ctx: Context, text: String): Int {
        val o = JSONObject(text)
        val clues = if (o.has("clues")) o.getJSONObject("clues") else o
        var added = ClueDb.get(ctx).mergeJson(clues)
        o.optJSONArray("letters")?.let { added += LetterDb.get(ctx).mergeJson(it) }
        return added
    }

    /** Elle yedek: dosyaya yaz. */
    fun exportTo(ctx: Context, out: OutputStream) {
        out.write(buildJson(ctx.applicationContext).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Elle yedek: dosyadan birleştirerek oku. Eklenen kayıt sayısını döner. */
    fun importFrom(ctx: Context, inp: InputStream): Int =
        mergeText(ctx.applicationContext, inp.readBytes().toString(Charsets.UTF_8))

    /** Buluttan oku, birleştir, geri yaz. Mesaj döner (hata olsa da fırlatmaz). */
    @Synchronized
    fun sync(ctx: Context): String {
        val app = ctx.applicationContext
        val uri = uri(app) ?: return "Bulut bağlı değil"
        val msg = try {
            val text = app.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: ""
            val added = if (text.isNotBlank()) mergeText(app, text) else 0

            val bytes = buildJson(app).toByteArray(Charsets.UTF_8)
            val out = try {
                app.contentResolver.openOutputStream(uri, "wt")
            } catch (e: Exception) {
                app.contentResolver.openOutputStream(uri, "w")
            } ?: error("Dosya yazılamadı")
            out.use { it.write(bytes); it.flush() }
            "Bulut eşitlendi (buluttan $added yeni kayıt)"
        } catch (e: Exception) {
            "Bulut hatası: ${e.message ?: e.javaClass.simpleName}"
        }
        val time = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date())
        prefs(app).edit().putString(KEY_MSG, "$time — $msg").apply()
        return msg
    }
}
