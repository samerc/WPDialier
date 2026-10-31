package com.fancyshark.wpdialer.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fancyshark.wpdialer.R
import com.fancyshark.wpdialer.data.AccountRef
import com.fancyshark.wpdialer.data.ContactAccounts
import com.fancyshark.wpdialer.data.ContactRedirects
import com.fancyshark.wpdialer.data.ContactSources
import com.fancyshark.wpdialer.data.DupCandidate
import com.fancyshark.wpdialer.data.DupMatch
import com.fancyshark.wpdialer.data.DupReason
import com.fancyshark.wpdialer.data.DuplicateFinder
import com.fancyshark.wpdialer.data.Duplicates
import com.fancyshark.wpdialer.data.Repo
import com.fancyshark.wpdialer.ui.AppBarAction
import com.fancyshark.wpdialer.ui.ContactTile
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroAppBar
import com.fancyshark.wpdialer.ui.MetroButton
import com.fancyshark.wpdialer.ui.MetroDialog
import com.fancyshark.wpdialer.ui.MetroEmptyState
import com.fancyshark.wpdialer.ui.MetroProgressDots
import com.fancyshark.wpdialer.ui.MetroTextBox
import com.fancyshark.wpdialer.ui.MetroToast
import com.fancyshark.wpdialer.ui.hiddenFromAccessibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Reason line under a suggestion: "Outlook · same number +961 3 039 056". */
@Composable
internal fun dupReasonText(match: DupMatch): String {
    val context = LocalContext.current
    return when (match.reason) {
        DupReason.NUMBER -> stringResource(R.string.acc_reason_number, Repo.pretty(context, match.detail.orEmpty()))
        DupReason.EMAIL -> stringResource(R.string.acc_reason_email, match.detail.orEmpty())
        DupReason.NAME -> stringResource(R.string.acc_reason_name)
    }
}

/** Kind labels of a candidate's accounts ("Google, WhatsApp"). */
@Composable
internal fun candidateAccounts(c: DupCandidate): String {
    val labels = c.accountTypes.distinct().map { rememberAccountLabel(AccountRef(null, it)) }.distinct()
    return labels.joinToString(stringResource(R.string.acc_list_sep))
}

/**
 * "link to <name>": pivot of suggestions (same number / email / similar
 * name) and all contacts. Picking one asks link or merge.
 */
