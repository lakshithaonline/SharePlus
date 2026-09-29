package com.example.shareplus

import android.content.Context

// user settings, stored in the same prefs file as the AirPlay device id
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shareplus", Context.MODE_PRIVATE)

    // name shown in the iPhone's Screen Mirroring list
    var tvName: String
        get() = prefs.getString("tv_name", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
        set(v) = prefs.edit().putString("tv_name", v.trim().take(MAX_NAME)).apply()

    // bring SharePlus to the front when an iPhone starts mirroring
    var autoOpen: Boolean
        get() = prefs.getBoolean("auto_open", true)
        set(v) = prefs.edit().putBoolean("auto_open", v).apply()

    var startOnBoot: Boolean
        get() = prefs.getBoolean("start_on_boot", true)
        set(v) = prefs.edit().putBoolean("start_on_boot", v).apply()

    // resolution / fps / decoder overlay while mirroring (OK on the remote still toggles it)
    var showStats: Boolean
        get() = prefs.getBoolean("show_stats", false)
        set(v) = prefs.edit().putBoolean("show_stats", v).apply()

    companion object {
        const val DEFAULT_NAME = "SharePlus"
        const val MAX_NAME = 40
    }
}
