package com.bridge.android.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object ImageUtils {
    const val MAX_DIMENSION = 4096
    const val MAX_DIRECT_BYTES = 10 * 1024 * 1024 // 10 MB
    const val CHUNK_SIZE_BYTES = 64 * 1024 // 64 KB

    data class ProcessedImage(
        val data: ByteArray,
        val mime: String,
        val hash: String,
        val totalChunks: Int
    )

    fun readBytesFromUri(context: Context, uri: Uri): ByteArray? {
        // 1. Direct file access if file:// or relative/absolute path
        if (uri.scheme == "file" || uri.scheme == null) {
            val path = uri.path ?: uri.schemeSpecificPart
            if (path != null) {
                try {
                    val f = File(path)
                    if (f.exists() && f.canRead()) {
                        return f.readBytes()
                    }
                } catch (_: Throwable) {}
            }
        }

        // 2. Open input stream via ContentResolver
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                return stream.readBytes()
            }
        } catch (e: Throwable) {
            LogManager.d("openInputStream failed for $uri: ${e.message}")
        }

        // 3. Fallback via openFileDescriptor
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { stream ->
                    return stream.readBytes()
                }
            }
        } catch (e: Throwable) {
            LogManager.d("openFileDescriptor failed for $uri: ${e.message}")
        }

        // 4. Fallback: try raw path if uri.path points to filesystem
        try {
            val rawPath = uri.path
            if (rawPath != null && rawPath.startsWith("/")) {
                val f = File(rawPath)
                if (f.exists() && f.canRead()) {
                    return f.readBytes()
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    fun detectImageMime(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        // PNG magic: 89 50 4E 47
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
            return "image/png"
        }
        // JPEG magic: FF D8 FF
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return "image/jpeg"
        }
        // WebP magic: RIFF....WEBP
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }
        // GIF magic: GIF8
        if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()) {
            return "image/gif"
        }
        // BMP magic: BM
        if (bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
            return "image/bmp"
        }
        return null
    }

    fun isImageUri(context: Context, uri: Uri): Boolean {
        val uriStr = uri.toString().lowercase()
        if (uriStr.endsWith(".png") || uriStr.endsWith(".jpg") || uriStr.endsWith(".jpeg") ||
            uriStr.endsWith(".webp") || uriStr.endsWith(".gif") || uriStr.endsWith(".bmp") ||
            uriStr.endsWith(".heic")
        ) {
            return true
        }

        try {
            val type = context.contentResolver.getType(uri)
            if (type != null && type.startsWith("image/")) return true
        } catch (_: Throwable) {}

        return try {
            val bytes = readBytesFromUri(context, uri) ?: return false
            detectImageMime(bytes) != null
        } catch (_: Throwable) {
            false
        }
    }

    fun processImageUri(context: Context, uri: Uri): ProcessedImage? {
        val rawBytes = readBytesFromUri(context, uri) ?: run {
            LogManager.w("Could not read bytes from image URI: $uri")
            return null
        }
        val typeFromResolver = try { context.contentResolver.getType(uri) } catch (_: Throwable) { null }
        return processRawImageBytes(rawBytes, typeFromResolver)
    }

    fun processRawImageBytes(rawBytes: ByteArray, fallbackMime: String? = null): ProcessedImage? {
        if (rawBytes.isEmpty()) return null

        val detectedMime = detectImageMime(rawBytes) ?: fallbackMime?.takeIf { it.startsWith("image/") } ?: "image/png"

        // If the raw image is already standard PNG or JPEG and within direct size limits,
        // send bytes directly without lossy re-encoding or high CPU overhead!
        if ((detectedMime == "image/png" || detectedMime == "image/jpeg") && rawBytes.size <= MAX_DIRECT_BYTES) {
            val hash = sha256Hex(rawBytes)
            val totalChunks = kotlin.math.ceil(rawBytes.size.toDouble() / CHUNK_SIZE_BYTES).toInt()
            return ProcessedImage(rawBytes, detectedMime, hash, totalChunks)
        }

        // Otherwise (WebP, GIF, BMP, or oversized file), decode and downscale/convert
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOptions)

            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) {
                // If decode failed but we have valid raw bytes, send raw bytes
                val hash = sha256Hex(rawBytes)
                val totalChunks = kotlin.math.ceil(rawBytes.size.toDouble() / CHUNK_SIZE_BYTES).toInt()
                return ProcessedImage(rawBytes, detectedMime, hash, totalChunks)
            }

            var sampleSize = 1
            var w = origWidth
            var h = origHeight
            while (w > MAX_DIMENSION || h > MAX_DIMENSION) {
                sampleSize *= 2
                w /= 2
                h /= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val rawBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions) ?: return null

            val finalBitmap = if (rawBitmap.width > MAX_DIMENSION || rawBitmap.height > MAX_DIMENSION) {
                val scale = minOf(MAX_DIMENSION.toFloat() / rawBitmap.width, MAX_DIMENSION.toFloat() / rawBitmap.height)
                val targetW = (rawBitmap.width * scale).toInt()
                val targetH = (rawBitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(rawBitmap, targetW, targetH, true).also {
                    if (it != rawBitmap) rawBitmap.recycle()
                }
            } else {
                rawBitmap
            }

            var mime = "image/png"
            var baos = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            var bytes = baos.toByteArray()

            if (bytes.size > MAX_DIRECT_BYTES) {
                baos = ByteArrayOutputStream()
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                bytes = baos.toByteArray()
                mime = "image/jpeg"
            }
            finalBitmap.recycle()

            val hash = sha256Hex(bytes)
            val totalChunks = kotlin.math.ceil(bytes.size.toDouble() / CHUNK_SIZE_BYTES).toInt()
            ProcessedImage(bytes, mime, hash, totalChunks)
        } catch (e: Exception) {
            LogManager.e("Error processing raw image bytes", e)
            null
        }
    }

    fun chunkBytes(bytes: ByteArray): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + CHUNK_SIZE_BYTES, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            chunks.add(chunk)
            offset = end
        }
        return chunks
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun cleanOldCacheFiles(context: Context) {
        try {
            val cacheDir = File(context.cacheDir, "bridge_images")
            if (!cacheDir.exists()) return

            val now = System.currentTimeMillis()
            val cutoff = now - 24 * 60 * 60 * 1000L // 24 hours
            cacheDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) {
                    val deleted = file.delete()
                    if (deleted) LogManager.d("Deleted expired cache image: ${file.name}")
                }
            }
        } catch (e: Exception) {
            LogManager.e("Error cleaning cache files", e)
        }
    }
}
