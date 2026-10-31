package com.fancyshark.wpdialer.call

/**
 * Pure RTT text bookkeeping (T.140 semantics as exposed by Call.RttCall):
 * text goes out character by character, and a backspace (U+0008) erases
 * the previous character on the other side.
 */
object RttText {

    const val BACKSPACE = '\b'

    /**
     * What to send so the remote side's view turns [old] into [new]: one
     * backspace per character past the common prefix, then the new tail.
     * Empty when nothing changed.
     */
    fun delta(old: String, new: String): String {
        var prefix = 0
        val max = minOf(old.length, new.length)
        while (prefix < max && old[prefix] == new[prefix]) prefix++
        val erase = old.length - prefix
        return buildString {
            repeat(erase) { append(BACKSPACE) }
            append(new, prefix, new.length)
        }
    }

    /** Appends received [incoming] text to [transcript], honoring backspaces. */
    fun applyRemote(transcript: String, incoming: String): String {
        val sb = StringBuilder(transcript)
        for (ch in incoming) {
            if (ch == BACKSPACE) {
                if (sb.isNotEmpty()) sb.setLength(sb.length - 1)
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}
