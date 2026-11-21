package com.example.crossup

import android.app.Activity
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
import com.example.crossup.service.CaptureService

class MainActivity : Activity() {
    private lateinit var keyBox: EditText
    private lateinit var info: TextView

    private companion object {
        const val REQ_PROJECTION = 100
        const val REQ_EXPORT = 101
        const val REQ_IMPORT = 102
        const val REQ_CLOUD_NEW = 103
        const val REQ_CLOUD_PICK = 104
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
        val cloudOff = Button(this).apply {
            text = "Bulut bağlantısını kes"
            setOnClickListener { CloudSync.disconnect(this@MainActivity); refreshInfo() }
        }

        val export = Button(this).apply {
            text = "Verileri dışa aktar (elle yedek)"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "crossup_yedek.json")
                    }, REQ_EXPORT
                )
            }
        }
        val import = Button(this).apply {
            text = "Verileri içe aktar (elle yedekten)"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }, REQ_IMPORT
                )
            }
        }

        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40)
                addView(keyBox); addView(go); addView(info)
                addView(cloudNew); addView(cloudPick); addView(cloudOff)
                addView(export); addView(import)
            })
        })
    }

    override fun onResume() {
        super.onResume()
        refreshInfo()
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
        info.text = "Kayıtlı kelime: ${ClueDb.get(this).size()}\n" +
            "Kayıtlı harf şablonu: ${LetterDb.get(this).size()}\n" +
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
            REQ_EXPORT -> {
                val uri = data.data ?: return
                Thread {
                    val msg = try {
                        contentResolver.openOutputStream(uri, "wt")!!.use { CloudSync.exportTo(this@MainActivity, it) }
                        "Yedek kaydedildi."
                    } catch (e: Exception) { "Hata: ${e.message}" }
                    runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() }
                }.start()
            }
            REQ_IMPORT -> {
                val uri = data.data ?: return
                Thread {
                    val msg = try {
                        val added = contentResolver.openInputStream(uri)!!.use { CloudSync.importFrom(this@MainActivity, it) }
                        "$added yeni kayıt eklendi."
                    } catch (e: Exception) { "Okunamadı: ${e.message}" }
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                        refreshInfo()
                    }
                }.start()
            }
        }
    }
}
