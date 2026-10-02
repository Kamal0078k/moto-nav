package dev.motonav.simulator.calls

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

enum class CallPhase { RINGING, ACTIVE, MISSED }

/**
 * A phone call to surface on the handlebar display. ACTIVE covers both an answered call and an
 * outgoing one (Android reports dialling and talking as the same state). [caller] may be blank
 * when Android did not reveal the number.
 */
data class CallAlert(val caller: String, val phase: CallPhase)

/**
 * Watches ordinary phone calls (not VoIP apps) and resolves the caller to a contact name.
 * Only the name or number leaves this object, and only towards the paired display.
 */
object CallAlerts {
    private const val PREFERENCES = "call_alerts"
    private const val KEY_ENABLED = "enabled"
    /** How long a call that rang out stays on the display before clearing. */
    private const val MISSED_CALL_MILLIS = 6_000L

    fun interface Listener {
        fun onCallAlert(alert: CallAlert?)
    }

    private val listeners = CopyOnWriteArraySet<Listener>()
    private var receiverRegistered = false
    private var appContext: Context? = null

    @Volatile
    var current: CallAlert? = null
        private set

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val clearMissed = Runnable { if (current?.phase == CallPhase.MISSED) publish(null) }

    /** Phone state tells us a call is ringing; the call log permission is what reveals its number. */
    val requiredPermissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
    )

    /** Asked for alongside [requiredPermissions]; without it outgoing calls show only the handset. */
    @Suppress("DEPRECATION")
    val requestedPermissions = requiredPermissions + Manifest.permission.PROCESS_OUTGOING_CALLS

    /** Name or number of the call being dialled, until the phone goes idle. */
    @Volatile private var outgoingCaller: String? = null

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun hasPermissions(context: Context): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) start(context) else stop()
    }

    /** Idempotent; safe to call from every activity and service start. */
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (receiverRegistered || !isEnabled(app) || !hasPermissions(app)) return
        @Suppress("DEPRECATION")
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
            addAction(Intent.ACTION_NEW_OUTGOING_CALL)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    fun stop() {
        val app = appContext
        if (receiverRegistered && app != null) runCatching { app.unregisterReceiver(receiver) }
        receiverRegistered = false
        publish(null)
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onCallAlert(current)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            if (intent.action == Intent.ACTION_NEW_OUTGOING_CALL) {
                // Arrives just before OFFHOOK for a call the rider places.
                outgoingCaller = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)?.let { describe(context, it) }
                current?.takeIf { it.phase == CallPhase.ACTIVE && it.caller.isBlank() }?.let {
                    publish(CallAlert(outgoingCaller.orEmpty(), CallPhase.ACTIVE))
                }
                return
            }
            // Android delivers RINGING twice: first without the number, then with it (READ_CALL_LOG).
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    mainHandler.removeCallbacks(clearMissed)
                    val known = current?.takeIf { it.phase == CallPhase.RINGING }?.caller
                    val caller = number?.let { describe(context, it) } ?: known ?: "Unknown caller"
                    publish(CallAlert(caller, CallPhase.RINGING))
                }
                // Answered an incoming call (keep its name), or placed an outgoing one.
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    val caller = current?.caller?.takeIf { it.isNotBlank() }
                        ?: outgoingCaller
                        ?: number?.let { describe(context, it) }
                        ?: ""
                    publish(CallAlert(caller, CallPhase.ACTIVE))
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    outgoingCaller = null
                    val rangOut = current?.takeIf { it.phase == CallPhase.RINGING }
                    if (rangOut != null) {
                        // Rang out or was rejected without answering: show it briefly as missed.
                        publish(CallAlert(rangOut.caller, CallPhase.MISSED))
                        mainHandler.removeCallbacks(clearMissed)
                        mainHandler.postDelayed(clearMissed, MISSED_CALL_MILLIS)
                    } else {
                        publish(null)
                    }
                }
            }
        }
    }

    private fun publish(alert: CallAlert?) {
        if (alert == current) return
        current = alert
        listeners.forEach { it.onCallAlert(alert) }
    }

    private fun describe(context: Context, number: String): String {
        contactName(context, number)?.let { return it }
        return PhoneNumberUtils.formatNumber(number, Locale.getDefault().country.ifBlank { "IN" }) ?: number
    }

    private fun contactName(context: Context, number: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }
}
