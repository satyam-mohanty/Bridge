package com.bridge.android.update.installer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.bridge.android.update.data.UpdateConfig
import com.bridge.android.util.LogManager
import java.io.File

sealed class InstallationResult {
    data class Success(val intent: Intent) : InstallationResult()
    data class RequiresPermission(val permissionIntent: Intent) : InstallationResult()
    data class Error(val message: String) : InstallationResult()
}

object UpdateInstaller {

    /**
     * Checks whether the app has permission to request package installations.
     * On Android 8.0+ (API 26+), this requires REQUEST_INSTALL_PACKAGES permission
     * and user toggle in system Settings.
     */
    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Intent to open system Settings where user can grant unknown-source installation permission.
     */
    fun getUnknownSourcesPermissionIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Prepares and starts the system installation intent using Android's FileProvider.
     * Prompts the user with Android's native package installer dialog.
     */
    fun installApk(context: Context, apkFile: File): InstallationResult {
        if (!apkFile.exists() || apkFile.length() <= 0) {
            return InstallationResult.Error("APK file not found on disk")
        }

        if (!canInstallPackages(context)) {
            LogManager.w("App does not have unknown sources install permission")
            return InstallationResult.RequiresPermission(getUnknownSourcesPermissionIntent(context))
        }

        return try {
            val contentUri = FileProvider.getUriForFile(
                context,
                UpdateConfig.FILE_PROVIDER_AUTHORITY,
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
            InstallationResult.Success(installIntent)
        } catch (e: Exception) {
            LogManager.e("Failed to trigger package installer", e)
            InstallationResult.Error("Failed to start installation: ${e.localizedMessage ?: "Unknown error"}")
        }
    }
}
