package com.bridge.android.ui

import android.content.Context
import android.content.Intent
import com.bridge.android.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

object LogcatWatcherService {
    private var watcherJob: Job? = null

    fun startWatching(context: Context, scope: CoroutineScope) {
        stopWatching()
        LogManager.i("Starting Logcat clipboard watcher (Auto Mode)")

        watcherJob = scope.launch(Dispatchers.IO) {
            var process: Process? = null
            try {
                // Monitor logcat starting from now without clearing system buffer
                process = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "brief", "-T", "1"))
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                var lastTriggerTime = 0L

                while (isActive) {
                    val line = reader.readLine() ?: break
                    // Ignore our own app's logs to prevent loops
                    if (line.contains("com.bridge.android") || line.contains("Bridge")) continue

                    // System logcat line when clip changes across AOSP and OEM variants (Motorola, Samsung, etc.)
                    val isClipEvent = line.contains("setPrimaryClip") ||
                            line.contains("ClipboardService") ||
                            line.contains("ClipboardOverlay") ||
                            line.contains("ClipboardRecord") ||
                            line.contains("updateClipboardCache") ||
                            line.contains("notifyPrimaryClip") ||
                            line.contains("notifyClipboardUpdated")

                    if (isClipEvent) {
                        val now = System.currentTimeMillis()
                        // Debounce by 800ms
                        if (now - lastTriggerTime > 800) {
                            lastTriggerTime = now
                            LogManager.i("Logcat watcher detected clipboard update event. Triggering reader.")
                            delay(150)
                            val intent = Intent(context, TransparentClipReaderActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                            }
                            context.startActivity(intent)
                        }
                    }
                }
            } catch (e: Exception) {
                LogManager.e("Logcat watcher error (is READ_LOGS permission granted?)", e)
            } finally {
                process?.destroy()
            }
        }
    }

    fun stopWatching() {
        watcherJob?.cancel()
        watcherJob = null
        LogManager.i("Stopped Logcat clipboard watcher")
    }

    fun isWatching(): Boolean = watcherJob?.isActive == true
}
