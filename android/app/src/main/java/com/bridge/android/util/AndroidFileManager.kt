package com.bridge.android.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.bridge.android.R
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

object AndroidFileManager {
    private const val FILE_CHANNEL_ID = "bridge_files_channel"
    private val notificationIdCounter = AtomicInteger(50000)

    fun saveFileToDownloads(
        context: Context,
        fileName: String,
        data: ByteArray,
        mimeType: String
    ): Uri? {
        val sanitizedName = sanitizeFilename(fileName)
        val finalMime = if (mimeType.isBlank() || mimeType == "*/*") "application/octet-stream" else mimeType

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, sanitizedName, data, finalMime)
            } else {
                saveLegacy(context, sanitizedName, data, finalMime)
            }
        } catch (e: Exception) {
            LogManager.e("Failed to save received file $fileName to Downloads", e)
            null
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        fileName: String,
        data: ByteArray,
        mimeType: String
    ): Uri? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues) ?: return null

        resolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(data)
            outputStream.flush()
        }

        contentValues.clear()
        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)

        LogManager.i("Saved file via MediaStore to Downloads: $fileName ($uri)")
        return uri
    }

    private fun saveLegacy(
        context: Context,
        fileName: String,
        data: ByteArray,
        mimeType: String
    ): Uri? {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }

        val targetFile = resolveNameCollision(downloadsDir, fileName)
        FileOutputStream(targetFile).use { fos ->
            fos.write(data)
            fos.flush()
        }

        MediaScannerConnection.scanFile(
            context,
            arrayOf(targetFile.absolutePath),
            arrayOf(mimeType),
            null
        )

        LogManager.i("Saved legacy file to Downloads: ${targetFile.absolutePath}")
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", targetFile)
        } catch (_: Exception) {
            Uri.fromFile(targetFile)
        }
    }

    private fun resolveNameCollision(dir: File, fileName: String): File {
        var file = File(dir, fileName)
        if (!file.exists()) return file

        val dotIndex = fileName.lastIndexOf('.')
        val base = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val ext = if (dotIndex > 0) fileName.substring(dotIndex) else ""

        var counter = 1
        while (file.exists()) {
            file = File(dir, "$base ($counter)$ext")
            counter++
        }
        return file
    }

    private fun sanitizeFilename(name: String): String {
        val clean = File(name).name.replace("[/\\\\?%*:|\"<>]".toRegex(), "_").trim()
        return if (clean.isEmpty()) "downloaded_file" else clean
    }

    fun showFileReceivedNotification(
        context: Context,
        fileName: String,
        fileSize: Int,
        fileUri: Uri?,
        mimeType: String
    ) {
        ensureNotificationChannel(context)

        val notificationId = notificationIdCounter.incrementAndGet()
        val builder = NotificationCompat.Builder(context, FILE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bridge_notification)
            .setContentTitle("Received from Mac")
            .setContentText("$fileName (${formatFileSize(fileSize)})")
            .setSubText("Saved to Downloads")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (fileUri != null) {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(fileUri, if (mimeType.isNotBlank()) mimeType else "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                notificationId,
                viewIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.setContentIntent(pendingIntent)
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(notificationId, builder.build())
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                FILE_CHANNEL_ID,
                "Bridge File Transfers",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for files received from Mac"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun formatFileSize(bytes: Int): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB".format(mb)
    }
}
