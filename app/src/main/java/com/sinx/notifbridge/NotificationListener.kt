package com.sinx.notifbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Intercepts every system notification and forwards relevant ones to the Windows PC.
 *
 * Lifecycle:
 *  - The system binds this service once the user grants notification access.
 *  - [KeepAliveService] runs a foreground notification to prevent the process from being killed.
 *  - [PcSender] handles the async HTTP delivery on a dedicated IO dispatcher.
 */
class NotificationListener : NotificationListenerService() {

    private lateinit var sender: PcSender
    private lateinit var settings: SettingsManager

    /** Packages that are always suppressed regardless of user configuration */
    private val hardBlockedPackages = setOf(
        "com.sinx.notifbridge",           // ourselves
        "android",                        // system internals
        "com.android.systemui",           // status bar / system UI
        "com.android.server.telecom",     // call stack internals
    )

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(applicationContext)
        sender = PcSender(settings)
        Log.i(TAG, "NotificationListener created, endpoint → ${settings.endpointUrl}")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName

        // Hard-block noisy / recursive packages first
        if (pkg in hardBlockedPackages) return

        // User-configured block list
        if (pkg in settings.blockedPackages) return

        // Skip group summaries — but only when a real child notification exists to
        // carry the actual content. Some apps (e.g. Hinge) post a single notification
        // that is itself flagged as a group summary with no separate child ever posted;
        // unconditionally dropping all summaries would silently swallow those entirely.
        val flags = sbn.notification.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0 && hasSiblingInGroup(sbn)) return

        // Skip ongoing / persistent notifications (media players, navigation, etc.)
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return

        // Skip silent / low-priority notifications (background sync, offline map updates, etc.)
        if (settings.filterSilent && isSilent(sbn)) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE).orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        // Skip empty notifications (often a side-effect of updating an ongoing service notif)
        if (title.isBlank() && text.isBlank()) return

        val appName = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            ).toString()
        }.getOrDefault(pkg)

        val payload = PcSender.Payload(
            app = appName,
            title = title,
            text = text,
            packageName = pkg
        )

        Log.d(TAG, "Forwarding [$appName] $title")
        sender.send(payload)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Nothing to do — we don't mirror dismissals
    }

    /**
     * True when another currently-active notification from the same app and group
     * (i.e. an actual child notification) exists alongside this group summary.
     */
    private fun hasSiblingInGroup(sbn: StatusBarNotification): Boolean = runCatching {
        activeNotifications.any {
            it.key != sbn.key &&
                it.packageName == sbn.packageName &&
                it.groupKey == sbn.groupKey
        }
    }.getOrDefault(false)

    /**
     * Determines whether a notification is "silent" — i.e. it wouldn't have made a sound,
     * vibrated, or popped up a heads-up alert on the phone itself. This catches things like
     * background sync progress, offline map downloads, and other low-importance chatter that
     * apps post without actually alerting the user.
     */
    private fun isSilent(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // On O+ the channel is the source of truth for alerting behavior — sound/vibrate
            // fields on the Notification itself are ignored by the system in favor of the
            // channel's configuration, so we must check importance here rather than below.
            val channel: NotificationChannel? = runCatching {
                notificationManager.getNotificationChannel(notification.channelId)
            }.getOrNull()

            // LOW/MIN importance channels never make sound or show heads-up — exactly the
            // "silent" background-update style notifications we want to drop.
            return channel != null && channel.importance <= NotificationManager.IMPORTANCE_LOW
        }

        // Pre-O: fall back to legacy priority/sound/vibration fields.
        if (notification.priority <= Notification.PRIORITY_MIN) return true

        val hasSound = notification.sound != null
        val hasVibration = notification.vibrate?.isNotEmpty() == true
        val defaultsAlert = (notification.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)) != 0

        return !hasSound && !hasVibration && !defaultsAlert
    }

    companion object {
        private const val TAG = "NotificationListener"
    }
}
