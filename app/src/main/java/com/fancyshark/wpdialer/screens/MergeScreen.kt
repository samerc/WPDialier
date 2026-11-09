package com.fancyshark.wpdialer.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fancyshark.wpdialer.R
import com.fancyshark.wpdialer.data.AccountOp
import com.fancyshark.wpdialer.data.AccountRef
import com.fancyshark.wpdialer.data.AccountStyle
import com.fancyshark.wpdialer.data.ContactAccounts
import com.fancyshark.wpdialer.data.ContactMerge
import com.fancyshark.wpdialer.data.ContactRedirects
import com.fancyshark.wpdialer.data.ContactSources
import com.fancyshark.wpdialer.data.DataRow
import com.fancyshark.wpdialer.data.Duplicates
import com.fancyshark.wpdialer.data.MergeChoices
import com.fancyshark.wpdialer.data.Mime
import com.fancyshark.wpdialer.data.RawSource
import com.fancyshark.wpdialer.data.Repo
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroButton
import com.fancyshark.wpdialer.ui.MetroConfirm
import com.fancyshark.wpdialer.ui.MetroDialog
import com.fancyshark.wpdialer.ui.MetroEmptyState
import com.fancyshark.wpdialer.ui.MetroProgressDots
import com.fancyshark.wpdialer.ui.MetroToast
import com.fancyshark.wpdialer.ui.hiddenFromAccessibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Same choice of kept part as ContactAccounts.merge: exact account first. */
private fun keptPart(writable: List<RawSource>, target: AccountRef): RawSource? =
    writable.firstOrNull { it.account == target }
        ?: writable.firstOrNull { AccountStyle.sameAccount(target, it.account) }

/**
 * Merge preview for 2+ contacts: which account keeps the result, the
 * conflicting single-valued fields to choose (name, photo, company), what
 * the combined contact holds and what is deleted — only after the merged
 * contact is saved and verified.
 */
