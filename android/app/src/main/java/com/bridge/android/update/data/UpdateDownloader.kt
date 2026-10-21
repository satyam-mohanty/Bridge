package com.bridge.android.update.data

import com.bridge.android.update.crypto.Ed25519Verifier
import com.bridge.android.update.crypto.Sha256.toHexString
import com.bridge.android.update.model.UpdateManifest
import com.bridge.android.util.LogManager
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

sealed class ManifestFetchResult {
    data class Success(val manifest: UpdateManifest) : ManifestFetchResult()
    data class Error(val message: String, val isNetworkError: Boolean = false) : ManifestFetchResult()
}

sealed class ApkDownloadResult {
    data class Success(val apkFile: File) : ApkDownloadResult()
    data class Error(val message: String, val isNetworkError: Boolean = false) : ApkDownloadResult()
}

class UpdateDownloader(
    private val verifier: Ed25519Verifier = Ed25519Verifier.fromHex(UpdateConfig.UPDATE_PUBLIC_KEY_HEX)
) {

    /**
     * Fetches the static update manifest from the advertised HTTPS URL and verifies its Ed25519 signature.
     */
    fun fetchManifest(manifestUrl: String): ManifestFetchResult {
        if (!manifestUrl.startsWith("https://") && !manifestUrl.startsWith("http://10.0.2.2") && !manifestUrl.startsWith("http://127.0.0.1")) {
            return ManifestFetchResult.Error("Manifest URL must use secure HTTPS")
        }

        var connection: HttpURLConnection? = null
        return try {
            val url = URL(manifestUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
            connection.readTimeout = UpdateConfig.READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Bridge-Android-Updater")
            connection.setRequestProperty("Accept", "application/json")

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return ManifestFetchResult.Error(
                    "Server returned HTTP $responseCode while fetching update manifest",
                    isNetworkError = true
                )
            }

            val jsonText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val manifest = try {
                UpdateManifest.fromJson(jsonText)
            } catch (e: Exception) {
                return ManifestFetchResult.Error("Failed to parse update manifest JSON: ${e.message}")
            }

            // Cryptographically verify manifest signature with embedded Ed25519 public key
            if (!manifest.verifySignature(verifier)) {
                LogManager.e("Update manifest Ed25519 signature verification failed!")
                return ManifestFetchResult.Error(
                    "Digital signature verification failed. The update manifest is invalid or untrusted."
                )
            }

            ManifestFetchResult.Success(manifest)
        } catch (e: java.io.IOException) {
            LogManager.e("Network error fetching update manifest: ${e.message}", e)
            ManifestFetchResult.Error("Network error: ${e.localizedMessage ?: "Unable to connect"}", isNetworkError = true)
        } catch (e: Exception) {
            LogManager.e("Unexpected error fetching update manifest", e)
            ManifestFetchResult.Error("Error checking for updates: ${e.localizedMessage ?: "Unknown error"}")
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Safely downloads the update APK over HTTPS, validates its SHA-256 checksum on the fly,
     * and performs atomic file renaming upon completion.
     */
    fun downloadApk(
        manifest: UpdateManifest,
        targetFile: File,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): ApkDownloadResult {
        if (!manifest.apkUrl.startsWith("https://") && !manifest.apkUrl.startsWith("http://10.0.2.2") && !manifest.apkUrl.startsWith("http://127.0.0.1")) {
            return ApkDownloadResult.Error("APK download URL must use secure HTTPS")
        }

        val parentDir = targetFile.parentFile ?: return ApkDownloadResult.Error("Invalid target file path")
        if (!parentDir.exists()) {
            parentDir.mkdirs()
        }

        val tempFile = File(parentDir, "${targetFile.name}.tmp")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        return try {
            val url = URL(manifest.apkUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = UpdateConfig.CONNECT_TIMEOUT_MS
            connection.readTimeout = UpdateConfig.READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Bridge-Android-Updater")

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return ApkDownloadResult.Error(
                    "Server returned HTTP $responseCode while downloading APK",
                    isNetworkError = true
                )
            }

            val contentLength = connection.contentLengthLong
            val expectedSize = if (contentLength > 0) contentLength else manifest.apkSizeBytes
            if (expectedSize > UpdateConfig.MAX_APK_SIZE_BYTES) {
                return ApkDownloadResult.Error("Update package exceeds maximum allowed size")
            }

            val md = MessageDigest.getInstance("SHA-256")
            inputStream = connection.inputStream
            outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(32 * 1024)
            var bytesRead: Int
            var totalDownloaded = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                md.update(buffer, 0, bytesRead)
                totalDownloaded += bytesRead

                if (totalDownloaded > UpdateConfig.MAX_APK_SIZE_BYTES) {
                    tempFile.delete()
                    return ApkDownloadResult.Error("Downloaded data exceeded safe size limits")
                }

                onProgress(totalDownloaded, expectedSize)
            }

            outputStream.flush()
            outputStream.close()
            outputStream = null

            val calculatedHash = md.digest().toHexString().lowercase()
            val expectedHash = manifest.apkSha256.trim().lowercase()

            if (calculatedHash != expectedHash) {
                tempFile.delete()
                LogManager.e("SHA-256 mismatch! Expected: $expectedHash, got: $calculatedHash")
                return ApkDownloadResult.Error(
                    "APK checksum verification failed. The downloaded file may be corrupted or modified."
                )
            }

            // Atomic file replace
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                // Fallback copy if rename fails
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            ApkDownloadResult.Success(targetFile)
        } catch (e: java.io.IOException) {
            tempFile.delete()
            LogManager.e("IO error downloading APK", e)
            ApkDownloadResult.Error("Download interrupted: ${e.localizedMessage ?: "Network error"}", isNetworkError = true)
        } catch (e: Exception) {
            tempFile.delete()
            LogManager.e("Unexpected error downloading APK", e)
            ApkDownloadResult.Error("Download error: ${e.localizedMessage ?: "Unknown error"}")
        } finally {
            try { outputStream?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            connection?.disconnect()
        }
    }
}
