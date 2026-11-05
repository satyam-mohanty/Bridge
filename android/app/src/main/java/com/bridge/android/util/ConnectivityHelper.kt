package com.bridge.android.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

object ConnectivityHelper {

    @Volatile
    private var cachedWifiEnabled: Boolean? = null

    fun updateWifiState(state: Int) {
        cachedWifiEnabled = when (state) {
            WifiManager.WIFI_STATE_ENABLED -> true
            WifiManager.WIFI_STATE_DISABLED,
            WifiManager.WIFI_STATE_DISABLING -> false
            else -> cachedWifiEnabled
        }
    }

    fun isWifiEnabled(context: Context): Boolean {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val isEnabled = wifiManager?.isWifiEnabled
            if (isEnabled != null) {
                cachedWifiEnabled = isEnabled
                return isEnabled
            }
        } catch (e: Exception) {
            LogManager.e("Error checking Wi-Fi status", e)
        }
        return cachedWifiEnabled ?: false
    }

    fun getMissingConnectivityReason(wifi: Boolean): String? {
        return if (!wifi) "Wi-Fi is Off" else null
    }

    fun getMissingConnectivityReason(context: Context): String? {
        return getMissingConnectivityReason(isWifiEnabled(context))
    }

    fun getWifiNetworkName(context: Context): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifiManager?.connectionInfo
            val ssid = info?.ssid?.replace("\"", "")
            if (!ssid.isNullOrBlank() && ssid != "<unknown ssid>") {
                return ssid
            }
        } catch (_: Exception) {}
        return "Wi-Fi"
    }

    fun openWifiPanelOrSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                activity.startActivity(Intent(Settings.Panel.ACTION_WIFI))
                return
            } catch (e: Exception) {
                LogManager.e("Could not launch Settings.Panel.ACTION_WIFI", e)
            }
        }
        try {
            activity.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        } catch (e: Exception) {
            LogManager.e("Could not launch Settings.ACTION_WIFI_SETTINGS", e)
        }
    }
}
