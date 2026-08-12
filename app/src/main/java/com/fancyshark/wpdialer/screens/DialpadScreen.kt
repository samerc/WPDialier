package com.fancyshark.wpdialer.screens

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.ui.Haptics
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.metroTilt

private val KEYS = listOf(
    "1" to "", "2" to "ABC", "3" to "DEF",
    "4" to "GHI", "5" to "JKL", "6" to "MNO",
    "7" to "PQRS", "8" to "TUV", "9" to "WXYZ",
    "*" to "", "0" to "+", "#" to "",
)

// T9 mapping for smart dial: letters to keypad digits.
private val T9: Map<Char, Char> = buildMap {
    "abc".forEach { put(it, '2') }
    "def".forEach { put(it, '3') }
    "ghi".forEach { put(it, '4') }
    "jkl".forEach { put(it, '5') }
    "mno".forEach { put(it, '6') }
    "pqrs".forEach { put(it, '7') }
    "tuv".forEach { put(it, '8') }
    "wxyz".forEach { put(it, '9') }
}

private fun t9(text: String): String = buildString {
    for (ch in text.lowercase()) {
        T9[ch]?.let { append(it) } ?: if (ch.isDigit()) append(ch) else Unit
    }
}

/**
 * A dial entry with its T9 digit forms computed once — recomputing them for
 * every contact on every keystroke caused visible jank at ~2k contacts.
 */
private class PreparedDialEntry(val entry: com.fancyshark.wpdialer.data.DialEntry) {
    val full = t9(entry.name)
    val words = entry.name.split(' ', '-', '.')
        .filter { it.isNotBlank() }.map { t9(it) }
    val digits = entry.number.filter { it.isDigit() }
}

/** Rank a smart-dial candidate for [input] digits; null = no match. */
private fun dialRank(p: PreparedDialEntry, input: String): Int? = when {
    p.full.startsWith(input) -> 0
    p.words.any { it.startsWith(input) } -> 1
    p.digits.contains(input) -> 2
    else -> null
}

/**
 * Routes hardware keypad presses (flip/keypad phones) into the dialpad:
 * digits/star/pound append, "del" backspaces, "call" dials.
 */
