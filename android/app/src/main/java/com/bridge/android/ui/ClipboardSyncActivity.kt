package com.bridge.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bridge.android.R
import com.bridge.android.crypto.KeyStoreManager
import com.bridge.android.databinding.ActivityClipboardSyncBinding
import com.bridge.android.history.ClipboardHistoryManager
import com.bridge.android.service.BridgeService
import com.bridge.android.util.ThemeManager

class ClipboardSyncActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClipboardSyncBinding
    private lateinit var historyManager: ClipboardHistoryManager
    private lateinit var keyStoreManager: KeyStoreManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.applyTheme(this)
        binding = ActivityClipboardSyncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        historyManager = ClipboardHistoryManager(this)
        keyStoreManager = KeyStoreManager(this)

        setupUI()
        renderHistory()
    }

    override fun onResume() {
        super.onResume()
        renderHistory()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.switchSyncWithMac.isChecked = keyStoreManager.isSearchingEnabled()
        binding.switchSyncWithMac.setOnCheckedChangeListener { _, isChecked ->
            keyStoreManager.setSearchingEnabled(isChecked)
            val service = BridgeService.instance
            if (isChecked) {
                service?.resumeSearching() ?: BridgeService.start(this)
            } else {
                service?.pauseSearching()
            }
        }

        binding.btnSendCurrentClipboard.setOnClickListener {
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
                renderHistory()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearClipboardHistory.setOnClickListener {
            historyManager.clearHistory()
            renderHistory()
            Toast.makeText(this, "Clipboard history cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun renderHistory() {
        val container = binding.llClipboardCardsContainer
        container.removeAllViews()

        val items = historyManager.getItems()
        if (items.isEmpty()) {
            val emptyCard = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setBackgroundResource(R.drawable.bg_rounded_card)
                setPadding(16.dp, 24.dp, 16.dp, 24.dp)
                gravity = android.view.Gravity.CENTER
                orientation = LinearLayout.VERTICAL
            }
            val emptyText = TextView(this).apply {
                text = "No clipboard history yet"
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@ClipboardSyncActivity, R.color.text_secondary))
            }
            emptyCard.addView(emptyText)
            container.addView(emptyCard)
            return
        }

        val macName = keyStoreManager.getMacName() ?: "Mac"
        val phoneName = android.os.Build.MODEL ?: "Phone"

        for (item in items.take(30)) {
            val card = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 12.dp }
                setBackgroundResource(R.drawable.bg_rounded_card)
                orientation = LinearLayout.VERTICAL
                setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            }

            // Top Row: Direction & Timestamp on Left, Copy Button on Right
            val topRow = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val directionText = if (item.isIncoming) "$macName → $phoneName" else "$phoneName → $macName"
            val timeSpan = DateUtils.getRelativeTimeSpanString(
                item.timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
            )
            val headerLabel = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text = "$directionText · $timeSpan"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@ClipboardSyncActivity, R.color.text_secondary))
            }
            topRow.addView(headerLabel)

            val copyBtn = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(32.dp, 32.dp)
                setPadding(6.dp, 6.dp, 6.dp, 6.dp)
                setImageResource(R.drawable.ic_copy)
                setColorFilter(ContextCompat.getColor(this@ClipboardSyncActivity, R.color.text_secondary))
                background = ContextCompat.getDrawable(this@ClipboardSyncActivity, android.R.drawable.list_selector_background)
                contentDescription = "Copy"
                setOnClickListener {
                    val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipManager.setPrimaryClip(ClipData.newPlainText("Bridge Clipboard", item.text))
                    Toast.makeText(this@ClipboardSyncActivity, "Copied to clipboard ✓", Toast.LENGTH_SHORT).show()
                }
            }
            topRow.addView(copyBtn)
            card.addView(topRow)

            // Content text
            val bodyText = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 8.dp
                    bottomMargin = 8.dp
                }
                text = item.text
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@ClipboardSyncActivity, R.color.text_primary))
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextIsSelectable(true)
            }
            card.addView(bodyText)

            // Bottom status
            val bottomStatus = TextView(this).apply {
                text = if (item.isIncoming) {
                    "Received · Tap copy to use on this phone"
                } else {
                    "Sent · Ready to paste on your Mac"
                }
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@ClipboardSyncActivity, R.color.text_secondary))
            }
            card.addView(bottomStatus)

            container.addView(card)
        }
    }
}
