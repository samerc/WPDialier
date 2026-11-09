package com.fancyshark.wpdialer.screens

import android.app.DatePickerDialog
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.R
import com.fancyshark.wpdialer.data.ContactAccount
import com.fancyshark.wpdialer.data.EditAddress
import com.fancyshark.wpdialer.data.EditEmail
import com.fancyshark.wpdialer.data.EditEvent
import com.fancyshark.wpdialer.data.EditIm
import com.fancyshark.wpdialer.data.EditName
import com.fancyshark.wpdialer.data.EditPhone
import com.fancyshark.wpdialer.data.EditRawContact
import com.fancyshark.wpdialer.data.EditRelation
import com.fancyshark.wpdialer.data.EditValue
import com.fancyshark.wpdialer.data.EditableContact
import com.fancyshark.wpdialer.data.EventDate
import com.fancyshark.wpdialer.data.EventDates
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroCloseMark
import java.io.ByteArrayOutputStream
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Building blocks shared by the new- and edit-contact forms.

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
 * Saves the contact form across process death (photo/map/location round
 * trips can cost the process). Rows travel as one JSON string; newly picked
 * photo bytes go to a cache file instead of the state bundle — a few
 * hundred KB of JPEG in saved state risks TransactionTooLargeException.
 */
internal fun editableContactSaver(context: android.content.Context): Saver<EditableContact?, Bundle> =
    Saver(
        save = { c ->
            if (c == null) return@Saver Bundle()
            Bundle().apply {
                putLong("id", c.contactId)
                putStringArrayList("readOnly", ArrayList(c.readOnlyAccounts))
                putString("raws", JSONArray().apply { c.raws.forEach { put(rawToJson(it)) } }.toString())
                c.raws.forEachIndexed { i, r ->
                    // The existing photo is a small thumbnail row — fine inline.
                    putByteArray("photo$i", r.photo)
                    r.newPhoto?.let { bytes ->
                        runCatching {
                            val f = java.io.File(context.cacheDir, "edit_photo_${r.rawContactId}.jpg")
                            f.writeBytes(bytes)
                            putString("newPhoto$i", f.absolutePath)
                        }
                    }
                }
            }
        },
        restore = { b ->
            if (!b.containsKey("id")) null
            else runCatching {
                val arr = JSONArray(b.getString("raws") ?: "[]")
                EditableContact(
                    b.getLong("id"),
                    (0 until arr.length()).map { i ->
                        rawFromJson(arr.getJSONObject(i)).copy(
                            photo = b.getByteArray("photo$i"),
                            newPhoto = b.getString("newPhoto$i")?.let { path ->
                                runCatching { java.io.File(path).readBytes() }.getOrNull()
                            },
                        )
                    },
                    b.getStringArrayList("readOnly").orEmpty(),
                )
            }.getOrNull()
        },
    )

private fun JSONObject.putId(key: String, v: Long?): JSONObject = apply { if (v != null) put(key, v) }
private fun JSONObject.id(key: String): Long? = if (has(key)) getLong(key) else null
private fun JSONObject.str(key: String): String = optString(key, "")
private fun <T> JSONObject.list(key: String, read: (JSONObject) -> T): List<T> {
    val arr = optJSONArray(key) ?: return emptyList()
    return (0 until arr.length()).map { read(arr.getJSONObject(it)) }
}
private fun <T> JSONObject.putList(key: String, items: List<T>, write: (T) -> JSONObject): JSONObject =
    apply { put(key, JSONArray().apply { items.forEach { put(write(it)) } }) }

