package com.example.crossup.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.view.*
import android.widget.*
import com.example.crossup.ai.CloudSync
import com.example.crossup.ai.TR_ALPHABET
import com.example.crossup.game.Analysis
import com.example.crossup.game.GameController
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/** Foreground servis: ekranı yakalar, overlay panel + hücre işaretleri gösterir. */
class CaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private val thread = HandlerThread("cap").apply { start() }
    private val lock = Any()
    private var held: Image? = null

    private lateinit var controller: GameController
    private lateinit var panel: LinearLayout
    private lateinit var panelLp: WindowManager.LayoutParams
    private lateinit var status: TextView
    private lateinit var markers: MarkerView
    private var busy = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra("data")
        val key = intent?.getStringExtra("key") ?: ""
        if (data == null || key.isBlank()) { stopSelf(); return START_NOT_STICKY }

        startForeground(1, notification())          // Android 14: önce foreground, sonra projection
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(Looper.getMainLooper()))
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        controller = GameController(key, applicationContext)
        setupCapture(); setupOverlay()
        return START_NOT_STICKY
    }

    // ---------- yakalama (VirtualDisplay bir kez kurulur, Android 14 tek kullanımlık token) ----------
    private fun setupCapture() {
        val b = wm.currentWindowMetrics.bounds
        val w = b.width(); val h = b.height()
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                synchronized(lock) { held?.close(); held = img }
            }, Handler(thread.looper))
        }
        vd = projection!!.createVirtualDisplay("crossup", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
    }

    private suspend fun capture(): Bitmap {
        setOverlayVisible(false)
        delay(350)                                   // overlay kaybolsun, yeni kare gelsin
        val bmp = synchronized(lock) { held?.toBitmap() }
        setOverlayVisible(true)
        return bmp ?: error("Ekran karesi alınamadı")
    }

    private fun Image.toBitmap(): Bitmap {
        val p = planes[0]
        val rowPad = p.rowStride - p.pixelStride * width
        val full = Bitmap.createBitmap(width + rowPad / p.pixelStride, height, Bitmap.Config.ARGB_8888)
        p.buffer.rewind(); full.copyPixelsFromBuffer(p.buffer)
        return Bitmap.createBitmap(full, 0, 0, width, height)
    }

    /** Bulut bağlıysa verileri arka planda eşitler (sessiz). */
    private fun cloudPush() {
        if (CloudSync.uri(applicationContext) == null) return
        val app = applicationContext
        Thread { CloudSync.sync(app) }.start()
    }

    // ---------- overlay ----------
    private fun setOverlayVisible(v: Boolean) {
        val vis = if (v) View.VISIBLE else View.INVISIBLE
        panel.visibility = vis; markers.visibility = vis
    }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; textSize = 10f; isAllCaps = false; minWidth = 0; minimumWidth = 0
        setPadding(8, 0, 8, 0)
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    private fun setupOverlay() {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        markers = MarkerView(this)
        wm.addView(markers, WindowManager.LayoutParams(-1, -1, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START
                // Çentikli ekranlarda işaretlerin kaymaması için tüm ekranı kapla.
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            })

        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f
            text = "Hazır. Önce 'Harfleri tanı', sonra 'Analiz'."
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Analiz") { analyze() })
            addView(btn("Son4: ben") { controller.turns.start(true); status.text = controller.turns.label })
            addView(btn("Son4: rakip") { controller.turns.start(false); status.text = controller.turns.label })
            addView(btn("Sıradaki ▶") { nextTurn() })
            addView(btn("✕") { stopSelf() })
        }
        // İkinci satır: harf tanıma ve veritabanı. Analiz/plan yapmaz, AI kullanmaz.
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Harfleri tanı") { recognize() })
            addView(btn("Öğren (kaydet)") { learn() })
        }

        val dm = resources.displayMetrics
        panelLp = WindowManager.LayoutParams(
            (dm.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels * 0.04f).toInt(); y = (dm.heightPixels * 0.65f).toInt()
        }

        // Sürükleme tutamacı: buradan tutup paneli istediğin yere taşı.
        val handle = TextView(this).apply {
            text = "⠿ sürükle ⠿"; setTextColor(0xFFBBBBBB.toInt()); textSize = 12f
            gravity = Gravity.CENTER; setPadding(0, 10, 0, 14)
            var sx = 0; var sy = 0; var ox = 0f; var oy = 0f
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = panelLp.x; sy = panelLp.y; ox = e.rawX; oy = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        panelLp.x = (sx + (e.rawX - ox).toInt()).coerceIn(0, dm.widthPixels - panelLp.width)
                        panelLp.y = (sy + (e.rawY - oy).toInt()).coerceIn(0, dm.heightPixels - 100)
                        wm.updateViewLayout(panel, panelLp)
                    }
                }
                true
            }
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xCC000000.toInt()); setPadding(16, 0, 16, 8)
            addView(handle); addView(status); addView(row); addView(row2)
        }
        wm.addView(panel, panelLp)
    }

    /** "Sıradaki ▶": son 4 turda sırayı bir adım ilerletir. */
    private fun nextTurn() {
        if (!controller.turns.endgame) {
            status.text = "Son 4 tur henüz başlamadı. Boş hücre 12'nin altına inince 'Son4: ben' veya " +
                "'Son4: rakip' tuşuna bas. Bu tuş her hamleden sonra sırayı ilerletir."
            return
        }
        if (!controller.turns.advance()) {
            status.text = "Oyun bitti (4 tur tamamlandı)."
            return
        }
        status.text = controller.turns.label
    }

    // ---------- "Bu harf hangisi?" penceresi ----------
    private suspend fun askLetter(crop: Bitmap, guess: Char?, remaining: Int): Char? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Char?> { cont ->
                val dm = resources.displayMetrics
                val box = LinearLayout(this@CaptureService)
                var removed = false
                fun finish(ch: Char?) {
                    if (!removed) { removed = true; runCatching { wm.removeView(box) } }
                    if (cont.isActive) cont.resume(ch)
                }

                box.orientation = LinearLayout.VERTICAL
                box.setBackgroundColor(0xEE000000.toInt())
                box.setPadding(16, 16, 16, 16)

                box.addView(TextView(this@CaptureService).apply {
                    setTextColor(Color.WHITE); textSize = 14f
                    text = "Bu harf hangisi? (kalan: $remaining)" +
                        (if (guess != null) "\nTahmin: $guess (yeşil)" else "")
                })
                box.addView(ImageView(this@CaptureService).apply {
                    setImageBitmap(crop)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(0xFFDDDDDD.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (dm.heightPixels * 0.12f).toInt()
                    ).apply { topMargin = 8; bottomMargin = 8 }
                })
                for (chunk in TR_ALPHABET.chunked(6)) {
                    val r = LinearLayout(this@CaptureService).apply { orientation = LinearLayout.HORIZONTAL }
                    for (c in chunk) {
                        r.addView(btn(c.toString()) { finish(c) }.also {
                            it.textSize = 16f
                            if (c == guess) it.setBackgroundColor(0xFF2E7D32.toInt())
                        })
                    }
                    box.addView(r)
                }
                val skipRow = LinearLayout(this@CaptureService).apply { orientation = LinearLayout.HORIZONTAL }
                skipRow.addView(btn("Atla (bilmiyorum)") { finish(null) })
                box.addView(skipRow)

                val lp = WindowManager.LayoutParams(
                    (dm.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = (dm.heightPixels * 0.06f).toInt()
                }
                wm.addView(box, lp)
                cont.invokeOnCancellation { box.post { finish(null) } }
            }
        }

    private fun analyze() {
        if (busy) return
        busy = true; status.text = "Ekran yakalanıyor…"
        scope.launch {
            try {
                val bmp = capture()
                val a = withTimeoutOrNull(300_000) {
                    withContext(Dispatchers.Default) {
                        controller.analyze(bmp) { msg ->
                            status.post { status.text = msg }
                        }
                    }
                } ?: error("Zaman aşımı. İnternet/API anahtarı/kota kontrol et.")
                show(a)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("CrossUp", "analyze", e)
                status.text = "Hata: ${e.message ?: e.javaClass.simpleName}"
            } finally { busy = false; cloudPush() }
        }
    }

    /** Sadece harf tanıma: raf + tahtadaki harfleri okur, bilinmeyenleri sorar. AI yok, plan yok. */
    private fun recognize() {
        if (busy) return
        busy = true; status.text = "Ekran yakalanıyor…"
        scope.launch {
            try {
                val bmp = capture()
                val r = withTimeoutOrNull(300_000) {
                    withContext(Dispatchers.Default) {
                        controller.recognizeLetters(bmp, { c, g, n -> askLetter(c, g, n) }) { msg ->
                            status.post { status.text = msg }
                        }
                    }
                } ?: error("Zaman aşımı.")
                markers.clear()
                status.text = "Harf tanıma bitti.\n" +
                    "Raf: ${r.rack.joinToString("")}\n" +
                    "Tahtadaki harf: ${r.boardKnown}/${r.boardFilled}\n" +
                    (if (r.unresolved > 0) "Tanınmayan: ${r.unresolved} (tekrar bas)"
                    else "Hepsi tanındı. Analiz'e basabilirsin.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("CrossUp", "recognize", e)
                status.text = "Hata: ${e.message ?: e.javaClass.simpleName}"
            } finally { busy = false; cloudPush() }
        }
    }

    /** Sadece öğrenme: ekrandaki dolu yuvaları veritabanına kaydeder. */
    private fun learn() {
        if (busy) return
        busy = true; status.text = "Ekran yakalanıyor…"
        scope.launch {
            try {
                val bmp = capture()
                val r = withTimeoutOrNull(300_000) {
                    withContext(Dispatchers.Default) {
                        controller.learn(bmp, { c, g, n -> askLetter(c, g, n) }) { msg ->
                            status.post { status.text = msg }
                        }
                    }
                } ?: error("Zaman aşımı.")
                markers.clear()
                status.text = "Öğrenme bitti.\n" +
                    "Dolu yuva: ${r.fullSlots} → yeni kelime: ${r.added}\n" +
                    (if (r.unreadCells > 0) "Okunamayan hücre: ${r.unreadCells} (tekrar bas)\n" else "") +
                    "Kayıtlı toplam: ${r.total}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("CrossUp", "learn", e)
                status.text = "Hata: ${e.message ?: e.javaClass.simpleName}"
            } finally { busy = false; cloudPush() }
        }
    }

    private fun show(a: Analysis) {
        markers.set(a)
        val moves = if (a.plan.pass) "PAS" else a.plan.placements.joinToString("  ") {
            "${it.letter}→(${it.cell.r + 1},${it.cell.c + 1}) %${(it.p * 100).toInt()}"
        }
        status.text = "Raf: ${a.rack.joinToString("")}\n$moves  EV=${"%.1f".format(a.plan.expectedValue)}\n" +
            "Kırmızı=plan, turuncu=rafta var, mavi=diğer tahmin\n${a.note}"
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cap", "CrossUp", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "cap").setContentTitle("CrossUp asistanı çalışıyor")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { wm.removeView(panel); wm.removeView(markers) }
        synchronized(lock) { held?.close(); held = null }
        vd?.release(); reader?.close(); projection?.stop(); thread.quitSafely()
        super.onDestroy()
    }
}

