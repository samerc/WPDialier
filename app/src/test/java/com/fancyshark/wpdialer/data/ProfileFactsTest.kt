package com.fancyshark.wpdialer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ProfileFactsTest {

    private val today = LocalDate.of(2026, 2, 20)

    @Test
    fun parsesProviderDates() {
        assertEquals(Triple(1992, 3, 4), ProfileFacts.parseDate("1992-03-04"))
        assertEquals(Triple(1992, 3, 4), ProfileFacts.parseDate("1992-03-04T00:00:00Z"))
        assertEquals(Triple(null, 3, 4), ProfileFacts.parseDate("--03-04"))
        assertNull(ProfileFacts.parseDate("1992-13-04"))
        assertNull(ProfileFacts.parseDate("someday"))
    }

    @Test
    fun upcomingBirthdayWithAge() {
        val u = ProfileFacts.upcoming(listOf(ProfileFacts.EVENT_BIRTHDAY to "1992-03-04"), today)!!
        assertEquals(12, u.daysUntil)
        assertEquals(34, u.years)
        assertEquals(3, u.month)
        assertEquals(4, u.day)
    }

    @Test
    fun outsideWindowIsNull() {
        assertNull(ProfileFacts.upcoming(listOf(ProfileFacts.EVENT_BIRTHDAY to "1992-06-01"), today))
        // Other event types never count as "coming up".
        assertNull(ProfileFacts.upcoming(listOf(2 to "2000-02-25"), today))
    }

    @Test
    fun wrapsIntoNextYearAndTodayIsZero() {
        val dec = LocalDate.of(2026, 12, 20)
        val u = ProfileFacts.upcoming(listOf(ProfileFacts.EVENT_ANNIVERSARY to "2019-01-05"), dec)!!
        assertEquals(16, u.daysUntil)
        assertEquals(8, u.years)
        assertEquals(0, ProfileFacts.upcoming(listOf(ProfileFacts.EVENT_BIRTHDAY to "--02-20"), today)!!.daysUntil)
    }

    @Test
    fun leapDayFallsOnFeb28InCommonYears() {
        val u = ProfileFacts.upcoming(listOf(ProfileFacts.EVENT_BIRTHDAY to "2000-02-29"), today)!!
        assertEquals(8, u.daysUntil)
    }

    @Test
    fun nearestWinsBirthdayOnTies() {
        val u = ProfileFacts.upcoming(
            listOf(
                ProfileFacts.EVENT_ANNIVERSARY to "2010-03-01",
                ProfileFacts.EVENT_BIRTHDAY to "1990-03-01",
                ProfileFacts.EVENT_BIRTHDAY to "1990-03-10",
            ),
            today,
        )!!
        assertEquals(ProfileFacts.EVENT_BIRTHDAY, u.type)
        assertEquals(9, u.daysUntil)
    }

    @Test
    fun noteDumpEvents() {
        val events = ProfileFacts.noteEvents("Likes tea\nbirthday = 1990-03-04 09:00:00 +0000\nPIN = 1234")
        assertEquals(listOf(ProfileFacts.EVENT_BIRTHDAY to "1990-03-04"), events)
    }

    private fun raw(id: Long, vararg rows: DataRow) = RawSource(
        id, 1, AccountRef("a@gmail.com", "com.google"), false, false, null, false, rows.toList(),
    )

    @Test
    fun summaryCountsWhatAPartHolds() {
        val groups = mapOf(
            7L to GroupInfo(7, "family", null, AccountRef("a@gmail.com", "com.google")),
            8L to GroupInfo(8, "My Contacts", "Contacts", AccountRef("a@gmail.com", "com.google")),
        )
        val r = raw(
            1,
            DataRow(1, 1, Mime.PHONE, mapOf("data1" to "1")),
            DataRow(2, 1, Mime.PHONE, mapOf("data1" to "2")),
            DataRow(3, 1, Mime.EMAIL, mapOf("data1" to "a@x.com")),
            DataRow(4, 1, Mime.PHOTO, mapOf("data15" to byteArrayOf(1))),
            DataRow(5, 1, Mime.EVENT, mapOf("data1" to "1990-03-04", "data2" to 3L)),
            DataRow(6, 1, Mime.GROUP, mapOf("data1" to 7L)),
            DataRow(7, 1, Mime.GROUP, mapOf("data1" to 8L)),
            DataRow(8, 1, "vnd.android.cursor.item/vnd.com.whatsapp.voip.call", mapOf("data3" to "Voice call +961 3 039 056")),
        )
        val s = ProfileFacts.summarize(r, groups)
        assertEquals(2, s.phones)
        assertEquals(1, s.emails)
        assertTrue(s.photo)
        assertTrue(s.birthday)
        assertEquals(listOf("family"), s.labels)
        assertEquals(listOf("voice call"), s.appActions)
        assertEquals(1, s.privateRows)
        assertFalse(s.note)
    }

    @Test
    fun accountKinds() {
        assertEquals(AccountKind.LOCAL, AccountStyle.kindOf(null))
        assertEquals(AccountKind.LOCAL, AccountStyle.kindOf("vnd.sec.contact.phone"))
        assertEquals(AccountKind.GOOGLE, AccountStyle.kindOf("com.google"))
        assertEquals(AccountKind.EXCHANGE, AccountStyle.kindOf("com.google.android.gm.exchange"))
        assertEquals(AccountKind.OUTLOOK, AccountStyle.kindOf("com.microsoft.office.outlook.USER_ACCOUNT"))
        assertEquals(AccountKind.WHATSAPP, AccountStyle.kindOf("com.whatsapp"))
        assertEquals(AccountKind.SIM, AccountStyle.kindOf("vnd.sec.contact.sim"))
        assertEquals(AccountKind.OTHER, AccountStyle.kindOf("org.example.sync"))
        assertEquals(0xFF2F6FDE, AccountStyle.color("com.google"))
        assertEquals(AccountStyle.color("org.example.sync"), AccountStyle.color("org.example.sync"))
        assertFalse(AccountStyle.canHoldFullContact("vnd.sec.contact.sim"))
        assertTrue(AccountStyle.sameAccount(AccountRef(null, null), AccountRef("Phone", "vnd.sec.contact.phone")))
        assertFalse(AccountStyle.sameAccount(AccountRef("a", "com.google"), AccountRef("b", "com.google")))
    }

    @Test
    fun redirectsFollowChains() {
        val map = mapOf(1L to 2L, 2L to 3L, 9L to 8L, 8L to 9L)
        assertEquals(3L, ContactRedirects.resolve(map, 1L))
        assertEquals(5L, ContactRedirects.resolve(map, 5L))
        // Cycles terminate.
        ContactRedirects.resolve(map, 9L)
    }
}
