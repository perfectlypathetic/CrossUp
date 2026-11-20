package com.example.crossup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import com.example.crossup.service.CaptureService

class MainActivity : Activity() {
    private lateinit var keyBox: EditText

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
                startActivityForResult(mpm.createScreenCaptureIntent(), 100)
            }
        } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40)
            addView(keyBox); addView(go)
        })
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 100 && res == RESULT_OK && data != null) {
            startForegroundService(Intent(this, CaptureService::class.java)
                .putExtra("code", res).putExtra("data", data).putExtra("key", keyBox.text.toString().trim()))
            moveTaskToBack(true)   // Bundle'a geç
        }
    }
}
