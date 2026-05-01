package com.integra.wsproxy

import android.content.Context
import androidx.core.content.edit
import java.security.SecureRandom

class ProxyConfigStore private constructor(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): ProxyConfig {
        val host = prefs.getString(KEY_HOST, "127.0.0.1") ?: "127.0.0.1"
        val port = prefs.getInt(KEY_PORT, 1443)
        val secret = prefs.getString(KEY_SECRET, null) ?: generateSecret().also {
            prefs.edit { putString(KEY_SECRET, it) }
        }

        return ProxyConfig(
            listenHost = host,
            listenPort = port,
            secretHex = secret,
            dcRedirects = mapOf(2 to "149.154.167.220", 4 to "149.154.167.220"),
            dcOverrides = mapOf(203 to 2),
            bufferSize = 256 * 1024,
        )
    }

    fun tgProxyLink(cfg: ProxyConfig): String {
        return "tg://proxy?server=${cfg.listenHost}&port=${cfg.listenPort}&secret=dd${cfg.secretHex}"
    }

    private fun generateSecret(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFS = "tg_ws_proxy"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_SECRET = "secret"

        fun get(ctx: Context) = ProxyConfigStore(ctx.applicationContext)
    }
}

