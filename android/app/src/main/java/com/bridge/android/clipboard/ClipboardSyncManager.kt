package com.bridge.android.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.bridge.android.network.BridgeClient
import com.bridge.android.util.ImageUtils
import com.bridge.android.util.LogManager
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.LinkedHashMap

class ClipboardSyncManager(private val context: Context) {
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val seenHashes = Collections.synchronizedSet(
        Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>(50, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > 50
            }
        })
    )

    fun handleRemoteText(text: String, hash: String) {
        val lowerHash = hash.lowercase()
        if (seenHashes.contains(lowerHash)) {
            LogManager.d("Ignoring duplicate/echo text clip ($hash)")
            return
        }
        seenHashes.add(lowerHash)

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
        if (seenHashes.contains(lowerHash)) {
            LogManager.d("Ignoring duplicate/echo image clip ($hash)")
            return
        }
        seenHashes.add(lowerHash)

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
        force: Boolean = false
    ): Boolean {
        try {
            val clip = clipManager.primaryClip ?: run {
                LogManager.i("Local clipboard is empty")
                return false
            }

            if (clip.itemCount == 0) return false
            val description = clip.description
            val isExplicitImageClip = description != null && description.hasMimeType("image/*")

            // 1. First scan all items for Image content
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i) ?: continue
                val uri = item.uri
                if (uri != null) {
                    val mimeType = try {
                        context.contentResolver.getType(uri) ?: ""
                    } catch (_: Throwable) {
                        ""
                    }
                    val uriStr = uri.toString().lowercase()
                    val hasImageExtension = uriStr.endsWith(".png") || uriStr.endsWith(".jpg") ||
                            uriStr.endsWith(".jpeg") || uriStr.endsWith(".webp") ||
                            uriStr.endsWith(".gif") || uriStr.endsWith(".bmp") ||
                            uriStr.endsWith(".heic")
                    val isImageMime = mimeType.startsWith("image/") || isExplicitImageClip

                    if (isImageMime || hasImageExtension || ImageUtils.isImageUri(context, uri)) {
                        val processed = ImageUtils.processImageUri(context, uri)
                        if (processed != null) {
                            val lowerHash = processed.hash.lowercase()
                            if (!force && seenHashes.contains(lowerHash)) {
                                LogManager.d("Local image hash already synced")
                                return true
                            }
                            seenHashes.add(lowerHash)

                            LogManager.i("Sending local image to Mac (${processed.data.size} bytes, ${processed.totalChunks} chunks)")
                            client.sendImage(processed.data, processed.mime, processed.hash, processed.totalChunks)
                            return true
                        }
                    }
                }
            }

            // 2. Scan items for Web Links or Text
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i) ?: continue

                // Check for URI that represents a web link
                val uri = item.uri
                if (uri != null) {
                    val scheme = uri.scheme?.lowercase()
                    if (scheme == "http" || scheme == "https") {
                        val linkText = uri.toString()
                        if (sendExtractedText(client, linkText, force, "web link URI")) {
                            return true
                        }
                    }
                }

                // Check text
                var text = item.text?.toString()
                if (text.isNullOrEmpty()) {
                    text = try {
                        item.coerceToText(context)?.toString()
                    } catch (_: Throwable) {
                        null
                    }
                }

                // Check HTML href if available and text is empty
                if (text.isNullOrEmpty() && !item.htmlText.isNullOrEmpty()) {
                    val html = item.htmlText!!
                    val hrefMatcher = java.util.regex.Pattern.compile("""href=["'](https?://[^"']+)["']""", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html)
                    if (hrefMatcher.find()) {
                        text = hrefMatcher.group(1)
                    } else {
                        text = android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
                    }
                }

                // Check URI string fallback
                if (text.isNullOrEmpty() && uri != null) {
                    text = uri.toString()
                }

                if (!text.isNullOrEmpty()) {
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) {
                        if (sendExtractedText(client, trimmed, force, "text/link")) {
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.e("Error reading local clipboard", e)
        }
        return false
    }

    private fun sendExtractedText(client: BridgeClient, text: String, force: Boolean, source: String): Boolean {
        val hash = ImageUtils.sha256Hex(text.toByteArray(Charsets.UTF_8))
        val lowerHash = hash.lowercase()
        if (!force && seenHashes.contains(lowerHash)) {
            LogManager.d("Local $source hash already synced")
            return true
        }
        seenHashes.add(lowerHash)
        LogManager.i("Sending local $source to Mac (${text.length} chars)")
        client.sendTextMessage(text, hash)
        return true
    }

    fun sendLocalTextToMac(client: BridgeClient, text: String, force: Boolean = false): Boolean {
        if (text.isEmpty()) return false
        try {
            val hash = ImageUtils.sha256Hex(text.toByteArray(Charsets.UTF_8))
            val lowerHash = hash.lowercase()
            if (!force && seenHashes.contains(lowerHash)) {
                LogManager.d("Local text hash already synced")
                return true
            }
            seenHashes.add(lowerHash)

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
        if (mimeType.startsWith("image/")) {
            val processed = ImageUtils.processImageUri(context, uri) ?: return
            seenHashes.add(processed.hash.lowercase())
            LogManager.i("Sharing image to Mac (${processed.data.size} bytes)")
            client.sendImage(processed.data, processed.mime, processed.hash, processed.totalChunks)
        } else {
            // Read as text
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: return
            val hash = ImageUtils.sha256Hex(text.toByteArray(Charsets.UTF_8))
            seenHashes.add(hash.lowercase())
            LogManager.i("Sharing text file content to Mac (${text.length} chars)")
            client.sendTextMessage(text, hash)
        }
    }
}
