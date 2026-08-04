package com.fancyshark.wpdialer.screens

import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.data.ContactAccount
import com.fancyshark.wpdialer.data.ContactEditor
import com.fancyshark.wpdialer.data.Countries
import com.fancyshark.wpdialer.data.Region
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroButton
import com.fancyshark.wpdialer.ui.MetroTextBox
import java.io.ByteArrayOutputStream
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class TypedEntry(
    val value: String,
    val typeIdx: Int,
    val customLabel: String = "",
)

@Composable
fun NewContactScreen(
    accent: Color,
    initialNumber: String = "",
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by produceState(listOf(ContactAccount(null, null))) {
        value = ContactEditor.listAccounts(context)
    }
    var selectedAccount by remember { mutableStateOf<ContactAccount?>(null) }
    val chosen = selectedAccount ?: accounts.first()

    val regions = remember { Countries.all() }
    var regionCode by remember { mutableStateOf(Countries.defaultRegionCode(context)) }
    val region = regions.firstOrNull { it.code == regionCode }

    var name by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) photoUri = uri }
    val photoPreview by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, photoUri) {
        val uri = photoUri ?: return@produceState
        value = withContext(Dispatchers.IO) {
            decodeSampled(context, uri, 256)?.asImageBitmap()
        }
    }

    val phones = remember {
        mutableListOf(TypedEntry(initialNumber, 0)).toMutableStateList()
    }
    val emails = remember { mutableListOf<TypedEntry>().toMutableStateList() }
    val events = remember { mutableListOf<TypedEntry>().toMutableStateList() }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var showAccountPicker by remember { mutableStateOf(false) }
    var showCountryPicker by remember { mutableStateOf(false) }
    var showMapPicker by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) {
            locating = true
            scope.launch {
                val loc = com.fancyshark.wpdialer.data.Geo.currentLocation(context)
                val addr = loc?.let {
                    com.fancyshark.wpdialer.data.Geo.reverseGeocode(context, it.latitude, it.longitude)
                }
                if (addr != null) address = addr
                locating = false
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Text(
                stringResource(
                    com.fancyshark.wpdialer.R.string.newcontact_title,
                    accountKindLabel(chosen.kindLabel).uppercase(),
                ),
                color = Metro.Foreground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(12.dp))
                // Name large at the top, photo tile beside it.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetroTextBox(
                        name,
                        { name = it },
                        accent,
                        placeholder = stringResource(
                            com.fancyshark.wpdialer.R.string.newcontact_name,
                        ),
                        textSize = 23.sp,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .size(76.dp)
                            .background(accent)
                            .clickable { photoPicker.launch("image/*") },
                        contentAlignment = Alignment.Center,
                    ) {
                        val preview = photoPreview
                        if (preview != null) {
                            Image(
                                preview,
                                contentDescription = stringResource(
                                    com.fancyshark.wpdialer.R.string.newcontact_change_photo,
                                ),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(76.dp),
                            )
                        } else {
                            Text(
                                "+",
                                color = Color.White,
                                fontSize = 34.sp,
                                fontWeight = FontWeight.Light,
                            )
                        }
                    }
                }

                FieldLabel(stringResource(com.fancyshark.wpdialer.R.string.newcontact_save_to))
                MetroSelector(
                    primary = accountKindLabel(chosen.kindLabel),
                    secondary = chosen.name,
                    accent = accent,
                ) { showAccountPicker = true }

                FieldLabel(stringResource(com.fancyshark.wpdialer.R.string.newcontact_country))
                MetroSelector(
                    primary = region?.label ?: regionCode,
                    secondary = null,
                    accent = accent,
                ) { showCountryPicker = true }

                TypedSection(
                    title = stringResource(com.fancyshark.wpdialer.R.string.newcontact_phone),
                    entries = phones,
                    typeLabels = ContactEditor.PHONE_TYPES.map { it.first },
                    accent = accent,
                    keyboardType = KeyboardType.Phone,
                    placeholder = stringResource(
                        com.fancyshark.wpdialer.R.string.newcontact_number,
                    ),
                )
                TypedSection(
                    title = stringResource(com.fancyshark.wpdialer.R.string.newcontact_email),
                    entries = emails,
                    typeLabels = ContactEditor.EMAIL_TYPES.map { it.first },
                    accent = accent,
                    keyboardType = KeyboardType.Email,
                    placeholder = stringResource(
                        com.fancyshark.wpdialer.R.string.newcontact_email_address,
                    ),
                )

                events.forEachIndexed { i, entry ->
                    FieldLabel(stringResource(com.fancyshark.wpdialer.R.string.newcontact_date))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .weight(1f)
                                .border(2.dp, Metro.Foreground)
                                .clickable {
                                    val cal = Calendar.getInstance()
                                    DatePickerDialog(
                                        context,
                                        { _, y, m, day ->
                                            events[i] = entry.copy(
                                                value = "%04d-%02d-%02d".format(y, m + 1, day),
                                            )
                                        },
                                        cal.get(Calendar.YEAR),
                                        cal.get(Calendar.MONTH),
                                        cal.get(Calendar.DAY_OF_MONTH),
                                    ).show()
                                }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                        ) {
                            Text(
                                entry.value.ifEmpty {
                                    stringResource(
                                        com.fancyshark.wpdialer.R.string.newcontact_pick_a_date,
                                    )
                                },
                                color = if (entry.value.isEmpty()) Metro.Subtle else Metro.Foreground,
                                fontSize = 18.sp,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        WpDropdown(
                            current = typeKeyLabel(ContactEditor.EVENT_TYPES[entry.typeIdx].first),
                            options = ContactEditor.EVENT_TYPES.map { typeKeyLabel(it.first) },
                            onSelect = { t -> events[i] = entry.copy(typeIdx = t) },
                            modifier = Modifier.width(130.dp),
                        )
                        RemoveMark { events.removeAt(i) }
                    }
                    if (ContactEditor.EVENT_TYPES[entry.typeIdx].first == "custom") {
                        Spacer(Modifier.height(6.dp))
                        MetroTextBox(
                            entry.customLabel,
                            { events[i] = entry.copy(customLabel = it) },
                            accent,
                            placeholder = stringResource(
                                com.fancyshark.wpdialer.R.string.newcontact_custom_label,
                            ),
                        )
                    }
                }
                AddLink(
                    stringResource(com.fancyshark.wpdialer.R.string.newcontact_add_date),
                    accent,
                ) { events.add(TypedEntry("", 0)) }

                FieldLabel(stringResource(com.fancyshark.wpdialer.R.string.newcontact_address))
                MetroTextBox(
                    address,
                    { address = it },
                    accent,
                    placeholder = stringResource(
                        com.fancyshark.wpdialer.R.string.newcontact_street_city_country,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    AddLink(
                        stringResource(com.fancyshark.wpdialer.R.string.newcontact_pick_on_map),
                        accent,
                    ) { showMapPicker = true }
                    AddLink(
                        if (locating) {
                            stringResource(com.fancyshark.wpdialer.R.string.newcontact_locating)
                        } else {
                            stringResource(
                                com.fancyshark.wpdialer.R.string.newcontact_use_current_location,
                            )
                        },
                        accent,
                    ) {
                        if (!locating) {
                            val fine = androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.ACCESS_FINE_LOCATION,
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (fine) {
                                locating = true
                                scope.launch {
                                    val loc = com.fancyshark.wpdialer.data.Geo.currentLocation(context)
                                    val addr = loc?.let {
                                        com.fancyshark.wpdialer.data.Geo.reverseGeocode(
                                            context, it.latitude, it.longitude,
                                        )
                                    }
                                    if (addr != null) address = addr
                                    locating = false
                                }
                            } else {
                                locationPermission.launch(
                                    arrayOf(
                                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                    ),
                                )
                            }
                        }
                    }
                }

                FieldLabel(stringResource(com.fancyshark.wpdialer.R.string.newcontact_note))
                MetroTextBox(
                    note,
                    { note = it },
                    accent,
                    placeholder = stringResource(
                        com.fancyshark.wpdialer.R.string.newcontact_note,
                    ),
                    singleLine = false,
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                )

                Spacer(Modifier.height(20.dp))
            }
            if (saveFailed) {
                Text(
                    stringResource(com.fancyshark.wpdialer.R.string.newcontact_save_failed),
                    color = Metro.Red,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(vertical = 14.dp),
            ) {
                MetroButton(
                    if (saving) {
                        stringResource(com.fancyshark.wpdialer.R.string.newcontact_saving)
                    } else {
                        stringResource(com.fancyshark.wpdialer.R.string.newcontact_save)
                    },
                    fill = accent,
                    enabled = !saving &&
                        (name.isNotBlank() || phones.any { it.value.isNotBlank() }),
                    modifier = Modifier.weight(1f),
                ) {
                    saving = true
                    saveFailed = false
                    scope.launch {
                        val photoBytes = photoUri?.let { loadPhotoBytes(context, it) }
                        val ok = ContactEditor.create(
                            context,
                            chosen,
                            name.trim(),
                            phones = phones.map {
                                ContactEditor.NewPhone(
                                    Countries.toInternational(it.value.trim(), regionCode),
                                    ContactEditor.PHONE_TYPES[it.typeIdx].second,
                                    it.customLabel.ifBlank { null },
                                )
                            },
                            emails = emails.map {
                                ContactEditor.NewEmail(
                                    it.value.trim(),
                                    ContactEditor.EMAIL_TYPES[it.typeIdx].second,
                                    it.customLabel.ifBlank { null },
                                )
                            },
                            events = events.map {
                                ContactEditor.NewEvent(
                                    it.value,
                                    ContactEditor.EVENT_TYPES[it.typeIdx].second,
                                    it.customLabel.ifBlank { null },
                                )
                            },
                            photo = photoBytes,
                            note = note.trim().ifBlank { null },
                            address = address.trim().ifBlank { null },
                        )
                        if (ok) {
                            onDone()
                        } else {
                            saving = false
                            saveFailed = true
                        }
                    }
                }
                MetroButton(
                    stringResource(com.fancyshark.wpdialer.R.string.newcontact_cancel),
                    modifier = Modifier.weight(1f),
                ) { onDone() }
            }
        }

        if (showAccountPicker) {
            BackHandler { showAccountPicker = false }
            FullScreenPicker(
                title = stringResource(com.fancyshark.wpdialer.R.string.newcontact_save_to_caps),
                onDismiss = { showAccountPicker = false },
            ) {
                accounts.forEach { account ->
                    val selected = account == chosen
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedAccount = account
                                showAccountPicker = false
                            }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            accountKindLabel(account.kindLabel),
                            color = if (selected) accent else Metro.Foreground,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Light,
                        )
                        if (!account.name.isNullOrBlank()) {
                            Text(
                                account.name,
                                color = Metro.Subtle,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Light,
                            )
                        }
                    }
                }
            }
        }

        if (showMapPicker) {
            BackHandler { showMapPicker = false }
            MapPinPicker(
                accent = accent,
                onCancel = { showMapPicker = false },
                onPicked = { addr ->
                    address = addr
                    showMapPicker = false
                },
            )
        }

        if (showCountryPicker) {
            BackHandler { showCountryPicker = false }
            var query by remember { mutableStateOf("") }
            FullScreenPicker(
                title = stringResource(com.fancyshark.wpdialer.R.string.newcontact_country_caps),
                onDismiss = { showCountryPicker = false },
                header = {
                    MetroTextBox(
                        query,
                        { query = it },
                        accent,
                        placeholder = stringResource(
                            com.fancyshark.wpdialer.R.string.newcontact_search,
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                },
            ) {
                val filtered = regions.filter {
                    query.isBlank() || it.name.contains(query, ignoreCase = true)
                }
                LazyColumn {
                    items(filtered.size, key = { filtered[it].code }) { i ->
                        val r = filtered[i]
                        Text(
                            r.label,
                            color = if (r.code == regionCode) accent else Metro.Foreground,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    regionCode = r.code
                                    showCountryPicker = false
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * OpenStreetMap pin picker: pan/zoom the map under a fixed center pin, then
 * confirm to reverse-geocode the pin position into an address line.
 */
@Composable
private fun MapPinPicker(
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
        com.fancyshark.wpdialer.data.Geo.currentLocation(context)?.let {
            mapView.controller.setCenter(org.osmdroid.util.GeoPoint(it.latitude, it.longitude))
            mapView.controller.setZoom(17.0)
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    Column(Modifier.fillMaxSize().background(Metro.Background)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                stringResource(com.fancyshark.wpdialer.R.string.newcontact_pick_a_location),
                color = Metro.Foreground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "✕",
                color = Metro.Subtle,
                fontSize = 20.sp,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onCancel() }
                    .padding(6.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize(),
            )
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Filled.Place,
                contentDescription = stringResource(
                    com.fancyshark.wpdialer.R.string.newcontact_pin,
                ),
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
                    stringResource(com.fancyshark.wpdialer.R.string.newcontact_resolving)
                } else {
                    stringResource(com.fancyshark.wpdialer.R.string.newcontact_use_this_location)
                },
                fill = accent,
                enabled = !resolving,
                modifier = Modifier.weight(1f),
            ) {
                resolving = true
                scope.launch {
                    val center = mapView.mapCenter
                    val addr = com.fancyshark.wpdialer.data.Geo.reverseGeocode(
                        context, center.latitude, center.longitude,
                    ) ?: "%.5f, %.5f".format(center.latitude, center.longitude)
                    onPicked(addr)
                }
            }
            MetroButton(
                stringResource(com.fancyshark.wpdialer.R.string.newcontact_cancel),
                modifier = Modifier.weight(1f),
            ) { onCancel() }
        }
    }
}

/** Full-screen Metro picker overlay. */
@Composable
private fun FullScreenPicker(
    title: String,
    onDismiss: () -> Unit,
    header: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Metro.Background)
            .padding(horizontal = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 18.dp, bottom = 14.dp),
        ) {
            Text(
                title,
                color = Metro.Foreground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                "✕",
                color = Metro.Subtle,
                fontSize = 20.sp,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onDismiss() }
                    .padding(6.dp),
            )
        }
        header()
        content()
    }
}

