package com.bridge.android.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.bridge.android.network.BridgeClient
import com.bridge.android.util.ImageUtils
import com.bridge.android.util.LogManager
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.LinkedHashMap
import java.util.regex.Pattern

class ClipboardSyncManager(private val context: Context) {
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    @Volatile
    private var lastRemoteReceivedHash: String? = null
    @Volatile
    private var lastLocalSentHash: String? = null
    @Volatile
    private var lastLocalSentTime: Long = 0L

    fun handleRemoteText(text: String, hash: String) {
        val lowerHash = hash.lowercase()
        lastRemoteReceivedHash = lowerHash

        try {
            val clip = ClipData.newPlainText("Bridge Text", text)
            clipboardManager.setPrimaryClip(clip)
            LogManager.i("Applied remote text from Mac to clipboard (${text.length} chars)")
        } catch (e: Exception) {
            LogManager.e("Error setting clipboard text", e)
        }
    }

    fun handleRemoteImage(data: ByteArray, hash: String) {
        val lowerHash = hash.lowercase()
        lastRemoteReceivedHash = lowerHash

        try {
            ImageUtils.cleanOldCacheFiles(context)

            val dir = File(context.cacheDir, "bridge_images")
            if (!dir.exists()) dir.mkdirs()

            val ext = if (data.size > 2 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) "jpg" else "png"
            val file = File(dir, "clip_${hash.take(12)}.$ext")
            FileOutputStream(file).use { it.write(data) }

            val contentUri = FileProvider.getUriForFile(
                context,
                "com.bridge.android.fileprovider",
                file
            )

            val clip = ClipData.newUri(context.contentResolver, "Bridge Image", contentUri)
            clipboardManager.setPrimaryClip(clip)
            LogManager.i("Applied remote image from Mac to clipboard (${data.size} bytes) as $contentUri")
        } catch (e: Exception) {
            LogManager.e("Error saving and setting clipboard image", e)
        }
    }

