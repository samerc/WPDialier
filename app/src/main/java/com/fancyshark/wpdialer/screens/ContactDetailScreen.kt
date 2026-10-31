package com.fancyshark.wpdialer.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.R
import com.fancyshark.wpdialer.data.AccountKind
import com.fancyshark.wpdialer.data.AccountStyle
import com.fancyshark.wpdialer.data.AppPrefs
import com.fancyshark.wpdialer.data.ContactAccounts
import com.fancyshark.wpdialer.data.ContactAppAction
import com.fancyshark.wpdialer.data.ContactCall
import com.fancyshark.wpdialer.data.ContactDetail
import com.fancyshark.wpdialer.data.ContactRedirects
import com.fancyshark.wpdialer.data.ContactSources
import com.fancyshark.wpdialer.data.DataRow
import com.fancyshark.wpdialer.data.GroupInfo
import com.fancyshark.wpdialer.data.HistoryItem
import com.fancyshark.wpdialer.data.Mime
import com.fancyshark.wpdialer.data.ProfileFacts
import com.fancyshark.wpdialer.data.RawSource
import com.fancyshark.wpdialer.data.Repo
import com.fancyshark.wpdialer.data.SimOption
import com.fancyshark.wpdialer.data.SimPrefs
import com.fancyshark.wpdialer.data.Sims
import com.fancyshark.wpdialer.data.UpcomingDate
import com.fancyshark.wpdialer.ui.AppBarAction
import com.fancyshark.wpdialer.ui.ContactTile
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroAppBar
import com.fancyshark.wpdialer.ui.MetroConfirm
import com.fancyshark.wpdialer.ui.MetroDialog
import com.fancyshark.wpdialer.ui.MetroOverlay
import com.fancyshark.wpdialer.ui.MetroToast
import com.fancyshark.wpdialer.ui.hiddenFromAccessibility
import com.fancyshark.wpdialer.ui.metroTilt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The contact card: a "profile | history | notes" pivot under the person's
 * name. The profile is one long WP page — photo + name header, accent action
 * tiles, numbers, emails, coming-up dates, about, per-contact settings, the
 * accounts it's saved in (with move / copy / link / unlink / delete per
 * part), and recent calls.
 *
 * The aggregate id can change under us (a move, merge, link or a sync
 * re-aggregation): the screen follows [ContactRedirects] and, failing that,
 * re-resolves its last lookup key; only a contact that is really gone pops
 * back via [onDeleted].
 */
