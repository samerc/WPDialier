package com.fancyshark.wpdialer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** App-level user preferences beyond accent/font/haptics. */
object AppPrefs {

    private const val SEPARATOR = "|;|"

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

    private fun prefs(context: Context) =
        context.getSharedPreferences("wp", Context.MODE_PRIVATE)

    fun init(context: Context) {
        val p = prefs(context)
        _rejectMessages.value = p.getString("reject_msgs", null)
            ?.split(SEPARATOR)?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_REJECT_MESSAGES
        _globalSim.value = p.getString("sim_global", null)
        _light.value = p.getBoolean("theme_light", false)
        _tilt.value = p.getBoolean("tilt", true)
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
