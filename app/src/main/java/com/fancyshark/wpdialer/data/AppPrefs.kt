package com.fancyshark.wpdialer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-level user preferences beyond accent/font/haptics. */
object AppPrefs {

    // A control char can't be typed or pasted from normal text, so entries
    // can never synthesize a separator at a join boundary (the old "|;|"
    // could: "a|;" + "|b" round-tripped as corrupt entries).
    private const val SEPARATOR = "\u0001"
    private const val LEGACY_SEPARATOR = "|;|"

    private fun decodeList(flat: String?): List<String>? = flat
        ?.let { s ->
            if (s.contains(SEPARATOR)) s.split(SEPARATOR) else s.split(LEGACY_SEPARATOR)
        }
        ?.filter { it.isNotBlank() }
        ?.takeIf { it.isNotEmpty() }

    val DEFAULT_REJECT_MESSAGES = listOf(
        "I'll call you back.",
        "Can't talk right now — text me.",
        "I'm on my way.",
    )

    private val _rejectMessages = MutableStateFlow(DEFAULT_REJECT_MESSAGES)
    val rejectMessages: StateFlow<List<String>> = _rejectMessages

    private val _globalSim = MutableStateFlow<String?>(null)
    val globalSim: StateFlow<String?> = _globalSim

    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"
    const val THEME_SYSTEM = "system"
    private val THEME_MODES = setOf(THEME_DARK, THEME_LIGHT, THEME_SYSTEM)

    /** dark / light / follow the system night mode. */
    private val _themeMode = MutableStateFlow(THEME_DARK)
    val themeMode: StateFlow<String> = _themeMode

    // Call screening (all default off): see call/ScreeningPolicy.
    private val _screenHidden = MutableStateFlow(false)
    val screenHidden: StateFlow<Boolean> = _screenHidden
    private val _screenUnknown = MutableStateFlow(false)
    val screenUnknown: StateFlow<Boolean> = _screenUnknown
    private val _screenSpoofed = MutableStateFlow(false)
    val screenSpoofed: StateFlow<Boolean> = _screenSpoofed

    private val _tilt = MutableStateFlow(true)
    val tilt: StateFlow<Boolean> = _tilt

    /** true = "2 hours ago" style; false = WP "Sat 01:06" style. */
    private val _relativeTimes = MutableStateFlow(false)
    val relativeTimes: StateFlow<Boolean> = _relativeTimes

    /** One-handed: history and search results anchor to the bottom, within thumb reach. */
    private val _oneHandedLists = MutableStateFlow(false)
    val oneHandedLists: StateFlow<Boolean> = _oneHandedLists

    /** One-handed: swipe down on the app bar slides the screen down (WP10M-style). */
    private val _reachGesture = MutableStateFlow(false)
    val reachGesture: StateFlow<Boolean> = _reachGesture

    /** First-run wizard completed (or grandfathered by an existing install). */
    private val _setupDone = MutableStateFlow(false)
    val setupDone: StateFlow<Boolean> = _setupDone

    /** The wizard has been shown at least once — distinguishes a pre-wizard
     *  install (grandfather it) from a user killed mid-wizard (resume it). */
    @Volatile
    var wizardSeen = false
        private set

    private val _speedDialNumbers = MutableStateFlow<List<String>>(emptyList())
    val speedDialNumbers: StateFlow<List<String>> = _speedDialNumbers

    private fun prefs(context: Context) =
        context.getSharedPreferences("wp", Context.MODE_PRIVATE)

    // A wrong-typed stored value (hand-edited backup, older build) makes the
    // typed getters throw ClassCastException — fall back to the default
    // instead of crashing on every launch.
    private fun android.content.SharedPreferences.bool(key: String, def: Boolean): Boolean =
        runCatching { getBoolean(key, def) }.getOrDefault(def)

    private fun android.content.SharedPreferences.str(key: String): String? =
        runCatching { getString(key, null) }.getOrNull()

    fun init(context: Context) {
        val p = prefs(context)
        _rejectMessages.value = decodeList(p.str("reject_msgs"))
            ?: DEFAULT_REJECT_MESSAGES
        _globalSim.value = p.str("sim_global")
        _themeMode.value = themeModeFrom(
            p.str("theme_mode"),
            if (p.contains("theme_light")) p.bool("theme_light", false) else null,
        )
        _screenHidden.value = p.bool(KEY_SCREEN_HIDDEN, false)
        _screenUnknown.value = p.bool(KEY_SCREEN_UNKNOWN, false)
        _screenSpoofed.value = p.bool(KEY_SCREEN_SPOOFED, false)
        _tilt.value = p.bool("tilt", true)
        _relativeTimes.value = p.bool("relative_times", false)
        _oneHandedLists.value = p.bool("one_handed_lists", false)
        _reachGesture.value = p.bool("reach_gesture", false)
        _setupDone.value = p.bool("setup_done", false)
        wizardSeen = p.bool("wizard_seen", false)
        _speedDialNumbers.value = decodeList(p.str("speed_dial")) ?: emptyList()
    }

