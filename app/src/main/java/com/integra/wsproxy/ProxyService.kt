package com.integra.wsproxy

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
            ProxyIntents.ACTION_START -> startProxy()
            ProxyIntents.ACTION_STOP -> stopProxy()
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

        startForeground(
            ProxyNotification.NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_running_on, cfg.listenHost, cfg.listenPort))
        )
        broadcastState(true)
        scope.launch {
            runCatching { proxy?.run() }
                .onFailure {
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
        sendBroadcast(Intent(ProxyIntents.ACTION_STATE_CHANGED).apply {
            setPackage(packageName)
            putExtra(ProxyIntents.EXTRA_RUNNING, running)
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
            Intent(this, ProxyActionReceiver::class.java).apply { action = ProxyIntents.ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openTelegramIntent = PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, ProxyActionReceiver::class.java).apply { action = ProxyIntents.ACTION_OPEN_TELEGRAM },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, ProxyNotification.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ws_status)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .addAction(0, getString(R.string.notification_action_open_telegram), openTelegramIntent)
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            ProxyNotification.CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        mgr.createNotificationChannel(ch)
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}