private fun rawToJson(r: EditRawContact): JSONObject = JSONObject().apply {
    put("rawId", r.rawContactId)
    if (r.account.name != null) put("accName", r.account.name)
    if (r.account.type != null) put("accType", r.account.type)
    putId("nameId", r.nameDataId)
    put(
        "name",
        JSONObject().apply {
            put("prefix", r.name.prefix); put("given", r.name.given); put("middle", r.name.middle)
            put("family", r.name.family); put("suffix", r.name.suffix)
            put("pGiven", r.name.phoneticGiven); put("pFamily", r.name.phoneticFamily)
            put("display", r.name.display)
        },
    )
    putId("noteId", r.noteDataId); put("note", r.note)
    putId("nickId", r.nicknameDataId); put("nick", r.nickname)
    putId("orgId", r.orgDataId); put("company", r.company); put("title", r.jobTitle)
    putList("phones", r.phones) { p ->
        JSONObject().putId("id", p.dataId).apply {
            put("number", p.number); put("label", p.label)
            if (p.type != null) put("type", p.type)
            if (p.customLabel != null) put("custom", p.customLabel)
            put("default", p.isDefault)
        }
    }
    putList("emails", r.emails) { e ->
        JSONObject().putId("id", e.dataId).apply {
            put("v", e.address); put("type", e.type); put("custom", e.customLabel)
        }
    }
    putList("events", r.events) { e ->
        JSONObject().putId("id", e.dataId).apply {
            put("v", e.startDate); put("type", e.type); put("custom", e.customLabel)
        }
    }
    putList("addresses", r.addresses) { a ->
        JSONObject().putId("id", a.dataId).apply {
            put("v", a.formatted); put("type", a.type); put("custom", a.customLabel)
            put("parts", JSONObject().apply { a.parts.forEach { (k, v) -> put(k, v) } })
        }
    }
    putList("relations", r.relations) { x ->
        JSONObject().putId("id", x.dataId).apply {
            put("v", x.name); put("type", x.type); put("custom", x.customLabel)
        }
    }
    putList("ims", r.ims) { x ->
        JSONObject().putId("id", x.dataId).apply {
            put("v", x.handle); put("protocol", x.protocol); put("custom", x.customProtocol)
            put("type", x.type)
        }
    }
    putList("websites", r.websites) { x ->
        JSONObject().putId("id", x.dataId).apply { put("v", x.value); put("type", x.type) }
    }
    putList("sips", r.sips) { x ->
        JSONObject().putId("id", x.dataId).apply { put("v", x.value); put("type", x.type) }
    }
    put("groups", JSONArray().apply { r.groupIds.forEach { put(it) } })
    put("newGroups", JSONArray().apply { r.newGroups.forEach { put(it) } })
}

private fun rawFromJson(o: JSONObject): EditRawContact {
    val n = o.optJSONObject("name") ?: JSONObject()
    val groups = o.optJSONArray("groups")
    val newGroups = o.optJSONArray("newGroups")
    return EditRawContact(
        rawContactId = o.getLong("rawId"),
        account = ContactAccount(
            if (o.has("accName")) o.getString("accName") else null,
            if (o.has("accType")) o.getString("accType") else null,
        ),
        nameDataId = o.id("nameId"),
        name = EditName(
            n.str("prefix"), n.str("given"), n.str("middle"), n.str("family"), n.str("suffix"),
            n.str("pGiven"), n.str("pFamily"), n.str("display"),
        ),
        phones = o.list("phones") { p ->
            EditPhone(
                p.id("id"), p.str("number"), p.str("label"),
                if (p.has("type")) p.getInt("type") else null,
                if (p.has("custom")) p.getString("custom") else null,
                p.optBoolean("default", false),
            )
        },
        noteDataId = o.id("noteId"),
        note = o.str("note"),
        emails = o.list("emails") { EditEmail(it.id("id"), it.str("v"), it.getInt("type"), it.str("custom")) },
        events = o.list("events") { EditEvent(it.id("id"), it.str("v"), it.getInt("type"), it.str("custom")) },
        addresses = o.list("addresses") {
            val parts = it.optJSONObject("parts")
            EditAddress(
                it.id("id"), it.str("v"), it.getInt("type"), it.str("custom"),
                parts?.keys()?.asSequence()?.associateWith { k -> parts.getString(k) }.orEmpty(),
            )
        },
        nicknameDataId = o.id("nickId"),
        nickname = o.str("nick"),
        orgDataId = o.id("orgId"),
        company = o.str("company"),
        jobTitle = o.str("title"),
        websites = o.list("websites") { EditValue(it.id("id"), it.str("v"), it.getInt("type")) },
        relations = o.list("relations") {
            EditRelation(it.id("id"), it.str("v"), it.getInt("type"), it.str("custom"))
        },
        sips = o.list("sips") { EditValue(it.id("id"), it.str("v"), it.getInt("type")) },
        ims = o.list("ims") {
            EditIm(it.id("id"), it.str("v"), it.getInt("protocol"), it.str("custom"), it.getInt("type"))
        },
        groupIds = if (groups == null) emptySet()
        else (0 until groups.length()).map { groups.getLong(it) }.toSet(),
        newGroups = if (newGroups == null) emptyList()
        else (0 until newGroups.length()).map { newGroups.getString(it) },
    )
}

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

