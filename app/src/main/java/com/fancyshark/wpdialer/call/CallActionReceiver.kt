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
                val number = intent.getStringExtra(
                    android.telecom.TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER,
                )
                if (count == 0) {
                    MissedCalls.cancelAll(context)
                } else if (number != null) {
                    // Same tag+id as the in-call service path, so at worst
                    // the two paths replace rather than duplicate.
                    MissedCalls.post(context, number)
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
