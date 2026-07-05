package com.sinx.notifbridge

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper around SharedPreferences for persisting the PC target address.
 * All reads return a sensible default so callers never receive null.
 */
class SettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** PC's local IPv4, e.g. "192.168.1.42" */
    var pcIp: String
        get() = prefs.getString(KEY_IP, DEFAULT_IP) ?: DEFAULT_IP
        set(value) = prefs.edit().putString(KEY_IP, value.trim()).apply()

    /** Port the Python server is listening on (1 – 65535) */
    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    /**
     * Comma-separated list of package names to block (e.g. "com.sinx.notifbridge,com.android.systemui").
     * Empty string means nothing is blocked.
     */
    var blockedPackages: Set<String>
        get() {
            val raw = prefs.getString(KEY_BLOCKED, "") ?: ""
            return if (raw.isBlank()) emptySet()
            else raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        }
        set(value) = prefs.edit().putString(KEY_BLOCKED, value.joinToString(",")).apply()

    /** Whether forwarding is currently active */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * Whether "silent" notifications (low/min importance channels, or notifications posted
     * without sound/vibration — e.g. background sync, offline map updates) should be dropped
     * instead of forwarded to the PC.
     */
    var filterSilent: Boolean
        get() = prefs.getBoolean(KEY_FILTER_SILENT, true)
        set(value) = prefs.edit().putBoolean(KEY_FILTER_SILENT, value).apply()

    /** Fully-qualified base URL built from ip + port */
    val endpointUrl: String get() = "http://$pcIp:$port/notify"

    companion object {
        private const val PREFS_NAME = "sinx_prefs"
        private const val KEY_IP = "pc_ip"
        private const val KEY_PORT = "pc_port"
        private const val KEY_BLOCKED = "blocked_packages"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_FILTER_SILENT = "filter_silent"
        const val DEFAULT_IP = "192.168.1.100"
        const val DEFAULT_PORT = 8765
    }
}
