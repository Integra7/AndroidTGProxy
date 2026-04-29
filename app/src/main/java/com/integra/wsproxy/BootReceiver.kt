package com.integra.wsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val autostart = prefs.getBoolean(KEY_AUTOSTART, false)
        if (!autostart) return

        context.startService(Intent(context, ProxyService::class.java).apply {
            action = ProxyService.ACTION_START
        })
    }

    companion object {
        const val PREFS = "tg_ws_proxy"
        const val KEY_AUTOSTART = "autostart"
    }
}

