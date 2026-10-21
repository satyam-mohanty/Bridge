package com.bridge.android.update.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.bridge.android.update.UpdateManager
import com.bridge.android.update.data.ManifestFetchResult
import com.bridge.android.util.LogManager

class UpdateCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val updateManager = UpdateManager.getInstance(applicationContext)

        if (!updateManager.preferences.isPeriodicCheckEnabled) {
            LogManager.i("Periodic update check disabled in preferences, skipping.")
            return Result.success()
        }

        LogManager.i("Running periodic background update check via WorkManager")

        return try {
            val result = updateManager.checkForUpdate(isManual = false)
            when (result) {
                is ManifestFetchResult.Success -> {
                    LogManager.i("Background update check succeeded: latest version code ${result.manifest.versionCode}")
                    Result.success()
                }
                is ManifestFetchResult.Error -> {
                    if (result.isNetworkError && runAttemptCount < 3) {
                        LogManager.w("Network error during background update check, retrying: ${result.message}")
                        Result.retry()
                    } else {
                        LogManager.w("Background update check failed: ${result.message}")
                        Result.success()
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.e("Exception during WorkManager update check", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
