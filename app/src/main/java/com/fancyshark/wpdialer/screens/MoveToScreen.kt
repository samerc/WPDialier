package com.fancyshark.wpdialer.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.R
import com.fancyshark.wpdialer.data.AccountKind
import com.fancyshark.wpdialer.data.AccountOp
import com.fancyshark.wpdialer.data.AccountRef
import com.fancyshark.wpdialer.data.AccountStyle
import com.fancyshark.wpdialer.data.ContactAccounts
import com.fancyshark.wpdialer.data.ContactRedirects
import com.fancyshark.wpdialer.data.ContactSources
import com.fancyshark.wpdialer.data.ProfileFacts
import com.fancyshark.wpdialer.data.RawSummary
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroConfirm
import com.fancyshark.wpdialer.ui.MetroEmptyState
import com.fancyshark.wpdialer.ui.MetroOverlay
import com.fancyshark.wpdialer.ui.MetroProgressDots
import com.fancyshark.wpdialer.ui.MetroToast
import com.fancyshark.wpdialer.ui.hiddenFromAccessibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Shared account UI (account squares, labels, the busy overlay, the
 * "what it holds" summary) plus the move / copy account picker.
 */

/** Account-kind label, resolved off the main thread (authenticator lookup). */
@Composable
internal fun rememberAccountLabel(account: AccountRef): String {
    val context = LocalContext.current
    val kind = AccountStyle.kindOf(account.type)
    val initial = AccountStyle.brandLabel(kind)
        ?: if (kind == AccountKind.LOCAL) stringResource(R.string.acc_phone_only)
        else account.type?.substringAfterLast('.') ?: ""
    val label by produceState(initial, account) {
        value = withContext(Dispatchers.IO) { ContactAccounts.accountLabel(context, account) }
    }
    return label
}

