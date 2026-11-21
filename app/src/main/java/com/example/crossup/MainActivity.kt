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
import com.example.crossup.service.CaptureService

class MainActivity : Activity() {
    private lateinit var keyBox: EditText
    private lateinit var info: TextView

    private companion object {
        const val REQ_PROJECTION = 100
        const val REQ_EXPORT = 101
        const val REQ_IMPORT = 102
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

        val export = Button(this).apply {
            text = "Kelimeleri dışa aktar (yedekle)"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, "crossup_kelimeler.json")
                    }, REQ_EXPORT
                )
            }
        }
        val import = Button(this).apply {
            text = "Kelimeleri içe aktar (yedekten)"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }, REQ_IMPORT
                )
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40)
            addView(keyBox); addView(go); addView(info); addView(export); addView(import)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshInfo()
    }

    private fun refreshInfo() {
        info.text = "Kayıtlı kelime: ${ClueDb.get(this).size()}\n" +
            "Yeni sürüm kurmadan / uygulamayı silmeden önce \"dışa aktar\" ile yedek al."
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
            REQ_EXPORT -> {
                val uri = data.data ?: return
                Thread {
                    val msg = try {
                        contentResolver.openOutputStream(uri, "wt")!!.use { ClueDb.get(this@MainActivity).exportTo(it) }
                        "Yedek kaydedildi."
                    } catch (e: Exception) { "Hata: ${e.message}" }
                    runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show() }
                }.start()
            }
            REQ_IMPORT -> {
                val uri = data.data ?: return
                Thread {
                    val msg = try {
                        val added = contentResolver.openInputStream(uri)!!.use { ClueDb.get(this@MainActivity).importFrom(it) }
                        "$added yeni kelime eklendi."
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
