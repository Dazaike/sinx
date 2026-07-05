package com.sinx.notifbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Triggered by two sources:
 *  1. System broadcasts after boot (BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, QUICKBOOT_POWERON)
 *  2. [KeepAliveService]'s repeating AlarmManager watchdog every 15 minutes
 *
 * In both cases the action is the same: ensure [KeepAliveService] is running.
 * startForegroundService is idempotent if the service is already alive — the OS
 * just calls onStartCommand again, which returns START_STICKY.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, KeepAliveService::class.java)
        )
    }
}
