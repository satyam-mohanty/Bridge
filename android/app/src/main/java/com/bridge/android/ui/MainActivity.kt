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
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.bridge.android.R
import com.bridge.android.crypto.KeyStoreManager
import com.bridge.android.databinding.ActivityMainBinding
import com.bridge.android.history.ClipboardHistoryItem
import com.bridge.android.history.ClipboardHistoryManager
import com.bridge.android.history.RecentNotificationHistoryManager
import com.bridge.android.history.TransferHistoryManager
import com.bridge.android.history.TransferItem
import com.bridge.android.notification.NotificationPreferences
import com.bridge.android.service.BridgeService
import com.bridge.android.update.UpdateManager
import com.bridge.android.update.data.ManifestFetchResult
import com.bridge.android.update.installer.InstallationResult
import com.bridge.android.update.installer.UpdateInstaller
import com.bridge.android.update.model.UpdateManifest
import com.bridge.android.update.model.UpdateState
import com.bridge.android.update.notification.UpdateNotificationHelper
import com.bridge.android.util.ConnectivityHelper
import com.bridge.android.util.LogManager
import com.bridge.android.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var keyStoreManager: KeyStoreManager
    private lateinit var updateManager: UpdateManager
    private lateinit var clipboardHistoryManager: ClipboardHistoryManager
    private lateinit var transferHistoryManager: TransferHistoryManager
    private lateinit var notificationHistoryManager: RecentNotificationHistoryManager

    private var currentActivityFilter = FilterMode.ALL
    private var pendingInstallApk: File? = null

    private enum class FilterMode {
        ALL, SENT, RECEIVED
    }

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
        ThemeManager.applyTheme(this)

        // Edge-to-edge transparent system bars (Apple style)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        insetsController.isAppearanceLightStatusBars = !isNight
        insetsController.isAppearanceLightNavigationBars = !isNight

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInset = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val density = resources.displayMetrics.density

            // Push content below the status bar
            binding.viewStatusBarSpacer.layoutParams = binding.viewStatusBarSpacer.layoutParams.apply {
                height = statusBarInset.top
            }

            // Dock floats 6dp above gesture bar
            val dockExtraMargin = (6 * density).toInt()
            val cardParams = binding.cardFloatingNav.layoutParams as? FrameLayout.LayoutParams
            if (cardParams != null) {
                cardParams.bottomMargin = navBarInset.bottom + dockExtraMargin
                binding.cardFloatingNav.layoutParams = cardParams
            }

            // Spacer at bottom of LinearLayout = dock height (68dp) + dock bottom margin + gesture bar height
            // This prevents content from ever going behind the floating dock
            val dockHeightPx = (68 * density).toInt()
            val spacerHeight = dockHeightPx + dockExtraMargin + navBarInset.bottom
            binding.viewNavSpacer.layoutParams = binding.viewNavSpacer.layoutParams.apply {
                height = spacerHeight
            }

            insets
        }

        keyStoreManager = KeyStoreManager(this)
        updateManager = UpdateManager.getInstance(this)
        clipboardHistoryManager = ClipboardHistoryManager(this)
        transferHistoryManager = TransferHistoryManager(this)
        notificationHistoryManager = RecentNotificationHistoryManager(this)

        setupNavigation()
        setupListeners()
        observeServiceState()
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
            service.getClipboardSyncManager().sendLocalClipboardToMac(
                service.getClient(),
                clipManager,
                activeContext = this
            )
        }
        updateClipboardPreview()
    }

    override fun onResume() {
        super.onResume()
        updateConnectionStatus()
        updateContinuityPills()
        updateSettingsValues()
        updateClipboardPreview()
        renderActivityList()
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
                    service.getClipboardSyncManager().sendLocalClipboardToMac(
                        service.getClient(),
                        clipManager,
                        activeContext = this
                    )
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
                    binding.ivAppLogo.visibility = View.VISIBLE
                    binding.tvAppTitle.text = "Bridge"
                    updateConnectionStatus()
                    updateContinuityPills()
                    true
                }
                R.id.nav_activity -> {
                    binding.tabContentBridge.visibility = View.GONE
                    binding.tabContentActivity.visibility = View.VISIBLE
                    binding.tabContentSettings.visibility = View.GONE
                    binding.ivAppLogo.visibility = View.GONE
                    binding.tvAppTitle.text = "Activity"
                    renderActivityList()
                    true
                }
                R.id.nav_settings -> {
                    binding.tabContentBridge.visibility = View.GONE
                    binding.tabContentActivity.visibility = View.GONE
                    binding.tabContentSettings.visibility = View.VISIBLE
                    binding.ivAppLogo.visibility = View.GONE
                    binding.tvAppTitle.text = "Settings"
                    updateSettingsValues()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupListeners() {
        // Top Overflow Kebab Menu
        binding.btnTopOverflow.setOnClickListener { v ->
            showOverflowMenu(v)
        }

        // ==================== TAB 1: BRIDGE DASHBOARD ====================
        // Lock Mac
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

        // Discovery & Sync switches (both connected and disconnected card views)
        val onSearchToggle = { isChecked: Boolean ->
            keyStoreManager.setSearchingEnabled(isChecked)
            val service = BridgeService.instance
            if (isChecked) {
                if (service != null) service.resumeSearching()
                else BridgeService.start(this)
                Toast.makeText(this, "Discovery & sync resumed", Toast.LENGTH_SHORT).show()
            } else {
                service?.pauseSearching()
                Toast.makeText(this, "Discovery & sync paused", Toast.LENGTH_SHORT).show()
            }
            updateConnectionStatus()
        }
        binding.switchSearchSync.setOnCheckedChangeListener { _, isChecked ->
            if (binding.switchDisconnectedSearchSync.isChecked != isChecked) {
                binding.switchDisconnectedSearchSync.isChecked = isChecked
            }
            onSearchToggle(isChecked)
        }
        binding.switchDisconnectedSearchSync.setOnCheckedChangeListener { _, isChecked ->
            if (binding.switchSearchSync.isChecked != isChecked) {
                binding.switchSearchSync.isChecked = isChecked
            }
            onSearchToggle(isChecked)
        }

        // Send Files
        binding.btnSendFileToMac.setOnClickListener {
            val service = BridgeService.instance
            if (service == null || !service.isConnectedFlow.value) {
                Toast.makeText(this, "Mac is not connected yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, SendFilesActivity::class.java))
        }

        // Send Clipboard
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
                force = true,
                activeContext = this
            )
            if (sent) {
                Toast.makeText(this, "Clipboard sent to Mac ✓", Toast.LENGTH_SHORT).show()
                updateClipboardPreview()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        // Services: Notifications
        binding.rowNotificationSync.setOnClickListener {
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }

        // Services: Clipboard Sync
        binding.rowClipboardSync.setOnClickListener {
            startActivity(Intent(this, ClipboardSyncActivity::class.java))
        }

        // Services: Background Sync
        binding.rowBackgroundReliability.setOnClickListener {
            showReliabilityOptionsDialog()
        }

        // Disconnected State: Try Reconnecting
        binding.btnTryReconnecting.setOnClickListener {
            triggerReconnect()
        }

        // Disconnected State: Pair another Mac
        binding.btnPairAnotherMac.setOnClickListener {
            startActivity(Intent(this, PairActivity::class.java))
        }

        // Disconnected State: Help & Troubleshooting
        binding.btnHelpTroubleshooting.setOnClickListener {
            showHelpDialog()
        }

        // ==================== TAB 2: ACTIVITY ====================
        binding.btnFilterAll.setOnClickListener {
            setFilter(FilterMode.ALL)
        }

        binding.btnFilterSent.setOnClickListener {
            setFilter(FilterMode.SENT)
        }

        binding.btnFilterReceived.setOnClickListener {
            setFilter(FilterMode.RECEIVED)
        }

        binding.btnClearActivity.setOnClickListener {
            showClearActivityDialog()
        }

        // ==================== TAB 3: SETTINGS ====================
        binding.btnSettingsReconnect.setOnClickListener {
            triggerReconnect()
        }

        binding.btnSettingsManageDevice.setOnClickListener {
            showManageDeviceDialog()
        }

        binding.switchSettingsSearchSync.setOnCheckedChangeListener { _, isChecked ->
            binding.switchSearchSync.isChecked = isChecked
        }

        binding.switchSettingsClipboardSync.setOnCheckedChangeListener { _, isChecked ->
            keyStoreManager.setClipboardSyncEnabled(isChecked)
            updateContinuityPills()
        }

        binding.switchSettingsBackgroundSync.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestDisableBatteryOptimization()
            } else {
                Toast.makeText(this, "Background sync adjusted", Toast.LENGTH_SHORT).show()
            }
        }

        binding.rowSettingsNotifications.setOnClickListener {
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }

        // Appearance / Theme
        binding.rowSettingsColorMode.setOnClickListener {
            showThemeSelectionDialog()
        }

        // Support & About
        binding.rowSettingsBuyCoffee.setOnClickListener {
            openUrl("https://buymeacoffee.com/satyam")
        }

        binding.rowSettingsHelp.setOnClickListener {
            showHelpDialog()
        }

        binding.rowSettingsAbout.setOnClickListener {
            showAboutDialog()
        }
    }

    private fun setFilter(mode: FilterMode) {
        currentActivityFilter = mode

        // Update Pill Backgrounds
        binding.btnFilterAll.setBackgroundResource(
            if (mode == FilterMode.ALL) R.drawable.bg_segmented_selected else 0
        )
        binding.btnFilterAll.setTextColor(
            ContextCompat.getColor(this, if (mode == FilterMode.ALL) R.color.text_primary else R.color.text_secondary)
        )
        binding.btnFilterAll.setTypeface(null, if (mode == FilterMode.ALL) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

        binding.btnFilterSent.setBackgroundResource(
            if (mode == FilterMode.SENT) R.drawable.bg_segmented_selected else 0
        )
        binding.btnFilterSent.setTextColor(
            ContextCompat.getColor(this, if (mode == FilterMode.SENT) R.color.text_primary else R.color.text_secondary)
        )
        binding.btnFilterSent.setTypeface(null, if (mode == FilterMode.SENT) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

        binding.btnFilterReceived.setBackgroundResource(
            if (mode == FilterMode.RECEIVED) R.drawable.bg_segmented_selected else 0
        )
        binding.btnFilterReceived.setTextColor(
            ContextCompat.getColor(this, if (mode == FilterMode.RECEIVED) R.color.text_primary else R.color.text_secondary)
        )
        binding.btnFilterReceived.setTypeface(null, if (mode == FilterMode.RECEIVED) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

        renderActivityList()
    }

    private fun showOverflowMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Try reconnecting")
        popup.menu.add(0, 2, 1, "Pair a Mac")
        popup.menu.add(0, 3, 2, "Check for updates")
        popup.menu.add(0, 4, 3, "Diagnostics & logs")
        popup.menu.add(0, 5, 4, "Help & troubleshooting")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> triggerReconnect()
                2 -> startActivity(Intent(this, PairActivity::class.java))
                3 -> performManualUpdateCheck()
                4 -> copyDiagnostics()
                5 -> showHelpDialog()
            }
            true
        }
        popup.show()
    }

    private fun showThemeSelectionDialog() {
        val modes = arrayOf("Auto (Follow system)", "Light mode", "Dark mode")
        val current = ThemeManager.getThemeMode(this)
        val selectedIndex = when (current) {
            ThemeManager.MODE_LIGHT -> 1
            ThemeManager.MODE_DARK -> 2
            else -> 0
        }

        AlertDialog.Builder(this)
            .setTitle("Color mode")
            .setSingleChoiceItems(modes, selectedIndex) { dialog, which ->
                val newMode = when (which) {
                    1 -> ThemeManager.MODE_LIGHT
                    2 -> ThemeManager.MODE_DARK
                    else -> ThemeManager.MODE_AUTO
                }
                ThemeManager.setThemeMode(this, newMode)
                binding.tvSettingsColorModeValue.text = ThemeManager.getThemeLabel(this)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManageDeviceDialog() {
        val macName = keyStoreManager.getMacName() ?: "Mac"
        val options = arrayOf("Reconnect now", "Pair another Mac", "Unpair $macName", "Cancel")
        AlertDialog.Builder(this)
            .setTitle(macName)
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> triggerReconnect()
                    1 -> startActivity(Intent(this, PairActivity::class.java))
                    2 -> {
                        keyStoreManager.clear()
                        BridgeService.instance?.triggerReconnect()
                        updateConnectionStatus()
                        Toast.makeText(this, "Unpaired from $macName", Toast.LENGTH_SHORT).show()
                    }
                    else -> dialog.dismiss()
                }
            }
            .show()
    }

    private fun showHelpDialog() {
        val message = """
            Bridge connects your Android phone and Mac seamlessly over Wi-Fi:
            
            • Both devices must be on the same local Wi-Fi network.
            • Clipboard sync happens in real time without cloud servers.
            • You can send photos, docs, or files directly from the app.
            • To automatically wake connection, keep Background sync optimized.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Help & Troubleshooting")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showAboutDialog() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "1.4.2"
        }

        AlertDialog.Builder(this)
            .setTitle("Bridge for Android & Mac")
            .setMessage("Version $version\n\nCross-device continuity, clipboard sync, notifications, and file transfer built with native LAN encryption.")
            .setPositiveButton("Check for Updates") { _, _ ->
                performManualUpdateCheck()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun copyDiagnostics() {
        val logs = LogManager.getDiagnostics()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Bridge Diagnostics", logs))
        Toast.makeText(this, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun performManualUpdateCheck() {
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

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun triggerReconnect() {
        BridgeService.instance?.triggerReconnect()
        Toast.makeText(this, "Reconnecting…", Toast.LENGTH_SHORT).show()
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

    private fun requestDisableBatteryOptimization() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
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
              
            • Pixel / OnePlus / Oppo / Realme:
              App Info → Battery usage → Allow background activity.
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("OEM Background Instructions")
            .setMessage(message)
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun updateClipboardPreview() {
        try {
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipManager.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()?.trim()
                if (!text.isNullOrBlank()) {
                    val preview = if (text.length > 28) text.take(28) + "…" else text
                    binding.tvClipboardPreview.text = "“$preview”"
                    return
                }
            }
            binding.tvClipboardPreview.text = "Push clipboard content"
        } catch (_: Exception) {
            binding.tvClipboardPreview.text = "Push clipboard content"
        }
    }

    private fun updateContinuityPills() {
        val notifPrefs = NotificationPreferences(this)
        val notifCount = notifPrefs.getEnabledPackages().size
        binding.tvNotificationStatusPill.text = if (notifPrefs.isSyncEnabled) {
            if (notifCount == 1) "1 app" else "$notifCount apps"
        } else {
            "Off"
        }

        val isClipEnabled = keyStoreManager.isClipboardSyncEnabled()
        binding.tvClipboardStatusPill.text = if (isClipEnabled) "Active" else "Off"

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
        binding.tvBatteryStatusPill.text = if (isIgnoring) "Optimized" else "Restricted"
    }

    private fun updateSettingsValues() {
        val macName = keyStoreManager.getMacName() ?: "Mac"
        val phoneModel = Build.MODEL ?: "Android"
        binding.tvSettingsDeviceName.text = macName
        binding.tvSettingsThisPhone.text = "This phone · $phoneModel"

        val service = BridgeService.instance
        val isConnected = service?.isConnectedFlow?.value == true
        val isSearching = keyStoreManager.isSearchingEnabled()
        val networkName = ConnectivityHelper.getWifiNetworkName(this)

        val statusDot = binding.dotSettingsStatus.background as? GradientDrawable
        if (isConnected) {
            binding.tvSettingsStatusText.text = "Connected · $networkName"
            statusDot?.setColor(ContextCompat.getColor(this, R.color.status_green))
        } else if (isSearching) {
            binding.tvSettingsStatusText.text = "Searching · $networkName"
            statusDot?.setColor(ContextCompat.getColor(this, R.color.status_orange))
        } else {
            binding.tvSettingsStatusText.text = "Offline"
            statusDot?.setColor(ContextCompat.getColor(this, R.color.status_gray))
        }

        val peerBattery = BridgeService.peerBatteryFlow.value
        if (isConnected && peerBattery != null) {
            val (level, isCharging) = peerBattery
            binding.tvSettingsMacBattery.visibility = View.VISIBLE
            binding.tvSettingsMacBattery.text = if (isCharging) "$level% · Charging" else "$level%"
        } else {
            binding.tvSettingsMacBattery.visibility = View.GONE
        }

        binding.switchSettingsSearchSync.isChecked = isSearching
        binding.switchSettingsClipboardSync.isChecked = keyStoreManager.isClipboardSyncEnabled()
        binding.switchSettingsBackgroundSync.isChecked = (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

        val notifPrefs = NotificationPreferences(this)
        val notifCount = notifPrefs.getEnabledPackages().size
        binding.tvSettingsNotificationCount.text = "$notifCount shared apps · All access granted"

        binding.tvSettingsColorModeValue.text = ThemeManager.getThemeLabel(this)

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "1.4.2"
        }
        binding.tvSettingsAppVersion.text = version
        binding.tvSettingsFooterVersion.text = "Bridge $version · Made for Android & Mac"
    }

    private fun updateConnectionStatus() {
        val service = BridgeService.instance
        val isConnected = service?.isConnectedFlow?.value == true
        val hasKey = keyStoreManager.hasKey()
        val isSearching = keyStoreManager.isSearchingEnabled()
        val macName = keyStoreManager.getMacName() ?: "Mac"

        binding.tvDeviceName.text = macName
        binding.tvDisconnectedDeviceName.text = macName

        val networkName = ConnectivityHelper.getWifiNetworkName(this)

        if (hasKey && isConnected) {
            binding.layoutConnectedState.visibility = View.VISIBLE
            binding.layoutDisconnectedState.visibility = View.GONE

            val dot = binding.dotConnectedStatus.background as? GradientDrawable
            dot?.setColor(ContextCompat.getColor(this, R.color.status_green))
            binding.tvConnectedStatusText.text = "Connected"

            val peerBattery = BridgeService.peerBatteryFlow.value
            if (peerBattery != null) {
                val (level, isCharging) = peerBattery
                binding.tvMacBatteryStatus.visibility = View.VISIBLE
                binding.tvMacBatteryStatus.text = if (isCharging) "$level% · Charging" else "$level%"
            } else {
                binding.tvMacBatteryStatus.visibility = View.GONE
            }

            binding.tvDiscoverySubtitle.text = "Search for Mac and maintain sync"
        } else {
            binding.layoutConnectedState.visibility = View.GONE
            binding.layoutDisconnectedState.visibility = View.VISIBLE

            binding.tvDisconnectedStatusSubtitle.text = if (isSearching) "Searching on $networkName" else "Offline"
            binding.tvDisconnectedSearchSubtitle.text = if (isSearching) "Searching on $networkName" else "Paused"
        }

        binding.switchSearchSync.isChecked = isSearching
        binding.switchDisconnectedSearchSync.isChecked = isSearching

        val phoneModel = Build.MODEL ?: "Pixel"
        binding.tvActivitySubtitle.text = "Between your $phoneModel and $macName."
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            while (true) {
                updateConnectionStatus()
                updateContinuityPills()
                kotlinx.coroutines.delay(1000)
            }
        }

        lifecycleScope.launch {
            while (true) {
                if (binding.tabContentActivity.visibility == View.VISIBLE) {
                    renderActivityList()
                }
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun showClearActivityDialog() {
        val options = arrayOf("Clear All Activity", "Clear Transfers Only", "Clear Clipboard History Only")
        AlertDialog.Builder(this)
            .setTitle("Clear History")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        transferHistoryManager.clearTransfers()
                        clipboardHistoryManager.clearHistory()
                        notificationHistoryManager.clearNotifications()
                        Toast.makeText(this, "All activity cleared", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        transferHistoryManager.clearTransfers()
                        Toast.makeText(this, "Transfer history cleared", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        clipboardHistoryManager.clearHistory()
                        Toast.makeText(this, "Clipboard history cleared", Toast.LENGTH_SHORT).show()
                    }
                }
                renderActivityList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderActivityList() {
        val container = binding.llActivityItemsContainer
        container.removeAllViews()

        val macName = keyStoreManager.getMacName() ?: "Mac"
        val phoneModel = Build.MODEL ?: "Pixel"

        // Gather real items
        val transfers = transferHistoryManager.getTransfers()
        val clipboards = clipboardHistoryManager.getItems()
        val notifications = notificationHistoryManager.getNotifications()

        data class UnifiedActivity(
            val title: String,
            val subtitle: String,
            val status: String,
            val timestamp: Long,
            val isIncoming: Boolean,
            val type: String, // "clip", "file", "notif"
            val pkg: String? = null
        )

        val list = mutableListOf<UnifiedActivity>()

        for (c in clipboards) {
            val isInc = c.isIncoming
            val direction = if (isInc) "$macName → $phoneModel" else "$phoneModel → $macName"
            val snippet = if (c.text.startsWith("http://") || c.text.startsWith("https://")) "Link" else "Text snippet"
            val status = if (isInc) "Received" else "Delivered"
            list.add(
                UnifiedActivity(
                    title = "Clipboard synced",
                    subtitle = "$direction · $snippet",
                    status = status,
                    timestamp = c.timestamp,
                    isIncoming = isInc,
                    type = "clip"
                )
            )
        }

        for (t in transfers) {
            val isInc = t.isIncoming
            val direction = if (isInc) "$macName → $phoneModel" else "$phoneModel → $macName"
            val sizeStr = Formatter.formatFileSize(this, t.size)
            val status = if (isInc) "Received · Saved to Downloads" else "Sent · Complete"
            list.add(
                UnifiedActivity(
                    title = t.name,
                    subtitle = "$direction · $sizeStr",
                    status = status,
                    timestamp = t.timestamp,
                    isIncoming = isInc,
                    type = "file"
                )
            )
        }

        for (n in notifications) {
            list.add(
                UnifiedActivity(
                    title = n.app.ifEmpty { "Notification" },
                    subtitle = "$phoneModel → $macName · ${n.title}",
                    status = "Forwarded to Mac",
                    timestamp = n.timestamp,
                    isIncoming = false,
                    type = "notif",
                    pkg = n.pkg
                )
            )
        }

        // Apply filter
        val filtered = when (currentActivityFilter) {
            FilterMode.ALL -> list
            FilterMode.SENT -> list.filter { !it.isIncoming }
            FilterMode.RECEIVED -> list.filter { it.isIncoming }
        }.sortedByDescending { it.timestamp }

        if (filtered.isEmpty()) {
            val emptyView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(16.dp, 24.dp, 16.dp, 24.dp)
                gravity = Gravity.CENTER
                text = "No activity recorded yet"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            }
            container.addView(emptyView)
            return
        }

        val now = System.currentTimeMillis()
        val todayStart = now - (now % (24 * 60 * 60 * 1000))
        val todayItems = filtered.filter { it.timestamp >= todayStart }
        val yesterdayItems = filtered.filter { it.timestamp < todayStart }

        // Render Today
        renderGroupedActivityItems(container, todayItems.ifEmpty { filtered.take(15) })

        // Render Yesterday if exists
        if (yesterdayItems.isNotEmpty() && todayItems.isNotEmpty()) {
            binding.layoutYesterdayHeader.visibility = View.VISIBLE
            binding.llYesterdayItemsContainer.visibility = View.VISIBLE
            binding.llYesterdayItemsContainer.removeAllViews()
            renderGroupedActivityItems(binding.llYesterdayItemsContainer, yesterdayItems.take(15))
        } else {
            binding.layoutYesterdayHeader.visibility = View.GONE
            binding.llYesterdayItemsContainer.visibility = View.GONE
        }
    }

    private fun renderGroupedActivityItems(
        container: LinearLayout,
        items: List<Any>
    ) {
        val pm = packageManager
        for ((index, obj) in items.withIndex()) {
            val item = obj as? Any ?: continue
            val title: String
            val subtitle: String
            val status: String
            val timestamp: Long
            val isIncoming: Boolean
            val type: String
            val pkg: String?

            // Reflect fields
            try {
                val tField = item.javaClass.getDeclaredField("title").apply { isAccessible = true }
                val subField = item.javaClass.getDeclaredField("subtitle").apply { isAccessible = true }
                val stField = item.javaClass.getDeclaredField("status").apply { isAccessible = true }
                val tsField = item.javaClass.getDeclaredField("timestamp").apply { isAccessible = true }
                val inField = item.javaClass.getDeclaredField("isIncoming").apply { isAccessible = true }
                val typeField = item.javaClass.getDeclaredField("type").apply { isAccessible = true }
                val pkgField = item.javaClass.getDeclaredField("pkg").apply { isAccessible = true }

                title = tField.get(item) as String
                subtitle = subField.get(item) as String
                status = stField.get(item) as String
                timestamp = tsField.get(item) as Long
                isIncoming = inField.get(item) as Boolean
                type = typeField.get(item) as String
                pkg = pkgField.get(item) as? String
            } catch (_: Exception) {
                continue
            }

            if (index > 0) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    ).apply {
                        topMargin = 12.dp
                        bottomMargin = 12.dp
                    }
                    setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.card_stroke))
                }
                container.addView(divider)
            }

            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // Left squircle icon
            val iconFrame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(40.dp, 40.dp).apply { marginEnd = 12.dp }
                setBackgroundResource(R.drawable.bg_rounded_inset)
            }
            val iconImg = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(20.dp, 20.dp, Gravity.CENTER)
                when (type) {
                    "clip" -> {
                        setImageResource(R.drawable.ic_clipboard_synced)
                        setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.icon_tint))
                    }
                    "file" -> {
                        setImageResource(if (isIncoming) R.drawable.ic_arrow_down_left else R.drawable.ic_arrow_up_right)
                        setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.icon_tint))
                    }
                    else -> {
                        if (pkg != null) {
                            try {
                                val d = pm.getApplicationIcon(pkg)
                                setImageDrawable(d)
                            } catch (_: Exception) {
                                setImageResource(R.drawable.ic_notifications_outline)
                            }
                        } else {
                            setImageResource(R.drawable.ic_notifications_outline)
                        }
                    }
                }
            }
            iconFrame.addView(iconImg)
            row.addView(iconFrame)

            // Center details
            val textCol = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL
            }

            val titleView = TextView(this).apply {
                text = title
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            }
            textCol.addView(titleView)

            val subtitleView = TextView(this).apply {
                text = subtitle
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            }
            textCol.addView(subtitleView)

            // Status + Timestamp line
            val statusRow = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 2.dp }
                orientation = LinearLayout.HORIZONTAL
            }

            val statusView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = status
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            }
            statusRow.addView(statusView)

            val timeView = TextView(this).apply {
                val timeStr = DateUtils.formatDateTime(
                    this@MainActivity, timestamp, DateUtils.FORMAT_SHOW_TIME
                )
                text = timeStr
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            }
            statusRow.addView(timeView)
            textCol.addView(statusRow)

            row.addView(textCol)
            container.addView(row)
        }
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
                            Toast.makeText(this@MainActivity, "Sent $fileName ✓", Toast.LENGTH_SHORT).show()
                            renderActivityList()
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

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }
}
