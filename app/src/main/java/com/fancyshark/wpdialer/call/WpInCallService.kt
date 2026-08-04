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
                CallManager.state, CallManager.secondState, CallManager.endpoint,
            ) { first, second, endpoint ->
                val inCall = listOf(first, second).any {
                    it == Call.STATE_ACTIVE || it == Call.STATE_DIALING ||
                        it == Call.STATE_CONNECTING || it == Call.STATE_HOLDING
                }
                val onEarpiece = endpoint == null ||
                    endpoint.endpointType == CallEndpoint.TYPE_EARPIECE ||
                    endpoint.endpointType == CallEndpoint.TYPE_UNKNOWN
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
        super.onDestroy()
    }

    private val notifyCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, newState: Int) {
            if (newState != Call.STATE_RINGING) cancelIncomingNotification()
            when (newState) {
                Call.STATE_ACTIVE, Call.STATE_DIALING, Call.STATE_CONNECTING ->
                    postOngoingNotification(call)
                Call.STATE_DISCONNECTED -> cancelOngoingNotification()
                else -> {}
            }
        }
    }

    private fun postOngoingNotification(call: Call) {
        val number = call.details.handle?.schemeSpecificPart ?: "unknown"
        val s = scope ?: run {
            postOngoingNotificationNow(number)
            return
        }
        s.launch {
            val (name, _) = com.fancyshark.wpdialer.data.Repo
                .lookupCaller(this@WpInCallService, number)
            val state = CallManager.stateOf(call)
            if (state != Call.STATE_DISCONNECTED && state != Call.STATE_RINGING) {
                postOngoingNotificationNow(name ?: number)
            }
        }
    }

    private fun postOngoingNotificationNow(display: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                ONGOING_CHANNEL_ID, "Ongoing calls", NotificationManager.IMPORTANCE_LOW,
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
        val caller = Person.Builder().setName(display).setImportant(true).build()
        com.fancyshark.wpdialer.ui.AccentStore.init(this)
        val notification = Notification.Builder(this, ONGOING_CHANNEL_ID)
            .setSmallIcon(
                android.graphics.drawable.Icon.createWithResource(
                    this, com.fancyshark.wpdialer.R.drawable.ic_launcher_foreground,
                ),
            )
            .setColor(com.fancyshark.wpdialer.ui.AccentStore.accent.value.color.toArgb())
            .setStyle(Notification.CallStyle.forOngoingCall(caller, hangup))
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(ONGOING_NOTIFICATION_ID, notification) }
    }

    private fun cancelOngoingNotification() {
        getSystemService(NotificationManager::class.java).cancel(ONGOING_NOTIFICATION_ID)
    }

    override fun onCallAdded(call: Call) {
        CallManager.service = this
        CallManager.onCallAdded(call)
        call.registerCallback(notifyCallback)

        if (CallManager.stateOf(call) == Call.STATE_RINGING) {
            // Ringing: the notification's full-screen intent decides — full
            // screen when the device is locked/idle, a heads-up banner while
            // the user is actively in another app (platform behavior).
            postIncomingNotification(call)
        } else {
            // Outgoing calls are user-initiated: open the in-call UI directly.
            startActivity(
                Intent(this, InCallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(notifyCallback)
        CallManager.onCallRemoved(call)
        cancelIncomingNotification()
        if (CallManager.call.value == null) cancelOngoingNotification()
        if (call.details.disconnectCause?.code == android.telecom.DisconnectCause.MISSED) {
            postMissedNotification(call)
        }
        if (CallManager.call.value == null) CallManager.service = null
    }

    private fun postMissedNotification(call: Call) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                MISSED_CHANNEL_ID, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val number = call.details.handle?.schemeSpecificPart ?: return
        val telUri = android.net.Uri.fromParts("tel", number, null)
        val callBack = PendingIntent.getActivity(
            this, number.hashCode(),
            Intent(Intent.ACTION_CALL, telUri),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = PendingIntent.getActivity(
            this, number.hashCode() + 1,
            Intent(Intent.ACTION_SENDTO, android.net.Uri.fromParts("smsto", number, null)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, number.hashCode() + 2,
            Intent(this, com.fancyshark.wpdialer.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        com.fancyshark.wpdialer.ui.AccentStore.init(this)
        val notification = Notification.Builder(this, MISSED_CHANNEL_ID)
            .setSmallIcon(
                android.graphics.drawable.Icon.createWithResource(
                    this, com.fancyshark.wpdialer.R.drawable.ic_launcher_foreground,
                ),
            )
            .setColor(com.fancyshark.wpdialer.ui.AccentStore.accent.value.color.toArgb())
            .setContentTitle("Missed call")
            .setContentText(number)
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "call back", callBack).build())
            .addAction(Notification.Action.Builder(null, "text", text).build())
            .build()
        runCatching { nm.notify(number.hashCode(), notification) }
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        CallManager.updateEndpoint(callEndpoint)
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        CallManager.updateAvailableEndpoints(availableEndpoints)
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        CallManager.updateMuted(isMuted)
    }

    private fun postIncomingNotification(call: Call) {
        val number = call.details.handle?.schemeSpecificPart ?: "unknown"
        val s = scope ?: run {
            postIncomingNotificationNow(number, number, null)
            return
        }
        s.launch {
            val (name, photoUri) = com.fancyshark.wpdialer.data.Repo
                .lookupCaller(this@WpInCallService, number)
            val photo = photoUri?.let { uri ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                            android.graphics.BitmapFactory.decodeStream(it)
                        }
                    }.getOrNull()
                }
            }
            // The lookup may finish after the call was answered or missed.
            if (CallManager.stateOf(call) == Call.STATE_RINGING) {
                postIncomingNotificationNow(number, name ?: number, photo)
            }
        }
    }

    private fun postIncomingNotificationNow(
        number: String,
        display: String,
        photo: android.graphics.Bitmap?,
    ) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) },
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
            if (display != number) number else "incoming call",
        )
        if (photo != null) {
            views.setImageViewBitmap(com.fancyshark.wpdialer.R.id.caller_photo, photo)
            views.setViewVisibility(
                com.fancyshark.wpdialer.R.id.caller_photo, android.view.View.VISIBLE,
            )
        }
        views.setOnClickPendingIntent(com.fancyshark.wpdialer.R.id.btn_answer, answer)
        views.setOnClickPendingIntent(com.fancyshark.wpdialer.R.id.btn_decline, decline)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                android.graphics.drawable.Icon.createWithResource(
                    this, com.fancyshark.wpdialer.R.drawable.ic_launcher_foreground,
                ),
            )
            .setColor(accent)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(views)
            .setCustomHeadsUpContentView(views)
            .setCustomBigContentView(views)
            .setCategory(Notification.CATEGORY_CALL)
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
        val ringing = when {
            CallManager.state.value == Call.STATE_RINGING -> CallManager.call.value
            CallManager.secondState.value == Call.STATE_RINGING -> CallManager.secondCall.value
            else -> null
        } ?: return
        if (visible) cancelIncomingNotification() else postIncomingNotification(ringing)
    }

    companion object {
        private const val CHANNEL_ID = "incoming_calls"
        private const val MISSED_CHANNEL_ID = "missed_calls"
        private const val ONGOING_CHANNEL_ID = "ongoing_calls"
        private const val NOTIFICATION_ID = 1
        private const val ONGOING_NOTIFICATION_ID = 2
    }
}
