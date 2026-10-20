package com.bridge.android.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import com.bridge.android.service.BridgeService
import com.bridge.android.util.ImageUtils
import com.bridge.android.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the Activity alive while the streams are read so Android does not revoke URI permissions!
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) {
            finish()
            return
        }

        val service = BridgeService.instance
        if (service == null || !service.isConnectedFlow.value) {
            Toast.makeText(this, "Bridge: Mac is not connected", Toast.LENGTH_SHORT).show()
            LogManager.i("Share target opened, but Mac is not connected")
            finish()
            return
        }

        val action = intent.action
        val type = intent.type ?: ""

        when (action) {
            Intent.ACTION_SEND -> {
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }

                if (streamUri != null) {
                    sendUrisAsync(service, listOf(streamUri), type)
                } else if (type.startsWith("text/")) {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!sharedText.isNullOrEmpty()) {
                        val hash = ImageUtils.sha256Hex(sharedText.toByteArray(Charsets.UTF_8))
                        service.getClient().sendTextMessage(sharedText, hash)
                        Toast.makeText(this, "Text sent to Mac", Toast.LENGTH_SHORT).show()
                        LogManager.i("Shared text sent to Mac (${sharedText.length} chars)")
                    }
                    finish()
                } else {
                    finish()
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) {
                    sendUrisAsync(service, uris, type)
                } else {
                    finish()
                }
            }
            else -> {
                finish()
            }
        }
    }

    private fun sendUrisAsync(service: BridgeService, uris: List<Uri>, fallbackMime: String) {
        val appContext = applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (uri in uris) {
                    var fileName = "shared_file"
                    val mime = contentResolver.getType(uri) ?: fallbackMime.ifEmpty { "application/octet-stream" }

                    try {
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1 && cursor.moveToFirst()) {
                                val name = cursor.getString(nameIndex)
                                if (!name.isNullOrBlank()) {
                                    fileName = name
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    if (fileName == "shared_file" && uri.lastPathSegment != null) {
                        fileName = File(uri.lastPathSegment ?: "").name
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "Sending $fileName to Mac…", Toast.LENGTH_SHORT).show()
                    }

                    // Read bytes while the Activity is alive and holds the URI permission grant!
                    val bytes: ByteArray? = try {
                        contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (e: SecurityException) {
                        // Fallback to openFileDescriptor if direct stream is denied by provider
                        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                        }
                    }

                    if (bytes == null || bytes.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appContext, "Unable to read $fileName", Toast.LENGTH_SHORT).show()
                        }
                        continue
                    }

                    service.sendFile(fileName, bytes, mime)

                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "Sent $fileName to Mac ✓", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                LogManager.e("Failed to share file to Mac", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Failed to send file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    finish()
                }
            }
        }
    }
}
