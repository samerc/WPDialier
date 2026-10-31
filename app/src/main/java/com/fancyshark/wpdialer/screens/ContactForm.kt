package com.fancyshark.wpdialer.screens

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.SipAddress
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fancyshark.wpdialer.R
import com.fancyshark.wpdialer.data.ContactAccount
import com.fancyshark.wpdialer.data.ContactEditor
import com.fancyshark.wpdialer.data.ContactGroup
import com.fancyshark.wpdialer.data.EditAddress
import com.fancyshark.wpdialer.data.EditEmail
import com.fancyshark.wpdialer.data.EditEvent
import com.fancyshark.wpdialer.data.EditIm
import com.fancyshark.wpdialer.data.EditPhone
import com.fancyshark.wpdialer.data.EditRawContact
import com.fancyshark.wpdialer.data.EditRelation
import com.fancyshark.wpdialer.data.EditValue
import com.fancyshark.wpdialer.data.EditableContact
import com.fancyshark.wpdialer.data.FieldKind
import com.fancyshark.wpdialer.data.defaultPhone
import com.fancyshark.wpdialer.data.preferredTarget
import com.fancyshark.wpdialer.data.withContactWide
import com.fancyshark.wpdialer.data.withDefaultPhone
import com.fancyshark.wpdialer.ui.AppBarAction
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroAppBar
import com.fancyshark.wpdialer.ui.MetroConfirm
import com.fancyshark.wpdialer.ui.MetroTextBox
import com.fancyshark.wpdialer.ui.hiddenFromAccessibility
import kotlinx.coroutines.launch

private fun <T> List<T>.replaced(index: Int, transform: (T) -> T): List<T> =
    mapIndexed { i, x -> if (i == index) transform(x) else x }

private fun <T> List<T>.without(index: Int): List<T> = filterIndexed { i, _ -> i != index }

/** Comma-joined set of revealed single-field kinds ("work", "notes", "labels"). */
private fun String.revealedSet(): Set<String> = split(',').filter { it.isNotEmpty() }.toSet()

/**
 * The one long contact form shared by new and edit: photo + name (opening
 * the name details page), work, phone, email, address, dates, more
 * (website, relationship, internet call, chat, labels, notes) and
 * "+ add another field". Every row is tagged with its raw contact, so each
 * field saves to its own account; on multi-account contacts a color square
 * per field shows which. New fields go to the chosen target raw contact.
 *
 * [update] must apply its transform to the LIVE form state (two edits can
 * land before the next recomposition). [header] sits under the name block
 * (the new-contact screen's account and country selectors); [extraOverlays]
 * draw over everything, with [extraOverlayOpen] hiding the form from
 * TalkBack while one is up.
 */
