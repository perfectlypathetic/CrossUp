package com.example.crossup

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.example.crossup.ai.ClueDb
import com.example.crossup.ai.CloudSync
import com.example.crossup.ai.PendingItem
import com.example.crossup.ai.PendingSession
import com.example.crossup.ai.PendingStore
import com.example.crossup.ai.TR_ALPHABET
import com.example.crossup.game.TR
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Onay ekranı: bulmaca bitince (veya yarım kalınca) yeni bulunan kelimeleri gösterir.
 *  - Tahtadan okunan kelimeler: işaretli olanlar kaydedilir, yanlışsa düzeltebilirsin.
 *  - Çözülmemiş yuvalar: cevabını bildiklerini yaz, boş bıraktıkların kaydedilmez.
 *  - "Bulmaca görüntüsü": o anki tam ekran görüntüsünü açar (telefon kapansa bile saklanır).
 *  - İş bitince ekran görüntüsü ve ipucu resimleri silinir.
 */
class ReviewActivity : Activity() {
    private lateinit var store: PendingStore
    private lateinit var db: ClueDb
    private lateinit var list: LinearLayout
    private lateinit var headTv: TextView
    private lateinit var sumTv: TextView
    private lateinit var shotBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var finishBtn: Button

    private var cur: PendingSession? = null
    private var wantId: String? = null
    private val rows = ArrayList<Row>()
    private val errors = HashMap<String, String>()

