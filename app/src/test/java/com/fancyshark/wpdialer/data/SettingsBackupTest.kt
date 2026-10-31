package com.fancyshark.wpdialer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A restored value of the wrong type used to crash every launch. */
class SettingsBackupTest {

    @Test
    fun knownKeysMustCarryTheirReadType() {
        assertTrue(SettingsBackup.accepts("theme_light", "b", null))
        assertFalse(SettingsBackup.accepts("theme_light", "s", null))
        assertFalse(SettingsBackup.accepts("haptics_level", "l", "i"))
        assertTrue(SettingsBackup.accepts("accent", "s", null))
    }

    @Test
    fun liveTileTogglesAreBooleans() {
        assertTrue(SettingsBackup.accepts(AppPrefs.KEY_TILES_ANIMATE, "b", null))
        assertTrue(SettingsBackup.accepts(AppPrefs.KEY_TILES_TRANSPARENT, "b", null))
        assertFalse(SettingsBackup.accepts(AppPrefs.KEY_TILES_ANIMATE, "s", null))
        assertFalse(SettingsBackup.accepts(AppPrefs.KEY_TILES_TRANSPARENT, "i", "b"))
    }

    @Test
    fun knownTypeWinsOverACorruptStoredType() {
        assertTrue(SettingsBackup.accepts("tilt", "b", "s"))
    }

    @Test
    fun bookkeepingAndDeviceBoundKeysAreNeverRestored() {
        assertFalse(SettingsBackup.accepts("setup_done", "b", null))
        assertFalse(SettingsBackup.accepts("wizard_seen", "b", null))
        assertFalse(SettingsBackup.accepts("whats_new_seen", "i", null))
        assertFalse(SettingsBackup.accepts("sim_global", "s", null))
        assertFalse(SettingsBackup.accepts("simn_+9613039056", "s", null))
    }

    @Test
    fun unknownKeysFollowTheStoredType() {
        assertTrue(SettingsBackup.accepts("future_key", "i", null))
        assertTrue(SettingsBackup.accepts("future_key", "i", "i"))
        assertFalse(SettingsBackup.accepts("future_key", "s", "i"))
    }

    @Test
    fun malformedEntriesAreRejected() {
        assertFalse(SettingsBackup.accepts("", "b", null))
        assertFalse(SettingsBackup.accepts("tilt", "x", null))
    }

    @Test
    fun tagsMatchSharedPreferencesTypes() {
        assertEquals("b", SettingsBackup.tagOf(true))
        assertEquals("i", SettingsBackup.tagOf(1))
        assertEquals("l", SettingsBackup.tagOf(1L))
        assertEquals("f", SettingsBackup.tagOf(1f))
        assertEquals("s", SettingsBackup.tagOf("x"))
        assertNull(SettingsBackup.tagOf(setOf("a")))
    }
}