@Composable
fun ContactDetailScreen(
    contactId: Long,
    accent: Color,
    refreshKey: Int,
    history: List<HistoryItem>,
    onCall: (String) -> Unit,
    onText: (String) -> Unit,
    onEdit: (Long) -> Unit,
    onDeleted: () -> Unit,
    onLink: (Long) -> Unit = {},
    onMoveTo: (contactId: Long, rawId: Long, copy: Boolean) -> Unit = { _, _, _ -> },
    onReachDown: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val redirects by ContactRedirects.map.collectAsState()
    var idOverride by rememberSaveable { mutableStateOf<Long?>(null) }
    val id = ContactRedirects.resolve(redirects, idOverride ?: contactId)
    var knownLookup by rememberSaveable { mutableStateOf<String?>(null) }
    var localTick by remember { mutableIntStateOf(0) }
    var gone by remember { mutableStateOf(false) }
    val detail by produceState<ContactDetail?>(null, id, refreshKey, localTick) {
        val loaded = Repo.loadContactDetail(context, id)
        if (loaded != null) {
            value = loaded
            loaded.lookupKey?.let { knownLookup = it }
            return@produceState
        }
        // Re-aggregated under a new id? The lookup key still finds it.
        val moved = knownLookup?.let { ContactAccounts.resolveLookup(context, it) }
        if (moved != null && moved != id) idOverride = moved else gone = true
    }
    LaunchedEffect(gone) { if (gone) onDeleted() }
    val sources by produceState<ContactSources?>(null, id, refreshKey, localTick) {
        value = ContactAccounts.loadSources(context, id)
    }
    val d = detail ?: return
    var starred by remember(d.id, d.starred) { mutableStateOf(d.starred) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmBlock by remember { mutableStateOf(false) }
    val shareChooserTitle = stringResource(R.string.contact_share_chooser)
    val pinTileLabel = stringResource(R.string.contact_pin_to_start)
    val pinShortcutLabel = stringResource(R.string.contact_add_call_shortcut)
    val pinUnsupported = stringResource(R.string.launcher_pin_unsupported)

    // Choosers/overlays hoisted so they cover the whole screen (pivot
    // headers included), not just the pager page.
    val simOptions = remember { Sims.options(context) }
    var simPref by remember(d.id) {
        mutableStateOf(d.phones.firstOrNull()?.let { SimPrefs.get(context, it.number) })
    }
    var showSimChooser by remember { mutableStateOf(false) }
    var appChooser by remember { mutableStateOf<Pair<String, List<ContactAppAction>>?>(null) }
    var fieldsOf by remember { mutableStateOf<RawSource?>(null) }
    var confirmDeleteRaw by remember { mutableStateOf<RawSource?>(null) }
    var confirmRemoveSpeed by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    // Every call with this person across all their numbers, shared by the
    // profile's "recent", the history and the notes pages.
    val phoneNumbers = remember(d) { d.phones.map { it.number } }
    var callsTick by remember { mutableIntStateOf(0) }
    val calls by produceState<List<ContactCall>?>(null, phoneNumbers, refreshKey, history, callsTick) {
        value = Repo.callsWith(context, phoneNumbers)
    }
    var confirmDeleteCall by remember { mutableStateOf<ContactCall?>(null) }
    val labelFor: (String) -> String? = remember(d) {
        { number: String -> d.phones.firstOrNull { Repo.numbersMatch(it.number, number) }?.label }
    }

    val pagerState = rememberPagerState { 3 }

    /** After an account write: follow the contact's new id, refresh tiles, reload. */
    fun afterAccountChange(newId: Long?, oldKey: String?) {
        if (newId != null && newId != id) {
            ContactRedirects.record(listOf(id), newId)
            idOverride = newId
        }
        scope.launch {
            withContext(Dispatchers.IO) { ContactAccounts.rebindTiles(context, listOfNotNull(oldKey), newId) }
        }
        com.fancyshark.wpdialer.data.Duplicates.invalidate()
        localTick++
    }

    val unlinkedMsg = stringResource(R.string.acc_unlinked_toast)
    val unlinkFailedMsg = stringResource(R.string.acc_unlink_failed)
    val workingLabel = stringResource(R.string.acc_working)

    val bodyOverlayOpen = appChooser != null || showSimChooser || fieldsOf != null
    val anyOverlay = bodyOverlayOpen || confirmDelete || confirmDeleteCall != null ||
        confirmDeleteRaw != null || confirmRemoveSpeed != null || confirmBlock || busy != null
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().hiddenFromAccessibility(anyOverlay)) {
            StatePivot(
                title = d.name.uppercase(),
                state = pagerState,
                modifier = Modifier.weight(1f),
                pages = listOf<Pair<String, @Composable () -> Unit>>(
                    stringResource(R.string.contact_profile) to {
                        ProfilePage(
                            d = d,
                            sources = sources,
                            accent = accent,
                            simOptions = simOptions,
                            simPref = simPref,
                            calls = calls,
                            labelFor = labelFor,
                            onCall = onCall,
                            onText = onText,
                            onAppChooser = { appChooser = it },
                            onSimChooser = { showSimChooser = true },
                            onRemoveSpeedDial = { confirmRemoveSpeed = it },
                            onLink = { onLink(id) },
                            onMove = { raw, copy -> onMoveTo(id, raw.rawId, copy) },
                            onFields = { fieldsOf = it },
                            onUnlink = { raw ->
                                val all = sources?.raws.orEmpty()
                                val others = all.map { it.rawId }.filter { it != raw.rawId }
                                busy = workingLabel
                                scope.launch {
                                    val oldKey = sources?.lookupKey
                                    val ok = ContactAccounts.unlink(context, raw.rawId, others)
                                    // The page stays on the rest of the contact.
                                    val newId = withContext(Dispatchers.IO) {
                                        others.firstNotNullOfOrNull { ContactAccounts.contactIdOfRaw(context, it) }
                                    }
                                    busy = null
                                    MetroToast.show(if (ok) unlinkedMsg else unlinkFailedMsg)
                                    if (ok) afterAccountChange(newId, oldKey)
                                }
                            },
                            onDeleteRaw = { confirmDeleteRaw = it },
                            onSeeAllHistory = { scope.launch { pagerState.animateScrollToPage(1) } },
                        )
                    },
                    stringResource(R.string.contact_history) to {
                        ContactHistoryPage(
                            calls = calls,
                            accent = accent,
                            simOptions = simOptions,
                            labelFor = labelFor,
                            onCall = onCall,
                            onCopy = { number ->
                                // Android 13+ confirms clipboard writes itself.
                                runCatching {
                                    context.getSystemService(android.content.ClipboardManager::class.java)
                                        ?.setPrimaryClip(android.content.ClipData.newPlainText(d.name, number))
                                }
                            },
                            onDelete = { confirmDeleteCall = it },
                        )
                    },
                    stringResource(R.string.contact_pivot_notes) to {
                        ContactNotesPage(calls = calls, accent = accent, labelFor = labelFor)
                    },
                ),
            )
            if (!bodyOverlayOpen) MetroAppBar(
                onSwipeDown = onReachDown,
                actions = listOf(
                    AppBarAction(Icons.Filled.Edit, stringResource(R.string.contact_edit)) { onEdit(id) },
                    AppBarAction(
                        if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                        stringResource(if (starred) R.string.contact_unpin else R.string.contact_pin),
                    ) {
                        starred = !starred
                        scope.launch { Repo.setStarred(context, d.id, starred) }
                    },
                    AppBarAction(Icons.Filled.Link, stringResource(R.string.acc_link)) { onLink(id) },
                    AppBarAction(Icons.Filled.Share, stringResource(R.string.contact_share)) {
                        d.lookupKey?.let { key ->
                            runCatching {
                                val vcard = android.net.Uri.withAppendedPath(
                                    android.provider.ContactsContract.Contacts.CONTENT_VCARD_URI, key,
                                )
                                context.startActivity(
                                    android.content.Intent.createChooser(
                                        android.content.Intent(android.content.Intent.ACTION_SEND)
                                            .setType("text/x-vcard")
                                            .putExtra(android.content.Intent.EXTRA_STREAM, vcard),
                                        shareChooserTitle,
                                    ),
                                )
                            }
                        }
                    },
                    AppBarAction(Icons.Filled.Delete, stringResource(R.string.contact_delete)) {
                        confirmDelete = true
                    },
                ),
                menu = buildList {
                    // WP "pin to Start": a live contact tile on the home screen.
                    d.lookupKey?.let { key ->
                        add(
                            pinTileLabel to {
                                val ok = com.fancyshark.wpdialer.widget.ContactTileWidget
                                    .requestPin(context, key, d.name)
                                if (!ok) MetroToast.show(pinUnsupported)
                            },
                        )
                    }
                    if (d.phones.isNotEmpty()) {
                        add(
                            pinShortcutLabel to {
                                scope.launch {
                                    val number = withContext(Dispatchers.IO) {
                                        com.fancyshark.wpdialer.widget.ContactTiles
                                            .phonesOf(context, d.id).firstOrNull()
                                    } ?: d.phones.first().number
                                    val ok = com.fancyshark.wpdialer.widget.Shortcuts
                                        .requestPinCall(context, d.name, number, d.photoUri)
                                    if (!ok) MetroToast.show(pinUnsupported)
                                }
                            },
                        )
                        add(resources.getString(R.string.acc_block) to { confirmBlock = true })
                    }
                },
            )
        }

        if (confirmDelete) {
            MetroConfirm(
                title = stringResource(R.string.contact_delete_confirm, d.name),
                message = null,
                confirmLabel = stringResource(R.string.contact_delete),
                cancelLabel = stringResource(R.string.contact_cancel),
                onConfirm = {
                    confirmDelete = false
                    scope.launch {
                        Repo.deleteContact(context, d.id)
                        onDeleted()
                    }
                },
                onCancel = { confirmDelete = false },
            )
        }

        if (confirmBlock) {
            val blockedMsg = stringResource(R.string.acc_blocked_toast, d.name)
            val blockFailed = stringResource(R.string.acc_block_failed)
            MetroConfirm(
                title = stringResource(R.string.acc_block_title, d.name),
                message = stringResource(R.string.acc_block_body),
                confirmLabel = stringResource(R.string.acc_block),
                cancelLabel = stringResource(R.string.contact_cancel),
                onConfirm = {
                    confirmBlock = false
                    scope.launch {
                        var ok = true
                        d.phones.forEach { if (!Repo.blockNumber(context, it.number)) ok = false }
                        MetroToast.show(if (ok) blockedMsg else blockFailed)
                    }
                },
                onCancel = { confirmBlock = false },
            )
        }

        confirmDeleteCall?.let { call ->
            MetroConfirm(
                title = stringResource(R.string.main_confirm_delete_call),
                message = Repo.pretty(context, call.item.number),
                confirmLabel = stringResource(R.string.main_button_delete),
                cancelLabel = stringResource(R.string.main_button_cancel),
                onConfirm = {
                    confirmDeleteCall = null
                    scope.launch {
                        Repo.deleteHistoryItem(context, call.item.id)
                        callsTick++
                    }
                },
                onCancel = { confirmDeleteCall = null },
            )
        }

        confirmRemoveSpeed?.let { number ->
            MetroConfirm(
                title = stringResource(R.string.acc_speed_remove_title),
                message = Repo.pretty(context, number),
                confirmLabel = stringResource(R.string.acc_remove),
                cancelLabel = stringResource(R.string.contact_cancel),
                onConfirm = {
                    confirmRemoveSpeed = null
                    AppPrefs.removeSpeedDial(context, number)
                },
                onCancel = { confirmRemoveSpeed = null },
            )
        }

        confirmDeleteRaw?.let { raw ->
            val label = rememberAccountLabel(raw.account)
            val line = accountLine(label, raw.account)
            val local = AccountStyle.kindOf(raw.account.type) == AccountKind.LOCAL
            val deletedMsg = stringResource(R.string.acc_deleted_toast, label)
            val failedMsg = stringResource(R.string.acc_delete_failed, label)
            MetroConfirm(
                title = stringResource(R.string.acc_delete_raw_title, label),
                message = if (local) {
                    stringResource(R.string.acc_delete_raw_body_local, shortName(d.name))
                } else {
                    stringResource(R.string.acc_delete_raw_body, shortName(d.name), line)
                },
                confirmLabel = stringResource(R.string.contact_delete),
                cancelLabel = stringResource(R.string.contact_cancel),
                onConfirm = {
                    confirmDeleteRaw = null
                    val all = sources?.raws.orEmpty()
                    val others = all.map { it.rawId }.filter { it != raw.rawId }
                    busy = workingLabel
                    scope.launch {
                        val oldKey = sources?.lookupKey
                        val ok = ContactAccounts.deleteRaw(context, raw)
                        val newId = withContext(Dispatchers.IO) {
                            others.firstNotNullOfOrNull { ContactAccounts.contactIdOfRaw(context, it) }
                        }
                        busy = null
                        MetroToast.show(if (ok) deletedMsg else failedMsg)
                        if (ok && newId == null) {
                            onDeleted()
                        } else if (ok) {
                            afterAccountChange(newId, oldKey)
                        }
                    }
                },
                onCancel = { confirmDeleteRaw = null },
            )
        }

        appChooser?.let { (app, actions) ->
            MetroDialog(onDismiss = { appChooser = null }) {
                Text(
                    app.uppercase(),
                    color = Metro.Foreground,
                    style = Metro.Type.Small,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                actions.forEach { action ->
                    Text(
                        shortActionLabel(action),
                        color = Metro.Foreground,
                        style = Metro.Type.Large,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                launchAppAction(context, action)
                                appChooser = null
                            }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }

        if (showSimChooser) {
            val accentText = Metro.textAccent(accent)
            MetroDialog(onDismiss = { showSimChooser = false }) {
                Text(
                    stringResource(R.string.contact_preferred_sim_caps),
                    color = Metro.Foreground,
                    style = Metro.Type.Small,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                (
                    listOf<Pair<String, String?>>(stringResource(R.string.acc_always_ask) to null) +
                        simOptions.map { it.label to it.flat }
                    ).forEach { (label, flat) ->
                    val selected = simPref == flat
                    Text(
                        label,
                        color = if (selected) accentText else Metro.Foreground,
                        style = Metro.Type.Large,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // The preference applies to the person:
                                // stored for every number they have.
                                d.phones.forEach { SimPrefs.set(context, it.number, flat) }
                                simPref = flat
                                showSimChooser = false
                            }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }

        fieldsOf?.let { raw ->
            RawFieldsOverlay(raw, sources?.groups.orEmpty(), d.name) { fieldsOf = null }
        }

        busy?.let { BusyOverlay(it) }
    }
}

/** Opens an app action row (WhatsApp call, Telegram chat...) in its app. */
private fun launchAppAction(context: android.content.Context, a: ContactAppAction) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(
                    android.content.ContentUris.withAppendedId(
                        android.provider.ContactsContract.Data.CONTENT_URI, a.dataId,
                    ),
                    a.mimetype,
                ),
        )
    }
}

/** The registering app's launcher label ("Telegram"), falling back to DATA2. */
private fun appDisplayName(context: android.content.Context, a: ContactAppAction): String =
    a.accountType?.let { pkg ->
        runCatching {
            context.packageManager
                .getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0))
                .toString()
        }.getOrNull()
    } ?: a.app

