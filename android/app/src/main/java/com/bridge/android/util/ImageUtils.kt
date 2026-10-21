package com.bridge.android.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

object ImageUtils {
    const val MAX_DIMENSION = 4096
    const val MAX_PNG_BYTES = 10 * 1024 * 1024 // 10 MB
    const val CHUNK_SIZE_BYTES = 64 * 1024 // 64 KB

    data class ProcessedImage(
        val data: ByteArray,
        val mime: String,
        val hash: String,
        val totalChunks: Int
    )

    fun isImageUri(context: Context, uri: Uri): Boolean {
        return try {
            val actualUri = if (uri.scheme == null) Uri.fromFile(File(uri.path ?: uri.toString())) else uri
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(actualUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            options.outWidth > 0 && options.outHeight > 0
        } catch (_: Throwable) {
            false
        }
    }

    fun processImageUri(context: Context, uri: Uri): ProcessedImage? {
        return try {
            val actualUri = if (uri.scheme == null) Uri.fromFile(File(uri.path ?: uri.toString())) else uri
            val contentResolver = context.contentResolver
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(actualUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            var sampleSize = 1
            var w = origWidth
            var h = origHeight
            while (w > MAX_DIMENSION || h > MAX_DIMENSION) {
                sampleSize *= 2
                w /= 2
                h /= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val rawBitmap = contentResolver.openInputStream(actualUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

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

            if (bytes.size > MAX_PNG_BYTES) {
                // If PNG exceeds 10 MB, compress as JPEG (0.85 quality)
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
            LogManager.e("Error processing image from URI", e)
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
