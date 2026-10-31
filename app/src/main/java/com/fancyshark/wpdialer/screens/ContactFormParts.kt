package com.fancyshark.wpdialer.screens

import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.R
import com.fancyshark.wpdialer.data.ContactAccount
import com.fancyshark.wpdialer.data.EditEmail
import com.fancyshark.wpdialer.data.EditEvent
import com.fancyshark.wpdialer.data.EditPhone
import com.fancyshark.wpdialer.data.EditRawContact
import com.fancyshark.wpdialer.data.EditableContact
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroCloseMark
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Building blocks shared by the new- and edit-contact forms.

/** One typed row of the new-contact form (phone/email/date + type). */
internal data class TypedEntry(
    val value: String,
    val typeIdx: Int,
    val customLabel: String = "",
)

/** Saves a form's typed rows across process death (picker round trips). */
internal val TypedEntryListSaver: Saver<SnapshotStateList<TypedEntry>, Any> = listSaver(
    save = { list -> list.flatMap { listOf(it.value, it.typeIdx, it.customLabel) } },
    restore = { flat ->
        flat.chunked(3).map {
            TypedEntry(it[0] as String, it[1] as Int, it[2] as String)
        }.toMutableStateList()
    },
)

internal val ContactAccountSaver: Saver<ContactAccount?, Any> = listSaver(
    save = { a -> if (a == null) emptyList() else listOf(a.name.orEmpty(), a.type.orEmpty()) },
    restore = { l ->
        if (l.size < 2) null
        else ContactAccount(
            l[0].ifEmpty { null },
            l[1].ifEmpty { null },
        )
    },
)

/**
 * Saves the edit form across process death. Newly picked photo bytes go to
 * a cache file instead of the state bundle — a few hundred KB of JPEG in
 * saved state risks TransactionTooLargeException.
 */
internal fun editableContactSaver(context: android.content.Context): Saver<EditableContact?, Bundle> =
    Saver(
        save = { c ->
            if (c == null) return@Saver Bundle()
            Bundle().apply {
                putLong("id", c.contactId)
                putParcelableArrayList(
                    "raws",
                    ArrayList(c.raws.map { r -> saveRaw(context, r) }),
                )
            }
        },
        restore = { b ->
            if (!b.containsKey("id")) null
            else EditableContact(
                b.getLong("id"),
                b.getParcelableArrayList("raws", Bundle::class.java).orEmpty()
                    .map { restoreRaw(it) },
            )
        },
    )

private fun Bundle.putNullableLong(key: String, v: Long?) {
    if (v != null) putLong(key, v)
}

private fun Bundle.nullableLong(key: String): Long? =
    if (containsKey(key)) getLong(key) else null

private fun saveRaw(context: android.content.Context, r: EditRawContact): Bundle = Bundle().apply {
    putLong("rawId", r.rawContactId)
    putString("accName", r.account.name)
    putString("accType", r.account.type)
    putNullableLong("nameId", r.nameDataId)
    putString("name", r.name)
    putNullableLong("noteId", r.noteDataId)
    putString("note", r.note)
    putNullableLong("addrId", r.addressDataId)
    putString("addr", r.address)
    // The existing photo is a small thumbnail row — fine inline.
    putByteArray("photo", r.photo)
    r.newPhoto?.let { bytes ->
        runCatching {
            val f = java.io.File(context.cacheDir, "edit_photo_${r.rawContactId}.jpg")
            f.writeBytes(bytes)
            putString("newPhoto", f.absolutePath)
        }
    }
    putParcelableArrayList(
        "phones",
        ArrayList(
            r.phones.map { p ->
                Bundle().apply {
                    putNullableLong("id", p.dataId)
                    putString("number", p.number)
                    putString("label", p.label)
                    if (p.type != null) putInt("type", p.type)
                    putString("custom", p.customLabel)
                }
            },
        ),
    )
    putParcelableArrayList(
        "emails",
        ArrayList(
            r.emails.map { e ->
                Bundle().apply {
                    putNullableLong("id", e.dataId)
                    putString("address", e.address)
                    putInt("type", e.type)
                    putString("custom", e.customLabel)
                }
            },
        ),
    )
    putParcelableArrayList(
        "events",
        ArrayList(
            r.events.map { ev ->
                Bundle().apply {
                    putNullableLong("id", ev.dataId)
                    putString("date", ev.startDate)
                    putInt("type", ev.type)
                    putString("custom", ev.customLabel)
                }
            },
        ),
    )
}

private fun restoreRaw(b: Bundle): EditRawContact = EditRawContact(
    rawContactId = b.getLong("rawId"),
    account = ContactAccount(b.getString("accName"), b.getString("accType")),
    nameDataId = b.nullableLong("nameId"),
    name = b.getString("name").orEmpty(),
    phones = b.getParcelableArrayList("phones", Bundle::class.java).orEmpty().map { p ->
        EditPhone(
            p.nullableLong("id"),
            p.getString("number").orEmpty(),
            p.getString("label").orEmpty(),
            if (p.containsKey("type")) p.getInt("type") else null,
            p.getString("custom"),
        )
    },
    noteDataId = b.nullableLong("noteId"),
    note = b.getString("note").orEmpty(),
    emails = b.getParcelableArrayList("emails", Bundle::class.java).orEmpty().map { e ->
        EditEmail(
            e.nullableLong("id"),
            e.getString("address").orEmpty(),
            e.getInt("type"),
            e.getString("custom").orEmpty(),
        )
    },
    events = b.getParcelableArrayList("events", Bundle::class.java).orEmpty().map { ev ->
        EditEvent(
            ev.nullableLong("id"),
            ev.getString("date").orEmpty(),
            ev.getInt("type"),
            ev.getString("custom").orEmpty(),
        )
    },
    addressDataId = b.nullableLong("addrId"),
    address = b.getString("addr").orEmpty(),
    photo = b.getByteArray("photo"),
    newPhoto = b.getString("newPhoto")?.let { path ->
        runCatching { java.io.File(path).readBytes() }.getOrNull()
    },
)

