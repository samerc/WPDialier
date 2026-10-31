package com.fancyshark.wpdialer.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialCodesTest {

    @Test
    fun secretCodesExtractTheirDigits() {
        assertEquals(SpecialCode.Secret("4636"), SpecialCodes.detect("*#*#4636#*#*"))
        assertEquals(SpecialCode.Secret("0"), SpecialCodes.detect("*#*#0#*#*"))
    }

    @Test
    fun incompleteOrMalformedSecretCodesAreIgnored() {
        assertNull(SpecialCodes.detect("*#*#4636#*#"))
        assertNull(SpecialCodes.detect("*#*##*#*"))
        assertNull(SpecialCodes.detect("*#*#46*36#*#*"))
        assertNull(SpecialCodes.detect("1*#*#4636#*#*"))
    }

    @Test
    fun deviceIdCodeIsExact() {
        assertEquals(SpecialCode.DeviceId, SpecialCodes.detect("*#06#"))
        assertNull(SpecialCodes.detect("*#06"))
        assertNull(SpecialCodes.detect("*#061#"))
    }

    @Test
    fun ordinaryUssdAndNumbersAreNotSpecial() {
        assertNull(SpecialCodes.detect("*123#"))
        assertNull(SpecialCodes.detect("#31#5551234"))
        assertNull(SpecialCodes.detect("5551234"))
        assertNull(SpecialCodes.detect(""))
    }

    @Test
    fun pauseAndWaitNeverStartTheNumber() {
        assertFalse(SpecialCodes.canAppendPostDial("", ','))
        assertFalse(SpecialCodes.canAppendPostDial("", ';'))
        assertTrue(SpecialCodes.canAppendPostDial("555", ','))
        assertTrue(SpecialCodes.canAppendPostDial("555", ';'))
    }

    @Test
    fun waitNeverFollowsAWaitButPausesStack() {
        assertFalse(SpecialCodes.canAppendPostDial("555;", ';'))
        assertTrue(SpecialCodes.canAppendPostDial("555,", ','))
        assertTrue(SpecialCodes.canAppendPostDial("555,", ';'))
        assertTrue(SpecialCodes.canAppendPostDial("555;", ','))
        assertFalse(SpecialCodes.canAppendPostDial("555", '5'))
    }

    @Test
    fun splitsAtTheFirstPostDialCharacter() {
        assertEquals("5551234" to "", SpecialCodes.splitPostDial("5551234"))
        assertEquals("5551234" to ",,12;34", SpecialCodes.splitPostDial("5551234,,12;34"))
        assertEquals("5551234" to ";1", SpecialCodes.splitPostDial("5551234;1"))
        assertTrue(SpecialCodes.hasPostDial("1,2"))
        assertFalse(SpecialCodes.hasPostDial("*123#"))
    }
}
