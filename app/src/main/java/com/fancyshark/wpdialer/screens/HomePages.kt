package com.fancyshark.wpdialer.screens

import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.data.ContactItem
import com.fancyshark.wpdialer.data.HistoryItem
import com.fancyshark.wpdialer.data.Repo
import com.fancyshark.wpdialer.ui.CallerTile
import com.fancyshark.wpdialer.ui.ContactTile
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.Continuum
import com.fancyshark.wpdialer.ui.ContinuumAnchor
import com.fancyshark.wpdialer.ui.MetroCloseMark
import com.fancyshark.wpdialer.ui.MetroEmptyState
import com.fancyshark.wpdialer.ui.MetroProgressDots
import com.fancyshark.wpdialer.ui.continuumAnchor
import com.fancyshark.wpdialer.ui.jumpTileModifier
import com.fancyshark.wpdialer.ui.rememberFeatherState
import com.fancyshark.wpdialer.ui.rowModifier
import com.fancyshark.wpdialer.ui.settleModifier
import com.fancyshark.wpdialer.ui.hiddenFromAccessibility
import kotlinx.coroutines.launch

@androidx.compose.runtime.Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun HistoryPage(
    items: List<HistoryItem>,
    accent: Color,
    onCall: (String) -> Unit,
    onText: (String) -> Unit,
    onSave: (String) -> Unit,
    onProfile: (String) -> Unit,
    onDelete: (List<HistoryItem>) -> Unit,
    onBlock: (String) -> Unit,
    onDetails: (HistoryItem) -> Unit,
    onAddSpeedDial: (String) -> Unit,
    emptyText: String? = null,
    onRemind: (String) -> Unit = {},
    reminders: List<com.fancyshark.wpdialer.data.Reminder> = emptyList(),
    onCancelReminder: (com.fancyshark.wpdialer.data.Reminder) -> Unit = {},
    canLoadMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    loading: Boolean = false,
) {
    Column(Modifier.fillMaxSize()) {
        // Pending call-back reminders sit above the list, newest-due first.
        reminders.forEach { r ->
            ReminderStrip(r, items, onCancel = { onCancelReminder(r) })
        }
        if (loading) {
            MetroProgressDots(Modifier.padding(top = 12.dp))
        } else if (items.isEmpty()) {
            // A filtered-empty list must not read like a wiped call log —
            // the caller passes filter-specific text when a filter is on.
            if (emptyText != null) {
                MetroEmptyState(emptyText)
            } else {
                MetroEmptyState(
                    stringResource(com.fancyshark.wpdialer.R.string.motion_history_empty),
                    hint = stringResource(com.fancyshark.wpdialer.R.string.home_history_empty),
                )
            }
        } else {
            HistoryList(
                items, accent, onCall, onText, onSave, onProfile, onDelete, onBlock,
                onDetails, onAddSpeedDial, onRemind, canLoadMore, onLoadMore,
            )
        }
    }
}

/** "reminder: call Alice at 14:30 ✕" — the ✕ asks for confirmation upstream. */
@Composable
private fun ReminderStrip(
    reminder: com.fancyshark.wpdialer.data.Reminder,
    history: List<HistoryItem>,
    onCancel: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Contact name first (the caller may have been saved since), then the
    // history's cached/CNAP name, then the formatted number.
    val historyName = remember(reminder.number, history) {
        history.firstOrNull { Repo.numbersMatch(it.number, reminder.number) }?.name
    }
    val name by androidx.compose.runtime.produceState<String?>(historyName, reminder.number) {
        value = Repo.lookupCaller(context, reminder.number).first ?: historyName
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 4.dp)
            .background(Metro.Chrome)
            .padding(start = 12.dp),
    ) {
        Text(
            stringResource(
                com.fancyshark.wpdialer.R.string.home_reminder_strip,
                name?.takeIf { it.isNotBlank() } ?: Repo.pretty(context, reminder.number),
                Repo.wpTime(reminder.at),
            ),
            color = Metro.Foreground,
            style = Metro.Type.Small,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MetroCloseMark(
            stringResource(com.fancyshark.wpdialer.R.string.home_reminder_cancel),
            fontSize = 16.sp,
        ) { onCancel() }
    }
}

/** A history list entry: a day header or a grouped "(n)" row. */
private sealed interface HistoryRow {
    val key: Any

