package com.fancyshark.wpdialer.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.R
import com.fancyshark.wpdialer.data.ContactAccount
import com.fancyshark.wpdialer.data.ContactGroup
import com.fancyshark.wpdialer.data.EditName
import com.fancyshark.wpdialer.data.FieldKind
import com.fancyshark.wpdialer.data.Geo
import com.fancyshark.wpdialer.data.Repo
import com.fancyshark.wpdialer.ui.AppBarAction
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroAppBar
import com.fancyshark.wpdialer.ui.MetroButton
import com.fancyshark.wpdialer.ui.MetroCloseMark
import com.fancyshark.wpdialer.ui.MetroDialog
import com.fancyshark.wpdialer.ui.MetroOverlay
import com.fancyshark.wpdialer.ui.MetroTextBox
import kotlinx.coroutines.launch

// Sub-screens and pickers of the contact form. Every one is a MetroOverlay
// or MetroDialog, so it blocks touches beneath and owns back.

/** Small uppercase page caption ("EDIT CONTACT", "NAME"). */
@Composable
internal fun PageCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Metro.Foreground,
        style = Metro.Type.Small,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = modifier,
    )
}

/**
 * Full-page sub-screen: caption, optional big title, scrolling body and a
 * bottom app bar. Back runs [onBack].
 */
@Composable
private fun SubPage(
    caption: String,
    title: String?,
    onBack: () -> Unit,
    actions: List<AppBarAction>,
    body: @Composable ColumnScope.() -> Unit,
) {
    MetroOverlay(onBack = onBack) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                PageCaption(caption, Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp))
                if (title != null) {
                    Text(
                        title,
                        color = Metro.Foreground,
                        style = Metro.Type.Header,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                body()
                Spacer(Modifier.height(20.dp))
            }
            MetroAppBar(actions = actions)
        }
    }
}

/**
 * Name details: first/middle/last, title (prefix), suffix, nickname,
 * phonetic first/last. The draft only reaches the form on done (or back —
 * leaving never throws typing away; cancel does).
 */
@Composable
internal fun NameDetailsPage(
    initial: EditName,
    initialNickname: String,
    showNickname: Boolean,
    accent: Color,
    onDone: (EditName, String) -> Unit,
    onCancel: () -> Unit,
) {
    // A display-only name (parts unparsed) starts out in "first name".
    val seed = if (initial.copy(display = "").composed().isEmpty() && initial.display.isNotBlank()) {
        initial.copy(given = initial.display.trim())
    } else {
        initial
    }
    var prefix by rememberSaveable { mutableStateOf(seed.prefix) }
    var given by rememberSaveable { mutableStateOf(seed.given) }
    var middle by rememberSaveable { mutableStateOf(seed.middle) }
    var family by rememberSaveable { mutableStateOf(seed.family) }
    var suffix by rememberSaveable { mutableStateOf(seed.suffix) }
    var pGiven by rememberSaveable { mutableStateOf(seed.phoneticGiven) }
    var pFamily by rememberSaveable { mutableStateOf(seed.phoneticFamily) }
    var nickname by rememberSaveable { mutableStateOf(initialNickname) }
    fun draft() = EditName(prefix, given, middle, family, suffix, pGiven, pFamily)
    fun finish() {
        val d = draft()
        // Untouched draft: hand back the original so nothing is marked dirty.
        val name = if (d.sameParts(seed.copy(display = ""))) initial else d
        onDone(name, nickname)
    }
    val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words)

    SubPage(
        caption = stringResource(R.string.cform_name_caption),
        title = null,
        onBack = { finish() },
        actions = listOf(
            AppBarAction(Icons.Filled.Check, stringResource(R.string.cform_done)) { finish() },
            AppBarAction(Icons.Filled.Close, stringResource(R.string.editcontact_cancel)) { onCancel() },
        ),
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(4.dp))
            FieldLabel(stringResource(R.string.cform_first_name))
            MetroTextBox(given, { given = it }, accent, keyboardOptions = words)
            FieldLabel(stringResource(R.string.cform_middle_name), top = 10.dp)
            MetroTextBox(middle, { middle = it }, accent, keyboardOptions = words)
            FieldLabel(stringResource(R.string.cform_last_name), top = 10.dp)
            MetroTextBox(family, { family = it }, accent, keyboardOptions = words)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel(stringResource(R.string.cform_name_prefix), top = 10.dp)
                    MetroTextBox(prefix, { prefix = it }, accent, keyboardOptions = words)
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel(stringResource(R.string.cform_name_suffix), top = 10.dp)
                    MetroTextBox(suffix, { suffix = it }, accent, keyboardOptions = words)
                }
            }
            if (showNickname) {
                FieldLabel(stringResource(R.string.cform_nickname), top = 10.dp)
                MetroTextBox(nickname, { nickname = it }, accent, keyboardOptions = words)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    FieldLabel(stringResource(R.string.cform_phonetic_first), top = 10.dp)
                    MetroTextBox(pGiven, { pGiven = it }, accent, keyboardOptions = words)
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel(stringResource(R.string.cform_phonetic_last), top = 10.dp)
                    MetroTextBox(pFamily, { pFamily = it }, accent, keyboardOptions = words)
                }
            }
            val shown = draft().composed()
            HintLine(
                if (shown.isNotEmpty()) {
                    stringResource(R.string.cform_name_shown_as, shown)
                } else {
                    stringResource(R.string.cform_name_phonetic_help)
                },
                top = 12.dp,
            )
        }
    }
}

