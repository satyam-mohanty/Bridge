package com.bridge.android.ui

import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bridge.android.crypto.KeyStoreManager
import com.bridge.android.databinding.ActivityPairBinding
import com.bridge.android.protocol.BridgeProtocol
import com.bridge.android.service.BridgeService
import com.bridge.android.util.LogManager
import com.bridge.android.util.ThemeManager
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class PairActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPairBinding
    private lateinit var keyStoreManager: KeyStoreManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyTheme(this)
        binding = ActivityPairBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keyStoreManager = KeyStoreManager(this)

        val deviceModel = Build.MODEL ?: "phone"
        binding.tvPairWifiNote.text = "Keep your $deviceModel and Mac on the same Wi-Fi. Leave Bridge open on both devices."

        setupListeners()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.cardQrScannerTrigger.setOnClickListener {
            startQrScanner()
        }

        binding.btnPairWithCode.setOnClickListener {
            val code = binding.etPairingCode.text.toString().trim()
            if (code.isEmpty()) {
                Toast.makeText(this, "Please enter pairing code", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            processPairingPayload(code)
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
                Toast.makeText(this, "Invalid key length in pairing code (${keyBytes.size} bytes)", Toast.LENGTH_LONG).show()
                return
            }

            keyStoreManager.saveSharedKey(keyBytes, pairing.name)
            Toast.makeText(this, "Paired successfully with ${pairing.name}!", Toast.LENGTH_SHORT).show()
            LogManager.i("Paired with ${pairing.name}. Triggering reconnect.")
            BridgeService.start(this)
            BridgeService.instance?.triggerReconnect()
            finish()
        } catch (e: Exception) {
            LogManager.e("Failed to process pairing payload", e)
            Toast.makeText(this, "Invalid Bridge pairing code format", Toast.LENGTH_LONG).show()
        }
    }
}
