package com.fancyshark.wpdialer.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles answer/decline actions from the incoming-call notification, plus
 * Telecom's missed-call broadcast. Declaring the latter makes Telecom
 * delegate missed-call notifications to us instead of posting its own
 * (the "Call Management" duplicate on ColorOS).
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> CallManager.answer()
            ACTION_DECLINE -> CallManager.reject()
            ACTION_HANGUP -> CallManager.hangup()
            android.telecom.TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION -> {
                val count = intent.getIntExtra(
                    android.telecom.TelecomManager.EXTRA_NOTIFICATION_COUNT, 0,
                )
                // Telecom only includes the number when count == 1.
                val number = intent.getStringExtra(
                    android.telecom.TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER,
                )
                when {
                    count == 0 -> MissedCalls.cancelAll(context)
                    number != null -> {
                        // goAsync keeps the process alive through the async
                        // name lookup — this broadcast is often the only
                        // thing that woke the process, and without it the
                        // notification would silently never post.
                        val pending = goAsync()
                        MissedCalls.post(context, number) { pending.finish() }
                    }
                    else -> MissedCalls.postSummary(context, count)
                }
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.fancyshark.wpdialer.ANSWER"
        const val ACTION_DECLINE = "com.fancyshark.wpdialer.DECLINE"
        const val ACTION_HANGUP = "com.fancyshark.wpdialer.HANGUP"
    }
}