    fun sendLocalClipboardToMac(
        client: BridgeClient,
        clipManager: ClipboardManager = this.clipboardManager,
        force: Boolean = false,
        activeContext: Context = this.context
    ): Boolean {
        try {
            val clip = clipManager.primaryClip ?: run {
                LogManager.d("Local clipboard primaryClip is null")
                return false
            }

            if (clip.itemCount == 0) return false

            val description = clip.description
            val mimeTypes = if (description != null) {
                (0 until description.mimeTypeCount).map { description.getMimeType(it) }
            } else {
                emptyList()
            }

            // Image ONLY if the clip mime type is image/* AND the URI scheme is content or file.
            val hasImageMime = description != null && description.hasMimeType("image/*")
            var imageUri: Uri? = null
            if (hasImageMime) {
                for (i in 0 until clip.itemCount) {
                    val item = clip.getItemAt(i) ?: continue
                    val uri = item.uri ?: item.intent?.data
                    val scheme = uri?.scheme?.lowercase()
                    if (uri != null && (scheme == "content" || scheme == "file")) {
                        imageUri = uri
                        break
                    }
                }
            }

            var attemptedImage = false
            if (imageUri != null) {
                attemptedImage = true
                val processed = try {
                    ImageUtils.processImageUri(activeContext, imageUri)
                } catch (e: Throwable) {
                    LogManager.w("Failed to process image URI: ${e.message}")
                    null
                }

                if (processed != null) {
                    val lowerHash = processed.hash.lowercase()
                    val now = System.currentTimeMillis()
                    if (!force && lowerHash == lastRemoteReceivedHash) {
                        LogManager.d("Ignoring echo of remote image clip from Mac")
                        return true
                    }
                    if (!force && lowerHash == lastLocalSentHash && (now - lastLocalSentTime < 1500)) {
                        LogManager.d("Local image already dispatched within 1.5s")
                        return true
                    }
                    lastLocalSentHash = lowerHash
                    lastLocalSentTime = now
                    lastRemoteReceivedHash = null

                    LogManager.i("Clip: mimeTypes=$mimeTypes, branch=image, textLength=0")
                    client.sendImage(processed.data, processed.mime, processed.hash, processed.totalChunks)
                    return true
                }
                // If the image branch fails for any reason, fall back to coerceToText instead of dropping the clip.
                LogManager.w("Image branch failed, falling back to coerceToText")
            }

            // Everything else: send item.coerceToText(context).toString() as plain text.
            val textBuilder = StringBuilder()
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i) ?: continue
                var itemText = try {
                    item.coerceToText(activeContext)?.toString()
                } catch (e: Throwable) {
                    LogManager.d("coerceToText failed for item $i: ${e.message}")
                    null
                }
                if (itemText.isNullOrBlank() && !item.htmlText.isNullOrBlank()) {
                    try {
                        itemText = android.text.Html.fromHtml(item.htmlText, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                    } catch (_: Throwable) {
                        itemText = item.htmlText
                    }
                }
                if (!itemText.isNullOrEmpty()) {
                    if (textBuilder.isNotEmpty()) {
                        textBuilder.append("\n")
                    }
                    textBuilder.append(itemText)
                }
            }

            val text = textBuilder.toString()
            if (text.isBlank()) {
                LogManager.i("Clip: mimeTypes=$mimeTypes, branch=text, textLength=0 (dropped blank)")
                return false
            }

            val hash = ImageUtils.sha256Hex(text.toByteArray(Charsets.UTF_8))
            val lowerHash = hash.lowercase()
            val now = System.currentTimeMillis()

            if (!force && lowerHash == lastRemoteReceivedHash) {
                LogManager.d("Ignoring echo of remote text from Mac")
                return true
            }
            if (!force && lowerHash == lastLocalSentHash && (now - lastLocalSentTime < 1500)) {
                LogManager.d("Local text already dispatched within 1.5s")
                return true
            }

            lastLocalSentHash = lowerHash
            lastLocalSentTime = now
            lastRemoteReceivedHash = null

            val branch = if (attemptedImage) "image_fallback_to_text" else "text"
            LogManager.i("Clip: mimeTypes=$mimeTypes, branch=$branch, textLength=${text.length}")
            client.sendTextMessage(text, hash)
            return true
        } catch (e: Exception) {
            LogManager.e("Error reading local clipboard", e)
        }
        return false
    }

    fun sendLocalTextToMac(client: BridgeClient, text: String, force: Boolean = false): Boolean {
        if (text.isEmpty()) return false
        try {
            val hash = ImageUtils.sha256Hex(text.toByteArray(Charsets.UTF_8))
            val lowerHash = hash.lowercase()
            val now = System.currentTimeMillis()

            if (!force && lowerHash == lastRemoteReceivedHash) return true
            if (!force && lowerHash == lastLocalSentHash && (now - lastLocalSentTime < 1500)) return true

            lastLocalSentHash = lowerHash
            lastLocalSentTime = now
            lastRemoteReceivedHash = null

            LogManager.i("Sending local text to Mac (${text.length} chars)")
            client.sendTextMessage(text, hash)
            return true
        } catch (e: Exception) {
            LogManager.e("Error sending local text to Mac", e)
            return false
        }
    }

    fun sendSharedUriToMac(client: BridgeClient, uri: Uri) {
        val mimeType = context.contentResolver.getType(uri) ?: ""
        if (mimeType.startsWith("image/") || ImageUtils.isImageUri(context, uri)) {
            val processed = ImageUtils.processImageUri(context, uri) ?: return
            lastLocalSentHash = processed.hash.lowercase()
            lastLocalSentTime = System.currentTimeMillis()
            lastRemoteReceivedHash = null
            LogManager.i("Sharing image to Mac (${processed.data.size} bytes)")
            client.sendImage(processed.data, processed.mime, processed.hash, processed.totalChunks)
        } else {
            // Read as text
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: return
            val hash = ImageUtils.sha256Hex(text.toByteArray(Charsets.UTF_8))
            lastLocalSentHash = hash.lowercase()
            lastLocalSentTime = System.currentTimeMillis()
            lastRemoteReceivedHash = null
            LogManager.i("Sharing text file content to Mac (${text.length} chars)")
            client.sendTextMessage(text, hash)
        }
    }
}
