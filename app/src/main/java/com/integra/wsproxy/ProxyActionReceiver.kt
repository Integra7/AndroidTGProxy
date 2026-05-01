package com.integra.wsproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

class ProxyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            ProxyIntents.ACTION_OPEN_TELEGRAM -> {
                context.startService(Intent(context, ProxyService::class.java).apply {
                    this.action = ProxyIntents.ACTION_START
                })

                val cfg = ProxyConfigStore.get(context).load()
                val link = ProxyConfigStore.get(context).tgProxyLink(cfg)
                val open = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(open) }
            }

            else -> {
                context.startService(Intent(context, ProxyService::class.java).apply {
                    this.action = action
                })
            }
        }
    }
}

