package com.fancyshark.wpdialer.widget

import android.provider.CallLog
import com.fancyshark.wpdialer.data.HistoryItem
import com.fancyshark.wpdialer.ui.initialsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherLogicTest {

    private var nextId = 0L
    private fun call(number: String, type: Int = CallLog.Calls.OUTGOING_TYPE) =
        HistoryItem(id = nextId++, number = number, name = null, type = type, date = 0L)

    @Test
    fun topCalleesCountsSpellingsOfOneLineTogether() {
        // History is newest first.
        val history = listOf(
            call("+961 3 039 056"),
            call("71 222 333"),
            call("03 039 056", CallLog.Calls.INCOMING_TYPE),
            call("03039056"),
            call("71 222 333"),
            call("05 111 222"),
        )
        val top = Shortcuts.topCallees(history, 3)
        assertEquals(3, top.size)
        assertEquals(3, top[0].count)
        // The newest spelling represents the line.
        assertEquals("+961 3 039 056", top[0].number)
        assertEquals("71 222 333", top[1].number)
        assertEquals(1, top[2].count)
    }

    @Test
    fun topCalleesIgnoresMissedRejectedBlockedAndBlank() {
        val history = listOf(
            call("71 222 333", CallLog.Calls.MISSED_TYPE),
            call("71 222 333", CallLog.Calls.MISSED_TYPE),
            call("71 222 333", CallLog.Calls.REJECTED_TYPE),
            call("05 111 222", CallLog.Calls.BLOCKED_TYPE),
            call(""),
            call("70 999 888"),
        )
        val top = Shortcuts.topCallees(history, 5)
        assertEquals(listOf("70 999 888"), top.map { it.number })
    }

    @Test
    fun topCalleesTiesGoToTheMostRecent() {
        val history = listOf(call("70 999 888"), call("71 222 333"), call("71 222 333"), call("70 999 888"))
        val top = Shortcuts.topCallees(history, 1)
        assertEquals("70 999 888", top.single().number)
    }

    @Test
    fun missedFromMatchesAnyOfTheContactsNumbers() {
        val missed = listOf("+961 3 039 056", "71222333", "05 111 222", "03039056")
        assertEquals(3, ContactTiles.missedFrom(missed, listOf("03 039 056", "+961 71 222 333")))
        assertEquals(0, ContactTiles.missedFrom(missed, emptyList()))
        assertEquals(0, ContactTiles.missedFrom(emptyList(), listOf("03 039 056")))
    }

    @Test
    fun missedFromDoesNotConfuseServiceCodesWithNumbers() {
        assertEquals(0, ContactTiles.missedFrom(listOf("*123#"), listOf("123")))
    }

    @Test
    fun sampleSizeKeepsShortSideAtOrAboveTarget() {
        assertEquals(1, ContactTiles.sampleSizeFor(300, 300, 300))
        assertEquals(1, ContactTiles.sampleSizeFor(599, 800, 300))
        assertEquals(2, ContactTiles.sampleSizeFor(720, 720, 300))
        assertEquals(4, ContactTiles.sampleSizeFor(4000, 3000, 600))
        // Smaller than the target: never upsample.
        assertEquals(1, ContactTiles.sampleSizeFor(96, 96, 300))
        assertEquals(1, ContactTiles.sampleSizeFor(0, 0, 300))
        val s = ContactTiles.sampleSizeFor(3024, 4032, 300)
        assertTrue(3024 / s >= 300 && 3024 / (s * 2) < 300)
    }

    @Test
    fun tileActionFallsBackToProfile() {
        assertEquals(TileAction.CALL, TileAction.of("call"))
        assertEquals(TileAction.PROFILE, TileAction.of("profile"))
        assertEquals(TileAction.PROFILE, TileAction.of(null))
        assertEquals(TileAction.PROFILE, TileAction.of("garbage"))
    }

    @Test
    fun initialsForTiles() {
        assertEquals("JD", initialsOf("John Ronald Doe"))
        assertEquals("M", initialsOf("  Madonna "))
        assertEquals("?", initialsOf(""))
    }
}
