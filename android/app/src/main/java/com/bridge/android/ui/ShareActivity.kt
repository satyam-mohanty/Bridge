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
                val stagingDir = File(appContext.cacheDir, "share_staging").apply { mkdirs() }

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

                    // Create a safe unique temporary file name in staging directory
                    val safePrefix = fileName.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }.take(32)
                    val tempFile = File(stagingDir, "stage_${System.currentTimeMillis()}_$safePrefix")

                    // Stream to temp staging file with 64KB chunks to protect Dalvik RAM from OOM
                    var copySuccess = false
                    try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output, bufferSize = 64 * 1024)
                            }
                        }
                        copySuccess = tempFile.exists() && tempFile.length() > 0
                    } catch (e: SecurityException) {
                        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            FileInputStream(pfd.fileDescriptor).use { input ->
                                tempFile.outputStream().use { output ->
                                    input.copyTo(output, bufferSize = 64 * 1024)
                                }
                            }
                        }
                        copySuccess = tempFile.exists() && tempFile.length() > 0
                    }

                    if (!copySuccess) {
                        try { tempFile.delete() } catch (_: Exception) {}
                        withContext(Dispatchers.Main) {
                            Toast.makeText(appContext, "Unable to read $fileName", Toast.LENGTH_SHORT).show()
                        }
                        continue
                    }

                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "Sending $fileName to Mac…", Toast.LENGTH_SHORT).show()
                    }

                    // Hand to background service for streaming over network and auto-deletion on completion
                    service.sendFile(tempFile, fileName, mime, deleteOnComplete = true)
                }
            } catch (e: Exception) {
                LogManager.e("Failed to stage share file to Mac", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Failed to share: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                // Promptly dismiss ShareActivity so user touches are NEVER blocked
                withContext(Dispatchers.Main) {
                    finish()
                }
            }
        }
    }
}
