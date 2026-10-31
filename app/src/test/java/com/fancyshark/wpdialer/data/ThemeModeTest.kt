package com.fancyshark.wpdialer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {

    @Test
    fun legacyBooleanIsTheFallback() {
        assertEquals(AppPrefs.THEME_LIGHT, AppPrefs.themeModeFrom(null, true))
        assertEquals(AppPrefs.THEME_DARK, AppPrefs.themeModeFrom(null, false))
        assertEquals(AppPrefs.THEME_DARK, AppPrefs.themeModeFrom(null, null))
    }

    @Test
    fun storedModeWinsOverTheLegacyBoolean() {
        assertEquals(AppPrefs.THEME_SYSTEM, AppPrefs.themeModeFrom("system", true))
        assertEquals(AppPrefs.THEME_DARK, AppPrefs.themeModeFrom("dark", true))
        // Garbage (hand-edited backup) falls back to the boolean.
        assertEquals(AppPrefs.THEME_LIGHT, AppPrefs.themeModeFrom("sepia", true))
    }

    @Test
    fun resolution() {
        assertTrue(AppPrefs.resolveLight(AppPrefs.THEME_LIGHT, systemDark = true))
        assertFalse(AppPrefs.resolveLight(AppPrefs.THEME_DARK, systemDark = false))
        assertTrue(AppPrefs.resolveLight(AppPrefs.THEME_SYSTEM, systemDark = false))
        assertFalse(AppPrefs.resolveLight(AppPrefs.THEME_SYSTEM, systemDark = true))
    }

    @Test
    fun backupKnowsTheNewKeys() {
        assertTrue(SettingsBackup.accepts("theme_mode", "s", null))
        assertFalse(SettingsBackup.accepts("theme_mode", "b", null))
        assertTrue(SettingsBackup.accepts("screen_unknown", "b", null))
        assertFalse(SettingsBackup.accepts("screen_hidden", "s", "b"))
    }

    @Test
    fun oldBackupsRestoreTheirThemeViaTheBoolean() {
        assertTrue(SettingsBackup.legacyThemeOnly(setOf("theme_light", "accent")))
        assertFalse(SettingsBackup.legacyThemeOnly(setOf("theme_light", "theme_mode")))
        assertFalse(SettingsBackup.legacyThemeOnly(setOf("accent")))
    }
}
