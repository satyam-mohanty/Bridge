package com.bridge.android.protocol

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

object BridgeProtocol {
    const val DEFAULT_PORT = 48250
    const val MAX_FRAME_SIZE = 131072 // 128 KB

    const val TYPE_PING: Byte = 0x01
    const val TYPE_PONG: Byte = 0x02
    const val TYPE_CLIPBOARD_TEXT: Byte = 0x03
    const val TYPE_CLIPBOARD_IMAGE_START: Byte = 0x04
    const val TYPE_CLIPBOARD_IMAGE_CHUNK: Byte = 0x05
    const val TYPE_CLIPBOARD_IMAGE_ACK: Byte = 0x06
    const val TYPE_DISCONNECT: Byte = 0x07
    const val TYPE_FILE_START: Byte = 0x08
    const val TYPE_FILE_CHUNK: Byte = 0x09
    const val TYPE_FILE_ACK: Byte = 0x0A
    const val TYPE_NOTIF_POST: Byte = 0x0B
    const val TYPE_ICON_NEED: Byte = 0x0C
    const val TYPE_ICON_RESP: Byte = 0x0D
    const val TYPE_NOTIF_REMOVE: Byte = 0x0E
    const val TYPE_CALL_EVENT: Byte = 0x0F
    const val TYPE_LOCK_MAC: Byte = 0x10
    const val TYPE_BATTERY_STATUS: Byte = 0x11

    data class TextMessage(
        val id: String,
        val origin: String,
        val hash: String,
        val text: String
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("id", id)
            json.put("origin", origin)
            json.put("hash", hash)
            json.put("text", text)
            return json.toString()
        }

