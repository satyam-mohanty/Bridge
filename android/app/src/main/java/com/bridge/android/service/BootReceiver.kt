package com.bridge.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bridge.android.crypto.KeyStoreManager
import com.bridge.android.util.LogManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val keyStoreManager = KeyStoreManager(context)
            if (keyStoreManager.hasKey()) {
                LogManager.i("Device boot/upgrade detected: Starting BridgeService")
                BridgeService.start(context)
            } else {
                LogManager.i("Device boot detected: Device not paired yet, skipping auto-start")
            }
        }
    }
}
