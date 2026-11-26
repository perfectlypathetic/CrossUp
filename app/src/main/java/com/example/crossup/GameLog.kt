package com.example.crossup

import android.app.Activity
import android.content.ClipData
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * CrossUp günlüğü (log).
 *
 * Oyun boyunca her şeyi bir dosyaya yazar: buton basışları, ekran yakalama, ızgara okuma,
 * harf tanıma, ipucu çözme, skor takibi, plan hesabı, hatalar, çökmeler.
 *
 * TEK dosya kullanılır (crossup_log.txt). Uygulama kapanıp açılsa da aynı dosyaya EKLENİR.
 * Yeni dosya ancak "Eski logları sil" tuşuna basınca açılır.
 *
 * Dosya iki yere yazılır:
 *  1) Uygulamanın kendi klasörüne (filesDir/logs) - her zaman çalışır.
 *  2) Telefonun İndirilenler/CrossUp klasörüne - dosya yöneticisinden görebilirsin.
 *
 * Ekran görüntüleri de (Analiz / Harfleri tanı / Öğren basışlarında) aynı klasöre JPEG olarak kaydedilir.
 *
 * VARSAYILAN: KAPALI. Ana ekrandaki "Log kaydı" anahtarıyla açılır; kapatana kadar açık kalır
 * (telefon yeniden başlasa bile hatırlanır). Kapalıyken hiçbir dosya oluşturulmaz, ekran görüntüsü kaydedilmez.
 *
 * Hiçbir log çağrısı uygulamayı çökertmez; hata olursa sessizce yutulur.
 * Yazma işi ayrı bir iş parçacığında yapıldığı için oyunu yavaşlatmaz.
 */