/** "Voice call +961 70 996 669" -> "voice call". */
private fun shortActionLabel(a: ContactAppAction): String =
    ProfileFacts.actionName(a.label) ?: a.label.lowercase()

/** A monochrome Metro glyph for a messaging app's tile. */
private fun appGlyph(pkg: String?, app: String): ImageVector {
    val k = ((pkg ?: "") + " " + app).lowercase()
    return when {
        k.contains("whatsapp") -> Icons.AutoMirrored.Filled.Chat
        k.contains("telegram") -> Icons.AutoMirrored.Filled.Send
        k.contains("signal") || k.contains("thoughtcrime") -> Icons.Filled.Forum
        k.contains("meet") || k.contains("duo") || k.contains("tachyon") || k.contains("video") ->
            Icons.Filled.Videocam
        k.contains("viber") || k.contains("skype") || k.contains("messenger") -> Icons.Filled.Forum
        else -> Icons.Filled.Apps
    }
}

private class ActionTileSpec(val key: String, val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** Square accent action tiles, 4 to a row (WP8.1 contact card). */
@Composable
private fun ActionTiles(tiles: List<ActionTileSpec>, accent: Color) {
    if (tiles.isEmpty()) return
    val tiltOn by AppPrefs.tilt.collectAsState()
    val fg = Metro.onAccent(accent)
    Column(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tiles.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { t ->
                    key(t.key) {
                        Box(
                            Modifier
                                .weight(1f)
                                .height(80.dp)
                                .metroTilt(tiltOn)
                                .background(accent)
                                .clickable(role = Role.Button) { t.onClick() }
                                .semantics { contentDescription = t.label },
                        ) {
                            Icon(
                                t.icon,
                                contentDescription = null,
                                tint = fg,
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp).size(26.dp),
                            )
                            Text(
                                t.label,
                                color = fg,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.BottomStart).padding(start = 7.dp, end = 4.dp, bottom = 5.dp),
                            )
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Big WP section header ("about", "saved in"). */
@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Metro.Subtle,
        style = Metro.Type.SubHeader,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 4.dp),
    )
}

/** Tappable two-line row: big light title, accent subtitle. */
@Composable
private fun ActionRow(
    title: String,
    subtitle: String?,
    subtitleColor: Color,
    onClick: (() -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Metro.Foreground, style = Metro.Type.Large, fontWeight = FontWeight.Light)
            if (subtitle != null) {
                Text(subtitle, color = subtitleColor, style = Metro.Type.Small)
            }
        }
        trailing?.invoke()
    }
}

