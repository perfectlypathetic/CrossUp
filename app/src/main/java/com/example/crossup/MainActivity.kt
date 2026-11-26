package com.example.crossup

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import com.example.crossup.ai.ClueDb
import com.example.crossup.ai.CloudSync
import com.example.crossup.ai.LetterDb
import com.example.crossup.ai.TdkDb
import com.example.crossup.service.CaptureService

class MainActivity : Activity() {
    private lateinit var keyBox: EditText
    private lateinit var info: TextView
    private lateinit var updInfo: TextView

    // Güncelleme kontrolü için yardımcı değişkenler
    private var lastCheck = 0L                       // en son ne zaman kontrol edildi
    private var dialogShowing = false                // "Yeni sürüm" penceresi açık mı
    private var pendingRel: Updater.Release? = null  // izin bekleyen güncelleme

    private companion object {
        const val REQ_PROJECTION = 100
        const val REQ_CLOUD_NEW = 103
        const val REQ_CLOUD_PICK = 104
        const val CHECK_INTERVAL_MS = 30_000L        // art arda kontrolü engeller
        var declinedBuild = 0                        // "Sonra" denen sürüm, tekrar sorulmaz
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        GameLog.init(this)
        GameLog.i("Main", "MainActivity.onCreate | sürüm v${BuildConfig.BUILD_NUMBER}")
        val prefs = getSharedPreferences("p", Context.MODE_PRIVATE)
        keyBox = EditText(this).apply { hint = "Gemini API anahtarı (AI Studio)"; setText(prefs.getString("key", "")) }
        val go = Button(this).apply { text = "Başlat"; setOnClickListener {
            GameLog.i("Main", "BUTON: Başlat (üstteGösterme izni=${Settings.canDrawOverlays(this@MainActivity)}, API anahtarı ${if (keyBox.text.toString().isBlank()) "YOK" else "var"})")
            prefs.edit().putString("key", keyBox.text.toString().trim()).apply()
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                Toast.makeText(this@MainActivity, "Üstte gösterme iznini ver, sonra tekrar Başlat", Toast.LENGTH_LONG).show()
            } else if (prefs.getBoolean("skipFilterGuide", false)) {
                startCaptureRequest()
            } else {
                showFilterGuide(prefs)
            }
        } }

        info = TextView(this).apply { textSize = 14f; setPadding(0, 40, 0, 10) }

        updInfo = TextView(this).apply {
            textSize = 14f; setPadding(0, 10, 0, 10)
            text = "Sürüm: v${BuildConfig.BUILD_NUMBER}"
        }

