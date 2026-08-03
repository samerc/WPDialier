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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.data.ContactItem
import com.fancyshark.wpdialer.data.HistoryItem
import com.fancyshark.wpdialer.data.Repo
import com.fancyshark.wpdialer.ui.ContactTile
import com.fancyshark.wpdialer.ui.Metro
import kotlinx.coroutines.launch

@Composable
fun HistoryPage(
    items: List<HistoryItem>,
    accent: Color,
    onCall: (String) -> Unit,
    onText: (String) -> Unit,
    onSave: (String) -> Unit,
    onProfile: (String) -> Unit,
    onDelete: (HistoryItem) -> Unit,
    onBlock: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    if (items.isEmpty()) {
        Text(
            "To make a call, tap an icon below.",
            color = Metro.Subtle,
            fontSize = 20.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 20.dp)) {
        items(items.size, key = { items[it].id }) { i ->
            val item = items[i]
            val missed = item.type == android.provider.CallLog.Calls.MISSED_TYPE
            val expanded = expandedId == item.id
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { expandedId = if (expanded) null else item.id }
                    .padding(vertical = 9.dp),
            ) {
                Text(
                    item.name?.takeIf { it.isNotBlank() }
                        ?: item.number.takeIf { it.isNotBlank() }?.let { Repo.pretty(context, it) }
                        ?: "unknown",
                    color = if (missed) accent else Metro.Foreground,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(
                    "${Repo.historyTypeLabel(item.type)}, ${Repo.relativeTime(item.date)}",
                    color = Metro.Subtle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light,
                )
                if (expanded) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    ) {
                        if (item.number.isNotBlank()) {
                            HistoryAction(
                                Icons.Filled.Phone,
                                "call", accent,
                            ) { onCall(item.number) }
                            HistoryAction(
                                Icons.AutoMirrored.Filled.Message,
                                "text", accent,
                            ) { onText(item.number) }
                            if (item.name.isNullOrBlank()) {
                                HistoryAction(
                                    Icons.Filled.PersonAdd,
                                    "save", accent,
                                ) { onSave(item.number) }
                            } else {
                                HistoryAction(
                                    Icons.Filled.Person,
                                    "profile", accent,
                                ) { onProfile(item.number) }
                            }
                        }
                        HistoryAction(
                            Icons.Filled.Delete,
                            "delete", accent,
                        ) {
                            expandedId = null
                            onDelete(item)
                        }
                        if (item.number.isNotBlank()) {
                            HistoryAction(
                                Icons.Filled.Block,
                                "block", accent,
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

/** WP 8.1-style speed dial: starred contacts as large accent tiles. */
@Composable
fun SpeedDialPage(
    contacts: List<ContactItem>,
    accent: Color,
    onOpen: (ContactItem) -> Unit,
) {
    val starred = contacts.filter { it.starred }
    if (starred.isEmpty()) {
        Text(
            "Pin favorites here from a contact's profile.",
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
            "no contacts",
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