    fun setRelativeTimes(context: Context, on: Boolean) {
        _relativeTimes.value = on
        prefs(context).edit().putBoolean("relative_times", on).apply()
    }

    fun setOneHandedLists(context: Context, on: Boolean) {
        _oneHandedLists.value = on
        prefs(context).edit().putBoolean("one_handed_lists", on).apply()
    }

    fun setReachGesture(context: Context, on: Boolean) {
        _reachGesture.value = on
        prefs(context).edit().putBoolean("reach_gesture", on).apply()
    }

    fun setSetupDone(context: Context, done: Boolean) {
        _setupDone.value = done
        prefs(context).edit().putBoolean("setup_done", done).apply()
    }

    fun markWizardSeen(context: Context) {
        if (wizardSeen) return
        wizardSeen = true
        prefs(context).edit().putBoolean("wizard_seen", true).apply()
    }

    fun addSpeedDial(context: Context, number: String) {
        if (number.isBlank() || number in _speedDialNumbers.value) return
        setSpeedDial(context, _speedDialNumbers.value + number)
    }

    fun removeSpeedDial(context: Context, number: String) {
        setSpeedDial(context, _speedDialNumbers.value - number)
    }

    private fun setSpeedDial(context: Context, numbers: List<String>) {
        // Same separator sanitation as reject messages — a pasted entry
        // containing the separator would round-trip as corrupt entries.
        val cleaned = numbers.map { it.replace(SEPARATOR, " ") }.filter { it.isNotBlank() }
        _speedDialNumbers.value = cleaned
        prefs(context).edit().putString("speed_dial", cleaned.joinToString(SEPARATOR)).apply()
    }

    fun setRejectMessages(context: Context, messages: List<String>) {
        val cleaned = messages.map { it.replace(SEPARATOR, " ") }.filter { it.isNotBlank() }
        _rejectMessages.value = cleaned
        prefs(context).edit()
            .putString("reject_msgs", cleaned.joinToString(SEPARATOR)).apply()
    }

    fun setGlobalSim(context: Context, flat: String?) {
        _globalSim.value = flat
        prefs(context).edit().apply {
            if (flat == null) remove("sim_global") else putString("sim_global", flat)
        }.apply()
    }

    /**
     * The stored mode; pre-1.2 installs only have the theme_light boolean,
     * which stays the fallback so their choice carries over untouched.
     */
    internal fun themeModeFrom(stored: String?, legacyLight: Boolean?): String = when {
        stored in THEME_MODES -> stored!!
        legacyLight == true -> THEME_LIGHT
        else -> THEME_DARK
    }

    /** Whether the Metro light palette applies for [mode]. */
    fun resolveLight(mode: String, systemDark: Boolean): Boolean = when (mode) {
        THEME_LIGHT -> true
        THEME_SYSTEM -> !systemDark
        else -> false
    }

    /** [resolveLight] against [context]'s current configuration (activity onCreate). */
    fun isLight(context: Context): Boolean {
        val night = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return resolveLight(
            themeMode.value, night == android.content.res.Configuration.UI_MODE_NIGHT_YES,
        )
    }

    fun setThemeMode(context: Context, mode: String) {
        if (mode !in THEME_MODES) return
        _themeMode.value = mode
        // theme_light is still written so a downgrade keeps a sane choice.
        prefs(context).edit()
            .putString("theme_mode", mode)
            .putBoolean("theme_light", mode == THEME_LIGHT)
            .apply()
    }

    const val KEY_SCREEN_HIDDEN = "screen_hidden"
    const val KEY_SCREEN_UNKNOWN = "screen_unknown"
    const val KEY_SCREEN_SPOOFED = "screen_spoofed"

    fun setScreenHidden(context: Context, on: Boolean) {
        _screenHidden.value = on
        prefs(context).edit().putBoolean(KEY_SCREEN_HIDDEN, on).apply()
    }

    fun setScreenUnknown(context: Context, on: Boolean) {
        _screenUnknown.value = on
        prefs(context).edit().putBoolean(KEY_SCREEN_UNKNOWN, on).apply()
    }

    fun setScreenSpoofed(context: Context, on: Boolean) {
        _screenSpoofed.value = on
        prefs(context).edit().putBoolean(KEY_SCREEN_SPOOFED, on).apply()
    }

    /** Fresh read for the screening service (may run in a cold process). */
    fun screeningSettings(context: Context): com.fancyshark.wpdialer.call.ScreeningSettings {
        val p = prefs(context)
        return com.fancyshark.wpdialer.call.ScreeningSettings(
            blockHidden = p.bool(KEY_SCREEN_HIDDEN, false),
            silenceUnknown = p.bool(KEY_SCREEN_UNKNOWN, false),
            blockSpoofed = p.bool(KEY_SCREEN_SPOOFED, false),
        )
    }

    fun setTilt(context: Context, on: Boolean) {
        _tilt.value = on
        prefs(context).edit().putBoolean("tilt", on).apply()
    }
}
