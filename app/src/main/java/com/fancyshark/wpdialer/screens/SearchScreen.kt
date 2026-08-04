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
    onOpen: (ContactItem) -> Unit,
) {
    var query by remember { mutableStateOf("") }
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

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.search_title),
            color = Metro.Foreground,
            fontSize = 15.sp,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(top = 18.dp, bottom = 14.dp),
        )
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
                    fontSize = 18.sp,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Metro.Foreground, fontSize = 18.sp),
                cursorBrush = SolidColor(accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        Spacer(Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(results.size, key = { results[it].id }) { i ->
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
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Light,
                        maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
