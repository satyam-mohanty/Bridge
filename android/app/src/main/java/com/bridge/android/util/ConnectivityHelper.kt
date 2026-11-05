package com.bridge.android.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.net.NetworkInterface
import java.net.InetAddress

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

    /**
     * Checks if Wi-Fi client interface is turned ON.
     */
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

    /**
     * Checks if any local IP network is available:
     * - Wi-Fi client network
     * - Wi-Fi Mobile Hotspot / Tethering interface (wlan1, ap0, swlan0, rndis0, etc.)
     * - Ethernet / USB tethering
     */
    fun isLocalNetworkAvailable(context: Context): Boolean {
        if (isWifiEnabled(context)) return true

        // Check if there is an active local IPv4 interface (e.g. mobile hotspot or tethering)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                // Typical hotspot, ap, rndis, or local wlan interfaces
                if (name.startsWith("ap") || name.startsWith("wlan") || name.startsWith("swlan") ||
                    name.startsWith("rndis") || name.startsWith("eth") || name.startsWith("tether")) {
                    for (addr in intf.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is InetAddress && addr.hostAddress?.contains(":") == false) {
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Fallback: ConnectivityManager
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNet = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(activeNet)
            if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                 caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    fun getMissingConnectivityReason(context: Context): String? {
        return if (!isLocalNetworkAvailable(context)) "No Wi-Fi or Hotspot" else null
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

        // Check if mobile hotspot is active
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (intf in interfaces) {
                    if (intf.isLoopback || !intf.isUp) continue
                    val name = intf.name.lowercase()
                    if (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("rndis") || name.startsWith("tether")) {
                        return "Personal Hotspot"
                    }
                }
            }
        } catch (_: Exception) {}

        return "Local Network"
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
