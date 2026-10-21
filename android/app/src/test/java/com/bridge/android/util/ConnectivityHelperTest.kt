package com.bridge.android.util

import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectivityHelperTest {

    @Test
    fun testConnectivityReasonLogic() {
        assertEquals("Wi-Fi is Off", ConnectivityHelper.getMissingConnectivityReason(wifi = false))
        assertNull(ConnectivityHelper.getMissingConnectivityReason(wifi = true))
    }

    @Test
    fun testStateUpdateCaching() {
        ConnectivityHelper.updateWifiState(WifiManager.WIFI_STATE_ENABLED)
        ConnectivityHelper.updateWifiState(WifiManager.WIFI_STATE_DISABLED)
    }
}
