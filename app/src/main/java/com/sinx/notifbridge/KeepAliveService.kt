package com.sinx.notifbridge

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the process alive so [NotificationListener]
 * is never killed by battery management.
 *
 * Two-layer survival strategy:
 *  1. Persistent foreground notification (primary — prevents process kill)
 *  2. AlarmManager watchdog (secondary — restarts us if we ARE killed anyway)
 *
 * [WatchdogReceiver] handles the alarm and calls startForegroundService().
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        scheduleWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if the OS kills us, it re-creates us as soon as resources free up
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Reschedule immediately on unexpected destroy so the watchdog fires ASAP
        scheduleWatchdog(delayMs = 5_000L)
    }

    // ── Watchdog alarm ────────────────────────────────────────────────────────

    private fun scheduleWatchdog(delayMs: Long = WATCHDOG_INTERVAL_MS) {
        val alarm = getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            this,
            WATCHDOG_REQUEST_CODE,
            Intent(this, WatchdogReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // ELAPSED_REALTIME_WAKEUP fires even when the device is dozing
        alarm.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            intent
        )
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sinx Keep-Alive",
            NotificationManager.IMPORTANCE_MIN   // silent, no badge, collapsed by default
        ).apply {
            description = "Keeps the notification bridge running"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sinx is running")
            .setContentText("Forwarding notifications to your PC")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID          = "sinx_keepalive"
        const val NOTIF_ID            = 1001
        const val WATCHDOG_REQUEST_CODE = 9001
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L  // 15 min
    }
}
