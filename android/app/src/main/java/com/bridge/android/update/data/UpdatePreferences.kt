package com.bridge.android.update.data

import android.content.Context
import android.content.SharedPreferences
import com.bridge.android.update.model.UpdateManifest

class UpdatePreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isNotificationEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATION_ENABLED, value).apply()

    var isPeriodicCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_PERIODIC_CHECK_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_PERIODIC_CHECK_ENABLED, value).apply()

    var lastCheckedTimestamp: Long
        get() = prefs.getLong(KEY_LAST_CHECKED, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECKED, value).apply()

    var dismissedVersionCode: Long
        get() = prefs.getLong(KEY_DISMISSED_VERSION_CODE, 0L)
        set(value) = prefs.edit().putLong(KEY_DISMISSED_VERSION_CODE, value).apply()

    var customManifestUrl: String?
        get() = prefs.getString(KEY_CUSTOM_MANIFEST_URL, null)
        set(value) = prefs.edit().putString(KEY_CUSTOM_MANIFEST_URL, value).apply()

    fun getManifestUrl(): String {
        val custom = customManifestUrl
        return if (!custom.isNullOrBlank()) custom else UpdateConfig.DEFAULT_MANIFEST_URL
    }

    fun saveCachedManifest(manifest: UpdateManifest?) {
        if (manifest == null) {
            prefs.edit().remove(KEY_CACHED_MANIFEST).apply()
        } else {
            // Re-encode or store json
            val json = org.json.JSONObject().apply {
                put("versionCode", manifest.versionCode)
                put("versionName", manifest.versionName)
                put("minSupportedVersionCode", manifest.minSupportedVersionCode)
                put("apkUrl", manifest.apkUrl)
                put("apkSha256", manifest.apkSha256)
                put("apkSizeBytes", manifest.apkSizeBytes)
                put("releaseNotes", manifest.releaseNotes)
                put("publishedAt", manifest.publishedAt)
                put("mandatory", manifest.mandatory)
                put("signature", manifest.signature)
            }.toString()
            prefs.edit().putString(KEY_CACHED_MANIFEST, json).apply()
        }
    }

    fun getCachedManifest(): UpdateManifest? {
        val raw = prefs.getString(KEY_CACHED_MANIFEST, null) ?: return null
        return try {
            UpdateManifest.fromJson(raw)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "bridge_update_prefs"
        private const val KEY_NOTIFICATION_ENABLED = "update_notifications_enabled"
        private const val KEY_PERIODIC_CHECK_ENABLED = "update_periodic_check_enabled"
        private const val KEY_LAST_CHECKED = "update_last_checked_timestamp"
        private const val KEY_DISMISSED_VERSION_CODE = "update_dismissed_version_code"
        private const val KEY_CUSTOM_MANIFEST_URL = "update_custom_manifest_url"
        private const val KEY_CACHED_MANIFEST = "update_cached_manifest_json"
    }
}