/**
 * Hücrelerin üstüne çizer (ekran görüntüsü koordinatı = ekran koordinatı).
 *  - Kırmızı büyük daire: planın önerdiği hamle.
 *  - Turuncu: en olası harf, rafında var.
 *  - Mavi: en olası harf, rafında yok (sonraki turlar için bilgi).
 */
class MarkerView(ctx: Context) : View(ctx) {
    private var a: Analysis? = null
    private val bg = Paint().apply { color = 0xCCE53935.toInt(); style = Paint.Style.FILL }
    private val bgRack = Paint().apply { color = 0xCCFB8C00.toInt(); style = Paint.Style.FILL }
    private val bgGuess = Paint().apply { color = 0xAA1E88E5.toInt(); style = Paint.Style.FILL }
    private val tx = Paint().apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true; isAntiAlias = true }
    private val small = Paint().apply { color = Color.BLACK; textAlign = Paint.Align.CENTER; isFakeBoldText = true; isAntiAlias = true }

    fun set(x: Analysis) { a = x; invalidate() }
    fun clear() { a = null; invalidate() }

    override fun onDraw(c: Canvas) {
        val an = a ?: return
        val planned = an.plan.placements.map { it.cell }.toSet()
        val rackSet = an.rack.toSet()

        // Tüm bulmaca tahmini (önce çizilir, planın altında kalır).
        for ((cell, g) in an.guesses) {
            if (cell in planned) continue
            val r = an.board.cellRects[cell.r][cell.c]
            val cx = (r.x0 + r.x1) / 2f; val cy = (r.y0 + r.y1) / 2f
            val rad = (r.x1 - r.x0) * 0.30f
            c.drawCircle(cx, cy, rad, if (g.letter in rackSet) bgRack else bgGuess)
            tx.textSize = rad * 1.3f
            c.drawText(g.letter.toString(), cx, cy + tx.textSize * 0.35f, tx)
            if (g.p < 0.95) {
                small.textSize = rad * 0.65f
                c.drawText("%${(g.p * 100).toInt()}", cx, r.y1 - rad * 0.12f, small)
            }
        }

        // Planın önerdiği hamleler.
        for (pl in an.plan.placements) {
            val r = an.board.cellRects[pl.cell.r][pl.cell.c]
            val cx = (r.x0 + r.x1) / 2f; val cy = (r.y0 + r.y1) / 2f
            val rad = (r.x1 - r.x0) * 0.38f
            c.drawCircle(cx, cy, rad, bg)
            tx.textSize = rad * 1.2f
            c.drawText(pl.letter.toString(), cx, cy + tx.textSize * 0.35f, tx)
        }
    }
}
