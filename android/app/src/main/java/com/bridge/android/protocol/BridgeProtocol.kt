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