@Composable
internal fun ContactFormScaffold(
    caption: String,
    accent: Color,
    form: EditableContact,
    update: ((EditableContact) -> EditableContact) -> Unit,
    dirty: Boolean,
    saving: Boolean,
    saveFailedText: String?,
    onSave: () -> Unit,
    onDone: () -> Unit,
    header: @Composable () -> Unit = {},
    extraOverlayOpen: Boolean = false,
    extraOverlays: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val accentText = Metro.textAccent(accent)
    val raws = form.raws
    if (raws.isEmpty()) return
    val accounts = raws.map { it.account }.distinct()
    val multi = accounts.size >= 2

    var target by rememberSaveable { mutableIntStateOf(form.preferredTarget()) }
    val targetIndex = target.coerceIn(raws.indices)
    val targetRaw = raws[targetIndex]
    val targetKinds = ContactEditor.supportedKinds(targetRaw.account)
    var revealedRaw by rememberSaveable { mutableStateOf("") }
    val revealed = revealedRaw.revealedSet()
    fun reveal(kind: String) {
        revealedRaw = (revealedRaw.revealedSet() + kind).joinToString(",")
    }

    var showName by rememberSaveable { mutableStateOf(false) }
    var showAddField by rememberSaveable { mutableStateOf(false) }
    var showLabels by rememberSaveable { mutableStateOf(false) }
    var showNewLabel by rememberSaveable { mutableStateOf(false) }
    var showTarget by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var relationRaw by rememberSaveable { mutableIntStateOf(-1) }
    var relationIdx by rememberSaveable { mutableIntStateOf(-1) }
    var mapRaw by rememberSaveable { mutableIntStateOf(-1) }
    var mapIdx by rememberSaveable { mutableIntStateOf(-1) }
    var locateRaw by rememberSaveable { mutableIntStateOf(-1) }
    var locateIdx by rememberSaveable { mutableIntStateOf(-1) }

    fun requestClose() {
        if (dirty) confirmDiscard = true else onDone()
    }
    // Registered before the overlays' handlers, so an open overlay closes first.
    BackHandler(enabled = dirty) { confirmDiscard = true }

    fun updateRaw(index: Int, transform: (EditRawContact) -> EditRawContact) = update { c ->
        c.copy(raws = c.raws.replaced(index, transform))
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = loadPhotoBytes(context, uri) ?: return@launch
                update { c ->
                    c.withContactWide(target, { it.hasPhoto }) { it.copy(newPhoto = bytes) }
                }
            }
        }
    }
    val locator = rememberLocationFetcher { addr ->
        val ri = locateRaw
        val ai = locateIdx
        if (ri >= 0) {
            updateRaw(ri) { r -> r.copy(addresses = r.addresses.replaced(ai) { it.copy(formatted = addr) }) }
        }
    }

    // Labels of every account on the contact; reloaded when the set changes.
    val groupsByAccount by produceState(emptyMap<ContactAccount, List<ContactGroup>>(), accounts) {
        value = accounts.associateWith { ContactEditor.listGroups(context, it) }
    }

    // Contact-level values come from the target raw contact when it has them.
    val nameRaw = targetRaw.takeIf { !it.name.isBlank } ?: raws.firstOrNull { !it.name.isBlank } ?: targetRaw
    val shownName = nameRaw.name.shown
    val nickname = (targetRaw.takeIf { it.nickname.isNotBlank() } ?: raws.firstOrNull { it.nickname.isNotBlank() })
        ?.nickname.orEmpty()
    val photoBytes = raws.firstNotNullOfOrNull { it.newPhoto } ?: raws.firstNotNullOfOrNull { it.photo }
    // Decoded off the main thread — a full-size photo row would stall composition.
    val preview by produceState<ImageBitmap?>(null, photoBytes) { value = decodePhotoPreview(photoBytes) }

    val workRaws = raws.indices.filter { raws[it].hasOrg }
        .ifEmpty { if ("work" in revealed) listOf(targetIndex) else emptyList() }
    val noteRaws = raws.indices.filter { raws[it].hasNote }
        .ifEmpty { if ("notes" in revealed) listOf(targetIndex) else emptyList() }
    val labelRaws = raws.indices.filter { FieldKind.LABELS in ContactEditor.supportedKinds(raws[it].account) }
    val labelsVisible = labelRaws.isNotEmpty() &&
        (raws.any { it.hasLabels } || "labels" in revealed)
    val hasMore = raws.any {
        it.websites.isNotEmpty() || it.relations.isNotEmpty() || it.sips.isNotEmpty() || it.ims.isNotEmpty()
    } || noteRaws.isNotEmpty() || labelsVisible

    fun squareOf(raw: EditRawContact): ContactAccount? = if (multi) raw.account else null

    fun addPhone() = updateRaw(targetIndex) {
        it.copy(phones = it.phones + EditPhone(null, "", "mobile", Phone.TYPE_MOBILE))
    }
    fun addEmail() = updateRaw(targetIndex) { it.copy(emails = it.emails + EditEmail(null, "", Email.TYPE_HOME)) }
    fun addAddress() = updateRaw(targetIndex) { it.copy(addresses = it.addresses + EditAddress(null, "")) }
    fun addDate() {
        val present = raws.flatMap { r -> r.events.map { it.type } }
        val type = listOf(Event.TYPE_BIRTHDAY, Event.TYPE_ANNIVERSARY).firstOrNull { it !in present }
            ?: Event.TYPE_OTHER
        updateRaw(targetIndex) { it.copy(events = it.events + EditEvent(null, "", type)) }
    }

    val anyOverlay = showName || showAddField || showLabels || showNewLabel || showTarget ||
        relationRaw >= 0 || mapRaw >= 0 || confirmDiscard || extraOverlayOpen

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().hiddenFromAccessibility(anyOverlay)) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                PageCaption(caption, Modifier.padding(top = 18.dp))

                // ---- photo + name ----
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.Top) {
                    PhotoTile(
                        preview,
                        accent,
                        initialsOf(shownName),
                        stringResource(R.string.cform_photo_change),
                    ) { photoPicker.launch("image/*") }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.editcontact_name),
                            color = Metro.Subtle,
                            style = Metro.Type.Small,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 46.dp)
                                .border(2.dp, Metro.Foreground)
                                .clickable(role = Role.Button) { showName = true }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                shownName.ifEmpty { stringResource(R.string.cform_name_placeholder) },
                                color = if (shownName.isEmpty()) Metro.Subtle else Metro.Foreground,
                                style = Metro.Type.Body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(chevron(), color = Metro.Subtle, style = Metro.Type.Body)
                        }
                        val extras = listOfNotNull(
                            nickname.takeIf { it.isNotBlank() }
                                ?.let { stringResource(R.string.cform_nickname_quoted, it.trim()) },
                            nameRaw.name.phonetic.takeIf { it.isNotEmpty() },
                        )
                        if (extras.isNotEmpty()) HintLine(extras.joinToString(" · "), top = 6.dp)
                        HintLine(stringResource(R.string.cform_name_hint), top = 2.dp)
                    }
                }

                if (multi) AccountLegend(accounts)

                header()

                // ---- work ----
                if (workRaws.isNotEmpty()) {
                    FormSectionHeader(stringResource(R.string.cform_section_work))
                    workRaws.forEach { ri ->
                        val raw = raws[ri]
                        FieldRow(squareOf(raw), onRemove = null) {
                            MetroTextBox(
                                raw.company,
                                { v -> updateRaw(ri) { it.copy(company = v) } },
                                accent,
                                placeholder = stringResource(R.string.cform_company),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        FieldRow(squareOf(raw), onRemove = null) {
                            MetroTextBox(
                                raw.jobTitle,
                                { v -> updateRaw(ri) { it.copy(jobTitle = v) } },
                                accent,
                                placeholder = stringResource(R.string.cform_job_title),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // ---- phone ----
                FormSectionHeader(stringResource(R.string.cform_section_phone))
                val setDefaultLabel = stringResource(R.string.cform_set_default)
                raws.forEachIndexed { ri, raw ->
                    raw.phones.forEachIndexed { pi, phone ->
                        val toggleDefault: () -> Unit = {
                            update { it.withDefaultPhone(ri, pi) }
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        val currentToggle by rememberUpdatedState(toggleDefault)
                        FieldRow(
                            squareOf(raw),
                            onRemove = { updateRaw(ri) { r -> r.copy(phones = r.phones.without(pi)) } },
                            modifier = Modifier
                                .semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction(setDefaultLabel) {
                                            currentToggle()
                                            true
                                        },
                                    )
                                },
                        ) {
                            TypePicker(
                                types = ContactEditor.PHONE_TYPES,
                                type = phone.type ?: Phone.TYPE_MOBILE,
                                custom = phone.customLabel.orEmpty(),
                                fallback = phone.label,
                                width = 104.dp,
                            ) { type ->
                                updateRaw(ri) { r ->
                                    r.copy(
                                        phones = r.phones.replaced(pi) {
                                            // A custom label only travels with TYPE_CUSTOM.
                                            it.copy(
                                                type = type,
                                                customLabel = if (type == Phone.TYPE_CUSTOM) it.customLabel else null,
                                            )
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            MetroTextBox(
                                phone.number,
                                { v -> updateRaw(ri) { r -> r.copy(phones = r.phones.replaced(pi) { it.copy(number = v) }) } },
                                accent,
                                placeholder = stringResource(R.string.editcontact_number),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                // Only the number box: the type select and the ✕
                                // would also fire their own click on release.
                                modifier = Modifier
                                    .weight(1f)
                                    .observeLongPress(ri to pi) { currentToggle() },
                            )
                            if (phone.isDefault) {
                                Text(
                                    "★",
                                    color = accentText,
                                    style = Metro.Type.Body,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                        if (phone.type == Phone.TYPE_CUSTOM) {
                            CustomLabelBox(phone.customLabel.orEmpty(), accent) { v ->
                                updateRaw(ri) { r -> r.copy(phones = r.phones.replaced(pi) { it.copy(customLabel = v) }) }
                            }
                        }
                    }
                }
                val allPhones = raws.sumOf { it.phones.size }
                val default = form.defaultPhone()
                if (default != null) {
                    HintLine(
                        stringResource(
                            R.string.cform_default_number,
                            typeText(
                                ContactEditor.PHONE_TYPES, default.type ?: Phone.TYPE_MOBILE,
                                default.customLabel.orEmpty(), default.label,
                            ),
                        ),
                        top = 8.dp,
                    )
                } else if (allPhones >= 2) {
                    HintLine(stringResource(R.string.cform_default_none), top = 8.dp)
                }
                AddLink(stringResource(R.string.editcontact_add_phone), accent) { addPhone() }

                // ---- email ----
                FormSectionHeader(stringResource(R.string.cform_section_email))
                raws.forEachIndexed { ri, raw ->
                    raw.emails.forEachIndexed { ei, email ->
                        FieldRow(
                            squareOf(raw),
                            onRemove = { updateRaw(ri) { r -> r.copy(emails = r.emails.without(ei)) } },
                        ) {
                            TypePicker(
                                types = ContactEditor.EMAIL_TYPES,
                                type = email.type,
                                custom = email.customLabel,
                                fallback = Email.getTypeLabel(context.resources, email.type, email.customLabel)
                                    .toString().lowercase(),
                                width = 104.dp,
                            ) { type ->
                                updateRaw(ri) { r -> r.copy(emails = r.emails.replaced(ei) { it.copy(type = type) }) }
                            }
                            Spacer(Modifier.width(8.dp))
                            MetroTextBox(
                                email.address,
                                { v -> updateRaw(ri) { r -> r.copy(emails = r.emails.replaced(ei) { it.copy(address = v) }) } },
                                accent,
                                placeholder = stringResource(R.string.editcontact_email_address),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (email.type == Email.TYPE_CUSTOM) {
                            CustomLabelBox(email.customLabel, accent) { v ->
                                updateRaw(ri) { r -> r.copy(emails = r.emails.replaced(ei) { it.copy(customLabel = v) }) }
                            }
                        }
                    }
                }
                AddLink(stringResource(R.string.editcontact_add_email), accent) { addEmail() }

                // ---- address ----
                if (raws.any { it.addresses.isNotEmpty() }) {
                    FormSectionHeader(stringResource(R.string.cform_section_address))
                    raws.forEachIndexed { ri, raw ->
                        raw.addresses.forEachIndexed { ai, address ->
                            FieldRow(
                                squareOf(raw),
                                onRemove = { updateRaw(ri) { r -> r.copy(addresses = r.addresses.without(ai)) } },
                            ) {
                                TypePicker(
                                    types = ContactEditor.ADDRESS_TYPES,
                                    type = address.type,
                                    custom = address.customLabel,
                                    fallback = typeKeyLabel("other"),
                                    width = 104.dp,
                                ) { type ->
                                    updateRaw(ri) { r ->
                                        r.copy(addresses = r.addresses.replaced(ai) { it.copy(type = type) })
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                InlineLink(stringResource(R.string.cform_map), accent) {
                                    mapRaw = ri
                                    mapIdx = ai
                                }
                                val locatingHere = locator.locating && locateRaw == ri && locateIdx == ai
                                InlineLink(
                                    if (locatingHere) {
                                        stringResource(R.string.cform_locating)
                                    } else {
                                        stringResource(R.string.cform_here)
                                    },
                                    accent,
                                ) {
                                    locateRaw = ri
                                    locateIdx = ai
                                    locator.request()
                                }
                            }
                            if (address.type == StructuredPostal.TYPE_CUSTOM) {
                                CustomLabelBox(address.customLabel, accent) { v ->
                                    updateRaw(ri) { r ->
                                        r.copy(addresses = r.addresses.replaced(ai) { it.copy(customLabel = v) })
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            MetroTextBox(
                                address.formatted,
                                { v ->
                                    updateRaw(ri) { r ->
                                        r.copy(addresses = r.addresses.replaced(ai) { it.copy(formatted = v) })
                                    }
                                },
                                accent,
                                placeholder = stringResource(R.string.editcontact_street_city_country),
                                singleLine = false,
                                minLines = 2,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            )
                        }
                    }
                    AddLink(stringResource(R.string.cform_add_address), accent) { addAddress() }
                }

                // ---- dates ----
                if (raws.any { it.events.isNotEmpty() }) {
                    FormSectionHeader(stringResource(R.string.cform_section_dates))
                    raws.forEachIndexed { ri, raw ->
                        raw.events.forEachIndexed { di, event ->
                            FieldRow(
                                squareOf(raw),
                                onRemove = { updateRaw(ri) { r -> r.copy(events = r.events.without(di)) } },
                            ) {
                                TypePicker(
                                    types = ContactEditor.EVENT_TYPES,
                                    type = event.type,
                                    custom = event.customLabel,
                                    fallback = typeKeyLabel("other"),
                                    width = 128.dp,
                                ) { type ->
                                    updateRaw(ri) { r -> r.copy(events = r.events.replaced(di) { it.copy(type = type) }) }
                                }
                                Spacer(Modifier.width(8.dp))
                                DateField(
                                    value = event.startDate,
                                    placeholder = stringResource(R.string.editcontact_pick_a_date),
                                    modifier = Modifier.weight(1f),
                                ) { picked ->
                                    updateRaw(ri) { r ->
                                        r.copy(events = r.events.replaced(di) { it.copy(startDate = picked) })
                                    }
                                }
                            }
                            if (event.type == Event.TYPE_CUSTOM) {
                                CustomLabelBox(event.customLabel, accent) { v ->
                                    updateRaw(ri) { r -> r.copy(events = r.events.replaced(di) { it.copy(customLabel = v) }) }
                                }
                            }
                        }
                    }
                    HintLine(stringResource(R.string.cform_year_optional), top = 8.dp)
                    AddLink(stringResource(R.string.editcontact_add_date), accent) { addDate() }
                }

                // ---- more ----
                if (hasMore) {
                    FormSectionHeader(stringResource(R.string.cform_section_more))
                    raws.forEachIndexed { ri, raw ->
                        raw.websites.forEachIndexed { wi, site ->
                            FieldLabel(stringResource(R.string.cform_website), top = 10.dp)
                            FieldRow(
                                squareOf(raw),
                                onRemove = { updateRaw(ri) { r -> r.copy(websites = r.websites.without(wi)) } },
                                top = 0.dp,
                            ) {
                                MetroTextBox(
                                    site.value,
                                    { v ->
                                        updateRaw(ri) { r -> r.copy(websites = r.websites.replaced(wi) { it.copy(value = v) }) }
                                    },
                                    accent,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    raws.forEachIndexed { ri, raw ->
                        raw.relations.forEachIndexed { li, rel ->
                            FieldLabel(stringResource(R.string.cform_relationship), top = 10.dp)
                            FieldRow(
                                squareOf(raw),
                                onRemove = { updateRaw(ri) { r -> r.copy(relations = r.relations.without(li)) } },
                                top = 0.dp,
                            ) {
                                TypePicker(
                                    types = ContactEditor.RELATION_TYPES,
                                    type = rel.type,
                                    custom = rel.customLabel,
                                    fallback = typeKeyLabel("custom"),
                                    width = 104.dp,
                                ) { type ->
                                    updateRaw(ri) { r -> r.copy(relations = r.relations.replaced(li) { it.copy(type = type) }) }
                                }
                                Spacer(Modifier.width(8.dp))
                                MetroTextBox(
                                    rel.name,
                                    { v ->
                                        updateRaw(ri) { r -> r.copy(relations = r.relations.replaced(li) { it.copy(name = v) }) }
                                    },
                                    accent,
                                    placeholder = stringResource(R.string.cform_related_name),
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                                    modifier = Modifier.weight(1f),
                                )
                                val pickLabel = stringResource(R.string.cform_pick_contact)
                                Box(
                                    Modifier
                                        .sizeIn(minWidth = 44.dp, minHeight = 46.dp)
                                        .clickable(role = Role.Button, onClickLabel = pickLabel) {
                                            relationRaw = ri
                                            relationIdx = li
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.Person,
                                        contentDescription = pickLabel,
                                        tint = Metro.Foreground,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            if (rel.type == android.provider.ContactsContract.CommonDataKinds.Relation.TYPE_CUSTOM) {
                                CustomLabelBox(rel.customLabel, accent) { v ->
                                    updateRaw(ri) { r ->
                                        r.copy(relations = r.relations.replaced(li) { it.copy(customLabel = v) })
                                    }
                                }
                            }
                        }
                    }
                    raws.forEachIndexed { ri, raw ->
                        raw.sips.forEachIndexed { si, sip ->
                            FieldLabel(stringResource(R.string.cform_internet_call), top = 10.dp)
                            FieldRow(
                                squareOf(raw),
                                onRemove = { updateRaw(ri) { r -> r.copy(sips = r.sips.without(si)) } },
                                top = 0.dp,
                            ) {
                                MetroTextBox(
                                    sip.value,
                                    { v -> updateRaw(ri) { r -> r.copy(sips = r.sips.replaced(si) { it.copy(value = v) }) } },
                                    accent,
                                    placeholder = stringResource(R.string.cform_sip_address),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    raws.forEachIndexed { ri, raw ->
                        raw.ims.forEachIndexed { ii, im ->
                            FieldLabel(stringResource(R.string.cform_chat), top = 10.dp)
                            FieldRow(
                                squareOf(raw),
                                onRemove = { updateRaw(ri) { r -> r.copy(ims = r.ims.without(ii)) } },
                                top = 0.dp,
                            ) {
                                TypePicker(
                                    types = ContactEditor.IM_PROTOCOLS,
                                    type = im.protocol,
                                    custom = im.customProtocol,
                                    fallback = typeKeyLabel("custom"),
                                    width = 104.dp,
                                    labelOf = { imProtocolLabel(it) },
                                ) { protocol ->
                                    updateRaw(ri) { r -> r.copy(ims = r.ims.replaced(ii) { it.copy(protocol = protocol) }) }
                                }
                                Spacer(Modifier.width(8.dp))
                                MetroTextBox(
                                    im.handle,
                                    { v -> updateRaw(ri) { r -> r.copy(ims = r.ims.replaced(ii) { it.copy(handle = v) }) } },
                                    accent,
                                    placeholder = stringResource(R.string.cform_chat_handle),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (ContactEditor.IM_PROTOCOLS.firstOrNull { it.second == im.protocol }?.first == "custom") {
                                CustomLabelBox(im.customProtocol, accent) { v ->
                                    updateRaw(ri) { r -> r.copy(ims = r.ims.replaced(ii) { it.copy(customProtocol = v) }) }
                                }
                            }
                        }
                    }
                    if (labelsVisible) {
                        FieldLabel(stringResource(R.string.cform_labels), top = 10.dp)
                        val titles = raws.flatMap { raw ->
                            groupsByAccount[raw.account].orEmpty()
                                .filter { it.id in raw.groupIds }.map { it.title } + raw.newGroups
                        }.distinct()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 46.dp)
                                .border(2.dp, Metro.Foreground)
                                .clickable(role = Role.Button) { showLabels = true }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                titles.joinToString(", ").ifEmpty { stringResource(R.string.cform_labels_none) },
                                color = if (titles.isEmpty()) Metro.Subtle else Metro.Foreground,
                                style = Metro.Type.Body,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(chevron(), color = Metro.Subtle, style = Metro.Type.Body)
                        }
                    }
                    noteRaws.forEach { ri ->
                        val raw = raws[ri]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                FieldLabel(stringResource(R.string.cform_notes), top = 10.dp)
                            }
                            squareOf(raw)?.let { SquareMark(it, Modifier.padding(top = 10.dp)) }
                        }
                        MetroTextBox(
                            raw.note,
                            { v -> updateRaw(ri) { it.copy(note = v) } },
                            accent,
                            singleLine = false,
                            minLines = 3,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        )
                    }
                }
                AddLink(stringResource(R.string.cform_add_another), accent) { showAddField = true }

                // ---- where new fields go ----
                if (multi) {
                    FieldLabel(stringResource(R.string.cform_new_fields_to), top = 22.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { showTarget = true }
                            .padding(vertical = 4.dp),
                    ) {
                        FieldAccountSquare(targetRaw.account, size = 22.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            accountKindLabel(targetRaw.account.kindLabel) +
                                (targetRaw.account.name?.let { " · $it" } ?: ""),
                            color = Metro.Foreground,
                            style = Metro.Type.Body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cform_change), color = accentText, style = Metro.Type.Small)
                    }
                }
                if (form.readOnlyAccounts.isNotEmpty()) {
                    HintLine(
                        stringResource(R.string.cform_read_only, form.readOnlyAccounts.joinToString(", ")),
                        top = if (multi) 4.dp else 18.dp,
                    )
                }
                Spacer(Modifier.height(24.dp))
            }

            val status = when {
                saving -> stringResource(R.string.editcontact_saving)
                saveFailedText != null -> saveFailedText
                else -> null
            }
            if (status != null) {
                Text(
                    status,
                    color = if (saving) Metro.Subtle else Metro.Red,
                    style = Metro.Type.Small,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            MetroAppBar(
                actions = listOf(
                    AppBarAction(Icons.Filled.Check, stringResource(R.string.editcontact_save)) {
                        if (!saving) onSave()
                    },
                    AppBarAction(Icons.Filled.Close, stringResource(R.string.editcontact_cancel)) {
                        requestClose()
                    },
                ),
            )
        }

        // ---- overlays ----
        if (showName) {
            NameDetailsPage(
                initial = nameRaw.name,
                initialNickname = nickname,
                showNickname = FieldKind.NICKNAME in targetKinds || raws.any { it.hasNickname },
                accent = accent,
                onDone = { name, nick ->
                    if (name != nameRaw.name) {
                        update { c ->
                            c.withContactWide(target, { it.hasName }) { it.copy(name = name.copy(display = "")) }
                        }
                    }
                    if (nick != nickname) {
                        update { c -> c.withContactWide(target, { it.hasNickname }) { it.copy(nickname = nick) } }
                    }
                    showName = false
                },
                onCancel = { showName = false },
            )
        }

        if (showAddField) {
            val items = listOf(
                AddFieldItem(FieldKind.PHONE, stringResource(R.string.cform_section_phone), null, false),
                AddFieldItem(FieldKind.EMAIL, stringResource(R.string.cform_section_email), null, false),
                AddFieldItem(FieldKind.ADDRESS, stringResource(R.string.cform_section_address), null, false),
                AddFieldItem(
                    FieldKind.DATE, stringResource(R.string.cform_kind_date),
                    stringResource(R.string.cform_sub_date), false,
                ),
                AddFieldItem(FieldKind.WORK, stringResource(R.string.cform_section_work),
                    stringResource(R.string.cform_sub_work), workRaws.isNotEmpty()),
                AddFieldItem(FieldKind.WEBSITE, stringResource(R.string.cform_website), null, false),
                AddFieldItem(
                    FieldKind.RELATION, stringResource(R.string.cform_relationship),
                    stringResource(R.string.cform_sub_relationship), false,
                ),
                AddFieldItem(
                    FieldKind.SIP, stringResource(R.string.cform_internet_call),
                    stringResource(R.string.cform_sip_address), false,
                ),
                AddFieldItem(
                    FieldKind.IM, stringResource(R.string.cform_chat),
                    stringResource(R.string.cform_sub_chat), false,
                ),
                AddFieldItem(
                    FieldKind.NICKNAME, stringResource(R.string.cform_nickname), null,
                    raws.any { it.nickname.isNotBlank() },
                ),
                AddFieldItem(FieldKind.LABELS, stringResource(R.string.cform_labels), null, labelsVisible),
                AddFieldItem(FieldKind.NOTE, stringResource(R.string.cform_notes), null, noteRaws.isNotEmpty()),
            ).filter { it.kind in targetKinds }
            AddFieldPage(
                caption = caption,
                items = items,
                onPick = { kind ->
                    when (kind) {
                        FieldKind.PHONE -> addPhone()
                        FieldKind.EMAIL -> addEmail()
                        FieldKind.ADDRESS -> addAddress()
                        FieldKind.DATE -> addDate()
                        FieldKind.WEBSITE -> updateRaw(targetIndex) {
                            it.copy(websites = it.websites + EditValue(null, "", Website.TYPE_OTHER))
                        }
                        FieldKind.RELATION -> updateRaw(targetIndex) {
                            it.copy(
                                relations = it.relations +
                                    EditRelation(null, "", ContactEditor.RELATION_TYPES.first().second),
                            )
                        }
                        FieldKind.SIP -> updateRaw(targetIndex) {
                            it.copy(sips = it.sips + EditValue(null, "", SipAddress.TYPE_OTHER))
                        }
                        FieldKind.IM -> updateRaw(targetIndex) {
                            it.copy(ims = it.ims + EditIm(null, "", ContactEditor.IM_PROTOCOLS.first().second))
                        }
                        FieldKind.NICKNAME -> showName = true
                        FieldKind.WORK -> reveal("work")
                        FieldKind.NOTE -> reveal("notes")
                        FieldKind.LABELS -> {
                            reveal("labels")
                            showLabels = true
                        }
                    }
                    showAddField = false
                },
                onDismiss = { showAddField = false },
            )
        }

        if (showLabels) {
            val sections = labelRaws.map { ri ->
                val raw = raws[ri]
                LabelSection(
                    rawIndex = ri,
                    account = raw.account,
                    groups = groupsByAccount[raw.account].orEmpty(),
                    checked = raw.groupIds,
                    newTitles = raw.newGroups,
                )
            }
            LabelsPage(
                caption = shownName.uppercase().ifEmpty { caption },
                sections = sections,
                showAccounts = multi,
                accent = accent,
                onToggle = { ri, gid ->
                    updateRaw(ri) { r ->
                        r.copy(groupIds = if (gid in r.groupIds) r.groupIds - gid else r.groupIds + gid)
                    }
                },
                onRemoveNew = { ri, title -> updateRaw(ri) { r -> r.copy(newGroups = r.newGroups - title) } },
                onNewLabel = { showNewLabel = true },
                onDone = { showLabels = false },
            )
        }

        if (showNewLabel) {
            val labelTarget = if (targetIndex in labelRaws) targetIndex else labelRaws.firstOrNull()
            NewLabelDialog(
                accountLabel = labelTarget?.takeIf { multi }?.let { raws[it].account.fullLabel },
                accent = accent,
                onCreate = { title ->
                    showNewLabel = false
                    if (labelTarget != null) {
                        val existing = groupsByAccount[raws[labelTarget].account].orEmpty()
                            .firstOrNull { it.title.equals(title, ignoreCase = true) }
                        updateRaw(labelTarget) { r ->
                            when {
                                existing != null -> r.copy(groupIds = r.groupIds + existing.id)
                                r.newGroups.any { it.equals(title, ignoreCase = true) } -> r
                                else -> r.copy(newGroups = r.newGroups + title)
                            }
                        }
                    }
                },
                onDismiss = { showNewLabel = false },
            )
        }

        if (showTarget) {
            TargetAccountDialog(
                options = accounts.map { a -> raws.indexOfFirst { it.account == a } to a },
                selected = targetIndex,
                accent = accent,
                onPick = {
                    target = it
                    showTarget = false
                },
                onDismiss = { showTarget = false },
            )
        }

        if (relationRaw >= 0) {
            ContactPickPage(
                accent = accent,
                onPicked = { name ->
                    val ri = relationRaw
                    val li = relationIdx
                    updateRaw(ri) { r -> r.copy(relations = r.relations.replaced(li) { it.copy(name = name) }) }
                    relationRaw = -1
                },
                onDismiss = { relationRaw = -1 },
            )
        }

        if (mapRaw >= 0) {
            MapPinPicker(
                accent = accent,
                onCancel = { mapRaw = -1 },
                onPicked = { addr ->
                    val ri = mapRaw
                    val ai = mapIdx
                    updateRaw(ri) { r -> r.copy(addresses = r.addresses.replaced(ai) { it.copy(formatted = addr) }) }
                    mapRaw = -1
                },
            )
        }

        extraOverlays()

        if (confirmDiscard) {
            MetroConfirm(
                title = stringResource(R.string.editor_discard_title),
                message = stringResource(R.string.editor_discard_message),
                confirmLabel = stringResource(R.string.editor_discard_button),
                cancelLabel = stringResource(R.string.editor_keep_editing),
                onConfirm = {
                    confirmDiscard = false
                    onDone()
                },
                onCancel = { confirmDiscard = false },
            )
        }
    }
}

/** "■ Google ■ Outlook" + "each field saves to its own account". */
@Composable
private fun AccountLegend(accounts: List<ContactAccount>) {
    val kinds = accounts.map { it.kindLabel }
    @Composable
    fun label(a: ContactAccount): String =
        if (kinds.count { it == a.kindLabel } > 1) a.fullLabel else accountKindLabel(a.kindLabel)
    Spacer(Modifier.height(14.dp))
    if (accounts.size <= 2) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            accounts.forEachIndexed { i, a ->
                if (i > 0) Spacer(Modifier.width(14.dp))
                FieldAccountSquare(a, size = 14.dp)
                Spacer(Modifier.width(6.dp))
                Text(label(a), color = Metro.Subtle, style = Metro.Type.Small, maxLines = 1)
            }
        }
    } else {
        accounts.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                FieldAccountSquare(a, size = 14.dp)
                Spacer(Modifier.width(6.dp))
                Text(label(a), color = Metro.Subtle, style = Metro.Type.Small, maxLines = 1)
            }
        }
    }
    HintLine(stringResource(R.string.cform_legend_hint), top = 2.dp)
}

/** One field row: content, then the account square (multi-account) and the remove mark. */
@Composable
private fun FieldRow(
    square: ContactAccount?,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    top: Dp = 10.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = top),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        if (square != null) {
            Spacer(Modifier.width(8.dp))
            SquareMark(square)
        }
        if (onRemove != null) {
            RemoveMark(onRemove)
        } else if (square != null) {
            Spacer(Modifier.width(4.dp))
        }
    }
}

/** The 10dp per-field account square, announced as "saved to <account>". */
@Composable
private fun SquareMark(account: ContactAccount, modifier: Modifier = Modifier) {
    Box(modifier) {
        FieldAccountSquare(
            account,
            description = stringResource(R.string.cform_saved_to_account, accountKindLabel(account.kindLabel)),
        )
    }
}

/** Display text of a typed row's type: table label, custom text, or [fallback] for unknown legacy types. */
@Composable
private fun typeText(
    types: List<Pair<String, Int>>,
    type: Int,
    custom: String,
    fallback: String,
    labelOf: @Composable (String) -> String = { typeKeyLabel(it) },
): String {
    val key = types.firstOrNull { it.second == type }?.first
    return when {
        key == "custom" -> custom.ifBlank { labelOf("custom") }
        key != null -> labelOf(key)
        else -> fallback
    }
}

/** The bordered type select of a typed row; [onPick] gets the chosen type value. */
@Composable
private fun TypePicker(
    types: List<Pair<String, Int>>,
    type: Int,
    custom: String,
    fallback: String,
    width: Dp,
    labelOf: @Composable (String) -> String = { typeKeyLabel(it) },
    onPick: (Int) -> Unit,
) {
    val current = typeText(types, type, custom, fallback, labelOf)
    val options = types.map { labelOf(it.first) }
    WpDropdown(
        current = current,
        options = options,
        onSelect = { i -> onPick(types[i].second) },
        modifier = Modifier.width(width),
    )
}

/** The free-text label box under a row whose type is "custom". */
@Composable
private fun CustomLabelBox(value: String, accent: Color, onChange: (String) -> Unit) {
    Spacer(Modifier.height(6.dp))
    MetroTextBox(
        value,
        onChange,
        accent,
        placeholder = stringResource(R.string.editcontact_custom_label),
    )
}

/** Accent text action inside a row ("map", "here"), 48dp tall touch target. */
@Composable
private fun InlineLink(text: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .sizeIn(minHeight = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) { onClick() }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Metro.textAccent(accent), style = Metro.Type.Small)
    }
}
