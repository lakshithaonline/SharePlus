package com.example.shareplus

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.shareplus.AirPlayState.Phase
import com.example.shareplus.service.AirPlayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var idle: LinearLayout
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private lateinit var address: TextView
    private lateinit var status: TextView
    private lateinit var stats: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AirPlayService.start(this)
        buildViews()

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) =
                AirPlayService.video.attachSurface(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) =
                AirPlayService.video.detachSurface(holder.surface)
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(AirPlayState.phase, AirPlayState.serverName, AirPlayState.ipAddress) { p, n, ip ->
                        Triple(p, n, ip)
                    }.collect { (p, n, ip) -> render(p, n, ip) }
                }
                launch { AirPlayState.videoSize.collect { layoutVideo() } }
                launch { driftIdle() }
                launch { refreshStats() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AirPlayState.activityVisible.value = true
    }

    override fun onStop() {
        AirPlayState.activityVisible.value = false
        super.onStop()
    }

    private fun render(phase: Phase, name: String, ip: String?) {
        val video = phase == Phase.MIRRORING
        surfaceView.visibility = if (video) View.VISIBLE else View.INVISIBLE
        idle.visibility = if (video) View.GONE else View.VISIBLE
        // idle backdrop on the root, so the drifting idle text never exposes an edge
        root.setBackgroundColor(if (video) Color.BLACK else IDLE_BG)
        if (!video) stats.visibility = View.GONE
        // keep the TV awake while something is playing, let it sleep when idle
        if (video || phase == Phase.AUDIO_ONLY || phase == Phase.CONNECTING) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        title.text = when (phase) {
            Phase.STOPPED -> "Starting…"
            Phase.IDLE, Phase.MIRRORING -> "Ready to mirror"
            Phase.CONNECTING -> "Connecting…"
            Phase.AUDIO_ONLY -> "Playing audio from iPhone"
            Phase.PAUSED -> "Paused — unlock your iPhone to continue"
        }
        hint.text = "On your iPhone: swipe down from the top-right → Screen Mirroring → $name"
        address.text = if (ip != null) "$name  ·  $ip" else name
        status.text = "Tip: keep your iPhone awake — Settings → Display & Brightness → Auto-Lock → Never"
    }

    // fit the video into the screen, keeping its aspect ratio (portrait iPhone = pillarbox)
    private fun layoutVideo() {
        val (vw, vh) = AirPlayState.videoSize.value
        val cw = root.width
        val ch = root.height
        if (vw <= 0 || vh <= 0 || cw <= 0 || ch <= 0) return
        val scale = minOf(cw.toFloat() / vw, ch.toFloat() / vh)
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        lp.width = (vw * scale).toInt()
        lp.height = (vh * scale).toInt()
        lp.gravity = Gravity.CENTER
        surfaceView.layoutParams = lp
    }

    // move the idle text around slowly so a static screen doesn't burn in
    private suspend fun driftIdle() {
        while (true) {
            idle.animate()
                .translationX(Random.nextInt(-80, 81).toFloat())
                .translationY(Random.nextInt(-50, 51).toFloat())
                .setDuration(4000).start()
            delay(60_000)
        }
    }

    private suspend fun refreshStats() {
        while (true) {
            if (stats.visibility == View.VISIBLE) {
                val v = AirPlayService.video
                stats.text = "${v.width}x${v.height}  ·  ${v.fps} fps  ·  ${v.codecName}  ·  dropped ${v.dropped}"
            }
            delay(500)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO -> {
                if (AirPlayState.phase.value == Phase.MIRRORING) {
                    stats.visibility = if (stats.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutVideo() }

        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        fun text(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        idle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val clock = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 96f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val date = TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.rgb(170, 180, 200))
        }
        title = text(34f, Color.WHITE, bold = true)
        hint = text(20f, Color.rgb(200, 210, 230))
        address = text(16f, Color.rgb(120, 135, 160))
        status = text(14f, Color.rgb(100, 115, 140))
        clock.gravity = Gravity.CENTER
        date.gravity = Gravity.CENTER
        listOf(clock, date, title, hint, address, status).forEach { idle.addView(it) }
        // spacing above each line after the clock
        listOf(date to 0, title to 40, hint to 16, address to 28, status to 8).forEach { (v, dp) ->
            (v.layoutParams as LinearLayout.LayoutParams).topMargin =
                (dp * resources.displayMetrics.density).toInt()
        }
        root.addView(idle, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        stats = text(16f, Color.WHITE).apply {
            setBackgroundColor(Color.argb(170, 0, 0, 0))
            setPadding(24, 12, 24, 12)
            visibility = View.GONE
        }
        root.addView(stats, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END).apply { setMargins(32, 32, 32, 32) })

        setContentView(root)
    }

    private companion object {
        val IDLE_BG = Color.rgb(10, 14, 24)
    }
}
