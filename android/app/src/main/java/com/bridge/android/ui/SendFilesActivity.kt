package com.bridge.android.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bridge.android.R
import com.bridge.android.crypto.KeyStoreManager
import com.bridge.android.databinding.ActivitySendFilesBinding
import com.bridge.android.history.TransferHistoryManager
import com.bridge.android.service.BridgeService
import com.bridge.android.util.ConnectivityHelper
import com.bridge.android.util.LogManager
import com.bridge.android.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class SendFilesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySendFilesBinding
    private lateinit var keyStoreManager: KeyStoreManager
    private lateinit var transferHistoryManager: TransferHistoryManager

    private val pickFilesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) {
            sendSelectedFiles(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyTheme(this)
        binding = ActivitySendFilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keyStoreManager = KeyStoreManager(this)
        transferHistoryManager = TransferHistoryManager(this)

        setupUI()
        observeTransferState()
        renderRecentFiles()
    }

    override fun onResume() {
        super.onResume()
        renderRecentFiles()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        val macName = keyStoreManager.getMacName() ?: "Mac"
        binding.tvTargetMacName.text = macName

        val service = BridgeService.instance
        val isConnected = service?.isConnectedFlow?.value == true
        val networkName = ConnectivityHelper.getWifiNetworkName(this)
        binding.tvTargetNetwork.text = if (isConnected) "Connected · $networkName" else "Offline · $networkName"

        binding.btnAddFiles.setOnClickListener {
            if (service == null || !service.isConnectedFlow.value) {
                Toast.makeText(this, "Mac is not connected yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pickFilesLauncher.launch("*/*")
        }

        binding.btnPauseTransfer.setOnClickListener {
            Toast.makeText(this, "Transfer paused", Toast.LENGTH_SHORT).show()
        }

        binding.btnCancelTransfer.setOnClickListener {
            binding.cardActiveTransfer.visibility = View.GONE
            Toast.makeText(this, "Transfer cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeTransferState() {
        lifecycleScope.launch {
            val service = BridgeService.instance
            service?.transferFlow?.collectLatest { transfer ->
                if (transfer != null && !transfer.isCompleted) {
                    binding.cardActiveTransfer.visibility = View.VISIBLE
                    val pct = (transfer.progress * 100).toInt()
                    binding.tvTransferStep.text = if (transfer.isIncoming) "Receiving file" else "Sending file"
                    binding.tvTransferPercent.text = "$pct%"
                    binding.tvTransferFileName.text = transfer.fileName
                    binding.tvTransferStats.text = "$pct% completed"
                    binding.progressBarSending.progress = pct
                    binding.tvTransferRemaining.text = "In progress…"
                } else if (transfer != null && transfer.isCompleted) {
                    binding.cardActiveTransfer.visibility = View.VISIBLE
                    binding.tvTransferStep.text = "Complete ✓"
                    binding.tvTransferPercent.text = "100%"
                    binding.tvTransferFileName.text = transfer.fileName
                    binding.progressBarSending.progress = 100
                    binding.tvTransferRemaining.text = "Finished"
                    renderRecentFiles()
                } else {
                    binding.cardActiveTransfer.visibility = View.GONE
                }
            }
        }
    }

    private fun sendSelectedFiles(uris: List<Uri>) {
        val service = BridgeService.instance ?: return
        Toast.makeText(this, "Sending ${uris.size} file(s) to Mac…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            for ((idx, uri) in uris.withIndex()) {
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

                    withContext(Dispatchers.Main) {
                        binding.cardActiveTransfer.visibility = View.VISIBLE
                        binding.tvTransferStep.text = "Sending file ${idx + 1} of ${uris.size}"
                        binding.tvTransferFileName.text = fileName
                        binding.tvTransferPercent.text = "Preparing…"
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
                            Toast.makeText(this@SendFilesActivity, "Sent $fileName ✓", Toast.LENGTH_SHORT).show()
                            renderRecentFiles()
                        }
                    }
                } catch (e: Exception) {
                    LogManager.e("Failed to send selected file", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SendFilesActivity, "Failed to send: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun renderRecentFiles() {
        val container = binding.llFilesListContainer
        container.removeAllViews()

        val items = transferHistoryManager.getTransfers()
        if (items.isEmpty()) {
            val emptyText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(16.dp, 20.dp, 16.dp, 20.dp)
                gravity = android.view.Gravity.CENTER
                text = "No files selected yet"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@SendFilesActivity, R.color.text_secondary))
            }
            container.addView(emptyText)
            binding.tvFilesSummary.text = "0 files"
            return
        }

        binding.tvFilesSummary.text = "${items.size} files"

        for ((index, item) in items.take(15).withIndex()) {
            if (index > 0) {
                val divider = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1
                    )
                    setBackgroundColor(ContextCompat.getColor(this@SendFilesActivity, R.color.card_stroke))
                }
                container.addView(divider)
            }

            val row = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            }

            // File Icon squircle
            val iconFrame = android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(40.dp, 40.dp).apply { marginEnd = 12.dp }
                setBackgroundResource(R.drawable.bg_rounded_inset)
            }
            val iconImg = ImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(20.dp, 20.dp, android.view.Gravity.CENTER)
                setImageResource(R.drawable.ic_folder_upload)
                setColorFilter(ContextCompat.getColor(this@SendFilesActivity, R.color.icon_tint))
            }
            iconFrame.addView(iconImg)
            row.addView(iconFrame)

            // Text Info
            val textCol = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL
            }

            val nameView = TextView(this).apply {
                text = item.name
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@SendFilesActivity, R.color.text_primary))
            }
            textCol.addView(nameView)

            val metaView = TextView(this).apply {
                val sizeStr = Formatter.formatFileSize(this@SendFilesActivity, item.size)
                val statusStr = if (item.isIncoming) "Received" else "Sent"
                text = "$sizeStr · $statusStr"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@SendFilesActivity, R.color.text_secondary))
            }
            textCol.addView(metaView)
            row.addView(textCol)

            // Arrow/Status Icon on Right
            val statusIcon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(20.dp, 20.dp)
                setImageResource(if (item.isIncoming) R.drawable.ic_arrow_down_left else R.drawable.ic_arrow_up_right)
                setColorFilter(ContextCompat.getColor(this@SendFilesActivity, R.color.primary))
            }
            row.addView(statusIcon)

            container.addView(row)
        }
    }
}
