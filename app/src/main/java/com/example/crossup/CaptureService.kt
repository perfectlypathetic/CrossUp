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
import com.example.crossup.GameLog
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
    @Volatile private var heldAt = 0L
    @Volatile private var frames = 0

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
        GameLog.init(applicationContext, forceNew = true)
        GameLog.section("SERVİS BAŞLADI")
        GameLog.i("Service", "onStartCommand: kod=$code veri=${data != null} anahtar=${if (key.isBlank()) "yok" else "var (${key.length} karakter)"} USE_AI=${GameController.USE_AI} DEBUG_TDK=${GameController.DEBUG_TDK}")
        // API anahtarı artık zorunlu değil (yapay zeka kapalı).
        if (data == null) {
            GameLog.e("Service", "ekran yakalama izni verisi yok → servis kapanıyor")
            stopSelf(); return START_NOT_STICKY
        }

        startForeground(1, notification())          // Android 14: önce foreground, sonra projection
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    GameLog.w("Service", "MediaProjection durduruldu (sistem/kullanıcı) → servis kapanıyor")
                    stopSelf()
                }
            }, Handler(Looper.getMainLooper()))
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        controller = GameController(key, applicationContext)
        GameLog.i("Service", "GameController hazır")
        setupCapture(); setupOverlay()
        GameLog.i("Service", "yakalama + overlay kuruldu | ${GameLog.mem()}")
        return START_NOT_STICKY
    }

    // ---------- yakalama (VirtualDisplay bir kez kurulur, Android 14 tek kullanımlık token) ----------
    private fun setupCapture() {
        val b = wm.currentWindowMetrics.bounds
        val w = b.width(); val h = b.height()
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                synchronized(lock) { held?.close(); held = img; heldAt = System.currentTimeMillis(); frames++ }
            }, Handler(thread.looper))
        }
        vd = projection!!.createVirtualDisplay("crossup", w, h, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
            GameLog.i("Service", "yakalama kuruldu: ${w}x${h} dpi=${resources.displayMetrics.densityDpi}")
    }

    private suspend fun capture(label: String = "ekran"): Bitmap {
        val t0 = System.currentTimeMillis()
        val framesBefore = frames
        setOverlayVisible(false)
        delay(700)                                   // overlay kaybolsun, yeni kare gelsin (panel yakalanmasın)
        val got = synchronized(lock) {
            val b = held?.toBitmap()
            val age = if (heldAt > 0L) System.currentTimeMillis() - heldAt else -1L
            Pair(b, age)
        }
        val bmp = got.first
        val age = got.second
        setOverlayVisible(true)
        GameLog.i("Capture", "ekran yakalandı ($label): ${if (bmp == null) "KARE YOK" else "${bmp.width}x${bmp.height}"} | son kare ${age}ms önce geldi | toplam kare=$frames (bekleme sırasında yeni=${frames - framesBefore}) | süre=${System.currentTimeMillis() - t0}ms | ${GameLog.mem()}")
        if (bmp == null) {
            GameLog.e("Capture", "ekran karesi alınamadı")
            error("Ekran karesi alınamadı")
        }
        if (age > 5000) GameLog.w("Capture", "UYARI: son kare ${age}ms önce geldi. Ekran değişmediği için eski kare olabilir.")
        GameLog.d("Capture", "ekran görüntüsü dosyası: " + GameLog.saveShot(bmp, label))
        return bmp
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
        setOnClickListener {
            GameLog.i("UI", "BUTON: '$t' | ${controller.scoreLabel()} | ${controller.turns.label}")
            onClick()
        }
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

        status = object : TextView(this@CaptureService) {
            override fun setText(text: CharSequence?, type: android.widget.TextView.BufferType?) {
                GameLog.d("Panel", "panel metni: " + text)
                super.setText(text, type)
            }
        }.apply {
            setTextColor(Color.WHITE); textSize = 12f
            text = "Hazır. 'Analiz'e bas."
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

        // Skor düzeltme satırları (rakibin yanlış harfleri gibi görünmeyen puanlar için).
        val rowMe = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Ben −5") { status.text = controller.adjustScore(true, -5) })
            addView(btn("Ben −1") { status.text = controller.adjustScore(true, -1) })
            addView(btn("Ben +1") { status.text = controller.adjustScore(true, 1) })
            addView(btn("Ben +5") { status.text = controller.adjustScore(true, 5) })
        }
        val rowOpp = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Rkp −5") { status.text = controller.adjustScore(false, -5) })
            addView(btn("Rkp −1") { status.text = controller.adjustScore(false, -1) })
            addView(btn("Rkp +1") { status.text = controller.adjustScore(false, 1) })
            addView(btn("Rkp +5") { status.text = controller.adjustScore(false, 5) })
            addView(btn("Yeni oyun") { status.text = controller.resetGame(); markers.clear() })
        }

        // Rakip son turunda harf DEĞİŞTİRDİYSE (tahtada görünmez) bas, sonra Analiz'e bas.
        val rowSwap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("Rkp harf değiştirdi (+1)") { status.text = controller.addOppSwap() })
        }

        val dm = resources.displayMetrics
        // Uzun açıklamalar için kaydırılabilir, sınırlı yükseklikte metin alanı.
        val maxTextH = (dm.heightPixels * 0.26f).toInt()
        val scroll = object : ScrollView(this@CaptureService) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(maxTextH, View.MeasureSpec.AT_MOST))
            }
        }
        scroll.addView(status)
        panelLp = WindowManager.LayoutParams(
            (dm.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels * 0.04f).toInt(); y = (dm.heightPixels * 0.55f).toInt()
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
            addView(handle); addView(scroll); addView(row); addView(row2); addView(rowMe); addView(rowOpp); addView(rowSwap)
        }
        wm.addView(panel, panelLp)
        GameLog.i("Overlay", "panel eklendi: x=${panelLp.x} y=${panelLp.y} genişlik=${panelLp.width}")
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
                    GameLog.i("AskLetter", "kullanıcı cevabı: ${ch ?: "ATLA"}")
                    if (!removed) { removed = true; runCatching { wm.removeView(box) } }
                    if (cont.isActive) cont.resume(ch)
                }

                box.orientation = LinearLayout.VERTICAL
                GameLog.i("AskLetter", "'Bu harf hangisi?' penceresi açıldı: tahmin=${guess ?: "yok"} kalan=$remaining")
                box.setBackgroundColor(0xEE000000.toInt())
                box.setPadding(16, 16, 16, 16)

                box.addView(TextView(this@CaptureService).apply {
                    setTextColor(Color.WHITE); textSize = 14f
                    text = "Bu harf hangisi? (kalan: $remaining)\nHarf değilse 'Atla'ya bas." +
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
        if (busy) { GameLog.w("Analyze", "ANALİZ isteği yok sayıldı: başka işlem sürüyor"); return }
        busy = true; status.text = "Ekran yakalanıyor…"
        scope.launch {
            val tStart = System.currentTimeMillis()
            GameLog.section("ANALİZ (Analiz tuşu)")
            try {
                val bmp = capture("analiz")
                val a = withTimeoutOrNull(300_000) {
                    withContext(Dispatchers.Default) {
                        controller.analyze(bmp) { msg ->
                            status.post { status.text = msg }
                        }
                    }
                } ?: error("Zaman aşımı. İnternet/API anahtarı/kota kontrol et.")
                show(a)
                GameLog.i("Analyze", "ANALİZ bitti, toplam süre=${System.currentTimeMillis() - tStart}ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("CrossUp", "analyze", e)
                GameLog.e("Analyze", "ANALİZ HATASI", e)
                status.text = "Hata: ${e.message ?: e.javaClass.simpleName}"
            } finally { busy = false; cloudPush() }
        }
    }

    /** Sadece harf tanıma: raf + tahtadaki harfleri okur, bilinmeyenleri sorar. AI yok, plan yok. */
    private fun recognize() {
        if (busy) { GameLog.w("Recognize", "HARFLERİ TANI isteği yok sayıldı: başka işlem sürüyor"); return }
        busy = true; status.text = "Ekran yakalanıyor…"
        scope.launch {
            GameLog.section("HARFLERİ TANI (tuş)")
            try {
                val bmp = capture("harf")
                val r = withTimeoutOrNull(300_000) {
                    withContext(Dispatchers.Default) {
                        controller.recognizeLetters(bmp, { c, g, n -> askLetter(c, g, n) }) { msg ->
                            status.post { status.text = msg }
                        }
                    }
                } ?: error("Zaman aşımı.")
                markers.clear()
                status.text = "Raf: ${r.rack.joinToString("")}\n" +
                    (if (r.rackMissing > 0) "Raftaki ${r.rackMissing} harf tanınmadı (tekrar bas)."
                    else "Raf tamam. Analiz'e basabilirsin.") +
                    (if (r.info.isNotEmpty()) "\n[${r.info}]" else "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("CrossUp", "recognize", e)
                GameLog.e("Recognize", "HARFLERİ TANI HATASI", e)
                status.text = "Hata: ${e.message ?: e.javaClass.simpleName}"
            } finally { busy = false; cloudPush() }
        }
    }

    /** Sadece öğrenme: ekrandaki dolu yuvaları veritabanına kaydeder. */
    private fun learn() {
        if (busy) { GameLog.w("Learn", "ÖĞREN isteği yok sayıldı: başka işlem sürüyor"); return }
        busy = true; status.text = "Ekran yakalanıyor…"
        scope.launch {
            GameLog.section("ÖĞREN (tuş)")
            try {
                val bmp = capture("ogren")
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
                GameLog.e("Learn", "ÖĞREN HATASI", e)
                status.text = "Hata: ${e.message ?: e.javaClass.simpleName}"
            } finally { busy = false; cloudPush() }
        }
    }

    /** Sonucu göster: ekranda koyulacak harfler işaretlenir, panelde plan ve nedenler yazar. */
    private fun show(a: Analysis) {
        markers.set(a)
        val p = a.plan
        val moves = when {
            p.swap.isNotEmpty() -> "HARF DEĞİŞTİR: " + p.swap.joinToString(" ")
            p.pass -> "PAS"
            else -> p.placements.joinToString("   ") { "${it.letter} → (${it.cell.r + 1},${it.cell.c + 1})" }
        }
        val sb = StringBuilder()
        sb.append("Raf: ").append(a.rack.joinToString("")).append("\n").append(moves)
        if (p.lines.isNotEmpty()) sb.append("\n").append(p.lines.joinToString("\n"))
        if (a.note.isNotEmpty()) sb.append("\n").append(a.note)
        status.text = sb.toString()
        GameLog.i("Show", "KULLANICIYA GÖSTERİLEN:\n" + sb.toString())
        for (pl in p.placements) {
            val r = a.board.cellRects[pl.cell.r][pl.cell.c]
            GameLog.d("Show", "işaret: ${pl.letter} → hücre(${pl.cell.r + 1},${pl.cell.c + 1}) piksel=[${r.x0},${r.y0}-${r.x1},${r.y1}] olasılık=${GameLog.f(pl.p)}")
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("cap", "CrossUp", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "cap").setContentTitle("CrossUp asistanı çalışıyor")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
    }

    override fun onDestroy() {
        GameLog.section("SERVİS KAPANIYOR")
        scope.cancel()
        runCatching { wm.removeView(panel); wm.removeView(markers) }
        synchronized(lock) { held?.close(); held = null }
        vd?.release(); reader?.close(); projection?.stop(); thread.quitSafely()
        GameLog.i("Service", "servis kapandı")
        GameLog.flush()
        super.onDestroy()
    }
}

/**
 * Hücrelerin üstüne çizer (ekran görüntüsü koordinatı = ekran koordinatı).
 * Planın önerdiği hamleler: kırmızı daire içinde koyulacak harf. Bilgisi olmayan hücreler: sarı nokta.
 */
class MarkerView(ctx: Context) : View(ctx) {
    private var a: Analysis? = null
    private val bg = Paint().apply { color = 0xCCE53935.toInt(); style = Paint.Style.FILL }
    private val tx = Paint().apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true; isAntiAlias = true }
    // Kelimesi bilinmeyen (bilgi yok) hücreler: küçük sarı nokta.
    private val unk = Paint().apply { color = 0xCCFFC107.toInt(); style = Paint.Style.FILL; isAntiAlias = true }

    fun set(x: Analysis) { a = x; invalidate() }
    fun clear() { a = null; invalidate() }

    override fun onDraw(c: Canvas) {
        val an = a ?: return
        for (uc in an.plan.unknownCells) {
            val r = an.board.cellRects[uc.r][uc.c]
            val cx = (r.x0 + r.x1) / 2f; val cy = (r.y0 + r.y1) / 2f
            c.drawCircle(cx, cy, (r.x1 - r.x0) * 0.09f, unk)
        }
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