object DialpadBus {
    @Volatile
    var open = false
    val events = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialpadScreen(
    initial: String,
    accent: Color,
    onCall: (String) -> Unit,
    onSave: (String) -> Unit,
) {
    var number by rememberSaveable { mutableStateOf(initial) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val tones = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_DTMF, 70) }.getOrNull()
    }
    DisposableEffect(Unit) {
        DialpadBus.open = true
        onDispose {
            DialpadBus.open = false
            tones?.release()
        }
    }

    fun beep(key: String) {
        val tone = when (key) {
            "*" -> ToneGenerator.TONE_DTMF_S
            "#" -> ToneGenerator.TONE_DTMF_P
            else -> key.toIntOrNull() ?: return
        }
        runCatching { tones?.startTone(tone, 120) }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        DialpadBus.events.collect { event ->
            when (event) {
                "del" -> number = number.dropLast(1)
                "call" -> if (number.isNotBlank()) {
                    // Consume the number once dialed — coming back to the
                    // dialpad after the call should start fresh.
                    val n = number
                    number = ""
                    onCall(n)
                }
                else -> {
                    beep(event)
                    Haptics.tick(context)
                    number += event
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Metro.Background)
            .padding(horizontal = 10.dp),
    ) {
        // Numbers and the keypad never mirror in RTL locales.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalLayoutDirection provides
                androidx.compose.ui.unit.LayoutDirection.Ltr,
        ) {
        Row(
            Modifier.padding(top = 26.dp, bottom = 14.dp).height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (number.length > 3) {
                    com.fancyshark.wpdialer.data.Repo.pretty(context, number)
                } else number,
                color = Metro.Foreground,
                fontSize = when {
                    number.length > 14 -> 26.sp
                    number.length > 10 -> 34.sp
                    else -> 42.sp
                },
                fontWeight = FontWeight.Light,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            if (number.isNotEmpty()) {
                Box(
                    Modifier
                        .size(52.dp)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { number = number.dropLast(1) },
                            onLongClick = { number = "" },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = stringResource(com.fancyshark.wpdialer.R.string.dialpad_delete),
                        tint = Metro.Foreground,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
        }

        // T9 smart dial: match typed digits against contact names and numbers.
        // All matches, scrollable, anchored just above the keypad.
        val entries by androidx.compose.runtime.produceState(
            emptyList<PreparedDialEntry>(),
        ) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.fancyshark.wpdialer.data.Repo.loadDialEntries(context)
                    .map { PreparedDialEntry(it) }
            }
        }
        val input = number.filter { it.isDigit() }
        val suggestions = remember(number, entries) {
            if (input.length < 2 || number.any { it == '*' || it == '#' }) {
                emptyList()
            } else {
                entries.mapNotNull { p -> dialRank(p, input)?.let { it to p.entry } }
                    .sortedBy { it.first }
                    .map { it.second }
                    .distinctBy { it.contactId to it.number.filter(Char::isDigit).takeLast(9) }
                    .take(50)
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            items(suggestions.size) { i ->
                val entry = suggestions[i]
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            number = ""
                            onCall(entry.number)
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        entry.name,
                        color = Metro.Foreground,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Light,
                        maxLines = 1,
                    )
                    Text(
                        com.fancyshark.wpdialer.data.Repo.pretty(context, entry.number),
                        color = accent,
                        fontSize = 14.sp,
                    )
                }
            }
        }
        if (suggestions.isNotEmpty()) Spacer(Modifier.height(8.dp))

        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalLayoutDirection provides
                androidx.compose.ui.unit.LayoutDirection.Ltr,
        ) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            KEYS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { (digit, letters) ->
                        DialKey(
                            digit = digit,
                            letters = letters,
                            accent = accent,
                            modifier = Modifier.weight(1f),
                            onTap = {
                                beep(digit)
                                Haptics.tick(context)
                                number += digit
                            },
                            onLongPress = if (digit == "0") {
                                { number += "+" }
                            } else null,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            CallTile(accent, modifier = Modifier.weight(2f)) {
                if (number.isNotBlank()) {
                    val n = number
                    number = ""
                    onCall(n)
                }
            }
            SaveTile(accent, modifier = Modifier.weight(1f)) {
                if (number.isNotBlank()) onSave(number)
            }
        }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** WP 8.1 keypad call button: accent-filled tile spanning two key columns. */
@Composable
private fun CallTile(accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val tiltOn by com.fancyshark.wpdialer.data.AppPrefs.tilt.collectAsState()
    Box(
        modifier
            .metroTilt(tiltOn)
            .height(64.dp)
            .background(if (pressed) Metro.Foreground else accent)
            .then(if (focused) Modifier.border(3.dp, Metro.Foreground) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.dialpad_call),
            color = if (pressed) Metro.Background else Color.White,
            fontSize = 16.sp,
        )
    }
}

/** WP 8.1 keypad save button: gray key tile with icon and small label. */
@Composable
private fun SaveTile(accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val tiltOn by com.fancyshark.wpdialer.data.AppPrefs.tilt.collectAsState()
    Box(
        modifier
            .metroTilt(tiltOn)
            .height(64.dp)
            .background(if (pressed) accent else Metro.Key)
            .then(if (focused) Modifier.border(3.dp, accent) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Save,
                contentDescription = stringResource(com.fancyshark.wpdialer.R.string.dialpad_save),
                tint = Metro.Foreground,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(com.fancyshark.wpdialer.R.string.dialpad_save),
                color = Metro.Foreground,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(
    digit: String,
    letters: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val tiltOn by com.fancyshark.wpdialer.data.AppPrefs.tilt.collectAsState()
    Box(
        modifier
            .metroTilt(tiltOn)
            .height(64.dp)
            .background(if (pressed) accent else Metro.Key)
            .then(if (focused) Modifier.border(3.dp, accent) else Modifier)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onTap,
                onLongClick = onLongPress,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Fixed-width digit and letter slots so digits line up in a
            // column across all keys, letters trailing at a shared offset.
            Box(Modifier.width(34.dp), contentAlignment = Alignment.CenterEnd) {
                Text(
                    digit,
                    color = Metro.Foreground,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
                )
            }
            Box(
                Modifier.width(52.dp).padding(start = 5.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (letters.isNotEmpty()) {
                    Text(
                        letters,
                        color = if (pressed) Metro.Foreground else Metro.Subtle,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