/** "about" item: small subtle label over the value. */
@Composable
private fun FactItem(
    label: String,
    value: String,
    valueColor: Color = Metro.Foreground,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Metro.Subtle, style = Metro.Type.Small)
            Text(value, color = valueColor, style = Metro.Type.Body)
        }
        trailing?.invoke()
    }
}

@Composable
private fun TrailingIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Icon(
        icon,
        contentDescription = description,
        tint = Metro.Foreground,
        modifier = Modifier
            .size(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) { onClick() }
            .padding(10.dp),
    )
}

/** Locale-ordered "March 4". */
private fun monthDay(month: Int, day: Int): String {
    val locale = java.util.Locale.getDefault()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMMd")
    return java.text.SimpleDateFormat(pattern, locale)
        .format(java.util.GregorianCalendar(2000, month - 1, day).time)
}

@Composable
private fun upcomingTitle(u: UpcomingDate): String {
    val birthday = u.type == ProfileFacts.EVENT_BIRTHDAY
    return when (u.daysUntil) {
        0 -> stringResource(if (birthday) R.string.acc_birthday_today else R.string.acc_anniversary_today)
        1 -> stringResource(if (birthday) R.string.acc_birthday_tomorrow else R.string.acc_anniversary_tomorrow)
        else -> pluralStringResource(
            if (birthday) R.plurals.acc_birthday_in else R.plurals.acc_anniversary_in,
            u.daysUntil, u.daysUntil,
        )
    }
}

@Composable
private fun upcomingSubtitle(u: UpcomingDate): String {
    val date = monthDay(u.month, u.day)
    val years = u.years ?: return date
    val extra = if (u.type == ProfileFacts.EVENT_BIRTHDAY) {
        pluralStringResource(R.plurals.acc_turns, years, years)
    } else {
        pluralStringResource(R.plurals.acc_years_married, years, years)
    }
    return "$date · $extra"
}