        val cloudNew = Button(this).apply {
            text = "Bulut: yeni dosya oluştur (ilk cihaz)"
            setOnClickListener {
                GameLog.i("Main", "BUTON: Bulut yeni dosya")
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "crossup_bulut.json")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    }, REQ_CLOUD_NEW
                )
            }
        }
        val cloudPick = Button(this).apply {
            text = "Bulut: mevcut dosyayı seç (diğer cihaz)"
            setOnClickListener {
                GameLog.i("Main", "BUTON: Bulut mevcut dosya")
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    }, REQ_CLOUD_PICK
                )
            }
        }
        val filterNote = TextView(this).apply {
            textSize = 13f; setPadding(0, 20, 0, 6)
            text = "⚠ Oynamadan önce ekran filtrelerini kapat: gece ışığı, Twilight, ekstra karartma, göz konforu kalkanı, okuma modu, renk ters çevirme. Renkleri değiştirdikleri için harf/hücre okumayı bozar."
        }
        val openDisplay = Button(this).apply {
            text = "Ekran ayarlarını aç"
            setOnClickListener { openSettings(Settings.ACTION_DISPLAY_SETTINGS) }
        }
        val openAccess = Button(this).apply {
            text = "Erişilebilirlik ayarlarını aç"
            setOnClickListener { openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        }
        val logSwitch = Switch(this).apply {
            text = "Log kaydı (sadece sorun arayan geliştirici için)"
            setPadding(0, 20, 0, 20)
            isChecked = GameLog.isEnabled(this@MainActivity)
        }
        val logShare = Button(this).apply {
            text = "Logu paylaş / gönder"
            setOnClickListener {
                GameLog.i("Main", "BUTON: Logu paylaş")
                GameLog.share(this@MainActivity) { m ->
                    Toast.makeText(this@MainActivity, m, Toast.LENGTH_LONG).show()
                    refreshInfo()
                }
            }
        }
        val logClear = Button(this).apply {
            text = "Eski logları sil"
            setOnClickListener {
                GameLog.i("Main", "BUTON: Eski logları sil")
                GameLog.clearOld(this@MainActivity) { m ->
                    Toast.makeText(this@MainActivity, m, Toast.LENGTH_LONG).show()
                    refreshInfo()
                }
            }
        }

        // Log düğmeleri sadece log açıkken görünür.
        fun applyLogUi(on: Boolean) {
            val v = if (on) android.view.View.VISIBLE else android.view.View.GONE
            logShare.visibility = v; logClear.visibility = v
        }
        applyLogUi(logSwitch.isChecked)
        logSwitch.setOnCheckedChangeListener { _, on ->
            GameLog.setEnabled(this@MainActivity, on)
            applyLogUi(on)
            refreshInfo()
            Toast.makeText(this@MainActivity,
                if (on) "Log kaydı AÇIK (kapatana kadar açık kalır)" else "Log kaydı kapatıldı",
                Toast.LENGTH_SHORT).show()
        }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40)
                addView(keyBox); addView(go)
                addView(filterNote); addView(openDisplay); addView(openAccess)
                addView(info)
                addView(updInfo)
                addView(cloudNew); addView(cloudPick)
                addView(logSwitch); addView(logShare); addView(logClear)
            })
        })
        // Güncelleme kontrolü onResume'da otomatik yapılır (uygulama her açıldığında).
    }

    // ---------- Ekran filtresi uyarısı ----------

    private fun startCaptureRequest() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
    }

    private fun openSettings(action: String) {
        try { startActivity(Intent(action)) } catch (e: Exception) {
            Toast.makeText(this, "Ayar ekranı açılamadı. Elle: Ayarlar'dan ekran / erişilebilirlik bölümüne gir.", Toast.LENGTH_LONG).show()
        }
    }

    /** Başlat'a basınca: filtreleri kapatma hatırlatması. */
    private fun showFilterGuide(prefs: SharedPreferences) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        box.addView(TextView(this).apply {
            textSize = 14f
            text = "Renkleri doğru okuyabilmem için şunları KAPAT:\n\n" +
                "• Gece ışığı / Night Light\n" +
                "• Twilight (ve benzeri filtre uygulamaları)\n" +
                "• Ekstra karartma (Extra dim)\n" +
                "• Göz konforu kalkanı (Samsung)\n" +
                "• Okuma modu\n" +
                "• Renk ters çevirme / renk düzeltme\n\n" +
                "Bunlar renkleri değiştirir; hücreler ve harfler yanlış okunabilir. " +
                "Android, uygulamaların bu ayarları kapatmasına izin vermez; elle kapatman gerekir."
        })
        val never = CheckBox(this).apply { text = "Bir daha gösterme" }
        box.addView(never)
        AlertDialog.Builder(this)
            .setTitle("Başlamadan önce")
            .setView(box)
            .setPositiveButton("Devam et") { _, _ ->
                if (never.isChecked) prefs.edit().putBoolean("skipFilterGuide", true).apply()
                startCaptureRequest()
            }
            .setNeutralButton("Ekran ayarları") { _, _ -> openSettings(Settings.ACTION_DISPLAY_SETTINGS) }
            .setNegativeButton("İptal", null)
            .show()
    }

    // ---------- Otomatik güncelleme (GitHub Releases) ----------

    private fun checkUpdate() {
        // Güncelleme zaten indiriliyor / kurulum onayı bekliyor: tekrar sorma.
        if (Updater.busyBuild != 0) { updInfo.text = Updater.status; return }
        val now = System.currentTimeMillis()
        if (now - lastCheck < CHECK_INTERVAL_MS) return
        lastCheck = now

        updInfo.text = "Güncelleme kontrol ediliyor…"
        Thread {
            val result = runCatching { Updater.latest() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val rel = result.getOrNull()
                if (rel == null) GameLog.w("Update", "kontrol başarısız: ${result.exceptionOrNull()?.message}")
                else GameLog.i("Update", "kontrol: son=${rel.tag} şu an=v${BuildConfig.BUILD_NUMBER} yeni=${rel.isNewer}")
                if (rel == null) {
                    updInfo.text = "Güncelleme kontrolü başarısız: ${result.exceptionOrNull()?.message}"
                } else if (rel.isNewer) {
                    updInfo.text = "Yeni sürüm var: ${rel.tag} (şu an: v${BuildConfig.BUILD_NUMBER})"
                    if (rel.build != declinedBuild && Updater.busyBuild == 0) askUpdate(rel)
                } else {
                    updInfo.text = "Uygulama güncel (v${BuildConfig.BUILD_NUMBER})"
                }
            }
        }.start()
    }

    private fun askUpdate(rel: Updater.Release) {
        if (dialogShowing) return
        dialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("Yeni sürüm: ${rel.tag}")
            .setMessage("Şu anki sürüm: v${BuildConfig.BUILD_NUMBER}\n\n${rel.notes}\n\nŞimdi güncellensin mi?")
            .setPositiveButton("Güncelle") { _, _ -> startUpdate(rel) }
            .setNegativeButton("Sonra") { _, _ -> declinedBuild = rel.build }
            .setOnDismissListener { dialogShowing = false }
            .show()
    }

    private fun startUpdate(rel: Updater.Release) {
        if (Updater.busyBuild != 0) { updInfo.text = Updater.status; return }   // çift başlatma yok
        GameLog.i("Update", "güncelleme başlatıldı: ${rel.tag} (kurulum izni=${packageManager.canRequestPackageInstalls()})")
        if (!packageManager.canRequestPackageInstalls()) {
            pendingRel = rel   // izin verilip geri dönünce güncelleme kendiliğinden devam eder
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Bu uygulamaya \"bilinmeyen uygulamaları yükle\" iznini ver, sonra uygulamaya geri dön; güncelleme otomatik devam eder", Toast.LENGTH_LONG).show()
            return
        }
        // Bildirim izni (Android 13+): indirme ilerlemesi bildirimde görünsün. Reddedilse de güncelleme sürer.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 200)
        }
        // İndirme + kurulum ön plan servisinde yapılır: başka uygulamaya geçsen de devam eder.
        Updater.begin(rel.build)
        updInfo.text = Updater.status
        try {
            startForegroundService(Intent(this, UpdateService::class.java)
                .putExtra("url", rel.apkUrl).putExtra("build", rel.build).putExtra("tag", rel.tag))
        } catch (e: Exception) {
            GameLog.e("Update", "güncelleme servisi başlatılamadı", e)
            Updater.finish("Güncelleme başlatılamadı: ${e.message}")
            updInfo.text = Updater.status
        }
    }

    override fun onPause() {
        Updater.onStatus = null
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        refreshInfo()
        Updater.cancelNotif(this, 2); Updater.cancelNotif(this, 3)
        // Güncelleme arka planda sürüyorsa durumunu canlı göster.
        Updater.onStatus = { m -> runOnUiThread { if (!isFinishing) updInfo.text = m } }
        if (Updater.busyBuild != 0) updInfo.text = Updater.status

        // İzin için ayarlara gitmiş ve izni vermişse güncellemeye kaldığı yerden devam et,
        // aksi halde yeni sürüm var mı diye otomatik bak.
        val pending = pendingRel
        if (pending != null && packageManager.canRequestPackageInstalls()) {
            pendingRel = null
            startUpdate(pending)
        } else {
            checkUpdate()
        }

        // TDK sözlüğünü arka planda yükle (yüklüyse hemen biter).
        val tdkDb = TdkDb.get(this)
        if (!tdkDb.isLoaded) {
            Thread {
                tdkDb.ensureLoaded()
                runOnUiThread { refreshInfo() }
            }.start()
        }

        // Bulut bağlıysa arka planda eşitle.
        if (CloudSync.uri(this) != null) {
            Thread {
                CloudSync.sync(applicationContext)
                runOnUiThread { refreshInfo() }
            }.start()
        }
    }

    private fun refreshInfo() {
        val cloud = if (CloudSync.uri(this) == null) "Bulut: bağlı değil"
        else "Bulut: bağlı\n" + CloudSync.lastMessage(this)
        val tdkDb = TdkDb.get(this)
        val tdkLine = when {
            tdkDb.isLoaded -> "TDK: yüklendi (${tdkDb.wordCount} madde)"
            tdkDb.lastError.isNotEmpty() -> "TDK: yüklenemedi (${tdkDb.lastError})"
            else -> "TDK: yükleniyor…"
        }
        info.text = "Kayıtlı kelime: ${ClueDb.get(this).size()}\n" +
            "Kayıtlı harf şablonu: ${LetterDb.get(this).size()}\n" +
            tdkLine + "\n" +
            cloud + "\n" +
            "API anahtarı buluta kaydedilmez, yeni cihazda elle yapıştır.\n" +
            GameLog.statusLine()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        GameLog.i("Main", "onActivityResult istek=$req sonuç=${if (res == RESULT_OK) "TAMAM" else res.toString()} veri=${data != null}")
        if (res != RESULT_OK || data == null) return

        when (req) {
            REQ_PROJECTION -> {
                GameLog.i("Main", "ekran yakalama izni verildi → servis başlatılıyor")
                startForegroundService(Intent(this, CaptureService::class.java)
                    .putExtra("code", res).putExtra("data", data).putExtra("key", keyBox.text.toString().trim()))
                moveTaskToBack(true)   // Bundle'a geç
            }
            REQ_CLOUD_NEW, REQ_CLOUD_PICK -> {
                val uri = data.data ?: return
                CloudSync.setUri(this, uri, data.flags)
                Toast.makeText(this, "Eşitleniyor…", Toast.LENGTH_SHORT).show()
                Thread {
                    val msg = CloudSync.sync(applicationContext)
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                        refreshInfo()
                    }
                }.start()
            }
        }
    }
}



