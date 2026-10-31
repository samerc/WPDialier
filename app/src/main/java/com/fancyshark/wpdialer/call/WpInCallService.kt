package com.fancyshark.wpdialer.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.InCallService
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Bound by the system Telecom framework while this app is the default dialer.
 * The system plays the ringtone; this service just surfaces the Metro in-call
 * UI (directly and via a full-screen-intent notification for the lock screen).
 */
class WpInCallService : InCallService() {

    // Turns the screen off when the phone is held to the ear during a call;
    // inactive while audio is routed to speaker/Bluetooth/headset.
    private var proximityLock: PowerManager.WakeLock? = null
    private var scope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityLock =
                pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "wpdialer:proximity")
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        s.launch {
            combine(
                CallManager.state, CallManager.secondState, CallManager.route,
            ) { first, second, route ->
                val inCall = listOf(first, second).any {
                    it == Call.STATE_ACTIVE || it == Call.STATE_DIALING ||
                        it == Call.STATE_CONNECTING || it == Call.STATE_HOLDING
                }
                val onEarpiece = route == null ||
                    route == AudioRoute.EARPIECE ||
                    route == AudioRoute.UNKNOWN
                inCall && onEarpiece
            }.distinctUntilChanged().collect { hold -> setProximityHeld(hold) }
        }
    }

    private fun setProximityHeld(hold: Boolean) {
        val lock = proximityLock ?: return
        runCatching {
            if (hold && !lock.isHeld) {
                lock.acquire()
            } else if (!hold && lock.isHeld) {
                // Wait until the phone leaves the ear so the screen doesn't
                // flash on mid-call when the route flips.
                lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            }
        }
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        proximityLock?.let { runCatching { if (it.isHeld) it.release() } }
        // If Telecom unbound without per-call removals, stale dead-binder
        // calls must not survive into the next session.
        lastTelecomName.clear()
        CallManager.reset()
        CallManager.service = null
        super.onDestroy()
    }

    // Whether our in-call activity is currently in the foreground.
    private var inCallUiVisible = false

    // Last telecom-provided name posted per call — gates the details-driven
    // reposts below to actual name arrivals (details updates can be chatty).
    private val lastTelecomName = HashMap<Call, String?>()

    private val notifyCallback = object : Call.Callback() {
        // Telecom resolves its contact match / CNAP name asynchronously and
        // delivers it here after onCallAdded — an already-posted number-only
        // notification must pick it up.
        override fun onDetailsChanged(call: Call, details: Call.Details) {
            val name = CallManager.telecomName(call)
            if (name == null || name == lastTelecomName[call]) return
            lastTelecomName[call] = name
            when (CallManager.stateOf(call)) {
                Call.STATE_RINGING ->
                    if (!inCallUiVisible) postIncomingNotification(call)
                Call.STATE_ACTIVE, Call.STATE_DIALING, Call.STATE_CONNECTING ->
                    postOngoingNotification(call)
                else -> {}
            }
        }

        override fun onStateChanged(call: Call, newState: Int) {
            // Another call's state change must not kill the notification of a
            // call that is still ringing (e.g. hanging up A while B rings).
            if (newState != Call.STATE_RINGING && CallManager.ringingCall() == null) {
                cancelIncomingNotification()
            }
            when (newState) {
                Call.STATE_ACTIVE, Call.STATE_DIALING, Call.STATE_CONNECTING ->
                    postOngoingNotification(call)
                Call.STATE_DISCONNECTED -> {
                    // If another call survives (e.g. the held one dropped),
                    // its ongoing notification must come back.
                    val remaining = CallManager.call.value
                    if (remaining != null &&
                        CallManager.stateOf(remaining) != Call.STATE_RINGING
                    ) {
                        postOngoingNotification(remaining)
                    } else {
                        cancelOngoingNotification()
                    }
                }
                else -> {}
            }
        }
    }

    private fun postOngoingNotification(call: Call) {
        // Conference parents carry no handle — label them properly instead
        // of falling through to "unknown".
        if (call.details.hasProperty(Call.Details.PROPERTY_CONFERENCE)) {
            postOngoingNotificationNow(
                getString(com.fancyshark.wpdialer.R.string.call_conference),
            )
            return
        }
        // Look up the raw handle only — the localized "unknown" label is
        // for display, never a phone number to query contacts with.
        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        val fallback = CallManager.telecomName(call)
            ?: number.ifBlank { getString(com.fancyshark.wpdialer.R.string.call_unknown) }
        val s = scope
        if (s == null || number.isBlank()) {
            postOngoingNotificationNow(fallback, number)
            return
        }
        s.launch {
            val (name, _) = com.fancyshark.wpdialer.data.Repo
                .lookupCaller(this@WpInCallService, number)
            val state = CallManager.stateOf(call)
            if (state != Call.STATE_DISCONNECTED && state != Call.STATE_RINGING) {
                postOngoingNotificationNow(name ?: fallback, number)
            }
        }
    }

    // Whether the ongoing notification is currently attached to a
    // foreground-service start (must be stopped, not just cancelled).
    private var foreground = false

    private fun postOngoingNotificationNow(display: String, number: String = "") {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                ONGOING_CHANNEL_ID,
                getString(com.fancyshark.wpdialer.R.string.notif_channel_ongoing),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val open = PendingIntent.getActivity(
            this, 4,
            Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hangup = PendingIntent.getBroadcast(
            this, 5,
            Intent(this, CallActionReceiver::class.java)
                .setAction(CallActionReceiver.ACTION_HANGUP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val caller = Person.Builder().setName(display).setImportant(true)
            .apply { if (number.isNotBlank()) setUri("tel:$number") }
            .build()
        com.fancyshark.wpdialer.ui.AccentStore.init(this)
        val notification = Notification.Builder(this, ONGOING_CHANNEL_ID)
            .setSmallIcon(
                android.graphics.drawable.Icon.createWithResource(
                    this, com.fancyshark.wpdialer.R.drawable.ic_notification,
                ),
            )
            .setColor(com.fancyshark.wpdialer.ui.AccentStore.accent.value.color.toArgb())
            .setStyle(Notification.CallStyle.forOngoingCall(caller, hangup))
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        // CallStyle.forOngoingCall is only accepted from a foreground service
        // (or with a full-screen intent) — a plain notify() is dropped by the
        // system. Being foreground also keeps the process alive mid-call.
        val started = runCatching {
            startForeground(
                ONGOING_NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
            )
        }.isSuccess
        if (started) {
            foreground = true
        } else {
            runCatching { nm.notify(ONGOING_NOTIFICATION_ID, notification) }
        }
    }

    private fun cancelOngoingNotification() {
        if (foreground) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            foreground = false
        }
        getSystemService(NotificationManager::class.java).cancel(ONGOING_NOTIFICATION_ID)
    }

    override fun onCallAdded(call: Call) {
        CallManager.service = this
        CallManager.onCallAdded(call)
        call.registerCallback(notifyCallback)

        when (CallManager.stateOf(call)) {
            Call.STATE_RINGING -> {
                // Ringing: the notification's full-screen intent decides — full
                // screen when the device is locked/idle, a heads-up banner while
                // in another app (platform behavior). While our own in-call UI is
                // in front it shows its inline waiting panel — no banner on top.
                if (!inCallUiVisible) postIncomingNotification(call)
            }
            Call.STATE_SELECT_PHONE_ACCOUNT -> {
                // Placed without a SIM (another app's ACTION_CALL, a car kit…)
                // on an "ask every time" device: Telecom waits for us. Honor
                // the contact / global SIM preference silently, else ask.
                if (!autoSelectAccount(call)) launchInCallUi()
            }
            else -> {
                // Outgoing calls are user-initiated: open the in-call UI
                // directly. Calls that arrive already live (an IMS merge's new
                // conference, re-delivery after a process restart) also need
                // their ongoing notification — no state change will post it.
                launchInCallUi()
                if (CallManager.stateOf(call) != Call.STATE_NEW) postOngoingNotification(call)
            }
        }
    }

    private fun launchInCallUi(showDialpad: Boolean = false) {
        startActivity(
            Intent(this, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(InCallActivity.EXTRA_SHOW_DIALPAD, showDialpad),
        )
    }

    /** System "return to call" entry points (TelecomManager.showInCallScreen,
     *  OEM status-bar call chips). */
    override fun onBringToForeground(showDialpad: Boolean) {
        launchInCallUi(showDialpad)
    }

    private fun autoSelectAccount(call: Call): Boolean {
        val choices = CallManager.accountChoices(call)
        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        com.fancyshark.wpdialer.data.AppPrefs.init(this)
        val sims = com.fancyshark.wpdialer.data.Sims.options(this)
        val preferred = sequenceOf(
            number.takeIf { it.isNotBlank() }
                ?.let { com.fancyshark.wpdialer.data.SimPrefs.get(this, it) },
            com.fancyshark.wpdialer.data.AppPrefs.globalSim.value,
        ).filterNotNull().firstNotNullOfOrNull { flat -> sims.firstOrNull { it.flat == flat } }
            ?.handle
            ?.takeIf { choices.isEmpty() || it in choices }
        // Only one usable account: nothing to ask.
            ?: choices.singleOrNull()
            ?: return false
        call.phoneAccountSelected(preferred, false)
        return true
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(notifyCallback)
        lastTelecomName.remove(call)
        CallManager.onCallRemoved(call)
        // A surviving ringing call keeps (or regains) its notification —
        // but never relight a screen the user deliberately blanked (same
        // guard as onInCallUiVisibility).
        val stillRinging = CallManager.ringingCall()
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (stillRinging == null) {
            cancelIncomingNotification()
        } else if (!inCallUiVisible && pm?.isInteractive != false) {
            postIncomingNotification(stillRinging)
        }
        if (CallManager.call.value == null) cancelOngoingNotification()
        if (call.details.disconnectCause?.code == android.telecom.DisconnectCause.MISSED) {
            postMissedNotification(call)
        }
        // CallManager.service is deliberately kept until onDestroy: the
        // in-call activity's onPause (after the ended screen) must still
        // reach onInCallUiVisibility, or a stale "visible" flag would hide
        // the banner of a call arriving before Telecom unbinds us.
    }

    private fun postMissedNotification(call: Call) {
        val number = call.details.handle?.schemeSpecificPart ?: return
        // Shared with the Telecom missed-call broadcast path; resolves the
        // contact name before posting. Telecom's name (its contact match,
        // else the network CNAP name) backs up our lookup — the broadcast
        // path has no Call object, so only this path can pass it.
        MissedCalls.post(this, number, fallbackName = CallManager.telecomName(call))
    }

    // Android 14+ delivers audio state through these two + onMuteStateChanged.
    @androidx.annotation.RequiresApi(34)
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        CallManager.updateEndpoint(callEndpoint)
    }

    @androidx.annotation.RequiresApi(34)
    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        CallManager.updateAvailableEndpoints(availableEndpoints)
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        CallManager.updateMuted(isMuted)
    }

    // Android 13 has no CallEndpoint API — routes and mute arrive here. On
    // 14+ this legacy callback is ignored so it can't race the endpoint one.
    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: android.telecom.CallAudioState) {
        if (android.os.Build.VERSION.SDK_INT >= 34) return
        CallManager.updateAudioState(audioState)
    }

    private fun postIncomingNotification(call: Call) {
        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        val fallback = CallManager.telecomName(call)
            ?: number.ifBlank { getString(com.fancyshark.wpdialer.R.string.call_unknown) }
        val s = scope
        if (s == null || number.isBlank()) {
            postIncomingNotificationNow(number, fallback, null, CallManager.callerVerified(call))
            return
        }
        s.launch {
            val (name, photoUri) = com.fancyshark.wpdialer.data.Repo
                .lookupCaller(this@WpInCallService, number)
            val photo = photoUri?.let { uri ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        // Bounds-decode then subsample: a full-resolution
                        // display photo can exceed the binder limit inside
                        // RemoteViews, silently killing the whole ringing UI.
                        val opts = android.graphics.BitmapFactory.Options()
                        opts.inJustDecodeBounds = true
                        contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                            android.graphics.BitmapFactory.decodeStream(it, null, opts)
                        }
                        val target = 256
                        var sample = 1
                        while (opts.outWidth / (sample * 2) >= target &&
                            opts.outHeight / (sample * 2) >= target
                        ) {
                            sample *= 2
                        }
                        val decode = android.graphics.BitmapFactory.Options()
                        decode.inSampleSize = sample
                        contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                            android.graphics.BitmapFactory.decodeStream(it, null, decode)
                        }
                    }.getOrNull()
                }
            }
            // The lookup may finish after the call was answered/missed or
            // after our own in-call UI came to the foreground (fast
            // pause/resume) — don't stack a banner on top of it.
            if (CallManager.stateOf(call) == Call.STATE_RINGING && !inCallUiVisible) {
                postIncomingNotificationNow(
                    number, name ?: fallback, photo, CallManager.callerVerified(call),
                )
            }
        }
    }

    private fun postIncomingNotificationNow(
        number: String,
        display: String,
        photo: android.graphics.Bitmap?,
        verified: Boolean? = null,
    ) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(com.fancyshark.wpdialer.R.string.notif_channel_incoming),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { setSound(null, null) },
        )
        val fullScreen = PendingIntent.getActivity(
            this, 1,
            Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val answer = PendingIntent.getBroadcast(
            this, 2,
            Intent(this, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_ANSWER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = PendingIntent.getBroadcast(
            this, 3,
            Intent(this, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        com.fancyshark.wpdialer.ui.AccentStore.init(this)
        val accent = com.fancyshark.wpdialer.ui.AccentStore.accent.value.color.toArgb()
        // WP-styled custom layout: Selawik text and round Metro answer/decline
        // buttons (CallStyle's system template allows neither).
        val views = android.widget.RemoteViews(
            packageName, com.fancyshark.wpdialer.R.layout.notification_incoming,
        )
        views.setTextViewText(com.fancyshark.wpdialer.R.id.caller_name, display)
        views.setTextViewText(
            com.fancyshark.wpdialer.R.id.caller_number,
            if (display != number && number.isNotBlank()) {
                number
            } else {
                getString(com.fancyshark.wpdialer.R.string.notif_incoming_call)
            },
        )
        if (photo != null) {
            views.setImageViewBitmap(com.fancyshark.wpdialer.R.id.caller_photo, photo)
            views.setViewVisibility(
                com.fancyshark.wpdialer.R.id.caller_photo, android.view.View.VISIBLE,
            )
        }
        // STIR/SHAKEN: accent check for a verified number, red warning for a
        // failed one (likely spoofed). Spans survive the RemoteViews parcel.
        if (verified != null) {
            val mark = if (verified) Char(0x2713) else Char(0x26A0)
            val label = getString(
                if (verified) {
                    com.fancyshark.wpdialer.R.string.call_verified
                } else {
                    com.fancyshark.wpdialer.R.string.call_spoof_warning
                },
            )
            val text = android.text.SpannableString("$mark $label")
            text.setSpan(
                android.text.style.ForegroundColorSpan(
                    if (verified) accent else com.fancyshark.wpdialer.ui.Metro.Red.toArgb(),
                ),
                0, if (verified) 1 else text.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            views.setTextViewText(com.fancyshark.wpdialer.R.id.caller_verification, text)
            views.setViewVisibility(
                com.fancyshark.wpdialer.R.id.caller_verification, android.view.View.VISIBLE,
            )
        }
        views.setOnClickPendingIntent(com.fancyshark.wpdialer.R.id.btn_answer, answer)
        views.setOnClickPendingIntent(com.fancyshark.wpdialer.R.id.btn_decline, decline)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                android.graphics.drawable.Icon.createWithResource(
                    this, com.fancyshark.wpdialer.R.drawable.ic_notification,
                ),
            )
            .setColor(accent)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(views)
            .setCustomHeadsUpContentView(views)
            .setCustomBigContentView(views)
            .setCategory(Notification.CATEGORY_CALL)
            // The caller as a Person: Do Not Disturb's "allow calls from
            // contacts / starred" filter matches the notification by this
            // tel: URI — without it an allowed caller rings with no UI.
            .addPerson(
                Person.Builder().setName(display)
                    .apply { if (number.isNotBlank()) setUri("tel:$number") }
                    .build(),
            )
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, notification) }
    }

    private fun cancelIncomingNotification() {
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /**
     * While our own incoming-call UI is in front, the incoming notification
     * would double up as a heads-up banner on top of it — hide it, and
     * repost if the user navigates away mid-ring.
     */
    fun onInCallUiVisibility(visible: Boolean) {
        inCallUiVisible = visible
        val ringing = CallManager.ringingCall() ?: return
        if (visible) {
            cancelIncomingNotification()
        } else {
            // Skip the repost when the screen just went off (power button
            // during ring) — the full-screen intent would relight it.
            val pm = getSystemService(PowerManager::class.java)
            if (pm?.isInteractive != false) postIncomingNotification(ringing)
        }
    }

    companion object {
        private const val CHANNEL_ID = "incoming_calls"
        private const val ONGOING_CHANNEL_ID = "ongoing_calls"
        private const val NOTIFICATION_ID = 1
        private const val ONGOING_NOTIFICATION_ID = 2
    }
}
