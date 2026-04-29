package com.example.androidtgproxy

import android.content.Context
import androidx.core.content.edit

class ProxyStateStore private constructor(ctx: Context) {
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isRunning(): Boolean = prefs.getBoolean(KEY_RUNNING, false)

    fun setRunning(running: Boolean) {
        prefs.edit { putBoolean(KEY_RUNNING, running) }
    }

    companion object {
        private const val PREFS = "tg_ws_proxy"
        private const val KEY_RUNNING = "proxy_running"

        fun get(ctx: Context) = ProxyStateStore(ctx.applicationContext)
    }
}

