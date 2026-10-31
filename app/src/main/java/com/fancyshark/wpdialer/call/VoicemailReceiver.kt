package com.fancyshark.wpdialer.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.PhoneAccountHandle
import android.telephony.TelephonyManager
import androidx.compose.ui.graphics.toArgb

/**
 * Telephony's message-waiting indicator, delivered to the default dialer.
 * Declaring a receiver for it is what stops the system posting its own
 * voicemail notification (com.android.phone checks queryBroadcastReceivers
 * on our package, then sends with receiver permission READ_PHONE_STATE).
 *
 * The broadcast is explicit from the phone process but the receiver has to
 * be exported, and nothing lets us verify the sender — so the extras are
 * treated as untrusted: only a count and a refresh flag are read, and the
 * notification's tap runs our own voicemail flow, never an intent from the
 * extras. Worst case a spoofed broadcast shows (or clears) this notice.
 * No I/O here — a cheap synchronous post, well inside the ANR window.
 */
class VoicemailReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_SHOW_VOICEMAIL_NOTIFICATION) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val handle = runCatching {
            intent.getParcelableExtra(
                TelephonyManager.EXTRA_PHONE_ACCOUNT_HANDLE, PhoneAccountHandle::class.java,
            )
        }.getOrNull()
        // One notice per SIM: a dual-SIM phone has a voicemail box on each.
        val tag = handle?.id ?: "default"
        // Telephony omits the count when there is nothing waiting; a present
        // but unreadable count means "some, number unknown".
        val count = if (intent.hasExtra(TelephonyManager.EXTRA_NOTIFICATION_COUNT)) {
            intent.getIntExtra(TelephonyManager.EXTRA_NOTIFICATION_COUNT, -1)
        } else {
            0
        }
        if (count == 0) {
            runCatching { nm.cancel(tag, NOTIFICATION_ID) }
            return
        }
        // Refresh = re-sent state (boot, SIM change): update silently.
        val refresh = intent.getBooleanExtra(TelephonyManager.EXTRA_IS_REFRESH, false)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(com.fancyshark.wpdialer.R.string.notif_channel_voicemail),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val call = PendingIntent.getActivity(
            context, tag.hashCode(),
            Intent(com.fancyshark.wpdialer.MainActivity.ACTION_CALL_VOICEMAIL)
                .setClassName(context, com.fancyshark.wpdialer.MainActivity.VOICEMAIL_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        com.fancyshark.wpdialer.ui.AccentStore.init(context)
        val title = if (count > 1) {
            context.getString(com.fancyshark.wpdialer.R.string.notif_voicemail_many, count)
        } else {
            context.getString(com.fancyshark.wpdialer.R.string.notif_voicemail)
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(
                android.graphics.drawable.Icon.createWithResource(
                    context, com.fancyshark.wpdialer.R.drawable.ic_notification,
                ),
            )
            .setColor(com.fancyshark.wpdialer.ui.AccentStore.accent.value.color.toArgb())
            .setContentTitle(title)
            .setContentText(
                context.getString(com.fancyshark.wpdialer.R.string.notif_voicemail_text),
            )
            .setCategory(Notification.CATEGORY_CALL)
            .setContentIntent(call)
            .setAutoCancel(true)
            // A count bump alerts once; a refresh never does.
            .setOnlyAlertOnce(refresh)
            // Framework Builder has no setSilent: a group child with
            // summary-only alerting posts without sound or vibration.
            .apply {
                if (refresh) {
                    setGroup(REFRESH_GROUP)
                    setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                }
            }
            .build()
        runCatching { nm.notify(tag, NOTIFICATION_ID, notification) }
    }

    companion object {
        private const val CHANNEL_ID = "voicemail"
        private const val NOTIFICATION_ID = 6
        private const val REFRESH_GROUP = "voicemail_refresh"
    }
}
