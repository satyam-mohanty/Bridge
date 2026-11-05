package com.bridge.android.notification

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.bridge.android.protocol.BridgeProtocol
import com.bridge.android.service.BridgeService
import com.bridge.android.util.LogManager

object CallSyncManager {

    data class CallSession(
        val id: String,
        var number: String?,
        var name: String?,
        var state: String, // "ringing", "answered", "ended"
        val startTime: Long = System.currentTimeMillis()
    )

    @Volatile
    private var currentCall: CallSession? = null

    private var telephonyReceiver: BroadcastReceiver? = null
    private var telephonyCallback: Any? = null

    fun register(context: Context) {
        val appContext = context.applicationContext
        val prefs = NotificationPreferences(appContext)
        if (!prefs.showIncomingCalls) {
            LogManager.i("CallSyncManager.register: incoming calls disabled in settings, skipping listener registration")
            return
        }

        // 1. Register TelephonyManager.ACTION_PHONE_STATE_CHANGED broadcast receiver
        if (telephonyReceiver == null) {
            telephonyReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                        LogManager.i("Call detected via PHONE_STATE broadcast: state=$stateStr number=${incomingNumber ?: "<hidden>"}")
                        handlePhoneState(appContext, stateStr, incomingNumber, source = "PHONE_STATE_broadcast")
                    }
                }
            }
            try {
                val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                appContext.registerReceiver(telephonyReceiver, filter)
                LogManager.i("Registered PHONE_STATE broadcast receiver for incoming call detection")
            } catch (e: Exception) {
                LogManager.e("Failed to register PHONE_STATE receiver", e)
            }
        }

        // 2. Register TelephonyCallback (Android 12+) or PhoneStateListener (< Android 12)
        try {
            val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (tm != null && ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                        override fun onCallStateChanged(state: Int) {
                            val stateStr = stateToString(state)
                            LogManager.i("Call detected via TelephonyCallback: state=$stateStr ($state)")
                            handlePhoneState(appContext, stateStr, incomingNumber = null, source = "TelephonyCallback")
                        }
                    }
                    telephonyCallback = callback
                    tm.registerTelephonyCallback(appContext.mainExecutor, callback)
                    LogManager.i("Registered TelephonyCallback for incoming calls")
                } else {
                    @Suppress("DEPRECATION")
                    val listener = object : PhoneStateListener() {
                        @Deprecated("Deprecated in Java")
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                            val stateStr = stateToString(state)
                            LogManager.i("Call detected via PhoneStateListener: state=$stateStr number=${phoneNumber ?: "<hidden>"}")
                            handlePhoneState(appContext, stateStr, phoneNumber, source = "PhoneStateListener")
                        }
                    }
                    telephonyCallback = listener
                    @Suppress("DEPRECATION")
                    tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                    LogManager.i("Registered PhoneStateListener for incoming calls")
                }
            }
        } catch (e: Exception) {
            LogManager.e("Failed to register telephony listener", e)
        }
    }

    fun unregister(context: Context) {
        val appContext = context.applicationContext
        telephonyReceiver?.let {
            try {
                appContext.unregisterReceiver(it)
            } catch (_: Exception) {}
            telephonyReceiver = null
        }

        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telephonyCallback is TelephonyCallback) {
                    tm.unregisterTelephonyCallback(telephonyCallback as TelephonyCallback)
                } else if (telephonyCallback is PhoneStateListener) {
                    @Suppress("DEPRECATION")
                    tm.listen(telephonyCallback as PhoneStateListener, PhoneStateListener.LISTEN_NONE)
                }
            } catch (_: Exception) {}
            telephonyCallback = null
        }
    }

    private fun stateToString(state: Int): String {
        return when (state) {
            TelephonyManager.CALL_STATE_RINGING -> TelephonyManager.EXTRA_STATE_RINGING
            TelephonyManager.CALL_STATE_OFFHOOK -> TelephonyManager.EXTRA_STATE_OFFHOOK
            TelephonyManager.CALL_STATE_IDLE -> TelephonyManager.EXTRA_STATE_IDLE
            else -> "UNKNOWN"
        }
    }

    private fun handlePhoneState(context: Context, stateStr: String?, incomingNumber: String?, source: String) {
        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                onCallRinging(context, incomingNumber, name = null, source = source)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                onCallAnswered(context, source = source)
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                onCallEnded(context, source = source)
            }
        }
    }

    @Synchronized
    fun onCallRinging(context: Context, number: String?, name: String?, source: String) {
        val prefs = NotificationPreferences(context)
        if (!prefs.showIncomingCalls) {
            LogManager.i("Call filtered: state=ringing caller=${name ?: number ?: "unknown"} reason=incoming_calls_disabled")
            return
        }

        val resolvedNumber = number?.trim()?.ifBlank { null }
        val resolvedName = name?.trim()?.ifBlank { null } ?: resolveContactName(context, resolvedNumber)

        val active = currentCall
        val callId = active?.id ?: resolvedNumber ?: "call_${System.currentTimeMillis()}"

        val session = CallSession(
            id = callId,
            number = resolvedNumber ?: active?.number,
            name = resolvedName ?: active?.name,
            state = "ringing"
        )
        currentCall = session

        val callerDisplay = session.name ?: session.number ?: "Unknown caller"
        LogManager.i("Call detected ($source): ringing caller=$callerDisplay id=$callId")

        val client = BridgeService.instance?.getClient()
        if (client != null) {
            val msg = BridgeProtocol.CallEventMessage(
                id = callId,
                state = "ringing",
                number = session.number,
                name = session.name
            )
            client.sendCallEvent(msg)
            LogManager.i("Call sent: state=ringing id=$callId caller=$callerDisplay")
        } else {
            LogManager.i("Call event dropped: client not connected")
        }
    }

    @Synchronized
    fun onCallAnswered(context: Context, source: String) {
        val prefs = NotificationPreferences(context)
        val active = currentCall ?: return

        if (active.state == "ringing") {
            active.state = "answered"
            val callerDisplay = active.name ?: active.number ?: "Unknown caller"
            LogManager.i("Call detected ($source): answered id=${active.id} caller=$callerDisplay")

            if (!prefs.showIncomingCalls) {
                LogManager.i("Call filtered: state=answered id=${active.id} reason=incoming_calls_disabled")
                return
            }

            val client = BridgeService.instance?.getClient()
            if (client != null) {
                val msg = BridgeProtocol.CallEventMessage(
                    id = active.id,
                    state = "answered",
                    number = active.number,
                    name = active.name
                )
                client.sendCallEvent(msg)
                LogManager.i("Call sent: state=answered id=${active.id}")
            }
        }
    }

    @Synchronized
    fun onCallEnded(context: Context, source: String) {
        val prefs = NotificationPreferences(context)
        val active = currentCall ?: return
        val wasRinging = (active.state == "ringing")
        val callId = active.id
        val number = active.number
        val name = active.name
        currentCall = null

        val callerDisplay = name ?: number ?: "Unknown caller"
        LogManager.i("Call detected ($source): ended (wasRinging=$wasRinging) id=$callId caller=$callerDisplay")

        if (!prefs.showIncomingCalls) {
            LogManager.i("Call filtered: state=ended id=$callId reason=incoming_calls_disabled")
            return
        }

        val client = BridgeService.instance?.getClient()
        if (client != null) {
            val msg = BridgeProtocol.CallEventMessage(
                id = callId,
                state = "ended",
                number = number,
                name = name
            )
            client.sendCallEvent(msg)
            LogManager.i("Call sent: state=ended (wasRinging=$wasRinging) id=$callId")
        }
    }

    /**
     * Correlates dialer notification with telephony state
     */
    fun onCallNotificationPosted(context: Context, sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        val category = notif.category
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.trim()

        LogManager.i("Call notification intercepted: pkg=${sbn.packageName} category=$category title=$title text=$text")

        // 1. If notification is a MISSED CALL notification, do NOT treat it as an incoming ringing call!
        val isMissedCall = android.app.Notification.CATEGORY_MISSED_CALL.equals(category, ignoreCase = true) ||
                title?.contains("missed call", ignoreCase = true) == true ||
                text?.contains("missed call", ignoreCase = true) == true ||
                title?.equals("Missed", ignoreCase = true) == true

        if (isMissedCall) {
            LogManager.i("Dialer notification is a missed call notification: pkg=${sbn.packageName}")
            if (currentCall?.state == "ringing") {
                onCallEnded(context, source = "dialer_missed_call_notification")
            }
            return
        }

        // 2. Correlate with currentCall if already active
        val active = currentCall
        if (active != null) {
            var updated = false
            if ((active.name == null || active.name == active.number) && !title.isNullOrBlank() && !title.equals("Incoming call", ignoreCase = true)) {
                active.name = title
                updated = true
            }
            if (active.number == null && !text.isNullOrBlank() && text.any { it.isDigit() }) {
                active.number = text
                updated = true
            }
            if (updated && active.state == "ringing") {
                val client = BridgeService.instance?.getClient()
                if (client != null) {
                    val msg = BridgeProtocol.CallEventMessage(
                        id = active.id,
                        state = "ringing",
                        number = active.number,
                        name = active.name
                    )
                    client.sendCallEvent(msg)
                    LogManager.i("Call notification enriched ringing call: id=${active.id} name=${active.name} number=${active.number}")
                }
            }
        } else {
            // Dialer notification arrived without or before phone state broadcast.
            // Verify telephony state: if telephony is available and not ringing (e.g. idle or offhook for outgoing call), do NOT start ringing
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            @Suppress("DEPRECATION")
            val callState = try {
                tm?.callState
            } catch (_: Exception) {
                null
            }

            if (callState != null && callState != TelephonyManager.CALL_STATE_RINGING) {
                LogManager.i("Ignoring dialer notification because telephony is not RINGING (callState=$callState)")
                return
            }

            val callerName = if (!title.isNullOrBlank() && !title.equals("Incoming call", ignoreCase = true)) title else null
            val callerNumber = if (!text.isNullOrBlank() && text.any { it.isDigit() }) text else null
            onCallRinging(context, callerNumber, callerName, source = "dialer_notification")
        }
    }

    fun onCallNotificationRemoved(context: Context, sbn: StatusBarNotification) {
        LogManager.i("Call notification removed: pkg=${sbn.packageName}")
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        @Suppress("DEPRECATION")
        val callState = try {
            tm?.callState
        } catch (_: Exception) {
            null
        }

        // Under no circumstances should we end a call if telephony is still RINGING or OFFHOOK
        if (callState == TelephonyManager.CALL_STATE_RINGING || callState == TelephonyManager.CALL_STATE_OFFHOOK) {
            LogManager.i("Call notification removed but TelephonyManager is still active (callState=$callState) - preserving call session")
            return
        }

        // Only end if telephony explicitly confirms IDLE and we have an active call
        if (callState == TelephonyManager.CALL_STATE_IDLE && currentCall != null) {
            LogManager.i("Call notification removed and TelephonyManager confirmed IDLE - ending call session")
            onCallEnded(context, source = "dialer_notification_removed_idle")
        }
    }

    private fun resolveContactName(context: Context, phoneNumber: String?): String? {
        if (phoneNumber.isNullOrBlank()) return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            LogManager.i("READ_CONTACTS not granted; using phone number directly")
            return null
        }

        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else null
            }
        } catch (e: Exception) {
            LogManager.e("Failed to resolve contact name for $phoneNumber", e)
            null
        }
    }
}
