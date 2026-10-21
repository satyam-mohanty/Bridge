package com.bridge.android.ui

import android.content.Context
import com.bridge.android.service.BridgeService
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
        LogManager.i("Starting resilient Logcat clipboard watcher (Auto Mode)")

        watcherJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                var process: Process? = null
                try {
                    // Monitor logcat on system and main buffers where ClipboardService events are published
                    process = Runtime.getRuntime().exec(arrayOf("logcat", "-T", "1", "-b", "system", "-b", "main", "-v", "brief"))
                    val reader = BufferedReader(InputStreamReader(process.inputStream))

                    var lastTriggerTime = 0L

                    while (isActive) {
                        val line = reader.readLine() ?: break
                        // Ignore our own app logs to avoid self-triggering
                        if (line.contains("com.bridge.android") || line.contains("Bridge") || line.contains("TransparentClip")) continue

                        // Match system clipboard event signatures across Android 10-14 (AOSP, Motorola, Samsung, etc.)
                        val isClipEvent = line.contains("ClipboardService") ||
                                line.contains("setPrimaryClip") ||
                                line.contains("ClipboardOverlay") ||
                                line.contains("ClipboardRecord") ||
                                line.contains("updateClipboardCache") ||
                                line.contains("notifyPrimaryClip") ||
                                line.contains("notifyClipboardUpdated") ||
                                line.contains("Denying clipboard access") ||
                                line.contains("primary clip")

                        if (isClipEvent) {
                            val now = System.currentTimeMillis()
                            // Debounce by 400ms
                            if (now - lastTriggerTime > 400) {
                                lastTriggerTime = now
                                LogManager.i("Logcat watcher detected clipboard update event. Triggering clipboard sync.")
                                delay(60)
                                BridgeService.instance?.handleClipboardChanged()
                            }
                        }
                    }
                } catch (e: Exception) {
                    LogManager.e("Logcat watcher process error (is READ_LOGS permission granted?)", e)
                } finally {
                    try {
                        process?.destroy()
                    } catch (_: Exception) {}
                }

                // If process exited or died, delay and retry while active
                if (isActive) {
                    delay(2000)
                }
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

