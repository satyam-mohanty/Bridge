package com.bridge.android.update.model

import java.io.File

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(val manifest: UpdateManifest) : UpdateState()
    data class UpToDate(val currentVersionName: String, val currentVersionCode: Long) : UpdateState()
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : UpdateState()
    data class ReadyToInstall(val apkFile: File, val manifest: UpdateManifest) : UpdateState()
    data class Error(val message: String, val isNetworkError: Boolean = false) : UpdateState()
}