/** One "+ add another field" entry. */
internal data class AddFieldItem(
    val kind: FieldKind,
    val label: String,
    val subtitle: String?,
    val alreadyAdded: Boolean,
)

/** The field-kind list; already-present single fields are greyed out. */
@Composable
internal fun AddFieldPage(
    caption: String,
    items: List<AddFieldItem>,
    onPick: (FieldKind) -> Unit,
    onDismiss: () -> Unit,
) {
    SubPage(
        caption = caption,
        title = stringResource(R.string.cform_add_title),
        onBack = onDismiss,
        actions = listOf(
            AppBarAction(Icons.Filled.Close, stringResource(R.string.editcontact_cancel)) { onDismiss() },
        ),
    ) {
        Spacer(Modifier.height(10.dp))
        items.forEach { item ->
            val color = if (item.alreadyAdded) Metro.Dim else Metro.Foreground
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !item.alreadyAdded, role = Role.Button) { onPick(item.kind) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Text(item.label, color = color, style = Metro.Type.Large, fontWeight = FontWeight.Light)
                val sub = if (item.alreadyAdded) stringResource(R.string.cform_already_added) else item.subtitle
                if (sub != null) {
                    Text(
                        sub,
                        color = if (item.alreadyAdded) Metro.Dim else Metro.Subtle,
                        style = Metro.Type.Small,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.cform_add_footer),
            color = Metro.Subtle,
            style = Metro.Type.Small,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
        )
    }
}

/** One account's block in the labels picker. */
internal data class LabelSection(
    val rawIndex: Int,
    val account: ContactAccount,
    val groups: List<ContactGroup>,
    val checked: Set<Long>,
    val newTitles: List<String>,
)

/** Labels (contact groups) per account, as checkboxes, plus "+ new label". */
@Composable
internal fun LabelsPage(
    caption: String,
    sections: List<LabelSection>,
    showAccounts: Boolean,
    accent: Color,
    onToggle: (rawIndex: Int, groupId: Long) -> Unit,
    onRemoveNew: (rawIndex: Int, title: String) -> Unit,
    onNewLabel: () -> Unit,
    onDone: () -> Unit,
) {
    SubPage(
        caption = caption,
        title = stringResource(R.string.cform_labels),
        onBack = onDone,
        actions = listOf(
            AppBarAction(Icons.Filled.Check, stringResource(R.string.cform_done)) { onDone() },
        ),
    ) {
        sections.forEach { section ->
            if (showAccounts) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                ) {
                    FieldAccountSquare(section.account, size = 14.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        accountKindLabel(section.account.kindLabel) +
                            (section.account.name?.let { " · $it" } ?: ""),
                        color = Metro.Subtle,
                        style = Metro.Type.Small,
                    )
                }
            } else {
                Spacer(Modifier.height(10.dp))
            }
            if (section.groups.isEmpty() && section.newTitles.isEmpty()) {
                Text(
                    stringResource(R.string.cform_no_labels),
                    color = Metro.Subtle,
                    style = Metro.Type.Small,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            section.groups.forEach { group ->
                LabelRow(group.title, group.id in section.checked, accent) {
                    onToggle(section.rawIndex, group.id)
                }
            }
            section.newTitles.forEach { title ->
                LabelRow(title, true, accent) { onRemoveNew(section.rawIndex, title) }
            }
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            AddLink(stringResource(R.string.cform_new_label), accent) { onNewLabel() }
        }
    }
}

@Composable
private fun LabelRow(title: String, checked: Boolean, accent: Color, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox) { onToggle() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        MetroCheckbox(checked, accent)
        Spacer(Modifier.width(14.dp))
        Text(
            title,
            color = Metro.Foreground,
            style = Metro.Type.Large,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Name entry for a new label, created in [accountLabel]'s account on save. */
@Composable
internal fun NewLabelDialog(
    accountLabel: String?,
    accent: Color,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    MetroDialog(onDismiss = onDismiss) {
        Text(
            stringResource(R.string.cform_new_label),
            color = Metro.Foreground,
            style = Metro.Type.SubHeader,
        )
        if (accountLabel != null) {
            Text(
                stringResource(R.string.cform_new_label_in, accountLabel),
                color = Metro.Subtle,
                style = Metro.Type.Small,
                fontWeight = FontWeight.Light,
            )
        }
        Spacer(Modifier.height(12.dp))
        MetroTextBox(
            title,
            { title = it },
            accent,
            placeholder = stringResource(R.string.cform_label_name),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetroButton(
                stringResource(R.string.cform_create),
                fill = accent,
                enabled = title.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) { onCreate(title.trim()) }
            MetroButton(
                stringResource(R.string.editcontact_cancel),
                modifier = Modifier.weight(1f),
            ) { onDismiss() }
        }
    }
}

/** Which of the contact's accounts new fields go to. */
@Composable
internal fun TargetAccountDialog(
    options: List<Pair<Int, ContactAccount>>,
    selected: Int,
    accent: Color,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val accentText = Metro.textAccent(accent)
    MetroDialog(onDismiss = onDismiss) {
        Text(
            stringResource(R.string.cform_new_fields_to),
            color = Metro.Foreground,
            style = Metro.Type.SubHeader,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        options.forEach { (rawIndex, account) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onPick(rawIndex) }
                    .padding(vertical = 10.dp),
            ) {
                FieldAccountSquare(account, size = 22.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        accountKindLabel(account.kindLabel),
                        color = if (rawIndex == selected) accentText else Metro.Foreground,
                        style = Metro.Type.Large,
                    )
                    if (!account.name.isNullOrBlank()) {
                        Text(
                            account.name,
                            color = Metro.Subtle,
                            style = Metro.Type.Small,
                            fontWeight = FontWeight.Light,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** The app's own people list, searchable; picks a name for a relationship. */
@Composable
internal fun ContactPickPage(
    accent: Color,
    onPicked: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val contacts by produceState(emptyList<com.fancyshark.wpdialer.data.ContactItem>()) {
        value = Repo.loadContacts(context)
    }
    var query by rememberSaveable { mutableStateOf("") }
    FullScreenPicker(
        title = stringResource(R.string.cform_pick_contact_caps),
        onDismiss = onDismiss,
        header = {
            MetroTextBox(
                query,
                { query = it },
                accent,
                placeholder = stringResource(R.string.newcontact_search),
            )
            Spacer(Modifier.height(10.dp))
        },
    ) {
        val filtered = contacts.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        LazyColumn {
            items(filtered.size, key = { filtered[it].id }) { i ->
                Text(
                    filtered[i].name,
                    color = Metro.Foreground,
                    style = Metro.Type.Large,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPicked(filtered[i].name) }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

/** Full-screen Metro picker overlay; back and the close mark dismiss it. */
@Composable
internal fun FullScreenPicker(
    title: String,
    onDismiss: () -> Unit,
    header: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    MetroOverlay(onBack = onDismiss) {
        Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
            ) {
                PageCaption(title, Modifier.weight(1f))
                MetroCloseMark(stringResource(R.string.ui_close)) {
                    onDismiss()
                }
            }
            Column(Modifier.padding(end = 12.dp)) {
                header()
                content()
            }
        }
    }
}

/** WP-style select control: bordered box showing the current choice. */
@Composable
internal fun MetroSelector(
    primary: String,
    secondary: String?,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(2.dp, Metro.Foreground)
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                primary,
                color = Metro.Foreground,
                style = Metro.Type.Body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!secondary.isNullOrBlank()) {
                Text(
                    secondary,
                    color = Metro.Subtle,
                    style = Metro.Type.Small,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text("▾", color = Metro.textAccent(accent), style = Metro.Type.Body)
    }
}

/** Current-location lookup for an address field: [request] asks for permission first if needed. */
internal class LocationFetcher(val locating: Boolean, val request: () -> Unit)

@Composable
internal fun rememberLocationFetcher(onAddress: (String) -> Unit): LocationFetcher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(onAddress)
    fun fetch() {
        locating = true
        scope.launch {
            val loc = Geo.currentLocation(context)
            val addr = loc?.let { Geo.reverseGeocode(context, it.latitude, it.longitude) }
            if (addr != null) latest(addr)
            locating = false
        }
    }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants -> if (grants.values.any { it }) fetch() }
    return LocationFetcher(locating) {
        if (!locating) {
            val fine = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (fine) {
                fetch()
            } else {
                permission.launch(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
        }
    }
}

/**
 * OpenStreetMap pin picker: pan/zoom the map under a fixed center pin, then
 * confirm to reverse-geocode the pin position into an address line.
 */
@Composable
internal fun MapPinPicker(
    accent: Color,
    onCancel: () -> Unit,
    onPicked: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var resolving by remember { mutableStateOf(false) }
    val mapView = remember {
        org.osmdroid.config.Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = java.io.File(context.cacheDir, "osm")
            osmdroidTileCache = java.io.File(context.cacheDir, "osm/tiles")
        }
        org.osmdroid.views.MapView(context).apply {
            setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(org.osmdroid.util.GeoPoint(33.8938, 35.5018))
        }
    }
    LaunchedEffect(Unit) {
        Geo.currentLocation(context)?.let {
            mapView.controller.setCenter(org.osmdroid.util.GeoPoint(it.latitude, it.longitude))
            mapView.controller.setZoom(17.0)
        }
    }
    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    MetroOverlay(onBack = onCancel) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            ) {
                PageCaption(
                    stringResource(R.string.newcontact_pick_a_location),
                    Modifier.weight(1f),
                )
                MetroCloseMark(stringResource(R.string.ui_close)) {
                    onCancel()
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { mapView },
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    Icons.Filled.Place,
                    contentDescription = stringResource(R.string.newcontact_pin),
                    tint = accent,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(44.dp)
                        .padding(bottom = 22.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                MetroButton(
                    if (resolving) {
                        stringResource(R.string.newcontact_resolving)
                    } else {
                        stringResource(R.string.newcontact_use_this_location)
                    },
                    fill = accent,
                    enabled = !resolving,
                    modifier = Modifier.weight(1f),
                ) {
                    resolving = true
                    scope.launch {
                        val center = mapView.mapCenter
                        val addr = Geo.reverseGeocode(
                            context, center.latitude, center.longitude,
                        ) ?: String.format(
                            // Stored text: ASCII digits and "." decimals in any locale.
                            java.util.Locale.ROOT, "%.5f, %.5f", center.latitude, center.longitude,
                        )
                        onPicked(addr)
                    }
                }
                MetroButton(
                    stringResource(R.string.newcontact_cancel),
                    modifier = Modifier.weight(1f),
                ) { onCancel() }
            }
        }
    }
}
