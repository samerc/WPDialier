package com.fancyshark.wpdialer.screens

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fancyshark.wpdialer.R
import com.fancyshark.wpdialer.data.CallNotes
import com.fancyshark.wpdialer.data.ContactCall
import com.fancyshark.wpdialer.data.Repo
import com.fancyshark.wpdialer.data.SimOption
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroEmptyState
import com.fancyshark.wpdialer.ui.MetroProgressDots
import com.fancyshark.wpdialer.ui.AccentStore
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch

/*
 * The contact pivot's "history" and "notes" pages. Both read the same
 * [Repo.callsWith] result (null while loading), hoisted in
 * ContactDetailScreen so the two pages share one query.
 */

/** Type glyph for a call-log TYPE (missed tinted by the caller). */
private fun callTypeIcon(type: Int): ImageVector = when (type) {
    android.provider.CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Filled.CallMade
    android.provider.CallLog.Calls.MISSED_TYPE -> Icons.AutoMirrored.Filled.CallMissed
    android.provider.CallLog.Calls.VOICEMAIL_TYPE -> Icons.Filled.Voicemail
    android.provider.CallLog.Calls.REJECTED_TYPE,
    android.provider.CallLog.Calls.BLOCKED_TYPE,
    -> Icons.Filled.Block
    else -> Icons.AutoMirrored.Filled.CallReceived
}

/** "mobile  +961 3 039 056" — the contact's label for the line, then the number. */
@Composable
private fun numberLine(call: ContactCall, labelFor: (String) -> String?): String {
    val context = LocalContext.current
    val pretty = Repo.pretty(context, call.item.number)
    val label = labelFor(call.contactNumber)?.takeIf { it.isNotBlank() } ?: return pretty
    return stringResource(R.string.contact_pivot_number_line, label, pretty)
}

/**
 * Every call with this contact, newest first. Tap calls that number back;
 * long-press opens the WP white context menu (copy number / delete — the
 * delete confirm is the caller's).
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun ContactHistoryPage(
    calls: List<ContactCall>?,
    accent: Color,
    simOptions: List<SimOption>,
    labelFor: (String) -> String?,
    onCall: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDelete: (ContactCall) -> Unit,
) {
    if (calls == null) {
        MetroProgressDots(Modifier.padding(top = 16.dp))
        return
    }
    if (calls.isEmpty()) {
        MetroEmptyState(stringResource(R.string.contact_pivot_history_empty))
        return
    }
    val context = LocalContext.current
    val accentText = Metro.textAccent(accent)
    // The SIM column only means something on a dual-SIM phone — the same
    // rule the profile page's preferred-SIM row follows.
    val showSim = simOptions.size > 1
    var menuForId by remember { mutableStateOf<Long?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 20.dp),
    ) {
        items(calls.size, key = { calls[it].item.id }) { i ->
            val call = calls[i]
            val item = call.item
            val missed = item.type == android.provider.CallLog.Calls.MISSED_TYPE
            val typeLabel = Repo.historyTypeLabel(context, item.type)
            Column(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onCall(item.number) },
                        onLongClick = { menuForId = item.id },
                    )
                    .padding(vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        callTypeIcon(item.type),
                        contentDescription = typeLabel,
                        tint = if (missed) accentText else Metro.Subtle,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                R.string.home_history_subtitle,
                                Repo.historyTypeShort(context, item.type),
                                Repo.historyWhen(item.date),
                            ),
                            color = if (missed) accentText else Metro.Foreground,
                            style = Metro.Type.Large,
                            fontWeight = FontWeight.Light,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sim = if (showSim) {
                            simOptions.firstOrNull { it.flat == call.phoneAccount }?.label
                        } else {
                            null
                        }
                        Text(
                            numberLine(call, labelFor) +
                                (
                                    if (item.duration > 0) {
                                        stringResource(
                                            R.string.home_details_duration,
                                            Repo.formatDuration(item.duration),
                                        )
                                    } else {
                                        ""
                                    }
                                    ) +
                                (if (sim != null) "  ·  $sim" else ""),
                            color = Metro.Subtle,
                            style = Metro.Type.Small,
                            fontWeight = FontWeight.Light,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // WP-style white context menu on long-press (HomePages pattern).
                Box {
                    MaterialTheme(colorScheme = lightColorScheme()) {
                        DropdownMenu(
                            expanded = menuForId == item.id,
                            onDismissRequest = { menuForId = null },
                        ) {
                            listOf(
                                stringResource(R.string.contact_pivot_copy_number) to {
                                    onCopy(item.number)
                                },
                                stringResource(R.string.home_delete) to { onDelete(call) },
                            ).forEach { (label, action) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            label,
                                            color = Color.Black,
                                            style = Metro.Type.Body,
                                            fontWeight = FontWeight.Light,
                                        )
                                    },
                                    onClick = {
                                        menuForId = null
                                        action()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Call notes left on this contact's calls, each under its call's date. */
