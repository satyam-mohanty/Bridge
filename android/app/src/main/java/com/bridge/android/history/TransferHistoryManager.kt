package com.bridge.android.history

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TransferItem(
    val id: String,
    val name: String,
    val size: Long,
    val isIncoming: Boolean,
    val timestamp: Long
)

class TransferHistoryManager(context: Context) {
    private val prefs = context.getSharedPreferences("bridge_transfer_history", Context.MODE_PRIVATE)

    fun getTransfers(): List<TransferItem> {
        val jsonStr = prefs.getString("transfers", null) ?: return emptyList()
        val list = mutableListOf<TransferItem>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    TransferItem(
                        id = obj.optString("id", ""),
                        name = obj.optString("name", "Unknown"),
                        size = obj.optLong("size", 0L),
                        isIncoming = obj.optBoolean("isIncoming", false),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    fun addTransfer(name: String, size: Long, isIncoming: Boolean) {
        val current = getTransfers().toMutableList()
        val item = TransferItem(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            size = size,
            isIncoming = isIncoming,
            timestamp = System.currentTimeMillis()
        )
        current.add(0, item)
        val trimmed = if (current.size > 30) current.subList(0, 30) else current
        save(trimmed)
    }

    fun clearTransfers() {
        prefs.edit().remove("transfers").apply()
    }

    private fun save(items: List<TransferItem>) {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("size", item.size)
                put("isIncoming", item.isIncoming)
                put("timestamp", item.timestamp)
            }
            arr.put(obj)
        }
        prefs.edit().putString("transfers", arr.toString()).apply()
    }
}