    private class Row(val item: PendingItem, val edit: EditText, val check: CheckBox?)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        GameLog.init(this)
        store = PendingStore.get(this)
        db = ClueDb.get(this)
        wantId = intent?.getStringExtra("sid")
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        headTv = TextView(this).apply { textSize = 18f; setTextColor(Color.BLACK); setPadding(0, 0, 0, dp(4)) }
        sumTv = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, dp(8)) }
        shotBtn = Button(this).apply {
            text = "🖼 Bulmacanın tam görüntüsünü aç"
            isAllCaps = false
            setOnClickListener { showShot() }
        }

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(list)
        }

        fun bar(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; textSize = 12f; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
        saveBtn = bar("✔ Kaydet") { saveClicked() }
        finishBtn = bar("Bitir (kalanları at)") { finishClicked() }
        val closeBtn = bar("Şimdilik kapat") { closeClicked() }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFE0E0E0.toInt())
            setPadding(dp(6), dp(4), dp(6), dp(4))
            addView(saveBtn); addView(finishBtn); addView(closeBtn)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll); addView(bottom)
        })
        load()
    }

    override fun onPause() {
        persistTyped()
        super.onPause()
    }

    // ---------- listeyi kur ----------

    private fun load() {
        val all = store.snapshot()
        val s = all.firstOrNull { it.id == wantId } ?: all.maxByOrNull { it.updatedAt }
        cur = s
        rows.clear()
        list.removeAllViews()
        if (s == null) {
            headTv.text = "Onay bekleyen bir şey yok ✔"
            sumTv.text = "Tüm kelimeler işlendi."
            list.addView(headTv); list.addView(sumTv)
            saveBtn.visibility = View.GONE
            finishBtn.visibility = View.GONE
            return
        }
        saveBtn.visibility = View.VISIBLE
        finishBtn.visibility = View.VISIBLE
        val idx = all.indexOfFirst { it.id == s.id } + 1
        val whenTxt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(s.updatedAt))
        val autos = s.items.filter { it.word.isNotEmpty() }
        val unknowns = s.items.filter { it.word.isEmpty() }
        headTv.text = "Bulmaca $whenTxt" + (if (all.size > 1) "  ($idx/${all.size})" else "")
        sumTv.text = "${autos.size} kelime onay bekliyor · ${unknowns.size} çözülmemiş yuva\n" +
            "Bu kayıt telefonda saklanır; servisi kapatsan da kaybolmaz."
        list.addView(headTv); list.addView(sumTv)
        if (s.shotFile.isNotEmpty() && store.shotFile(s).exists()) {
            shotBtn.visibility = View.VISIBLE
            list.addView(shotBtn)
        }
        if (autos.isNotEmpty()) {
            list.addView(section("ONAY BEKLEYEN KELİMELER",
                "İşaretli olanlar kaydedilir. Yanlış okunmuşsa kutudaki kelimeyi düzelt veya işareti kaldır."))
            for (it in autos) addRow(it, true)
        }
        if (unknowns.isNotEmpty()) {
            list.addView(section("ÇÖZÜLMEMİŞ YUVALAR",
                "Cevabını bildiklerini yaz. Boş bıraktıkların kaydedilmez. Hatırlamıyorsan üstteki bulmaca görüntüsüne bakıp çözebilirsin."))
            for (it in unknowns) addRow(it, false)
        }
    }

    private fun section(title: String, sub: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(16), 0, dp(4))
        addView(TextView(this@ReviewActivity).apply {
            text = title; textSize = 13f; setTextColor(0xFF1565C0.toInt()); setTypeface(null, android.graphics.Typeface.BOLD)
        })
        addView(TextView(this@ReviewActivity).apply { text = sub; textSize = 12f })
    }

    private fun addRow(item: PendingItem, auto: Boolean) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(0xFFF2F2F2.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, dp(6)) }
        }
        val bmp = store.clueBitmap(item)
        if (bmp != null) {
            box.addView(ImageView(this).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_START
                setBackgroundColor(0xFFDDDDDD.toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(90))
            })
        }
        val shownPattern = item.pattern.toCharArray().joinToString(" ")
        box.addView(TextView(this).apply {
            textSize = 13f
            text = "${item.arrow} satır ${item.row}, sütun ${item.col} · ${item.length} harf" +
                (if (item.pattern.any { it != '_' }) "\nTahtada: $shownPattern" else "")
            setPadding(0, dp(4), 0, dp(2))
        })
        var check: CheckBox? = null
        if (auto) {
            check = CheckBox(this).apply { text = "Kaydet"; isChecked = item.checked }
            box.addView(check)
        }
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf<InputFilter>(InputFilter.LengthFilter(item.length))
            hint = if (auto) "Kelime" else "Cevap (${item.length} harf)"
            textSize = 18f
            setText(if (item.typed.isNotEmpty()) item.typed else item.word)
        }
        box.addView(et)
        val err = TextView(this).apply {
            textSize = 12f; setTextColor(0xFFC62828.toInt())
            val m = errors[item.id]
            if (m != null) text = m else visibility = View.GONE
        }
        box.addView(err)
        list.addView(box)
        rows.add(Row(item, et, check))
    }

    // ---------- kaydetme ----------

    private fun norm(t: String) = t.trim().uppercase(TR)

    private fun validate(w: String, item: PendingItem, auto: Boolean): String? {
        if (w.length != item.length) return "Bu yuva ${item.length} harfli, sen ${w.length} harf yazdın."
        for ((i, ch) in w.withIndex()) {
            if (ch !in TR_ALPHABET) return "'$ch' geçerli bir harf değil."
            if (!auto) {
                val p = item.pattern.getOrNull(i) ?: '_'
                if (p != '_' && p != ch)
                    return "${i + 1}. harf tahtada '$p' görünüyor, sen '$ch' yazdın. (Tahtadaki yanlış okunmuş olabilir; emin değilsen boş bırak.)"
            }
        }
        return null
    }

    /** Geçerli olanları veritabanına yazar. [kaydedilen, hatalı] döner. Listeyi yenilemez. */
    private fun applyEntries(): IntArray {
        val s = cur ?: return intArrayOf(0, 0)
        errors.clear()
        var saved = 0
        var bad = 0
        for (r in rows) {
            val auto = r.item.word.isNotEmpty()
            if (auto && r.check?.isChecked == false) continue
            val w = norm(r.edit.text.toString())
            if (w.isEmpty()) continue
            val e = validate(w, r.item, auto)
            if (e != null) { errors[r.item.id] = e; bad++; continue }
            db.put(r.item.key, w)
            GameLog.i("Review", "ONAYLANDI: \"$w\" (${r.item.arrow} ${r.item.row},${r.item.col} ${if (auto) "okunan" else "elle girilen"})")
            store.removeItem(s.id, r.item.id, false)
            saved++
        }
        if (saved > 0 && CloudSync.uri(applicationContext) != null) {
            val app = applicationContext
            Thread { CloudSync.sync(app) }.start()
        }
        return intArrayOf(saved, bad)
    }

    private fun persistTyped() {
        val s = cur ?: return
        if (rows.isEmpty()) return
        store.updateDrafts(s.id, rows.map { Triple(it.item.id, it.edit.text.toString(), it.check?.isChecked ?: true) })
    }

    private fun saveClicked() {
        val r = applyEntries()
        val msg = StringBuilder()
        msg.append("${r[0]} kelime kaydedildi.")
        if (r[1] > 0) msg.append(" ${r[1]} satırda hata var (kırmızı yazıya bak).")
        Toast.makeText(this, msg.toString(), Toast.LENGTH_LONG).show()
        persistTyped()
        load()
    }

    private fun finishClicked() {
        val s = cur ?: return
        val r = applyEntries()
        if (r[1] > 0) {
            Toast.makeText(this, "${r[1]} satırda hata var. Düzelt (veya sil) sonra tekrar Bitir'e bas.", Toast.LENGTH_LONG).show()
            persistTyped()
            load()
            return
        }
        val remaining = store.snapshot().firstOrNull { it.id == s.id }?.items?.size ?: 0
        if (remaining == 0) {
            Toast.makeText(this, "${r[0]} kelime kaydedildi. Bulmaca tamamlandı, görüntü silindi.", Toast.LENGTH_LONG).show()
            wantId = null
            load()
            return
        }
        persistTyped()
        AlertDialog.Builder(this)
            .setTitle("Kalanlar atılsın mı?")
            .setMessage("${r[0]} kelime kaydedildi.\n\nKalan $remaining yuva (işareti kaldırdıkların ve boş bıraktıkların) kaydedilmeyecek; " +
                "bulmaca görüntüsü de silinecek.")
            .setPositiveButton("Evet, at ve sil") { _, _ ->
                GameLog.i("Review", "bulmaca bitirildi: kalan $remaining yuva atıldı")
                store.finishSession(s.id)
                wantId = null
                load()
            }
            .setNegativeButton("Hayır, devam", null)
            .show()
    }

    private fun closeClicked() {
        persistTyped()
        finish()
    }

    // ---------- tam bulmaca görüntüsü ----------

    private fun showShot() {
        val s = cur ?: return
        val f = store.shotFile(s)
        if (!f.exists()) {
            Toast.makeText(this, "Ekran görüntüsü bulunamadı.", Toast.LENGTH_SHORT).show()
            return
        }
        val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
        if (bmp == null) {
            Toast.makeText(this, "Ekran görüntüsü açılamadı.", Toast.LENGTH_SHORT).show()
            return
        }
        val dm = resources.displayMetrics
        val zooms = floatArrayOf(1f, 1.7f, 2.6f, 4f)
        var zi = 0
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.WHITE)
        }
        fun applyZoom() {
            val w = (dm.widthPixels * zooms[zi]).toInt()
            val h = (w.toLong() * bmp.height / bmp.width).toInt()
            iv.layoutParams = FrameLayout.LayoutParams(w, h)
        }
        applyZoom()

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val zoomBtn = Button(this).apply {
            isAllCaps = false
            text = "🔍 Yakınlaştır (1×)"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                zi = (zi + 1) % zooms.size
                applyZoom()
                text = "🔍 Yakınlaştır (${zooms[zi]}×)"
            }
        }
        val closeBtn = Button(this).apply {
            isAllCaps = false
            text = "✕ Kapat"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { dialog.dismiss() }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(zoomBtn); addView(closeBtn)
        }
        val h = HorizontalScrollView(this).apply { addView(iv) }
        val v = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(h)
        }
        dialog.setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(top); addView(v)
        })
        dialog.setOnDismissListener { iv.setImageDrawable(null); runCatching { bmp.recycle() } }
        dialog.show()
    }
}