/** "March 4, 1993" / "March 4" in the app locale; the raw value when unparseable. */
internal fun eventDateDisplay(value: String): String {
    val d = EventDates.parse(value) ?: return value
    val locale = java.util.Locale.getDefault()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(
        locale, if (d.year == null) "MMMMd" else "yMMMMd",
    )
    return runCatching {
        java.text.SimpleDateFormat(pattern, locale)
            .format(java.util.GregorianCalendar(d.year ?: 2000, d.month - 1, d.day).time)
    }.getOrDefault(value)
}

/** Small subtle field caption ("website", "company"). */
@Composable
internal fun FieldLabel(text: String, top: Dp = 14.dp) {
    Spacer(Modifier.height(top))
    Text(text, color = Metro.Subtle, style = Metro.Type.Small)
    Spacer(Modifier.height(4.dp))
}

/** Lowercase light section header in the subtle color ("phone", "more"). */
@Composable
internal fun FormSectionHeader(text: String) {
    Text(
        text,
        color = Metro.Subtle,
        style = Metro.Type.SubHeader,
        modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
    )
}

/** Small subtle hint line under a field group. */
@Composable
internal fun HintLine(text: String, top: Dp = 6.dp) {
    Text(
        text,
        color = Metro.Subtle,
        style = Metro.Type.Small,
        fontWeight = FontWeight.Light,
        modifier = Modifier.padding(top = top),
    )
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

/** "›" pointing toward the end edge (mirrored for RTL). */
@Composable
internal fun chevron(): String =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) "‹" else "›"

/**
 * Color square marking which account a field saves to: Google blue,
 * Exchange/Outlook blue, grey for local phone storage, others hashed onto
 * a fixed palette so each account type keeps a stable color.
 */
internal fun fieldAccountColor(account: ContactAccount): Color {
    val t = account.type?.lowercase() ?: return LOCAL_GREY
    return when {
        t.contains("google") -> Color(0xFF2F6FDE)
        t.contains("exchange") || t.contains("outlook") ||
            t.contains("microsoft") || t.contains("office") -> Color(0xFF0A64B8)
        t.contains("local") || t.endsWith(".phone") || t.contains("localphone") -> LOCAL_GREY
        else -> HASH_PALETTE[Math.floorMod(t.hashCode(), HASH_PALETTE.size)]
    }
}

private val LOCAL_GREY = Color(0xFF8A8A8A)
private val HASH_PALETTE = listOf(
    Color(0xFF00ABA9), Color(0xFFFA6800), Color(0xFFD80073), Color(0xFF008A00),
    Color(0xFFAA00FF), Color(0xFFF0A30A), Color(0xFFA20025), Color(0xFF6D8764),
)

/** The account square; [description] is read by TalkBack ("saved to Google"). */
@Composable
internal fun FieldAccountSquare(account: ContactAccount, size: Dp = 10.dp, description: String? = null) {
    Box(
        Modifier
            .size(size)
            .background(fieldAccountColor(account))
            .then(
                if (description != null) {
                    Modifier.semantics { contentDescription = description }
                } else {
                    Modifier
                },
            ),
    )
}

