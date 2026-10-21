package com.bridge.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bridge.android.R
import com.bridge.android.crypto.KeyStoreManager
import com.bridge.android.databinding.ActivityMainBinding
import com.bridge.android.history.RecentNotificationHistoryManager
import com.bridge.android.history.TransferHistoryManager
import com.bridge.android.notification.NotificationPreferences
import com.bridge.android.protocol.BridgeProtocol
import com.bridge.android.service.BridgeAccessibilityService
import com.bridge.android.service.BridgeService
import com.bridge.android.util.LogManager
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import com.bridge.android.update.UpdateManager
import com.bridge.android.update.data.ManifestFetchResult
import com.bridge.android.update.installer.InstallationResult
import com.bridge.android.update.installer.UpdateInstaller
import com.bridge.android.update.model.UpdateManifest
import com.bridge.android.update.model.UpdateState
import com.bridge.android.update.notification.UpdateNotificationHelper

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var keyStoreManager: KeyStoreManager
    private lateinit var updateManager: UpdateManager
    private var pendingInstallApk: File? = null

    private val unknownSourcesLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apk = pendingInstallApk
        if (apk != null && UpdateInstaller.canInstallPackages(this)) {
            pendingInstallApk = null
            triggerInstall(apk)
        }
    }

    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) {
            sendSelectedFiles(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keyStoreManager = KeyStoreManager(this)
        updateManager = UpdateManager.getInstance(this)

        setupNavigation()
        setupListeners()
        setupUpdateListeners()
        observeServiceState()
        observeUpdateState()
        checkAndRequestNotificationPermission()

        BridgeService.start(this)

        // Handle opening directly from update notification
        if (intent?.getBooleanExtra(UpdateNotificationHelper.EXTRA_OPEN_UPDATE, false) == true) {
            handleNotificationLaunch()
        }

        // Check for updates on app launch in the background
        lifecycleScope.launch {
            updateManager.checkForUpdate(isManual = false)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(UpdateNotificationHelper.EXTRA_OPEN_UPDATE, false) == true) {
            handleNotificationLaunch()
        }
    }

    private fun handleNotificationLaunch() {
        val cached = updateManager.preferences.getCachedManifest()
        if (cached != null && cached.versionCode > updateManager.getCurrentVersionCode()) {
            showUpdateDialog(cached)
        }
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val service = BridgeService.instance
        if (service != null && service.isConnectedFlow.value) {
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            service.getClipboardSyncManager().sendLocalClipboardToMac(service.getClient(), clipManager)
        }
        updateClipboardPreview()
    }

    override fun onResume() {
        super.onResume()
        updateConnectionStatus()
        updateContinuityPills()
        updateSettingsValues()
        updateClipboardPreview()
        renderRecentTransfers()
        renderRecentNotifications()
        BridgeService.instance?.syncAutoModeState()
        try {
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipManager.addPrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            LogManager.e("Error registering clipListener in onResume", e)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            try {
                val service = BridgeService.instance
                if (service != null && service.isConnectedFlow.value) {
                    val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    service.getClipboardSyncManager().sendLocalClipboardToMac(service.getClient(), clipManager)
                }
            } catch (e: Exception) {
                LogManager.e("Error reading clipboard in onWindowFocusChanged", e)
            }
            updateClipboardPreview()
            updateContinuityPills()
            updateSettingsValues()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipManager.removePrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            LogManager.e("Error unregistering clipListener in onPause", e)
        }
    }

    private fun setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    binding.tabContentBridge.visibility = View.VISIBLE
                    binding.tabContentActivity.visibility = View.GONE
                    binding.tabContentSettings.visibility = View.GONE
                    updateConnectionStatus()
                    updateContinuityPills()
                    true
                }
                R.id.nav_activity -> {
                    binding.tabContentBridge.visibility = View.GONE
                    binding.tabContentActivity.visibility = View.VISIBLE
                    binding.tabContentSettings.visibility = View.GONE
                    renderRecentTransfers()
                    renderRecentNotifications()
                    true
                }
                R.id.nav_settings -> {
                    binding.tabContentBridge.visibility = View.GONE
                    binding.tabContentActivity.visibility = View.GONE
                    binding.tabContentSettings.visibility = View.VISIBLE
                    updateSettingsValues()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupListeners() {
        // Header Reconnect Button
        binding.btnHeaderReconnect.setOnClickListener {
            triggerReconnect()
        }

        // ==================== UNPAIRED HERO ONBOARDING ====================
        binding.btnPairScanQr.setOnClickListener {
            startQrScanner()
        }

        binding.btnPairEnterCode.setOnClickListener {
            showPastePairingDialog()
        }

        binding.btnPairManualIp.setOnClickListener {
            showManualIpDialog()
        }

        // ==================== PAIRED DASHBOARD ====================
        // Send Files to Mac
        binding.btnSendFileToMac.setOnClickListener {
            val service = BridgeService.instance
            if (service == null || !service.isConnectedFlow.value) {
                Toast.makeText(this, "Mac is not connected yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pickFilesLauncher.launch("*/*")
        }

        // Send Current Clipboard
        binding.btnSendClipboardToMac.setOnClickListener {
            val service = BridgeService.instance
            if (service == null || !service.isConnectedFlow.value) {
                Toast.makeText(this, "Mac is not connected yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val sent = service.getClipboardSyncManager().sendLocalClipboardToMac(
                service.getClient(),
                clipManager,
                force = true
            )
            if (sent) {
                Toast.makeText(this, "Clipboard sent to Mac ✓", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        // Lock Mac Screen Remotely
        binding.btnLockMac.setOnClickListener {
            val service = BridgeService.instance
            if (service == null || !service.isConnectedFlow.value) {
                Toast.makeText(this, "Mac is not connected yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val success = service.sendLockMac()
            if (success) {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                Toast.makeText(this, "Mac lock command sent ✓", Toast.LENGTH_SHORT).show()
            }
        }

        // Quick Reconnect on card
        binding.btnQuickReconnect.setOnClickListener {
            triggerReconnect()
        }

        // Active Discovery & Sync Toggle
        binding.switchSearchSync.setOnCheckedChangeListener { _, isChecked ->
            keyStoreManager.setSearchingEnabled(isChecked)
            val service = BridgeService.instance
            if (isChecked) {
                if (service != null) {
                    service.resumeSearching()
                } else {
                    BridgeService.start(this)
                }
                Toast.makeText(this, "Discovery & sync resumed", Toast.LENGTH_SHORT).show()
            } else {
                service?.pauseSearching()
                Toast.makeText(this, "Discovery & sync paused", Toast.LENGTH_SHORT).show()
            }
            updateConnectionStatus()
        }

        // Continuity Services Shortcuts on Dashboard
        binding.rowNotificationSync.setOnClickListener {
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }

        binding.rowClipboardSync.setOnClickListener {
            showClipboardOptionsDialog()
        }

        binding.rowBackgroundReliability.setOnClickListener {
            showReliabilityOptionsDialog()
        }

        // ==================== ACTIVITY TAB ====================
        binding.chipFilterAll.setOnClickListener {
            binding.sectionTransfers.visibility = View.VISIBLE
            binding.sectionNotifications.visibility = View.VISIBLE
        }

        binding.chipFilterTransfers.setOnClickListener {
            binding.sectionTransfers.visibility = View.VISIBLE
            binding.sectionNotifications.visibility = View.GONE
        }

        binding.chipFilterNotifications.setOnClickListener {
            binding.sectionTransfers.visibility = View.GONE
            binding.sectionNotifications.visibility = View.VISIBLE
        }

        // Clear Activity
        binding.btnClearAllHistory.setOnClickListener {
            val options = arrayOf("Clear All", "Clear Transfers Only", "Clear Notifications Only")
            AlertDialog.Builder(this)
                .setTitle("Clear History")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            TransferHistoryManager(this).clearTransfers()
                            RecentNotificationHistoryManager(this).clearNotifications()
                            Toast.makeText(this, "All history cleared", Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            TransferHistoryManager(this).clearTransfers()
                            Toast.makeText(this, "Transfer history cleared", Toast.LENGTH_SHORT).show()
                        }
                        2 -> {
                            RecentNotificationHistoryManager(this).clearNotifications()
                            Toast.makeText(this, "Notification history cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                    renderRecentTransfers()
                    renderRecentNotifications()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ==================== SETTINGS TAB ====================
        binding.rowSettingsScanQr.setOnClickListener {
            startQrScanner()
        }

        binding.rowSettingsPastePairing.setOnClickListener {
            showPastePairingDialog()
        }

        binding.rowSettingsManualIp.setOnClickListener {
            showManualIpDialog()
        }

        binding.rowSettingsNotifications.setOnClickListener {
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }

        binding.rowSettingsBatteryOpt.setOnClickListener {
            requestDisableBatteryOptimization()
        }

        binding.rowSettingsAutostart.setOnClickListener {
            showOemInstructionsDialog()
        }

        binding.rowSettingsAccessibilityService.setOnClickListener {
            openAccessibilitySettings()
        }

        binding.switchSettingsAutoMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED) {
                    keyStoreManager.setAutoModeEnabled(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                        binding.switchSettingsAutoMode.isChecked = false
                        showOverlayPermissionDialog()
                        BridgeService.instance?.syncAutoModeState()
                        return@setOnCheckedChangeListener
                    }
                    BridgeService.instance?.syncAutoModeState()
                    Toast.makeText(this, "Auto-sync mode enabled", Toast.LENGTH_SHORT).show()
                } else {
                    binding.switchSettingsAutoMode.isChecked = false
                    showAdbPermissionDialog()
                }
            } else {
                keyStoreManager.setAutoModeEnabled(false)
                BridgeService.instance?.syncAutoModeState()
                Toast.makeText(this, "Auto-sync mode disabled", Toast.LENGTH_SHORT).show()
            }
            updateContinuityPills()
        }

        binding.rowSettingsCopyLogs.setOnClickListener {
            val logs = LogManager.getDiagnostics()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Bridge Diagnostics", logs))
            Toast.makeText(this, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.rowSettingsUnpair.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Unpair Mac")
                .setMessage("Are you sure you want to unpair from this Mac? You will need to scan a new QR code to reconnect.")
                .setPositiveButton("Unpair") { _, _ ->
                    keyStoreManager.clear()
                    BridgeService.instance?.triggerReconnect()
                    updateConnectionStatus()
                    binding.bottomNav.selectedItemId = R.id.nav_home
                    Toast.makeText(this, "Unpaired from Mac", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Footer Social Links & Attribution
        setupFooterLinks()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupFooterLinks() {
        val websiteUrl = "https://www.satyammohanty.com"
        val xUrl = "https://x.com/isatymm"
        val linkedinUrl = "https://www.linkedin.com/in/satyam-mohanty"
        val githubUrl = "https://github.com/satyam-mohanty"
        val instagramUrl = "https://www.instagram.com/isatymm"

        // Dashboard footer
        binding.btnSocialX.setOnClickListener { openUrl(xUrl) }
        binding.btnSocialLinkedin.setOnClickListener { openUrl(linkedinUrl) }
        binding.btnSocialGithub.setOnClickListener { openUrl(githubUrl) }
        binding.btnSocialInstagram.setOnClickListener { openUrl(instagramUrl) }
        binding.btnAuthorName.setOnClickListener { openUrl(websiteUrl) }

        // Settings footer
        binding.btnSocialXSettings.setOnClickListener { openUrl(xUrl) }
        binding.btnSocialLinkedinSettings.setOnClickListener { openUrl(linkedinUrl) }
        binding.btnSocialGithubSettings.setOnClickListener { openUrl(githubUrl) }
        binding.btnSocialInstagramSettings.setOnClickListener { openUrl(instagramUrl) }
        binding.btnAuthorNameSettings.setOnClickListener { openUrl(websiteUrl) }
    }

    private fun setupUpdateListeners() {
        binding.btnUpdateBannerDismiss.setOnClickListener {
            val state = updateManager.updateState.value
            if (state is UpdateState.UpdateAvailable) {
                updateManager.dismissUpdate(state.manifest.versionCode)
            } else {
                binding.cardUpdateBanner.visibility = View.GONE
            }
        }

        binding.rowSettingsCheckUpdates.setOnClickListener {
            performManualUpdateCheck()
        }

        binding.switchSettingsUpdateNotifications.setOnCheckedChangeListener { _, isChecked ->
            updateManager.preferences.isNotificationEnabled = isChecked
        }
    }

    private fun observeUpdateState() {
        lifecycleScope.launch {
            updateManager.updateState.collectLatest { state ->
                when (state) {
                    is UpdateState.Idle -> {
                        binding.cardUpdateBanner.visibility = View.GONE
                        binding.layoutUpdateProgress.visibility = View.GONE
                    }
                    is UpdateState.Checking -> {
                        binding.tvSettingsUpdateStatus.text = "Checking…"
                        binding.tvSettingsUpdateStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    }
                    is UpdateState.UpdateAvailable -> {
                        val manifest = state.manifest
                        if (manifest.versionCode > updateManager.preferences.dismissedVersionCode) {
                            binding.cardUpdateBanner.visibility = View.VISIBLE
                            binding.tvUpdateBannerTitle.text = "Update Available: v${manifest.versionName}"
                            binding.tvUpdateBannerNotes.text = manifest.releaseNotes
                            binding.layoutUpdateProgress.visibility = View.GONE
                            binding.btnUpdateBannerAction.text = "Download & Install"
                            binding.btnUpdateBannerAction.isEnabled = true
                            binding.btnUpdateBannerAction.setOnClickListener {
                                updateManager.startDownload(manifest)
                            }
                        }
                        binding.tvSettingsUpdateStatus.text = "v${manifest.versionName} Available"
                        binding.tvSettingsUpdateStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    }
                    is UpdateState.Downloading -> {
                        binding.cardUpdateBanner.visibility = View.VISIBLE
                        binding.layoutUpdateProgress.visibility = View.VISIBLE
                        binding.progressUpdateDownload.isIndeterminate = state.progressPercent <= 0
                        if (state.progressPercent > 0) {
                            binding.progressUpdateDownload.progress = state.progressPercent
                        }
                        val mbDownloaded = state.bytesDownloaded.toDouble() / (1024 * 1024)
                        val mbTotal = state.totalBytes.toDouble() / (1024 * 1024)
                        binding.tvUpdateProgressStatus.text = if (state.totalBytes > 0) {
                            String.format("Downloading... %d%% (%.1f MB / %.1f MB)", state.progressPercent, mbDownloaded, mbTotal)
                        } else {
                            String.format("Downloading... (%.1f MB)", mbDownloaded)
                        }
                        binding.btnUpdateBannerAction.isEnabled = false
                        binding.btnUpdateBannerAction.text = "Downloading…"
                    }
                    is UpdateState.ReadyToInstall -> {
                        binding.cardUpdateBanner.visibility = View.VISIBLE
                        binding.layoutUpdateProgress.visibility = View.GONE
                        binding.btnUpdateBannerAction.isEnabled = true
                        binding.btnUpdateBannerAction.text = "Install Update"
                        binding.btnUpdateBannerAction.setOnClickListener {
                            triggerInstall(state.apkFile)
                        }
                        binding.tvSettingsUpdateStatus.text = "Ready to Install"
                        binding.tvSettingsUpdateStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                    }
                    is UpdateState.UpToDate -> {
                        binding.cardUpdateBanner.visibility = View.GONE
                        binding.tvSettingsUpdateStatus.text = "Latest ✓"
                        binding.tvSettingsUpdateStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.status_green))
                    }
                    is UpdateState.Error -> {
                        binding.layoutUpdateProgress.visibility = View.GONE
                        binding.btnUpdateBannerAction.isEnabled = true
                        binding.btnUpdateBannerAction.text = "Retry"
                        binding.btnUpdateBannerAction.setOnClickListener {
                            val cached = updateManager.preferences.getCachedManifest()
                            if (cached != null) updateManager.startDownload(cached)
                            else performManualUpdateCheck()
                        }
                        binding.tvSettingsUpdateStatus.text = "Check"
                        binding.tvSettingsUpdateStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    }
                }
            }
        }
    }

    private fun performManualUpdateCheck() {
        binding.tvSettingsUpdateStatus.text = "Checking…"
        lifecycleScope.launch {
            val result = updateManager.checkForUpdate(isManual = true)
            when (result) {
                is ManifestFetchResult.Success -> {
                    val manifest = result.manifest
                    if (manifest.versionCode > updateManager.getCurrentVersionCode()) {
                        showUpdateDialog(manifest)
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Bridge is up to date (Version ${updateManager.getCurrentVersionName()})",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                is ManifestFetchResult.Error -> {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Update Check")
                        .setMessage(result.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun showUpdateDialog(manifest: UpdateManifest) {
        val sizeMb = manifest.apkSizeBytes.toDouble() / (1024 * 1024)
        val sizeStr = if (sizeMb > 0) String.format(" (%.1f MB)", sizeMb) else ""
        AlertDialog.Builder(this)
            .setTitle("Bridge ${manifest.versionName} Available")
            .setMessage("Release Notes:\n${manifest.releaseNotes.ifBlank { "Performance updates and security improvements." }}\n\nSize$sizeStr")
            .setPositiveButton("Download & Install") { _, _ ->
                binding.bottomNav.selectedItemId = R.id.nav_home
                updateManager.startDownload(manifest)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun triggerInstall(apkFile: File) {
        if (!UpdateInstaller.canInstallPackages(this)) {
            pendingInstallApk = apkFile
            showUnknownSourcesDialog()
            return
        }

        when (val result = updateManager.installUpdate(this, apkFile)) {
            is InstallationResult.Success -> {
                LogManager.i("Started APK installation flow")
            }
            is InstallationResult.RequiresPermission -> {
                pendingInstallApk = apkFile
                showUnknownSourcesDialog()
            }
            is InstallationResult.Error -> {
                AlertDialog.Builder(this)
                    .setTitle("Installation Error")
                    .setMessage(result.message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showUnknownSourcesDialog() {
        AlertDialog.Builder(this)
            .setTitle("Allow Bridge to Install Updates")
            .setMessage("Android requires permission to install updates from within Bridge. Please enable 'Allow from this source' on the next screen.")
            .setPositiveButton("Open Settings") { _, _ ->
                val intent = UpdateInstaller.getUnknownSourcesPermissionIntent(this)
                try {
                    unknownSourcesLauncher.launch(intent)
                } catch (e: Exception) {
                    startActivity(intent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun triggerReconnect() {
        BridgeService.instance?.triggerReconnect()
        Toast.makeText(this, "Reconnecting…", Toast.LENGTH_SHORT).show()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        try {
            startActivity(intent)
            Toast.makeText(this, "Find 'Bridge' under Downloaded apps and turn it ON", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            LogManager.e("Failed to open accessibility settings", e)
        }
    }

    private fun showClipboardOptionsDialog() {
        val isA11y = isAccessibilityServiceEnabled()
        val options = arrayOf(
            if (isA11y) "Accessibility Service (Active ✓)" else "Enable Real-Time Sync (Accessibility)",
            "Setup Foreground Auto-Sync (via ADB)",
            "Cancel"
        )

        AlertDialog.Builder(this)
            .setTitle("Universal Clipboard Setup")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> openAccessibilitySettings()
                    1 -> showAdbPermissionDialog()
                    else -> dialog.dismiss()
                }
            }
            .show()
    }

    private fun showReliabilityOptionsDialog() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
        val options = arrayOf(
            if (isIgnoring) "Battery Optimization (Disabled ✓)" else "Disable Battery Optimization",
            "OEM Background Autostart Guides",
            "Cancel"
        )

        AlertDialog.Builder(this)
            .setTitle("Background Reliability")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> requestDisableBatteryOptimization()
                    1 -> showOemInstructionsDialog()
                    else -> dialog.dismiss()
                }
            }
            .show()
    }

    private fun updateClipboardPreview() {
        try {
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim()
                if (!text.isNullOrBlank()) {
                    val preview = if (text.length > 32) text.take(32) + "…" else text
                    binding.tvClipboardPreview.text = "“$preview”"
                    return
                }
            }
            binding.tvClipboardPreview.text = "Push copied content"
        } catch (_: Exception) {
            binding.tvClipboardPreview.text = "Push copied content"
        }
    }

    private fun updateContinuityPills() {
        val secondaryColor = ContextCompat.getColor(this, R.color.text_secondary)
        val orangeColor = ContextCompat.getColor(this, R.color.status_orange)

        // Notification status text
        val notifPrefs = NotificationPreferences(this)
        val notifCount = notifPrefs.getEnabledPackages().size
        binding.tvNotificationStatusPill.background = null
        if (notifPrefs.isSyncEnabled) {
            binding.tvNotificationStatusPill.text = if (notifCount == 1) "1 app" else "$notifCount apps"
            binding.tvNotificationStatusPill.setTextColor(secondaryColor)
        } else {
            binding.tvNotificationStatusPill.text = "Off"
            binding.tvNotificationStatusPill.setTextColor(secondaryColor)
        }

        // Clipboard status text
        val isA11y = isAccessibilityServiceEnabled()
        val isAuto = keyStoreManager.isAutoModeEnabled() && checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED
        binding.tvClipboardStatusPill.background = null
        if (isA11y) {
            binding.tvClipboardStatusPill.text = "Real-time"
            binding.tvClipboardStatusPill.setTextColor(secondaryColor)
        } else if (isAuto) {
            binding.tvClipboardStatusPill.text = "Foreground"
            binding.tvClipboardStatusPill.setTextColor(secondaryColor)
        } else {
            binding.tvClipboardStatusPill.text = "Manual"
            binding.tvClipboardStatusPill.setTextColor(secondaryColor)
        }

        // Battery status text
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
        binding.tvBatteryStatusPill.background = null
        if (isIgnoring) {
            binding.tvBatteryStatusPill.text = "Optimized"
            binding.tvBatteryStatusPill.setTextColor(secondaryColor)
        } else {
            binding.tvBatteryStatusPill.text = "Restricted"
            binding.tvBatteryStatusPill.setTextColor(orangeColor)
        }
    }

    private fun updateSettingsValues() {
        val currentIp = keyStoreManager.getLastKnownIp()
        binding.tvSettingsCurrentIp.text = if (currentIp.isNullOrBlank()) "Auto" else currentIp

        val notifPrefs = NotificationPreferences(this)
        val notifCount = notifPrefs.getEnabledPackages().size
        binding.tvSettingsNotificationStatus.text = if (notifPrefs.isSyncEnabled) "$notifCount active" else "Off"

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBattery = pm.isIgnoringBatteryOptimizations(packageName)
        if (isIgnoringBattery) {
            binding.tvSettingsBatteryOptStatus.text = "Disabled ✓"
            binding.tvSettingsBatteryOptStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            binding.tvSettingsBatteryOptStatus.text = "Enable"
            binding.tvSettingsBatteryOptStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
        }

        val isA11yEnabled = isAccessibilityServiceEnabled()
        if (isA11yEnabled) {
            binding.tvSettingsAccessibilityStatus.text = "Active ✓"
            binding.tvSettingsAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            binding.tvSettingsAccessibilityStatus.text = "Enable"
            binding.tvSettingsAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
        }

        val hasReadLogs = checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED
        val canLaunchReader = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        binding.switchSettingsAutoMode.isChecked = keyStoreManager.isAutoModeEnabled() && hasReadLogs && canLaunchReader

        // Updates
        binding.tvSettingsVersionCode.text = "Version ${updateManager.getCurrentVersionName()} (Build ${updateManager.getCurrentVersionCode()})"
        binding.switchSettingsUpdateNotifications.isChecked = updateManager.preferences.isNotificationEnabled
    }

    private fun sendSelectedFiles(uris: List<Uri>) {
        val service = BridgeService.instance ?: return
        Toast.makeText(this, "Sending ${uris.size} file(s) to Mac…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            for (uri in uris) {
                try {
                    var fileName = "file"
                    val mime = contentResolver.getType(uri) ?: "application/octet-stream"

                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            val name = cursor.getString(nameIndex)
                            if (!name.isNullOrBlank()) fileName = name
                        }
                    }

                    if (fileName == "file" && uri.lastPathSegment != null) {
                        fileName = File(uri.lastPathSegment ?: "").name
                    }

                    val bytes = try {
                        contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    } catch (e: SecurityException) {
                        contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
                        }
                    }
                    if (bytes != null && bytes.isNotEmpty()) {
                        service.sendFile(fileName, bytes, mime)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Sent $fileName to Mac ✓", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    LogManager.e("Failed to send selected file", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to send: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            while (true) {
                updateConnectionStatus()
                updateContinuityPills()
                kotlinx.coroutines.delay(1000)
            }
        }

        // Active File Transfer Progress
        lifecycleScope.launch {
            val service = BridgeService.instance
            service?.transferFlow?.collectLatest { transfer ->
                if (transfer != null && !transfer.isCompleted) {
                    binding.cardActiveTransfer.visibility = View.VISIBLE
                    val action = if (transfer.isIncoming) "Receiving" else "Sending"
                    val pct = (transfer.progress * 100).toInt()
                    binding.tvTransferLabel.text = "$action ${transfer.fileName} • $pct%"
                    binding.progressBarTransfer.progress = pct
                } else if (transfer != null && transfer.isCompleted) {
                    binding.cardActiveTransfer.visibility = View.VISIBLE
                    val action = if (transfer.isIncoming) "Received" else "Sent"
                    binding.tvTransferLabel.text = "$action ${transfer.fileName} ✓"
                    binding.progressBarTransfer.progress = 100
                    kotlinx.coroutines.delay(2500)
                    binding.cardActiveTransfer.visibility = View.GONE
                } else {
                    binding.cardActiveTransfer.visibility = View.GONE
                }
            }
        }

        // Last Received File
        lifecycleScope.launch {
            val service = BridgeService.instance
            service?.lastReceivedFileFlow?.collectLatest { fileName ->
                if (!fileName.isNullOrBlank()) {
                    binding.tvLastReceivedInfo.text = "✓ Last received: $fileName (saved in Downloads)"
                    renderRecentTransfers()
                }
            }
        }

        // Periodic refresh of recent lists
        lifecycleScope.launch {
            while (true) {
                if (binding.tabContentActivity.visibility == View.VISIBLE) {
                    renderRecentTransfers()
                    renderRecentNotifications()
                }
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    private fun updateConnectionStatus() {
        val service = BridgeService.instance
        val isSearching = keyStoreManager.isSearchingEnabled()
        val isConnected = service?.isConnectedFlow?.value == true
        val hasKey = keyStoreManager.hasKey()
        val macName = keyStoreManager.getMacName() ?: "Mac"

        val statusText = when {
            !hasKey -> "Not Paired"
            !isSearching -> "Paused"
            isConnected -> service?.statusFlow?.value ?: "Connected"
            else -> service?.statusFlow?.value ?: "Searching for Mac…"
        }

        // Top Status Dot and indicator styling
        val topDot = binding.topStatusDot.background as? GradientDrawable
        val cardDot = binding.statusIndicator.background as? GradientDrawable

        if (!hasKey) {
            // UNPAIRED STATE
            binding.cardUnpaired.visibility = View.VISIBLE
            binding.layoutPaired.visibility = View.GONE

            binding.tvTopStatusText.text = "Not Paired"
            binding.tvTopStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            topDot?.setColor(ContextCompat.getColor(this, R.color.status_red))
            binding.btnHeaderReconnect.visibility = View.GONE
        } else {
            // PAIRED STATE
            binding.cardUnpaired.visibility = View.GONE
            binding.layoutPaired.visibility = View.VISIBLE

            binding.tvDeviceName.text = macName
            binding.tvConnectionStatus.text = statusText
            if (binding.switchSearchSync.isChecked != isSearching) {
                binding.switchSearchSync.isChecked = isSearching
            }

            if (!isSearching) {
                topDot?.setColor(ContextCompat.getColor(this, R.color.status_gray))
                cardDot?.setColor(ContextCompat.getColor(this, R.color.status_gray))
                binding.tvTopStatusText.text = "Paused"
                binding.tvTopStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.tvSecurityBadge.text = "Discovery paused"
                binding.btnQuickReconnect.visibility = View.GONE
                binding.btnHeaderReconnect.visibility = View.GONE
            } else if (isConnected) {
                topDot?.setColor(ContextCompat.getColor(this, R.color.status_green))
                cardDot?.setColor(ContextCompat.getColor(this, R.color.status_green))
                binding.tvTopStatusText.text = "Connected"
                binding.tvTopStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.tvSecurityBadge.text = "Encrypted • Port 48250"
                binding.btnQuickReconnect.visibility = View.GONE
                binding.btnHeaderReconnect.visibility = View.GONE
            } else {
                topDot?.setColor(ContextCompat.getColor(this, R.color.status_orange))
                cardDot?.setColor(ContextCompat.getColor(this, R.color.status_orange))
                binding.tvTopStatusText.text = "Searching…"
                binding.tvTopStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.tvSecurityBadge.text = "Searching on local network"
                binding.btnQuickReconnect.visibility = View.VISIBLE
                binding.btnHeaderReconnect.visibility = View.VISIBLE
            }
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun renderRecentTransfers() {
        val container = binding.llRecentTransfersContainer
        container.removeAllViews()
        val items = TransferHistoryManager(this).getTransfers()

        if (items.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No transfers yet"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            }
            container.addView(emptyView)
            return
        }

        for ((index, item) in items.take(15).withIndex()) {
            if (index > 0) {
                val divider = View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply {
                        topMargin = 10.dp
                        bottomMargin = 10.dp
                    }
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.card_stroke))
                }
                container.addView(divider)
            }

            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val icon = android.widget.ImageView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(24.dp, 24.dp).apply { marginEnd = 12.dp }
                setImageResource(R.drawable.ic_file_upload)
                rotation = if (item.isIncoming) 180f else 0f
                setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.accent))
            }
            row.addView(icon)

            val textCol = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameView = TextView(this).apply {
                text = item.name
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            }
            textCol.addView(nameView)

            val metaView = TextView(this).apply {
                val action = if (item.isIncoming) "Received" else "Sent"
                val sizeStr = android.text.format.Formatter.formatFileSize(this@MainActivity, item.size)
                val timeStr = android.text.format.DateUtils.getRelativeTimeSpanString(
                    item.timestamp, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
                )
                text = "$action • $sizeStr • $timeStr"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            }
            textCol.addView(metaView)

            row.addView(textCol)
            container.addView(row)
        }
    }

    private fun renderRecentNotifications() {
        val container = binding.llRecentNotificationsContainer
        container.removeAllViews()
        val items = RecentNotificationHistoryManager(this).getNotifications()

        if (items.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No recent notifications forwarded"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            }
            container.addView(emptyView)
            return
        }

        val pm = packageManager
        for ((index, item) in items.take(15).withIndex()) {
            if (index > 0) {
                val divider = View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply {
                        topMargin = 10.dp
                        bottomMargin = 10.dp
                    }
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.card_stroke))
                }
                container.addView(divider)
            }

            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val icon = android.widget.ImageView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(28.dp, 28.dp).apply { marginEnd = 12.dp }
                try {
                    val appIcon = pm.getApplicationIcon(item.pkg)
                    setImageDrawable(appIcon)
                } catch (_: Exception) {
                    setImageResource(R.drawable.ic_bridge_notification)
                }
            }
            row.addView(icon)

            val textCol = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleRow = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val appNameView = TextView(this).apply {
                text = item.app.ifEmpty { "Notification" }
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            titleRow.addView(appNameView)

            val timeView = TextView(this).apply {
                val timeStr = android.text.format.DateUtils.getRelativeTimeSpanString(
                    item.timestamp, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
                )
                text = timeStr
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            }
            titleRow.addView(timeView)
            textCol.addView(titleRow)

            if (item.title.isNotEmpty()) {
                val titleView = TextView(this).apply {
                    text = item.title
                    textSize = 13f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                }
                textCol.addView(titleView)
            }

            if (item.text.isNotEmpty()) {
                val previewView = TextView(this).apply {
                    text = item.text
                    textSize = 12f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                }
                textCol.addView(previewView)
            }

            row.addView(textCol)
            container.addView(row)
        }
    }

    private fun startQrScanner() {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val rawValue = barcode.rawValue
                if (rawValue != null) {
                    processPairingPayload(rawValue)
                } else {
                    Toast.makeText(this, "Empty QR code scanned", Toast.LENGTH_SHORT).show()
                }
            }
            .addOnFailureListener { e ->
                LogManager.e("Barcode scanner failed", e)
                Toast.makeText(this, "Scanner error: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun processPairingPayload(payload: String) {
        try {
            val pairing = BridgeProtocol.PairingInfo.fromJson(payload)
            val keyBytes = Base64.decode(pairing.key, Base64.DEFAULT)
            if (keyBytes.size != 32) {
                Toast.makeText(this, "Invalid key length in QR code (${keyBytes.size} bytes)", Toast.LENGTH_LONG).show()
                return
            }

            keyStoreManager.saveSharedKey(keyBytes, pairing.name)
            Toast.makeText(this, "Paired successfully with ${pairing.name}!", Toast.LENGTH_SHORT).show()
            LogManager.i("Paired with ${pairing.name}. Triggering reconnect.")
            BridgeService.start(this)
            BridgeService.instance?.triggerReconnect()
            updateConnectionStatus()
            updateContinuityPills()
            binding.bottomNav.selectedItemId = R.id.nav_home
        } catch (e: Exception) {
            LogManager.e("Failed to process pairing payload", e)
            Toast.makeText(this, "Invalid Bridge pairing code format", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPastePairingDialog() {
        val input = EditText(this).apply {
            hint = "Paste pairing JSON or base64 key here"
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.btn_paste_pairing)
            .setMessage("Paste the pairing code copied from your Mac:")
            .setView(input)
            .setPositiveButton("Pair") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    processPairingPayload(text)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualIpDialog() {
        val currentIp = keyStoreManager.getLastKnownIp() ?: ""
        val input = EditText(this).apply {
            hint = "e.g. 192.168.1.100"
            setText(currentIp)
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.btn_manual_ip)
            .setMessage("Optionally set Mac LAN IP directly (useful if Wi-Fi blocks broadcast discovery):")
            .setView(input)
            .setPositiveButton("Save & Connect") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    keyStoreManager.saveLastKnownIp(ip)
                    BridgeService.instance?.triggerReconnect()
                    updateSettingsValues()
                    Toast.makeText(this, "Saved IP: $ip", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Clear IP") { _, _ ->
                keyStoreManager.saveLastKnownIp("")
                BridgeService.instance?.triggerReconnect()
                updateSettingsValues()
                Toast.makeText(this, "Cleared manual IP", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestDisableBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open battery settings directly", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Battery optimization is already disabled for Bridge", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showOemInstructionsDialog() {
        val message = """
            To ensure Android keeps Bridge connected in the background:
            
            • Samsung (One UI):
              Settings → Battery → Background usage limits → Never sleeping apps → Add Bridge.
              Also disable 'Put unused apps to sleep'.
              
            • Xiaomi / Redmi / POCO (MIUI / HyperOS):
              Settings → Apps → Manage Apps → Bridge → Enable 'Autostart'.
              Battery Saver → Select 'No restrictions'.
              
            • OnePlus / Oppo / Realme (OxygenOS / ColorOS):
              App Info → Battery usage → Allow background activity + Allow auto-launch.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("OEM Background Instructions")
            .setMessage(message)
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun showAdbPermissionDialog() {
        val message = """
            Android blocks background apps from reading the clipboard unless triggered by user action (Quick Settings Tile or Share Sheet).
            
            To enable 100% automatic phone-to-Mac sync without tapping anything, connect your phone to your Mac once and run:
            
            adb shell pm grant com.bridge.android android.permission.READ_LOGS
            
            Once granted, flip this switch on!
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Grant READ_LOGS via ADB")
            .setMessage(message)
            .setPositiveButton("Copy ADB Command") { _, _ ->
                val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clip.setPrimaryClip(ClipData.newPlainText("adb command", "adb shell pm grant com.bridge.android android.permission.READ_LOGS"))
                Toast.makeText(this, "Command copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Allow automatic clipboard capture")
            .setMessage("Android only lets Bridge read clipboard contents while it is visible. Allow “Display over other apps” so Bridge can briefly open its transparent reader after a clipboard change.")
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    LogManager.e("Could not open overlay permission settings", e)
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (BridgeAccessibilityService.isRunning) {
            return true
        }
        val expectedComponentName = ComponentName(this, BridgeAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val componentName = colonSplitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }
}
