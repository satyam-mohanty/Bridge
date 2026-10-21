package com.bridge.android.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

object BatterySyncHelper {
    data class BatteryInfo(
        val level: Int,
        val isCharging: Boolean
    )

    fun getCurrentBattery(context: Context): BatteryInfo? {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent: Intent? = context.registerReceiver(null, filter)
            if (intent != null) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else level
                if (pct >= 0) {
                    BatteryInfo(level = pct.coerceIn(0, 100), isCharging = isCharging)
                } else null
            } else null
        } catch (e: Exception) {
            LogManager.e("Failed to get battery info", e)
            null
        }
    }
}
