package com.example.shareplus.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView

// Apple system colours (light) and small view helpers shared by the TV screens
object Ui {
    val INK = Color.rgb(29, 29, 31)
    val SECONDARY = Color.rgb(110, 110, 115)
    val TERTIARY = Color.rgb(134, 134, 139)
    val HAIRLINE = Color.rgb(229, 229, 234)
    val FILL = Color.rgb(242, 242, 247)
    val GREEN = Color.rgb(52, 199, 89)
    val ORANGE = Color.rgb(255, 159, 10)
    val BLUE = Color.rgb(0, 122, 255)
    val GREY = Color.rgb(174, 174, 178)
    val BACKDROP_TOP = Color.rgb(251, 251, 253)
    val BACKDROP_BOTTOM = Color.rgb(232, 232, 237)

    fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun backdrop() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(BACKDROP_TOP, BACKDROP_BOTTOM))

    fun label(context: Context, sizeSp: Float, color: Int, family: String = "sans-serif") =
        TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            typeface = Typeface.create(family, Typeface.NORMAL)
            includeFontPadding = false
        }

    fun rounded(context: Context, color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(context, radiusDp).toFloat()
    }

    fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /**
     * tvOS-style focus: the focused item becomes a white raised tile and grows slightly;
     * unfocused it sits flat on [rest].
     */
    fun focusable(view: View, radiusDp: Int, rest: Int = Color.TRANSPARENT, grow: Float = 1.03f) {
        val ctx = view.context
        view.isFocusable = true
        view.isClickable = true
        view.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(ctx, Color.WHITE, radiusDp))
            addState(intArrayOf(), rounded(ctx, rest, radiusDp))
        }
        view.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) grow else 1f).scaleY(if (has) grow else 1f)
                .translationZ(if (has) dp(ctx, 10).toFloat() else 0f)
                .setDuration(150).start()
        }
    }
}