@Composable
fun LinkContactScreen(
    contactId: Long,
    accent: Color,
    onLinked: (newContactId: Long?) -> Unit,
    onMerge: (List<Long>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val target by produceState<ContactSources?>(null, contactId) {
        value = ContactAccounts.loadSources(context, contactId)
    }
    val candidates by produceState<List<DupCandidate>?>(null, contactId) {
        value = Duplicates.loadCandidates(context)
    }
    var chosen by remember { mutableStateOf<DupCandidate?>(null) }
    var busy by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val pagerState = rememberPagerState { 2 }
    val searchFocus = remember { FocusRequester() }
    var focusSearch by remember { mutableStateOf(0) }

    val t = target
    val all = candidates
    val self = all?.firstOrNull { it.contactId == contactId }
    val others = remember(all) { all.orEmpty().filter { it.contactId != contactId } }
    val suggestions = remember(self, others) {
        self?.let { DuplicateFinder.suggestionsFor(it, others).take(40) }.orEmpty()
    }
    val name = t?.displayName ?: self?.name ?: ""
    val overlay = chosen != null || busy

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().hiddenFromAccessibility(overlay)) {
            StatePivot(
                title = stringResource(R.string.acc_link_header, name).uppercase(),
                state = pagerState,
                modifier = Modifier.weight(1f),
                pages = listOf<Pair<String, @Composable () -> Unit>>(
                    stringResource(R.string.acc_suggestions) to {
                        when {
                            all == null -> MetroProgressDots(Modifier.padding(top = 16.dp))
                            else -> LazyColumn(
                                Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 14.dp, bottom = 20.dp),
                            ) {
                                if (suggestions.isEmpty()) {
                                    item { MetroEmptyState(stringResource(R.string.acc_no_suggestions)) }
                                }
                                items(suggestions.size, key = { suggestions[it].other.contactId }) { i ->
                                    val m = suggestions[i]
                                    CandidateRow(
                                        m.other, accent,
                                        listOf(candidateAccounts(m.other), dupReasonText(m))
                                            .filter { it.isNotBlank() }.joinToString(" · "),
                                    ) { chosen = m.other }
                                }
                                item {
                                    Text(
                                        stringResource(R.string.acc_link_explain),
                                        color = Metro.Subtle,
                                        style = Metro.Type.Small,
                                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp),
                                    )
                                }
                            }
                        }
                    },
                    stringResource(R.string.acc_all) to {
                        Column(Modifier.fillMaxSize()) {
                            MetroTextBox(
                                value = query,
                                onValueChange = { query = it },
                                accent = accent,
                                placeholder = stringResource(R.string.acc_search_hint),
                                modifier = Modifier
                                    .padding(horizontal = 20.dp, vertical = 10.dp)
                                    .focusRequester(searchFocus),
                            )
                            LaunchedEffect(focusSearch) {
                                if (focusSearch > 0) runCatching { searchFocus.requestFocus() }
                            }
                            if (all == null) {
                                MetroProgressDots(Modifier.padding(top = 16.dp))
                            } else {
                                val q = DuplicateFinder.normalizeName(query)
                                val digits = query.filter { it.isDigit() }
                                val shown = remember(others, q, digits) {
                                    others.filter { c ->
                                        q.isEmpty() ||
                                            DuplicateFinder.normalizeName(c.name).contains(q) ||
                                            (digits.length >= 3 && c.phones.any { it.filter(Char::isDigit).contains(digits) }) ||
                                            c.emails.any { it.contains(query.trim(), ignoreCase = true) }
                                    }
                                }
                                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                                    items(shown.size, key = { shown[it].contactId }) { i ->
                                        val c = shown[i]
                                        CandidateRow(c, accent, candidateAccounts(c)) { chosen = c }
                                    }
                                }
                            }
                        }
                    },
                ),
            )
            MetroAppBar(
                actions = listOf(
                    AppBarAction(Icons.Filled.Search, stringResource(R.string.acc_search)) {
                        scope.launch { pagerState.animateScrollToPage(1) }
                        focusSearch++
                    },
                ),
            )
        }

        chosen?.let { other ->
            val otherSources by produceState<ContactSources?>(null, other.contactId) {
                value = ContactAccounts.loadSources(context, other.contactId)
            }
            val linkedMsg = stringResource(R.string.acc_linked_toast)
            val failedMsg = stringResource(R.string.acc_link_failed)
            MetroDialog(onDismiss = { chosen = null }) {
                Text(
                    stringResource(R.string.acc_link_pair, name, other.name),
                    color = Metro.Foreground,
                    style = Metro.Type.SubHeader,
                )
                val mine = t?.raws?.firstOrNull { !it.readOnly } ?: t?.raws?.firstOrNull()
                val theirs = otherSources?.raws?.firstOrNull { !it.readOnly } ?: otherSources?.raws?.firstOrNull()
                if (mine != null && theirs != null) {
                    Text(
                        stringResource(
                            R.string.acc_link_pair_accounts,
                            accountLine(rememberAccountLabel(mine.account), mine.account),
                            accountLine(rememberAccountLabel(theirs.account), theirs.account),
                        ),
                        color = Metro.Subtle,
                        style = Metro.Type.Small,
                        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                    )
                }
                ChoiceBlock(stringResource(R.string.acc_link), stringResource(R.string.acc_link_desc)) {
                    val mineRaws = t?.raws.orEmpty()
                    val theirRaws = otherSources?.raws.orEmpty()
                    if (mineRaws.isEmpty() || theirRaws.isEmpty()) return@ChoiceBlock
                    chosen = null
                    busy = true
                    scope.launch {
                        val ok = ContactAccounts.link(context, (mineRaws + theirRaws).map { it.rawId })
                        val newId = withContext(Dispatchers.IO) {
                            ContactAccounts.contactIdOfRaw(context, mineRaws.first().rawId)
                        }
                        if (ok) {
                            withContext(Dispatchers.IO) {
                                ContactAccounts.rebindTiles(
                                    context,
                                    listOfNotNull(t?.lookupKey, otherSources?.lookupKey, other.lookupKey),
                                    newId,
                                )
                            }
                            ContactRedirects.record(listOf(contactId, other.contactId), newId)
                            Duplicates.invalidate()
                        }
                        busy = false
                        MetroToast.show(if (ok) linkedMsg else failedMsg)
                        if (ok) onLinked(newId)
                    }
                }
                ChoiceBlock(stringResource(R.string.acc_merge), stringResource(R.string.acc_merge_desc)) {
                    chosen = null
                    onMerge(listOf(contactId, other.contactId))
                }
                Row(Modifier.padding(top = 10.dp)) {
                    MetroButton(stringResource(R.string.contact_cancel), modifier = Modifier.weight(1f)) {
                        chosen = null
                    }
                }
            }
        }
        if (busy) BusyOverlay(stringResource(R.string.acc_linking))
    }
}

/** Title + explanation option inside a Metro dialog (link / merge). */
@Composable
private fun ChoiceBlock(title: String, description: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onClick() }
            .padding(vertical = 12.dp),
    ) {
        Text(title, color = Metro.Foreground, style = Metro.Type.Large, fontWeight = androidx.compose.ui.text.font.FontWeight.Light)
        Text(description, color = Metro.Subtle, style = Metro.Type.Small)
    }
}

@Composable
internal fun CandidateRow(c: DupCandidate, accent: Color, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactTile(c.name, c.photoUri, accent, 60.dp)
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(
                c.name.ifBlank { c.phones.firstOrNull().orEmpty() },
                color = Metro.Foreground,
                style = Metro.Type.Large,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Light,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Metro.Subtle, style = Metro.Type.Small, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
