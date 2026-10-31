package com.fancyshark.wpdialer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The number-identity rules CLAUDE.md mandates everywhere (numbersMatch /
 * numberKey) — national vs E.164 spellings must unify, different lines and
 * service codes must not.
 */
class NumberMatchingTest {

    @Test
    fun nationalAndInternationalSpellingsMatch() {
        assertTrue(Repo.numbersMatch("03 039 056", "+961 3 039 056"))
        assertTrue(Repo.numbersMatch("+961 3 039 056", "03039056"))
        assertTrue(Repo.numbersMatch("00961 3 039 056", "+9613039056"))
        assertTrue(Repo.numbersMatch("(555) 123-4567", "+1 555 123 4567"))
    }

    @Test
    fun differentLinesDoNotMatch() {
        assertFalse(Repo.numbersMatch("+1 212 555 1234", "+1 212 555 1235"))
        assertFalse(Repo.numbersMatch("5551234", "5551235"))
    }

    @Test
    fun shortNumbersOnlyMatchExactly() {
        assertTrue(Repo.numbersMatch("112", "112"))
        assertFalse(Repo.numbersMatch("1234", "991234"))
    }

    @Test
    fun emptyNumbersNeverMatch() {
        assertFalse(Repo.numbersMatch("", ""))
        assertFalse(Repo.numbersMatch("", "5551234"))
    }

    @Test
    fun postDialDigitsAreIgnored() {
        assertTrue(Repo.numbersMatch("+9613039056,,123", "+961 3 039 056"))
        assertTrue(Repo.numbersMatch("5551234;99#", "5551234"))
        assertEquals(Repo.numberKey("+9613039056"), Repo.numberKey("+9613039056,123"))
    }

    @Test
    fun networkPortionCutsAtFirstPauseOrWait() {
        assertEquals("5551234", Repo.networkPortion("5551234,123#"))
        assertEquals("5551234", Repo.networkPortion("5551234;1"))
        assertEquals("5551234", Repo.networkPortion("5551234p1w2"))
        assertEquals("+961 3 039 056", Repo.networkPortion("+961 3 039 056"))
    }

    @Test
    fun serviceCodesAreOpaque() {
        assertTrue(Repo.numbersMatch("*100#", "*100#"))
        assertTrue(Repo.numbersMatch("*100 #", "*100#"))
        assertFalse(Repo.numbersMatch("*100#", "100"))
        assertFalse(Repo.numbersMatch("*100#", "*101#"))
        assertEquals("*100#", Repo.numberKey("*100#"))
        assertFalse(Repo.numberKey("*100#") == Repo.numberKey("100"))
    }

    @Test
    fun numberKeyIsLastSevenSignificantDigits() {
        assertEquals("3039056", Repo.numberKey("+961 3 039 056"))
        assertEquals("3039056", Repo.numberKey("03 039 056"))
        assertEquals("5551234", Repo.numberKey("+1 (212) 555-1234"))
    }

    @Test
    fun actionNumberKeyPrefersLabelDigits() {
        assertEquals("9613039056", Repo.actionNumberKey("Voice call +961 3 039 056", "12345@s.whatsapp.net"))
        // Letters p/w in app names are text, not pause characters.
        assertEquals("9613039056", Repo.actionNumberKey("WhatsApp +961 3 039 056", null))
    }

    @Test
    fun actionNumberKeyFallsBackToData1() {
        assertEquals("9613039056", Repo.actionNumberKey("Message", "9613039056@s.whatsapp.net"))
        assertNull(Repo.actionNumberKey("Message", "123"))
    }
}