/** Metro checkbox: 2dp square, accent fill + check when on. Pair with Modifier.toggleable. */
@Composable
internal fun MetroCheckbox(checked: Boolean, accent: Color) {
    Box(
        Modifier
            .size(24.dp)
            .border(2.dp, if (checked) accent else Metro.Foreground)
            .background(if (checked) accent else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", color = Metro.onAccent(accent), fontSize = 16.sp)
    }
}

/**
 * Fires [onLongPress] when a pointer rests in place for the long-press
 * timeout, observing in the Initial pass without consuming — so it works
 * over a text field (which keeps its own handling) and never blocks
 * scrolling: moving past touch slop cancels it.
 */
internal fun Modifier.observeLongPress(key: Any?, onLongPress: () -> Unit): Modifier =
    pointerInput(key) {
        val timeout = viewConfiguration.longPressTimeoutMillis
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var cancelled = false
            val ended = withTimeoutOrNull(timeout) {
                var done = false
                while (!done) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) {
                        done = true
                    } else if ((change.position - down.position).getDistance() > slop) {
                        cancelled = true
                        done = true
                    }
                }
                true
            }
            if (ended == null && !cancelled) onLongPress()
        }
    }

/**
 * Bordered date box opening the system date picker on the current value.
 * The picker's neutral "no year" button stores a yearless "--MM-dd" date.
 */
@Composable
internal fun DateField(
    value: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    onPicked: (String) -> Unit,
) {
    val context = LocalContext.current
    val noYear = stringResource(R.string.cform_no_year)
    Box(
        modifier
            .heightIn(min = 46.dp)
            .border(2.dp, Metro.Foreground)
            .clickable(role = Role.Button) {
                // Yearless dates open on a leap seed year (Feb 29 survives) and
                // stay yearless unless the user moves the year off it.
                val today = Calendar.getInstance()
                val start = EventDates.pickerStart(value)
                val dialog = DatePickerDialog(
                    context,
                    { _, y, m, day -> onPicked(EventDates.picked(value, y, m + 1, day)) },
                    start?.year ?: today.get(Calendar.YEAR),
                    start?.let { it.month - 1 } ?: today.get(Calendar.MONTH),
                    start?.day ?: today.get(Calendar.DAY_OF_MONTH),
                )
                dialog.setButton(DialogInterface.BUTTON_NEUTRAL, noYear) { _, _ ->
                    val picker = dialog.datePicker
                    onPicked(EventDates.format(EventDate(null, picker.month + 1, picker.dayOfMonth)))
                }
                dialog.show()
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            if (value.isEmpty()) placeholder else eventDateDisplay(value),
            color = if (value.isEmpty()) Metro.Subtle else Metro.Foreground,
            style = Metro.Type.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Accent photo tile: the picked/current photo, or the contact's initials;
 * a small "change"/"add photo" caption sits under them.
 */
@Composable
internal fun PhotoTile(
    preview: ImageBitmap?,
    accent: Color,
    initials: String,
    changeLabel: String,
    onClick: () -> Unit,
) {
    val addLabel = stringResource(R.string.ui_add_photo)
    val onAccent = Metro.onAccent(accent)
    Box(
        Modifier
            .size(110.dp)
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
                modifier = Modifier.size(110.dp),
            )
            Text(
                changeLabel,
                color = Color.White,
                style = Metro.Type.Tiny,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(vertical = 3.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    initials.ifEmpty { "+" },
                    color = onAccent,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Light,
                )
                Text(
                    if (initials.isEmpty()) addLabel else changeLabel,
                    color = onAccent,
                    style = Metro.Type.Tiny,
                )
            }
        }
    }
}

/** Up to two initials of [name] ("Alice Martin" -> "AM"). */
internal fun initialsOf(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }
        .take(2).joinToString("").uppercase()

/** WP-style dropdown: bordered select box that opens a menu of options. */
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
                .heightIn(min = 46.dp)
                .border(2.dp, Metro.Foreground)
                .clickable(role = Role.DropdownList) { expanded = true }
                .padding(horizontal = 10.dp, vertical = 10.dp),
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
