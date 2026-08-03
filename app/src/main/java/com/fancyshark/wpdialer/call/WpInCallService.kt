package com.fancyshark.wpdialer.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.InCallService

/**
 * Bound by the system Telecom framework while this app is the default dialer.
 * The system plays the ringtone; this service just surfaces the Metro in-call
 * UI (directly and via a full-screen-intent notification for the lock screen).
 */
class WpInCallService : InCallService() {

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
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                ONGOING_CHANNEL_ID, "Ongoing calls", NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val number = call.details.handle?.schemeSpecificPart ?: "unknown"
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
        val caller = Person.Builder().setName(number).setImportant(true).build()
        val notification = Notification.Builder(this, ONGOING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
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
            postIncomingNotification(call)
        }
        startActivity(
            Intent(this, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
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
        val notification = Notification.Builder(this, MISSED_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_missed)
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
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) },
        )
        val number = call.details.handle?.schemeSpecificPart ?: "unknown"
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
        val caller = Person.Builder().setName(number).setImportant(true).build()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setStyle(Notification.CallStyle.forIncomingCall(caller, decline, answer))
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

    companion object {
        private const val CHANNEL_ID = "incoming_calls"
        private const val MISSED_CHANNEL_ID = "missed_calls"
        private const val ONGOING_CHANNEL_ID = "ongoing_calls"
        private const val NOTIFICATION_ID = 1
        private const val ONGOING_NOTIFICATION_ID = 2
    }
}
