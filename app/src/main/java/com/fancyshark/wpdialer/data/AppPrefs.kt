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

    private val _light = MutableStateFlow(false)
    val light: StateFlow<Boolean> = _light

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

    fun init(context: Context) {
        val p = prefs(context)
        _rejectMessages.value = decodeList(p.getString("reject_msgs", null))
            ?: DEFAULT_REJECT_MESSAGES
        _globalSim.value = p.getString("sim_global", null)
        _light.value = p.getBoolean("theme_light", false)
        _tilt.value = p.getBoolean("tilt", true)
        _relativeTimes.value = p.getBoolean("relative_times", false)
        _oneHandedLists.value = p.getBoolean("one_handed_lists", false)
        _reachGesture.value = p.getBoolean("reach_gesture", false)
        _setupDone.value = p.getBoolean("setup_done", false)
        wizardSeen = p.getBoolean("wizard_seen", false)
        _speedDialNumbers.value = decodeList(p.getString("speed_dial", null)) ?: emptyList()
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

    fun setLight(context: Context, on: Boolean) {
        _light.value = on
        prefs(context).edit().putBoolean("theme_light", on).apply()
    }

    fun setTilt(context: Context, on: Boolean) {
        _tilt.value = on
        prefs(context).edit().putBoolean("tilt", on).apply()
    }
}
