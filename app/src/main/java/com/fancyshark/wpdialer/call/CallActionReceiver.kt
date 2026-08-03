package com.fancyshark.wpdialer.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles answer/decline actions from the incoming-call notification. */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> CallManager.answer()
            ACTION_DECLINE -> CallManager.reject()
            ACTION_HANGUP -> CallManager.hangup()
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.fancyshark.wpdialer.ANSWER"
        const val ACTION_DECLINE = "com.fancyshark.wpdialer.DECLINE"
        const val ACTION_HANGUP = "com.fancyshark.wpdialer.HANGUP"
    }
}
