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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Phone
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
import com.fancyshark.wpdialer.ui.ContactTile
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
    // Owner token, not a flag: when one dialpad replaces another, the
    // outgoing one's dispose can run after the new one claimed the bus —
    // it must only release the bus if it still owns it.
    @Volatile
    private var owner: Any? = null
    val open: Boolean get() = owner != null

    @Synchronized
    fun claim(token: Any) {
        owner = token
    }

    @Synchronized
    fun release(token: Any) {
        if (owner === token) owner = null
    }

    val events = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)
}

/**
 * [onCall] gets the number plus an `onPlaced` callback the host runs once the
 * call really went out — the typed number is only consumed then, so a
 * cancelled SIM chooser or a denied permission keeps it on the pad.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialpadScreen(
    initial: String,
    accent: Color,
    onCall: (number: String, onPlaced: () -> Unit) -> Unit,
    onSave: (String) -> Unit,
    onVoicemail: (() -> Unit)? = null,
    onSpecialCode: ((SpecialCode) -> Unit)? = null,
) {
    var number by rememberSaveable { mutableStateOf(initial) }
    val speedDial by com.fancyshark.wpdialer.data.AppPrefs.speedDialNumbers.collectAsState()
    val resources = androidx.compose.ui.platform.LocalResources.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val tones = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_DTMF, 70) }.getOrNull()
    }
    DisposableEffect(Unit) {
        val busToken = Any()
        DialpadBus.claim(busToken)
        onDispose {
            DialpadBus.release(busToken)
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

    // Secret / MMI codes act as they're typed (AOSP SpecialCharSequenceMgr):
    // no call press, and the pad clears so the code can't be re-sent.
    androidx.compose.runtime.LaunchedEffect(number) {
        val code = SpecialCodes.detect(number) ?: return@LaunchedEffect
        if (onSpecialCode != null) {
            number = ""
            onSpecialCode(code)
        }
    }

    // Hold 2-9 on an empty pad: speed-dial entry N-1 (key 2 = first entry).
    fun speedDialKey(key: Int) {
        val target = speedDial.getOrNull(key - 2)
        Haptics.tick(context)
        if (target != null) {
            onCall(target) {}
        } else {
            com.fancyshark.wpdialer.ui.MetroToast.show(
                resources.getString(com.fancyshark.wpdialer.R.string.dialpad_speed_dial_empty, key),
            )
        }
    }

    // Hold * = pause (','), hold # = wait (';') — AOSP convention. Where one
    // isn't allowed (first character, wait after wait) the key types itself.
    fun postDialKey(key: String, ch: Char) {
        if (SpecialCodes.canAppendPostDial(number, ch)) {
            Haptics.tick(context)
            number += ch
        } else {
            beep(key)
            number += key
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        DialpadBus.events.collect { event ->
            when (event) {
                "del" -> number = number.dropLast(1)
                "call" -> if (number.isNotBlank()) {
                    // Consume the number once dialed — coming back to the
                    // dialpad after the call should start fresh.
                    val n = number
                    onCall(n) { if (number == n) number = "" }
                }
                else -> {
                    beep(event)
                    Haptics.tick(context)
                    number += event
                }
            }
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
    // Short windows (split screen, landscape tablets) shrink the keys so the
    // call tile always stays on screen: fixed chrome is ~136dp + 5 key rows.
    val compact = maxHeight < 560.dp
    val chrome = if (compact) 100.dp else 136.dp
    val keyHeight = ((maxHeight - chrome - 24.dp) / 5).coerceIn(36.dp, 64.dp)
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
            Modifier
                .padding(top = if (compact) 8.dp else 26.dp, bottom = if (compact) 4.dp else 14.dp)
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Pause/wait tones stay verbatim after the formatted number —
            // the formatter would mangle (or drop) them.
            val (dialable, postDial) = SpecialCodes.splitPostDial(number)
            Text(
                if (dialable.length > 3) {
                    com.fancyshark.wpdialer.data.Repo.pretty(context, dialable) + postDial
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
        // Contact id -> thumbnail URI for the match tiles: one query up front
        // instead of a lookup per row (ContactTile decodes + caches them).
        val photos by androidx.compose.runtime.produceState(emptyMap<Long, String>()) {
            value = com.fancyshark.wpdialer.data.Repo.loadContacts(context)
                .mapNotNull { c -> c.photoUri?.let { c.id to it } }
                .toMap()
        }
        val input = number.filter { it.isDigit() }
        val suggestions = remember(number, entries) {
            if (input.length < 2 || number.any { it == '*' || it == '#' } ||
                SpecialCodes.hasPostDial(number)
            ) {
                emptyList()
            } else {
                entries.mapNotNull { p -> dialRank(p, input)?.let { it to p.entry } }
                    .sortedBy { it.first }
                    .map { it.second }
                    .distinctBy {
                        it.contactId to
                            com.fancyshark.wpdialer.data.Repo.numberKey(it.number)
                                .ifEmpty { it.number }
                    }
                    .take(50)
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            items(suggestions.size) { i ->
                val entry = suggestions[i]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val typed = number
                            onCall(entry.number) { if (number == typed) number = "" }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContactTile(
                        entry.name, photos[entry.contactId], accent, 56.dp,
                        Modifier.clearAndSetSemantics {},
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.name,
                            color = Metro.Foreground,
                            style = Metro.Type.Large,
                            fontWeight = FontWeight.Light,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            com.fancyshark.wpdialer.data.Repo.pretty(context, entry.number),
                            color = Metro.textAccent(accent),
                            style = Metro.Type.Small,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        if (suggestions.isNotEmpty()) Spacer(Modifier.height(8.dp))

        // The SIM the call tile will use, when that's already decided —
        // mirrors MainActivity.placeCall: contact preference, then the global
        // one. "Ask every time", single-SIM and emergency numbers show none.
        val sims by androidx.compose.runtime.produceState(
            emptyList<com.fancyshark.wpdialer.data.SimOption>(),
        ) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.fancyshark.wpdialer.data.Sims.options(context)
            }
        }
        val globalSim by com.fancyshark.wpdialer.data.AppPrefs.globalSim.collectAsState()
        val simLabel by androidx.compose.runtime.produceState<String?>(
            null, number, sims, globalSim,
        ) {
            if (sims.size < 2) {
                value = null
                return@produceState
            }
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val dialable = SpecialCodes.splitPostDial(number).first
                val emergency = dialable.isNotBlank() && runCatching {
                    context.getSystemService(android.telephony.TelephonyManager::class.java)
                        ?.isEmergencyNumber(dialable) == true
                }.getOrDefault(false)
                if (emergency) return@withContext null
                val contactPref = dialable.takeIf { it.isNotBlank() }
                    ?.let { com.fancyshark.wpdialer.data.SimPrefs.get(context, it) }
                    ?.let { flat -> sims.firstOrNull { it.flat == flat } }
                val global = globalSim?.let { flat -> sims.firstOrNull { it.flat == flat } }
                (contactPref ?: global)?.label
            }
        }

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
                            height = keyHeight,
                            modifier = Modifier.weight(1f),
                            onTap = {
                                beep(digit)
                                Haptics.tick(context)
                                number += digit
                            },
                            onLongPress = when (digit) {
                                "0" -> {
                                    { number += "+" }
                                }
                                // Standard dialer gesture: hold 1 = voicemail —
                                // but only on an empty pad; mid-number a rested
                                // thumb must not hijack dialing (types the 1).
                                "1" -> if (onVoicemail != null) {
                                    {
                                        if (number.isEmpty()) {
                                            Haptics.tick(context)
                                            onVoicemail()
                                        } else {
                                            number += "1"
                                        }
                                    }
                                } else {
                                    null
                                }
                                "*" -> {
                                    { postDialKey("*", SpecialCodes.PAUSE) }
                                }
                                "#" -> {
                                    { postDialKey("#", SpecialCodes.WAIT) }
                                }
                                // Same empty-pad rule as hold-1: mid-number a
                                // long press just types the digit.
                                in "2".."9" -> {
                                    {
                                        if (number.isEmpty()) {
                                            speedDialKey(digit.toInt())
                                        } else {
                                            number += digit
                                        }
                                    }
                                }
                                else -> null
                            },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            CallTile(accent, height = keyHeight, sim = simLabel, modifier = Modifier.weight(2f)) {
                if (number.isNotBlank()) {
                    val n = number
                    // Cleared only if the pad still shows what was dialed.
                    onCall(n) { if (number == n) number = "" }
                }
            }
            SaveTile(accent, height = keyHeight, modifier = Modifier.weight(1f)) {
                if (number.isNotBlank()) onSave(number)
            }
        }
        }
        Spacer(Modifier.height(12.dp))
    }
    }
}

/** The big glyph + small label layout only fits from this key height on. */
private val LABELED_TILE = 56.dp

