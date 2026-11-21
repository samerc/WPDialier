package com.fancyshark.wpdialer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsAppLinkTest {
    @Test
    fun linkUsesBareInternationalDigits() {
        assertEquals("https://wa.me/9613039056", WhatsApp.link("+9613039056"))
    }

    @Test
    fun nationalNumberResolvesThroughRegion() {
        // Lebanese national form -> same chat as the international form.
        val e164 = Countries.e164OrNull("03 039 056", "LB")
        assertEquals("https://wa.me/9613039056", WhatsApp.link(e164!!))
    }

    @Test
    fun shortCodesGetNoLink() {
        assertNull(Countries.e164OrNull("112", "LB"))
        assertNull(Countries.e164OrNull("*100#", "LB"))
    }
}