@Composable
fun MergeScreen(
    contactIds: List<Long>,
    accent: Color,
    onMerged: (newContactId: Long?) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loaded by produceState<List<ContactSources>?>(null, contactIds) {
        value = contactIds.distinct().mapNotNull { ContactAccounts.loadSources(context, it) }
    }
    val accounts by produceState<List<AccountRef>?>(null) {
        value = ContactAccounts.targetAccounts(context)
    }
    var targetKey by rememberSaveable { mutableStateOf<String?>(null) }
    var nameRaw by rememberSaveable { mutableStateOf<Long?>(null) }
    var photoRaw by rememberSaveable { mutableStateOf<Long?>(null) }
    var orgRaw by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickAccount by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val accentText = Metro.textAccent(accent)

    val contacts = loaded
    if (contacts == null || accounts == null) {
        MetroProgressDots(Modifier.padding(top = 40.dp))
        return
    }
    if (contacts.size < 2) {
        Column(Modifier.fillMaxSize().padding(top = 40.dp)) {
            MetroEmptyState(stringResource(R.string.acc_merge_gone))
            MetroButton(stringResource(R.string.acc_close), modifier = Modifier.padding(20.dp).fillMaxWidth()) { onCancel() }
        }
        return
    }
    val raws = contacts.flatMap { it.raws }.distinctBy { it.rawId }
    val groups = contacts.flatMap { it.groups.entries }.associate { it.key to it.value }
    val writableAll = raws.filter { !it.readOnly }
    val readOnly = raws.filter { it.readOnly }

    // "keep in": accounts already holding a writable part first, then any
    // other writable account.
    val options = remember(writableAll, accounts) {
        (writableAll.map { it.account } + accounts.orEmpty())
            .filter { AccountStyle.canHoldFullContact(it.type) }
            .distinct()
    }
    fun keyOf(a: AccountRef) = "${a.type.orEmpty()}\u0001${a.name.orEmpty()}\u0001${a.type == null}"
    val target = options.firstOrNull { keyOf(it) == targetKey } ?: options.firstOrNull() ?: AccountRef(null, null)
    val kept = keptPart(writableAll, target)
    val ordered = listOfNotNull(kept) + writableAll.filter { it.rawId != kept?.rawId }
    val inputs = remember(ordered, groups) { ordered.map { ContactAccounts.mergeInput(it, groups) } }
    val conflicts = remember(inputs) { ContactMerge.conflicts(inputs) }
    val choices = MergeChoices(
        nameRaw = nameRaw ?: conflicts.names.firstOrNull()?.rawId,
        photoRaw = photoRaw ?: conflicts.photos.firstOrNull()?.rawId,
        orgRaw = orgRaw ?: conflicts.orgs.firstOrNull()?.rawId,
    )
    val combined = remember(inputs, choices) { ContactMerge.combine(inputs, choices) }
    val existingLabels by produceState(emptyList<String>(), target) {
        value = ContactAccounts.labelTitlesIn(context, target)
    }
    val labelPlan = remember(combined, existingLabels) { ContactMerge.mapLabels(combined.labels, existingLabels) }
    val targetLabel = rememberAccountLabel(target)
    val accountOf: (Long) -> RawSource? = { id -> raws.firstOrNull { it.rawId == id } }
    val toDelete = writableAll.filter { it.rawId != kept?.rawId }
    // App-specific rows of the deleted copies aren't copied — say so.
    val lostRows = remember(inputs, kept) { ContactMerge.uncopiedRows(inputs, kept?.rawId) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().hiddenFromAccessibility(pickAccount || confirm || busy)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
                    Text(
                        stringResource(R.string.acc_merge_header).uppercase(),
                        color = Metro.Foreground,
                        style = Metro.Type.AppTitle,
                    )
                    Text(stringResource(R.string.acc_merge_preview), color = Metro.Foreground, style = Metro.Type.PageTitle)
                    Text(
                        contacts.joinToString(" + ") { it.displayName },
                        color = Metro.Subtle,
                        style = Metro.Type.Small,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (writableAll.isEmpty()) {
                    // Only read-only parts: nothing can be combined.
                    Text(
                        stringResource(R.string.acc_merge_only_link),
                        color = Metro.Foreground,
                        style = Metro.Type.Body,
                        modifier = Modifier.padding(20.dp),
                    )
                } else {
                    Text(
                        stringResource(R.string.acc_keep_in),
                        color = Metro.Subtle,
                        style = Metro.Type.Small,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
                    )
                    AccountPickRow(
                        target,
                        targetLabel,
                        listOfNotNull(
                            target.name?.takeIf { AccountStyle.kindOf(target.type) != com.fancyshark.wpdialer.data.AccountKind.LOCAL },
                            stringResource(R.string.acc_tap_to_change),
                        ).joinToString(" · "),
                    ) { pickAccount = true }

                    if (conflicts.any) {
                        MergeSubHeader(stringResource(R.string.acc_choose))
                        if (conflicts.names.isNotEmpty()) {
                            MergeFieldLabel(stringResource(R.string.acc_field_name))
                            conflicts.names.forEach { row ->
                                RadioRow(
                                    text = row.data1.orEmpty(),
                                    source = accountOf(row.rawId)?.let { rememberAccountLabel(it.account) },
                                    selected = choices.nameRaw == row.rawId,
                                    accent = accent,
                                ) { nameRaw = row.rawId }
                            }
                        }
                        if (conflicts.photos.isNotEmpty()) {
                            MergeFieldLabel(stringResource(R.string.acc_field_photo))
                            Row(
                                Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                conflicts.photos.forEach { row ->
                                    PhotoChoice(row, accent, selected = choices.photoRaw == row.rawId) { photoRaw = row.rawId }
                                }
                            }
                        }
                        if (conflicts.orgs.isNotEmpty()) {
                            MergeFieldLabel(stringResource(R.string.contact_company))
                            conflicts.orgs.forEach { row ->
                                RadioRow(
                                    text = listOfNotNull(row.data1, row.text("data4")).joinToString(" · "),
                                    source = accountOf(row.rawId)?.let { rememberAccountLabel(it.account) },
                                    selected = choices.orgRaw == row.rawId,
                                    accent = accent,
                                ) { orgRaw = row.rawId }
                            }
                        }
                    }

                    MergeSubHeader(stringResource(R.string.acc_combined))
                    Column(
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (combined.phones.isNotEmpty()) {
                            Column {
                                MergeInlineLabel(stringResource(R.string.acc_phones))
                                combined.phones.forEach { p ->
                                    val type = phoneType(p.row)
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            listOfNotNull(type, Repo.pretty(context, p.row.data1.orEmpty())).joinToString(" "),
                                            color = Metro.Foreground,
                                            style = Metro.Type.Body,
                                        )
                                        if (p.parts > 1) {
                                            Text(
                                                stringResource(R.string.acc_in_both),
                                                color = Metro.Subtle,
                                                style = Metro.Type.Small,
                                                modifier = Modifier.padding(start = 8.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (combined.emails.isNotEmpty()) {
                            Column {
                                MergeInlineLabel(stringResource(R.string.acc_emails))
                                combined.emails.forEach {
                                    Text(it.data1.orEmpty(), color = Metro.Foreground, style = Metro.Type.Body)
                                }
                            }
                        }
                        val kinds = buildList {
                            if (combined.events.isNotEmpty()) add(stringResource(R.string.acc_kind_dates))
                            if (combined.addresses.isNotEmpty()) add(stringResource(R.string.acc_field_address))
                            if (combined.websites.isNotEmpty()) add(stringResource(R.string.contact_website))
                            if (combined.others.isNotEmpty()) add(stringResource(R.string.acc_kind_other))
                        }
                        if (kinds.isNotEmpty()) {
                            val from = (combined.events + combined.addresses + combined.websites + combined.others)
                                .mapNotNull { accountOf(it.rawId)?.account }.distinct()
                                .map { rememberAccountLabel(it) }.distinct()
                            Column {
                                MergeInlineLabel(kinds.joinToString(stringResource(R.string.acc_list_sep)))
                                Text(
                                    stringResource(R.string.acc_from_sources, from.joinToString(stringResource(R.string.acc_list_sep))),
                                    color = Metro.Foreground,
                                    style = Metro.Type.Body,
                                )
                            }
                        }
                        if (combined.labels.isNotEmpty()) {
                            Column {
                                MergeInlineLabel(stringResource(R.string.acc_labels))
                                Text(
                                    combined.labels.joinToString(stringResource(R.string.acc_list_sep)),
                                    color = Metro.Foreground,
                                    style = Metro.Type.Body,
                                )
                                if (labelPlan.create.isNotEmpty()) {
                                    Text(
                                        stringResource(
                                            R.string.acc_labels_created,
                                            labelPlan.create.joinToString(stringResource(R.string.acc_list_sep)),
                                            targetLabel,
                                        ),
                                        color = Metro.Subtle,
                                        style = Metro.Type.Small,
                                    )
                                }
                            }
                        }
                        val noteCount = inputs.count { i -> i.rows.any { it.mimetype == Mime.NOTE && it.data1 != null } }
                        if (combined.note != null) {
                            Column {
                                MergeInlineLabel(stringResource(R.string.acc_notes))
                                Text(
                                    if (noteCount > 1) stringResource(R.string.acc_notes_both) else combined.note,
                                    color = Metro.Foreground,
                                    style = Metro.Type.Body,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Column {
                            MergeInlineLabel(stringResource(R.string.acc_stays_header))
                            Text(stringResource(R.string.acc_stays_merge), color = Metro.Foreground, style = Metro.Type.Body)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Footer: what gets deleted, and only after the save.
            Column(Modifier.padding(horizontal = 20.dp)) {
                toDelete.forEach { raw ->
                    val l = rememberAccountLabel(raw.account)
                    Text(
                        stringResource(
                            R.string.acc_merge_deletes,
                            raw.displayName ?: contacts.first { it.contactId == raw.contactId }.displayName,
                            accountLine(l, raw.account),
                        ),
                        color = Metro.Subtle,
                        style = Metro.Type.Small,
                    )
                }
                if (readOnly.isNotEmpty()) {
                    val names = readOnly.map { it.account }.distinct().map { rememberAccountLabel(it) }.distinct()
                    Text(
                        stringResource(R.string.acc_readonly_stay, names.joinToString(stringResource(R.string.acc_list_sep))),
                        color = Metro.Subtle,
                        style = Metro.Type.Small,
                    )
                }
                if (lostRows > 0) {
                    Text(
                        pluralStringResource(R.plurals.acc_private_rows, lostRows, lostRows),
                        color = Metro.Subtle,
                        style = Metro.Type.Small,
                    )
                }
            }
            Row(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetroButton(
                    stringResource(if (writableAll.isEmpty()) R.string.acc_link else R.string.acc_merge),
                    fill = accent,
                    modifier = Modifier.weight(1f),
                ) { confirm = true }
                MetroButton(stringResource(R.string.contact_cancel), modifier = Modifier.weight(1f)) { onCancel() }
            }
        }

        if (pickAccount) {
            MetroDialog(onDismiss = { pickAccount = false }) {
                Text(
                    stringResource(R.string.acc_keep_in).uppercase(),
                    color = Metro.Foreground,
                    style = Metro.Type.Small,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                options.forEach { option ->
                    val l = rememberAccountLabel(option)
                    Text(
                        accountLine(l, option),
                        color = if (option == target) accentText else Metro.Foreground,
                        style = Metro.Type.Large,
                        fontWeight = FontWeight.Light,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                targetKey = keyOf(option)
                                pickAccount = false
                            }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }

        if (confirm) {
            val names = contacts.joinToString(" + ") { it.displayName }
            val mergedMsg = stringResource(R.string.acc_merged_toast, combined.name?.data1 ?: contacts.first().displayName)
            val keptMsg = stringResource(R.string.acc_merged_partial)
            val failMsg = stringResource(R.string.acc_merge_failed)
            val deletesLine = toDelete.map { accountLine(rememberAccountLabel(it.account), it.account) }.distinct()
            val lostLine = if (lostRows > 0) pluralStringResource(R.plurals.acc_private_rows, lostRows, lostRows) else null
            MetroConfirm(
                title = stringResource(
                    if (writableAll.isEmpty()) R.string.acc_link_confirm_title else R.string.acc_merge_confirm_title,
                    names,
                ),
                message = if (writableAll.isEmpty()) {
                    stringResource(R.string.acc_link_desc)
                } else {
                    listOfNotNull(
                        stringResource(R.string.acc_merge_confirm_into, accountLine(targetLabel, target)),
                        deletesLine.takeIf { it.isNotEmpty() }?.let {
                            stringResource(R.string.acc_merge_confirm_deletes, it.joinToString(stringResource(R.string.acc_list_sep)))
                        },
                        lostLine,
                        stringResource(R.string.acc_move_confirm_safe),
                    ).joinToString("\n\n")
                },
                confirmLabel = stringResource(if (writableAll.isEmpty()) R.string.acc_link else R.string.acc_merge),
                cancelLabel = stringResource(R.string.contact_cancel),
                confirmFill = accent,
                onConfirm = {
                    confirm = false
                    busy = true
                    scope.launch {
                        val result = ContactAccounts.merge(context, contacts, target, choices)
                        busy = false
                        when (result) {
                            is AccountOp.Done -> {
                                withContext(Dispatchers.IO) {
                                    ContactAccounts.rebindTiles(context, contacts.mapNotNull { it.lookupKey }, result.contactId)
                                }
                                ContactRedirects.record(contacts.map { it.contactId }, result.contactId)
                                Duplicates.invalidate()
                                MetroToast.show(if (result.sourceKept) keptMsg else mergedMsg)
                                onMerged(result.contactId)
                            }
                            is AccountOp.Failed -> MetroToast.show(failMsg)
                        }
                    }
                },
                onCancel = { confirm = false },
            )
        }
        if (busy) BusyOverlay(stringResource(R.string.acc_merging))
    }
}

@Composable
private fun phoneType(row: DataRow): String? {
    val resources = androidx.compose.ui.platform.LocalResources.current
    val type = row.int("data2") ?: return null
    return android.provider.ContactsContract.CommonDataKinds.Phone
        .getTypeLabel(resources, type, row.text("data3")).toString().lowercase()
}

@Composable
private fun MergeSubHeader(text: String) {
    Text(
        text,
        color = Metro.Subtle,
        style = Metro.Type.SubHeader,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun MergeFieldLabel(text: String) {
    Text(
        text,
        color = Metro.Subtle,
        style = Metro.Type.Small,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun MergeInlineLabel(text: String) {
    Text(text, color = Metro.Subtle, style = Metro.Type.Small)
}

/** Metro radio: outlined circle, accent dot when selected. */
@Composable
private fun RadioRow(text: String, source: String?, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton) { onClick() }
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).border(2.dp, Metro.Foreground, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
        }
        Text(text, color = Metro.Foreground, style = Metro.Type.Body, modifier = Modifier.padding(start = 12.dp))
        if (source != null) {
            Text(source, color = Metro.Subtle, style = Metro.Type.Small, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** A part's photo thumbnail; the chosen one gets the WP outline. */
@Composable
private fun PhotoChoice(row: DataRow, accent: Color, selected: Boolean, onClick: () -> Unit) {
    val bitmap = remember(row) {
        row.blob("data15")?.let { bytes ->
            runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(
        Modifier
            .size(80.dp)
            .then(if (selected) Modifier.border(3.dp, Metro.Foreground) else Modifier)
            .padding(4.dp)
            .background(if (selected) accent else Metro.Dim)
            .selectable(selected = selected, role = Role.RadioButton) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
