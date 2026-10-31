package com.fancyshark.wpdialer.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.data.ContactItem
import com.fancyshark.wpdialer.ui.ContactTile
import com.fancyshark.wpdialer.ui.Metro

@Composable
fun SearchScreen(
    contacts: List<ContactItem>,
    accent: Color,
    history: List<com.fancyshark.wpdialer.data.HistoryItem> = emptyList(),
    onOpenCall: (com.fancyshark.wpdialer.data.HistoryItem) -> Unit = {},
    onOpen: (ContactItem) -> Unit,
) {
    // Saveable: the state holder keeps the query alive while a result's
    // profile is open on top, so back returns to the same search.
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val numberIds by androidx.compose.runtime.produceState(emptySet<Long>(), query) {
        value = if (query.count { it.isDigit() } >= 3) {
            com.fancyshark.wpdialer.data.Repo.contactIdsByNumber(context, query)
        } else emptySet()
    }
    val results = remember(query, contacts, numberIds) {
        if (query.isBlank()) emptyList()
        else contacts.filter {
            it.name.contains(query, ignoreCase = true) || it.id in numberIds
        }
    }

    // One-handed mode flips the layout: results grow upward from a
    // bottom-anchored search box, keeping everything within thumb reach.
    val oneHanded by com.fancyshark.wpdialer.data.AppPrefs.oneHandedLists
        .collectAsState()

    val searchBox: @Composable () -> Unit = {
        Box(
            Modifier
                .fillMaxWidth()
                .border(2.dp, Metro.Foreground)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            if (query.isEmpty()) {
                Text(
                    stringResource(com.fancyshark.wpdialer.R.string.search_hint),
                    color = Metro.Subtle,
                    style = Metro.Type.Body,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                // Token (not a bare TextStyle) so the field gets the app font.
                textStyle = Metro.Type.Body.copy(color = Metro.Foreground),
                cursorBrush = SolidColor(accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
    }
    // Call-log matches (loaded history pages), one row per line.
    val callResults = remember(query, history) {
        com.fancyshark.wpdialer.data.HistorySearch.search(history, query)
    }
    val accentText = Metro.textAccent(accent)

    val resultsList: @Composable (Modifier) -> Unit = { modifier ->
        LazyColumn(modifier, reverseLayout = oneHanded) {
            items(results.size, key = { "c" + results[it].id }) { i ->
                val contact = results[i]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(contact) }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContactTile(contact.name, contact.photoUri, accent, 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        contact.name,
                        color = Metro.Foreground,
                        style = Metro.Type.Large,
                        fontWeight = FontWeight.Light,
                        maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            if (callResults.isNotEmpty()) {
                item(key = "history_header") {
                    Text(
                        stringResource(com.fancyshark.wpdialer.R.string.search_history_header),
                        color = accentText,
                        style = Metro.Type.Small,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                    )
                }
            }
            items(callResults.size, key = { "h" + callResults[it].id }) { i ->
                val call = callResults[i]
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenCall(call) }
                        .padding(vertical = 6.dp),
                ) {
                    Text(
                        call.name?.takeIf { it.isNotBlank() }
                            ?: com.fancyshark.wpdialer.data.Repo.pretty(context, call.number),
                        color = Metro.Foreground,
                        style = Metro.Type.Large,
                        fontWeight = FontWeight.Light,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            com.fancyshark.wpdialer.R.string.home_history_subtitle,
                            com.fancyshark.wpdialer.data.Repo.historyTypeShort(context, call.type),
                            com.fancyshark.wpdialer.data.Repo.historyWhen(call.date),
                        ),
                        color = Metro.Subtle,
                        style = Metro.Type.Small,
                        fontWeight = FontWeight.Light,
                    )
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.search_title),
            color = Metro.Foreground,
            style = Metro.Type.AppTitle,
            modifier = Modifier.padding(top = 18.dp, bottom = 14.dp),
        )
        if (oneHanded) {
            resultsList(Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.padding(top = 8.dp))
            searchBox()
            Spacer(Modifier.padding(top = 12.dp))
        } else {
            searchBox()
            Spacer(Modifier.padding(top = 8.dp))
            resultsList(Modifier.fillMaxSize())
        }
    }
}
