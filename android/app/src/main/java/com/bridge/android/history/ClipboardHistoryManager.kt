package com.bridge.android.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ClipboardHistoryItem(
    val id: String,
    val text: String,
    val isIncoming: Boolean,
    val timestamp: Long,
    val macName: String,
    val deviceName: String
)

class ClipboardHistoryManager(context: Context) {
    private val prefs = context.getSharedPreferences("bridge_clipboard_history", Context.MODE_PRIVATE)

    fun getItems(): List<ClipboardHistoryItem> {
        val jsonStr = prefs.getString("items", null) ?: return emptyList()
        val list = mutableListOf<ClipboardHistoryItem>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ClipboardHistoryItem(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        text = obj.optString("text", ""),
                        isIncoming = obj.optBoolean("isIncoming", false),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        macName = obj.optString("macName", "Mac"),
                        deviceName = obj.optString("deviceName", android.os.Build.MODEL ?: "Phone")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun addItem(text: String, isIncoming: Boolean, macName: String? = null, deviceName: String? = null) {
        if (text.isBlank()) return
        val current = getItems().toMutableList()
        // Deduplicate recent identical snippet within 10 seconds
        val now = System.currentTimeMillis()
        if (current.isNotEmpty() && current[0].text == text && (now - current[0].timestamp < 10000)) {
            return
        }
        val item = ClipboardHistoryItem(
            id = java.util.UUID.randomUUID().toString(),
            text = text,
            isIncoming = isIncoming,
            timestamp = now,
            macName = macName ?: "Mac",
            deviceName = deviceName ?: (android.os.Build.MODEL ?: "Phone")
        )
        current.add(0, item)
        val trimmed = if (current.size > 50) current.subList(0, 50) else current
        save(trimmed)
    }

    fun clearHistory() {
        prefs.edit().remove("items").apply()
    }

    private fun save(items: List<ClipboardHistoryItem>) {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("text", item.text)
                put("isIncoming", item.isIncoming)
                put("timestamp", item.timestamp)
                put("macName", item.macName)
                put("deviceName", item.deviceName)
            }
            arr.put(obj)
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }
}
