package com.bridge.android.network

import com.bridge.android.crypto.BridgeCrypto
import com.bridge.android.protocol.BridgeProtocol
import com.bridge.android.util.ImageUtils
import com.bridge.android.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

interface BridgeClientListener {
    fun onConnected(ip: String)
    fun onDisconnected()
    fun onTextMessageReceived(text: String, hash: String)
    fun onImageReceived(data: ByteArray, hash: String)
    fun onFileReceived(name: String, data: ByteArray, mime: String)
    fun onFileProgress(transferId: String, name: String, progress: Float, isIncoming: Boolean) {}
    fun onIconNeed(pkg: String) {}
}

class BridgeClient(
    private val scope: CoroutineScope,
    private val listener: BridgeClientListener
) {
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        data class Searching(val step: String) : ConnectionState()
        data class Connected(val ip: String) : ConnectionState()
    }

    private val _stateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val stateFlow: StateFlow<ConnectionState> = _stateFlow.asStateFlow()

    private var socket: Socket? = null
    private var dataIn: DataInputStream? = null
    private var dataOut: DataOutputStream? = null

    private var crypto: BridgeCrypto? = null
    private var outgoingCounter: Long = 0
    private var expectedIncomingCounter: Long = 0

    private var lastReceivedTime: Long = 0
    private var lastSentTime: Long = 0

    private var clientJob: Job? = null
    private var heartbeatJob: Job? = null
    private val isDisconnecting = AtomicBoolean(false)

    // Image assembly state
    private var pendingImageStart: BridgeProtocol.ImageStartMessage? = null
    private val receivedChunks = ConcurrentHashMap<Int, ByteArray>()

    // File assembly state
    private var pendingFileStart: BridgeProtocol.FileStartMessage? = null
    private val receivedFileChunks = ConcurrentHashMap<Int, ByteArray>()

    fun start(ip: String, key: ByteArray): Job {
        stop()
        val job = scope.launch(Dispatchers.IO) {
            runSession(ip, key)
        }
        clientJob = job
        return job
    }

    fun stop() {
        val wasConnected = _stateFlow.value !is ConnectionState.Disconnected
        isDisconnecting.set(true)
        heartbeatJob?.cancel()
        heartbeatJob = null
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        dataIn = null
        dataOut = null
        clientJob?.cancel()
        clientJob = null
        _stateFlow.value = ConnectionState.Disconnected
        if (wasConnected) {
            listener.onDisconnected()
        }
    }

    suspend fun runSession(ip: String, key: ByteArray): Boolean = withContext(Dispatchers.IO) {
        stop()
        isDisconnecting.set(false)
        var connectedSuccessfully = false
        try {
            LogManager.i("Connecting TCP socket to $ip:${BridgeProtocol.DEFAULT_PORT}")
            crypto = BridgeCrypto(key)
            outgoingCounter = 0
            expectedIncomingCounter = 0
            lastReceivedTime = System.currentTimeMillis()
            lastSentTime = System.currentTimeMillis()
            pendingImageStart = null
            receivedChunks.clear()
            pendingFileStart = null
            receivedFileChunks.clear()

            val newSocket = Socket()
            newSocket.tcpNoDelay = true
            newSocket.keepAlive = true
            newSocket.sendBufferSize = 2 * 1024 * 1024 // 2 MB TCP send buffer
            newSocket.receiveBufferSize = 2 * 1024 * 1024 // 2 MB TCP receive buffer
            newSocket.soTimeout = 0 // Blocking read, managed by heartbeat / timeout loop
            newSocket.connect(InetSocketAddress(ip, BridgeProtocol.DEFAULT_PORT), 4000)

            socket = newSocket
            dataIn = DataInputStream(BufferedInputStream(newSocket.getInputStream(), 256 * 1024))
            dataOut = DataOutputStream(BufferedOutputStream(newSocket.getOutputStream(), 256 * 1024))

            connectedSuccessfully = true
            _stateFlow.value = ConnectionState.Connected(ip)
            listener.onConnected(ip)
            LogManager.i("Successfully connected to Mac at $ip")

            startHeartbeat()
            readLoop()
            true
        } catch (e: Exception) {
            if (!isDisconnecting.get()) {
                LogManager.d("TCP session ended: ${e.message}")
            }
            connectedSuccessfully
        } finally {
            if (!isDisconnecting.get()) {
                stop()
            }
        }
    }

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        val inputStream = dataIn ?: return@withContext
        val activeCrypto = crypto ?: return@withContext

        while (scope.isActive && !isDisconnecting.get()) {
            // Read 4-byte big endian length
            val frameLength = inputStream.readInt()
            if (frameLength <= 0 || frameLength > BridgeProtocol.MAX_FRAME_SIZE) {
                LogManager.e("Invalid frame length received: $frameLength. Terminating connection.")
                break
            }

            // Read frame body
            val ciphertextWithTag = ByteArray(frameLength)
            inputStream.readFully(ciphertextWithTag)

            val counter = expectedIncomingCounter
            val plaintext = try {
                val pt = activeCrypto.decrypt(
                    ciphertextWithTag,
                    counter,
                    BridgeCrypto.DIRECTION_MAC_TO_ANDROID
                )
                expectedIncomingCounter++
                lastReceivedTime = System.currentTimeMillis()
                pt
            } catch (e: Exception) {
                LogManager.e("Auth / Decryption failed for frame counter $counter: silently dropping", e)
                break
            }

            handlePlaintextMessage(plaintext)
        }
    }

    private fun handlePlaintextMessage(data: ByteArray) {
        if (data.isEmpty()) return
        val type = data[0]
        val payload = data.copyOfRange(1, data.size)

        when (type) {
            BridgeProtocol.TYPE_PING -> {
                sendPong()
            }
            BridgeProtocol.TYPE_PONG -> {
                // Heartbeat acknowledged
            }
            BridgeProtocol.TYPE_CLIPBOARD_TEXT -> {
                val jsonStr = String(payload, Charsets.UTF_8)
                val msg = BridgeProtocol.TextMessage.fromJson(jsonStr)
                listener.onTextMessageReceived(msg.text, msg.hash)
            }
            BridgeProtocol.TYPE_CLIPBOARD_IMAGE_START -> {
                val jsonStr = String(payload, Charsets.UTF_8)
                val start = BridgeProtocol.ImageStartMessage.fromJson(jsonStr)
                pendingImageStart = start
                receivedChunks.clear()
            }
            BridgeProtocol.TYPE_CLIPBOARD_IMAGE_CHUNK -> {
                val chunk = BridgeProtocol.ImageChunkData.deserialize(payload)
                if (chunk != null) {
                    handleImageChunk(chunk)
                }
            }
            BridgeProtocol.TYPE_CLIPBOARD_IMAGE_ACK -> {
                val jsonStr = String(payload, Charsets.UTF_8)
                val ack = BridgeProtocol.ImageAckMessage.fromJson(jsonStr)
                LogManager.i("Image transfer ACK from Mac: ${ack.status}")
            }
            BridgeProtocol.TYPE_FILE_START -> {
                val jsonStr = String(payload, Charsets.UTF_8)
                val start = BridgeProtocol.FileStartMessage.fromJson(jsonStr)
                pendingFileStart = start
                receivedFileChunks.clear()
                listener.onFileProgress(start.id, start.name, 0f, true)
                LogManager.i("Incoming file transfer from Mac: ${start.name} (${start.size} bytes)")
            }
            BridgeProtocol.TYPE_FILE_CHUNK -> {
                val chunk = BridgeProtocol.FileChunkData.deserialize(payload)
                if (chunk != null) {
                    handleFileChunk(chunk)
                }
            }
            BridgeProtocol.TYPE_FILE_ACK -> {
                val jsonStr = String(payload, Charsets.UTF_8)
                val ack = BridgeProtocol.FileAckMessage.fromJson(jsonStr)
                LogManager.i("File transfer ACK from Mac: ${ack.status}")
            }
            BridgeProtocol.TYPE_DISCONNECT -> {
                LogManager.i("Mac requested disconnect")
                stop()
            }
            BridgeProtocol.TYPE_ICON_NEED -> {
                val jsonStr = String(payload, Charsets.UTF_8)
                val need = BridgeProtocol.IconNeedMessage.fromJson(jsonStr)
                listener.onIconNeed(need.pkg)
            }
            else -> {
                LogManager.d("Unknown message type: $type")
            }
        }
    }

    private fun handleImageChunk(chunk: BridgeProtocol.ImageChunkData) {
        val start = pendingImageStart ?: return
        if (chunk.transferId != start.id) return

        receivedChunks[chunk.chunkIndex] = chunk.chunkBytes

        if (receivedChunks.size == start.totalChunks) {
            // Reassemble
            val totalSize = (0 until start.totalChunks).sumOf { receivedChunks[it]?.size ?: 0 }
            val fullBytes = ByteArray(totalSize)
            var offset = 0
            for (i in 0 until start.totalChunks) {
                val piece = receivedChunks[i] ?: return
                System.arraycopy(piece, 0, fullBytes, offset, piece.size)
                offset += piece.size
            }

            val computedHash = ImageUtils.sha256Hex(fullBytes)
            if (computedHash.equals(start.hash, ignoreCase = true)) {
                sendImageAck(start.id, "ok")
                listener.onImageReceived(fullBytes, computedHash)
            } else {
                LogManager.e("Image SHA-256 mismatch! Expected ${start.hash}, got $computedHash")
                sendImageAck(start.id, "error")
            }
            pendingImageStart = null
            receivedChunks.clear()
        }
    }

    private fun handleFileChunk(chunk: BridgeProtocol.FileChunkData) {
        val start = pendingFileStart ?: return
        if (chunk.transferId != start.id) return

        receivedFileChunks[chunk.chunkIndex] = chunk.chunkBytes
        val currentCount = receivedFileChunks.size
        val total = start.totalChunks
        val throttleStep = maxOf(1, total / 50)

        if (currentCount == total || currentCount % throttleStep == 0) {
            val progress = currentCount.toFloat() / total
            listener.onFileProgress(start.id, start.name, progress, true)
        }

        if (currentCount == total) {
            val totalSize = (0 until total).sumOf { receivedFileChunks[it]?.size ?: 0 }
            val fullBytes = ByteArray(totalSize)
            var offset = 0
            for (i in 0 until total) {
                val piece = receivedFileChunks[i] ?: return
                System.arraycopy(piece, 0, fullBytes, offset, piece.size)
                offset += piece.size
            }

            val computedHash = ImageUtils.sha256Hex(fullBytes)
            if (computedHash.equals(start.hash, ignoreCase = true)) {
                sendFileAck(start.id, "ok")
                LogManager.i("File received from Mac verified: ${start.name} ($totalSize bytes)")
                listener.onFileReceived(start.name, fullBytes, start.mime)
            } else {
                LogManager.e("File SHA-256 mismatch! Expected ${start.hash}, got $computedHash")
                sendFileAck(start.id, "error")
            }
            pendingFileStart = null
            receivedFileChunks.clear()
        }
    }

    fun sendFile(name: String, bytes: ByteArray, mime: String, onProgress: ((Float) -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            val transferId = java.util.UUID.randomUUID().toString()
            val hash = ImageUtils.sha256Hex(bytes)
            val chunks = ImageUtils.chunkBytes(bytes)
            val totalChunks = chunks.size

            val startMsg = BridgeProtocol.FileStartMessage(
                id = transferId,
                origin = "android",
                name = name,
                size = bytes.size,
                mime = mime.ifEmpty { "application/octet-stream" },
                totalChunks = totalChunks,
                hash = hash
            )
            send(BridgeProtocol.TYPE_FILE_START, startMsg.toJson().toByteArray(Charsets.UTF_8), flush = true)
            listener.onFileProgress(transferId, name, 0f, false)
            onProgress?.invoke(0f)

            val throttleStep = maxOf(1, totalChunks / 50)

            for ((index, chunk) in chunks.withIndex()) {
                val chunkObj = BridgeProtocol.FileChunkData(
                    transferId = transferId,
                    chunkIndex = index,
                    chunkBytes = chunk
                )
                val isLast = index == totalChunks - 1
                // Batch-flush every 8 chunks (~512 KB) or on the last chunk to maximize TCP throughput
                val shouldFlush = isLast || ((index + 1) % 8 == 0)
                send(BridgeProtocol.TYPE_FILE_CHUNK, chunkObj.serialize(), flush = shouldFlush)

                val current = index + 1
                if (isLast || current % throttleStep == 0) {
                    val progress = current.toFloat() / totalChunks
                    onProgress?.invoke(progress)
                    listener.onFileProgress(transferId, name, progress, false)
                }
            }
            LogManager.i("Sent file $name ($totalChunks chunks) to Mac at high speed")
        }
    }

    fun sendTextMessage(text: String, hash: String) {
        scope.launch(Dispatchers.IO) {
            val msg = BridgeProtocol.TextMessage(
                id = java.util.UUID.randomUUID().toString(),
                origin = "android",
                hash = hash,
                text = text
            )
            send(BridgeProtocol.TYPE_CLIPBOARD_TEXT, msg.toJson().toByteArray(Charsets.UTF_8), flush = true)
            LogManager.i("Dispatched clipboard text to Mac (${text.length} chars)")
        }
    }

    fun sendNotifPost(msg: BridgeProtocol.NotifPostMessage) {
        scope.launch(Dispatchers.IO) {
            send(BridgeProtocol.TYPE_NOTIF_POST, msg.toJson().toByteArray(Charsets.UTF_8), flush = true)
        }
    }

    fun sendNotifRemove(key: String) {
        scope.launch(Dispatchers.IO) {
            val msg = BridgeProtocol.NotifRemoveMessage(key)
            send(BridgeProtocol.TYPE_NOTIF_REMOVE, msg.toJson().toByteArray(Charsets.UTF_8), flush = true)
        }
    }

    fun sendCallEvent(msg: BridgeProtocol.CallEventMessage) {
        scope.launch(Dispatchers.IO) {
            send(BridgeProtocol.TYPE_CALL_EVENT, msg.toJson().toByteArray(Charsets.UTF_8), flush = true)
        }
    }

    fun sendLockMac() {
        scope.launch(Dispatchers.IO) {
            val msg = BridgeProtocol.LockMacMessage()
            send(BridgeProtocol.TYPE_LOCK_MAC, msg.toJson().toByteArray(Charsets.UTF_8), flush = true)
            LogManager.i("Sent lock mac request to Mac")
        }
    }

    fun sendIconResp(pkg: String, versionCode: Long, iconBase64: String) {
        scope.launch(Dispatchers.IO) {
            val msg = BridgeProtocol.IconRespMessage(pkg, versionCode, iconBase64)
            send(BridgeProtocol.TYPE_ICON_RESP, msg.toJson().toByteArray(Charsets.UTF_8), flush = true)
        }
    }

    fun sendImage(bytes: ByteArray, mime: String, hash: String, totalChunks: Int) {
        scope.launch(Dispatchers.IO) {
            val transferId = java.util.UUID.randomUUID().toString()
            val chunks = ImageUtils.chunkBytes(bytes)
            val actualTotalChunks = chunks.size
            val startMsg = BridgeProtocol.ImageStartMessage(
                id = transferId,
                origin = "android",
                hash = hash,
                mime = mime,
                size = bytes.size,
                totalChunks = actualTotalChunks
            )
            send(BridgeProtocol.TYPE_CLIPBOARD_IMAGE_START, startMsg.toJson().toByteArray(Charsets.UTF_8), flush = true)

            for ((index, chunk) in chunks.withIndex()) {
                val chunkObj = BridgeProtocol.ImageChunkData(
                    transferId = transferId,
                    chunkIndex = index,
                    chunkBytes = chunk
                )
                val isLast = index == chunks.size - 1
                send(BridgeProtocol.TYPE_CLIPBOARD_IMAGE_CHUNK, chunkObj.serialize(), flush = isLast || ((index + 1) % 8 == 0))
            }
        }
    }

    private fun sendPing() {
        val json = JSONObject().apply { put("t", System.currentTimeMillis()) }
        send(BridgeProtocol.TYPE_PING, json.toString().toByteArray(Charsets.UTF_8), flush = true)
    }

    private fun sendPong() {
        val json = JSONObject().apply { put("t", System.currentTimeMillis()) }
        send(BridgeProtocol.TYPE_PONG, json.toString().toByteArray(Charsets.UTF_8), flush = true)
    }

    private fun sendImageAck(id: String, status: String) {
        val msg = BridgeProtocol.ImageAckMessage(id, status)
        send(BridgeProtocol.TYPE_CLIPBOARD_IMAGE_ACK, msg.toJson().toByteArray(Charsets.UTF_8), flush = true)
    }

    private fun sendFileAck(id: String, status: String) {
        val msg = BridgeProtocol.FileAckMessage(id, status)
        send(BridgeProtocol.TYPE_FILE_ACK, msg.toJson().toByteArray(Charsets.UTF_8), flush = true)
    }

    @Synchronized
    private fun send(type: Byte, payload: ByteArray, flush: Boolean = true) {
        val out = dataOut ?: return
        val activeCrypto = crypto ?: return

        try {
            val plaintext = ByteArray(1 + payload.size)
            plaintext[0] = type
            System.arraycopy(payload, 0, plaintext, 1, payload.size)

            val ciphertext = activeCrypto.encrypt(
                plaintext,
                outgoingCounter,
                BridgeCrypto.DIRECTION_ANDROID_TO_MAC
            )
            outgoingCounter++
            lastSentTime = System.currentTimeMillis()

            out.writeInt(ciphertext.size)
            out.write(ciphertext)
            if (flush) {
                out.flush()
            }
        } catch (e: Exception) {
            LogManager.e("Failed to send frame (type=$type)", e)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && !isDisconnecting.get()) {
                delay(15000)
                val now = System.currentTimeMillis()

                // Check 40-second dead connection timeout
                if (now - lastReceivedTime >= 40000) {
                    LogManager.e("No traffic from Mac for 40s (dead connection). Reconnecting.")
                    stop()
                    break
                }

                // If idle for >= 15s since last send, ping
                if (now - lastSentTime >= 15000) {
                    sendPing()
                }
            }
        }
    }
}
