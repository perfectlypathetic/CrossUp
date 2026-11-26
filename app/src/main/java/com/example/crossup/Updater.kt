package com.example.crossup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

    // ---- Güncelleme durumu (uygulama ve servis ortak kullanır) ----
    /** 0 = güncelleme yok. Doluysa bu sürüm şu an indiriliyor / kurulumu bekleniyor: tekrar sorma. */
    @Volatile var busyBuild = 0
        private set
    @Volatile var status = ""
        private set
    /** Ekran açıkken durum metnini canlı göstermek için (MainActivity ayarlar). */
    @Volatile var onStatus: ((String) -> Unit)? = null

    fun begin(build: Int) { busyBuild = build; setStatus("İndirme başlıyor…") }
    fun setStatus(s: String) { status = s; try { onStatus?.invoke(s) } catch (e: Throwable) { } }
    fun finish(s: String) { busyBuild = 0; setStatus(s) }

    private const val PREFS = "updater"
    private const val KEY_RELAUNCH = "relaunch"
    fun setRelaunch(ctx: Context, on: Boolean) {
        try { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RELAUNCH, on).apply() } catch (e: Throwable) { }
    }
    fun consumeRelaunch(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = p.getBoolean(KEY_RELAUNCH, false)
        if (v) p.edit().putBoolean(KEY_RELAUNCH, false).apply()
        return v
    }

    /** Dokununca açılan bildirim (yüksek öncelik). */
    fun postNotif(ctx: Context, id: Int, title: String, text: String, pi: PendingIntent?) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("upd", "Güncelleme", NotificationManager.IMPORTANCE_HIGH))
            val b = Notification.Builder(ctx, "upd").setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setAutoCancel(true)
            if (pi != null) b.setContentIntent(pi)
            nm.notify(id, b.build())
        } catch (e: Throwable) { }
    }

    fun cancelNotif(ctx: Context, id: Int) {
        try { ctx.getSystemService(NotificationManager::class.java).cancel(id) } catch (e: Throwable) { }
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
        setRelaunch(ctx, true)      // güncelleme bitince uygulama kendiliğinden açılsın
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
            setRelaunch(ctx, false)
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
        val stopSvc = { ctx.stopService(Intent(ctx, UpdateService::class.java)); Unit }
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                Updater.setStatus("Kurulum onayı bekleniyor… Çıkan pencerede \"Güncelle\"ye bas.")
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { ctx.startActivity(confirm) } catch (e: Throwable) { }
                    // Pencere arka planda açılamazsa diye bildirimden de onaylanabilir.
                    val pi = PendingIntent.getActivity(ctx, 0, confirm,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    Updater.postNotif(ctx, 2, "CrossUp güncellemesi hazır", "Kurmak için dokun", pi)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Updater.cancelNotif(ctx, 2)
                Updater.finish("Güncellendi.")
                stopSvc()
            }
            else -> {
                Updater.cancelNotif(ctx, 2)
                Updater.setRelaunch(ctx, false)
                val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
                val m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                val text = when {
                    st == PackageInstaller.STATUS_FAILURE_ABORTED -> "Güncelleme iptal edildi."
                    m.contains("INCOMPATIBLE", ignoreCase = true) || m.contains("signature", ignoreCase = true) ->
                        "Güncelleme kurulamadı: imza uyuşmuyor. Eski uygulamayı silip son APK'yı elle kur."
                    else -> "Güncelleme kurulamadı: $m"
                }
                Updater.finish(text)
                stopSvc()
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * İndirme + kurulum başlatma işini ön planda çalışan servis yapar: uygulama arka plana geçse de,
 * başka uygulamaya geçilse de indirme devam eder. İlerleme bildirimde görünür.
 */
class UpdateService : Service() {
    private var worker: Thread? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("url") ?: ""
        val build = intent?.getIntExtra("build", 0) ?: 0
        val tag = intent?.getStringExtra("tag") ?: ""
        if (url.isEmpty() || build == 0) { stopSelf(); return START_NOT_STICKY }

        startForeground(NOTIF_ID, notif("Güncelleme hazırlanıyor…", -1), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (worker?.isAlive == true) return START_NOT_STICKY    // zaten çalışıyor: ikinci kez başlatma

        Updater.begin(build)
        worker = Thread {
            try {
                val rel = Updater.Release(build, tag, url, "")
                val apk = Updater.download(applicationContext, rel) { p ->
                    Updater.setStatus("İndiriliyor… %$p")
                    update("İndiriliyor… %$p", p)
                }
                Updater.setStatus("Kurulum başlatılıyor…")
                update("Kurulum başlatılıyor…", -1)
                Updater.install(applicationContext, apk)
                Updater.setStatus("Kurulum onayı bekleniyor…")
                update("Kurulum onayı bekleniyor…", -1)
                // Onay hiç gelmezse servis sonsuza dek açık kalmasın.
                handler.postDelayed({
                    if (Updater.busyBuild == build) Updater.finish("Kurulum onayı gelmedi. Tekrar dene.")
                    stopSelf()
                }, 180_000)
            } catch (e: Throwable) {
                GameLog.e("Update", "güncelleme hatası", e)
                Updater.finish("Güncelleme hatası: ${e.message ?: e.javaClass.simpleName}")
                stopSelf()
            }
        }.also { it.start() }
        return START_NOT_STICKY
    }

    private fun notif(text: String, progress: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("upd_prog", "Güncelleme ilerlemesi", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "upd_prog")
            .setContentTitle("CrossUp güncelleniyor").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true).setContentIntent(open)
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .build()
    }

    private fun update(text: String, progress: Int) {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif(text, progress)) } catch (e: Throwable) { }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object { const val NOTIF_ID = 77 }
}

/** Güncelleme kurulunca uygulamayı kendiliğinden yeniden açar (sadece uygulamanın kendi güncellemesinde). */
class AppUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Updater.consumeRelaunch(ctx)) return
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(launch) } catch (e: Throwable) { }
        // Sistem arka plandan açmaya izin vermezse bildirime dokunarak açılır.
        val pi = PendingIntent.getActivity(ctx, 1, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        Updater.postNotif(ctx, 3, "CrossUp güncellendi", "Açmak için dokun", pi)
    }
}
