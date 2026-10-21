package com.bridge.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import com.bridge.android.R
import com.bridge.android.crypto.KeyStoreManager
import com.bridge.android.service.BridgeService
import com.bridge.android.ui.MainActivity
import com.bridge.android.util.LogManager

class BridgeWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_TOGGLE = "com.bridge.android.action.WIDGET_TOGGLE"
        const val ACTION_TEST_CLIPBOARD = "com.bridge.android.action.TEST_CLIPBOARD"

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val componentName = ComponentName(context, BridgeWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(componentName) ?: return
            for (id in ids) {
                updateWidget(context, appWidgetManager, id)
            }
        }

        private fun buildViews(
            context: Context,
            layoutId: Int,
            statusText: String,
            btnText: String
        ): RemoteViews {
            return RemoteViews(context.packageName, layoutId).apply {
                setTextViewText(R.id.widgetStatus, statusText)
                setTextViewText(R.id.widgetBtnToggle, btnText)

                // Main area click -> Open MainActivity
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val mainPending = PendingIntent.getActivity(
                    context,
                    100,
                    mainIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                setOnClickPendingIntent(R.id.widgetRoot, mainPending)

                // Button click -> Toggle Search
                val toggleIntent = Intent(context, BridgeWidgetProvider::class.java).apply {
                    action = ACTION_WIDGET_TOGGLE
                }
                val togglePending = PendingIntent.getBroadcast(
                    context,
                    101,
                    toggleIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                setOnClickPendingIntent(R.id.widgetBtnToggle, togglePending)
            }
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val keyStoreManager = KeyStoreManager(context)
            val isSearching = keyStoreManager.isSearchingEnabled()
            val service = BridgeService.instance
            val isConnected = service?.isConnectedFlow?.value == true

            val statusText = when {
                !isSearching -> "Off / Paused"
                isConnected -> "Connected to ${keyStoreManager.getMacName() ?: "Mac"}"
                else -> "Searching for Mac…"
            }

            val btnText = if (isSearching) "Pause" else "Resume"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val viewMapping = mapOf(
                    SizeF(100f, 40f) to buildViews(context, R.layout.widget_bridge_compact, statusText, btnText),
                    SizeF(180f, 40f) to buildViews(context, R.layout.widget_bridge, statusText, btnText),
                    SizeF(120f, 95f) to buildViews(context, R.layout.widget_bridge_tall, statusText, btnText)
                )
                appWidgetManager.updateAppWidget(appWidgetId, RemoteViews(viewMapping))
            } else {
                val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
                val minWidth = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
                val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0

                val layoutId = when {
                    minHeight >= 95 -> R.layout.widget_bridge_tall
                    minWidth in 1..170 -> R.layout.widget_bridge_compact
                    else -> R.layout.widget_bridge
                }
                appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, layoutId, statusText, btnText))
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TEST_CLIPBOARD) {
            val setText = intent.getStringExtra("clip_text")
            val force = intent.getBooleanExtra("force", true)
            LogManager.i("BridgeWidgetProvider: Received ACTION_TEST_CLIPBOARD (clip_text=$setText)")
            val service = BridgeService.instance
            if (service != null) {
                if (!setText.isNullOrEmpty()) {
                    val a11y = com.bridge.android.service.BridgeAccessibilityService.instance
                    val launchCtx: Context = if (a11y != null && com.bridge.android.service.BridgeAccessibilityService.isRunning) a11y else context
                    val actIntent = Intent(launchCtx, com.bridge.android.ui.TransparentClipReaderActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                        putExtra("set_clip", setText)
                        putExtra("force", true)
                    }
                    launchCtx.startActivity(actIntent)
                } else {
                    service.handleClipboardChanged(force = force)
                }
            }
            return
        }
        if (intent.action == ACTION_WIDGET_TOGGLE) {
            val km = KeyStoreManager(context)
            val isSearching = km.isSearchingEnabled()
            val newSearching = !isSearching
            km.setSearchingEnabled(newSearching)
            LogManager.i("BridgeWidgetProvider: Toggle search -> $newSearching")

            val service = BridgeService.instance
            if (service != null) {
                if (newSearching) {
                    service.resumeSearching()
                } else {
                    service.pauseSearching()
                }
            } else if (newSearching) {
                BridgeService.start(context)
            }

            updateAllWidgets(context)
        }
    }
}
