package com.fancyshark.wpdialer.call

import android.telecom.Call
import android.telecom.CallScreeningService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Telecom binds the default dialer's screening service for every incoming
 * call, before it rings. Decisions come from [ScreeningPolicy]; this class
 * only gathers inputs and answers. Telecom gives up on us after ~5s, so
 * the one slow input (the known-caller lookup) runs under a 3s deadline
 * and anything late, failed or unexpected answers "allow".
 *
 * Rejected calls are still logged (Telecom records them as BLOCKED, never
 * MISSED) and skip the missed-call notification, so neither Telecom's
 * SHOW_MISSED_CALLS broadcast nor our in-call service's MISSED disconnect
 * path posts one — a disallowed call is never handed to the InCallService.
 */
class WpScreeningService : CallScreeningService() {

    private var scope: CoroutineScope? = null

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val responded = AtomicBoolean(false)
        fun answer(verdict: ScreeningVerdict) {
            if (!responded.compareAndSet(false, true)) return
            runCatching { respondToCall(callDetails, responseFor(verdict)) }
        }
        val settings = runCatching {
            com.fancyshark.wpdialer.data.AppPrefs.screeningSettings(this)
        }.getOrDefault(ScreeningSettings())
        val direction = callDetails.callDirection
        if (direction != Call.Details.DIRECTION_INCOMING || !settings.any) {
            answer(ScreeningVerdict.ALLOW)
            return
        }
        val number = callDetails.handle?.schemeSpecificPart
        val presentation = callDetails.handlePresentation
        val verification = callDetails.callerNumberVerificationStatus
        // Everything but "silence unknown" decides without I/O.
        val quick = ScreeningPolicy.decide(
            settings.copy(silenceUnknown = false), direction, number, presentation,
            verification,
        ) { null }
        if (quick != ScreeningVerdict.ALLOW || !settings.silenceUnknown) {
            answer(quick)
            return
        }
        val s = scope
        if (s == null) {
            answer(ScreeningVerdict.ALLOW)
            return
        }
        val app = applicationContext
        s.launch {
            // async + timed await: the provider query itself can't be
            // interrupted, but the answer must not wait for it.
            val lookup = async(Dispatchers.IO) {
                runCatching { com.fancyshark.wpdialer.data.Repo.isKnownNumber(app, number.orEmpty()) }
                    .getOrNull()
            }
            val known = withTimeoutOrNull(LOOKUP_DEADLINE_MS) { lookup.await() }
            val verdict = runCatching {
                ScreeningPolicy.decide(
                    settings, direction, number, presentation, verification,
                ) { known }
            }.getOrDefault(ScreeningVerdict.ALLOW)
            answer(verdict)
        }
    }

    private fun responseFor(verdict: ScreeningVerdict): CallResponse =
        when (verdict) {
            ScreeningVerdict.REJECT -> CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                // Still in history (as "blocked"), but no missed-call alert.
                .setSkipCallLog(false)
                .setSkipNotification(true)
                .build()
            ScreeningVerdict.SILENCE -> CallResponse.Builder()
                .setDisallowCall(false)
                .setSilenceCall(true)
                .build()
            ScreeningVerdict.ALLOW -> CallResponse.Builder()
                .setDisallowCall(false)
                .build()
        }

    private companion object {
        const val LOOKUP_DEADLINE_MS = 3_000L
    }
}