/**
 * Field-by-field form comparison for the discard prompt. Photo arrays
 * compare by reference in data-class equality, so they're judged by
 * "a new photo was picked" instead.
 */
internal fun EditableContact.sameFormAs(other: EditableContact): Boolean =
    raws.size == other.raws.size && raws.zip(other.raws).all { (a, b) ->
        a.copy(photo = null, newPhoto = null) == b.copy(photo = null, newPhoto = null) &&
            (a.newPhoto == null) == (b.newPhoto == null)
    }

/** Machine date for ContactsContract — ASCII digits in every app locale. */
internal fun formatEventDate(year: Int, month1: Int, day: Int): String =
    String.format(Locale.ROOT, "%04d-%02d-%02d", year, month1, day)

/** Parses "yyyy-MM-dd" into (year, month 1-12, day), or null. */
internal fun parseEventDate(value: String): Triple<Int, Int, Int>? {
    val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").find(value) ?: return null
    val (y, mo, d) = m.destructured
    return Triple(y.toInt(), mo.toInt(), d.toInt())
}

@Composable
internal fun FieldLabel(text: String, top: Dp = 14.dp) {
    Spacer(Modifier.height(top))
    Text(text, color = Metro.Subtle, style = Metro.Type.Small)
    Spacer(Modifier.height(4.dp))
}

@Composable
internal fun AddLink(text: String, accent: Color, onClick: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Text(
        "+ $text",
        color = Metro.textAccent(accent),
        style = Metro.Type.Body,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
            ) { onClick() }
            .padding(vertical = 4.dp),
    )
}

/** The row-removing "✕" of a form field. */
@Composable
internal fun RemoveMark(onClick: () -> Unit) {
    MetroCloseMark(stringResource(R.string.ui_remove), onClick = onClick)
}

/** Bordered date box opening the system date picker on the current value. */
@Composable
internal fun DateField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    onPicked: (String) -> Unit,
) {
    val context = LocalContext.current
    Box(
        modifier
            .border(2.dp, Metro.Foreground)
            .clickable(role = Role.Button) {
                val cal = Calendar.getInstance()
                parseEventDate(value)?.let { (y, m, d) -> cal.set(y, m - 1, d) }
                DatePickerDialog(
                    context,
                    { _, y, m, day -> onPicked(formatEventDate(y, m + 1, day)) },
                    cal.get(Calendar.YEAR),
                    cal.get(Calendar.MONTH),
                    cal.get(Calendar.DAY_OF_MONTH),
                ).show()
            }
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Text(
            value.ifEmpty { placeholder },
            color = if (value.isEmpty()) Metro.Subtle else Metro.Foreground,
            style = Metro.Type.Body,
        )
    }
}

/** Accent photo tile: the picked/current photo, or a "+" to add one. */
@Composable
internal fun PhotoTile(
    preview: ImageBitmap?,
    accent: Color,
    changeLabel: String,
    onClick: () -> Unit,
) {
    val addLabel = stringResource(R.string.ui_add_photo)
    Box(
        Modifier
            .size(76.dp)
            .background(accent)
            .clickable(role = Role.Button) { onClick() }
            .semantics { contentDescription = if (preview != null) changeLabel else addLabel },
        contentAlignment = Alignment.Center,
    ) {
        if (preview != null) {
            Image(
                preview,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(76.dp),
            )
        } else {
            Text(
                "+",
                color = Metro.onAccent(accent),
                style = Metro.Type.Header,
            )
        }
    }
}

/** WP-style dropdown: white select box that opens a menu of options. */
@Composable
fun WpDropdown(
    current: String,
    options: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .border(2.dp, Metro.Foreground)
                .clickable(role = Role.DropdownList) { expanded = true }
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                current,
                color = Metro.Foreground,
                style = Metro.Type.Small,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("▾", color = Metro.Foreground, style = Metro.Type.Small)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, option ->
                DropdownMenuItem(
                    text = { Text(option, style = Metro.Type.Small) },
                    onClick = {
                        expanded = false
                        onSelect(i)
                    },
                )
            }
        }
    }
}

/** Decodes contact photo bytes for preview off the main thread. */
internal suspend fun decodePhotoPreview(bytes: ByteArray?): ImageBitmap? =
    if (bytes == null) null
    else withContext(Dispatchers.IO) {
        runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }?.asImageBitmap()

/**
 * Decodes an image bounded to roughly [maxSide] px using inSampleSize, so an
 * arbitrarily large gallery image can't exhaust memory.
 */
internal fun decodeSampled(context: android.content.Context, uri: Uri, maxSide: Int): Bitmap? =
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

/** A picked gallery image as ≤720px JPEG bytes, ready for the photo row. */
internal suspend fun loadPhotoBytes(context: android.content.Context, uri: Uri): ByteArray? =
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
