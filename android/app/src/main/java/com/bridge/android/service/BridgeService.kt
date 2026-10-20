package com.bridge.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bridge.android.R
import com.bridge.android.clipboard.ClipboardSyncManager
import com.bridge.android.crypto.KeyStoreManager
import com.bridge.android.network.BridgeClient
import com.bridge.android.network.BridgeClientListener
import com.bridge.android.network.DiscoveryEngine
import android.content.pm.PackageManager
import com.bridge.android.ui.LogcatWatcherService
import com.bridge.android.ui.MainActivity
import com.bridge.android.util.AndroidFileManager
import com.bridge.android.util.LogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BridgeService : Service(), BridgeClientListener {

    inner class LocalBinder : Binder() {
        val service: BridgeService get() = this@BridgeService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var keyStoreManager: KeyStoreManager
    private lateinit var discoveryEngine: DiscoveryEngine
    private lateinit var clipboardSyncManager: ClipboardSyncManager
    private lateinit var client: BridgeClient
    private lateinit var connectivityManager: ConnectivityManager

    private var loopJob: Job? = null
    private var backoffMs = 1000L

    data class FileTransferInfo(
        val transferId: String,
        val fileName: String,
        val progress: Float,
        val isIncoming: Boolean,
        val isCompleted: Boolean = false
    )

    private val _statusFlow = MutableStateFlow("Initializing…")
    val statusFlow: StateFlow<String> = _statusFlow.asStateFlow()

    private val _isConnectedFlow = MutableStateFlow(false)
    val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()

    private val _transferFlow = MutableStateFlow<FileTransferInfo?>(null)
    val transferFlow: StateFlow<FileTransferInfo?> = _transferFlow.asStateFlow()

    private val _lastReceivedFileFlow = MutableStateFlow<String?>(null)
    val lastReceivedFileFlow: StateFlow<String?> = _lastReceivedFileFlow.asStateFlow()

    companion object {
        const val CHANNEL_ID = "bridge_service_channel"
        const val NOTIFICATION_ID = 48250
        const val ACTION_START = "com.bridge.android.action.START"
        const val ACTION_RECONNECT = "com.bridge.android.action.RECONNECT"
        const val ACTION_SEND_CLIPBOARD = "com.bridge.android.action.SEND_CLIPBOARD"

        @Volatile
        var instance: BridgeService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, BridgeService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        keyStoreManager = KeyStoreManager(this)
        discoveryEngine = DiscoveryEngine(this)
        clipboardSyncManager = ClipboardSyncManager(this)
        client = BridgeClient(serviceScope, this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting Bridge service…"))

        registerNetworkCallback()
        startConnectionLoop()
        syncAutoModeState()
        LogManager.i("BridgeService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RECONNECT -> {
                LogManager.i("Received explicit reconnect command")
                triggerReconnect()
            }
            ACTION_SEND_CLIPBOARD -> {
                LogManager.i("Received send clipboard command")
                sendCurrentClipboard()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        instance = null
        LogcatWatcherService.stopWatching()
        unregisterNetworkCallback()
        loopJob?.cancel()
        client.stop()
        serviceScope.cancel()
        LogManager.i("BridgeService destroyed")
        super.onDestroy()
    }

    fun syncAutoModeState() {
        val hasReadLogs = checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        val isAutoEnabled = keyStoreManager.isAutoModeEnabled() && hasReadLogs
        LogManager.i("syncAutoModeState: hasReadLogs=$hasReadLogs, isAutoEnabled=$isAutoEnabled (pref=${keyStoreManager.isAutoModeEnabled()})")
        if (isAutoEnabled) {
            if (!LogcatWatcherService.isWatching()) {
                LogcatWatcherService.startWatching(this, serviceScope)
            }
        } else {
            LogcatWatcherService.stopWatching()
        }
    }

    fun triggerReconnect() {
        serviceScope.launch {
            client.stop()
            backoffMs = 1000L
            startConnectionLoop()
        }
    }

    fun sendCurrentClipboard() {
        if (_isConnectedFlow.value) {
            val intent = Intent(this, com.bridge.android.ui.TransparentClipReaderActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            startActivity(intent)
        } else {
            LogManager.i("Cannot send clipboard: Mac is not connected")
        }
    }

    fun sendFile(name: String, bytes: ByteArray, mime: String, onProgress: ((Float) -> Unit)? = null) {
        if (!_isConnectedFlow.value) {
            LogManager.i("Cannot send file: Mac is not connected")
            return
        }
        client.sendFile(name, bytes, mime, onProgress)
    }

    fun getClient(): BridgeClient = client
    fun getClipboardSyncManager(): ClipboardSyncManager = clipboardSyncManager

    private fun startConnectionLoop() {
        loopJob?.cancel()
        loopJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                val key = keyStoreManager.loadSharedKey()
                if (key == null) {
                    _statusFlow.value = "Not Paired (Scan QR Code)"
                    _isConnectedFlow.value = false
                    updateNotification("Not paired. Tap to scan Mac QR code.")
                    delay(3000)
                    continue
                }

                // 1. Try last known IP directly first without throwaway socket probe
                val lastIp = keyStoreManager.getLastKnownIp()
                var sessionSucceeded = false

                if (!lastIp.isNullOrBlank()) {
                    _statusFlow.value = "Connecting to $lastIp…"
                    updateNotification("Connecting to $lastIp…")
                    LogManager.i("Attempting connection to last known IP: $lastIp")
                    sessionSucceeded = client.runSession(lastIp, key)
                }

                // 2. If last known IP failed or is not available, discover Mac
                if (!sessionSucceeded && isActive) {
                    _statusFlow.value = "Searching for Mac…"
                    _isConnectedFlow.value = false
                    updateNotification("Searching for Mac…")
                    LogManager.i("Discovering Mac on network…")

                    val discoveredIp = discoveryEngine.discoverMacIp(keyStoreManager)
                    if (discoveredIp != null) {
                        keyStoreManager.saveLastKnownIp(discoveredIp)
                        _statusFlow.value = "Connecting to $discoveredIp…"
                        updateNotification("Connecting to $discoveredIp…")
                        sessionSucceeded = client.runSession(discoveredIp, key)
                    }
                }

                // 3. If still not connected, backoff before retry
                if (!sessionSucceeded && isActive) {
                    _statusFlow.value = "Mac not found. Retrying in ${backoffMs / 1000}s…"
                    _isConnectedFlow.value = false
                    updateNotification("Mac not found. Retrying in ${backoffMs / 1000}s…")
                    delay(backoffMs)
                    backoffMs = minOf(backoffMs * 2, 15000L)
                } else {
                    backoffMs = 1000L
                }
            }
        }
    }

    // MARK: - BridgeClientListener
    override fun onConnected(ip: String) {
        serviceScope.launch(Dispatchers.Main) {
            backoffMs = 1000L
            val macName = keyStoreManager.getMacName() ?: "Mac"
            _statusFlow.value = "Connected to $macName ($ip)"
            _isConnectedFlow.value = true
            updateNotification("Connected to $macName ($ip)")
            LogManager.i("BridgeService: Link established with $ip")
            syncAutoModeState()
        }
    }

    override fun onDisconnected() {
        serviceScope.launch(Dispatchers.Main) {
            _isConnectedFlow.value = false
            _statusFlow.value = "Disconnected"
            updateNotification("Disconnected from Mac")
            LogManager.i("BridgeService: Disconnected")
        }
    }

    override fun onTextMessageReceived(text: String, hash: String) {
        serviceScope.launch(Dispatchers.Main) {
            clipboardSyncManager.handleRemoteText(text, hash)
        }
    }

    override fun onImageReceived(data: ByteArray, hash: String) {
        serviceScope.launch(Dispatchers.Main) {
            clipboardSyncManager.handleRemoteImage(data, hash)
        }
    }

    override fun onFileReceived(name: String, data: ByteArray, mime: String) {
        serviceScope.launch(Dispatchers.IO) {
            val savedUri = AndroidFileManager.saveFileToDownloads(this@BridgeService, name, data, mime)
            AndroidFileManager.showFileReceivedNotification(this@BridgeService, name, data.size, savedUri, mime)
            _lastReceivedFileFlow.value = name
            _transferFlow.value = FileTransferInfo(
                transferId = "",
                fileName = name,
                progress = 1.0f,
                isIncoming = true,
                isCompleted = true
            )
        }
    }

    override fun onFileProgress(transferId: String, name: String, progress: Float, isIncoming: Boolean) {
        _transferFlow.value = FileTransferInfo(
            transferId = transferId,
            fileName = name,
            progress = progress,
            isIncoming = isIncoming,
            isCompleted = progress >= 1.0f
        )
    }

    // MARK: - Network Callback (Instant Reconnect)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            LogManager.i("Network available: triggering instant reconnect")
            triggerReconnect()
        }

        override fun onLost(network: Network) {
            LogManager.i("Network lost: resetting client")
            client.stop()
        }
    }

    private fun registerNetworkCallback() {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            LogManager.e("Failed to register NetworkCallback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    // MARK: - Notifications
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bridge Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains connection with your Mac for clipboard synchronization"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bridge")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_bridge_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }
}
