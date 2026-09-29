package com.example.shareplus

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.example.shareplus.service.AirPlayService
import com.example.shareplus.ui.Ui

/** tvOS-style settings: big glyph on the left, a focusable list on the right. */
class SettingsActivity : ComponentActivity() {

    private lateinit var settings: Settings
    private lateinit var list: LinearLayout
    private fun dp(v: Int) = Ui.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.backdrop()
            setPadding(dp(56), dp(48), dp(56), dp(48))
        }

        // left: glyph + title
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        side.addView(FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(142, 142, 147), Color.rgb(99, 99, 102))).apply { shape = GradientDrawable.OVAL }
            addView(ImageView(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_settings)
                setColorFilter(Color.WHITE)
            }, FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(128), dp(128)))
        side.addView(Ui.label(this, 30f, Ui.INK, "sans-serif-medium").apply { text = "Settings" },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) })
        root.addView(side, LinearLayout.LayoutParams(0, -1, 1f))

        // right: rows
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(list)
        }
        list.clipChildren = false
        root.addView(scroll, LinearLayout.LayoutParams(0, -1, 1.4f))
        setContentView(root)

        build()
    }

    private fun build() {
        list.removeAllViews()
        section("AirPlay")
        row("TV Name", settings.tvName) { editName() }
        toggle("Open When iPhone Connects", settings.autoOpen) { settings.autoOpen = it }
        toggle("Start When TV Turns On", settings.startOnBoot) { settings.startOnBoot = it }

        section("Mirroring")
        toggle("Show Performance Stats", settings.showStats) { settings.showStats = it }

        section("About")
        row("Version", versionName()) {}
        row("Developer", DEVELOPER) {}
        row("Email", DEV_EMAIL) {}
        row("GitHub", "github.com/$DEV_GITHUB") {}
        row("IP Address", AirPlayState.ipAddress.value ?: "Not connected") {}
        row("Open-Source Licences", "") { showLicences() }

        list.getChildAt(1)?.requestFocus()
    }

    private fun section(title: String) {
        list.addView(Ui.label(this, 15f, Ui.TERTIARY, "sans-serif-medium").apply { text = title.uppercase() },
            LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(if (list.childCount == 0) 0 else 28)
                bottomMargin = dp(8)
                marginStart = dp(20)
            })
    }

    private fun row(title: String, value: String, onClick: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        Ui.focusable(row, 14, rest = Color.argb(110, 255, 255, 255))
        row.addView(Ui.label(this, 19f, Ui.INK).apply { text = title }, LinearLayout.LayoutParams(0, -2, 1f))
        val valueView = Ui.label(this, 19f, Ui.SECONDARY).apply { text = value }
        row.addView(valueView)
        if (value.isEmpty()) row.addView(Ui.label(this, 19f, Ui.TERTIARY).apply { text = "›" })
        row.setOnClickListener { onClick() }
        list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        return valueView
    }

    private fun toggle(title: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        var on = initial
        lateinit var value: TextView
        value = row(title, onOff(on)) {
            on = !on
            onChange(on)
            value.text = onOff(on)
            value.setTextColor(if (on) Ui.BLUE else Ui.SECONDARY)
        }
        value.setTextColor(if (on) Ui.BLUE else Ui.SECONDARY)
    }

    private fun onOff(on: Boolean) = if (on) "On" else "Off"

    private fun editName() {
        val input = EditText(this).apply {
            setText(settings.tvName)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE
            filters = arrayOf(InputFilter.LengthFilter(Settings.MAX_NAME))
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("TV Name")
            .setMessage("This is the name your iPhone shows under Screen Mirroring.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != settings.tvName) {
                    settings.tvName = name
                    AirPlayService.restart(this)  // re-announce under the new name
                    build()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLicences() {
        val text = """
            SharePlus is free software under the GNU General Public License v3.
            Developed by $DEVELOPER ($DEV_EMAIL, github.com/$DEV_GITHUB).

            It is built on:
            • UxPlay — AirPlay server (GPL-3.0), by F. Duncanh and contributors
            • android-airplay-server by jqssun, and airplay-receiver by manu-tech-code (GPL-3.0)
            • libplist — libimobiledevice project (LGPL-2.1)
            • OpenSSL (Apache-2.0)
            • Oboe — Google (Apache-2.0)
            • AndroidX — Google (Apache-2.0)

            AirPlay is a trademark of Apple Inc. SharePlus is not affiliated with Apple.
        """.trimIndent()
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle("Open-Source Licences")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "1.0"

    private companion object {
        const val DEVELOPER = "Lakshitha Geethmal"
        const val DEV_EMAIL = "lakshithageethmal@gmail.com"
        const val DEV_GITHUB = "lakshithaonline"
    }
}
