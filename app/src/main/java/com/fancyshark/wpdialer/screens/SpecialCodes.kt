package com.fancyshark.wpdialer.screens

/** A dialpad entry that acts on its own, without pressing call. */
sealed interface SpecialCode {
    /** `*#*#<digits>#*#*` — handed to the platform's secret-code receivers. */
    data class Secret(val code: String) : SpecialCode

    /** `*#06#` — device identifiers (IMEI/MEID). */
    data object DeviceId : SpecialCode
}

/**
 * Pure dialpad string rules (AOSP SpecialCharSequenceMgr + DialpadFragment
 * conventions), kept Android-free so they're JVM unit-testable.
 */
object SpecialCodes {

    const val PAUSE = ','
    const val WAIT = ';'

    private val SECRET = Regex("""^\*#\*#([0-9]+)#\*#\*$""")

    /**
     * Detected as the user types, like AOSP: a match fires immediately.
     * Ordinary USSD (`*123#`) is NOT special here — it goes out through a
     * normal call and Telecom handles it.
     */
    fun detect(input: String): SpecialCode? {
        if (input == "*#06#") return SpecialCode.DeviceId
        SECRET.matchEntire(input)?.let { return SpecialCode.Secret(it.groupValues[1]) }
        return null
    }

    /**
     * Whether a pause/wait may be appended to [current] (AOSP canAddDigit):
     * never as the first character, and a wait never right after a wait.
     */
    fun canAppendPostDial(current: String, ch: Char): Boolean {
        if (ch != PAUSE && ch != WAIT) return false
        if (current.isEmpty()) return false
        if (ch == WAIT && current.last() == WAIT) return false
        return true
    }

    /** True when [number] carries post-dial tones (pause or wait). */
    fun hasPostDial(number: String): Boolean = number.any { it == PAUSE || it == WAIT }

    /**
     * Splits at the first pause/wait: the dialable part (formatted and used
     * for contact / SIM lookups) and the post-dial sequence (shown verbatim,
     * sent as tones by Telecom).
     */
    fun splitPostDial(number: String): Pair<String, String> {
        val i = number.indexOfFirst { it == PAUSE || it == WAIT }
        return if (i < 0) number to "" else number.substring(0, i) to number.substring(i)
    }
}
