package com.fancyshark.wpdialer.screens

import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.data.ContactAppAction
import com.fancyshark.wpdialer.data.ContactCall
import com.fancyshark.wpdialer.data.ContactDetail
import com.fancyshark.wpdialer.data.HistoryItem
import com.fancyshark.wpdialer.data.Repo
import com.fancyshark.wpdialer.data.SimOption
import com.fancyshark.wpdialer.data.SimPrefs
import com.fancyshark.wpdialer.data.Sims
import com.fancyshark.wpdialer.ui.AppBarAction
import com.fancyshark.wpdialer.ui.ContactTile
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroAppBar
import com.fancyshark.wpdialer.ui.MetroConfirm
import com.fancyshark.wpdialer.ui.MetroDialog
import com.fancyshark.wpdialer.ui.MetroToast
import com.fancyshark.wpdialer.ui.Pivot
import com.fancyshark.wpdialer.ui.hiddenFromAccessibility

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
    onReachDown: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val detail by produceState<ContactDetail?>(null, contactId, refreshKey) {
        value = Repo.loadContactDetail(context, contactId)
    }
    val d = detail ?: return
    var starred by remember(d.id, d.starred) { mutableStateOf(d.starred) }
    var confirmDelete by remember { mutableStateOf(false) }
    val shareChooserTitle = stringResource(com.fancyshark.wpdialer.R.string.contact_share_chooser)
    val pinTileLabel = stringResource(com.fancyshark.wpdialer.R.string.contact_pin_to_start)
    val pinShortcutLabel = stringResource(com.fancyshark.wpdialer.R.string.contact_add_call_shortcut)
    val pinUnsupported = stringResource(com.fancyshark.wpdialer.R.string.launcher_pin_unsupported)

    // Profile-page choosers, hoisted so their overlays cover the whole
    // screen (pivot headers included), not just the pager page.
    val simOptions = remember { Sims.options(context) }
    var simPref by remember(d.id) {
        mutableStateOf(d.phones.firstOrNull()?.let { SimPrefs.get(context, it.number) })
    }
    var showSimChooser by remember { mutableStateOf(false) }
    var appChooser by remember { mutableStateOf<Pair<String, List<ContactAppAction>>?>(null) }

    // Every call with this person across all their numbers, shared by the
    // history and notes pages. Null = still loading. Re-queried when the
    // home history reloads (a call landed / rows were deleted) and after a
    // delete here; earlier results stay up during a reload.
    val phoneNumbers = remember(d) { d.phones.map { it.number } }
    var callsTick by remember { mutableStateOf(0) }
    val calls by produceState<List<ContactCall>?>(null, phoneNumbers, refreshKey, history, callsTick) {
        value = Repo.callsWith(context, phoneNumbers)
    }
    var confirmDeleteCall by remember { mutableStateOf<ContactCall?>(null) }
    val labelFor: (String) -> String? = remember(d) {
        { number: String -> d.phones.firstOrNull { Repo.numbersMatch(it.number, number) }?.label }
    }

    // The choosers hide the app bar, as before the pivot, so edit/delete
    // never sit under a half-dimmed panel.
    val bodyOverlayOpen = appChooser != null || showSimChooser
    val anyOverlay = bodyOverlayOpen || confirmDelete || confirmDeleteCall != null
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().hiddenFromAccessibility(anyOverlay)) {
            // WP contact card: the person's name as the pivot title over
            // "profile | history | notes". Pivot's pager state is saveable,
            // so the selected page survives recreation.
            Pivot(
                title = d.name.uppercase(),
                modifier = Modifier.weight(1f),
                pages = listOf<Pair<String, @Composable () -> Unit>>(
                    stringResource(com.fancyshark.wpdialer.R.string.contact_profile) to {
                        ContactDetailBody(
                            d, accent, simOptions, simPref, onCall, onText,
                            modifier = Modifier.fillMaxSize(),
                            onAppChooser = { appChooser = it },
                            onSimChooser = { showSimChooser = true },
                        )
                    },
                    stringResource(com.fancyshark.wpdialer.R.string.contact_history) to {
                        ContactHistoryPage(
                            calls = calls,
                            accent = accent,
                            simOptions = simOptions,
                            labelFor = labelFor,
                            onCall = onCall,
                            onCopy = { number ->
                                // Android 13+ confirms clipboard writes itself —
                                // no toast of our own (minSdk 33).
                                runCatching {
                                    context.getSystemService(android.content.ClipboardManager::class.java)
                                        ?.setPrimaryClip(
                                            android.content.ClipData.newPlainText(d.name, number),
                                        )
                                }
                            },
                            onDelete = { confirmDeleteCall = it },
                        )
                    },
                    stringResource(com.fancyshark.wpdialer.R.string.contact_pivot_notes) to {
                        ContactNotesPage(calls = calls, accent = accent, labelFor = labelFor)
                    },
                ),
            )
            if (!bodyOverlayOpen) MetroAppBar(
                onSwipeDown = onReachDown,
                actions = listOf(
                    AppBarAction(
                        Icons.Filled.Edit,
                        stringResource(com.fancyshark.wpdialer.R.string.contact_edit),
                    ) { onEdit() },
                    AppBarAction(
                        if (starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                        if (starred) {
                            stringResource(com.fancyshark.wpdialer.R.string.contact_unpin)
                        } else {
                            stringResource(com.fancyshark.wpdialer.R.string.contact_pin)
                        },
                    ) {
                        starred = !starred
                        scope.launch { Repo.setStarred(context, d.id, starred) }
                    },
                    AppBarAction(
                        Icons.Filled.Share,
                        stringResource(com.fancyshark.wpdialer.R.string.contact_share),
                    ) {
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
                                        shareChooserTitle,
                                    ),
                                )
                            }
                        }
                    },
                    AppBarAction(
                        Icons.Filled.Delete,
                        stringResource(com.fancyshark.wpdialer.R.string.contact_delete),
                    ) { confirmDelete = true },
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
                                    // Primary number (the profile's list
                                    // isn't primary-ordered).
                                    val number = kotlinx.coroutines.withContext(
                                        kotlinx.coroutines.Dispatchers.IO,
                                    ) {
                                        com.fancyshark.wpdialer.widget.ContactTiles
                                            .phonesOf(context, d.id).firstOrNull()
                                    } ?: d.phones.first().number
                                    val ok = com.fancyshark.wpdialer.widget.Shortcuts
                                        .requestPinCall(context, d.name, number, d.photoUri)
                                    if (!ok) MetroToast.show(pinUnsupported)
                                }
                            },
                        )
                    }
                },
            )
        }

        if (confirmDelete) {
            MetroConfirm(
                title = stringResource(
                    com.fancyshark.wpdialer.R.string.contact_delete_confirm,
                    d.name,
                ),
                message = null,
                confirmLabel = stringResource(com.fancyshark.wpdialer.R.string.contact_delete),
                cancelLabel = stringResource(com.fancyshark.wpdialer.R.string.contact_cancel),
                // MetroConfirm fires once: a double-tap can't run delete +
                // onDeleted (an extra back-pop) twice.
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

        confirmDeleteCall?.let { call ->
            MetroConfirm(
                title = stringResource(com.fancyshark.wpdialer.R.string.main_confirm_delete_call),
                message = Repo.pretty(context, call.item.number),
                confirmLabel = stringResource(com.fancyshark.wpdialer.R.string.main_button_delete),
                cancelLabel = stringResource(com.fancyshark.wpdialer.R.string.main_button_cancel),
                onConfirm = {
                    confirmDeleteCall = null
                    scope.launch {
                        // Also drops the row's call note (Repo delete paths do).
                        Repo.deleteHistoryItem(context, call.item.id)
                        callsTick++
                    }
                },
                onCancel = { confirmDeleteCall = null },
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
                    stringResource(com.fancyshark.wpdialer.R.string.contact_preferred_sim_caps),
                    color = Metro.Foreground,
                    style = Metro.Type.Small,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
                (
                    listOf<Pair<String, String?>>(
                        stringResource(
                            com.fancyshark.wpdialer.R.string.contact_no_preference,
                        ) to null,
                    ) +
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
                                // store it for every number they have.
                                d.phones.forEach {
                                    SimPrefs.set(context, it.number, flat)
                                }
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

/** Opens an app action row (WhatsApp call, Telegram chat...) in its app. */
private fun launchAppAction(context: android.content.Context, a: ContactAppAction) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(
                    android.content.ContentUris.withAppendedId(
                        android.provider.ContactsContract.Data.CONTENT_URI,
                        a.dataId,
                    ),
                    a.mimetype,
                ),
        )
    }
}

// 7-significant-digit key via Repo — unifies national ("03 039 056") and
// E.164 ("+961 3 039 056") spellings of the same line.
private fun normalized(number: String): String =
    com.fancyshark.wpdialer.data.Repo.numberKey(number)

/** The registering app's launcher icon, falling back to its initial. */
@Composable
private fun AppActionIcon(accountType: String?, app: String, onClick: () -> Unit) {
    val context = LocalContext.current
    // PackageManager IPC + rasterizing: off the main thread.
    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, accountType) {
        value = accountType?.let { pkg ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val drawable = context.packageManager.getApplicationIcon(pkg)
                    val bmp = android.graphics.Bitmap.createBitmap(
                        96, 96, android.graphics.Bitmap.Config.ARGB_8888,
                    )
                    val canvas = android.graphics.Canvas(bmp)
                    drawable.setBounds(0, 0, 96, 96)
                    drawable.draw(canvas)
                    bmp.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    Box(
        Modifier
            .size(40.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(bitmap, contentDescription = app, modifier = Modifier.fillMaxSize())
        } else {
            Text(app.take(1).uppercase(), color = Metro.Foreground, style = Metro.Type.Small)
        }
    }
}

/** The registering app's launcher label ("Telegram"), falling back to DATA2. */
private fun appDisplayName(
    context: android.content.Context,
    a: com.fancyshark.wpdialer.data.ContactAppAction,
): String = a.accountType?.let { pkg ->
    runCatching {
        context.packageManager
            .getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0))
            .toString()
    }.getOrNull()
} ?: a.app

/** Digits key of an app action's target number, or null if it isn't one. */
private fun actionDigits(a: com.fancyshark.wpdialer.data.ContactAppAction): String? =
    com.fancyshark.wpdialer.data.Repo.actionNumberKey(a.label, a.data1)
        ?.let { com.fancyshark.wpdialer.data.Repo.numberKey(it) }

/** "Voice call +961 70 996 669" -> "voice call". */
private fun shortActionLabel(a: com.fancyshark.wpdialer.data.ContactAppAction): String {
    val stripped = a.label
        .replace(Regex("""\+?\d[\d\s()\-]{3,}"""), "")
        .trim()
        .trim(',')
        .trim()
    return stripped.ifEmpty { a.label }.lowercase()
}

/**
 * The pivot's "profile" page: tile + name, phones, emails, dates, app
 * actions, ringtone, preferred SIM, note. Its choosers are drawn by
 * [ContactDetailScreen] (full-screen) — this page only asks to open them.
 */
@Composable
private fun ContactDetailBody(
    d: ContactDetail,
    accent: Color,
    simOptions: List<SimOption>,
    simPref: String?,
    onCall: (String) -> Unit,
    onText: (String) -> Unit,
    onAppChooser: (Pair<String, List<ContactAppAction>>) -> Unit,
    onSimChooser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val contactNumbers = remember(d) { d.phones.map { normalized(it.number) }.filter { it.isNotEmpty() } }

    // Fold app actions into the phone/email rows they target; the rest stay
    // as standalone rows.
    val (phoneActions, emailActions, standaloneActions) = remember(d) {
        val byPhone = mutableMapOf<String, MutableList<ContactAppAction>>()
        val byEmail = mutableMapOf<String, MutableList<ContactAppAction>>()
        val rest = mutableListOf<ContactAppAction>()
        val phoneKeys = contactNumbers.toSet()
        val mailKeys = d.emails.map { it.address.lowercase() }.toSet()
        d.appActions.forEach { a ->
            val digits = actionDigits(a)
            val mail = a.data1?.lowercase()?.takeIf { it in mailKeys }
            when {
                digits != null && digits in phoneKeys ->
                    byPhone.getOrPut(digits) { mutableListOf() } += a
                mail != null -> byEmail.getOrPut(mail) { mutableListOf() } += a
                else -> rest += a
            }
        }
        Triple(byPhone, byEmail, rest)
    }

    fun launchAction(a: ContactAppAction) = launchAppAction(context, a)

    @Composable
    fun AppIcons(actions: List<ContactAppAction>) {
        // Group by package: apps like Telegram use a different summary name
        // per row ("Telegram Voice Call", ...), which is one app, not three.
        actions.groupBy { it.accountType ?: it.app }.forEach { (group, list) ->
            androidx.compose.runtime.key(group) {
                // Launcher label lookup is a PackageManager IPC — resolve it
                // once per package off the main thread, DATA2 until then.
                val name by produceState(list.first().app, list.first().accountType) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        appDisplayName(context, list.first())
                    }
                }
                AppActionIcon(list.first().accountType, name) {
                    if (list.size == 1) launchAction(list[0]) else onAppChooser(name to list)
                }
            }
        }
    }

    val accentText = Metro.textAccent(accent)
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // The "profile" title is now the pivot header above this page.
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ContactTile(d.name, d.photoUri, accent, 96.dp)
                Text(
                    d.name,
                    color = Metro.Foreground,
                    style = Metro.Type.SubHeader,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            Spacer(Modifier.height(22.dp))

            if (d.phones.isEmpty()) {
                Text(
                    stringResource(com.fancyshark.wpdialer.R.string.contact_no_phone_numbers),
                    color = Metro.Subtle,
                    style = Metro.Type.Body,
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
                            stringResource(
                                com.fancyshark.wpdialer.R.string.contact_call_label,
                                phone.label,
                            ),
                            color = Metro.Foreground,
                            style = Metro.Type.SubHeader,
                        )
                        Text(Repo.pretty(context, phone.number), color = accentText, style = Metro.Type.Small)
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.Message,
                        contentDescription = stringResource(
                            com.fancyshark.wpdialer.R.string.contact_text_label,
                            phone.label,
                        ),
                        tint = Metro.Foreground,
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onText(phone.number) }
                            .padding(8.dp),
                    )
                    AppIcons(phoneActions[normalized(phone.number)].orEmpty())
                }
            }

            d.emails.forEach { email ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_SENDTO,
                                        android.net.Uri.parse(
                                            "mailto:${android.net.Uri.encode(email.address)}",
                                        ),
                                    ),
                                )
                            }
                        }
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                com.fancyshark.wpdialer.R.string.contact_email_label,
                                email.label,
                            ),
                            color = Metro.Foreground,
                            style = Metro.Type.SubHeader,
                        )
                        Text(email.address, color = accentText, style = Metro.Type.Small)
                    }
                    AppIcons(emailActions[email.address.lowercase()].orEmpty())
                }
            }

            d.events.forEach { event ->
                Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                    Text(
                        event.label,
                        color = Metro.Foreground,
                        style = Metro.Type.SubHeader,
                    )
                    Text(event.date, color = accentText, style = Metro.Type.Small)
                }
            }

            d.websites.forEach { site ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                val url = if (site.startsWith("http://") ||
                                    site.startsWith("https://")
                                ) {
                                    site
                                } else {
                                    "https://$site"
                                }
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(url),
                                    ),
                                )
                            }
                        }
                        .padding(vertical = 9.dp),
                ) {
                    Text(
                        stringResource(com.fancyshark.wpdialer.R.string.contact_website),
                        color = Metro.Foreground,
                        style = Metro.Type.SubHeader,
                    )
                    Text(site, color = accentText, style = Metro.Type.Small)
                }
            }

            if (d.organization != null) {
                Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                    Text(
                        stringResource(com.fancyshark.wpdialer.R.string.contact_company),
                        color = Metro.Foreground,
                        style = Metro.Type.SubHeader,
                    )
                    Text(d.organization, color = accentText, style = Metro.Type.Small)
                }
            }

            if (d.nickname != null) {
                Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                    Text(
                        stringResource(com.fancyshark.wpdialer.R.string.contact_nickname),
                        color = Metro.Foreground,
                        style = Metro.Type.SubHeader,
                    )
                    Text(d.nickname, color = accentText, style = Metro.Type.Small)
                }
            }

            standaloneActions.forEach { action ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { launchAction(action) }
                        .padding(vertical = 9.dp),
                ) {
                    Text(
                        action.label,
                        color = Metro.Foreground,
                        style = Metro.Type.SubHeader,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(action.app, color = accentText, style = Metro.Type.Small)
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
                        stringResource(com.fancyshark.wpdialer.R.string.contact_map_address),
                        color = Metro.Foreground,
                        style = Metro.Type.SubHeader,
                    )
                    Text(d.address, color = accentText, style = Metro.Type.Small)
                }
            }

            if (simOptions.size > 1) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSimChooser() }
                        .padding(vertical = 9.dp),
                ) {
                    Text(
                        stringResource(com.fancyshark.wpdialer.R.string.contact_preferred_sim),
                        color = Metro.Foreground,
                        style = Metro.Type.SubHeader,
                    )
                    Text(
                        simOptions.firstOrNull { it.flat == simPref }?.label
                            ?: stringResource(com.fancyshark.wpdialer.R.string.contact_not_set),
                        color = accentText,
                        style = Metro.Type.Small,
                    )
                }
            }

            var ringtoneUri by remember(d.id) { mutableStateOf<String?>(null) }
            androidx.compose.runtime.LaunchedEffect(d.id) {
                ringtoneUri = Repo.contactRingtone(context, d.id)
            }
            val ringtoneDefaultTitle =
                stringResource(com.fancyshark.wpdialer.R.string.contact_ringtone_default)
            // getRingtone/getTitle query the media provider — off the main thread.
            val ringtoneTitle by produceState(ringtoneDefaultTitle, ringtoneUri, ringtoneDefaultTitle) {
                value = ringtoneUri?.let { uri ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            android.media.RingtoneManager
                                .getRingtone(context, android.net.Uri.parse(uri))
                                ?.getTitle(context)
                        }.getOrNull()
                    }
                } ?: ringtoneDefaultTitle
            }
            val ringtoneScope = androidx.compose.runtime.rememberCoroutineScope()
            val ringtonePicker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                if (result.resultCode == android.app.Activity.RESULT_OK) {
                    val uri = result.data?.getParcelableExtra(
                        android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                        android.net.Uri::class.java,
                    )?.toString()
                    ringtoneUri = uri
                    ringtoneScope.launch { Repo.setContactRingtone(context, d.id, uri) }
                }
            }
            val ringtonePickerTitle =
                stringResource(com.fancyshark.wpdialer.R.string.contact_ringtone)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching {
                            ringtonePicker.launch(
                                android.content.Intent(
                                    android.media.RingtoneManager.ACTION_RINGTONE_PICKER,
                                )
                                    .putExtra(
                                        android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                                        android.media.RingtoneManager.TYPE_RINGTONE,
                                    )
                                    .putExtra(
                                        android.media.RingtoneManager.EXTRA_RINGTONE_TITLE,
                                        ringtonePickerTitle,
                                    )
                                    .putExtra(
                                        android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT,
                                        true,
                                    )
                                    // "Silent" returns a null picked URI —
                                    // indistinguishable from picking Default
                                    // (CUSTOM_RINGTONE null = default), so
                                    // hide it; a null existing URI would also
                                    // wrongly preselect Silent, so fall back
                                    // to the default tone.
                                    .putExtra(
                                        android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT,
                                        false,
                                    )
                                    .putExtra(
                                        android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                        ringtoneUri?.let { android.net.Uri.parse(it) }
                                            ?: android.provider.Settings.System
                                                .DEFAULT_RINGTONE_URI,
                                    ),
                            )
                        }
                    }
                    .padding(vertical = 9.dp),
            ) {
                Text(
                    stringResource(com.fancyshark.wpdialer.R.string.contact_ringtone),
                    color = Metro.Foreground,
                    style = Metro.Type.SubHeader,
                )
                Text(ringtoneTitle, color = accentText, style = Metro.Type.Small)
            }

            if (!d.note.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(com.fancyshark.wpdialer.R.string.contact_note),
                    color = Metro.Foreground,
                    style = Metro.Type.SubHeader,
                )
                Text(
                    d.note,
                    color = Metro.Subtle,
                    style = Metro.Type.Small,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
