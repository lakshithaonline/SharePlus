package com.example.shareplus

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.shareplus.AirPlayState.Phase
import com.example.shareplus.service.AirPlayService
import com.example.shareplus.ui.Ui
import com.example.shareplus.ui.Ui.BLUE
import com.example.shareplus.ui.Ui.FILL
import com.example.shareplus.ui.Ui.GREEN
import com.example.shareplus.ui.Ui.GREY
import com.example.shareplus.ui.Ui.HAIRLINE
import com.example.shareplus.ui.Ui.INK
import com.example.shareplus.ui.Ui.ORANGE
import com.example.shareplus.ui.Ui.SECONDARY
import com.example.shareplus.ui.Ui.TERTIARY
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Light, tvOS-inspired receiver screen: a white card with the TV name, live status and
 * three-step instructions on a soft grey backdrop. Mirroring goes full screen
 * on black.
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var mirrorView: SurfaceView
    private lateinit var home: FrameLayout
    private lateinit var card: LinearLayout
    private lateinit var nameText: TextView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var step3: TextView
    private lateinit var footer: TextView
    private lateinit var stats: TextView
    private lateinit var settingsButton: View
    private var wasMirroring = false

    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AirPlayService.start(this)
        buildViews()

        mirrorView.holder.addCallback(object : SurfaceHolder.Callback {
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
                launch { AirPlayState.videoSize.collect { fit(mirrorView, it) } }
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
        val mirroring = phase == Phase.MIRRORING
        // the SurfaceView stays VISIBLE (surface alive) under the home screen, so showing
        // the home screen never tears down a running decoder
        home.visibility = if (mirroring) View.GONE else View.VISIBLE
        if (mirroring && !wasMirroring) {
            stats.visibility = if (Settings(this).showStats) View.VISIBLE else View.GONE
        }
        if (!mirroring) {
            stats.visibility = View.GONE
            if (!settingsButton.hasFocus()) settingsButton.requestFocus()
        }
        wasMirroring = mirroring

        if (phase != Phase.IDLE && phase != Phase.STOPPED && phase != Phase.PAUSED) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        nameText.text = name
        step3.text = "Choose  “$name”"
        val (color, label) = when (phase) {
            Phase.STOPPED -> GREY to "Starting…"
            Phase.IDLE, Phase.MIRRORING -> GREEN to "Ready for AirPlay"
            Phase.CONNECTING -> ORANGE to "Connecting…"
            Phase.AUDIO_ONLY -> BLUE to "Playing audio from iPhone"
            Phase.PAUSED -> ORANGE to "Paused — unlock your iPhone to continue"
        }
        (statusDot.background as GradientDrawable).setColor(color)
        statusText.text = label
        footer.text = listOfNotNull(ip, "Tip: set Auto-Lock to Never so mirroring doesn’t stop when your iPhone sleeps")
            .joinToString("   ·   ")
    }

    // fit a video into the screen keeping its aspect ratio (portrait iPhone = pillarbox)
    private fun fit(view: SurfaceView, size: Pair<Int, Int>) {
        val (vw, vh) = size
        val cw = root.width
        val ch = root.height
        if (vw <= 0 || vh <= 0 || cw <= 0 || ch <= 0) return
        val scale = minOf(cw.toFloat() / vw, ch.toFloat() / vh)
        val lp = view.layoutParams as FrameLayout.LayoutParams
        val w = (vw * scale).toInt()
        val h = (vh * scale).toInt()
        if (lp.width == w && lp.height == h) return
        lp.width = w
        lp.height = h
        lp.gravity = Gravity.CENTER
        view.layoutParams = lp
    }

    private suspend fun refreshStats() {
        while (true) {
            if (stats.visibility == View.VISIBLE) {
                val v = AirPlayService.video
                stats.text = "${v.width}×${v.height}   ${v.fps} fps   ${v.codecName}   dropped ${v.dropped}"
            }
            delay(500)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val phase = AirPlayState.phase.value
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_INFO -> {
                if (phase == Phase.MIRRORING) {
                    stats.visibility = if (stats.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                openSettings()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- views ----

    private fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java))

    private fun label(sizeSp: Float, color: Int, family: String = "sans-serif") =
        Ui.label(this, sizeSp, color, family)

    private fun rounded(color: Int, radiusDp: Int) = Ui.rounded(this, color, radiusDp)

    private fun oval(color: Int) = Ui.oval(color)

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            fit(mirrorView, AirPlayState.videoSize.value)
        }

        fun fullscreen() = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        mirrorView = SurfaceView(this)
        root.addView(mirrorView, fullscreen())

        home = FrameLayout(this).apply {
            background = Ui.backdrop()
            clipChildren = false  // focused Settings button grows past its row
        }

        // top bar: wordmark left, time right (like tvOS)
        home.addView(label(18f, INK, "sans-serif-medium").apply { text = "SharePlus" },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(56), dp(40), 0, 0) })
        val topRight = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        settingsButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(9), dp(20), dp(9))
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_settings) },
                LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
            addView(label(16f, INK, "sans-serif-medium").apply { text = "Settings" })
            setOnClickListener { openSettings() }
        }
        Ui.focusable(settingsButton, 22, rest = Color.argb(140, 255, 255, 255), grow = 1.08f)
        topRight.addView(settingsButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(28) })
        topRight.addView(TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        home.addView(topRight, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(32), dp(56), 0) })

        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(Color.WHITE, 36)
            elevation = dp(18).toFloat()
            setPadding(dp(56), dp(36), dp(56), dp(32))
            minimumWidth = dp(480)
        }

        val icon = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(10, 132, 255), Color.rgb(94, 92, 230))).apply { shape = GradientDrawable.OVAL }
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_airplay) },
                FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(68), dp(68)))

        nameText = label(32f, INK, "sans-serif-medium").apply { gravity = Gravity.CENTER }
        card.addView(nameText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })

        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply { background = oval(GREEN) }
        statusText = label(16f, SECONDARY)
        status.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
        status.addView(statusText)
        card.addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })

        card.addView(View(this).apply { setBackgroundColor(HAIRLINE) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(24); bottomMargin = dp(20) })

        fun step(n: Int, text: String): TextView {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(label(13f, INK, "sans-serif-medium").apply {
                this.text = n.toString()
                gravity = Gravity.CENTER
                background = oval(FILL)
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(14) })
            val t = label(16f, INK).apply { this.text = text }
            row.addView(t)
            card.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (n == 1) 0 else 10) })
            return t
        }
        step(1, "Open Control Centre on your iPhone")
        step(2, "Tap  Screen Mirroring")
        step3 = step(3, "")

        home.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        footer = label(12f, TERTIARY).apply { gravity = Gravity.CENTER }
        home.addView(footer, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(28) })

        root.addView(home, fullscreen())

        stats = label(16f, Color.WHITE, "sans-serif-medium").apply {
            background = rounded(Color.argb(170, 28, 28, 30), 18)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            visibility = View.GONE
        }
        root.addView(stats, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
            .apply { setMargins(dp(32), dp(32), dp(32), dp(32)) })

        setContentView(root)
    }

}
