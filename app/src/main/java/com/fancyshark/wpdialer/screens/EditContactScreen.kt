package com.fancyshark.wpdialer.screens

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.data.ContactEditor
import com.fancyshark.wpdialer.data.EditEmail
import com.fancyshark.wpdialer.data.EditEvent
import com.fancyshark.wpdialer.data.EditPhone
import com.fancyshark.wpdialer.data.EditRawContact
import com.fancyshark.wpdialer.data.EditableContact
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroButton
import com.fancyshark.wpdialer.ui.MetroConfirm
import com.fancyshark.wpdialer.ui.MetroTextBox
import com.fancyshark.wpdialer.ui.hiddenFromAccessibility
import kotlinx.coroutines.launch

/**
 * [onDirtyChange] reports whether the form holds unsaved edits, so the host
 * can keep the editor alive when a dial intent arrives.
 */
@Composable
fun EditContactScreen(
    contactId: Long,
    accent: Color,
    onDone: () -> Unit,
    onDirtyChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loadFailed by remember { mutableStateOf(false) }
    val original by produceState<EditableContact?>(null, contactId) {
        value = ContactEditor.loadEditable(context, contactId)
        if (value == null) loadFailed = true
    }
    // The working copy is saveable: the photo picker round trip can cost
    // the process, and the edits must survive it. Null = untouched so far.
    val formSaver = remember { editableContactSaver(context.applicationContext) }
    var editedState by rememberSaveable(contactId, stateSaver = formSaver) {
        mutableStateOf<EditableContact?>(null)
    }
    // One picker shared by every raw contact's photo tile; the tile records
    // which raw contact the picked image belongs to before launching.
    var photoTarget by rememberSaveable { mutableIntStateOf(-1) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    if (loadFailed) {
        // Deleted or unreadable contact: say so, then leave instead of
        // stranding the user on a blank page. Leaving composition (back
        // pressed first) cancels the pop, so it never pops twice.
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1500)
            onDone()
        }
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.editcontact_load_failed),
            color = Metro.Subtle,
            fontSize = 20.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    val loaded = original ?: return
    val edited = editedState ?: loaded
    val dirty = editedState?.let { !it.sameFormAs(loaded) } ?: false
    LaunchedEffect(dirty) { onDirtyChange(dirty) }
    fun requestClose() {
        if (dirty) confirmDiscard = true else onDone()
    }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    // Reads the live state at call time — two edits landing before the
    // next recomposition must both stick.
    fun updateRaw(index: Int, transform: (EditRawContact) -> EditRawContact) {
        val current = editedState ?: loaded
        editedState = current.copy(
            raws = current.raws.mapIndexed { i, r -> if (i == index) transform(r) else r },
        )
    }

    fun updatePhone(rawIndex: Int, phoneIndex: Int, transform: (EditPhone) -> EditPhone) {
        updateRaw(rawIndex) { r ->
            r.copy(phones = r.phones.mapIndexed { j, p -> if (j == phoneIndex) transform(p) else p })
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val target = photoTarget
        if (uri != null && target >= 0) {
            scope.launch {
                val bytes = loadPhotoBytes(context, uri)
                if (bytes != null) {
                    updateRaw(target) { it.copy(newPhoto = bytes) }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .hiddenFromAccessibility(confirmDiscard)
            .padding(horizontal = 20.dp),
    ) {
        val title = if (edited.raws.size == 1) {
            stringResource(
                com.fancyshark.wpdialer.R.string.editcontact_title_kind,
                accountKindLabel(edited.raws.first().account.kindLabel).uppercase(),
            )
        } else {
            stringResource(com.fancyshark.wpdialer.R.string.editcontact_title)
        }
        Text(
            title,
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
            edited.raws.forEachIndexed { rawIndex, raw ->
                Spacer(Modifier.height(14.dp))
                if (edited.raws.size > 1) {
                    Text(
                        stringResource(
                            com.fancyshark.wpdialer.R.string.editcontact_saved_to,
                            raw.account.fullLabel,
                        ),
                        color = Metro.textAccent(accent),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    stringResource(com.fancyshark.wpdialer.R.string.editcontact_name),
                    color = Metro.Subtle,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetroTextBox(
                        value = raw.name,
                        onValueChange = { newName ->
                            updateRaw(rawIndex) { it.copy(name = newName) }
                        },
                        accent = accent,
                        placeholder = stringResource(
                            com.fancyshark.wpdialer.R.string.editcontact_name,
                        ),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    // Decoded off the main thread — a full-size photo row
                    // would otherwise stall composition.
                    val photoBytes = raw.newPhoto ?: raw.photo
                    val preview by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
                        null, photoBytes,
                    ) { value = decodePhotoPreview(photoBytes) }
                    PhotoTile(
                        preview,
                        accent,
                        stringResource(com.fancyshark.wpdialer.R.string.editcontact_change_photo),
                    ) {
                        photoTarget = rawIndex
                        photoPicker.launch("image/*")
                    }
                }

                raw.phones.forEachIndexed { phoneIndex, phone ->
                    // Unknown legacy types (fax, pager...) keep their own
                    // label until the user picks one from the list.
                    val typeKey = ContactEditor.PHONE_TYPES
                        .firstOrNull { it.second == (phone.type ?: Phone.TYPE_MOBILE) }?.first
                    val typeLabel = when {
                        typeKey == "custom" -> phone.customLabel?.takeIf { it.isNotBlank() }
                            ?: typeKeyLabel("custom")
                        typeKey != null -> typeKeyLabel(typeKey)
                        else -> phone.label
                    }
                    FieldLabel(
                        stringResource(
                            com.fancyshark.wpdialer.R.string.editcontact_phone_label,
                            typeLabel,
                        ),
                        top = 10.dp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MetroTextBox(
                            value = phone.number,
                            onValueChange = { newNumber ->
                                updatePhone(rawIndex, phoneIndex) { it.copy(number = newNumber) }
                            },
                            accent = accent,
                            placeholder = stringResource(
                                com.fancyshark.wpdialer.R.string.editcontact_number,
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        WpDropdown(
                            current = if (typeKey != null) typeKeyLabel(typeKey) else phone.label,
                            options = ContactEditor.PHONE_TYPES.map { typeKeyLabel(it.first) },
                            onSelect = { t ->
                                val type = ContactEditor.PHONE_TYPES[t].second
                                updatePhone(rawIndex, phoneIndex) {
                                    // A custom label only travels with TYPE_CUSTOM.
                                    it.copy(
                                        type = type,
                                        customLabel = if (type == Phone.TYPE_CUSTOM) {
                                            it.customLabel
                                        } else {
                                            null
                                        },
                                    )
                                }
                            },
                            modifier = Modifier.width(110.dp),
                        )
                        RemoveMark {
                            updateRaw(rawIndex) { r ->
                                r.copy(phones = r.phones.filterIndexed { j, _ -> j != phoneIndex })
                            }
                        }
                    }
                    if (phone.type == Phone.TYPE_CUSTOM) {
                        Spacer(Modifier.height(6.dp))
                        MetroTextBox(
                            value = phone.customLabel.orEmpty(),
                            onValueChange = { newLabel ->
                                updatePhone(rawIndex, phoneIndex) { it.copy(customLabel = newLabel) }
                            },
                            accent = accent,
                            placeholder = stringResource(
                                com.fancyshark.wpdialer.R.string.editcontact_custom_label,
                            ),
                        )
                    }
                }
                AddLink(
                    stringResource(com.fancyshark.wpdialer.R.string.editcontact_add_phone),
                    accent,
                ) {
                    updateRaw(rawIndex) { r ->
                        r.copy(phones = r.phones + EditPhone(null, "", "mobile", Phone.TYPE_MOBILE))
                    }
                }

                raw.emails.forEachIndexed { emailIndex, email ->
                    FieldLabel(
                        stringResource(com.fancyshark.wpdialer.R.string.editcontact_email),
                        top = 10.dp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MetroTextBox(
                            value = email.address,
                            onValueChange = { newAddress ->
                                updateRaw(rawIndex) { r ->
                                    r.copy(
                                        emails = r.emails.mapIndexed { j, e ->
                                            if (j == emailIndex) e.copy(address = newAddress) else e
                                        },
                                    )
                                }
                            },
                            accent = accent,
                            placeholder = stringResource(
                                com.fancyshark.wpdialer.R.string.editcontact_email_address,
                            ),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        WpDropdown(
                            current = ContactEditor.EMAIL_TYPES
                                .firstOrNull { it.second == email.type }?.first
                                ?.let { typeKeyLabel(it) }
                                ?: stringResource(
                                    com.fancyshark.wpdialer.R.string.editcontact_other,
                                ),
                            options = ContactEditor.EMAIL_TYPES.map { typeKeyLabel(it.first) },
                            onSelect = { t ->
                                updateRaw(rawIndex) { r ->
                                    r.copy(
                                        emails = r.emails.mapIndexed { j, e ->
                                            if (j == emailIndex) {
                                                e.copy(type = ContactEditor.EMAIL_TYPES[t].second)
                                            } else e
                                        },
                                    )
                                }
                            },
                            modifier = Modifier.width(110.dp),
                        )
                        RemoveMark {
                            updateRaw(rawIndex) { r ->
                                r.copy(emails = r.emails.filterIndexed { j, _ -> j != emailIndex })
                            }
                        }
                    }
                    if (email.type == Email.TYPE_CUSTOM) {
                        Spacer(Modifier.height(6.dp))
                        MetroTextBox(
                            value = email.customLabel,
                            onValueChange = { newLabel ->
                                updateRaw(rawIndex) { r ->
                                    r.copy(
                                        emails = r.emails.mapIndexed { j, e ->
                                            if (j == emailIndex) e.copy(customLabel = newLabel) else e
                                        },
                                    )
                                }
                            },
                            accent = accent,
                            placeholder = stringResource(
                                com.fancyshark.wpdialer.R.string.editcontact_custom_label,
                            ),
                        )
                    }
                }
                AddLink(
                    stringResource(com.fancyshark.wpdialer.R.string.editcontact_add_email),
                    accent,
                ) {
                    updateRaw(rawIndex) { r ->
                        r.copy(emails = r.emails + EditEmail(null, "", Email.TYPE_HOME))
                    }
                }

                raw.events.forEachIndexed { eventIndex, event ->
                    FieldLabel(
                        stringResource(com.fancyshark.wpdialer.R.string.editcontact_date),
                        top = 10.dp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DateField(
                            value = event.startDate,
                            placeholder = stringResource(
                                com.fancyshark.wpdialer.R.string.editcontact_pick_a_date,
                            ),
                            modifier = Modifier.weight(1f),
                        ) { picked ->
                            updateRaw(rawIndex) { r ->
                                r.copy(
                                    events = r.events.mapIndexed { j, ev ->
                                        if (j == eventIndex) ev.copy(startDate = picked) else ev
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        WpDropdown(
                            current = ContactEditor.EVENT_TYPES
                                .firstOrNull { it.second == event.type }?.first
                                ?.let { typeKeyLabel(it) }
                                ?: stringResource(
                                    com.fancyshark.wpdialer.R.string.editcontact_other,
                                ),
                            options = ContactEditor.EVENT_TYPES.map { typeKeyLabel(it.first) },
                            onSelect = { t ->
                                updateRaw(rawIndex) { r ->
                                    r.copy(
                                        events = r.events.mapIndexed { j, ev ->
                                            if (j == eventIndex) {
                                                ev.copy(type = ContactEditor.EVENT_TYPES[t].second)
                                            } else ev
                                        },
                                    )
                                }
                            },
                            modifier = Modifier.width(130.dp),
                        )
                        RemoveMark {
                            updateRaw(rawIndex) { r ->
                                r.copy(events = r.events.filterIndexed { j, _ -> j != eventIndex })
                            }
                        }
                    }
                    if (event.type == Event.TYPE_CUSTOM) {
                        Spacer(Modifier.height(6.dp))
                        MetroTextBox(
                            value = event.customLabel,
                            onValueChange = { newLabel ->
                                updateRaw(rawIndex) { r ->
                                    r.copy(
                                        events = r.events.mapIndexed { j, ev ->
                                            if (j == eventIndex) ev.copy(customLabel = newLabel) else ev
                                        },
                                    )
                                }
                            },
                            accent = accent,
                            placeholder = stringResource(
                                com.fancyshark.wpdialer.R.string.editcontact_custom_label,
                            ),
                        )
                    }
                }
                AddLink(
                    stringResource(com.fancyshark.wpdialer.R.string.editcontact_add_date),
                    accent,
                ) {
                    updateRaw(rawIndex) { r ->
                        r.copy(events = r.events + EditEvent(null, "", Event.TYPE_BIRTHDAY))
                    }
                }

                FieldLabel(
                    stringResource(com.fancyshark.wpdialer.R.string.editcontact_address),
                    top = 10.dp,
                )
                MetroTextBox(
                    value = raw.address,
                    onValueChange = { newAddress ->
                        updateRaw(rawIndex) { it.copy(address = newAddress) }
                    },
                    accent = accent,
                    placeholder = stringResource(
                        com.fancyshark.wpdialer.R.string.editcontact_street_city_country,
                    ),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                )

                FieldLabel(
                    stringResource(com.fancyshark.wpdialer.R.string.editcontact_note),
                    top = 10.dp,
                )
                MetroTextBox(
                    value = raw.note,
                    onValueChange = { newNote ->
                        updateRaw(rawIndex) { it.copy(note = newNote) }
                    },
                    accent = accent,
                    placeholder = stringResource(
                        com.fancyshark.wpdialer.R.string.editcontact_note,
                    ),
                    singleLine = false,
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                )
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(20.dp))
        }
        if (saveFailed) {
            Text(
                stringResource(com.fancyshark.wpdialer.R.string.editcontact_save_failed),
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
                    stringResource(com.fancyshark.wpdialer.R.string.editcontact_saving)
                } else {
                    stringResource(com.fancyshark.wpdialer.R.string.editcontact_save)
                },
                fill = accent,
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) {
                saving = true
                saveFailed = false
                scope.launch {
                    val ok = ContactEditor.save(context, loaded, editedState ?: loaded)
                    if (ok) {
                        onDone()
                    } else {
                        saving = false
                        saveFailed = true
                    }
                }
            }
            MetroButton(
                stringResource(com.fancyshark.wpdialer.R.string.editcontact_cancel),
                modifier = Modifier.weight(1f),
            ) { requestClose() }
        }
    }

    if (confirmDiscard) {
        MetroConfirm(
            title = stringResource(com.fancyshark.wpdialer.R.string.editor_discard_title),
            message = stringResource(com.fancyshark.wpdialer.R.string.editor_discard_message),
            confirmLabel = stringResource(com.fancyshark.wpdialer.R.string.editor_discard_button),
            cancelLabel = stringResource(com.fancyshark.wpdialer.R.string.editor_keep_editing),
            onConfirm = {
                confirmDiscard = false
                onDone()
            },
            onCancel = { confirmDiscard = false },
        )
    }
    }
}