/**
 * The long profile page. Its choosers and confirms are drawn by
 * [ContactDetailScreen] (full-screen) — this page only asks to open them.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ProfilePage(
    d: ContactDetail,
    sources: ContactSources?,
    accent: Color,
    simOptions: List<SimOption>,
    simPref: String?,
    calls: List<ContactCall>?,
    labelFor: (String) -> String?,
    onCall: (String) -> Unit,
    onText: (String) -> Unit,
    onAppChooser: (Pair<String, List<ContactAppAction>>) -> Unit,
    onSimChooser: () -> Unit,
    onRemoveSpeedDial: (String) -> Unit,
    onLink: () -> Unit,
    onMove: (RawSource, Boolean) -> Unit,
    onFields: (RawSource) -> Unit,
    onUnlink: (RawSource) -> Unit,
    onDeleteRaw: (RawSource) -> Unit,
    onSeeAllHistory: () -> Unit,
) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val accentText = Metro.textAccent(accent)
    val scroll = rememberScrollState()
    var savedInY by remember { mutableIntStateOf(0) }
    val raws = sources?.raws.orEmpty()
    val groups = sources?.groups.orEmpty()

    // Primary-first number for the call/text tiles.
    val primary by produceState<String?>(null, d) {
        value = withContext(Dispatchers.IO) {
            com.fancyshark.wpdialer.widget.ContactTiles.phonesOf(context, d.id).firstOrNull()
        }
    }
    val mainNumber = primary ?: d.phones.firstOrNull()?.number

    // One tile per messaging app (grouped by package: Telegram's per-action
    // summary names are one app, not three).
    val appGroups = remember(d) {
        d.appActions.groupBy { it.accountType ?: it.app }.values.toList()
    }
    val appNames by produceState(emptyMap<String, String>(), appGroups) {
        value = withContext(Dispatchers.IO) {
            appGroups.associate { list -> (list.first().accountType ?: list.first().app) to appDisplayName(context, list.first()) }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        // ---- header --------------------------------------------------
        Row(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            ContactTile(d.name, d.photoUri, accent, 150.dp)
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    d.name,
                    color = Metro.Foreground,
                    style = Metro.Type.SubHeader,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (d.nickname != null) {
                    Text(
                        stringResource(R.string.acc_nickname_quoted, d.nickname),
                        color = Metro.Subtle,
                        style = Metro.Type.Small,
                    )
                }
                val job = remember(raws) { ProfileFacts.jobLine(raws) }
                val jobText = job?.let { (title, company) ->
                    listOfNotNull(title, company).joinToString(" · ")
                } ?: d.organization
                if (!jobText.isNullOrBlank()) {
                    Text(jobText, color = Metro.Subtle, style = Metro.Type.Small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (raws.isNotEmpty()) {
            val first = raws.firstOrNull { !it.readOnly } ?: raws.first()
            val label = rememberAccountLabel(first.account)
            val line = accountLine(label, first.account)
            val prefix = stringResource(R.string.acc_saved_in)
            val more = if (raws.size > 1) stringResource(R.string.acc_more, raws.size - 1) else null
            Text(
                buildAnnotatedString {
                    append(prefix)
                    append(" ")
                    withStyle(SpanStyle(color = accentText)) { append(line) }
                    if (more != null) {
                        append(" ")
                        append(more)
                    }
                },
                color = Metro.Subtle,
                style = Metro.Type.Small,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { scope.launch { scroll.animateScrollTo(savedInY) } }
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        // ---- action tiles --------------------------------------------
        val tileCall = stringResource(R.string.acc_tile_call)
        val tileText = stringResource(R.string.acc_tile_text)
        val tileEmail = stringResource(R.string.acc_tile_email)
        val tiles = buildList {
            if (mainNumber != null) {
                add(ActionTileSpec("call", Icons.Filled.Call, tileCall) { onCall(mainNumber) })
                add(ActionTileSpec("text", Icons.AutoMirrored.Filled.Message, tileText) { onText(mainNumber) })
            }
            d.emails.firstOrNull()?.let { email ->
                add(ActionTileSpec("email", Icons.Filled.Email, tileEmail) { sendEmail(context, email.address) })
            }
            appGroups.forEach { list ->
                val groupKey = list.first().accountType ?: list.first().app
                val name = appNames[groupKey] ?: list.first().app
                add(
                    ActionTileSpec("app:$groupKey", appGlyph(list.first().accountType, name), name) {
                        if (list.size == 1) launchAppAction(context, list[0]) else onAppChooser(name to list)
                    },
                )
            }
        }
        ActionTiles(tiles, accent)

        // ---- numbers & emails ----------------------------------------
        Spacer(Modifier.height(14.dp))
        if (d.phones.isEmpty()) {
            Text(
                stringResource(R.string.contact_no_phone_numbers),
                color = Metro.Subtle,
                style = Metro.Type.Body,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
        d.phones.forEach { phone ->
            val sim = if (simOptions.size > 1) {
                SimPrefs.get(context, phone.number)?.let { flat -> simOptions.firstOrNull { it.flat == flat }?.label }
            } else {
                null
            }
            val pretty = Repo.pretty(context, phone.number)
            ActionRow(
                title = stringResource(R.string.contact_call_label, phone.label),
                subtitle = if (sim != null) "$pretty · $sim" else pretty,
                subtitleColor = accentText,
                onClick = { onCall(phone.number) },
                trailing = {
                    TrailingIcon(
                        Icons.AutoMirrored.Filled.Message,
                        stringResource(R.string.contact_text_label, phone.label),
                    ) { onText(phone.number) }
                },
            )
        }
        d.emails.forEach { email ->
            ActionRow(
                title = stringResource(R.string.contact_email_label, email.label),
                subtitle = email.address,
                subtitleColor = accentText,
                onClick = { sendEmail(context, email.address) },
            )
        }

        // ---- coming up -------------------------------------------------
        val upcoming = remember(raws) {
            ProfileFacts.upcoming(ProfileFacts.datedEvents(raws), java.time.LocalDate.now())
        }
        if (upcoming != null) {
            Text(
                stringResource(R.string.acc_coming_up),
                color = accentText,
                style = Metro.Type.Small,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 2.dp),
            )
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(upcomingTitle(upcoming), color = Metro.Foreground, style = Metro.Type.Large, fontWeight = FontWeight.Light)
                Text(upcomingSubtitle(upcoming), color = Metro.Subtle, style = Metro.Type.Small)
            }
        }

        // ---- about -----------------------------------------------------
        val birthdayLabel = stringResource(R.string.data_event_birthday)
        val anniversaryLabel = stringResource(R.string.data_event_anniversary)
        val featured = upcoming?.let { if (it.type == ProfileFacts.EVENT_BIRTHDAY) birthdayLabel else anniversaryLabel }
        val events = remember(d, featured) {
            // The coming-up date isn't repeated below.
            val i = d.events.indexOfFirst { it.label == featured }
            if (i >= 0) d.events.filterIndexed { idx, _ -> idx != i } else d.events
        }
        val allRows = remember(raws) { raws.flatMap { it.rows } }
        val addresses = remember(allRows) {
            allRows.filter { it.mimetype == Mime.POSTAL && it.data1 != null }
                .distinctBy { it.data1!!.lowercase().replace(Regex("\\s+"), " ").trim() }
        }
        val relations = remember(allRows) { allRows.filter { it.mimetype == Mime.RELATION && it.data1 != null }.distinctBy { it.data1!!.lowercase() } }
        val sips = remember(allRows) { allRows.filter { it.mimetype == Mime.SIP && it.data1 != null }.distinctBy { it.data1!!.lowercase() } }
        val ims = remember(allRows) { allRows.filter { it.mimetype == Mime.IM && it.data1 != null }.distinctBy { it.data1!!.lowercase() } }
        val phonetic = remember(raws) { ProfileFacts.phoneticName(raws) }
        val labels = remember(raws, groups) { ProfileFacts.userLabels(raws, groups) }
        val fallbackAddress = d.address.takeIf { addresses.isEmpty() && !it.isNullOrBlank() }
        val hasAbout = events.isNotEmpty() || addresses.isNotEmpty() || fallbackAddress != null ||
            d.websites.isNotEmpty() || relations.isNotEmpty() || sips.isNotEmpty() || ims.isNotEmpty() ||
            phonetic != null || labels.isNotEmpty() || !d.note.isNullOrBlank()
        if (hasAbout) {
            SectionHeader(stringResource(R.string.acc_about))
            Spacer(Modifier.height(4.dp))
            events.forEach { FactItem(it.label, it.date) }
            val mapsLabel = stringResource(R.string.acc_open_map)
            addresses.forEach { row ->
                val type = ContactsTypeLabels.postal(resources, row)
                FactItem(
                    stringResource(R.string.acc_address_label, type),
                    row.data1!!,
                    onClick = { openMap(context, row.data1!!) },
                    trailing = { TrailingIcon(Icons.Filled.Place, mapsLabel) { openMap(context, row.data1!!) } },
                )
            }
            fallbackAddress?.let { addr ->
                FactItem(
                    stringResource(R.string.contact_map_address),
                    addr,
                    onClick = { openMap(context, addr) },
                    trailing = { TrailingIcon(Icons.Filled.Place, mapsLabel) { openMap(context, addr) } },
                )
            }
            d.websites.forEach { site ->
                FactItem(stringResource(R.string.contact_website), site, valueColor = accentText, onClick = { openWebsite(context, site) })
            }
            relations.forEach { row ->
                FactItem(
                    stringResource(R.string.acc_relationship),
                    ContactsTypeLabels.relation(resources, row) + " · " + row.data1!!,
                )
            }
            sips.forEach { FactItem(stringResource(R.string.acc_internet_call), it.data1!!) }
            ims.forEach { row ->
                FactItem(
                    stringResource(R.string.acc_chat),
                    ContactsTypeLabels.imProtocol(resources, row)?.let { "$it · ${row.data1}" } ?: row.data1!!,
                )
            }
            phonetic?.let { FactItem(stringResource(R.string.acc_phonetic), it) }
            if (labels.isNotEmpty()) {
                FactItem(stringResource(R.string.acc_labels), labels.joinToString(stringResource(R.string.acc_list_sep)))
            }
            if (!d.note.isNullOrBlank()) FactItem(stringResource(R.string.acc_notes), d.note)
        }

        // ---- for this contact ------------------------------------------
        SectionHeader(stringResource(R.string.acc_for_contact))
        RingtoneRow(d.id, accentText)
        if (simOptions.size > 1) {
            ActionRow(
                title = stringResource(R.string.acc_call_with),
                subtitle = simOptions.firstOrNull { it.flat == simPref }?.label
                    ?: stringResource(R.string.acc_always_ask),
                subtitleColor = accentText,
                onClick = onSimChooser,
            )
        }
        if (d.phones.isNotEmpty()) {
            val speedDial by AppPrefs.speedDialNumbers.collectAsState()
            val index = speedDial.indexOfFirst { s -> d.phones.any { Repo.numbersMatch(it.number, s) } }
            val addedKey = stringResource(R.string.acc_speed_added_key, speedDial.size + 2)
            val addedPage = stringResource(R.string.acc_speed_added)
            ActionRow(
                title = stringResource(R.string.acc_speed_dial),
                subtitle = when {
                    index < 0 -> stringResource(R.string.contact_not_set)
                    index <= 7 -> stringResource(R.string.acc_speed_key, index + 2)
                    else -> stringResource(R.string.acc_speed_page)
                },
                subtitleColor = accentText,
                onClick = {
                    if (index >= 0) {
                        onRemoveSpeedDial(speedDial[index])
                    } else {
                        val number = mainNumber ?: d.phones.first().number
                        val willBeKey = speedDial.size <= 7
                        AppPrefs.addSpeedDial(context, number)
                        MetroToast.show(if (willBeKey) addedKey else addedPage)
                    }
                },
            )
        }

        // ---- saved in ---------------------------------------------------
        if (raws.isNotEmpty()) {
            SectionHeader(
                stringResource(R.string.acc_saved_in),
                Modifier.onGloballyPositioned { savedInY = it.positionInParent().y.toInt() },
            )
            raws.forEach { raw ->
                key(raw.rawId) {
                    SavedInRow(
                        raw = raw,
                        groups = groups,
                        contactName = d.name,
                        canUnlink = raws.size > 1,
                        onMove = { onMove(raw, false) },
                        onCopy = { onMove(raw, true) },
                        onFields = { onFields(raw) },
                        onUnlink = { onUnlink(raw) },
                        onDelete = { onDeleteRaw(raw) },
                    )
                }
            }
            Text(
                stringResource(R.string.acc_link_another),
                color = accentText,
                style = Metro.Type.Body,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onLink() }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        // ---- recent -----------------------------------------------------
        val recent = calls.orEmpty().take(3)
        if (recent.isNotEmpty()) {
            SectionHeader(stringResource(R.string.acc_recent))
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                recent.forEach { call ->
                    val item = call.item
                    val missed = item.type == android.provider.CallLog.Calls.MISSED_TYPE
                    Column(Modifier.fillMaxWidth().clickable { onCall(item.number) }) {
                        Text(
                            stringResource(
                                R.string.acc_recent_line,
                                Repo.historyTypeShort(context, item.type),
                                Repo.historyWhen(item.date),
                            ),
                            color = if (missed) accentText else Metro.Foreground,
                            style = Metro.Type.Body,
                        )
                        val label = labelFor(call.contactNumber)
                        val numberText = listOfNotNull(label, Repo.pretty(context, item.number)).joinToString(" ")
                        Text(
                            if (item.duration > 0) "$numberText · ${Repo.formatDuration(item.duration)}" else numberText,
                            color = Metro.Subtle,
                            style = Metro.Type.Small,
                        )
                    }
                }
                Text(
                    stringResource(R.string.acc_see_all),
                    color = accentText,
                    style = Metro.Type.Small,
                    modifier = Modifier
                        .clickable(role = Role.Button) { onSeeAllHistory() }
                        .padding(top = 4.dp, bottom = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun sendEmail(context: android.content.Context, address: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_SENDTO,
                android.net.Uri.parse("mailto:${android.net.Uri.encode(address)}"),
            ),
        )
    }
}

private fun openMap(context: android.content.Context, address: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("geo:0,0?q=${android.net.Uri.encode(address)}"),
            ),
        )
    }
}

private fun openWebsite(context: android.content.Context, site: String) {
    runCatching {
        val url = if (site.startsWith("http://") || site.startsWith("https://")) site else "https://$site"
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}

/** Platform-localized type labels for data rows (display only). */
private object ContactsTypeLabels {
    fun postal(res: android.content.res.Resources, row: DataRow): String =
        android.provider.ContactsContract.CommonDataKinds.StructuredPostal
            .getTypeLabel(res, row.int("data2") ?: 0, row.text("data3")).toString().lowercase()

