package com.ukpro.android.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ukpro.android.R
import com.ukpro.android.UkApp
import com.ukpro.android.UkRuntime
import com.ukpro.android.ui.ClipboardSendActivity
import com.ukpro.android.ui.MainActivity

/**
 * User-started foreground service (type connectedDevice) that keeps the laptop connections alive
 * while the app is in the background. Its notification is the visible "UK Pro is connected" indicator
 * and carries Pause/Resume and "Send clipboard" (manual sync) actions.
 */
class ConnectionService : Service() {
    private val ticker = object : Runnable {
        override fun run() { UkRuntime.refresh(); updateNotification(); UkRuntime.main.postDelayed(this, 2000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { UkRuntime.stop(); stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE_PAUSE -> UkRuntime.setPaused(!UkRuntime.settings.current.paused)
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, build(), type)
        UkRuntime.start()
        UkRuntime.main.removeCallbacks(ticker)
        UkRuntime.main.post(ticker)
        return START_STICKY
    }

    override fun onDestroy() {
        UkRuntime.main.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun updateNotification() =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build())

    private fun build(): Notification {
        val hubs = UkRuntime.hubs.value
        val connected = hubs.filter { it.connected }
        val paused = UkRuntime.settings.current.paused
        val title = when {
            paused -> "UK Pro — paused"
            connected.isEmpty() -> "UK Pro — looking for your laptop"
            else -> "Connected to " + connected.joinToString { it.name }
        }
        val text = connected.firstOrNull()?.let { "${it.transport.uppercase()} · ${if (it.rttMs >= 0) "${it.rttMs} ms" else "…"}" }
            ?: "Same Wi-Fi or hotspot needed"
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val pause = PendingIntent.getService(this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_TOGGLE_PAUSE), flags)
        val clip = PendingIntent.getActivity(this, 2, Intent(this, ClipboardSendActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        return NotificationCompat.Builder(this, UkApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(0, if (paused) "Resume" else "Pause", pause)
            .addAction(0, "Send clipboard", clip)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 7
        const val ACTION_STOP = "com.ukpro.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.ukpro.TOGGLE_PAUSE"

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, ConnectionService::class.java))
        fun stop(ctx: Context) = ctx.startService(Intent(ctx, ConnectionService::class.java).setAction(ACTION_STOP))
    }
}
