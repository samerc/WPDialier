package com.fancyshark.wpdialer.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Missed-call notifications. Shared by [WpInCallService] (disconnect cause
 * MISSED) and [CallActionReceiver] (Telecom's missed-call broadcast, which
 * also stops the system posting its own duplicate on some OEMs).
 */
object MissedCalls {

    private const val CHANNEL_ID = "missed_calls"
    const val NOTIFICATION_ID = 3
    private const val SUMMARY_TAG = "summary"

    // Bumped by cancelAll so a lookup that was in flight when the user
    // acknowledged the calls doesn't resurrect a notification afterwards.
    @Volatile
    private var generation = 0

    fun post(
        context: Context,
        number: String,
        fallbackName: String? = null,
        onDone: (() -> Unit)? = null,
    ) {
        val app = context.applicationContext
        val gen = generation
        // Detached scope on purpose: the in-call service is torn down right
        // after the last call ends, which would cancel a service-scoped
        // lookup before the name resolves.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val name = runCatching {
                    com.fancyshark.wpdialer.data.Repo.lookupCaller(app, number).first
                }.getOrNull()
                if (gen == generation) postNow(app, number, name, fallbackName)
            } finally {
                onDone?.invoke()
            }
        }
    }

    /**
     * Telecom's restore broadcast (e.g. after reboot) carries only a count
     * when more than one call was missed — post an aggregate.
     */
    fun postSummary(context: Context, count: Int) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, nm)
        val open = PendingIntent.getActivity(
            context, SUMMARY_TAG.hashCode(),
            Intent(context, com.fancyshark.wpdialer.MainActivity::class.java),
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
            .setContentTitle(context.getString(com.fancyshark.wpdialer.R.string.notif_missed_call))
            .setContentText(
                context.getString(com.fancyshark.wpdialer.R.string.notif_missed_many, count),
            )
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(SUMMARY_TAG, NOTIFICATION_ID, notification) }
        // Sync — the receiver's goAsync window must cover the tile refresh.
        com.fancyshark.wpdialer.widget.TileWidget.updateAllSync(context)
    }

    private fun ensureChannel(context: Context, nm: NotificationManager) {
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(com.fancyshark.wpdialer.R.string.notif_channel_missed),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
    }

    private fun postNow(
        context: Context,
        number: String,
        name: String?,
        fallbackName: String? = null,
    ) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        // The service path and the Telecom broadcast both post for the same
        // miss. If our contacts lookup failed but a notification for this
        // number is already showing (possibly with the real name resolved),
        // keep it — a telecom/CNAP fallbackName must not overwrite it either
        // (it only fills fresh posts), so the guard checks the lookup name.
        if (name == null &&
            runCatching {
                nm.activeNotifications.any { it.id == NOTIFICATION_ID && it.tag == number }
            }.getOrDefault(false)
        ) {
            return
        }
        val display = name ?: fallbackName
        ensureChannel(context, nm)
        val telUri = android.net.Uri.fromParts("tel", number, null)
        // Explicit intent into our own dialpad (prefilled) — an implicit
        // ACTION_CALL PendingIntent could be won by any dialer-filter app,
        // and would bypass our SIM-preference flow.
        val callBack = PendingIntent.getActivity(
            context, number.hashCode(),
            Intent(context, com.fancyshark.wpdialer.MainActivity::class.java)
                .setAction(Intent.ACTION_DIAL)
                .setData(telUri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = PendingIntent.getActivity(
            context, number.hashCode() + 1,
            Intent(Intent.ACTION_SENDTO, android.net.Uri.fromParts("smsto", number, null)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            context, number.hashCode() + 2,
            Intent(context, com.fancyshark.wpdialer.MainActivity::class.java),
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
            .setContentTitle(context.getString(com.fancyshark.wpdialer.R.string.notif_missed_call))
            .setContentText(display ?: com.fancyshark.wpdialer.data.Repo.pretty(context, number))
            .setCategory(Notification.CATEGORY_MISSED_CALL)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(com.fancyshark.wpdialer.R.string.notif_call_back),
                    callBack,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(com.fancyshark.wpdialer.R.string.notif_text),
                    text,
                ).build(),
            )
            .build()
        // Tag by number with a fixed ID outside the call-notification ID
        // space (1/2) — number.hashCode() as the ID could collide with them.
        runCatching { nm.notify(number, NOTIFICATION_ID, notification) }
        // Sync: post() runs this inside a goAsync-backed IO coroutine — an
        // async refresh would outlive the receiver window and get frozen.
        com.fancyshark.wpdialer.widget.TileWidget.updateAllSync(context)
    }

    /** Telecom sends count=0 when the user has seen the call log. */
    fun cancelAll(context: Context) {
        generation += 1
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.activeNotifications
                .filter { it.id == NOTIFICATION_ID }
                .forEach { nm.cancel(it.tag, NOTIFICATION_ID) }
        }
        // No tile refresh here: MainActivity.onResume already refreshes on
        // its IO scope, and the receiver's count==0 branch refreshes inside
        // its own goAsync window — doing it here would either jank the
        // resume main thread (sync) or escape the receiver window (async).
    }
}
