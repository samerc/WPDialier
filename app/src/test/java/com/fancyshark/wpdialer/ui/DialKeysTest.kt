package com.fancyshark.wpdialer.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DialKeysTest {

    @Test
    fun plainDigits() {
        assertEquals('0', DialKeys.charFor(KeyEvent.KEYCODE_0, false))
        assertEquals('8', DialKeys.charFor(KeyEvent.KEYCODE_8, false))
        assertEquals('9', DialKeys.charFor(KeyEvent.KEYCODE_9, false))
    }

    @Test
    fun qwertyShiftProducesStarAndPound() {
        assertEquals('*', DialKeys.charFor(KeyEvent.KEYCODE_8, true))
        assertEquals('#', DialKeys.charFor(KeyEvent.KEYCODE_3, true))
        assertNull(DialKeys.charFor(KeyEvent.KEYCODE_1, true))
    }

    @Test
    fun keypadKeys() {
        assertEquals('*', DialKeys.charFor(KeyEvent.KEYCODE_STAR, false))
        assertEquals('#', DialKeys.charFor(KeyEvent.KEYCODE_POUND, false))
        assertEquals('5', DialKeys.charFor(KeyEvent.KEYCODE_NUMPAD_5, false))
        assertEquals('*', DialKeys.charFor(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, false))
    }

    @Test
    fun otherKeysIgnored() {
        assertNull(DialKeys.charFor(KeyEvent.KEYCODE_A, false))
    }
}