        companion object {
            fun fromJson(jsonStr: String): TextMessage {
                val json = JSONObject(jsonStr)
                return TextMessage(
                    id = json.getString("id"),
                    origin = json.getString("origin"),
                    hash = json.getString("hash"),
                    text = json.getString("text")
                )
            }
        }
    }

    data class ImageStartMessage(
        val id: String,
        val origin: String,
        val hash: String,
        val mime: String,
        val size: Int,
        val totalChunks: Int
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("id", id)
            json.put("origin", origin)
            json.put("hash", hash)
            json.put("mime", mime)
            json.put("size", size)
            json.put("totalChunks", totalChunks)
            return json.toString()
        }

        companion object {
            fun fromJson(jsonStr: String): ImageStartMessage {
                val json = JSONObject(jsonStr)
                return ImageStartMessage(
                    id = json.getString("id"),
                    origin = json.getString("origin"),
                    hash = json.getString("hash"),
                    mime = json.getString("mime"),
                    size = json.getInt("size"),
                    totalChunks = json.getInt("totalChunks")
                )
            }
        }
    }

    data class ImageChunkData(
        val transferId: String,
        val chunkIndex: Int,
        val chunkBytes: ByteArray
    ) {
        fun serialize(): ByteArray {
            val idBytes = transferId.toByteArray(StandardCharsets.UTF_8)
            val paddedId = ByteArray(36) { 0x20 }
            System.arraycopy(idBytes, 0, paddedId, 0, minOf(idBytes.size, 36))

            val buffer = ByteBuffer.allocate(36 + 4 + 4 + chunkBytes.size).order(ByteOrder.BIG_ENDIAN)
            buffer.put(paddedId)
            buffer.putInt(chunkIndex)
            buffer.putInt(chunkBytes.size)
            buffer.put(chunkBytes)
            return buffer.array()
        }

        companion object {
            fun deserialize(data: ByteArray): ImageChunkData? {
                if (data.size < 44) return null
                val idStr = String(data, 0, 36, StandardCharsets.UTF_8).trim()
                val buffer = ByteBuffer.wrap(data, 36, data.size - 36).order(ByteOrder.BIG_ENDIAN)
                val chunkIndex = buffer.getInt()
                val chunkLen = buffer.getInt()
                if (data.size < 44 + chunkLen) return null
                val chunkBytes = ByteArray(chunkLen)
                buffer.get(chunkBytes)
                return ImageChunkData(idStr, chunkIndex, chunkBytes)
            }
        }
    }

    data class ImageAckMessage(
        val id: String,
        val status: String
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("id", id)
            json.put("status", status)
            return json.toString()
        }

        companion object {
            fun fromJson(jsonStr: String): ImageAckMessage {
                val json = JSONObject(jsonStr)
                return ImageAckMessage(
                    id = json.getString("id"),
                    status = json.getString("status")
                )
            }
        }
    }

    data class FileStartMessage(
        val id: String,
        val origin: String,
        val name: String,
        val size: Int,
        val mime: String,
        val totalChunks: Int,
        val hash: String
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("id", id)
            json.put("origin", origin)
            json.put("name", name)
            json.put("size", size)
            json.put("mime", mime)
            json.put("totalChunks", totalChunks)
            json.put("hash", hash)
            return json.toString()
        }

        companion object {
            fun fromJson(jsonStr: String): FileStartMessage {
                val json = JSONObject(jsonStr)
                return FileStartMessage(
                    id = json.getString("id"),
                    origin = json.getString("origin"),
                    name = json.getString("name"),
                    size = json.getInt("size"),
                    mime = json.getString("mime"),
                    totalChunks = json.getInt("totalChunks"),
                    hash = json.getString("hash")
                )
            }
        }
    }

    data class FileChunkData(
        val transferId: String,
        val chunkIndex: Int,
        val chunkBytes: ByteArray
    ) {
        fun serialize(): ByteArray {
            val idBytes = transferId.toByteArray(StandardCharsets.UTF_8)
            val paddedId = ByteArray(36) { 0x20 }
            System.arraycopy(idBytes, 0, paddedId, 0, minOf(idBytes.size, 36))

            val buffer = ByteBuffer.allocate(36 + 4 + 4 + chunkBytes.size).order(ByteOrder.BIG_ENDIAN)
            buffer.put(paddedId)
            buffer.putInt(chunkIndex)
            buffer.putInt(chunkBytes.size)
            buffer.put(chunkBytes)
            return buffer.array()
        }

        companion object {
            fun deserialize(data: ByteArray): FileChunkData? {
                if (data.size < 44) return null
                val idStr = String(data, 0, 36, StandardCharsets.UTF_8).trim()
                val buffer = ByteBuffer.wrap(data, 36, data.size - 36).order(ByteOrder.BIG_ENDIAN)
                val chunkIndex = buffer.getInt()
                val chunkLen = buffer.getInt()
                if (data.size < 44 + chunkLen) return null
                val chunkBytes = ByteArray(chunkLen)
                buffer.get(chunkBytes)
                return FileChunkData(idStr, chunkIndex, chunkBytes)
            }
        }
    }

    data class FileAckMessage(
        val id: String,
        val status: String
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("id", id)
            json.put("status", status)
            return json.toString()
        }

        companion object {
            fun fromJson(jsonStr: String): FileAckMessage {
                val json = JSONObject(jsonStr)
                return FileAckMessage(
                    id = json.getString("id"),
                    status = json.getString("status")
                )
            }
        }
    }

    data class NotifPostMessage(
        val key: String,
        val pkg: String,
        val app: String,
        val title: String,
        val text: String,
        val bigText: String? = null,
        val subText: String? = null,
        val convTitle: String? = null,
        val sender: String? = null,
        val ts: Long,
        val category: String? = null,
        val versionCode: Long = 0,
        val largeIcon: String? = null
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("key", key)
            json.put("pkg", pkg)
            json.put("app", app)
            json.put("title", title)
            json.put("text", text)
            if (bigText != null) json.put("bigText", bigText)
            if (subText != null) json.put("subText", subText)
            if (convTitle != null) json.put("convTitle", convTitle)
            if (sender != null) json.put("sender", sender)
            json.put("ts", ts)
            if (category != null) json.put("category", category)
            json.put("versionCode", versionCode)
            if (largeIcon != null) json.put("largeIcon", largeIcon)
            return json.toString()
        }

        companion object {
            fun fromJson(jsonStr: String): NotifPostMessage {
                val json = JSONObject(jsonStr)
                return NotifPostMessage(
                    key = json.getString("key"),
                    pkg = json.getString("pkg"),
                    app = json.getString("app"),
                    title = json.getString("title"),
                    text = json.getString("text"),
                    bigText = if (json.has("bigText") && !json.isNull("bigText")) json.getString("bigText") else null,
                    subText = if (json.has("subText") && !json.isNull("subText")) json.getString("subText") else null,
                    convTitle = if (json.has("convTitle") && !json.isNull("convTitle")) json.getString("convTitle") else null,
                    sender = if (json.has("sender") && !json.isNull("sender")) json.getString("sender") else null,
                    ts = json.optLong("ts", System.currentTimeMillis()),
                    category = if (json.has("category") && !json.isNull("category")) json.getString("category") else null,
                    versionCode = json.optLong("versionCode", 0),
                    largeIcon = if (json.has("largeIcon") && !json.isNull("largeIcon")) json.getString("largeIcon") else null
                )
            }
        }
    }

    data class IconNeedMessage(
        val pkg: String
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("pkg", pkg)
            return json.toString()
        }

        companion object {
            fun fromJson(jsonStr: String): IconNeedMessage {
                val json = JSONObject(jsonStr)
                return IconNeedMessage(
                    pkg = json.getString("pkg")
                )
            }
        }
    }

    data class IconRespMessage(
        val pkg: String,
        val versionCode: Long,
        val icon: String
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("pkg", pkg)
            json.put("versionCode", versionCode)
            json.put("icon", icon)
            return json.toString()
        }

        companion object {
            fun fromJson(jsonStr: String): IconRespMessage {
                val json = JSONObject(jsonStr)
                return IconRespMessage(
                    pkg = json.getString("pkg"),
                    versionCode = json.optLong("versionCode", 0),
                    icon = json.getString("icon")
                )
            }
        }
    }

    data class NotifRemoveMessage(
        val key: String
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("key", key)
            return json.toString()
        }

        companion object {
            fun fromJson(jsonStr: String): NotifRemoveMessage {
                val json = JSONObject(jsonStr)
                return NotifRemoveMessage(
                    key = json.getString("key")
                )
            }
        }
    }

    data class CallEventMessage(
        val id: String,
        val state: String, // "ringing", "answered", "ended"
        val number: String?,
        val name: String?,
        val ts: Long = System.currentTimeMillis()
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("id", id)
            json.put("state", state)
            if (number != null) json.put("number", number)
            if (name != null) json.put("name", name)
            json.put("ts", ts)
            return json.toString()
        }

        companion object {
            fun fromJson(jsonStr: String): CallEventMessage {
                val json = JSONObject(jsonStr)
                return CallEventMessage(
                    id = json.getString("id"),
                    state = json.getString("state"),
                    number = if (json.has("number") && !json.isNull("number")) json.getString("number") else null,
                    name = if (json.has("name") && !json.isNull("name")) json.getString("name") else null,
                    ts = json.optLong("ts", System.currentTimeMillis())
                )
            }
        }
    }

    data class LockMacMessage(
        val t: Long = System.currentTimeMillis()
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("t", t)
            return json.toString()
        }

        companion object {
            fun fromJson(jsonStr: String): LockMacMessage {
                val json = JSONObject(jsonStr)
                return LockMacMessage(
                    t = json.optLong("t", System.currentTimeMillis())
                )
            }
        }
    }

    data class BatteryStatusMessage(
        val level: Int,
        val isCharging: Boolean,
        val ts: Long = System.currentTimeMillis(),
        val deviceName: String? = null
    ) {
        fun toJson(): String {
            val json = JSONObject()
            json.put("level", level)
            json.put("isCharging", isCharging)
            json.put("ts", ts)
            if (!deviceName.isNullOrBlank()) {
                json.put("deviceName", deviceName)
            }
            return json.toString()
        }

        companion object {
            fun fromJson(jsonStr: String): BatteryStatusMessage {
                val json = JSONObject(jsonStr)
                return BatteryStatusMessage(
                    level = json.getInt("level"),
                    isCharging = json.optBoolean("isCharging", false),
                    ts = json.optLong("ts", System.currentTimeMillis()),
                    deviceName = json.optString("deviceName").takeIf { it.isNotBlank() }
                )
            }
        }
    }

    data class PairingInfo(
        val v: Int,
        val name: String,
        val key: String,
        val port: Int
    ) {
        companion object {
            fun fromJson(jsonStr: String): PairingInfo {
                val json = JSONObject(jsonStr)
                return PairingInfo(
                    v = json.optInt("v", 1),
                    name = json.optString("name", "Mac"),
                    key = json.getString("key"),
                    port = json.optInt("port", DEFAULT_PORT)
                )
            }
        }
    }
}
