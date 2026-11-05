package com.bridge.android.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bridge.android.R
import com.bridge.android.camera.CameraScanActivity
import com.bridge.android.crypto.KeyStoreManager
import com.bridge.android.databinding.ActivityOnboardingBinding
import com.bridge.android.protocol.BridgeProtocol
import com.bridge.android.service.BridgeService
import com.bridge.android.util.LogManager
import com.bridge.android.util.OnboardingManager
import com.bridge.android.util.ThemeManager
import com.google.android.material.textfield.TextInputEditText

class OnboardingActivity : AppCompatActivity() {

    enum class Step {
        WELCOME,
        NOTIFICATIONS,
        CLIPBOARD,
        FILE_TRANSFER,
        BATTERY_OPTIMIZATION,
        CAMERA_PAIRING,
        ALL_SET
    }

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var keyStoreManager: KeyStoreManager
    private var currentStep = Step.WELCOME

    // Activity Result Launchers for Permissions and Scanners

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updateCurrentStepUI()
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        updateCurrentStepUI()
    }

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val payload = result.data?.getStringExtra(CameraScanActivity.EXTRA_PAIRING_PAYLOAD)
            if (!payload.isNullOrEmpty()) {
                handlePairingPayload(payload)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyTheme(this)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keyStoreManager = KeyStoreManager(this)

        setupTopBar()
        updateCurrentStepUI()
    }

    override fun onResume() {
        super.onResume()
        // Re-check system settings (e.g. Accessibility or Battery or Notifications toggled in System Settings)
        updateCurrentStepUI()
    }

    private fun setupTopBar() {
        binding.btnOnboardingBack.setOnClickListener {
            goToPreviousStep()
        }

        binding.btnOnboardingSkip.setOnClickListener {
            showSkipConfirmationDialog()
        }
    }

    private fun goToPreviousStep() {
        val steps = Step.values()
        val prevIndex = currentStep.ordinal - 1
        if (prevIndex >= 0) {
            currentStep = steps[prevIndex]
            updateCurrentStepUI()
        }
    }

    private fun goToNextStep() {
        val steps = Step.values()
        val nextIndex = currentStep.ordinal + 1
        if (nextIndex < steps.size) {
            currentStep = steps[nextIndex]
            updateCurrentStepUI()
        } else {
            finishOnboarding()
        }
    }

    private fun finishOnboarding() {
        OnboardingManager.setOnboardingCompleted(this, true)
        BridgeService.start(this)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun showSkipConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Skip Setup?")
            .setMessage("Bridge works best when background permissions are granted. You can configure them anytime later in Settings.")
            .setPositiveButton("Skip to App") { _, _ ->
                finishOnboarding()
            }
            .setNegativeButton("Continue Setup", null)
            .show()
    }

    private fun updateCurrentStepUI() {
        val stepNumber = currentStep.ordinal + 1
        binding.tvStepCounter.text = "Step $stepNumber of ${Step.values().size}"
        binding.onboardingProgress.progress = stepNumber

        binding.btnOnboardingBack.visibility = if (currentStep == Step.WELCOME) View.INVISIBLE else View.VISIBLE
        binding.btnOnboardingSkip.visibility = if (currentStep == Step.ALL_SET) View.GONE else View.VISIBLE
        binding.btnAlternativeAction.visibility = View.GONE

        when (currentStep) {
            Step.WELCOME -> renderWelcomeStep()
            Step.NOTIFICATIONS -> renderNotificationStep()
            Step.CLIPBOARD -> renderClipboardStep()
            Step.FILE_TRANSFER -> renderFileTransferStep()
            Step.BATTERY_OPTIMIZATION -> renderBatteryStep()
            Step.CAMERA_PAIRING -> renderCameraPairingStep()
            Step.ALL_SET -> renderAllSetStep()
        }
    }

    // Step 1: WELCOME
    private fun renderWelcomeStep() {
        binding.ivHeroIcon.setImageResource(R.drawable.ic_bridge_logo)
        binding.tvStepTitle.text = "Welcome to Bridge"
        binding.tvStepSubtitle.text = "Seamless cross-device connectivity between your Android phone and Mac over your local Wi-Fi."

        binding.tvGuidanceTitle.text = "What Bridge does"
        setStatusBadge("Fast Setup", isPositive = true)
        binding.tvGuidanceInstructions.text =
            "• Universal Clipboard: Copy text & images on phone, paste on Mac.\n" +
            "• Instant File Transfer: Send photos, videos, and files without cloud compression.\n" +
            "• Local & Secure: All data is end-to-end encrypted with AES-256-GCM directly over LAN."
        binding.tvGuidanceExtraText.text = "We will guide you through a few quick permissions to get everything running seamlessly."

        binding.btnPrimaryAction.text = "Get Started"
        binding.btnPrimaryAction.setOnClickListener {
            goToNextStep()
        }

        binding.btnSecondaryAction.visibility = View.VISIBLE
        binding.btnSecondaryAction.text = "Skip Setup for Now"
        binding.btnSecondaryAction.setOnClickListener {
            showSkipConfirmationDialog()
        }
    }

    // Step 2: NOTIFICATIONS
    private fun renderNotificationStep() {
        binding.ivHeroIcon.setImageResource(R.drawable.ic_notification_bell)
        binding.tvStepTitle.text = "Stay in the Loop"
        binding.tvStepSubtitle.text = "Receive alerts when files arrive from your Mac, clipboard updates sync, and keep the connection alive."

        binding.tvGuidanceTitle.text = "Notification Settings"
        val isGranted = OnboardingManager.isNotificationPermissionGranted(this)

        if (isGranted) {
            setStatusBadge("Notifications Enabled", isPositive = true)
            binding.tvGuidanceInstructions.text = "✓ Notifications are fully enabled.\nBridge can alert you to incoming transfers and clipboard sync events."
            binding.tvGuidanceExtraText.text = "You can also customize which phone alerts forward to your Mac inside Notification Settings later."
            binding.btnPrimaryAction.text = "Continue"
            binding.btnPrimaryAction.setOnClickListener {
                goToNextStep()
            }
        } else {
            setStatusBadge("Action Needed", isPositive = false)
            binding.tvGuidanceInstructions.text =
                "1. Tap 'Get notification' below to allow system alerts.\n" +
                "2. If prompted, tap 'Allow'.\n" +
                "3. You can also open Notification Settings to fine-tune alerts."
            binding.tvGuidanceExtraText.text = "Android requires explicit permission for background sync and file reception alerts."

            binding.btnPrimaryAction.text = "Get notification"
            binding.btnPrimaryAction.setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    OnboardingManager.openNotificationSettings(this)
                }
            }
        }

        binding.btnSecondaryAction.visibility = View.VISIBLE
        binding.btnSecondaryAction.text = "I don't want to receive notifications"
        binding.btnSecondaryAction.setOnClickListener {
            OnboardingManager.markStepDeclined(this, "notifications")
            goToNextStep()
        }

        binding.btnAlternativeAction.visibility = View.VISIBLE
        binding.btnAlternativeAction.text = "Open Notification Settings"
        binding.btnAlternativeAction.setOnClickListener {
            OnboardingManager.openNotificationSettings(this)
        }
    }

    // Step 3: CLIPBOARD SYNC
    private fun renderClipboardStep() {
        binding.ivHeroIcon.setImageResource(R.drawable.ic_clipboard_synced)
        binding.tvStepTitle.text = "Clipboard Sync"
        binding.tvStepSubtitle.text = "Seamlessly sync copied text and images between your phone and Mac in real time."

        binding.tvGuidanceTitle.text = "Ready to Sync"
        setStatusBadge("Auto-Sync Active", isPositive = true)
        binding.tvGuidanceInstructions.text =
            "• Auto Sync from Mac: Anything you copy on your Mac instantly appears on your phone's clipboard.\n\n" +
            "• Send to Mac: Tap 'Send Clipboard' anytime inside Bridge or using the Quick Settings tile to mirror your current clipboard to your Mac."
        binding.tvGuidanceExtraText.text = "Operates directly over local Wi-Fi with AES-256-GCM encryption. No cloud servers, no analytics."

        binding.btnPrimaryAction.text = "Continue"
        binding.btnPrimaryAction.setOnClickListener {
            goToNextStep()
        }
        binding.btnSecondaryAction.visibility = View.GONE
        binding.btnAlternativeAction.visibility = View.GONE
    }

    // Step 4: FILE TRANSFER & STORAGE
    private fun renderFileTransferStep() {
        binding.ivHeroIcon.setImageResource(R.drawable.ic_folder_upload)
        binding.tvStepTitle.text = "File & Photo Transfer"
        binding.tvStepSubtitle.text = "AirDrop-style sharing between your phone and Mac over your local network."

        binding.tvGuidanceTitle.text = "Storage & Media Access"
        val isGranted = OnboardingManager.isStoragePermissionGranted(this)

        if (isGranted) {
            setStatusBadge("Storage Allowed", isPositive = true)
            binding.tvGuidanceInstructions.text = "✓ Storage and media access is granted!\nFiles sent from your Mac will be saved directly into your Downloads folder."
            binding.tvGuidanceExtraText.text = "You can also share files from any Android app directly to your Mac using the system Share sheet."
            binding.btnPrimaryAction.text = "Continue"
            binding.btnPrimaryAction.setOnClickListener {
                goToNextStep()
            }
        } else {
            setStatusBadge("Permission Needed", isPositive = false)
            binding.tvGuidanceInstructions.text =
                "Bridge needs storage permission to save incoming files from Mac and to let you pick files to send:\n\n" +
                "1. Tap 'Allow File Transfer Access' below.\n" +
                "2. When prompted by Android, tap 'Allow'.\n" +
                "3. Received files will appear directly in your Downloads folder."
            binding.tvGuidanceExtraText.text = "All files are sent directly over LAN in chunks with SHA-256 verification."

            binding.btnPrimaryAction.text = "Allow File Transfer Access"
            binding.btnPrimaryAction.setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    storagePermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.READ_MEDIA_VIDEO
                        )
                    )
                } else {
                    storagePermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    )
                }
            }
        }

        binding.btnSecondaryAction.visibility = View.VISIBLE
        binding.btnSecondaryAction.text = "I don't want file transfer"
        binding.btnSecondaryAction.setOnClickListener {
            OnboardingManager.markStepDeclined(this, "files")
            goToNextStep()
        }

        binding.btnAlternativeAction.visibility = View.VISIBLE
        binding.btnAlternativeAction.text = "Open App Storage Settings"
        binding.btnAlternativeAction.setOnClickListener {
            OnboardingManager.openStorageSettings(this)
        }
    }

    // Step 5: BATTERY OPTIMIZATION
    private fun renderBatteryStep() {
        binding.ivHeroIcon.setImageResource(R.drawable.ic_battery_sync)
        binding.tvStepTitle.text = "Background Reliability"
        binding.tvStepSubtitle.text = "Prevent Android battery management from dropping your Mac connection when the phone screen is locked."

        binding.tvGuidanceTitle.text = "Battery Optimization"
        val isIgnored = OnboardingManager.isBatteryOptimizationIgnored(this)

        if (isIgnored) {
            setStatusBadge("Unrestricted", isPositive = true)
            binding.tvGuidanceInstructions.text = "✓ Battery optimization is disabled for Bridge.\nYour phone and Mac will maintain an instant, reliable connection."
            binding.tvGuidanceExtraText.text = "Bridge consumes zero CPU cycles when idle, waiting passively on your local network."
            binding.btnPrimaryAction.text = "Continue"
            binding.btnPrimaryAction.setOnClickListener {
                goToNextStep()
            }
        } else {
            setStatusBadge("Optimization Enabled", isPositive = false)
            binding.tvGuidanceInstructions.text =
                "Android aggressive battery savers can stop Bridge when the phone is locked:\n\n" +
                "1. Tap 'Disable Battery Optimization' below.\n" +
                "2. When prompted, select 'Allow' or choose 'Unrestricted'.\n" +
                "3. This keeps your clipboard and file notifications real-time."
            binding.tvGuidanceExtraText.text = "Bridge uses zero idle battery while maintaining background readiness."

            binding.btnPrimaryAction.text = "Disable Battery Optimization"
            binding.btnPrimaryAction.setOnClickListener {
                OnboardingManager.openBatteryOptimizationSettings(this)
            }
        }

        binding.btnSecondaryAction.visibility = View.VISIBLE
        binding.btnSecondaryAction.text = "Keep battery optimization"
        binding.btnSecondaryAction.setOnClickListener {
            OnboardingManager.markStepDeclined(this, "battery")
            goToNextStep()
        }
    }

    // Step 6: CAMERA PAIRING WITH BUILT-IN SCANNER
    private fun renderCameraPairingStep() {
        binding.ivHeroIcon.setImageResource(R.drawable.ic_qr_code)
        binding.tvStepTitle.text = "Pair with Your Mac"
        binding.tvStepSubtitle.text = "Scan the QR code displayed in Bridge on your Mac using our built-in camera scanner."

        binding.tvGuidanceTitle.text = "Pairing Guide"
        val isPaired = OnboardingManager.isPairedWithMac(this)
        val macName = keyStoreManager.getMacName()

        if (isPaired && !macName.isNullOrEmpty()) {
            setStatusBadge("Paired with $macName", isPositive = true)
            binding.tvGuidanceInstructions.text = "✓ Successfully paired with $macName!\nYour devices can now securely communicate."
            binding.tvGuidanceExtraText.text = "Encryption keys are stored securely in Android Keystore with AES-256-GCM."
            binding.btnPrimaryAction.text = "Continue"
            binding.btnPrimaryAction.setOnClickListener {
                goToNextStep()
            }
        } else {
            setStatusBadge("Not Paired", isPositive = false)
            binding.tvGuidanceInstructions.text =
                "1. Open Bridge on your Mac.\n" +
                "2. Click the Bridge menu bar icon ➔ 'Pair New Phone…'.\n" +
                "3. Tap 'Scan Mac QR Code' below to open our built-in scanner.\n" +
                "4. Point your camera at the Mac's QR code."
            binding.tvGuidanceExtraText.text = "Uses our native built-in camera scanner with offline ZXing decoding (no Google Play Services camera)."

            binding.btnPrimaryAction.text = "Scan Mac QR Code"
            binding.btnPrimaryAction.setOnClickListener {
                val intent = Intent(this, CameraScanActivity::class.java)
                qrScannerLauncher.launch(intent)
            }
        }

        binding.btnSecondaryAction.visibility = View.VISIBLE
        binding.btnSecondaryAction.text = "I'll pair later / Skip camera"
        binding.btnSecondaryAction.setOnClickListener {
            goToNextStep()
        }

        binding.btnAlternativeAction.visibility = View.GONE
    }

    // Step 7: ALL SET
    private fun renderAllSetStep() {
        binding.ivHeroIcon.setImageResource(R.drawable.ic_check_circle)
        binding.tvStepTitle.text = "You're All Set!"
        binding.tvStepSubtitle.text = "Bridge is configured and ready. Here is a summary of your setup:"

        binding.tvGuidanceTitle.text = "Setup Summary"
        setStatusBadge("Ready to Bridge", isPositive = true)

        val notifStatus = if (OnboardingManager.isNotificationPermissionGranted(this)) "✓ Notifications enabled" else "○ Notifications skipped"
        val clipStatus = if (keyStoreManager.isClipboardSyncEnabled()) "✓ Clipboard sync enabled" else "○ Clipboard sync disabled"
        val fileStatus = if (OnboardingManager.isStoragePermissionGranted(this)) "✓ File transfer allowed" else "○ File transfer skipped"
        val batteryStatus = if (OnboardingManager.isBatteryOptimizationIgnored(this)) "✓ Unrestricted background" else "○ Standard battery mode"
        val macName = keyStoreManager.getMacName()
        val pairStatus = if (!macName.isNullOrEmpty()) "✓ Paired with $macName" else "○ Not paired yet"

        binding.tvGuidanceInstructions.text =
            "$notifStatus\n" +
            "$clipStatus\n" +
            "$fileStatus\n" +
            "$batteryStatus\n" +
            "$pairStatus"

        binding.tvGuidanceExtraText.text = "You can revisit these settings or pair another device at any time from Settings."

        binding.btnPrimaryAction.text = "Start Using Bridge"
        binding.btnPrimaryAction.setOnClickListener {
            finishOnboarding()
        }

        binding.btnSecondaryAction.visibility = View.GONE
    }

    private fun setStatusBadge(text: String, isPositive: Boolean) {
        binding.tvStatusBadge.text = text
        val bgColor = if (isPositive) R.color.pill_green_bg else R.color.pill_orange_bg
        val textColor = if (isPositive) R.color.status_green else R.color.status_orange
        binding.badgeStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bgColor))
        binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, textColor))
    }

    private fun handlePairingPayload(payload: String) {
        try {
            val pairing = BridgeProtocol.PairingInfo.fromJson(payload)
            val keyBytes = Base64.decode(pairing.key, Base64.DEFAULT)
            if (keyBytes.size != 32) {
                LogManager.e("Invalid key length in pairing code (${keyBytes.size} bytes)")
                return
            }

            keyStoreManager.saveSharedKey(keyBytes, pairing.name)
            LogManager.i("Paired with ${pairing.name} from onboarding.")
            BridgeService.start(this)
            BridgeService.instance?.triggerReconnect()
            updateCurrentStepUI()
        } catch (e: Exception) {
            LogManager.e("Failed to process pairing payload", e)
        }
    }

    private fun showManualCodeDialog() {
        val input = TextInputEditText(this).apply {
            hint = "000 000"
            setPadding(40, 30, 40, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Enter Pairing Code")
            .setMessage("Enter the 6-digit code or JSON pairing payload shown in Bridge on your Mac.")
            .setView(input)
            .setPositiveButton("Pair") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty()) {
                    handlePairingPayload(code)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
