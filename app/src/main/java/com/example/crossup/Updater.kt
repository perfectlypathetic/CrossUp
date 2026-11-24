package com.example.crossup

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases üzerinden kendini güncelleme.
 * Depo adı ve sürüm numarası derleme sırasında (GitHub Actions) BuildConfig'e yazılır.
 * Tüm fonksiyonlar ağ kullanır: ana iş parçacığından ÇAĞIRMA.
 */
object Updater {

    data class Release(val build: Int, val tag: String, val apkUrl: String, val notes: String) {
        val isNewer: Boolean get() = build > BuildConfig.BUILD_NUMBER
    }

    private fun open(url: String, json: Boolean): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "CrossUp-Updater")
        if (json) c.setRequestProperty("Accept", "application/vnd.github+json")
        return c
    }

    /** GitHub'daki en son sürümü getirir. Hata olursa Exception fırlatır. */
    fun latest(): Release {
        val repo = BuildConfig.GH_REPO
        if (repo.isBlank()) throw Exception("Depo bilinmiyor (APK, GitHub Actions ile derlenmemiş)")

        val c = open("https://api.github.com/repos/$repo/releases/latest", true)
        try {
            val code = c.responseCode
            if (code == 404) throw Exception("GitHub'da henüz yayınlanmış sürüm yok (veya depo gizli)")
            GameLog.d("Updater", "GitHub yanıt kodu=$code")
            if (code != 200) throw Exception("GitHub yanıtı: $code")
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val o = JSONObject(text)
            val tag = o.optString("tag_name")
            val build = tag.removePrefix("v").toIntOrNull()
                ?: throw Exception("Sürüm etiketi anlaşılamadı: $tag")
            val assets = o.optJSONArray("assets") ?: JSONArray()
            var url = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url")
                    break
                }
            }
            if (url.isEmpty()) throw Exception("Sürümde APK dosyası yok")
            val rel = Release(build, tag, url, o.optString("body"))
            GameLog.i("Updater", "son sürüm: $tag (build=$build) şu an=v${BuildConfig.BUILD_NUMBER} yeni mi=${rel.isNewer} apk=$url")
            return rel
        } finally {
            c.disconnect()
        }
    }

    /** APK'yı indirir. İlerleme 0-100 olarak bildirilir. */
    fun download(ctx: Context, rel: Release, onProgress: (Int) -> Unit): File {
        val out = File(ctx.cacheDir, "update.apk")
        if (out.exists()) out.delete()
        val c = open(rel.apkUrl, false)
        GameLog.i("Updater", "APK indiriliyor: ${rel.apkUrl}")
        try {
            if (c.responseCode != 200) throw Exception("İndirme hatası: ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
            if (total > 0 && out.length() != total) throw Exception("İndirme yarım kaldı")
            GameLog.i("Updater", "APK indirildi: ${out.length()} bayt (beklenen $total)")
        } finally {
            c.disconnect()
        }
        return out
    }

    /** İndirilen APK'yı sistem yükleyicisine verir (kullanıcıya "Güncelle" onayı çıkar). */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        GameLog.i("Updater", "kurulum oturumu açıldı: id=$id apk=${apk.length()} bayt")
        val session = installer.openSession(id)
        try {
            apk.inputStream().use { inp ->
                session.openWrite("update.apk", 0, apk.length()).use { o ->
                    inp.copyTo(o)
                    session.fsync(o)
                }
            }
            val intent = Intent(ctx, InstallReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                ctx, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pending.intentSender)
            GameLog.i("Updater", "kurulum onaya gönderildi (session.commit)")
        } catch (e: Exception) {
            GameLog.e("Updater", "kurulum hatası", e)
            session.abandon()
            throw e
        } finally {
            session.close()
        }
    }
}

/** Sistem yükleyicisinden gelen sonucu karşılar. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> { /* uygulama zaten yenilenip kapanır */ }
            else -> {
                val m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                val text = if (m.contains("INCOMPATIBLE", ignoreCase = true) || m.contains("signature", ignoreCase = true))
                    "Güncelleme kurulamadı: imza uyuşmuyor. Eski uygulamayı silip son APK'yı elle kur."
                else "Güncelleme kurulamadı: $m"
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
            }
        }
    }
}