/**
 * WP 8.1 keypad call button: accent-filled tile spanning two key columns —
 * a large phone glyph, "call" at the bottom start and, when the SIM is
 * already decided, its name at the bottom end.
 */
@Composable
private fun CallTile(
    accent: Color,
    height: androidx.compose.ui.unit.Dp,
    sim: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val tiltOn by com.fancyshark.wpdialer.data.AppPrefs.tilt.collectAsState()
    val ink = if (pressed) Metro.Background else Metro.onAccent(accent)
    val label = stringResource(com.fancyshark.wpdialer.R.string.dialpad_call)
    val spoken = if (sim != null) {
        stringResource(com.fancyshark.wpdialer.R.string.refined_call_via_sim, sim)
    } else {
        label
    }
    Box(
        modifier
            .metroTilt(tiltOn)
            .heightIn(min = height)
            .background(if (pressed) Metro.Foreground else accent)
            .then(if (focused) Modifier.border(3.dp, Metro.Foreground) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { onClick() }
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
            },
    ) {
        TileGlyph(Icons.Filled.Phone, ink, height)
        if (height >= LABELED_TILE) {
            TileLabel(label, ink, Modifier.align(Alignment.BottomStart))
            if (sim != null) {
                Text(
                    sim,
                    color = ink.copy(alpha = 0.85f),
                    style = Metro.Type.Tiny,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .fillMaxWidth(0.45f)
                        .padding(end = 10.dp, bottom = 6.dp),
                )
            }
        }
    }
}

