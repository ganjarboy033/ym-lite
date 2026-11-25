package dev.bagja.spatialaudio.audio

import android.annotation.SuppressLint
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.bagja.spatialaudio.data.BUILTIN_PRESETS
import dev.bagja.spatialaudio.data.EQ_PRESETS
import dev.bagja.spatialaudio.data.MixerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Floating Control Bubble & Floating Synced Lyrics Overlay (SYSTEM_ALERT_WINDOW).
 * Memudahkan kontrol 8D, ganti preset EQ, Play/Pause, dan Tangkap Link Clipboard
 * saat pengguna sedang membuka TikTok, YouTube, atau YMusic.
 * Karya DEV BAGJA / KruzenLab (bagja.dev).
 */
class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private var bubbleView: View? = null
    private var lyricsView: TextView? = null
    private var expanded = false
    private var eqIndex = 0
    private var presetIndex = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        MixerRepository.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MixerRepository.floatingBubbleEnabled.value = false
            MixerRepository.floatingLyricsEnabled.value = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            MixerRepository.floatingBubbleEnabled.value = false
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_SYNC_LYRICS) {
            if (MixerRepository.floatingLyricsEnabled.value) showLyricsOverlay() else removeLyricsOverlay()
            if (!MixerRepository.floatingBubbleEnabled.value && !MixerRepository.floatingLyricsEnabled.value) stopSelf()
            return START_STICKY
        }

        if (MixerRepository.floatingBubbleEnabled.value && bubbleView == null) {
            showBubble()
        } else if (!MixerRepository.floatingBubbleEnabled.value) {
            removeBubble()
        }

        if (MixerRepository.floatingLyricsEnabled.value && lyricsView == null) {
            showLyricsOverlay()
        } else if (!MixerRepository.floatingLyricsEnabled.value) {
            removeLyricsOverlay()
        }

        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 300
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 14)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E616161B"))
                cornerRadius = 42f
                setStroke(2, Color.parseColor("#1ED760"))
            }
        }

        val toggleHeader = TextView(this).apply {
            text = "🎧 8D"
            setTextColor(Color.parseColor("#1ED760"))
            textSize = 14f
            setPadding(8, 4, 8, 4)
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 8, 0, 0)
        }

        fun makeBtn(label: String, bgHex: String = "#25252D", onClick: () -> Unit): Button =
            Button(this).apply {
                text = label
                textSize = 11f
                setTextColor(Color.WHITE)
                isAllCaps = false
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(bgHex))
                    cornerRadius = 24f
                }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = 8
                layoutParams = lp
                setPadding(24, 12, 24, 12)
                setOnClickListener { onClick() }
            }

        val btnToggle8D = makeBtn("Toggle 8D: Aktif") {
            val next = !MixerRepository.settings.value.enabled
            MixerRepository.update { it.copy(enabled = next) }
            SystemGlobalEqManager.apply(MixerRepository.settings.value)
            Toast.makeText(this, if (next) "Efek 8D Aktif" else "Efek 8D Nonaktif", Toast.LENGTH_SHORT).show()
        }

        val btnEq = makeBtn("Ganti Preset EQ") {
            eqIndex = (eqIndex + 1) % EQ_PRESETS.size
            val eqP = EQ_PRESETS[eqIndex]
            MixerRepository.update { it.copy(eq = eqP.gains) }
            SystemGlobalEqManager.apply(MixerRepository.settings.value)
            Toast.makeText(this, "EQ: ${eqP.label}", Toast.LENGTH_SHORT).show()
        }

        val btn8DPreset = makeBtn("Ganti Preset 8D") {
            presetIndex = (presetIndex + 1) % BUILTIN_PRESETS.size
            val p = BUILTIN_PRESETS[presetIndex]
            MixerRepository.set(p.build())
            SystemGlobalEqManager.apply(MixerRepository.settings.value)
            Toast.makeText(this, "8D: ${p.name}", Toast.LENGTH_SHORT).show()
        }

        val btnPlayPause = makeBtn("⏯ Putar / Jeda") {
            val callback = SpatialHostBridge.onTogglePlayback
            if (callback != null) runCatching { callback() }
            else Toast.makeText(this, "Hubungkan tombol putar melalui SpatialHostBridge.", Toast.LENGTH_SHORT).show()
        }

        val btnClipboard = makeBtn("📋 Tangkap Link", "#165B2E") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
            val url = Regex("https?://\\S+").find(text)?.value
            val payload = url ?: text
            val callback = SpatialHostBridge.onSharedText
            if (callback != null) {
                runCatching { callback(payload) }
            } else {
                val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, payload)
                }
                if (launch != null) startActivity(launch)
                else Toast.makeText(this, "Tidak ada layar utama untuk menerima tautan.", Toast.LENGTH_SHORT).show()
            }
            expanded = false
            panel.visibility = View.GONE
        }

        val btnClose = makeBtn("✕ Tutup Bubble", "#4A1822") {
            MixerRepository.floatingBubbleEnabled.value = false
            removeBubble()
            if (!MixerRepository.floatingLyricsEnabled.value) stopSelf()
        }

        panel.addView(btnToggle8D)
        panel.addView(btnEq)
        panel.addView(btn8DPreset)
        panel.addView(btnPlayPause)
        panel.addView(btnClipboard)
        panel.addView(btnClose)
        root.addView(toggleHeader)
        root.addView(panel)

        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        toggleHeader.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - touchX).toInt()
                    val dy = (ev.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    wm.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        expanded = !expanded
                        panel.visibility = if (expanded) View.VISIBLE else View.GONE
                        val isOn = MixerRepository.settings.value.enabled
                        btnToggle8D.text = if (isOn) "Toggle 8D: Aktif" else "Toggle 8D: Mati"
                    }
                    true
                }
                else -> false
            }
        }

        wm.addView(root, params)
        bubbleView = root
    }

    private fun showLyricsOverlay() {
        if (lyricsView != null) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 140
        }

        val tv = TextView(this).apply {
            text = "♪ Lirik Melayang Laguku"
            setTextColor(Color.parseColor("#1ED760"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(24, 12, 24, 12)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B3000000"))
                cornerRadius = 24f
            }
        }
        wm.addView(tv, params)
        lyricsView = tv

        scope.launch {
            while (lyricsView != null && MixerRepository.floatingLyricsEnabled.value) {
                val active = SpatialHostBridge.currentLyrics.value
                lyricsView?.text = active ?: SpatialHostBridge.currentTitle.value ?: "♪ Spatial Audio"
                delay(350L)
            }
        }
    }

    private fun removeBubble() {
        bubbleView?.let { runCatching { wm.removeView(it) } }
        bubbleView = null
    }

    private fun removeLyricsOverlay() {
        lyricsView?.let { runCatching { wm.removeView(it) } }
        lyricsView = null
    }

    override fun onDestroy() {
        removeBubble()
        removeLyricsOverlay()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.bagja.spatialaudio.BUBBLE_START"
        const val ACTION_STOP = "dev.bagja.spatialaudio.BUBBLE_STOP"
        const val ACTION_SYNC_LYRICS = "dev.bagja.spatialaudio.LYRICS_SYNC"

        fun start(context: Context) {
            context.startService(Intent(context, FloatingBubbleService::class.java).setAction(ACTION_START))
        }

        fun syncLyrics(context: Context) {
            context.startService(Intent(context, FloatingBubbleService::class.java).setAction(ACTION_SYNC_LYRICS))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FloatingBubbleService::class.java).setAction(ACTION_STOP))
        }
    }
}
