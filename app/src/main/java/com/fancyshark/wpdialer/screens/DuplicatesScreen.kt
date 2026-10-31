package com.fancyshark.wpdialer.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fancyshark.wpdialer.R
import com.fancyshark.wpdialer.data.AppPrefs
import com.fancyshark.wpdialer.data.DupGroup
import com.fancyshark.wpdialer.data.DupMatch
import com.fancyshark.wpdialer.data.Duplicates
import com.fancyshark.wpdialer.ui.ContactTile
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroAppBar
import com.fancyshark.wpdialer.ui.MetroEmptyState
import com.fancyshark.wpdialer.ui.MetroProgressDots

/**
 * Settings -> find duplicates: groups of contacts that look like the same
 * person (shared number / email, similar name). Tapping a group opens the
 * merge preview; "not duplicates" hides it for good (persisted, backed up).
 */
@Composable
fun DuplicatesScreen(
    accent: Color,
    refreshKey: Int,
    onOpen: (List<Long>) -> Unit,
) {
    val context = LocalContext.current
    val dismissed by AppPrefs.dupDismissed.collectAsState()
    val groups by produceState<List<DupGroup>?>(null, refreshKey, dismissed) {
        value = Duplicates.scan(context)
    }
    val accentText = Metro.textAccent(accent)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
            Text(stringResource(R.string.acc_dups_header), color = Metro.Foreground, style = Metro.Type.AppTitle)
            Text(stringResource(R.string.acc_dups_title), color = Metro.Foreground, style = Metro.Type.PageTitle)
        }
        val list = groups
        Column(Modifier.weight(1f)) {
            when {
                list == null -> MetroProgressDots(Modifier.padding(top = 16.dp))
                list.isEmpty() -> MetroEmptyState(
                    stringResource(R.string.acc_dups_none),
                    hint = stringResource(R.string.acc_dups_none_hint),
                )
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 20.dp),
                ) {
                    items(list.size, key = { list[it].signature }) { i ->
                        val g = list[i]
                        val first = g.members.first()
                        val reason = dupReasonText(DupMatch(g.members.last(), g.reason, g.detail))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(g.members.map { it.contactId }) }
                                .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ContactTile(first.name, first.photoUri, accent, 60.dp)
                            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                                Text(
                                    g.members.joinToString(" + ") { it.name.ifBlank { it.phones.firstOrNull().orEmpty() } },
                                    color = Metro.Foreground,
                                    style = Metro.Type.Large,
                                    fontWeight = FontWeight.Light,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(reason, color = Metro.Subtle, style = Metro.Type.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                stringResource(R.string.acc_not_dups),
                                color = accentText,
                                style = Metro.Type.Small,
                                modifier = Modifier
                                    .clickable(role = Role.Button) { AppPrefs.dismissDuplicate(context, g.signature) }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
        // Only menu item: bring dismissed groups back.
        if (dismissed.isNotEmpty()) {
            MetroAppBar(
                actions = emptyList(),
                menu = listOf(
                    stringResource(R.string.acc_dups_reset) to { AppPrefs.clearDismissedDuplicates(context) },
                ),
                minimized = true,
            )
        }
    }
}
