package com.fancyshark.wpdialer.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import com.fancyshark.wpdialer.ui.MetroButton
import kotlinx.coroutines.launch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.data.ContactDetail
import com.fancyshark.wpdialer.data.HistoryItem
import com.fancyshark.wpdialer.data.Repo
import com.fancyshark.wpdialer.data.SimPrefs
import com.fancyshark.wpdialer.data.Sims
import com.fancyshark.wpdialer.ui.AppBarAction
import com.fancyshark.wpdialer.ui.ContactTile
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroAppBar

@Composable
fun ContactDetailScreen(
    contactId: Long,
    accent: Color,
    refreshKey: Int,
    history: List<HistoryItem>,
    onCall: (String) -> Unit,
    onText: (String) -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val detail by produceState<ContactDetail?>(null, contactId, refreshKey) {
        value = Repo.loadContactDetail(context, contactId)
    }
    val d = detail ?: return
    var starred by remember(d.id, d.starred) { mutableStateOf(d.starred) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ContactDetailBody(d, accent, history, onCall, onText, Modifier.weight(1f))
            MetroAppBar(
                actions = listOf(
                    AppBarAction(Icons.Filled.Edit, "edit") { onEdit() },
                    AppBarAction(
                        if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                        if (starred) "unpin" else "pin",
                    ) {
                        starred = !starred
                        scope.launch { Repo.setStarred(context, d.id, starred) }
                    },
                    AppBarAction(Icons.Filled.Share, "share") {
                        d.lookupKey?.let { key ->
                            runCatching {
                                val vcard = android.net.Uri.withAppendedPath(
                                    android.provider.ContactsContract.Contacts.CONTENT_VCARD_URI,
                                    key,
                                )
                                context.startActivity(
                                    android.content.Intent.createChooser(
                                        android.content.Intent(android.content.Intent.ACTION_SEND)
                                            .setType("text/x-vcard")
                                            .putExtra(android.content.Intent.EXTRA_STREAM, vcard),
                                        "share contact",
                                    ),
                                )
                            }
                        }
                    },
                    AppBarAction(Icons.Filled.Delete, "delete") { confirmDelete = true },
                ),
            )
        }

        if (confirmDelete) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Metro.Background.copy(alpha = 0.96f))
                    .clickable { confirmDelete = false },
            ) {
                Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp)) {
                    Text(
                        "delete ${d.name}?",
                        color = Metro.Foreground,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    Row(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement
                            .spacedBy(10.dp),
                    ) {
                        MetroButton("delete", fill = Metro.Red, modifier = Modifier.weight(1f)) {
                            scope.launch {
                                Repo.deleteContact(context, d.id)
                                onDeleted()
                            }
                        }
                        MetroButton("cancel", modifier = Modifier.weight(1f)) {
                            confirmDelete = false
                        }
                    }
                }
            }
        }
    }
}

private fun normalized(number: String): String =
    number.filter { it.isDigit() }.takeLast(9)

@Composable
private fun ContactDetailBody(
    d: ContactDetail,
    accent: Color,
    history: List<HistoryItem>,
    onCall: (String) -> Unit,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val simOptions = remember { Sims.options(context) }
    var simPref by remember(d.id) { mutableStateOf(SimPrefs.get(context, d.id)) }
    var showSimChooser by remember { mutableStateOf(false) }

    val contactNumbers = remember(d) { d.phones.map { normalized(it.number) }.filter { it.isNotEmpty() } }
    val personHistory = remember(d, history) {
        history.filter { normalized(it.number) in contactNumbers }.take(15)
    }

    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                d.name.uppercase(),
                color = Metro.Foreground,
                fontSize = 15.sp,
                letterSpacing = 1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                "profile",
                color = Metro.Foreground,
                fontSize = 48.sp,
                fontWeight = FontWeight.Light,
            )
            Spacer(Modifier.height(14.dp))
            ContactTile(d.name, d.photoUri, accent, 96.dp)
            Spacer(Modifier.height(22.dp))

            if (d.phones.isEmpty()) {
                Text(
                    "no phone numbers",
                    color = Metro.Subtle,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Light,
                )
            }
            d.phones.forEach { phone ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onCall(phone.number) }
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "call ${phone.label}",
                            color = Metro.Foreground,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Light,
                        )
                        Text(Repo.pretty(context, phone.number), color = accent, fontSize = 15.sp)
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.Message,
                        contentDescription = "text ${phone.label}",
                        tint = Metro.Foreground,
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onText(phone.number) }
                            .padding(8.dp),
                    )
                }
            }

            if (!d.address.isNullOrBlank()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(
                                            "geo:0,0?q=${android.net.Uri.encode(d.address)}",
                                        ),
                                    ),
                                )
                            }
                        }
                        .padding(vertical = 9.dp),
                ) {
                    Text(
                        "map address",
                        color = Metro.Foreground,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text(d.address, color = accent, fontSize = 15.sp)
                }
            }

            if (simOptions.size > 1) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showSimChooser = true }
                        .padding(vertical = 9.dp),
                ) {
                    Text(
                        "preferred SIM",
                        color = Metro.Foreground,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text(
                        simOptions.firstOrNull { it.flat == simPref }?.label ?: "not set",
                        color = accent,
                        fontSize = 15.sp,
                    )
                }
            }

            if (!d.note.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "note",
                    color = Metro.Foreground,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
                )
                Text(
                    d.note,
                    color = Metro.Subtle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (personHistory.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "history",
                    color = Metro.Foreground,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
                )
                personHistory.forEach { item ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(
                            Repo.historyTypeLabel(item.type),
                            color = Metro.Foreground,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Light,
                        )
                        Text(
                            Repo.historyWhen(item.date),
                            color = Metro.Subtle,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Light,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (showSimChooser) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Metro.Background.copy(alpha = 0.96f))
                    .clickable { showSimChooser = false },
            ) {
                Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp)) {
                    Text(
                        "PREFERRED SIM",
                        color = Metro.Foreground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                    (listOf<Pair<String, String?>>("no preference" to null) +
                        simOptions.map { it.label to it.flat }
                        ).forEach { (label, flat) ->
                        val selected = simPref == flat
                        Text(
                            label,
                            color = if (selected) accent else Metro.Foreground,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    SimPrefs.set(context, d.id, flat)
                                    simPref = flat
                                    showSimChooser = false
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}
