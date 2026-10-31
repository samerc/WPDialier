package com.fancyshark.wpdialer.call

import android.telecom.Call
import android.telecom.Connection
import android.telecom.TelecomManager

/** The three call-screening toggles from Settings (all default off). */
data class ScreeningSettings(
    val blockHidden: Boolean = false,
    val silenceUnknown: Boolean = false,
    val blockSpoofed: Boolean = false,
) {
    val any: Boolean get() = blockHidden || silenceUnknown || blockSpoofed
}

enum class ScreeningVerdict { ALLOW, SILENCE, REJECT }

/**
 * Pure screening decision, kept free of Telecom objects so it can be unit
 * tested. Every doubt resolves to ALLOW: a dialer that drops a real call
 * because a lookup hiccuped is far worse than one that lets spam ring.
 */
object ScreeningPolicy {

    /** Withheld caller ID: no number, or the network marked it private/unknown. */
    fun isHidden(number: String?, presentation: Int): Boolean =
        number.isNullOrBlank() ||
            presentation == TelecomManager.PRESENTATION_RESTRICTED ||
            presentation == TelecomManager.PRESENTATION_UNKNOWN

    /**
     * [isKnown] is only consulted when "silence unknown" is on (it costs a
     * provider query); it returns null when the lookup failed or timed out,
     * which allows the call to ring normally.
     */
    fun decide(
        settings: ScreeningSettings,
        direction: Int,
        number: String?,
        presentation: Int,
        verificationStatus: Int,
        isKnown: () -> Boolean?,
    ): ScreeningVerdict {
        // Outgoing (and unknown-direction) calls are never screened.
        if (direction != Call.Details.DIRECTION_INCOMING || !settings.any) {
            return ScreeningVerdict.ALLOW
        }
        val hidden = isHidden(number, presentation)
        if (settings.blockHidden && hidden) return ScreeningVerdict.REJECT
        if (settings.blockSpoofed &&
            verificationStatus == Connection.VERIFICATION_STATUS_FAILED
        ) {
            return ScreeningVerdict.REJECT
        }
        if (settings.silenceUnknown) {
            // A withheld number can't be in the contacts — no lookup needed.
            if (hidden) return ScreeningVerdict.SILENCE
            return when (runCatching(isKnown).getOrNull()) {
                false -> ScreeningVerdict.SILENCE
                else -> ScreeningVerdict.ALLOW
            }
        }
        return ScreeningVerdict.ALLOW
    }
}