@Composable
fun ContactNotesPage(
    calls: List<ContactCall>?,
    accent: Color,
    labelFor: (String) -> String?,
) {
    if (calls == null) {
        MetroProgressDots(Modifier.padding(top = 16.dp))
        return
    }
    val notes by CallNotes.notes.collectAsState()
    // Notes are keyed by call-log row _ID, so a deleted call takes its note.
    val noted = remember(calls, notes) { calls.filter { notes.containsKey(it.item.id) } }
    if (noted.isEmpty()) {
        MetroEmptyState(
            stringResource(R.string.contact_pivot_notes_empty),
            hint = stringResource(R.string.contact_pivot_notes_hint),
        )
        return
    }
    val context = LocalContext.current
    val accentText = Metro.textAccent(accent)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 20.dp),
    ) {
        items(noted.size, key = { noted[it].item.id }) { i ->
            val call = noted[i]
            val item = call.item
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    stringResource(
                        R.string.home_history_subtitle,
                        Repo.historyTypeShort(context, item.type),
                        Repo.wpTime(item.date),
                    ),
                    color = accentText,
                    style = Metro.Type.Small,
                )
                Text(
                    notes[item.id].orEmpty(),
                    color = Metro.Foreground,
                    style = Metro.Type.Body,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    numberLine(call, labelFor),
                    color = Metro.Subtle,
                    style = Metro.Type.Small,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The WP pivot (same look as ui.Pivot) driven by a caller-owned
 * [PagerState], so a page can switch the pivot itself — the profile's
 * "see all on the history page". Create the state with rememberPagerState
 * (saveable) so the page survives recreation.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun StatePivot(
    title: String,
    state: PagerState,
    pages: List<Pair<String, @Composable () -> Unit>>,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    Column(modifier) {
        Text(
            title,
            color = Metro.Foreground,
            style = Metro.Type.AppTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp, end = 20.dp),
        )
        val titleWidths = remember(pages.size) { IntArray(pages.size) }
        var widthTick by remember { mutableStateOf(0) }
        Row(
            Modifier
                .padding(start = 12.dp, top = 2.dp)
                .horizontalScroll(rememberScrollState(), enabled = false)
                .offset {
                    widthTick
                    val progress = state.currentPage + state.currentPageOffsetFraction
                    val whole = progress.toInt().coerceIn(0, pages.size - 1)
                    val base = (0 until whole).sumOf { titleWidths[it] }
                    val frac = (progress - whole) * titleWidths.getOrElse(whole) { 0 }
                    // Toward-start pan; offset mirrors for RTL by itself.
                    IntOffset(-(base + frac.toInt()), 0)
                },
        ) {
            val accent by AccentStore.accent.collectAsState()
            pages.forEachIndexed { i, (name, _) ->
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                Text(
                    name,
                    maxLines = 1,
                    softWrap = false,
                    color = if (focused) Metro.textAccent(accent.color) else Metro.Foreground,
                    style = Metro.Type.PageTitle,
                    modifier = Modifier
                        .graphicsLayer {
                            val progress = state.currentPage + state.currentPageOffsetFraction
                            val distance = kotlin.math.abs(progress - i).coerceIn(0f, 1f)
                            alpha = if (focused) 1f else 1f - 0.6f * distance
                        }
                        .onSizeChanged {
                            if (titleWidths[i] != it.width) {
                                titleWidths[i] = it.width
                                widthTick++
                            }
                        }
                        .padding(end = 16.dp)
                        .clickable(interactionSource = interaction, indication = null) {
                            scope.launch { state.animateScrollToPage(i) }
                        },
                )
            }
        }
        HorizontalPager(
            state = state,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) { page ->
            pages[page].second()
        }
    }
}
