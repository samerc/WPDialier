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
    val shareChooserTitle = stringResource(com.fancyshark.wpdialer.R.string.contact_share_chooser)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ContactDetailBody(d, accent, history, onCall, onText, Modifier.weight(1f))
            MetroAppBar(
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
            )
        }

        if (confirmDelete) {
            androidx.activity.compose.BackHandler { confirmDelete = false }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Metro.Background.copy(alpha = 0.96f))
                    .clickable { confirmDelete = false },
            ) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { /* consume so taps inside don't dismiss */ },
                ) {
                    Text(
                        stringResource(
                            com.fancyshark.wpdialer.R.string.contact_delete_confirm,
                            d.name,
                        ),
                        color = Metro.Foreground,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    Row(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement
                            .spacedBy(10.dp),
                    ) {
                        MetroButton(
                            stringResource(com.fancyshark.wpdialer.R.string.contact_delete),
                            fill = Metro.Red,
                            modifier = Modifier.weight(1f),
                        ) {
                            // Guard: a double-tap must not run delete +
                            // onDeleted (an extra back-pop) twice.
                            if (confirmDelete) {
                                confirmDelete = false
                                scope.launch {
                                    Repo.deleteContact(context, d.id)
                                    onDeleted()
                                }
                            }
                        }
                        MetroButton(
                            stringResource(com.fancyshark.wpdialer.R.string.contact_cancel),
                            modifier = Modifier.weight(1f),
                        ) {
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

/** The registering app's launcher icon, falling back to its initial. */
@Composable
private fun AppActionIcon(accountType: String?, app: String, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon = remember(accountType) {
        accountType?.let { pkg ->
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
        if (icon != null) {
            Image(icon, contentDescription = app, modifier = Modifier.fillMaxSize())
        } else {
            Text(app.take(1).uppercase(), color = Metro.Foreground, fontSize = 16.sp)
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

/** "Voice call +961 70 996 669" -> "voice call". */
private fun shortActionLabel(a: com.fancyshark.wpdialer.data.ContactAppAction): String {
    val stripped = a.label
        .replace(Regex("""\+?\d[\d\s()\-]{3,}"""), "")
        .trim()
        .trim(',')
        .trim()
    return stripped.ifEmpty { a.label }.lowercase()
}

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

    // Fold app actions into the phone/email rows they target; the rest stay
    // as standalone rows.
    var appChooser by remember { mutableStateOf<Pair<String, List<ContactAppAction>>?>(null) }
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

    fun launchAction(a: ContactAppAction) {
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

    @Composable
    fun AppIcons(actions: List<ContactAppAction>) {
        // Group by package: apps like Telegram use a different summary name
        // per row ("Telegram Voice Call", ...), which is one app, not three.
        actions.groupBy { it.accountType ?: it.app }.forEach { (_, list) ->
            val name = appDisplayName(context, list.first())
            AppActionIcon(list.first().accountType, name) {
                if (list.size == 1) launchAction(list[0]) else appChooser = name to list
            }
        }
    }

    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                stringResource(com.fancyshark.wpdialer.R.string.contact_profile),
                color = Metro.Foreground,
                fontSize = 48.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 18.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ContactTile(d.name, d.photoUri, accent, 96.dp)
                Text(
                    d.name,
                    color = Metro.Foreground,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
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
                            stringResource(
                                com.fancyshark.wpdialer.R.string.contact_call_label,
                                phone.label,
                            ),
                            color = Metro.Foreground,
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Light,
                        )
                        Text(Repo.pretty(context, phone.number), color = accent, fontSize = 15.sp)
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
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Light,
                        )
                        Text(email.address, color = accent, fontSize = 15.sp)
                    }
                    AppIcons(emailActions[email.address.lowercase()].orEmpty())
                }
            }

            d.events.forEach { event ->
                Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                    Text(
                        event.label,
                        color = Metro.Foreground,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text(event.date, color = accent, fontSize = 15.sp)
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
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text(site, color = accent, fontSize = 15.sp)
                }
            }

            if (d.organization != null) {
                Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                    Text(
                        stringResource(com.fancyshark.wpdialer.R.string.contact_company),
                        color = Metro.Foreground,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text(d.organization, color = accent, fontSize = 15.sp)
                }
            }

            if (d.nickname != null) {
                Column(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                    Text(
                        stringResource(com.fancyshark.wpdialer.R.string.contact_nickname),
                        color = Metro.Foreground,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text(d.nickname, color = accent, fontSize = 15.sp)
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
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Light,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(action.app, color = accent, fontSize = 15.sp)
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
                        stringResource(com.fancyshark.wpdialer.R.string.contact_preferred_sim),
                        color = Metro.Foreground,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Light,
                    )
                    Text(
                        simOptions.firstOrNull { it.flat == simPref }?.label
                            ?: stringResource(com.fancyshark.wpdialer.R.string.contact_not_set),
                        color = accent,
                        fontSize = 15.sp,
                    )
                }
            }

            var ringtoneUri by remember(d.id) { mutableStateOf<String?>(null) }
            androidx.compose.runtime.LaunchedEffect(d.id) {
                ringtoneUri = Repo.contactRingtone(context, d.id)
            }
            val ringtoneDefaultTitle =
                stringResource(com.fancyshark.wpdialer.R.string.contact_ringtone_default)
            val ringtoneTitle = remember(ringtoneUri, ringtoneDefaultTitle) {
                ringtoneUri?.let { uri ->
                    runCatching {
                        android.media.RingtoneManager
                            .getRingtone(context, android.net.Uri.parse(uri))
                            ?.getTitle(context)
                    }.getOrNull()
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
                                    .putExtra(
                                        android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                        ringtoneUri?.let { android.net.Uri.parse(it) },
                                    ),
                            )
                        }
                    }
                    .padding(vertical = 9.dp),
            ) {
                Text(
                    stringResource(com.fancyshark.wpdialer.R.string.contact_ringtone),
                    color = Metro.Foreground,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Light,
                )
                Text(ringtoneTitle, color = accent, fontSize = 15.sp)
            }

            if (!d.note.isNullOrBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(com.fancyshark.wpdialer.R.string.contact_note),
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
                    stringResource(com.fancyshark.wpdialer.R.string.contact_history),
                    color = Metro.Foreground,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Light,
                )
                personHistory.forEach { item ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(
                            Repo.historyTypeLabel(context, item.type),
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

        appChooser?.let { (app, actions) ->
            androidx.activity.compose.BackHandler { appChooser = null }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Metro.Background.copy(alpha = 0.96f))
                    .clickable { appChooser = null },
            ) {
                Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp)) {
                    Text(
                        app.uppercase(),
                        color = Metro.Foreground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                    actions.forEach { action ->
                        Text(
                            shortActionLabel(action),
                            color = Metro.Foreground,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    launchAction(action)
                                    appChooser = null
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }

        if (showSimChooser) {
            androidx.activity.compose.BackHandler { showSimChooser = false }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Metro.Background.copy(alpha = 0.96f))
                    .clickable { showSimChooser = false },
            ) {
                Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp)) {
                    Text(
                        stringResource(com.fancyshark.wpdialer.R.string.contact_preferred_sim_caps),
                        color = Metro.Foreground,
                        fontSize = 16.sp,
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
