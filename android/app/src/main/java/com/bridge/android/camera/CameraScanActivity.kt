package com.bridge.android.camera

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.bridge.android.R
import com.bridge.android.databinding.ActivityCameraScanBinding
import com.bridge.android.util.LogManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAIRING_PAYLOAD = "extra_pairing_payload"
        const val EXTRA_MANUAL_CODE_REQUESTED = "extra_manual_code_requested"
    }

    private lateinit var binding: ActivityCameraScanBinding
    private var cameraExecutor: ExecutorService? = null
    private var camera: Camera? = null
    private var isTorchOn = false
    private var scanLineAnimator: ObjectAnimator? = null
    private var qrAnalyzer: QrCodeAnalyzer? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showScanner()
            startCamera()
        } else {
            showPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupListeners()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScanner()
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupListeners() {
        binding.btnScannerClose.setOnClickListener {
            finish()
        }

        binding.btnCancelScanner.setOnClickListener {
            finish()
        }

        binding.btnRequestCameraPermission.setOnClickListener {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        binding.btnTorch.setOnClickListener {
            toggleTorch()
        }

        binding.btnManualCode.setOnClickListener {
            val resultIntent = Intent().apply {
                putExtra(EXTRA_MANUAL_CODE_REQUESTED, true)
            }
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun showScanner() {
        binding.permissionDeniedContainer.visibility = View.GONE
        binding.scannerOverlay.visibility = View.VISIBLE
        startScanLineAnimation()
    }

    private fun showPermissionDenied() {
        binding.scannerOverlay.visibility = View.GONE
        binding.permissionDeniedContainer.visibility = View.VISIBLE
        stopScanLineAnimation()
    }

    private fun startScanLineAnimation() {
        binding.viewfinderBox.post {
            val height = binding.viewfinderBox.height.toFloat()
            if (height > 0) {
                scanLineAnimator?.cancel()
                scanLineAnimator = ObjectAnimator.ofFloat(binding.scanningLine, "translationY", 0f, height).apply {
                    duration = 2000
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = LinearInterpolator()
                    start()
                }
            }
        }
    }

    private fun stopScanLineAnimation() {
        scanLineAnimator?.cancel()
        scanLineAnimator = null
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

                val analyzer = QrCodeAnalyzer { qrPayload ->
                    runOnUiThread {
                        onQrCodeDetected(qrPayload)
                    }
                }
                qrAnalyzer = analyzer

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        val executor = cameraExecutor ?: Executors.newSingleThreadExecutor()
                        it.setAnalyzer(executor, analyzer)
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                updateTorchIcon()
            } catch (e: Exception) {
                LogManager.e("CameraX binding failed", e)
                Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) {
            isTorchOn = !isTorchOn
            cam.cameraControl.enableTorch(isTorchOn)
            updateTorchIcon()
        } else {
            Toast.makeText(this, "Torch not available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTorchIcon() {
        val res = if (isTorchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        binding.btnTorch.setImageResource(res)
    }

    private fun onQrCodeDetected(payload: String) {
        vibrateSuccess()
        binding.tvScannerStatus.text = "QR Code detected!"

        val resultIntent = Intent().apply {
            putExtra(EXTRA_PAIRING_PAYLOAD, payload)
        }
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun vibrateSuccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(80)
            }
        } catch (_: Exception) {
            // Ignore vibration errors if not supported
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScanLineAnimation()
        cameraExecutor?.shutdown()
    }
}
