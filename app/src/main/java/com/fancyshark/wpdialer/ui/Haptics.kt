package com.fancyshark.wpdialer.ui

import android.content.Context
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Keypad haptic feedback. User-toggleable with three intensity levels, and
 * automatically silenced while the system is in battery saver mode.
 */
object Haptics {

    val LEVELS = listOf("low", "medium", "high")

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    private val _level = MutableStateFlow(1)
    val level: StateFlow<Int> = _level

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("wp", Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean("haptics", true)
        _level.value = prefs.getInt("haptics_level", 1).coerceIn(0, LEVELS.lastIndex)
    }

    fun set(context: Context, on: Boolean) {
        _enabled.value = on
        context.getSharedPreferences("wp", Context.MODE_PRIVATE)
            .edit().putBoolean("haptics", on).apply()
    }

    fun setLevel(context: Context, level: Int) {
        _level.value = level.coerceIn(0, LEVELS.lastIndex)
        context.getSharedPreferences("wp", Context.MODE_PRIVATE)
            .edit().putInt("haptics_level", _level.value).apply()
    }

    fun tick(context: Context) {
        if (!_enabled.value) return
        val power = context.getSystemService(PowerManager::class.java)
        if (power?.isPowerSaveMode == true) return
        runCatching {
            val vibrator = context.getSystemService(VibratorManager::class.java)
                ?.defaultVibrator ?: return
            val effect = if (vibrator.hasAmplitudeControl()) {
                val (duration, amplitude) = when (_level.value) {
                    0 -> 10L to 50
                    2 -> 22L to 255
                    else -> 15L to 130
                }
                VibrationEffect.createOneShot(duration, amplitude)
            } else {
                VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            }
            vibrator.vibrate(effect)
        }
    }
}