object GameLog {
    private const val SUB = "CrossUp"
    private const val DIR = "CrossUp"
    private const val MAX_BYTES = 60L * 1024 * 1024
    private const val LOG_NAME = "crossup_log.txt"   // tek ve sabit log dosyası
    private const val MAX_SHOTS = 80

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GameLogWriter").apply { isDaemon = true }
    }

    // ---- Sadece yazıcı iş parçacığında kullanılan durum ----
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var ready = false
    private var sessionName = ""
    private var internalFile: File? = null
    private var internalOut: OutputStream? = null
    private var dlUri: Uri? = null
    private var dlOut: OutputStream? = null
    private var dlErr = ""
    private var bytes = 0L
    private var capped = false
    private var shotsSaved = 0
    private val pre = ArrayList<String>()

    // ---- Her yerden okunabilen ----
    @Volatile private var app: Context? = null
    @Volatile private var sessionStamp = ""
    @Volatile private var currentName = ""
    @Volatile private var currentBytes = 0L
    @Volatile private var crashHooked = false
    private val shotSeq = AtomicInteger(0)

    /** Log kaydı açık mı? Varsayılan KAPALI. Ayar telefonda saklanır. */
    @Volatile var enabled = false
        private set
    private const val PREFS = "crossup_log"
    private const val KEY_ON = "enabled"

    /** Kayıtlı ayarı okur (uygulama açılırken). */
    private fun loadSetting(a: Context) {
        enabled = try { a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false) } catch (ex: Throwable) { false }
    }

    fun isEnabled(ctx: Context): Boolean {
        loadSetting(ctx.applicationContext)
        return enabled
    }

    /** Menüdeki anahtar: log kaydını açar/kapatır ve seçimi hatırlar. */
    fun setEnabled(ctx: Context, on: Boolean) {
        try {
            val a = ctx.applicationContext
            app = a
            try { a.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply() } catch (ex: Throwable) { }
            enabled = on
            if (on) {
                hookCrash()
                exec.execute { startSession(a) }
            } else {
                exec.execute {
                    try { internalOut?.flush(); internalOut?.close() } catch (ex: Throwable) { }
                    try { dlOut?.flush(); dlOut?.close() } catch (ex: Throwable) { }
                    internalOut = null; dlOut = null; dlUri = null
                    ready = false
                    pre.clear()
                }
            }
        } catch (ex: Throwable) {
        }
    }

    // ------------------------------------------------------------------ genel kullanım

    fun d(tag: String, msg: String) = log("D", tag, msg, null)
    fun i(tag: String, msg: String) = log("I", tag, msg, null)
    fun w(tag: String, msg: String) = log("W", tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable? = null) = log("E", tag, msg, t)

    /** Log dosyasında kolay bulunsun diye başlık satırı. */
    fun section(title: String) = log("I", "====", "========== $title ==========", null)

    /** Ondalık sayıyı noktalı yazar (telefon dili ne olursa olsun). */
    fun f(d: Double, n: Int = 2): String =
        try { String.format(Locale.US, "%." + n + "f", d) } catch (ex: Throwable) { d.toString() }

    fun mem(): String {
        val r = Runtime.getRuntime()
        val used = (r.totalMemory() - r.freeMemory()) / 1048576
        return "bellek ${used}MB/${r.maxMemory() / 1048576}MB"
    }

    /** Log dosyasını açar (varsa sonuna ekler). Zaten açıksa dokunmaz. forceNew artık kullanılmıyor, uyumluluk için duruyor. */
    fun init(ctx: Context, forceNew: Boolean = false) {
        try {
            val a = ctx.applicationContext
            app = a
            loadSetting(a)
            if (!enabled) return          // kapalıyken dosya açılmaz
            hookCrash()
            exec.execute { startSession(a) }
        } catch (ex: Throwable) {
        }
    }

    /** Ekranda göstermek için kısa durum satırı. */
    fun statusLine(): String {
        if (!enabled) return "Log kaydı: kapalı"
        if (currentName.isEmpty()) return "Log: henüz başlamadı"
        return "Log: $currentName (${currentBytes / 1024} KB)\nKonum: İndirilenler/$DIR (ekran görüntüleri de orada)"
    }

    /** Bekleyen satırların yazılmasını (en fazla 1,5 sn) bekler. */
    fun flush() {
        try {
            exec.submit(Runnable { }).get(1500, TimeUnit.MILLISECONDS)
        } catch (ex: Throwable) {
        }
    }

    // ------------------------------------------------------------------ ekran görüntüsü

    /** Ekran görüntüsünü İndirilenler/CrossUp klasörüne JPEG olarak kaydeder. Dosya adını döner. */
    fun saveShot(bmp: Bitmap, label: String): String {
        if (!enabled) return ""
        val a = app ?: return ""
        val n = shotSeq.incrementAndGet()
        val safe = label.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }.take(20)
        val name = "crossup_shot_${sessionStamp}_${n}_$safe.jpg"
        try {
            exec.execute {
                try {
                    if (shotsSaved >= MAX_SHOTS) {
                        direct("W", "Shot", "ekran görüntüsü atlandı (en fazla $MAX_SHOTS): $name")
                        return@execute
                    }
                    val u = insertDownload(a, name, "image/jpeg")
                    if (u == null) {
                        direct("W", "Shot", "ekran görüntüsü kaydedilemedi (MediaStore): $name")
                        return@execute
                    }
                    a.contentResolver.openOutputStream(u)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 75, it) }
                    shotsSaved++
                    direct("I", "Shot", "ekran görüntüsü kaydedildi: İndirilenler/$DIR/$name (${bmp.width}x${bmp.height})")
                } catch (ex: Throwable) {
                    direct("E", "Shot", "ekran görüntüsü hatası: ${ex.message}")
                }
            }
        } catch (ex: Throwable) {
        }
        return name
    }

    // ------------------------------------------------------------------ paylaş / temizle

    /** En yeni (dolu) log dosyasını paylaşma penceresiyle açar. Mesajı onMsg'ye verir. */
    fun share(act: Activity, onMsg: (String) -> Unit) {
        val a = act.applicationContext
        try {
            exec.execute {
                val res: Pair<Uri?, String> = try {
                    pickExport(a)
                } catch (ex: Throwable) {
                    Pair(null, "Log hazırlanamadı: ${ex.message ?: ex.javaClass.simpleName}")
                }
                Handler(Looper.getMainLooper()).post {
                    val uri = res.first
                    if (uri == null) {
                        onMsg(res.second)
                    } else {
                        try {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri("log", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            val chooser = Intent.createChooser(send, "Logu paylaş")
                            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            act.startActivity(chooser)
                            onMsg(res.second)
                        } catch (ex: Throwable) {
                            onMsg(res.second + "\nPaylaşım penceresi açılamadı (${ex.message}). Dosyayı İndirilenler/$DIR klasöründen elle gönderebilirsin.")
                        }
                    }
                }
            }
        } catch (ex: Throwable) {
            onMsg("Log paylaşılamadı: ${ex.message}")
        }
    }

    /** Log dosyasını ve ekran görüntülerini siler, ardından boş yeni bir log dosyası açar. */
    fun clearOld(ctx: Context, onDone: (String) -> Unit) {
        val a = ctx.applicationContext
        try {
            exec.execute {
                val msg: String = try {
                    try { internalOut?.close() } catch (ex: Throwable) { }
                    try { dlOut?.close() } catch (ex: Throwable) { }
                    internalOut = null; dlOut = null; dlUri = null
                    ready = false
                    var n = 0
                    File(a.filesDir, "logs").listFiles()?.forEach { if (it.delete()) n++ }
                    var m = 0
                    for ((id, _) in queryOwn(a, null)) {
                        val u = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                        try { if (a.contentResolver.delete(u, null, null) > 0) m++ } catch (ex: Throwable) { }
                    }
                    shotsSaved = 0
                    if (enabled) {
                        startSession(a)
                        direct("I", "GameLog", "loglar silindi, yeni log dosyası açıldı")
                    }
                    "Loglar silindi (uygulama içi: $n, İndirilenler/$DIR: $m dosya)." +
                        (if (enabled) " Yeni boş log dosyası açıldı." else "")
                } catch (ex: Throwable) {
                    try { if (enabled) startSession(a) } catch (x: Throwable) { }
                    "Silme hatası: ${ex.message ?: ex.javaClass.simpleName}"
                }
                Handler(Looper.getMainLooper()).post { onDone(msg) }
            }
        } catch (ex: Throwable) {
            onDone("Silinemedi: ${ex.message}")
        }
    }

    // ------------------------------------------------------------------ iç işler

    private fun log(level: String, tag: String, msg: String, t: Throwable?) {
        if (!enabled) return
        try {
            val ts = System.currentTimeMillis()
            val th = Thread.currentThread().name
            val text = if (t == null) msg else msg + "\n" + android.util.Log.getStackTraceString(t)
            try {
                when (level) {
                    "E" -> android.util.Log.e("$SUB/$tag", text)
                    "W" -> android.util.Log.w("$SUB/$tag", text)
                    else -> android.util.Log.d("$SUB/$tag", text)
                }
            } catch (ex: Throwable) {
            }
            exec.execute { write(ts, th, level, tag, text) }
        } catch (ex: Throwable) {
        }
    }

    /** Yazıcı iş parçacığının kendi içinden doğrudan satır yazar. */
    private fun direct(level: String, tag: String, text: String) {
        write(System.currentTimeMillis(), "GameLogWriter", level, tag, text)
    }

    private fun write(ts: Long, th: String, level: String, tag: String, text: String) {
        try {
            val line = fmt.format(Date(ts)) + " " + level + " [" + th + "] " + tag + ": " +
                text.replace("\n", "\n        ") + "\n"
            if (!ready) {
                if (enabled && pre.size < 3000) pre.add(line)
                return
            }
            append(line)
        } catch (ex: Throwable) {
        }
    }

    private fun append(line: String) {
        if (capped) return
        val b = line.toByteArray(Charsets.UTF_8)
        if (bytes + b.size > MAX_BYTES) {
            capped = true
            raw("!!! LOG SINIRI (60 MB) AŞILDI, yazma durduruldu. \"Eski logları sil\" tuşuna bas !!!\n".toByteArray(Charsets.UTF_8))
            return
        }
        bytes += b.size
        currentBytes = bytes
        raw(b)
    }

    private fun raw(b: ByteArray) {
        try { internalOut?.write(b) } catch (ex: Throwable) { }
        try { dlOut?.write(b) } catch (ex: Throwable) { }
    }

    private fun startSession(a: Context) {
        try {
            if (ready) return
            sessionStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            sessionName = LOG_NAME
            currentName = sessionName
            capped = false; shotsSaved = 0
            dlErr = ""
            bytes = 0L; currentBytes = 0L
            var appended = false

            // 1) Uygulama içi kopya (varsa sonuna eklenir)
            try {
                val dir = File(a.filesDir, "logs")
                dir.mkdirs()
                val f = File(dir, sessionName)
                internalFile = f
                bytes = f.length()
                appended = bytes > 0
                internalOut = FileOutputStream(f, true)
            } catch (ex: Throwable) {
                internalOut = null
            }
            currentBytes = bytes

            // 2) İndirilenler/CrossUp kopyası (varsa sonuna eklenir)
            try {
                val existing = queryOwn(a, sessionName).firstOrNull()
                val u: Uri? = if (existing != null)
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, existing.first)
                else insertDownload(a, sessionName, "text/plain")
                if (u != null) {
                    dlUri = u
                    dlOut = a.contentResolver.openOutputStream(u, if (existing != null) "wa" else "w")
                    if (dlOut == null) dlErr = "akış açılamadı"
                } else {
                    dlErr = "MediaStore kayıt vermedi"
                }
            } catch (ex: Throwable) {
                dlErr = ex.message ?: ex.javaClass.simpleName
                dlOut = null
            }

            ready = true
            append((if (appended) "\n\n" else "") + header(a))
            for (l in pre) append(l)
            pre.clear()
        } catch (ex: Throwable) {
        }
    }

    private fun header(a: Context): String {
        val dm = a.resources.displayMetrics
        val sb = StringBuilder()
        sb.append("==================== CROSSUP LOG ====================\n")
        sb.append("Dosya       : ").append(sessionName).append(" (tek dosya, her açılışta sona eklenir)\n")
        sb.append("Açılış      : ").append(sessionStamp).append("\n")
        sb.append("Sürüm       : v").append(BuildConfig.BUILD_NUMBER).append(" (repo=").append(BuildConfig.GH_REPO).append(")\n")
        sb.append("Cihaz       : ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
            .append(" | Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Ekran       : ").append(dm.widthPixels).append("x").append(dm.heightPixels)
            .append(" dpi=").append(dm.densityDpi).append(" yoğunluk=").append(dm.density).append("\n")
        sb.append("Dil/Saat    : ").append(Locale.getDefault()).append(" | ").append(java.util.TimeZone.getDefault().id).append("\n")
        sb.append("Bellek      : ").append(mem()).append("\n")
        sb.append("Uygulama içi: ").append(internalFile?.absolutePath ?: "YAZILAMADI").append("\n")
        sb.append("İndirilenler: ").append(if (dlOut != null) "İndirilenler/$DIR/$sessionName" else "YAZILAMADI ($dlErr)").append("\n")
        sb.append("Saat biçimi : SS:DD:ss.mmm SEVİYE [iş parçacığı] ETİKET: mesaj  (D=ayrıntı I=bilgi W=uyarı E=hata)\n")
        sb.append("Not         : Tahta çizimlerinde satır/sütun 1'den başlar, ekranda gösterilen (satır,sütun) ile aynıdır.\n")
        sb.append("=====================================================\n")
        return sb.toString()
    }

    private fun insertDownload(a: Context, name: String, mime: String): Uri? {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR)
        }
        return a.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
    }

    /** Uygulamanın kendi oluşturduğu İndirilenler dosyalarını listeler: (id, ad). nameEq doluysa o ada göre. */
    private fun queryOwn(a: Context, nameEq: String?): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        val proj = arrayOf(BaseColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        val sel: String
        val args: Array<String>
        if (nameEq != null) {
            sel = MediaStore.MediaColumns.DISPLAY_NAME + "=?"
            args = arrayOf(nameEq)
        } else {
            sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?"
            args = arrayOf(Environment.DIRECTORY_DOWNLOADS + "/" + DIR + "%")
        }
        a.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, sel, args, null)?.use { c ->
            while (c.moveToNext()) out.add(Pair(c.getLong(0), c.getString(1) ?: ""))
        }
        return out
    }

    /** Paylaşılacak log dosyasının adresini bulur (yoksa İndirilenler'e kopyalar). Yazıcı iş parçacığında çalışır. */
    private fun pickExport(a: Context): Pair<Uri?, String> {
        val dir = File(a.filesDir, "logs")
        val cur = internalFile
        val target: File = if (cur != null && cur.exists() && cur.length() > 0) {
            cur   // her zaman şu anki oturumun dosyası
        } else {
            dir.listFiles()?.filter { it.isFile && it.length() > 0 }
                ?.maxByOrNull { it.lastModified() }
                ?: return Pair(null, "Paylaşılacak log dosyası yok.")
        }
        val name = target.name
        val kb = target.length() / 1024
        direct("I", "GameLog", "paylaşım istendi: $name ($kb KB)")

        if (target == internalFile && dlUri != null && dlOut != null) {
            return Pair(dlUri, "Log paylaşılıyor: $name ($kb KB)")
        }
        val found = queryOwn(a, name).firstOrNull()
        if (found != null) {
            val u = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, found.first)
            return Pair(u, "Log paylaşılıyor: $name ($kb KB)")
        }
        val u = insertDownload(a, name, "text/plain")
            ?: return Pair(null, "İndirilenler'e kopya oluşturulamadı.")
        a.contentResolver.openOutputStream(u)?.use { out ->
            target.inputStream().use { it.copyTo(out) }
        }
        return Pair(u, "Log İndirilenler/$DIR klasörüne kopyalandı ve paylaşılıyor: $name ($kb KB)")
    }

    private fun hookCrash() {
        if (crashHooked) return
        crashHooked = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, ex ->
            try {
                log("E", "CRASH", "ÇÖKME! iş parçacığı=${t.name}", ex)
                exec.submit(Runnable { }).get(2, TimeUnit.SECONDS)
            } catch (x: Throwable) {
            }
            prev?.uncaughtException(t, ex)
        }
    }
}



