package com.example.androidtgproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class ProxyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var proxy: MtprotoWsProxy? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProxy()
            ACTION_STOP -> stopProxy()
            else -> startProxy()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopProxy()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startProxy() {
        if (proxy != null) return
        val cfg = ProxyConfigStore.get(this).load()
        proxy = MtprotoWsProxy(cfg)
        isRunning = true
        ProxyStateStore.get(this).setRunning(true)

        startForeground(NOTIF_ID, buildNotification("Running on ${cfg.listenHost}:${cfg.listenPort}"))
        broadcastState(true)
        scope.launch {
            runCatching { proxy?.run() }
                .onFailure {
                    // Stop on fatal errors so UI doesn't lie.
                    stopProxy()
                }
        }
    }

    private fun stopProxy() {
        if (!isRunning && proxy == null) {
            stopSelf()
            return
        }
        isRunning = false
        ProxyStateStore.get(this).setRunning(false)
        proxy?.close()
        proxy = null
        broadcastState(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcastState(running: Boolean) {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNNING, running)
        })
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, ProxyActionReceiver::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openTelegramIntent = PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, ProxyActionReceiver::class.java).apply { action = ACTION_OPEN_TELEGRAM },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ws_status)
            .setContentTitle("TG WS Proxy")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .addAction(0, "Open Telegram", openTelegramIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            "TG WS Proxy",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        mgr.createNotificationChannel(ch)
    }

    companion object {
        const val ACTION_START = "com.example.androidtgproxy.action.START"
        const val ACTION_STOP = "com.example.androidtgproxy.action.STOP"
        const val ACTION_OPEN_TELEGRAM = "com.example.androidtgproxy.action.OPEN_TELEGRAM"
        const val ACTION_STATE_CHANGED = "com.example.androidtgproxy.action.STATE_CHANGED"
        const val EXTRA_RUNNING = "running"

        // Bump channel id to avoid old user channel settings (silent/minimized) hiding status icons.
        private const val CHANNEL_ID = "tg_ws_proxy_v2"
        private const val NOTIF_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set
    }
}

