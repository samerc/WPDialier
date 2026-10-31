package com.fancyshark.wpdialer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RemindersTest {

    private val zone = TimeZone.getTimeZone("Asia/Beirut")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun relativeDelays() {
        val now = at(2026, 10, 31, 14, 0)
        assertEquals(now + 15 * 60_000L, Reminders.fireTime(ReminderDelay.IN_15_MIN, now, zone))
        assertEquals(now + 3_600_000L, Reminders.fireTime(ReminderDelay.IN_1_HOUR, now, zone))
        assertEquals(now + 3 * 3_600_000L, Reminders.fireTime(ReminderDelay.IN_3_HOURS, now, zone))
    }

    @Test
    fun tomorrowNineIsTheNextCalendarDay() {
        assertEquals(
            at(2026, 11, 1, 9, 0),
            Reminders.fireTime(ReminderDelay.TOMORROW_9, at(2026, 10, 31, 23, 59), zone),
        )
        // Just after midnight "tomorrow" is still the next day, not today.
        assertEquals(
            at(2026, 11, 2, 9, 0),
            Reminders.fireTime(ReminderDelay.TOMORROW_9, at(2026, 11, 1, 0, 5), zone),
        )
        // Year rollover.
        assertEquals(
            at(2027, 1, 1, 9, 0),
            Reminders.fireTime(ReminderDelay.TOMORROW_9, at(2026, 12, 31, 18, 0), zone),
        )
    }

    @Test
    fun tomorrowNineSurvivesADstChange() {
        // Beirut leaves DST on the last Sunday of October (2026-10-25).
        assertEquals(
            at(2026, 10, 25, 9, 0),
            Reminders.fireTime(ReminderDelay.TOMORROW_9, at(2026, 10, 24, 20, 0), zone),
        )
    }

    @Test
    fun overdueRemindersFireSoonAfterBoot() {
        val now = 1_000_000L
        assertEquals(now + 10_000L, Reminders.rearmTime(now - 60_000L, now))
        assertEquals(now + 600_000L, Reminders.rearmTime(now + 600_000L, now))
    }

    @Test
    fun encodingRoundTripsNumbersWithPauses() {
        val list = listOf(
            Reminder(2, "+961 3 039 056", 200),
            Reminder(1, "5551234,567#", 100),
        )
        val decoded = Reminders.decode(Reminders.encode(list))
        assertEquals(list.sortedBy { it.at }, decoded)
    }

    @Test
    fun corruptEntriesAreSkipped() {
        assertTrue(Reminders.decode(null).isEmpty())
        assertTrue(Reminders.decode("").isEmpty())
        assertEquals(
            listOf(Reminder(5, "123", 9)),
            Reminders.decode("garbage\u0001x\u0002y\u0002z\u00015\u00029\u0002123"),
        )
    }

    @Test
    fun aNewReminderReplacesTheSameLine() {
        val old = Reminder(1, "03 039 056", 100)
        val other = Reminder(2, "+1 212 555 1234", 300)
        val fresh = Reminder(3, "+961 3 039 056", 200)
        assertEquals(listOf(fresh, other), Reminders.withAdded(listOf(old, other), fresh))
    }
}
