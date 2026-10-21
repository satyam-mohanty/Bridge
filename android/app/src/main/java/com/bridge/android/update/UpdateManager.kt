package com.bridge.android.update

import android.content.Context
import android.os.Build
import com.bridge.android.update.data.ApkDownloadResult
import com.bridge.android.update.data.ManifestFetchResult
import com.bridge.android.update.data.UpdateDownloader
import com.bridge.android.update.data.UpdatePreferences
import com.bridge.android.update.installer.InstallationResult
import com.bridge.android.update.installer.UpdateInstaller
import com.bridge.android.update.model.UpdateManifest
import com.bridge.android.update.model.UpdateState
import com.bridge.android.update.notification.UpdateNotificationHelper
import com.bridge.android.update.verification.ApkVerificationResult
import com.bridge.android.update.verification.ApkVerifier
import com.bridge.android.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UpdateManager private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val preferences = UpdatePreferences(appContext)
    private val downloader = UpdateDownloader()
    private val apkVerifier = ApkVerifier(appContext)

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    init {
        // Restore cached manifest if available and not dismissed
        val cached = preferences.getCachedManifest()
        val currentCode = getCurrentVersionCode()
        if (cached != null && cached.versionCode > currentCode && cached.versionCode > preferences.dismissedVersionCode) {
            _updateState.value = UpdateState.UpdateAvailable(cached)
        }
    }

    fun getCurrentVersionCode(): Long {
        return try {
            val pm = appContext.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(appContext.packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(appContext.packageName, 0).versionCode.toLong()
            }
        } catch (_: Exception) {
            1L
        }
    }

    fun getCurrentVersionName(): String {
        return try {
            val pm = appContext.packageManager
            pm.getPackageInfo(appContext.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }

    /**
     * Checks for updates against the static HTTPS manifest.
     * Can be invoked on app launch, from WorkManager, or manually from Settings.
     */
    suspend fun checkForUpdate(isManual: Boolean = false): ManifestFetchResult = withContext(Dispatchers.IO) {
        _updateState.value = UpdateState.Checking
        preferences.lastCheckedTimestamp = System.currentTimeMillis()

        val manifestUrl = preferences.getManifestUrl()
        val result = downloader.fetchManifest(manifestUrl)

        when (result) {
            is ManifestFetchResult.Success -> {
                val manifest = result.manifest
                val currentCode = getCurrentVersionCode()

                if (manifest.versionCode > currentCode) {
                    preferences.saveCachedManifest(manifest)
                    _updateState.value = UpdateState.UpdateAvailable(manifest)

                    // Post system notification if enabled and not currently dismissed
                    if (preferences.isNotificationEnabled && (isManual || manifest.versionCode > preferences.dismissedVersionCode)) {
                        UpdateNotificationHelper.showUpdateAvailableNotification(appContext, manifest)
                    }
                } else {
                    preferences.saveCachedManifest(null)
                    _updateState.value = UpdateState.UpToDate(getCurrentVersionName(), currentCode)
                }
            }
            is ManifestFetchResult.Error -> {
                _updateState.value = UpdateState.Error(result.message, result.isNetworkError)
            }
        }
        result
    }

    /**
     * Downloads and verifies the release APK file.
     */
    fun startDownload(manifest: UpdateManifest) {
        scope.launch {
            _updateState.value = UpdateState.Downloading(0, 0L, manifest.apkSizeBytes)

            val updatesDir = File(appContext.cacheDir, "updates")
            val targetFile = File(updatesDir, "bridge-update-v${manifest.versionCode}.apk")

            val downloadResult = downloader.downloadApk(
                manifest = manifest,
                targetFile = targetFile,
                onProgress = { downloaded, total ->
                    val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
                    _updateState.value = UpdateState.Downloading(percent, downloaded, total)
                }
            )

            when (downloadResult) {
                is ApkDownloadResult.Success -> {
                    // Pre-installation integrity, package ID, version, and signature verification
                    when (val verifyResult = apkVerifier.verify(downloadResult.apkFile, manifest)) {
                        is ApkVerificationResult.Success -> {
                            LogManager.i("APK signature and package verification passed successfully")
                            _updateState.value = UpdateState.ReadyToInstall(downloadResult.apkFile, manifest)
                        }
                        is ApkVerificationResult.Error -> {
                            LogManager.e("APK verification failed: ${verifyResult.message}")
                            downloadResult.apkFile.delete()
                            _updateState.value = UpdateState.Error("Security verification failed: ${verifyResult.message}")
                        }
                    }
                }
                is ApkDownloadResult.Error -> {
                    _updateState.value = UpdateState.Error(downloadResult.message, downloadResult.isNetworkError)
                }
            }
        }
    }

    /**
     * Triggers installation of the verified APK.
     */
    fun installUpdate(context: Context, apkFile: File): InstallationResult {
        return UpdateInstaller.installApk(context, apkFile)
    }

    /**
     * Dismisses the update banner for this version until a newer version is released.
     */
    fun dismissUpdate(versionCode: Long) {
        preferences.dismissedVersionCode = versionCode
        _updateState.value = UpdateState.Idle
        UpdateNotificationHelper.cancelUpdateNotification(appContext)
    }

    fun resetState() {
        _updateState.value = UpdateState.Idle
    }

    companion object {
        @Volatile
        private var instance: UpdateManager? = null

        fun getInstance(context: Context): UpdateManager {
            return instance ?: synchronized(this) {
                instance ?: UpdateManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
