package com.fancyshark.wpdialer.call

import org.junit.Assert.assertEquals
import org.junit.Test

class RttTextTest {

    @Test
    fun typingSendsOnlyTheNewCharacters() {
        assertEquals("o", RttText.delta("hell", "hello"))
        assertEquals("hi", RttText.delta("", "hi"))
        assertEquals("", RttText.delta("same", "same"))
    }

    @Test
    fun deletionsSendBackspaces() {
        assertEquals("\b", RttText.delta("hello", "hell"))
        assertEquals("\b\b\b\b\b", RttText.delta("hello", ""))
    }

    @Test
    fun midTextEditsEraseBackToTheChangeThenRetype() {
        // "hello" -> "help": erase "lo", type "p".
        assertEquals("\b\bp", RttText.delta("hello", "help"))
    }

    @Test
    fun remoteBackspacesEraseTheTranscript() {
        assertEquals("hello", RttText.applyRemote("hel", "lx\bo"))
        assertEquals("", RttText.applyRemote("a", "\b\b\b"))
        assertEquals("ab", RttText.applyRemote("", "ab"))
    }
}