    class Day(day: Long, val label: String) : HistoryRow {
        override val key: Any = "day:$day"
    }

    class Group(val calls: List<HistoryItem>) : HistoryRow {
        override val key: Any = calls.first().id
    }
}

/** Local calendar day of [date] as a sortable number (year * 1000 + day). */
private fun dayOf(date: Long): Long {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = date }
    return c.get(java.util.Calendar.YEAR) * 1000L + c.get(java.util.Calendar.DAY_OF_YEAR)
}

/** "today" / "yesterday" strings, read once per composition. */
@Composable
private fun dayLabelStrings(): Pair<String, String> =
    stringResource(com.fancyshark.wpdialer.R.string.refined_day_today) to
        stringResource(com.fancyshark.wpdialer.R.string.refined_day_yesterday)

/** WP day header: "today", "yesterday", a weekday this week, else the date. */
private fun dayLabel(date: Long, words: Pair<String, String>): String {
    val now = System.currentTimeMillis()
    val day = dayOf(date)
    val yesterday = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, -1)
    }.timeInMillis
    val locale = java.util.Locale.getDefault()
    val thisYear = dayOf(now) / 1000 == day / 1000
    return when {
        day == dayOf(now) -> words.first
        day == dayOf(yesterday) -> words.second
        else -> {
            val skeleton = when {
                now - date < 6 * 24 * 3600_000L -> "EEEE"
                thisYear -> "MMMMd"
                else -> "yMMMMd"
            }
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
            java.text.SimpleDateFormat(pattern, locale).format(date).lowercase(locale)
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun HistoryList(
    items: List<HistoryItem>,
    accent: Color,
    onCall: (String) -> Unit,
    onText: (String) -> Unit,
    onSave: (String) -> Unit,
    onProfile: (String) -> Unit,
    onDelete: (List<HistoryItem>) -> Unit,
    onBlock: (String) -> Unit,
    onDetails: (HistoryItem) -> Unit,
    onAddSpeedDial: (String) -> Unit,
    onRemind: (String) -> Unit,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val accentText = Metro.textAccent(accent)
    val notes by com.fancyshark.wpdialer.data.CallNotes.notes.collectAsState()
    // WP 8.1 groups consecutive calls with the same number and type: "(2)".
    // Bounded to the same calendar day so a group delete never removes old
    // entries the row's single timestamp doesn't represent.
    val groups = remember(items) {
        fun sameDay(a: Long, b: Long): Boolean {
            val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
            val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
            return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
                ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
        }
        val out = mutableListOf<MutableList<HistoryItem>>()
        items.forEach { item ->
            val last = out.lastOrNull()?.lastOrNull()
            // Same line (national vs E.164 spellings unify); withheld calls
            // have no number and group with each other.
            val sameNumber = if (last == null) false
            else if (last.number.isBlank() || item.number.isBlank()) {
                last.number.isBlank() && item.number.isBlank()
            } else {
                Repo.numbersMatch(last.number, item.number)
            }
            if (last != null && last.type == item.type && sameNumber &&
                sameDay(last.date, item.date)
            ) {
                out.last() += item
            } else {
                out += mutableListOf(item)
            }
        }
        out
    }
    // One-handed mode anchors the list to the bottom: index 0 (the newest
    // call) renders just above the thumb, like the dialpad's T9 list.
    val oneHanded by com.fancyshark.wpdialer.data.AppPrefs.oneHandedLists.collectAsState()
    // Day headers ("today", "yesterday", a date) interleaved with the groups.
    // Groups never straddle a day, so a header goes wherever the day changes.
    // Reversed layout draws higher indices ABOVE lower ones, so there the
    // header follows its day's groups to still sit on top of them.
    val dayLabels = dayLabelStrings()
    val rows = remember(groups, oneHanded, dayLabels) {
        val out = mutableListOf<HistoryRow>()
        var day = Long.MIN_VALUE
        var pending: HistoryRow.Day? = null
        groups.forEach { group ->
            val d = dayOf(group.first().date)
            if (d != day) {
                day = d
                pending?.let { out += it }
                val header = HistoryRow.Day(d, dayLabel(group.first().date, dayLabels))
                if (oneHanded) pending = header else out += header
            }
            out += HistoryRow.Group(group)
        }
        pending?.let { out += it }
        out
    }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var menuForId by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    // Load-more: nearing the oldest loaded row asks for the next page. The
    // flow re-arms when the group count changes (a page arrived).
    val loadMore by androidx.compose.runtime.rememberUpdatedState(onLoadMore)
    androidx.compose.runtime.LaunchedEffect(listState, rows.size, canLoadMore) {
        if (!canLoadMore) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        }.collect { last ->
            if (com.fancyshark.wpdialer.data.HistoryPaging.nearEnd(last, rows.size)) {
                loadMore()
            }
        }
    }
    // Rows feather in each time the pivot settles on this page.
    val feather = rememberFeatherState()
    androidx.compose.runtime.SideEffect {
        feather.onContent(groups.isNotEmpty(), listState.firstVisibleItemIndex)
    }
    val titleSize = Metro.Type.Large.fontSize.value
    LazyColumn(
        Modifier
            .fillMaxSize()
            .then(feather.settleModifier { listState.firstVisibleItemIndex }),
        state = listState,
        reverseLayout = oneHanded,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 20.dp),
    ) {
        items(
            rows.size,
            key = { rows[it].key },
            contentType = { if (rows[it] is HistoryRow.Day) 0 else 1 },
        ) { i ->
            val row = rows[i]
            if (row is HistoryRow.Day) {
                Text(
                    row.label,
                    color = accentText,
                    style = Metro.Type.Small,
                    modifier = Modifier
                        .then(feather.rowModifier(i))
                        .padding(top = 14.dp, bottom = 2.dp)
                        .semantics { heading() },
                )
                return@items
            }
            val group = (row as HistoryRow.Group).calls
            val item = group.first()
            val missed = item.type == android.provider.CallLog.Calls.MISSED_TYPE
            val expanded = expandedId == item.id
            // Continuum: the name lifts off toward the profile / details page.
            val anchor = remember { ContinuumAnchor() }
            val token = "history:${item.id}"
            val label = item.name?.takeIf { it.isNotBlank() }
                ?: item.number.takeIf { it.isNotBlank() }
                    ?.let { Repo.pretty(context, it) }
                ?: stringResource(com.fancyshark.wpdialer.R.string.home_unknown)
            fun armContinuum() = Continuum.arm(token, label, anchor.bounds, titleSize)
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(feather.rowModifier(i))
                    .combinedClickable(
                        onClick = { expandedId = if (expanded) null else item.id },
                        onLongClick = { menuForId = item.id },
                    )
                    .padding(vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Photo / initials / "#" — decorative, the name says it all.
                    CallerTile(
                        item.number, item.name, accent, 56.dp,
                        Modifier.clearAndSetSemantics {},
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            if (group.size > 1) "$label (${group.size})" else label,
                            color = if (missed) accentText else Metro.Foreground,
                            style = Metro.Type.Large,
                            fontWeight = FontWeight.Light,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier
                                .continuumAnchor(anchor)
                                // Hidden while its copy is in flight.
                                .graphicsLayer { alpha = if (Continuum.isFlying(token)) 0f else 1f },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // A grouped row shows the mark if any of its calls has a note.
                            if (group.any { notes.containsKey(it.id) }) {
                                androidx.compose.material3.Icon(
                                    Icons.AutoMirrored.Filled.StickyNote2,
                                    contentDescription = stringResource(
                                        com.fancyshark.wpdialer.R.string.home_note_present,
                                    ),
                                    tint = Metro.Subtle,
                                    modifier = Modifier.padding(end = 4.dp).size(14.dp),
                                )
                            }
                            Text(
                                stringResource(
                                    com.fancyshark.wpdialer.R.string.home_history_subtitle,
                                    Repo.historyTypeShort(context, item.type),
                                    Repo.historyWhen(item.date),
                                ),
                                color = Metro.Subtle,
                                style = Metro.Type.Small,
                                fontWeight = FontWeight.Light,
                            )
                        }
                    }
                    if (item.number.isNotBlank()) {
                        // WP 8.1: a missed call's circle wears the accent so
                        // it stands out down the list; the rest stay neutral.
                        val circle = if (missed) accentText else Metro.Foreground
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .border(2.dp, circle, CircleShape)
                                .clickable { onCall(item.number) },
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.Icon(
                                Icons.Filled.Phone,
                                contentDescription = stringResource(com.fancyshark.wpdialer.R.string.home_call),
                                tint = circle,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                // WP-style white context menu on long-press.
                Box {
                    androidx.compose.material3.MaterialTheme(
                        colorScheme = androidx.compose.material3.lightColorScheme(),
                    ) {
                        androidx.compose.material3.DropdownMenu(
                            expanded = menuForId == item.id,
                            onDismissRequest = { menuForId = null },
                        ) {
                            // Withheld/private calls have no number — only
                            // delete makes sense (blocking "" would insert a
                            // junk row, details would aggregate all of them).
                            (
                                if (item.number.isBlank()) {
                                    listOf(
                                        stringResource(com.fancyshark.wpdialer.R.string.home_delete) to { onDelete(group.toList()) },
                                    )
                                } else {
                                    listOf(
                                        stringResource(com.fancyshark.wpdialer.R.string.home_details) to {
                                            armContinuum()
                                            onDetails(item)
                                        },
                                        stringResource(com.fancyshark.wpdialer.R.string.home_delete) to { onDelete(group.toList()) },
                                        stringResource(com.fancyshark.wpdialer.R.string.home_remind_me) to { onRemind(item.number) },
                                        stringResource(com.fancyshark.wpdialer.R.string.home_add_to_speed_dial) to { onAddSpeedDial(item.number) },
                                        stringResource(com.fancyshark.wpdialer.R.string.home_block) to { onBlock(item.number) },
                                    )
                                }
                                ).forEach { (label, action) ->
                                androidx.compose.material3.DropdownMenuItem(
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
                if (expanded) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        // Indented past the thumbnail, under the name.
                        modifier = Modifier.padding(start = 70.dp, top = 10.dp, bottom = 4.dp),
                    ) {
                        if (item.number.isNotBlank()) {
                            HistoryAction(
                                Icons.Filled.Phone,
                                stringResource(com.fancyshark.wpdialer.R.string.home_call), accent,
                            ) { onCall(item.number) }
                            HistoryAction(
                                Icons.AutoMirrored.Filled.Message,
                                stringResource(com.fancyshark.wpdialer.R.string.home_text), accent,
                            ) { onText(item.number) }
                            if (item.name.isNullOrBlank()) {
                                HistoryAction(
                                    Icons.Filled.PersonAdd,
                                    stringResource(com.fancyshark.wpdialer.R.string.home_save), accent,
                                ) { onSave(item.number) }
                            } else {
                                HistoryAction(
                                    Icons.Filled.Person,
                                    stringResource(com.fancyshark.wpdialer.R.string.home_profile), accent,
                                ) {
                                    armContinuum()
                                    onProfile(item.number)
                                }
                            }
                        }
                        HistoryAction(
                            Icons.Filled.Delete,
                            stringResource(com.fancyshark.wpdialer.R.string.home_delete), accent,
                        ) {
                            expandedId = null
                            onDelete(group.toList())
                        }
                        if (item.number.isNotBlank()) {
                            HistoryAction(
                                Icons.Filled.Block,
                                stringResource(com.fancyshark.wpdialer.R.string.home_block), accent,
                            ) {
                                expandedId = null
                                onBlock(item.number)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onClick() },
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .border(2.dp, Metro.Foreground, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                icon,
                contentDescription = label,
                tint = Metro.Foreground,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            label,
            color = Metro.Foreground,
            style = Metro.Type.Tiny,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** WP 8.1 call details: every call with this number, with durations. */
@Composable
fun CallDetailsScreen(
    number: String,
    name: String?,
    history: List<HistoryItem>,
    accent: Color,
    onCall: (String) -> Unit,
    onText: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val entries = remember(history, number) {
        history.filter { Repo.numbersMatch(it.number, number) }
    }
    val notes by com.fancyshark.wpdialer.data.CallNotes.notes.collectAsState()
    // Call-log row id whose note editor is open / whose note awaits delete.
    var editingNoteFor by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf<Long?>(null)
    }
    var confirmDeleteNote by remember { mutableStateOf<Long?>(null) }
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .hiddenFromAccessibility(editingNoteFor != null)
            .padding(horizontal = 20.dp),
    ) {
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.home_details_app_header),
            color = Metro.Foreground,
            style = Metro.Type.AppTitle,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.home_details),
            color = Metro.Foreground,
            style = Metro.Type.PageTitle,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            name?.takeIf { it.isNotBlank() } ?: Repo.pretty(context, number),
            color = Metro.Foreground,
            style = Metro.Type.SubHeader,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (!name.isNullOrBlank()) {
            Text(Repo.pretty(context, number), color = Metro.textAccent(accent), style = Metro.Type.Small)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            HistoryAction(
                Icons.Filled.Phone,
                stringResource(com.fancyshark.wpdialer.R.string.home_call), accent,
            ) { onCall(number) }
            HistoryAction(
                Icons.AutoMirrored.Filled.Message,
                stringResource(com.fancyshark.wpdialer.R.string.home_text), accent,
            ) { onText(number) }
        }
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.home_note_hint),
            color = Metro.Subtle,
            style = Metro.Type.Small,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries.size, key = { entries[it].id }) { i ->
                val e = entries[i]
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editingNoteFor = e.id }
                        .padding(vertical = 7.dp),
                ) {
                    Text(
                        Repo.wpTime(e.date),
                        color = Metro.Foreground,
                        style = Metro.Type.Large,
                        fontWeight = FontWeight.Light,
                    )
                    Text(
                        Repo.historyTypeShort(context, e.type) +
                            if (e.duration > 0) {
                                stringResource(
                                    com.fancyshark.wpdialer.R.string.home_details_duration,
                                    Repo.formatDuration(e.duration),
                                )
                            } else "",
                        color = Metro.Subtle,
                        style = Metro.Type.Small,
                        fontWeight = FontWeight.Light,
                    )
                    notes[e.id]?.let { note ->
                        Text(
                            note,
                            color = Metro.textAccent(accent),
                            style = Metro.Type.Small,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }
    }

    editingNoteFor?.let { callId ->
        val existing = notes[callId]
        // Saveable: an IME config change must not lose a half-typed note.
        var draft by androidx.compose.runtime.saveable.rememberSaveable(callId) {
            mutableStateOf(existing.orEmpty())
        }
        com.fancyshark.wpdialer.ui.MetroDialog(
            onDismiss = { editingNoteFor = null },
            // A stray backdrop tap must not throw away typed text.
            dismissOnBackdrop = false,
        ) {
            Text(
                stringResource(com.fancyshark.wpdialer.R.string.home_note_title),
                color = Metro.Foreground,
                style = Metro.Type.Small,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            com.fancyshark.wpdialer.ui.MetroTextBox(
                draft,
                { draft = it.take(com.fancyshark.wpdialer.data.CallNotes.MAX_LENGTH) },
                accent,
                placeholder = stringResource(com.fancyshark.wpdialer.R.string.home_note_placeholder),
                singleLine = false,
                minLines = 3,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.fancyshark.wpdialer.ui.MetroButton(
                    stringResource(com.fancyshark.wpdialer.R.string.home_note_save),
                    fill = accent,
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    com.fancyshark.wpdialer.data.CallNotes.set(context, callId, draft)
                    editingNoteFor = null
                }
                com.fancyshark.wpdialer.ui.MetroButton(
                    stringResource(com.fancyshark.wpdialer.R.string.main_button_cancel),
                    modifier = Modifier.weight(1f),
                ) { editingNoteFor = null }
            }
            if (existing != null) {
                Text(
                    stringResource(com.fancyshark.wpdialer.R.string.home_note_delete),
                    color = Metro.Foreground,
                    style = Metro.Type.Body,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { confirmDeleteNote = callId }
                        .padding(vertical = 12.dp),
                )
            }
        }
    }

    // After the editor so it stacks (and takes back) above it.
    confirmDeleteNote?.let { callId ->
        com.fancyshark.wpdialer.ui.MetroConfirm(
            title = stringResource(com.fancyshark.wpdialer.R.string.home_note_confirm_delete),
            message = notes[callId],
            confirmLabel = stringResource(com.fancyshark.wpdialer.R.string.main_button_delete),
            cancelLabel = stringResource(com.fancyshark.wpdialer.R.string.main_button_cancel),
            onConfirm = {
                com.fancyshark.wpdialer.data.CallNotes.remove(context, listOf(callId))
                confirmDeleteNote = null
                editingNoteFor = null
            },
            onCancel = { confirmDeleteNote = null },
        )
    }
    }
}

/** WP 8.1-style speed dial: starred contacts as large accent tiles. */
@Composable
fun SpeedDialPage(
    contacts: List<ContactItem>,
    accent: Color,
    onOpen: (ContactItem) -> Unit,
    onCallNumber: (String) -> Unit,
    onRemoveNumber: (String) -> Unit,
    loading: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val numbers by com.fancyshark.wpdialer.data.AppPrefs.speedDialNumbers
        .collectAsState()
    val starred = contacts.filter { it.starred }
    // Plain numbers don't wait on the contacts load; only an otherwise
    // empty page shows the dots.
    if (loading && numbers.isEmpty()) {
        MetroProgressDots(Modifier.padding(top = 12.dp))
        return
    }
    if (starred.isEmpty() && numbers.isEmpty()) {
        MetroEmptyState(
            stringResource(com.fancyshark.wpdialer.R.string.motion_speed_dial_empty),
            hint = stringResource(com.fancyshark.wpdialer.R.string.home_speed_dial_empty),
        )
        return
    }
    val listState = rememberLazyListState()
    val feather = rememberFeatherState()
    androidx.compose.runtime.SideEffect {
        feather.onContent(true, listState.firstVisibleItemIndex)
    }
    val starredRows = starred.chunked(2)
    LazyColumn(
        Modifier
            .fillMaxSize()
            .then(feather.settleModifier { listState.firstVisibleItemIndex }),
        state = listState,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp, end = 20.dp, top = 6.dp, bottom = 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Plain numbers added via history "add to speed dial".
        items(numbers.size, key = { "num_" + numbers[it] }) { i ->
            val number = numbers[i]
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(feather.rowModifier(i))
                    .clickable { onCallNumber(number) },
            ) {
                // Hold 2-9 on the dialpad dials the first eight entries —
                // the tile shows its key so the gesture is discoverable.
                val key = i + 2
                Box(
                    Modifier.size(40.dp).background(accent),
                    contentAlignment = Alignment.Center,
                ) {
                    if (key <= 9) {
                        val keyDesc = stringResource(
                            com.fancyshark.wpdialer.R.string.home_speed_dial_key, key,
                        )
                        Text(
                            key.toString(),
                            color = Metro.onAccent(accent),
                            style = Metro.Type.Large,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.semantics { contentDescription = keyDesc },
                        )
                    }
                }
                Text(
                    Repo.pretty(context, number),
                    color = Metro.Foreground,
                    style = Metro.Type.Large,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                MetroCloseMark(
                    stringResource(com.fancyshark.wpdialer.R.string.ui_remove),
                    fontSize = 18.sp,
                ) { onRemoveNumber(number) }
            }
        }
        items(starredRows.size) { rowIndex ->
            val row = starredRows[rowIndex]
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.then(feather.rowModifier(numbers.size + rowIndex)),
            ) {
                row.forEach { contact ->
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(accent)
                            .clickable { onOpen(contact) },
                    ) {
                        Column(Modifier.padding(0.dp)) {
                            Box(Modifier.fillMaxWidth()) {
                                ContactTile(
                                    contact.name, contact.photoUri, accent, 96.dp,
                                )
                            }
                            Text(
                                contact.name,
                                color = Metro.onAccent(accent),
                                style = Metro.Type.Small,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.padding(
                                    start = 8.dp, bottom = 6.dp, top = 4.dp, end = 8.dp,
                                ),
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Jump-list buckets for the app locale plus Latin (so Arabic/Hindi names
 * bucket under their own script while English names still land on a-z).
 * Everything the index can't place goes under "#", which sorts first.
 */
private class JumpIndex(locale: java.util.Locale) {
    private val index = android.icu.text.AlphabeticIndex<Any>(locale)
        .addLabels(java.util.Locale.ENGLISH)
        .buildImmutableIndex()
    private val lower = locale

    /** Grid tiles: "#" then every letter bucket in index order. */
    val labels: List<String> = listOf("#") + (0 until index.bucketCount)
        .map { index.getBucket(it) }
        .filter { it.labelType == android.icu.text.AlphabeticIndex.Bucket.LabelType.NORMAL }
        .map { it.label.lowercase(lower) }
        .distinct()

    /** (sort order, label) for [name]; "#" names sort before the letters. */
    fun bucketOf(name: String): Pair<Int, String> {
        val i = index.getBucketIndex(name)
        val bucket = index.getBucket(i)
        return if (name.isBlank() ||
            bucket.labelType != android.icu.text.AlphabeticIndex.Bucket.LabelType.NORMAL
        ) {
            -1 to "#"
        } else {
            i to bucket.label.lowercase(lower)
        }
    }
}

@Composable
fun PeoplePage(
    contacts: List<ContactItem>,
    accent: Color,
    loading: Boolean = false,
    onOpen: (ContactItem) -> Unit,
) {
    if (loading) {
        MetroProgressDots(Modifier.padding(top = 12.dp))
        return
    }
    if (contacts.isEmpty()) {
        MetroEmptyState(
            stringResource(com.fancyshark.wpdialer.R.string.home_no_contacts),
            hint = stringResource(com.fancyshark.wpdialer.R.string.motion_people_empty_hint),
        )
        return
    }

    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val jump = remember(locale) { JumpIndex(locale) }
    // Flatten into list entries and record where each letter group starts.
    val entries = remember(contacts, jump) {
        val list = mutableListOf<Any>() // String = group header, ContactItem = row
        val positions = mutableMapOf<String, Int>()
        var current: String? = null
        // Stable sort: names keep the provider's collation within a bucket.
        contacts.map { it to jump.bucketOf(it.name) }
            .sortedBy { it.second.first }
            .forEach { (contact, bucket) ->
                val letter = bucket.second
                if (letter != current) {
                    current = letter
                    positions[letter] = list.size
                    list += letter
                }
                list += contact
            }
        list to positions
    }
    val (list, positions) = entries
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showJump by remember { mutableStateOf(false) }
    val feather = rememberFeatherState()
    androidx.compose.runtime.SideEffect {
        feather.onContent(true, listState.firstVisibleItemIndex)
    }
    val nameSize = Metro.Type.Large.fontSize.value

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(feather.settleModifier { listState.firstVisibleItemIndex }),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 20.dp),
        ) {
            items(list.size) { i ->
                when (val entry = list[i]) {
                    is String -> {
                        Box(
                            Modifier
                                .then(feather.rowModifier(i))
                                .padding(top = 10.dp, bottom = 6.dp)
                                .size(40.dp)
                                .background(accent)
                                .clickable { showJump = true },
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            Text(
                                entry,
                                color = Metro.onAccent(accent),
                                style = Metro.Type.Large,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 5.dp),
                            )
                        }
                    }
                    is ContactItem -> {
                        // Continuum: the name lifts off toward the profile.
                        val anchor = remember { ContinuumAnchor() }
                        val token = "people:${entry.id}"
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .then(feather.rowModifier(i))
                                .clickable {
                                    Continuum.arm(token, entry.name, anchor.bounds, nameSize)
                                    onOpen(entry)
                                }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ContactTile(entry.name, entry.photoUri, accent, 56.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(
                                entry.name,
                                color = Metro.Foreground,
                                style = Metro.Type.Large,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .continuumAnchor(anchor)
                                    .graphicsLayer {
                                        alpha = if (Continuum.isFlying(token)) 0f else 1f
                                    },
                            )
                        }
                    }
                }
            }
        }

        if (showJump) {
            // Back must close the jump grid, not exit the app (People sits at
            // the bottom of the nav stack).
            androidx.activity.compose.BackHandler { showJump = false }
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Metro.Background)
                    .clickable { showJump = false }
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Latin + a second script can top 50 tiles — go 5 wide.
                val columns = if (jump.labels.size > 30) 5 else 4
                jump.labels.chunked(columns).forEachIndexed { rowIndex, row ->
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.forEachIndexed { col, letter ->
                            val target = positions[letter]
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    // Tiles pop in one by one, reading order.
                                    .then(jumpTileModifier(rowIndex * columns + col))
                                    .background(if (target != null) accent else Metro.Chrome)
                                    .clickable(enabled = target != null) {
                                        showJump = false
                                        target?.let { scope.launch { listState.scrollToItem(it) } }
                                    },
                                contentAlignment = Alignment.BottomStart,
                            ) {
                                Text(
                                    letter,
                                    color = if (target != null) Metro.onAccent(accent) else Metro.Dim,
                                    style = if (columns > 4) Metro.Type.Large else Metro.Type.SubHeader,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                                )
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
