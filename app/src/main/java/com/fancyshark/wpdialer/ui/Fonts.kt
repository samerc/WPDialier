package com.fancyshark.wpdialer.ui

import android.content.Context
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.fancyshark.wpdialer.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Selawik — Microsoft's open-source (SIL OFL) Segoe-compatible typeface, the
 * closest legally distributable match to Segoe WP.
 */
val Selawik = FontFamily(
    Font(R.font.selawkl, FontWeight.Thin),
    Font(R.font.selawkl, FontWeight.ExtraLight),
    Font(R.font.selawkl, FontWeight.Light),
    Font(R.font.selawksl, FontWeight(350)),
    Font(R.font.selawk, FontWeight.Normal),
    Font(R.font.selawksb, FontWeight.Medium),
    Font(R.font.selawksb, FontWeight.SemiBold),
    Font(R.font.selawkb, FontWeight.Bold),
)

object FontStore {

    private val _selawik = MutableStateFlow(true)
    val selawik: StateFlow<Boolean> = _selawik

    fun init(context: Context) {
        _selawik.value = context.getSharedPreferences("wp", Context.MODE_PRIVATE)
            .getBoolean("font_selawik", true)
    }

    fun set(context: Context, on: Boolean) {
        _selawik.value = on
        context.getSharedPreferences("wp", Context.MODE_PRIVATE)
            .edit().putBoolean("font_selawik", on).apply()
    }
}