    fun relation(res: android.content.res.Resources, row: DataRow): String =
        android.provider.ContactsContract.CommonDataKinds.Relation
            .getTypeLabel(res, row.int("data2") ?: 0, row.text("data3")).toString().lowercase()

    fun imProtocol(res: android.content.res.Resources, row: DataRow): String? {
        val protocol = row.int("data5") ?: return row.text("data6")
        return android.provider.ContactsContract.CommonDataKinds.Im
            .getProtocolLabel(res, protocol, row.text("data6")).toString()
    }

    fun phone(res: android.content.res.Resources, row: DataRow): String =
        android.provider.ContactsContract.CommonDataKinds.Phone
            .getTypeLabel(res, row.int("data2") ?: 0, row.text("data3")).toString().lowercase()

    fun email(res: android.content.res.Resources, row: DataRow): String =
        android.provider.ContactsContract.CommonDataKinds.Email
            .getTypeLabel(res, row.int("data2") ?: 0, row.text("data3")).toString().lowercase()
}

/** Ringtone row: Telecom honors CUSTOM_RINGTONE when this contact calls. */
@Composable
private fun RingtoneRow(contactId: Long, accentText: Color) {
    val context = LocalContext.current
    var ringtoneUri by remember(contactId) { mutableStateOf<String?>(null) }
    LaunchedEffect(contactId) { ringtoneUri = Repo.contactRingtone(context, contactId) }
    val defaultTitle = stringResource(R.string.contact_ringtone_default)
    // getRingtone/getTitle query the media provider — off the main thread.
    val ringtoneTitle by produceState(defaultTitle, ringtoneUri, defaultTitle) {
        value = ringtoneUri?.let { uri ->
            withContext(Dispatchers.IO) {
                runCatching {
                    android.media.RingtoneManager.getRingtone(context, android.net.Uri.parse(uri))?.getTitle(context)
                }.getOrNull()
            }
        } ?: defaultTitle
    }
    val scope = rememberCoroutineScope()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra(
                android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                android.net.Uri::class.java,
            )?.toString()
            ringtoneUri = uri
            scope.launch { Repo.setContactRingtone(context, contactId, uri) }
        }
    }
    val pickerTitle = stringResource(R.string.contact_ringtone)
    ActionRow(
        title = stringResource(R.string.contact_ringtone),
        subtitle = ringtoneTitle,
        subtitleColor = accentText,
        onClick = {
            runCatching {
                picker.launch(
                    android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                        .putExtra(
                            android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                            android.media.RingtoneManager.TYPE_RINGTONE,
                        )
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, pickerTitle)
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        // "Silent" returns a null URI — indistinguishable from
                        // Default (CUSTOM_RINGTONE null = default), so hide it;
                        // a null existing URI would also preselect Silent.
                        .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        .putExtra(
                            android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                            ringtoneUri?.let { android.net.Uri.parse(it) }
                                ?: android.provider.Settings.System.DEFAULT_RINGTONE_URI,
                        ),
                )
            }
        },
    )
}

