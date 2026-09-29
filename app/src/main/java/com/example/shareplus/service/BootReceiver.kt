package com.example.shareplus.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.shareplus.Settings

// start receiving as soon as the TV boots, without opening the app first
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Settings(context).startOnBoot) {
            AirPlayService.start(context)
        }
    }
}
