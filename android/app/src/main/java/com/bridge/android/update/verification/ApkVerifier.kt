package com.bridge.android.update.verification

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.bridge.android.update.crypto.Sha256
import com.bridge.android.update.model.UpdateManifest
import com.bridge.android.util.LogManager
import java.io.File

sealed class ApkVerificationResult {
    data class Success(val packageInfo: PackageInfo) : ApkVerificationResult()
    data class Error(val message: String) : ApkVerificationResult()
}

class ApkVerifier(private val context: Context) {

    /**
     * Inspects and validates the downloaded APK file before handing it to Android PackageInstaller:
     * 1. Validates that APK file exists and is readable.
     * 2. Inspects APK manifest using PackageManager.getPackageArchiveInfo.
     * 3. Verifies package name matches context.packageName (com.bridge.android).
     * 4. Verifies version code is strictly newer than installed version and matches the manifest.
     * 5. Verifies APK signing certificate matches the currently running app's signing certificate.
     */
    fun verify(apkFile: File, manifest: UpdateManifest): ApkVerificationResult {
        if (!apkFile.exists() || apkFile.length() <= 0) {
            return ApkVerificationResult.Error("APK file does not exist or is empty")
        }

        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_ACTIVITIES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES or PackageManager.GET_ACTIVITIES
        }

        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: return ApkVerificationResult.Error("Unable to parse APK package info. The APK may be corrupted.")

        // 1. Verify Package ID
        val expectedPackageId = context.packageName
        if (archiveInfo.packageName != expectedPackageId) {
            LogManager.e("Package ID mismatch: expected $expectedPackageId, found ${archiveInfo.packageName}")
            return ApkVerificationResult.Error(
                "Package ID mismatch: APK is for '${archiveInfo.packageName}', expected '$expectedPackageId'"
            )
        }

        // 2. Verify Version Code
        val currentVersionCode = getCurrentVersionCode()
        val apkVersionCode = getArchiveVersionCode(archiveInfo)

        if (apkVersionCode <= currentVersionCode) {
            LogManager.e("APK version code $apkVersionCode is not newer than current $currentVersionCode")
            return ApkVerificationResult.Error(
                "APK version ($apkVersionCode) is not newer than the currently installed version ($currentVersionCode)"
            )
        }

        if (apkVersionCode < manifest.versionCode) {
            LogManager.e("APK version code $apkVersionCode is lower than manifest version ${manifest.versionCode}")
            return ApkVerificationResult.Error(
                "APK version code ($apkVersionCode) does not match the advertised update ($manifest.versionCode)"
            )
        }

        // 3. Verify Signing Certificate
        val isSignatureValid = verifyCertificateMatches(pm, archiveInfo)
        if (!isSignatureValid) {
            LogManager.e("Signing certificate verification failed!")
            return ApkVerificationResult.Error(
                "Security Alert: APK signing certificate does not match the installed Bridge application certificate."
            )
        }

        LogManager.i("APK pre-install verification succeeded for ${archiveInfo.packageName} v$apkVersionCode")
        return ApkVerificationResult.Success(archiveInfo)
    }

    private fun getCurrentVersionCode(): Long {
        return try {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0).versionCode.toLong()
            }
        } catch (_: Exception) {
            1L
        }
    }

    private fun getArchiveVersionCode(archiveInfo: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode.toLong()
        }
    }

    private fun verifyCertificateMatches(pm: PackageManager, archiveInfo: PackageInfo): Boolean {
        val currentCertHashes = getCurrentAppCertificateHashes(pm)
        if (currentCertHashes.isEmpty()) {
            LogManager.w("Could not extract current app signing certificate hashes")
            return false
        }

        val archiveCertHashes = getArchiveCertificateHashes(archiveInfo)
        if (archiveCertHashes.isEmpty()) {
            LogManager.w("Could not extract archive APK signing certificate hashes")
            return false
        }

        // Must share at least one signing certificate in the chain / history
        val matches = currentCertHashes.intersect(archiveCertHashes).isNotEmpty()
        if (!matches) {
            LogManager.e("Cert hashes mismatch. Current: $currentCertHashes vs Archive: $archiveCertHashes")
        }
        return matches
    }

    private fun getCurrentAppCertificateHashes(pm: PackageManager): Set<String> {
        val hashes = mutableSetOf<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pkgInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = pkgInfo.signingInfo
                if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners?.forEach { sig ->
                            hashes.add(Sha256.digest(sig.toByteArray()).lowercase())
                        }
                    } else {
                        signingInfo.signingCertificateHistory?.forEach { sig ->
                            hashes.add(Sha256.digest(sig.toByteArray()).lowercase())
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val pkgInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                pkgInfo.signatures?.forEach { sig ->
                    hashes.add(Sha256.digest(sig.toByteArray()).lowercase())
                }
            }
        } catch (e: Exception) {
            LogManager.e("Failed to get current app certificates", e)
        }
        return hashes
    }

    private fun getArchiveCertificateHashes(archiveInfo: PackageInfo): Set<String> {
        val hashes = mutableSetOf<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = archiveInfo.signingInfo
                if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners?.forEach { sig ->
                            hashes.add(Sha256.digest(sig.toByteArray()).lowercase())
                        }
                    } else {
                        signingInfo.signingCertificateHistory?.forEach { sig ->
                            hashes.add(Sha256.digest(sig.toByteArray()).lowercase())
                        }
                    }
                }
            }
            if (hashes.isEmpty()) {
                @Suppress("DEPRECATION")
                archiveInfo.signatures?.forEach { sig ->
                    hashes.add(Sha256.digest(sig.toByteArray()).lowercase())
                }
            }
        } catch (e: Exception) {
            LogManager.e("Failed to get archive certificates", e)
        }
        return hashes
    }
}
