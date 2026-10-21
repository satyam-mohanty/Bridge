package com.bridge.android.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class NotificationHistoryItem(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val timestamp: Long
)

class RecentNotificationHistoryManager(context: Context) {
    private val prefs = context.getSharedPreferences("bridge_recent_notifs", Context.MODE_PRIVATE)

    fun getNotifications(): List<NotificationHistoryItem> {
        val jsonStr = prefs.getString("notifications", null) ?: return emptyList()
        val list = mutableListOf<NotificationHistoryItem>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    NotificationHistoryItem(
                        key = obj.optString("key", ""),
                        pkg = obj.optString("pkg", ""),
                        app = obj.optString("app", ""),
                        title = obj.optString("title", ""),
                        text = obj.optString("text", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun addNotification(key: String, pkg: String, app: String, title: String, text: String, ts: Long) {
        val current = getNotifications().toMutableList()
        current.removeAll { it.key == key }
        current.add(0, NotificationHistoryItem(key, pkg, app, title, text, ts))
        val trimmed = if (current.size > 30) current.subList(0, 30) else current
        save(trimmed)
    }

    fun clearNotifications() {
        prefs.edit().remove("notifications").apply()
    }

    private fun save(items: List<NotificationHistoryItem>) {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("key", item.key)
                put("pkg", item.pkg)
                put("app", item.app)
                put("title", item.title)
                put("text", item.text)
                put("timestamp", item.timestamp)
            }
            arr.put(obj)
        }
        prefs.edit().putString("notifications", arr.toString()).apply()
    }
}