/** WP 8.1 keypad save button: gray key tile with a large glyph and small label. */
@Composable
private fun SaveTile(
    accent: Color,
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val tiltOn by com.fancyshark.wpdialer.data.AppPrefs.tilt.collectAsState()
    val label = stringResource(com.fancyshark.wpdialer.R.string.dialpad_save)
    Box(
        modifier
            .metroTilt(tiltOn)
            .heightIn(min = height)
            .background(if (pressed) accent else Metro.Key)
            .then(if (focused) Modifier.border(3.dp, accent) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
            ) { onClick() }
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
            },
    ) {
        val ink = if (pressed) Metro.onAccent(accent) else Metro.Foreground
        TileGlyph(Icons.Filled.Save, ink, height)
        // Hidden on short keys — the glyph alone is centered instead.
        if (height >= LABELED_TILE) {
            TileLabel(label, ink, Modifier.align(Alignment.BottomStart))
        }
    }
}

/**
 * The call/save glyph, sized like the 30sp digits. Tall tiles keep it near
 * the top so the bottom row holds the labels; short tiles center a smaller one.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.TileGlyph(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    height: androidx.compose.ui.unit.Dp,
) {
    Icon(
        icon,
        contentDescription = null,
        tint = tint,
        modifier = if (height >= LABELED_TILE) {
            Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(34.dp)
        } else {
            Modifier.align(Alignment.Center).size((height * 0.55f).coerceIn(20.dp, 34.dp))
        },
    )
}

@Composable
private fun TileLabel(text: String, color: Color, modifier: Modifier) {
    Text(
        text,
        color = color,
        style = Metro.Type.Small,
        fontSize = 14.sp,
        maxLines = 1,
        modifier = modifier.padding(start = 10.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DialKey(
    digit: String,
    letters: String,
    accent: Color,
    height: androidx.compose.ui.unit.Dp,
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
            // min, not fixed: at 200% font the digit + letters need more.
            .heightIn(min = height)
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
            // Min widths: large font scales widen the slots instead of
            // wrapping "WXYZ" or clipping the digit.
            Box(Modifier.widthIn(min = 34.dp), contentAlignment = Alignment.CenterEnd) {
                Text(
                    digit,
                    color = Metro.Foreground,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Box(
                Modifier.widthIn(min = 52.dp).padding(start = 5.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (letters.isNotEmpty()) {
                    Text(
                        letters,
                        color = if (pressed) Metro.Foreground else Metro.Subtle,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}
