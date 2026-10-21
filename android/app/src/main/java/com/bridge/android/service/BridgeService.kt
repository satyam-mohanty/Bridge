package com.bridge.android.service

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
        const val ACTION_TOGGLE_SEARCH = "com.bridge.android.action.TOGGLE_SEARCH"
        const val ACTION_STOP_SEARCH = "com.bridge.android.action.STOP_SEARCH"
        const val ACTION_START_SEARCH = "com.bridge.android.action.START_SEARCH"
        const val ACTION_LOCK_MAC = "com.bridge.android.action.LOCK_MAC"

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
        registerClipboardListeners()
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

    override fun onDestroy() {
        instance = null
        com.bridge.android.notification.CallSyncManager.unregister(this)
        unregisterClipboardListeners()
        LogcatWatcherService.stopWatching()
        unregisterNetworkCallback()
        loopJob?.cancel()
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

    fun handleClipboardChanged(force: Boolean = false) {
        if (!_isConnectedFlow.value) return

        // 1. First attempt: Direct read via BridgeAccessibilityService context
        val a11y = BridgeAccessibilityService.instance
        if (a11y != null && BridgeAccessibilityService.isRunning) {
            try {
                val clipManager = a11y.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val sent = clipboardSyncManager.sendLocalClipboardToMac(client, clipManager, force = force, activeContext = a11y)
                if (sent) {
                    LogManager.i("Direct read via BridgeAccessibilityService SUCCEEDED")
                    return
                }
            } catch (e: Throwable) {
                LogManager.d("Direct read via a11y failed: ${e.message}")
            }
        }

        // 2. Direct read from BridgeService (succeeds if application is in focus)
        try {
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val sent = clipboardSyncManager.sendLocalClipboardToMac(client, clipManager, force = force, activeContext = this)
            if (sent) return
        } catch (_: Throwable) {}

        // 3. Launch TransparentClipReaderActivity to gain momentary focus and capture the clip cleanly
        triggerTransparentClipReader(force = force)
    }

    fun triggerTransparentClipReader(force: Boolean = false) {
        if (!_isConnectedFlow.value) return
        try {
            val a11y = BridgeAccessibilityService.instance
            val launchContext: Context = if (a11y != null && BridgeAccessibilityService.isRunning) a11y else this
            val intent = Intent(launchContext, com.bridge.android.ui.TransparentClipReaderActivity::class.java).apply {
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

            // 1. If AccessibilityService is connected and active, launch directly through its system context
            if (a11y != null && BridgeAccessibilityService.isRunning) {
                try {
                    a11y.startActivity(intent, options.toBundle())
                    LogManager.i("Triggered TransparentClipReaderActivity via A11y (force=$force)")
                    return
                } catch (e: Throwable) {
                    LogManager.d("Direct launch via A11y failed: ${e.message}")
                }
            }

            // 2. PendingIntent dispatch with background activity permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    val pi = PendingIntent.getActivity(
                        launchContext,
                        (System.currentTimeMillis() % 10000).toInt(),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        options.toBundle()
                    )
                    pi.send(launchContext, 0, null, null, null, null, options.toBundle())
                    LogManager.i("Triggered TransparentClipReaderActivity via PendingIntent (force=$force)")
                    return
                } catch (e: Throwable) {
                    LogManager.d("PendingIntent launch failed: ${e.message}")
                }
            }

            // 3. Direct launch fallback
            launchContext.startActivity(intent, options.toBundle())
            LogManager.i("Triggered TransparentClipReaderActivity via Context (force=$force)")
        } catch (e: Exception) {
            LogManager.e("Error triggering TransparentClipReaderActivity", e)
        }
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

    fun getClient(): BridgeClient = client
    fun getClipboardSyncManager(): ClipboardSyncManager = clipboardSyncManager

    fun pauseSearching() {
        keyStoreManager.setSearchingEnabled(false)
        loopJob?.cancel()
        client.stop()
        _statusFlow.value = "Searching Paused"
        _isConnectedFlow.value = false
        updateNotification("Searching Paused")
        notifyWidgetUpdate()
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

    fun startConnectionLoop() {
        loopJob?.cancel()
        if (!keyStoreManager.isSearchingEnabled()) {
            _statusFlow.value = "Searching Paused"
            _isConnectedFlow.value = false
            updateNotification("Searching Paused")
            notifyWidgetUpdate()
            return
        }
        loopJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive && keyStoreManager.isSearchingEnabled()) {
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
                if (!sessionSucceeded && isActive && keyStoreManager.isSearchingEnabled()) {
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
                if (!sessionSucceeded && isActive && keyStoreManager.isSearchingEnabled()) {
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
            _statusFlow.value = "Connected to $macName"
            _isConnectedFlow.value = true
            updateNotification("Connected to $macName")
            LogManager.i("BridgeService: Link established with $ip")
            syncAutoModeState()
            com.bridge.android.notification.BridgeNotificationListenerService.rebind(this@BridgeService)
            com.bridge.android.notification.NotificationSyncManager.flushOfflineQueue(client)
            notifyWidgetUpdate()
        }
    }

    override fun onDisconnected() {
        serviceScope.launch(Dispatchers.Main) {
            _isConnectedFlow.value = false
            _statusFlow.value = if (keyStoreManager.isSearchingEnabled()) "Disconnected" else "Searching Paused"
            updateNotification(_statusFlow.value)
            LogManager.i("BridgeService: Disconnected")
            notifyWidgetUpdate()
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