/** WP-style select control: bordered box showing the current choice. */
@Composable
private fun MetroSelector(
    primary: String,
    secondary: String?,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(2.dp, Metro.Foreground)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                primary,
                color = Metro.Foreground,
                fontSize = 19.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!secondary.isNullOrBlank()) {
                Text(
                    secondary,
                    color = Metro.Subtle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text("▾", color = accent, fontSize = 18.sp)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text, color = Metro.Subtle, fontSize = 14.sp)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun AddLink(text: String, accent: Color, onClick: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Text(
        "+ $text",
        color = accent,
        fontSize = 17.sp,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun RemoveMark(onClick: () -> Unit) {
    Text(
        "✕",
        color = Metro.Subtle,
        fontSize = 20.sp,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(start = 10.dp, end = 2.dp),
    )
}

@Composable
private fun TypedSection(
    title: String,
    entries: androidx.compose.runtime.snapshots.SnapshotStateList<TypedEntry>,
    typeLabels: List<String>,
    accent: Color,
    keyboardType: KeyboardType,
    placeholder: String,
) {
    entries.forEachIndexed { i, entry ->
        FieldLabel(title)
        Row(verticalAlignment = Alignment.CenterVertically) {
            MetroTextBox(
                entry.value,
                { entries[i] = entry.copy(value = it) },
                accent,
                placeholder = placeholder,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            WpDropdown(
                current = typeKeyLabel(typeLabels[entry.typeIdx]),
                options = typeLabels.map { typeKeyLabel(it) },
                onSelect = { t -> entries[i] = entry.copy(typeIdx = t) },
                modifier = Modifier.width(110.dp),
            )
            RemoveMark { entries.removeAt(i) }
        }
        if (typeLabels[entry.typeIdx] == "custom") {
            Spacer(Modifier.height(6.dp))
            MetroTextBox(
                entry.customLabel,
                { entries[i] = entry.copy(customLabel = it) },
                accent,
                placeholder = stringResource(
                    com.fancyshark.wpdialer.R.string.newcontact_custom_label,
                ),
            )
        }
    }
    AddLink(
        stringResource(com.fancyshark.wpdialer.R.string.newcontact_add_typed, title.lowercase()),
        accent,
    ) { entries.add(TypedEntry("", 0)) }
}

/** WP-style dropdown: white select box that opens a menu of options. */
@Composable
fun WpDropdown(
    current: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .border(2.dp, Metro.Foreground)
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                current,
                color = Metro.Foreground,
                fontSize = 16.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text("▾", color = Metro.Foreground, fontSize = 14.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = { Text(option, fontSize = 16.sp) },
                    onClick = {
                        expanded = false
                        onSelect(i)
                    },
                )
            }
        }
    }
}

/**
 * Decodes an image bounded to roughly [maxSide] px using inSampleSize, so an
 * arbitrarily large gallery image can't exhaust memory.
 */
private fun decodeSampled(context: android.content.Context, uri: Uri, maxSide: Int): Bitmap? =
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()

private suspend fun loadPhotoBytes(context: android.content.Context, uri: Uri): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching {
            val source = decodeSampled(context, uri, 720) ?: return@withContext null
            val maxSide = 720
            val scale = minOf(1f, maxSide.toFloat() / maxOf(source.width, source.height))
            val bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else source
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }.getOrNull()
    }