/**
 * One "saved in" part: account square, kind label, account name and what
 * it holds. Tap or long-press opens the WP white context menu; read-only
 * parts only offer "unlink".
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun SavedInRow(
    raw: RawSource,
    groups: Map<Long, GroupInfo>,
    contactName: String,
    canUnlink: Boolean,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onFields: () -> Unit,
    onUnlink: () -> Unit,
    onDelete: () -> Unit,
) {
    val label = rememberAccountLabel(raw.account)
    val summary = remember(raw, groups) { ProfileFacts.summarize(raw, groups) }
    val holds = if (raw.readOnly) {
        listOf(stringResource(R.string.acc_read_only)) +
            (summary.appActions.takeIf { it.isNotEmpty() }?.let { listOf(it.joinToString(stringResource(R.string.acc_list_sep))) } ?: emptyList())
    } else {
        listOfNotNull(
            raw.account.name?.takeIf { AccountStyle.kindOf(raw.account.type) != AccountKind.LOCAL },
            summaryText(summary),
        )
    }
    val items = buildList<Pair<String, () -> Unit>> {
        if (!raw.readOnly) {
            add(stringResource(R.string.acc_menu_move) to onMove)
            add(stringResource(R.string.acc_menu_copy) to onCopy)
            add(stringResource(R.string.acc_menu_fields) to onFields)
        }
        if (canUnlink) add(stringResource(R.string.acc_menu_unlink, contactName) to onUnlink)
        if (!raw.readOnly) add(stringResource(R.string.acc_menu_delete) to onDelete)
    }
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    enabled = items.isNotEmpty(),
                    onClick = { menuOpen = true },
                    onLongClick = { menuOpen = true },
                )
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountSquare(raw.account, label)
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(label, color = Metro.Foreground, style = Metro.Type.Body)
                Text(
                    holds.joinToString(" · "),
                    color = Metro.Subtle,
                    style = Metro.Type.Small,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // WP-style white context menu (HomePages pattern).
        MaterialTheme(colorScheme = lightColorScheme()) {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                items.forEach { (text, action) ->
                    DropdownMenuItem(
                        text = {
                            Text(text, color = Color.Black, style = Metro.Type.Body, fontWeight = FontWeight.Light)
                        },
                        onClick = {
                            menuOpen = false
                            action()
                        },
                    )
                }
            }
        }
    }
}

/** "show only this account's fields": every row of one part, read-only view. */
@Composable
private fun RawFieldsOverlay(raw: RawSource, groups: Map<Long, GroupInfo>, contactName: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val label = rememberAccountLabel(raw.account)
    val fieldName = stringResource(R.string.acc_field_name)
    val fieldPhoto = stringResource(R.string.acc_field_photo)
    val fieldPhotoValue = stringResource(R.string.acc_field_photo_value)
    val fieldCompany = stringResource(R.string.contact_company)
    val fieldNotes = stringResource(R.string.acc_notes)
    val fieldAddress = stringResource(R.string.acc_field_address)
    val fieldWebsite = stringResource(R.string.contact_website)
    val fieldDate = stringResource(R.string.data_event_date)
    val fieldBirthday = stringResource(R.string.data_event_birthday)
    val fieldAnniversary = stringResource(R.string.data_event_anniversary)
    val fieldNick = stringResource(R.string.contact_nickname)
    val fieldChat = stringResource(R.string.acc_chat)
    val fieldSip = stringResource(R.string.acc_internet_call)
    val fieldRelation = stringResource(R.string.acc_relationship)
    val fieldLabel = stringResource(R.string.acc_field_label)
    val fields = remember(raw, groups) {
        raw.rows.mapNotNull { row ->
            val v = row.data1
            when (row.mimetype) {
                Mime.NAME -> v?.let { fieldName to it }
                Mime.PHONE -> v?.let { ContactsTypeLabels.phone(resources, row) to Repo.pretty(context, it) }
                Mime.EMAIL -> v?.let { ContactsTypeLabels.email(resources, row) to it }
                Mime.PHOTO -> fieldPhoto to fieldPhotoValue
                Mime.ORGANIZATION -> listOfNotNull(row.data1, row.text("data4")).joinToString(" · ")
                    .takeIf { it.isNotBlank() }?.let { fieldCompany to it }
                Mime.NOTE -> v?.let { fieldNotes to it }
                Mime.POSTAL -> v?.let { fieldAddress to it }
                Mime.WEBSITE -> v?.let { fieldWebsite to it }
                Mime.EVENT -> v?.let {
                    val type = when (row.int("data2")) {
                        ProfileFacts.EVENT_BIRTHDAY -> fieldBirthday
                        ProfileFacts.EVENT_ANNIVERSARY -> fieldAnniversary
                        else -> row.text("data3") ?: fieldDate
                    }
                    type to it
                }
                Mime.NICKNAME -> v?.let { fieldNick to it }
                Mime.IM -> v?.let { fieldChat to it }
                Mime.SIP -> v?.let { fieldSip to it }
                Mime.RELATION -> v?.let { fieldRelation to it }
                Mime.GROUP -> row.text("data1")?.toLongOrNull()?.let { groups[it] }
                    ?.takeIf { it.systemId == null }?.title?.let { fieldLabel to it }
                Mime.IDENTITY -> null
                else -> {
                    // App rows: app name over its action.
                    val action = row.text("data3") ?: return@mapNotNull null
                    (row.text("data2") ?: row.mimetype.substringAfterLast('/')) to action
                }
            }
        }
    }
    MetroOverlay(onBack = onClose) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                accountLine(label, raw.account).uppercase(),
                color = Metro.Foreground,
                style = Metro.Type.AppTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp),
            )
            Text(
                raw.displayName ?: contactName,
                color = Metro.Foreground,
                style = Metro.Type.PageTitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            if (raw.readOnly) {
                Text(
                    stringResource(R.string.acc_read_only),
                    color = Metro.Subtle,
                    style = Metro.Type.Small,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            if (fields.isEmpty()) {
                Text(
                    stringResource(R.string.acc_fields_none),
                    color = Metro.Subtle,
                    style = Metro.Type.Body,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            fields.forEach { (k, v) -> FactItem(k, v) }
            Spacer(Modifier.height(16.dp))
            com.fancyshark.wpdialer.ui.MetroButton(
                stringResource(R.string.acc_close),
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
            ) { onClose() }
            Spacer(Modifier.height(24.dp))
        }
    }
}
