package com.integra.wsproxy

import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit

class MainActivity : AppCompatActivity() {
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ProxyService.ACTION_STATE_CHANGED) return
            val running = intent.getBooleanExtra(ProxyService.EXTRA_RUNNING, ProxyService.isRunning)
            applyState(running)
        }
    }

    private fun applyState(running: Boolean) {
        val status = findViewById<TextView>(R.id.statusText)
        val toggle = findViewById<Button>(R.id.toggleBtn)
        status.text = if (running) "Started" else "Stopped"
        status.setBackgroundResource(
            if (running) R.drawable.status_badge_started else R.drawable.status_badge_stopped
        )
        toggle.text = if (running) "Stop proxy" else "Start proxy"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val requestNotif = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // No-op. If granted, foreground notification (and status icon) will be visible.
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val configText = findViewById<TextView>(R.id.configText)
        val toggle = findViewById<Button>(R.id.toggleBtn)
        val autostart = findViewById<SwitchCompat>(R.id.autostartSwitch)
        val openTelegram = findViewById<Button>(R.id.openTelegramBtn)
        val infoText = findViewById<TextView>(R.id.infoText)

        val prefs = getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)
        autostart.isChecked = prefs.getBoolean(BootReceiver.KEY_AUTOSTART, false)
        autostart.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(BootReceiver.KEY_AUTOSTART, isChecked) }
        }

        val cfg = ProxyConfigStore.get(this).load()
        val link = ProxyConfigStore.get(this).tgProxyLink(cfg)
        // Hide "settings" on main screen as requested, but keep the tg:// link working.
        configText.visibility = android.view.View.GONE
        infoText.visibility = android.view.View.GONE

        toggle.setOnClickListener {
            val running = ProxyStateStore.get(this).isRunning()
            val action = if (running) ProxyService.ACTION_STOP else ProxyService.ACTION_START
            startService(Intent(this, ProxyService::class.java).apply { this.action = action })
            // Optimistic UI update; real state will be corrected by broadcast/prefs.
            applyState(!running)
        }

        openTelegram.setOnClickListener {
            // Start proxy first so Telegram can connect immediately.
            startService(Intent(this, ProxyService::class.java).apply { action = ProxyService.ACTION_START })
            ProxyStateStore.get(this).setRunning(true)
            applyState(true)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        }

        applyState(ProxyStateStore.get(this).isRunning())
    }

    override fun onResume() {
        super.onResume()
        applyState(ProxyStateStore.get(this).isRunning())
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ProxyService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onStop()
    }
}
