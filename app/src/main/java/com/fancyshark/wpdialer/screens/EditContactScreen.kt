package com.fancyshark.wpdialer.screens

import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.fancyshark.wpdialer.ui.MetroTextBox
import java.io.ByteArrayOutputStream
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EditContactScreen(
    contactId: Long,
    accent: Color,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val original by produceState<EditableContact?>(null, contactId) {
        value = ContactEditor.loadEditable(context, contactId)
    }
    val loaded = original ?: return

    var edited by remember(loaded) { mutableStateOf(loaded) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    fun updateRaw(index: Int, transform: (EditRawContact) -> EditRawContact) {
        edited = edited.copy(
            raws = edited.raws.mapIndexed { i, r -> if (i == index) transform(r) else r },
        )
    }

    // One picker shared by every raw contact's photo tile; the tile records
    // which raw contact the picked image belongs to before launching.
    var photoTarget by remember { mutableIntStateOf(-1) }
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val target = photoTarget
        if (uri != null && target >= 0) {
            scope.launch {
                val bytes = loadEditPhotoBytes(context, uri)
                if (bytes != null) {
                    updateRaw(target) { it.copy(newPhoto = bytes) }
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        val title = if (edited.raws.size == 1) {
            "EDIT ${edited.raws.first().account.kindLabel.uppercase()} CONTACT"
        } else {
            "EDIT CONTACT"
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
                        "saved to ${raw.account.fullLabel}",
                        color = accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text("name", color = Metro.Subtle, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetroTextBox(
                        value = raw.name,
                        onValueChange = { newName ->
                            updateRaw(rawIndex) { it.copy(name = newName) }
                        },
                        accent = accent,
                        placeholder = "name",
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
                            .clickable {
                                photoTarget = rawIndex
                                photoPicker.launch("image/*")
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        val photoBytes = raw.newPhoto ?: raw.photo
                        val preview = remember(photoBytes) {
                            photoBytes?.let { bytes ->
                                runCatching {
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                }.getOrNull()
                            }?.asImageBitmap()
                        }
                        if (preview != null) {
                            Image(
                                preview,
                                contentDescription = "change photo",
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

                raw.phones.forEachIndexed { phoneIndex, phone ->
                    EditFieldLabel("phone (${phone.label})")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MetroTextBox(
                            value = phone.number,
                            onValueChange = { newNumber ->
                                updateRaw(rawIndex) { r ->
                                    r.copy(
                                        phones = r.phones.mapIndexed { j, p ->
                                            if (j == phoneIndex) p.copy(number = newNumber) else p
                                        },
                                    )
                                }
                            },
                            accent = accent,
                            placeholder = "number",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.weight(1f),
                        )
                        EditRemoveMark {
                            updateRaw(rawIndex) { r ->
                                r.copy(phones = r.phones.filterIndexed { j, _ -> j != phoneIndex })
                            }
                        }
                    }
                }
                EditAddLink("add phone", accent) {
                    updateRaw(rawIndex) { r ->
                        r.copy(phones = r.phones + EditPhone(null, "", "mobile"))
                    }
                }

                raw.emails.forEachIndexed { emailIndex, email ->
                    EditFieldLabel("email")
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
                            placeholder = "email address",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        WpDropdown(
                            current = ContactEditor.EMAIL_TYPES
                                .firstOrNull { it.second == email.type }?.first ?: "other",
                            options = ContactEditor.EMAIL_TYPES.map { it.first },
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
                        EditRemoveMark {
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
                            placeholder = "custom label",
                        )
                    }
                }
                EditAddLink("add email", accent) {
                    updateRaw(rawIndex) { r ->
                        r.copy(emails = r.emails + EditEmail(null, "", Email.TYPE_HOME))
                    }
                }

                raw.events.forEachIndexed { eventIndex, event ->
                    EditFieldLabel("date")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .weight(1f)
                                .border(2.dp, Metro.Foreground)
                                .clickable {
                                    val cal = Calendar.getInstance()
                                    parseEventDate(event.startDate)?.let { (y, m, d) ->
                                        cal.set(y, m - 1, d)
                                    }
                                    DatePickerDialog(
                                        context,
                                        { _, y, m, day ->
                                            updateRaw(rawIndex) { r ->
                                                r.copy(
                                                    events = r.events.mapIndexed { j, ev ->
                                                        if (j == eventIndex) {
                                                            ev.copy(
                                                                startDate = "%04d-%02d-%02d"
                                                                    .format(y, m + 1, day),
                                                            )
                                                        } else ev
                                                    },
                                                )
                                            }
                                        },
                                        cal.get(Calendar.YEAR),
                                        cal.get(Calendar.MONTH),
                                        cal.get(Calendar.DAY_OF_MONTH),
                                    ).show()
                                }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                        ) {
                            Text(
                                event.startDate.ifEmpty { "pick a date" },
                                color = if (event.startDate.isEmpty()) Metro.Subtle else Metro.Foreground,
                                fontSize = 18.sp,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        WpDropdown(
                            current = ContactEditor.EVENT_TYPES
                                .firstOrNull { it.second == event.type }?.first ?: "other",
                            options = ContactEditor.EVENT_TYPES.map { it.first },
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
                        EditRemoveMark {
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
                            placeholder = "custom label",
                        )
                    }
                }
                EditAddLink("add date", accent) {
                    updateRaw(rawIndex) { r ->
                        r.copy(events = r.events + EditEvent(null, "", Event.TYPE_BIRTHDAY))
                    }
                }

                EditFieldLabel("address")
                MetroTextBox(
                    value = raw.address,
                    onValueChange = { newAddress ->
                        updateRaw(rawIndex) { it.copy(address = newAddress) }
                    },
                    accent = accent,
                    placeholder = "street, city, country",
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                )

                EditFieldLabel("note")
                MetroTextBox(
                    value = raw.note,
                    onValueChange = { newNote ->
                        updateRaw(rawIndex) { it.copy(note = newNote) }
                    },
                    accent = accent,
                    placeholder = "note",
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
                "couldn't save the changes — try again",
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
                if (saving) "saving..." else "save",
                fill = accent,
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) {
                saving = true
                saveFailed = false
                scope.launch {
                    val ok = ContactEditor.save(context, loaded, edited)
                    if (ok) {
                        onDone()
                    } else {
                        saving = false
                        saveFailed = true
                    }
                }
            }
            MetroButton("cancel", modifier = Modifier.weight(1f)) { onDone() }
        }
    }
}

@Composable
private fun EditFieldLabel(text: String) {
    Spacer(Modifier.height(10.dp))
    Text(text, color = Metro.Subtle, fontSize = 14.sp)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun EditAddLink(text: String, accent: Color, onClick: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Text(
        "+ $text",
        color = accent,
        fontSize = 17.sp,
        fontWeight = FontWeight.Normal,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun EditRemoveMark(onClick: () -> Unit) {
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

/** Parses "yyyy-MM-dd" into (year, month 1-12, day), or null. */
private fun parseEventDate(value: String): Triple<Int, Int, Int>? {
    val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(value) ?: return null
    val (y, mo, d) = m.destructured
    return Triple(y.toInt(), mo.toInt(), d.toInt())
}

/**
 * Decodes an image bounded to roughly [maxSide] px using inSampleSize, so an
 * arbitrarily large gallery image can't exhaust memory.
 */
private fun decodeSampledEdit(
    context: android.content.Context,
    uri: Uri,
    maxSide: Int,
): Bitmap? = runCatching {
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

private suspend fun loadEditPhotoBytes(
    context: android.content.Context,
    uri: Uri,
): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        val source = decodeSampledEdit(context, uri, 720) ?: return@withContext null
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
