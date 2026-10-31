package com.fancyshark.wpdialer.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import com.fancyshark.wpdialer.data.ReminderDelay
import com.fancyshark.wpdialer.data.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Call-back reminders: alarm fires, "remind me" from the missed-call
 * notification, the reminder notification's dismiss action, and re-arming
 * after boot. Not exported — only our own PendingIntents and the system's
 * BOOT_COMPLETED reach it. Every branch is a few prefs/alarm calls plus at
 * most one contacts lookup, far inside the broadcast window.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Reminders.rearmAll(app)
            Reminders.ACTION_FIRE -> {
                val id = intent.getLongExtra(Reminders.EXTRA_ID, -1L)
                // Cancelled from the history strip after the alarm was
                // already in flight: nothing pending, nothing to show.
                val reminder = Reminders.take(app, id) ?: return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Names resolve at fire time: the caller may have
                        // been saved as a contact since the reminder was set.
                        val name = runCatching {
                            com.fancyshark.wpdialer.data.Repo.lookupCaller(app, reminder.number).first
                        }.getOrNull()
                        post(app, reminder.id, reminder.number, name)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_DISMISS -> {
                val id = intent.getLongExtra(Reminders.EXTRA_ID, -1L)
                cancelNotification(app, id)
            }
            ACTION_REMIND_LATER -> {
                val number = intent.getStringExtra(EXTRA_NUMBER) ?: return
                Reminders.schedule(app, number, ReminderDelay.IN_1_HOUR)
                // The missed-call notice is handled now — the reminder
                // takes over.
                intent.getStringExtra(EXTRA_MISSED_TAG)?.let { tag ->
                    runCatching {
                        app.getSystemService(NotificationManager::class.java)
                            ?.cancel(tag, MissedCalls.NOTIFICATION_ID)
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_DISMISS = "com.fancyshark.wpdialer.REMINDER_DISMISS"
        const val ACTION_REMIND_LATER = "com.fancyshark.wpdialer.REMINDER_LATER"
        const val EXTRA_NUMBER = "com.fancyshark.wpdialer.REMINDER_NUMBER"
        const val EXTRA_MISSED_TAG = "com.fancyshark.wpdialer.REMINDER_MISSED_TAG"
        private const val CHANNEL_ID = "reminders"
        // Outside the call (1/2), missed (3) and voicemail (6) IDs; tagged
        // by reminder id so several reminders can show at once.
        const val NOTIFICATION_ID = 7

        fun cancelNotification(context: Context, id: Long) {
            runCatching {
                context.getSystemService(NotificationManager::class.java)
                    ?.cancel(id.toString(), NOTIFICATION_ID)
            }
        }

        /** "Remind me" PendingIntent for the missed-call notification. */
        fun remindLaterIntent(context: Context, number: String, missedTag: String): PendingIntent =
            PendingIntent.getBroadcast(
                context, number.hashCode() + 3,
                Intent(ACTION_REMIND_LATER)
                    .setClass(context, ReminderReceiver::class.java)
                    .setData(android.net.Uri.fromParts("tel", number, null))
                    .putExtra(EXTRA_NUMBER, number)
                    .putExtra(EXTRA_MISSED_TAG, missedTag),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun post(context: Context, id: Long, number: String, name: String?) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(com.fancyshark.wpdialer.R.string.notif_channel_reminders),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            val display = name ?: com.fancyshark.wpdialer.data.Repo.pretty(context, number)
            // Through the non-exported ReminderLauncher alias: MainActivity
            // only places a call for this action when it arrives that way.
            val call = PendingIntent.getActivity(
                context, id.hashCode(),
                Intent(com.fancyshark.wpdialer.MainActivity.ACTION_REMINDER_CALL)
                    .setClassName(context, com.fancyshark.wpdialer.MainActivity.REMINDER_LAUNCHER)
                    .setData(android.net.Uri.fromParts("tel", number, null))
                    .putExtra(Reminders.EXTRA_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val dismiss = PendingIntent.getBroadcast(
                context, id.hashCode() + 1,
                Intent(ACTION_DISMISS)
                    .setClass(context, ReminderReceiver::class.java)
                    .setData(android.net.Uri.parse("wpreminder:$id"))
                    .putExtra(Reminders.EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            com.fancyshark.wpdialer.ui.AccentStore.init(context)
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(
                    android.graphics.drawable.Icon.createWithResource(
                        context, com.fancyshark.wpdialer.R.drawable.ic_notification,
                    ),
                )
                .setColor(com.fancyshark.wpdialer.ui.AccentStore.accent.value.color.toArgb())
                .setContentTitle(
                    context.getString(com.fancyshark.wpdialer.R.string.notif_reminder_title, display),
                )
                .setContentText(context.getString(com.fancyshark.wpdialer.R.string.notif_reminder_text))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(call)
                .setAutoCancel(true)
                .addAction(
                    Notification.Action.Builder(
                        null,
                        context.getString(com.fancyshark.wpdialer.R.string.notif_call_back),
                        call,
                    ).build(),
                )
                .addAction(
                    Notification.Action.Builder(
                        null,
                        context.getString(com.fancyshark.wpdialer.R.string.notif_reminder_dismiss),
                        dismiss,
                    ).build(),
                )
                .build()
            runCatching { nm.notify(id.toString(), NOTIFICATION_ID, notification) }
        }
    }
}
