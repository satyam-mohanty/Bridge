package com.bridge.android.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.bridge.android.util.LogManager

@RequiresApi(Build.VERSION_CODES.N)
class BridgeTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val service = BridgeService.instance
        if (service != null && service.isConnectedFlow.value) {
            val intent = Intent(this, com.bridge.android.ui.TransparentClipReaderActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                putExtra("force", true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
            LogManager.i("Quick Settings tile: Triggered clipboard capture")
        } else {
            Toast.makeText(this, "Bridge: Mac is not connected", Toast.LENGTH_SHORT).show()
            LogManager.i("Quick Settings tile tapped but Mac is not connected")
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val service = BridgeService.instance
        if (service != null && service.isConnectedFlow.value) {
            tile.state = Tile.STATE_ACTIVE
            tile.subtitle = "Connected"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.subtitle = "Disconnected"
        }
        tile.updateTile()
    }
}
