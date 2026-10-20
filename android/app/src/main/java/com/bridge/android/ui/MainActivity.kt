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
import com.bridge.android.databinding.BottomSheetSettingsBinding
import com.bridge.android.protocol.BridgeProtocol
import com.bridge.android.service.BridgeService
import com.bridge.android.util.LogManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var keyStoreManager: KeyStoreManager

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

        setupListeners()
        observeServiceState()
        checkAndRequestNotificationPermission()

        BridgeService.start(this)
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val service = BridgeService.instance
        if (service != null && service.isConnectedFlow.value) {
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            service.getClipboardSyncManager().sendLocalClipboardToMac(service.getClient(), clipManager)
        }
    }

    override fun onResume() {
        super.onResume()
        updateConnectionStatus()
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

    private fun setupListeners() {
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

        // Open Settings Bottom Sheet
        binding.btnOpenSettings.setOnClickListener {
            showSettingsBottomSheet()
        }

        // Quick Reconnect
        binding.btnQuickReconnect.setOnClickListener {
            BridgeService.instance?.triggerReconnect()
            Toast.makeText(this, "Reconnecting…", Toast.LENGTH_SHORT).show()
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
                }
            }
        }
    }

    private fun updateConnectionStatus() {
        val service = BridgeService.instance
        val isConnected = service?.isConnectedFlow?.value == true
        val statusText = service?.statusFlow?.value ?: if (keyStoreManager.hasKey()) "Searching for Mac…" else "Not Paired"
        val macName = keyStoreManager.getMacName() ?: "Mac"

        binding.tvConnectionStatus.text = statusText
        val dotDrawable = binding.statusIndicator.background as? GradientDrawable

        if (isConnected) {
            binding.tvDeviceName.text = macName
            dotDrawable?.setColor(ContextCompat.getColor(this, R.color.status_green))
            binding.tvSecurityBadge.text = "Active AES-256-GCM Session • Port 48250"
            binding.btnQuickReconnect.visibility = View.GONE
        } else if (keyStoreManager.hasKey()) {
            binding.tvDeviceName.text = macName
            dotDrawable?.setColor(ContextCompat.getColor(this, R.color.status_orange))
            binding.tvSecurityBadge.text = "Local Network Discovery Active"
            binding.btnQuickReconnect.visibility = View.VISIBLE
        } else {
            binding.tvDeviceName.text = "Not Paired"
            dotDrawable?.setColor(ContextCompat.getColor(this, R.color.status_red))
            binding.tvSecurityBadge.text = "Tap Settings gear to pair with Mac"
            binding.btnQuickReconnect.visibility = View.GONE
        }
    }

    // MARK: - Settings Bottom Sheet
    private fun showSettingsBottomSheet() {
        val sheetDialog = BottomSheetDialog(this)
        val sheetBinding = BottomSheetSettingsBinding.inflate(layoutInflater)
        sheetDialog.setContentView(sheetBinding.root)

        // IP Display
        val currentIp = keyStoreManager.getLastKnownIp()
        sheetBinding.tvCurrentIpSetting.text = if (currentIp.isNullOrBlank()) "Auto" else currentIp

        // Battery Status Display
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBattery = pm.isIgnoringBatteryOptimizations(packageName)
        if (isIgnoringBattery) {
            sheetBinding.tvBatteryOptStatus.text = "Disabled ✓"
            sheetBinding.tvBatteryOptStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            sheetBinding.tvBatteryOptStatus.text = "Enable"
            sheetBinding.tvBatteryOptStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
        }

        // Accessibility Service Status
        val isA11yEnabled = isAccessibilityServiceEnabled()
        if (isA11yEnabled) {
            sheetBinding.tvAccessibilityStatus.text = "Active ✓"
            sheetBinding.tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            sheetBinding.tvAccessibilityStatus.text = "Enable"
            sheetBinding.tvAccessibilityStatus.setTextColor(ContextCompat.getColor(this, R.color.accent))
        }

        sheetBinding.rowAccessibilityService.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Find 'Bridge' under Downloaded apps and turn it ON", Toast.LENGTH_LONG).show()
        }

        // Auto Mode Switch
        val hasReadLogs = checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED
        val canLaunchReader = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        val isAutoEnabled = keyStoreManager.isAutoModeEnabled() && hasReadLogs && canLaunchReader
        sheetBinding.switchAutoModeSheet.isChecked = isAutoEnabled

        sheetBinding.switchAutoModeSheet.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED) {
                    keyStoreManager.setAutoModeEnabled(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                        sheetBinding.switchAutoModeSheet.isChecked = false
                        showOverlayPermissionDialog()
                        BridgeService.instance?.syncAutoModeState()
                        return@setOnCheckedChangeListener
                    }
                    BridgeService.instance?.syncAutoModeState()
                    Toast.makeText(this, "Auto-sync mode enabled", Toast.LENGTH_SHORT).show()
                } else {
                    sheetBinding.switchAutoModeSheet.isChecked = false
                    showAdbPermissionDialog()
                }
            } else {
                keyStoreManager.setAutoModeEnabled(false)
                BridgeService.instance?.syncAutoModeState()
                Toast.makeText(this, "Auto-sync mode disabled", Toast.LENGTH_SHORT).show()
            }
        }

        // Actions
        sheetBinding.rowScanQr.setOnClickListener {
            sheetDialog.dismiss()
            startQrScanner()
        }

        sheetBinding.rowPastePairing.setOnClickListener {
            sheetDialog.dismiss()
            showPastePairingDialog()
        }

        sheetBinding.rowManualIp.setOnClickListener {
            sheetDialog.dismiss()
            showManualIpDialog()
        }

        sheetBinding.rowBatteryOpt.setOnClickListener {
            requestDisableBatteryOptimization()
        }

        sheetBinding.rowAutostart.setOnClickListener {
            showOemInstructionsDialog()
        }

        sheetBinding.rowCopyLogs.setOnClickListener {
            val logs = LogManager.getDiagnostics()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Bridge Diagnostics", logs))
            Toast.makeText(this, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        sheetBinding.rowUnpair.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Unpair Mac")
                .setMessage("Are you sure you want to unpair from this Mac?")
                .setPositiveButton("Unpair") { _, _ ->
                    keyStoreManager.clear()
                    BridgeService.instance?.triggerReconnect()
                    updateConnectionStatus()
                    sheetDialog.dismiss()
                    Toast.makeText(this, "Unpaired from Mac", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        sheetDialog.show()
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
                    Toast.makeText(this, "Saved IP: $ip", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Clear IP") { _, _ ->
                keyStoreManager.saveLastKnownIp("")
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
        val expectedComponentName = ComponentName(this, com.bridge.android.service.BridgeAccessibilityService::class.java).flattenToString()
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
