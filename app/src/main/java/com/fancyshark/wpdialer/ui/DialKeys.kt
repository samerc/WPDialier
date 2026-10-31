package com.fancyshark.wpdialer.ui

import android.view.KeyEvent

/**
 * The dial character a hardware key produces: flip-phone keypads send
 * STAR/POUND, but QWERTY and Bluetooth keyboards send Shift+8 / Shift+3 —
 * reading those as plain digits would turn "*#06#" into "83063". Other
 * shifted digits are symbols (!, @…), not dial characters.
 */
object DialKeys {

    fun charFor(keyCode: Int, shift: Boolean): Char? = when (keyCode) {
        KeyEvent.KEYCODE_8 -> if (shift) '*' else '8'
        KeyEvent.KEYCODE_3 -> if (shift) '#' else '3'
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
            if (shift) null else '0' + (keyCode - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
            '0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
        KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> '*'
        KeyEvent.KEYCODE_POUND -> '#'
        else -> null
    }

    fun charFor(keyCode: Int, event: KeyEvent?): Char? =
        charFor(keyCode, event?.isShiftPressed == true)
}
