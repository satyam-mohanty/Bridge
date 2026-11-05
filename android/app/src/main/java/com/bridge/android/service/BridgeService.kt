package com.bridge.android.service

import java.io.File
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
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
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import android.widget.Toast
import com.bridge.android.ui.MainActivity
import com.bridge.android.util.AndroidFileManager
import com.bridge.android.util.ConnectivityHelper
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
    private var backoffMs = 2000L // Start at 2s instead of 1s

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        LogManager.i("BridgeService: onPrimaryClipChanged event detected")
        handleClipboardChanged()
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                LogManager.d("Device unlocked: checking clipboard state")
                handleClipboardChanged()
            }
        }
    }

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                ConnectivityHelper.updateWifiState(state)
                handleConnectivityChange()
            }
        }
    }

    data class FileTransferInfo(
        val transferId: String,
        val fileName: String,
        val progress: Float,
        val isIncoming: Boolean,
        val isCompleted: Boolean = false
    )

    companion object {
        const val CHANNEL_ID = "bridge_service_channel"
        const val NOTIFICATION_ID = 48250
        const val ACTION_START = "com.bridge.android.action.START"
        const val ACTION_RECONNECT = "com.bridge.android.action.RECONNECT"
        const val ACTION_SEND_CLIPBOARD = "com.bridge.android.action.SEND_CLIPBOARD"
        const val ACTION_TOGGLE_SEARCH = "com.bridge.android.action.TOGGLE_SEARCH"
        const val ACTION_STOP_SEARCH = "com.bridge.android.action.STOP_SEARCH"
        const val ACTION_START_SEARCH = "com.bridge.android.action.START_SEARCH"
        const val ACTION_LOCK_MAC = "com.bridge.android.action.LOCK_MAC"

        private val _statusFlow = MutableStateFlow("Initializing…")
        val statusFlow: StateFlow<String> = _statusFlow.asStateFlow()

        private val _isConnectedFlow = MutableStateFlow(false)
        val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()

        private val _transferFlow = MutableStateFlow<FileTransferInfo?>(null)
        val transferFlow: StateFlow<FileTransferInfo?> = _transferFlow.asStateFlow()

        private val _lastReceivedFileFlow = MutableStateFlow<String?>(null)
        val lastReceivedFileFlow: StateFlow<String?> = _lastReceivedFileFlow.asStateFlow()

        private val _peerBatteryFlow = MutableStateFlow<Pair<Int, Boolean>?>(null)
        val peerBatteryFlow: StateFlow<Pair<Int, Boolean>?> = _peerBatteryFlow.asStateFlow()

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

    val statusFlow: StateFlow<String> get() = Companion.statusFlow
    val isConnectedFlow: StateFlow<Boolean> get() = Companion.isConnectedFlow
    val transferFlow: StateFlow<FileTransferInfo?> get() = Companion.transferFlow
    val lastReceivedFileFlow: StateFlow<String?> get() = Companion.lastReceivedFileFlow

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

        registerConnectivityReceiver()
        registerNetworkCallback()
        startConnectionLoop()
        registerClipboardListeners()
        registerBatteryReceiver()
        com.bridge.android.notification.CallSyncManager.register(this)
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
            ACTION_TOGGLE_SEARCH -> {
                LogManager.i("Received toggle search command")
                toggleSearching()
            }
            ACTION_STOP_SEARCH -> {
                LogManager.i("Received stop search command")
                pauseSearching()
            }
            ACTION_START_SEARCH -> {
                LogManager.i("Received start search command")
                resumeSearching()
            }
            ACTION_LOCK_MAC -> {
                LogManager.i("Received remote lock Mac command")
                if (_isConnectedFlow.value) {
                    client.sendLockMac()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, "Lock command sent to Mac ✓", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(applicationContext, "Mac is not connected", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val keyStoreManager = KeyStoreManager(this)
        if (keyStoreManager.isSearchingEnabled()) {
            LogManager.i("App task removed from recents, but search/sync is enabled -> keeping BridgeService running in background")
        } else {
            LogManager.i("App task removed from recents and search/sync is disabled -> stopping BridgeService")
            loopJob?.cancel()
            loopJob = null
            client.stop()
            _isConnectedFlow.value = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        instance = null
        _isConnectedFlow.value = false
        _peerBatteryFlow.value = null
        com.bridge.android.notification.CallSyncManager.unregister(this)
        unregisterBatteryReceiver()
        unregisterClipboardListeners()
        unregisterConnectivityReceiver()
        unregisterNetworkCallback()
        loopJob?.cancel()
        loopJob = null
        client.stop()
        serviceScope.cancel()
        LogManager.i("BridgeService destroyed")
        super.onDestroy()
    }

    private fun registerClipboardListeners() {
        try {
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipManager.addPrimaryClipChangedListener(clipListener)
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(unlockReceiver, filter)
            }
            LogManager.i("Registered clipboard change listener & unlock receiver in BridgeService")
        } catch (e: Exception) {
            LogManager.e("Failed to register clipboard listeners in BridgeService", e)
        }
    }

    private fun unregisterClipboardListeners() {
        try {
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipManager.removePrimaryClipChangedListener(clipListener)
            unregisterReceiver(unlockReceiver)
        } catch (_: Exception) {}
    }

    private var batteryReceiver: BroadcastReceiver? = null

    private fun registerBatteryReceiver() {
        if (batteryReceiver == null) {
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (_isConnectedFlow.value) {
                        sendLocalBatteryStatus()
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(batteryReceiver, filter)
            }
            LogManager.i("Registered battery status receiver in BridgeService")
        }
    }

    private fun unregisterBatteryReceiver() {
        batteryReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
            batteryReceiver = null
        }
    }

    fun sendLocalBatteryStatus() {
        val info = com.bridge.android.util.BatterySyncHelper.getCurrentBattery(this) ?: return
        val deviceName = getLocalDeviceName()
        client.sendBatteryStatus(info.level, info.isCharging, deviceName)
    }

    private fun getLocalDeviceName(): String {
        return try {
            val userNamed = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            if (!userNamed.isNullOrBlank()) return userNamed
            val model = Build.MODEL
            val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        } catch (_: Exception) {
            val model = Build.MODEL
            val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
        }
    }

    fun handleClipboardChanged(force: Boolean = false) {
        if (!_isConnectedFlow.value) return

        // 1. Direct read from BridgeService (succeeds if application is in focus)
        try {
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val sent = clipboardSyncManager.sendLocalClipboardToMac(client, clipManager, force = force, activeContext = this)
            if (sent) return
        } catch (_: Throwable) {}

        // 2. ONLY launch TransparentClipReaderActivity if this was an EXPLICIT user action (force == true),
        // such as tapping "Send to Mac" in notification or widget.
        // NEVER launch it in the background during passive events, as doing so steals focus from Recent Apps and foreground activities.
        if (force) {
            triggerTransparentClipReader(force = true)
        }
    }

    fun triggerTransparentClipReader(force: Boolean = false) {
        if (!_isConnectedFlow.value) return
        try {
            val intent = Intent(this, com.bridge.android.ui.TransparentClipReaderActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
                putExtra("force", force)
            }

            val options = ActivityOptions.makeBasic()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
                try {
                    options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                } catch (_: Throwable) {}
                try {
                    val method = ActivityOptions::class.java.getMethod("setPendingIntentCreatorBackgroundActivityStartMode", Int::class.javaPrimitiveType)
                    method.invoke(options, ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                } catch (_: Throwable) {}
            }

            // 1. PendingIntent dispatch with background activity permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    val pi = PendingIntent.getActivity(
                        this,
                        (System.currentTimeMillis() % 10000).toInt(),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        options.toBundle()
                    )
                    pi.send(this, 0, null, null, null, null, options.toBundle())
                    LogManager.i("Triggered TransparentClipReaderActivity via PendingIntent (force=$force)")
                    return
                } catch (e: Throwable) {
                    LogManager.d("PendingIntent launch failed: ${e.message}")
                }
            }

            // 2. Direct launch fallback
            startActivity(intent, options.toBundle())
            LogManager.i("Triggered TransparentClipReaderActivity via Context (force=$force)")
        } catch (e: Exception) {
            LogManager.e("Error triggering TransparentClipReaderActivity", e)
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
            triggerTransparentClipReader(force = true)
        } else {
            LogManager.i("Cannot send clipboard: Mac is not connected")
        }
    }

    fun sendLockMac(): Boolean {
        if (_isConnectedFlow.value) {
            client.sendLockMac()
            return true
        } else {
            LogManager.i("Cannot lock Mac: Mac is not connected")
            return false
        }
    }

    fun sendFile(name: String, bytes: ByteArray, mime: String, onProgress: ((Float) -> Unit)? = null) {
        if (!_isConnectedFlow.value) {
            LogManager.i("Cannot send file: Mac is not connected")
            return
        }
        com.bridge.android.history.TransferHistoryManager(this).addTransfer(
            name = name,
            size = bytes.size.toLong(),
            isIncoming = false
        )
        client.sendFile(name, bytes, mime, onProgress)
    }

    fun sendFile(
        file: File,
        displayName: String = file.name,
        mime: String,
        onProgress: ((Float) -> Unit)? = null,
        deleteOnComplete: Boolean = false
    ) {
        if (!_isConnectedFlow.value) {
            LogManager.i("Cannot send file: Mac is not connected")
            if (deleteOnComplete) {
                try { file.delete() } catch (_: Exception) {}
            }
            return
        }
        com.bridge.android.history.TransferHistoryManager(this).addTransfer(
            name = displayName,
            size = file.length(),
            isIncoming = false
        )
        client.sendFile(file, displayName, mime, onProgress, deleteOnComplete)
    }

    fun getClient(): BridgeClient = client
    fun getClipboardSyncManager(): ClipboardSyncManager = clipboardSyncManager

    fun pauseSearching() {
        keyStoreManager.setSearchingEnabled(false)
        loopJob?.cancel()
        loopJob = null
        client.stop()
        _statusFlow.value = "Searching Paused"
        _isConnectedFlow.value = false
        notifyWidgetUpdate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    fun resumeSearching() {
        keyStoreManager.setSearchingEnabled(true)
        backoffMs = 1000L
        startConnectionLoop()
        notifyWidgetUpdate()
    }

    fun toggleSearching() {
        if (keyStoreManager.isSearchingEnabled()) {
            pauseSearching()
        } else {
            resumeSearching()
        }
    }

    fun notifyWidgetUpdate() {
        try {
            val intent = Intent(this, com.bridge.android.widget.BridgeWidgetProvider::class.java).apply {
                action = android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE
                val ids = android.appwidget.AppWidgetManager.getInstance(this@BridgeService)
                    .getAppWidgetIds(ComponentName(this@BridgeService, com.bridge.android.widget.BridgeWidgetProvider::class.java))
                putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    private fun handleConnectivityChange() {
        val networkAvailable = ConnectivityHelper.isLocalNetworkAvailable(this)
        LogManager.i("BridgeService: Connectivity change (localNetworkAvailable=$networkAvailable)")

        if (!networkAvailable) {
            loopJob?.cancel()
            loopJob = null
            client.stop()
            _isConnectedFlow.value = false
            val reason = "No Wi-Fi or Hotspot"
            _statusFlow.value = reason
            updateNotification(reason)
            notifyWidgetUpdate()
        } else {
            if (keyStoreManager.isSearchingEnabled() && !_isConnectedFlow.value && (loopJob == null || !loopJob!!.isActive)) {
                LogManager.i("BridgeService: Local network/hotspot is active. Starting connection loop.")
                startConnectionLoop()
            }
        }
    }

    private fun registerConnectivityReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
                addAction("android.net.conn.CONNECTIVITY_CHANGE")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(connectivityReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(connectivityReceiver, filter)
            }
        } catch (e: Exception) {
            LogManager.e("Failed to register connectivity receiver", e)
        }
    }

    private fun unregisterConnectivityReceiver() {
        try {
            unregisterReceiver(connectivityReceiver)
        } catch (_: Exception) {}
    }

    fun startConnectionLoop() {
        loopJob?.cancel()
        if (!keyStoreManager.isSearchingEnabled()) {
            _statusFlow.value = "Searching Paused"
            _isConnectedFlow.value = false
            notifyWidgetUpdate()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return
        }

        if (!ConnectivityHelper.isLocalNetworkAvailable(this)) {
            val reason = "No Wi-Fi or Hotspot"
            _statusFlow.value = reason
            _isConnectedFlow.value = false
            updateNotification(reason)
            notifyWidgetUpdate()
            return
        }

        loopJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && keyStoreManager.isSearchingEnabled() && ConnectivityHelper.isLocalNetworkAvailable(this@BridgeService)) {
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
                    _statusFlow.value = "Connecting to Mac…"
                    updateNotification("Connecting to Mac…")
                    LogManager.i("Attempting connection to last known IP: $lastIp")
                    sessionSucceeded = client.runSession(lastIp, key)
                }

                // 2. If last known IP failed or is not available, discover Mac
                if (!sessionSucceeded && isActive && keyStoreManager.isSearchingEnabled() && ConnectivityHelper.isLocalNetworkAvailable(this@BridgeService)) {
                    _statusFlow.value = "Searching for Mac…"
                    _isConnectedFlow.value = false
                    updateNotification("Searching for Mac…")
                    LogManager.i("Discovering Mac on network…")

                    val discoveredIp = discoveryEngine.discoverMacIp(keyStoreManager)
                    if (discoveredIp != null) {
                        keyStoreManager.saveLastKnownIp(discoveredIp)
                        _statusFlow.value = "Connecting to Mac…"
                        updateNotification("Connecting to Mac…")
                        sessionSucceeded = client.runSession(discoveredIp, key)
                    }
                }

                // 3. If still not connected, backoff before retry
                if (!sessionSucceeded && isActive && keyStoreManager.isSearchingEnabled() && ConnectivityHelper.isLocalNetworkAvailable(this@BridgeService)) {
                    _statusFlow.value = "Mac not found. Retrying in ${backoffMs / 1000}s…"
                    _isConnectedFlow.value = false
                    updateNotification("Mac not found. Retrying in ${backoffMs / 1000}s…")
                    delay(backoffMs)
                    // Gentler backoff: 2s -> 4s -> 6s -> 8s -> 10s -> 15s -> 20s -> 30s
                    if (backoffMs < 10000) {
                        backoffMs = minOf(backoffMs + 2000, 10000)
                    } else if (backoffMs < 20000) {
                        backoffMs = minOf(backoffMs + 5000, 20000)
                    } else {
                        backoffMs = 30000
                    }
                } else {
                    backoffMs = 2000L // Reset to 2s instead of 5s
                }
            }
        }
    }

    // MARK: - BridgeClientListener
    override fun onConnected(ip: String) {
        serviceScope.launch(Dispatchers.Main) {
            backoffMs = 1000L
            val macName = keyStoreManager.getMacName() ?: "Mac"
            _statusFlow.value = "Connected to $macName"
            _isConnectedFlow.value = true
            updateNotification("Connected to $macName")
            LogManager.i("BridgeService: Link established with $ip")
            sendLocalBatteryStatus()
            com.bridge.android.notification.BridgeNotificationListenerService.rebind(this@BridgeService)
            com.bridge.android.notification.NotificationSyncManager.flushOfflineQueue(client)
            notifyWidgetUpdate()
        }
    }

    override fun onDisconnected() {
        serviceScope.launch(Dispatchers.Main) {
            _isConnectedFlow.value = false
            _peerBatteryFlow.value = null
            _statusFlow.value = if (keyStoreManager.isSearchingEnabled()) "Disconnected" else "Searching Paused"
            updateNotification(_statusFlow.value)
            LogManager.i("BridgeService: Disconnected")
            notifyWidgetUpdate()
        }
    }

    override fun onUnpaired() {
        serviceScope.launch(Dispatchers.Main) {
            LogManager.i("BridgeService: Received unpaired signal from Mac. Clearing stored pairing.")
            keyStoreManager.clear()
            _statusFlow.value = "Unpaired by Mac (Scan QR Code)"
            _isConnectedFlow.value = false
            updateNotification("Device unpaired by Mac. Tap to pair.")
            notifyWidgetUpdate()
        }
    }

    override fun onRemoteDisconnected(reason: String) {
        serviceScope.launch(Dispatchers.Main) {
            LogManager.i("BridgeService: Disconnected by Mac ($reason)")
            _statusFlow.value = "Disconnected by Mac"
            _isConnectedFlow.value = false
            updateNotification("Disconnected by Mac")
            notifyWidgetUpdate()
        }
    }

    override fun onBatteryStatusReceived(level: Int, isCharging: Boolean) {
        serviceScope.launch(Dispatchers.Main) {
            _peerBatteryFlow.value = Pair(level, isCharging)
            LogManager.i("BridgeService: Received Mac battery status: $level%, isCharging=$isCharging")
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
            com.bridge.android.history.TransferHistoryManager(this@BridgeService).addTransfer(
                name = name,
                size = data.size.toLong(),
                isIncoming = true
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

    override fun onIconNeed(pkg: String) {
        serviceScope.launch(Dispatchers.IO) {
            com.bridge.android.notification.NotificationSyncManager.handleIconNeed(this@BridgeService, pkg, client)
        }
    }

    // MARK: - Network Callback (Instant Reconnect)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            LogManager.i("Network available: triggering instant reconnect")
            triggerReconnect()
        }

        override fun onLost(network: Network) {
            if (!ConnectivityHelper.isLocalNetworkAvailable(this@BridgeService)) {
                LogManager.i("Local network lost: resetting client")
                client.stop()
            } else {
                LogManager.d("Network lost but local network/hotspot is still active -> keeping connection intact")
            }
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

        val sendClipIntent = Intent(this, com.bridge.android.ui.TransparentClipReaderActivity::class.java).apply {
            putExtra("force", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        val sendClipPendingIntent = PendingIntent.getActivity(
            this,
            1,
            sendClipIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bridge")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_bridge_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (_isConnectedFlow.value) {
            builder.addAction(R.drawable.ic_clipboard, "Send to Mac", sendClipPendingIntent)
            val lockMacIntent = Intent(this, BridgeService::class.java).apply {
                action = ACTION_LOCK_MAC
            }
            val lockMacPendingIntent = PendingIntent.getService(
                this,
                3,
                lockMacIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(R.drawable.ic_lock, "Lock Mac", lockMacPendingIntent)
        }

        val isSearching = keyStoreManager.isSearchingEnabled()
        val toggleActionIntent = Intent(this, BridgeService::class.java).apply {
            action = if (isSearching) ACTION_STOP_SEARCH else ACTION_START_SEARCH
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            2,
            toggleActionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggleLabel = if (isSearching) "Pause Search" else "Resume Search"
        builder.addAction(R.drawable.circle_status, toggleLabel, togglePendingIntent)

        return builder.build()
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(statusText))
    }
}
