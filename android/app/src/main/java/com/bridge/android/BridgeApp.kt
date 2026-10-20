package com.bridge.android

import android.app.Application
import com.bridge.android.crypto.KeyStoreManager
import com.bridge.android.service.BridgeService
import com.bridge.android.util.LogManager

class BridgeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        LogManager.i("Bridge App starting up")

        val keyStoreManager = KeyStoreManager(this)
        if (keyStoreManager.hasKey()) {
            BridgeService.start(this)
        }
    }
}