/** Square account badge: brand color + monogram (a phone glyph for local storage). */
@Composable
internal fun AccountSquare(account: AccountRef, label: String, size: Dp = 44.dp) {
    val kind = AccountStyle.kindOf(account.type)
    Box(
        Modifier.size(size).background(Color(AccountStyle.color(account.type))),
        contentAlignment = Alignment.Center,
    ) {
        if (kind == AccountKind.LOCAL) {
            Icon(
                Icons.Filled.PhoneAndroid,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.5f),
            )
        } else {
            val mono = AccountStyle.monogram(kind, label)
            Text(
                mono,
                color = Color.White,
                fontSize = if (mono.length > 1) (size.value * 0.34f).sp else (size.value * 0.45f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Full-screen "working" panel; swallows back so an operation can't be abandoned mid-way. */
@Composable
internal fun BusyOverlay(text: String) {
    MetroOverlay(onBack = {}, background = Metro.Background.copy(alpha = 0.92f), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                text.uppercase(),
                color = Metro.Subtle,
                style = Metro.Type.Small,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            MetroProgressDots()
        }
    }
}

/** "2 phones, email, photo, birthday" — what a part holds. */
@Composable
internal fun summaryText(s: RawSummary): String {
    val parts = buildList {
        if (s.phones > 0) add(pluralStringResource(R.plurals.acc_sum_phones, s.phones, s.phones))
        if (s.emails > 0) add(pluralStringResource(R.plurals.acc_sum_emails, s.emails, s.emails))
        if (s.photo) add(stringResource(R.string.acc_sum_photo))
        if (s.birthday) add(stringResource(R.string.acc_sum_birthday))
        if (s.otherDates > 0) add(pluralStringResource(R.plurals.acc_sum_dates, s.otherDates, s.otherDates))
        if (s.addresses > 0) add(pluralStringResource(R.plurals.acc_sum_addresses, s.addresses, s.addresses))
        if (s.company) add(stringResource(R.string.acc_sum_company))
        if (s.jobTitle) add(stringResource(R.string.acc_sum_title))
        if (s.websites > 0) add(stringResource(R.string.acc_sum_website))
        if (s.note) add(stringResource(R.string.acc_sum_notes))
        if (s.labels.isNotEmpty()) add(stringResource(R.string.acc_sum_labels))
        if (s.otherFields > 0) add(pluralStringResource(R.plurals.acc_sum_other, s.otherFields, s.otherFields))
    }
    return parts.joinToString(stringResource(R.string.acc_list_sep))
        .ifEmpty { stringResource(R.string.acc_sum_name_only) }
}

/** Short first name for messages ("Alice moved to Outlook"). */
internal fun shortName(name: String): String =
    name.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() } ?: name

/**
 * Account picker for moving (or copying) one part of a contact. Lists the
 * writable accounts, the current one dimmed, flags accounts that already
 * hold part of this contact, and spells out what moves and what stays.
 */
@Composable
fun MoveToScreen(
    contactId: Long,
    rawId: Long,
    copy: Boolean,
    accent: Color,
    onDone: (newContactId: Long?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by produceState<ContactSources?>(null, contactId) {
        value = ContactAccounts.loadSources(context, contactId)
    }
    val accounts by produceState<List<AccountRef>?>(null) {
        value = ContactAccounts.targetAccounts(context)
    }
    var confirmTarget by remember { mutableStateOf<AccountRef?>(null) }
    var busy by remember { mutableStateOf(false) }
    val accentText = Metro.textAccent(accent)

    val s = sources
    val src = s?.raws?.firstOrNull { it.rawId == rawId }
    if (s == null || accounts == null) {
        MetroProgressDots(Modifier.padding(top = 40.dp))
        return
    }
    if (src == null || src.readOnly) {
        MetroEmptyState(stringResource(R.string.acc_part_gone), modifier = Modifier.padding(top = 40.dp))
        return
    }
    val name = s.displayName
    val summary = remember(src, s.groups) { ProfileFacts.summarize(src, s.groups) }
    val srcLabel = rememberAccountLabel(src.account)
    val targets = accounts.orEmpty().filter { !AccountStyle.sameAccount(it, src.account) && it != src.account }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .hiddenFromAccessibility(confirmTarget != null || busy)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)) {
                Text(
                    stringResource(if (copy) R.string.acc_copy_header else R.string.acc_move_header, name)
                        .uppercase(),
                    color = Metro.Foreground,
                    style = Metro.Type.AppTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.acc_choose_account),
                    color = Metro.Foreground,
                    style = Metro.Type.PageTitle,
                )
                Text(
                    stringResource(R.string.acc_from, accountLine(srcLabel, src.account)),
                    color = Metro.Subtle,
                    style = Metro.Type.Small,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            if (targets.isEmpty()) {
                Text(
                    stringResource(R.string.acc_no_targets),
                    color = Metro.Subtle,
                    style = Metro.Type.Body,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            targets.forEach { account ->
                val label = rememberAccountLabel(account)
                val holdsPart = s.raws.any { it.rawId != src.rawId && AccountStyle.sameAccount(account, it.account) }
                val sub = buildList {
                    if (AccountStyle.kindOf(account.type) == AccountKind.LOCAL) {
                        add(stringResource(R.string.acc_local_hint))
                    } else {
                        account.name?.let { add(it) }
                    }
                    if (holdsPart) add(stringResource(R.string.acc_already_has_part, shortName(name)))
                }.joinToString(" · ")
                AccountPickRow(account, label, sub, onClick = { confirmTarget = account })
            }
            // The current account, dimmed and inert.
            Row(
                Modifier
                    .fillMaxWidth()
                    .alpha(0.35f)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccountSquare(src.account, srcLabel)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(srcLabel, color = Metro.Foreground, style = Metro.Type.Large, fontWeight = FontWeight.Light)
                    Text(
                        listOfNotNull(src.account.name, stringResource(R.string.acc_current)).joinToString(" · "),
                        color = Metro.Foreground,
                        style = Metro.Type.Small,
                    )
                }
            }

            Text(
                stringResource(if (copy) R.string.acc_what_copies else R.string.acc_what_moves),
                color = Metro.Subtle,
                style = Metro.Type.SubHeader,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp),
            )
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(summaryText(summary), color = Metro.Foreground, style = Metro.Type.Small)
                if (summary.labels.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.acc_labels_matched,
                            summary.labels.joinToString(stringResource(R.string.acc_list_sep)),
                        ),
                        color = Metro.Foreground,
                        style = Metro.Type.Small,
                    )
                }
                Text(stringResource(R.string.acc_stays), color = Metro.Foreground, style = Metro.Type.Small)
                val readOnlyLabels = s.raws.filter { it.readOnly }.map { it.account }.distinct()
                if (readOnlyLabels.isNotEmpty()) {
                    val names = readOnlyLabels.map { rememberAccountLabel(it) }.distinct()
                    Text(
                        stringResource(R.string.acc_readonly_stay, names.joinToString(stringResource(R.string.acc_list_sep))),
                        color = Metro.Subtle,
                        style = Metro.Type.Small,
                    )
                }
                if (summary.privateRows > 0) {
                    Text(
                        pluralStringResource(R.plurals.acc_private_rows, summary.privateRows, summary.privateRows),
                        color = Metro.Subtle,
                        style = Metro.Type.Small,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        confirmTarget?.let { target ->
            val targetLabel = rememberAccountLabel(target)
            val first = shortName(name)
            val movedMsg = stringResource(if (copy) R.string.acc_copied_toast else R.string.acc_moved_toast, first, targetLabel)
            val keptMsg = stringResource(R.string.acc_moved_source_kept, first, targetLabel)
            val failMsg = stringResource(if (copy) R.string.acc_copy_failed else R.string.acc_move_failed, first)
            val targetName = accountLine(targetLabel, target)
            val srcName = accountLine(srcLabel, src.account)
            MetroConfirm(
                title = stringResource(if (copy) R.string.acc_copy_confirm_title else R.string.acc_move_confirm_title, targetLabel),
                message = if (copy) {
                    stringResource(R.string.acc_copy_confirm_body, first, targetName, srcName)
                } else {
                    val body = if (AccountStyle.kindOf(src.account.type) == AccountKind.LOCAL) {
                        stringResource(R.string.acc_move_confirm_body_local, first, targetName)
                    } else {
                        stringResource(R.string.acc_move_confirm_body, first, targetName, srcName)
                    }
                    body + "\n\n" + stringResource(R.string.acc_move_confirm_safe)
                },
                confirmLabel = stringResource(if (copy) R.string.acc_copy else R.string.acc_move),
                cancelLabel = stringResource(R.string.contact_cancel),
                confirmFill = accent,
                onConfirm = {
                    confirmTarget = null
                    busy = true
                    scope.launch {
                        val oldKey = s.lookupKey
                        val result = if (copy) {
                            ContactAccounts.copy(context, s, src, target)
                        } else {
                            ContactAccounts.move(context, s, src, target)
                        }
                        busy = false
                        when (result) {
                            is AccountOp.Done -> {
                                withContext(Dispatchers.IO) {
                                    ContactAccounts.rebindTiles(context, listOfNotNull(oldKey), result.contactId)
                                }
                                ContactRedirects.record(listOf(contactId), result.contactId)
                                MetroToast.show(if (result.sourceKept) keptMsg else movedMsg)
                                onDone(result.contactId)
                            }
                            is AccountOp.Failed -> MetroToast.show(failMsg)
                        }
                    }
                },
                onCancel = { confirmTarget = null },
            )
        }
        if (busy) {
            BusyOverlay(stringResource(if (copy) R.string.acc_copying else R.string.acc_moving))
        }
    }
}

/** "Google · alice@gmail.com" from an already-resolved kind label. */
internal fun accountLine(label: String, account: AccountRef): String {
    val name = account.name?.takeIf { it.isNotBlank() && AccountStyle.kindOf(account.type) != AccountKind.LOCAL }
    return if (name == null) label else "$label · $name"
}

@Composable
internal fun AccountPickRow(account: AccountRef, label: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountSquare(account, label)
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(label, color = Metro.Foreground, style = Metro.Type.Large, fontWeight = FontWeight.Light)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    color = Metro.Subtle,
                    style = Metro.Type.Small,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
