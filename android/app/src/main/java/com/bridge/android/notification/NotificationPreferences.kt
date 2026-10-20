package com.bridge.android.notification

import android.content.Context
import android.content.SharedPreferences

class NotificationPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "bridge_notification_prefs"
        private const val KEY_NOTIF_SYNC_ENABLED = "notif_sync_enabled"
        private const val KEY_SHOW_PREVIEWS = "notif_show_previews"
        private const val KEY_SHOW_SYSTEM_APPS = "notif_show_system_apps"
        private const val KEY_SHOW_INCOMING_CALLS = "notif_show_incoming_calls"
        private const val KEY_ENABLED_PACKAGES = "notif_enabled_packages"
        private const val KEY_RECENT_PACKAGES = "notif_recent_packages"
    }

    var isSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_SYNC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_SYNC_ENABLED, value).apply()

    var showPreviews: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PREVIEWS, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_PREVIEWS, value).apply()

    var showSystemApps: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SYSTEM_APPS, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_SYSTEM_APPS, value).apply()

    var showIncomingCalls: Boolean
        get() = prefs.getBoolean(KEY_SHOW_INCOMING_CALLS, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_INCOMING_CALLS, value).apply()

    fun getEnabledPackages(): Set<String> {
        return prefs.getStringSet(KEY_ENABLED_PACKAGES, emptySet()) ?: emptySet()
    }

    fun isPackageEnabled(packageName: String): Boolean {
        return getEnabledPackages().contains(packageName)
    }

    fun setPackageEnabled(packageName: String, enabled: Boolean) {
        val set = getEnabledPackages().toMutableSet()
        if (enabled) {
            set.add(packageName)
        } else {
            set.remove(packageName)
        }
        prefs.edit().putStringSet(KEY_ENABLED_PACKAGES, set).apply()
    }

    fun getRecentPackages(): List<String> {
        val raw = prefs.getString(KEY_RECENT_PACKAGES, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    @Synchronized
    fun recordRecentPackage(packageName: String) {
        val current = getRecentPackages().toMutableList()
        current.remove(packageName)
        current.add(0, packageName)
        // Keep at most 20 recent
        val trimmed = current.take(20)
        prefs.edit().putString(KEY_RECENT_PACKAGES, trimmed.joinToString(",")).apply()
    }
}
