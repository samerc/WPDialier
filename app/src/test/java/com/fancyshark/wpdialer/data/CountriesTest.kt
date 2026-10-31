package com.fancyshark.wpdialer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountriesTest {

    @Test
    fun nationalNumbersResolveViaRegion() {
        assertEquals("+9613039056", Countries.e164OrNull("03 039 056", "LB"))
        assertEquals("+12125551234", Countries.e164OrNull("(212) 555-1234", "US"))
    }

    @Test
    fun explicitCountryPrefixWinsOverRegion() {
        assertEquals("+9613039056", Countries.e164OrNull("+961 3 039 056", "US"))
    }

    @Test
    fun invalidOrBlankNumbersAreNull() {
        assertNull(Countries.e164OrNull("", "US"))
        assertNull(Countries.e164OrNull("123", "US"))
        assertNull(Countries.e164OrNull("not a number", "US"))
    }

    @Test
    fun toInternationalEchoesUnparseableInput() {
        assertEquals("123", Countries.toInternational("123", "US"))
        assertEquals("+12125551234", Countries.toInternational("2125551234", "US"))
    }
}
