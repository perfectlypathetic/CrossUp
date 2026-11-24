package com.example.crossup

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
    @Volatile private var updating = false

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
        val prefs = getSharedPreferences("p", Context.MODE_PRIVATE)
        keyBox = EditText(this).apply { hint = "Gemini API anahtarı (AI Studio)"; setText(prefs.getString("key", "")) }
        val go = Button(this).apply { text = "Başlat"; setOnClickListener {
            prefs.edit().putString("key", keyBox.text.toString().trim()).apply()
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                Toast.makeText(this@MainActivity, "Üstte gösterme iznini ver, sonra tekrar Başlat", Toast.LENGTH_LONG).show()
            } else {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
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
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40)
                addView(keyBox); addView(go); addView(info)
                addView(updInfo)
                addView(cloudNew); addView(cloudPick)
            })
        })
        // Güncelleme kontrolü onResume'da otomatik yapılır (uygulama her açıldığında).
    }

    // ---------- Otomatik güncelleme (GitHub Releases) ----------

    private fun checkUpdate() {
        if (updating) return
        val now = System.currentTimeMillis()
        if (now - lastCheck < CHECK_INTERVAL_MS) return
        lastCheck = now

        updInfo.text = "Güncelleme kontrol ediliyor…"
        Thread {
            val result = runCatching { Updater.latest() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val rel = result.getOrNull()
                if (rel == null) {
                    updInfo.text = "Güncelleme kontrolü başarısız: ${result.exceptionOrNull()?.message}"
                } else if (rel.isNewer) {
                    updInfo.text = "Yeni sürüm var: ${rel.tag} (şu an: v${BuildConfig.BUILD_NUMBER})"
                    if (rel.build != declinedBuild) askUpdate(rel)
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
        if (!packageManager.canRequestPackageInstalls()) {
            pendingRel = rel   // izin verilip geri dönünce güncelleme kendiliğinden devam eder
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            Toast.makeText(this, "Bu uygulamaya \"bilinmeyen uygulamaları yükle\" iznini ver, sonra uygulamaya geri dön; güncelleme otomatik devam eder", Toast.LENGTH_LONG).show()
            return
        }
        updating = true
        Thread {
            try {
                val apk = Updater.download(this, rel) { p ->
                    runOnUiThread { updInfo.text = "İndiriliyor… %$p" }
                }
                runOnUiThread { updInfo.text = "Yükleme ekranı açılıyor…" }
                Updater.install(this, apk)
            } catch (e: Exception) {
                runOnUiThread { updInfo.text = "Güncelleme hatası: ${e.message}" }
            } finally {
                updating = false
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        refreshInfo()

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
            "API anahtarı buluta kaydedilmez, yeni cihazda elle yapıştır."
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return

        when (req) {
            REQ_PROJECTION -> {
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
