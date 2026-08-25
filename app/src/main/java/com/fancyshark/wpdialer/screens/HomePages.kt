package com.fancyshark.wpdialer.screens

import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.data.ContactItem
import com.fancyshark.wpdialer.data.HistoryItem
import com.fancyshark.wpdialer.data.Repo
import com.fancyshark.wpdialer.ui.ContactTile
import com.fancyshark.wpdialer.ui.Metro
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
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (items.isEmpty()) {
        Text(
            // A filtered-empty list must not read like a wiped call log —
            // the caller passes filter-specific text when a filter is on.
            emptyText ?: stringResource(com.fancyshark.wpdialer.R.string.home_history_empty),
            color = Metro.Subtle,
            fontSize = 20.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
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
            if (last != null && last.type == item.type &&
                last.number.filter(Char::isDigit) == item.number.filter(Char::isDigit) &&
                sameDay(last.date, item.date)
            ) {
                out.last() += item
            } else {
                out += mutableListOf(item)
            }
        }
        out
    }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var menuForId by remember { mutableStateOf<Long?>(null) }
    // One-handed mode anchors the list to the bottom: index 0 (the newest
    // call) renders just above the thumb, like the dialpad's T9 list.
    val oneHanded by com.fancyshark.wpdialer.data.AppPrefs.oneHandedLists.collectAsState()
    LazyColumn(
        Modifier.fillMaxSize(),
        reverseLayout = oneHanded,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 20.dp),
    ) {
        items(groups.size, key = { groups[it].first().id }) { i ->
            val group = groups[i]
            val item = group.first()
            val missed = item.type == android.provider.CallLog.Calls.MISSED_TYPE
            val expanded = expandedId == item.id
            Column(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { expandedId = if (expanded) null else item.id },
                        onLongClick = { menuForId = item.id },
                    )
                    .padding(vertical = 9.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val label = item.name?.takeIf { it.isNotBlank() }
                            ?: item.number.takeIf { it.isNotBlank() }
                                ?.let { Repo.pretty(context, it) }
                            ?: stringResource(com.fancyshark.wpdialer.R.string.home_unknown)
                        Text(
                            if (group.size > 1) "$label (${group.size})" else label,
                            color = if (missed) accent else Metro.Foreground,
                            fontSize = 27.sp,
                            fontWeight = FontWeight.Light,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            stringResource(
                                com.fancyshark.wpdialer.R.string.home_history_subtitle,
                                Repo.historyTypeShort(context, item.type),
                                Repo.historyWhen(item.date),
                            ),
                            color = Metro.Subtle,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Light,
                        )
                    }
                    if (item.number.isNotBlank()) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .border(2.dp, Metro.Foreground, CircleShape)
                                .clickable { onCall(item.number) },
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.material3.Icon(
                                Icons.Filled.Phone,
                                contentDescription = stringResource(com.fancyshark.wpdialer.R.string.home_call),
                                tint = Metro.Foreground,
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
                                        stringResource(com.fancyshark.wpdialer.R.string.home_details) to { onDetails(item) },
                                        stringResource(com.fancyshark.wpdialer.R.string.home_delete) to { onDelete(group.toList()) },
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
                                            fontSize = 17.sp,
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
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
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
                                ) { onProfile(item.number) }
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
            fontSize = 11.sp,
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
    val digits = number.filter(Char::isDigit)
    val entries = remember(history, number) {
        history.filter { it.number.filter(Char::isDigit) == digits }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.home_details_app_header),
            color = Metro.Foreground,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.home_details),
            color = Metro.Foreground,
            fontSize = 48.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            name?.takeIf { it.isNotBlank() } ?: Repo.pretty(context, number),
            color = Metro.Foreground,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (!name.isNullOrBlank()) {
            Text(Repo.pretty(context, number), color = accent, fontSize = 15.sp)
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
        Spacer(Modifier.height(14.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries.size, key = { entries[it].id }) { i ->
                val e = entries[i]
                Column(Modifier.padding(vertical = 7.dp)) {
                    Text(
                        Repo.wpTime(e.date),
                        color = Metro.Foreground,
                        fontSize = 21.sp,
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
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Light,
                    )
                }
            }
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
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val numbers by com.fancyshark.wpdialer.data.AppPrefs.speedDialNumbers
        .collectAsState()
    val starred = contacts.filter { it.starred }
    if (starred.isEmpty() && numbers.isEmpty()) {
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.home_speed_dial_empty),
            color = Metro.Subtle,
            fontSize = 20.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
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
                    .clickable { onCallNumber(number) },
            ) {
                Box(Modifier.size(40.dp).background(accent))
                Text(
                    Repo.pretty(context, number),
                    color = Metro.Foreground,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
                Text(
                    "✕",
                    color = Metro.Subtle,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onRemoveNumber(number) }
                        .padding(8.dp),
                )
            }
        }
        items(starred.chunked(2).size) { rowIndex ->
            val row = starred.chunked(2)[rowIndex]
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Normal,
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

private const val JUMP_LETTERS = "#abcdefghijklmnopqrstuvwxyz"

private fun groupLetterOf(name: String): Char {
    val c = name.firstOrNull()?.lowercaseChar() ?: '#'
    return if (c in 'a'..'z') c else '#'
}

@Composable
fun PeoplePage(
    contacts: List<ContactItem>,
    accent: Color,
    onOpen: (ContactItem) -> Unit,
) {
    if (contacts.isEmpty()) {
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.home_no_contacts),
            color = Metro.Subtle,
            fontSize = 18.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(20.dp),
        )
        return
    }

    // Flatten into list entries and record where each letter group starts.
    val entries = remember(contacts) {
        val list = mutableListOf<Any>() // Char = group header, ContactItem = row
        val positions = mutableMapOf<Char, Int>()
        var current: Char? = null
        contacts.sortedBy { groupLetterOf(it.name).let { c -> if (c == '#') ' ' else c } }
            .forEach { contact ->
                val letter = groupLetterOf(contact.name)
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

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 20.dp),
        ) {
            items(list.size) { i ->
                when (val entry = list[i]) {
                    is Char -> {
                        Box(
                            Modifier
                                .padding(top = 10.dp, bottom = 6.dp)
                                .size(40.dp)
                                .background(accent)
                                .clickable { showJump = true },
                            contentAlignment = Alignment.BottomStart,
                        ) {
                            Text(
                                entry.toString(),
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 5.dp),
                            )
                        }
                    }
                    is ContactItem -> {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(entry) }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ContactTile(entry.name, entry.photoUri, accent, 46.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                entry.name,
                                color = Metro.Foreground,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Normal,
                                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
                JUMP_LETTERS.toList().chunked(4).forEach { row ->
                    Row(
                        Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        row.forEach { letter ->
                            val target = positions[letter]
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(if (target != null) accent else Metro.Chrome)
                                    .clickable(enabled = target != null) {
                                        showJump = false
                                        target?.let { scope.launch { listState.scrollToItem(it) } }
                                    },
                                contentAlignment = Alignment.BottomStart,
                            ) {
                                Text(
                                    letter.toString(),
                                    color = if (target != null) Color.White else Metro.Dim,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                                )
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
