package com.fancyshark.wpdialer.call

import android.telecom.Call
import android.telecom.Connection
import android.telecom.TelecomManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreeningPolicyTest {

    private val IN = Call.Details.DIRECTION_INCOMING
    private val OUT = Call.Details.DIRECTION_OUTGOING
    private val ALLOWED = TelecomManager.PRESENTATION_ALLOWED
    private val NOT_VERIFIED = Connection.VERIFICATION_STATUS_NOT_VERIFIED
    private val FAILED = Connection.VERIFICATION_STATUS_FAILED
    private val all = ScreeningSettings(blockHidden = true, silenceUnknown = true, blockSpoofed = true)

    private fun decide(
        settings: ScreeningSettings,
        direction: Int = IN,
        number: String? = "+15551234567",
        presentation: Int = ALLOWED,
        verification: Int = NOT_VERIFIED,
        known: Boolean? = true,
    ) = ScreeningPolicy.decide(settings, direction, number, presentation, verification) { known }

    @Test
    fun everythingOffAllows() {
        assertEquals(ScreeningVerdict.ALLOW, decide(ScreeningSettings(), number = null, known = false))
    }

    @Test
    fun outgoingCallsAreNeverScreened() {
        assertEquals(ScreeningVerdict.ALLOW, decide(all, direction = OUT, number = null, known = false))
    }

    @Test
    fun hiddenNumbersAreRejectedWhenEnabled() {
        val s = ScreeningSettings(blockHidden = true)
        assertEquals(ScreeningVerdict.REJECT, decide(s, number = null))
        assertEquals(ScreeningVerdict.REJECT, decide(s, number = ""))
        assertEquals(
            ScreeningVerdict.REJECT,
            decide(s, presentation = TelecomManager.PRESENTATION_RESTRICTED),
        )
        assertEquals(
            ScreeningVerdict.REJECT,
            decide(s, presentation = TelecomManager.PRESENTATION_UNKNOWN),
        )
        assertEquals(ScreeningVerdict.ALLOW, decide(s))
    }

    @Test
    fun payphonesAreNotHidden() {
        assertFalse(ScreeningPolicy.isHidden("5551234", TelecomManager.PRESENTATION_PAYPHONE))
        assertTrue(ScreeningPolicy.isHidden(" ", ALLOWED))
    }

    @Test
    fun failedVerificationIsRejectedOnlyWhenEnabled() {
        assertEquals(
            ScreeningVerdict.REJECT,
            decide(ScreeningSettings(blockSpoofed = true), verification = FAILED),
        )
        assertEquals(
            ScreeningVerdict.ALLOW,
            decide(ScreeningSettings(blockSpoofed = true), verification = Connection.VERIFICATION_STATUS_PASSED),
        )
        assertEquals(
            ScreeningVerdict.ALLOW,
            decide(ScreeningSettings(blockHidden = true), verification = FAILED),
        )
    }

    @Test
    fun unknownCallersAreSilencedNotRejected() {
        val s = ScreeningSettings(silenceUnknown = true)
        assertEquals(ScreeningVerdict.SILENCE, decide(s, known = false))
        assertEquals(ScreeningVerdict.ALLOW, decide(s, known = true))
        // Withheld numbers can't be known: silenced without a lookup.
        assertEquals(ScreeningVerdict.SILENCE, decide(s, number = null, known = true))
    }

    @Test
    fun failedOrThrowingLookupAllows() {
        val s = ScreeningSettings(silenceUnknown = true)
        assertEquals(ScreeningVerdict.ALLOW, decide(s, known = null))
        assertEquals(
            ScreeningVerdict.ALLOW,
            ScreeningPolicy.decide(s, IN, "5551234", ALLOWED, NOT_VERIFIED) {
                throw IllegalStateException("provider down")
            },
        )
    }

    @Test
    fun rejectionWinsOverSilence() {
        assertEquals(ScreeningVerdict.REJECT, decide(all, number = null, known = false))
        assertEquals(ScreeningVerdict.REJECT, decide(all, verification = FAILED, known = false))
    }

    @Test
    fun lookupSkippedWhenNotSilencing() {
        var asked = false
        ScreeningPolicy.decide(ScreeningSettings(blockHidden = true), IN, "5551234", ALLOWED, NOT_VERIFIED) {
            asked = true
            false
        }
        assertFalse(asked)
    }
}
