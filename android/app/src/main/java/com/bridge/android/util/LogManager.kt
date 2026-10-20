package com.bridge.android.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogManager {
    private const val TAG = "Bridge"
    private const val MAX_LOGS = 200

    private val logs = ArrayDeque<String>(MAX_LOGS)
    private val lock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val _recentLogsFlow = MutableStateFlow<List<String>>(emptyList())
    val recentLogsFlow: StateFlow<List<String>> = _recentLogsFlow.asStateFlow()

    fun i(message: String) {
        log("INFO", message)
        Log.i(TAG, message)
    }

    fun d(message: String) {
        log("DEBUG", message)
        Log.d(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) "$message: ${throwable.message}" else message
        log("ERROR", fullMsg)
        Log.e(TAG, fullMsg, throwable)
    }

    private fun log(level: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val formatted = "[$timestamp] [$level] $message"

        synchronized(lock) {
            if (logs.size >= MAX_LOGS) {
                logs.removeFirst()
            }
            logs.addLast(formatted)
            _recentLogsFlow.value = logs.toList()
        }
    }

    fun getDiagnostics(): String {
        synchronized(lock) {
            return if (logs.isEmpty()) {
                "No logs recorded yet."
            } else {
                logs.joinToString("\n")
            }
        }
    }
}
