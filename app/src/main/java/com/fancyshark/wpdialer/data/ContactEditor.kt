package com.fancyshark.wpdialer.data

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Im
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.Relation
import android.provider.ContactsContract.CommonDataKinds.SipAddress
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sync account a contact (raw contact) is stored in. A null [type] means the
 * contact lives in local phone storage. Google contacts sync through the
 * Google account; OneDrive/Outlook contacts sync through the Microsoft
 * account added on the device.
 */
data class ContactAccount(val name: String?, val type: String?) {

    val kindLabel: String
        get() = when {
            type == null -> "phone"
            type.contains("google", true) -> "Google"
            type.contains("outlook", true) ||
                type.contains("microsoft", true) ||
                type.contains("office", true) -> "Outlook / OneDrive"
            type.contains("exchange", true) -> "Exchange"
            type.contains("sim", true) -> "SIM"
            else -> type.substringAfterLast('.')
        }

    val fullLabel: String
        get() = if (name.isNullOrBlank()) kindLabel else "$kindLabel · $name"
}

/** The field kinds the editor can add ("+ add another field"). */
enum class FieldKind { PHONE, EMAIL, ADDRESS, DATE, WEBSITE, RELATION, SIP, IM, NICKNAME, WORK, NOTE, LABELS }

/** Outcome of a contact save: [PHOTO_FAILED] = everything but the photo was written. */
enum class SaveResult { SAVED, PHOTO_FAILED, FAILED }

/**
 * StructuredName parts. [display] is DISPLAY_NAME as loaded; edits clear it
 * so the provider re-joins the parts (locale-aware ordering) on save.
 */
data class EditName(
    val prefix: String = "",
    val given: String = "",
    val middle: String = "",
    val family: String = "",
    val suffix: String = "",
    val phoneticGiven: String = "",
    val phoneticFamily: String = "",
    val display: String = "",
) {
    /** Western-order preview of the parts ("Dr. Alice Martin, Jr."). */
    fun composed(): String {
        val base = listOf(prefix, given, middle, family)
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val sfx = suffix.trim()
        return when {
            sfx.isEmpty() -> base
            base.isEmpty() -> sfx
            else -> "$base, $sfx"
        }
    }

    /** What the name box shows: the stored display name, else the parts. */
    val shown: String get() = display.trim().ifEmpty { composed() }

    val phonetic: String
        get() = listOf(phoneticGiven, phoneticFamily)
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    val isBlank: Boolean get() = shown.isEmpty() && phonetic.isEmpty()

    /** Same parts, ignoring the loaded display string. */
    fun sameParts(other: EditName): Boolean = copy(display = "") == other.copy(display = "")
}

/**
 * [label] is the display string ("mobile", "work", a custom label). [type] /
 * [customLabel] are what save writes: null type keeps the row's current type
 * (TYPE_MOBILE for new rows); a non-blank [customLabel] with a null or
 * TYPE_CUSTOM type saves as TYPE_CUSTOM with that LABEL. [isDefault] is the
 * contact's default number for calls (IS_SUPER_PRIMARY).
 */
data class EditPhone(
    val dataId: Long?,
    val number: String,
    val label: String,
    val type: Int? = null,
    val customLabel: String? = null,
    val isDefault: Boolean = false,
)

data class EditEmail(
    val dataId: Long?,
    val address: String,
    val type: Int,
    val customLabel: String = "",
)

/** [startDate] is "yyyy-MM-dd", or "--MM-dd" when the year is unknown. */
data class EditEvent(
    val dataId: Long?,
    val startDate: String,
    val type: Int,
    val customLabel: String = "",
)

/**
 * [parts] are the row's structured columns as loaded (StructuredPostal
 * column -> value, blanks left out). The editor only edits [formatted];
 * the parts are carried so a text edit can keep them (see planChanges).
 */
data class EditAddress(
    val dataId: Long?,
    val formatted: String,
    val type: Int = StructuredPostal.TYPE_HOME,
    val customLabel: String = "",
    val parts: Map<String, String> = emptyMap(),
)

/** The StructuredPostal columns split out of FORMATTED_ADDRESS. */
internal val POSTAL_PARTS = listOf(
    StructuredPostal.STREET,
    StructuredPostal.POBOX,
    StructuredPostal.NEIGHBORHOOD,
    StructuredPostal.CITY,
    StructuredPostal.REGION,
    StructuredPostal.POSTCODE,
    StructuredPostal.COUNTRY,
)

data class EditRelation(
    val dataId: Long?,
    val name: String,
    val type: Int,
    val customLabel: String = "",
)

/** Chat handle; [type] (home/work/other) is kept as loaded, never edited. */
data class EditIm(
    val dataId: Long?,
    val handle: String,
    val protocol: Int,
    val customProtocol: String = "",
    val type: Int = Im.TYPE_OTHER,
)

/** One-value rows (website, SIP address); [type] is kept as loaded. */
data class EditValue(val dataId: Long?, val value: String, val type: Int)

data class EditRawContact(
    val rawContactId: Long,
    val account: ContactAccount,
    val nameDataId: Long?,
    val name: EditName,
    val phones: List<EditPhone>,
    val noteDataId: Long? = null,
    val note: String = "",
    val emails: List<EditEmail> = emptyList(),
    val events: List<EditEvent> = emptyList(),
    val addresses: List<EditAddress> = emptyList(),
    /** Existing photo bytes (thumbnail from the data row), for preview only. */
    val photo: ByteArray? = null,
    /** Newly picked photo bytes; non-null means "replace the photo on save". */
    val newPhoto: ByteArray? = null,
    val nicknameDataId: Long? = null,
    val nickname: String = "",
    val orgDataId: Long? = null,
    val company: String = "",
    val jobTitle: String = "",
    val websites: List<EditValue> = emptyList(),
    val relations: List<EditRelation> = emptyList(),
    val sips: List<EditValue> = emptyList(),
    val ims: List<EditIm> = emptyList(),
    /** Group (label) row ids this raw contact belongs to. */
    val groupIds: Set<Long> = emptySet(),
    /** Labels to create in this raw contact's account and join on save. */
    val newGroups: List<String> = emptyList(),
) {
    val hasOrg: Boolean get() = orgDataId != null || company.isNotBlank() || jobTitle.isNotBlank()
    val hasNote: Boolean get() = noteDataId != null || note.isNotBlank()
    val hasNickname: Boolean get() = nicknameDataId != null || nickname.isNotBlank()
    val hasName: Boolean get() = nameDataId != null || !name.isBlank
    val hasPhoto: Boolean get() = photo != null || newPhoto != null
    val hasLabels: Boolean get() = groupIds.isNotEmpty() || newGroups.isNotEmpty()
}

/**
 * [readOnlyAccounts] are kind labels of the raw contacts left out because
 * their accounts can't be written (WhatsApp, Telegram, ...).
 */
data class EditableContact(
    val contactId: Long,
    val raws: List<EditRawContact>,
    val readOnlyAccounts: List<String> = emptyList(),
)

/** A user label (contact group) of one account. */
data class ContactGroup(val id: Long, val title: String)

/** A contact date split apart; [year] null = yearless ("--MM-dd"). */
data class EventDate(val year: Int?, val month: Int, val day: Int)

/** ContactsContract START_DATE parsing/formatting — ASCII digits in every locale. */
object EventDates {
    private val yearless = Regex("""^--(\d{2})-?(\d{2})$""")
    private val dashed = Regex("""^(\d{4})-(\d{2})-(\d{2})""")
    private val compact = Regex("""^(\d{4})(\d{2})(\d{2})$""")

    fun parse(value: String): EventDate? {
        val v = value.trim()
        yearless.find(v)?.let { m ->
            return valid(null, m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        (dashed.find(v) ?: compact.find(v))?.let { m ->
            return valid(
                m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(),
            )
        }
        return null
    }

    fun format(date: EventDate): String =
        if (date.year == null) {
            String.format(Locale.ROOT, "--%02d-%02d", date.month, date.day)
        } else {
            String.format(Locale.ROOT, "%04d-%02d-%02d", date.year, date.month, date.day)
        }

    /**
     * Year the date picker opens on for a yearless date: a leap year, so a
     * yearless Feb 29 isn't rolled to Mar 1 by the (lenient) calendar.
     */
    const val PICKER_SEED_YEAR = 2000

    /** Where the picker opens for [value]; null = today (empty or unparseable). */
    fun pickerStart(value: String): EventDate? {
        val d = parse(value) ?: return null
        val year = d.year ?: return d.copy(year = PICKER_SEED_YEAR)
        // A stored Feb 29 in a common year would roll over too.
        val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        return if (d.month == 2 && d.day == 29 && !leap) d.copy(day = 28) else d
    }

    /**
     * The value stored for an OK press on the picker opened on [original]:
     * a yearless original stays yearless while the year is left on the
     * seed; any other year the user picks is stored.
     */
    fun picked(original: String, year: Int, month: Int, day: Int): String {
        val o = parse(original)
        val wasYearless = o != null && o.year == null
        val y = if (wasYearless && year == PICKER_SEED_YEAR) null else year
        return format(EventDate(y, month, day))
    }

    private val monthDays = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    private fun valid(year: Int?, month: Int, day: Int): EventDate? =
        if (month in 1..12 && day in 1..monthDays[month - 1]) EventDate(year, month, day) else null
}

/**
 * One planned data-row write of a raw contact, kept free of Android types
 * so the diff is unit-testable. Values map ContactsContract column names.
 */
sealed interface RowChange {
    data class Insert(val mime: String, val values: Map<String, Any?>) : RowChange
    data class Update(val dataId: Long, val values: Map<String, Any?>) : RowChange
    data class Delete(val dataId: Long) : RowChange

    /** Deletes this raw contact's [mime] rows whose [column] equals [value] (all rows when null). */
    data class DeleteWhere(val mime: String, val column: String? = null, val value: String? = null) : RowChange

    /** Creates a label titled [title] in the raw contact's account and joins it. */
    data class JoinNewGroup(val title: String) : RowChange
}

/** Toggles the default number for calls: one phone across the whole contact, or none. */
fun EditableContact.withDefaultPhone(rawIndex: Int, phoneIndex: Int): EditableContact {
    val target = raws.getOrNull(rawIndex)?.phones?.getOrNull(phoneIndex) ?: return this
    val makeDefault = !target.isDefault
    return copy(
        raws = raws.mapIndexed { i, r ->
            r.copy(
                phones = r.phones.mapIndexed { j, p ->
                    val want = makeDefault && i == rawIndex && j == phoneIndex
                    if (p.isDefault == want) p else p.copy(isDefault = want)
                },
            )
        },
    )
}

/** The contact's default number for calls, if one is set. */
fun EditableContact.defaultPhone(): EditPhone? =
    raws.asSequence().flatMap { it.phones.asSequence() }.firstOrNull { it.isDefault }

/**
 * Applies a contact-level edit (name, nickname, photo): every raw contact
 * that already [has] the field gets it, else only raw contact [target].
 */
fun EditableContact.withContactWide(
    target: Int,
    has: (EditRawContact) -> Boolean,
    apply: (EditRawContact) -> EditRawContact,
): EditableContact {
    if (raws.isEmpty()) return this
    val holders = raws.indices.filter { has(raws[it]) }
        .ifEmpty { listOf(target.coerceIn(raws.indices)) }
    return copy(raws = raws.mapIndexed { i, r -> if (i in holders) apply(r) else r })
}

/**
 * Default raw contact for new fields: the first sync account (Google,
 * Exchange, ...), else local phone storage, and a SIM card only as a last
 * resort — SIMs can't store most fields.
 */
fun EditableContact.preferredTarget(): Int {
    fun rank(a: ContactAccount): Int = when {
        ContactEditor.isSim(a) -> 2
        ContactEditor.isLocal(a) -> 1
        else -> 0
    }
    return raws.indices.minByOrNull { rank(raws[it].account) } ?: 0
}

/**
 * Field kinds holding non-blank input that an account limited to [kinds]
 * can't store, in form order. Labels are left out: they belong to one
 * account and are dropped on any account change anyway.
 */
fun EditRawContact.unsupportedFilled(kinds: Set<FieldKind>): List<FieldKind> {
    val filled = linkedMapOf(
        FieldKind.WORK to (company.isNotBlank() || jobTitle.isNotBlank()),
        FieldKind.PHONE to phones.any { it.number.isNotBlank() },
        FieldKind.EMAIL to emails.any { it.address.isNotBlank() },
        FieldKind.ADDRESS to addresses.any { it.formatted.isNotBlank() },
        FieldKind.DATE to events.any { it.startDate.isNotBlank() },
        FieldKind.WEBSITE to websites.any { it.value.isNotBlank() },
        FieldKind.RELATION to relations.any { it.name.isNotBlank() },
        FieldKind.SIP to sips.any { it.value.isNotBlank() },
        FieldKind.IM to ims.any { it.handle.isNotBlank() },
        FieldKind.NICKNAME to nickname.isNotBlank(),
        FieldKind.NOTE to note.isNotBlank(),
    )
    return filled.filter { (kind, has) -> has && kind !in kinds }.keys.toList()
}

/** This raw contact without the fields an account limited to [kinds] can't store. */
fun EditRawContact.keepingOnly(kinds: Set<FieldKind>): EditRawContact {
    fun has(k: FieldKind) = k in kinds
    return copy(
        company = if (has(FieldKind.WORK)) company else "",
        jobTitle = if (has(FieldKind.WORK)) jobTitle else "",
        phones = if (has(FieldKind.PHONE)) phones else emptyList(),
        emails = if (has(FieldKind.EMAIL)) emails else emptyList(),
        addresses = if (has(FieldKind.ADDRESS)) addresses else emptyList(),
        events = if (has(FieldKind.DATE)) events else emptyList(),
        websites = if (has(FieldKind.WEBSITE)) websites else emptyList(),
        relations = if (has(FieldKind.RELATION)) relations else emptyList(),
        sips = if (has(FieldKind.SIP)) sips else emptyList(),
        ims = if (has(FieldKind.IM)) ims else emptyList(),
        nickname = if (has(FieldKind.NICKNAME)) nickname else "",
        note = if (has(FieldKind.NOTE)) note else "",
        groupIds = if (has(FieldKind.LABELS)) groupIds else emptySet(),
        newGroups = if (has(FieldKind.LABELS)) newGroups else emptyList(),
    )
}

object ContactEditor {

    suspend fun loadEditable(context: Context, contactId: Long): EditableContact? =
        withContext(Dispatchers.IO) {
            val raws = mutableListOf<EditRawContact>()
            val readOnly = LinkedHashSet<String>()
            val adapters = contactSyncAdapters()
            runCatching {
                context.contentResolver.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.RawContacts._ID,
                        ContactsContract.RawContacts.ACCOUNT_NAME,
                        ContactsContract.RawContacts.ACCOUNT_TYPE,
                    ),
                    "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
                    arrayOf(contactId.toString()),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val account = ContactAccount(c.getString(1), c.getString(2))
                        // Never surface raw contacts owned by read-only sync
                        // accounts (WhatsApp, Telegram, ...) — editing them
                        // fights their sync adapters and gets reverted.
                        if (isReadOnly(account.type, adapters)) {
                            readOnly += account.kindLabel
                            continue
                        }
                        raws += loadRawContact(context, c.getLong(0), account)
                    }
                }
            }
            if (raws.isEmpty()) null else EditableContact(contactId, raws, readOnly.toList())
        }

    private fun loadRawContact(
        context: Context,
        rawContactId: Long,
        account: ContactAccount,
    ): EditRawContact {
        var raw = EditRawContact(rawContactId, account, null, EditName(), emptyList())
        val phones = mutableListOf<EditPhone>()
        val emails = mutableListOf<EditEmail>()
        val events = mutableListOf<EditEvent>()
        val addresses = mutableListOf<EditAddress>()
        val websites = mutableListOf<EditValue>()
        val relations = mutableListOf<EditRelation>()
        val sips = mutableListOf<EditValue>()
        val ims = mutableListOf<EditIm>()
        val groups = LinkedHashSet<Long>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA4,
                ContactsContract.Data.DATA5,
                ContactsContract.Data.DATA6,
                ContactsContract.Data.DATA7,
                ContactsContract.Data.DATA8,
                ContactsContract.Data.DATA9,
                ContactsContract.Data.DATA15,
                ContactsContract.Data.IS_SUPER_PRIMARY,
                // After the flag so the DATAn = n + 1 indexing above holds.
                ContactsContract.Data.DATA10,
            ),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
            arrayOf(rawContactId.toString()),
            null,
        )?.use { c ->
            // DATAn sits at column n + 1.
            fun s(n: Int): String = c.getString(n + 1) ?: ""
            fun i(n: Int, fallback: Int): Int = if (c.isNull(n + 1)) fallback else c.getInt(n + 1)
            while (c.moveToNext()) {
                val id = c.getLong(0)
                when (c.getString(1)) {
                    StructuredName.CONTENT_ITEM_TYPE -> if (raw.nameDataId == null) {
                        raw = raw.copy(
                            nameDataId = id,
                            name = EditName(
                                prefix = s(4), given = s(2), middle = s(5), family = s(3),
                                suffix = s(6), phoneticGiven = s(7), phoneticFamily = s(9),
                                display = s(1),
                            ),
                        )
                    }
                    Phone.CONTENT_ITEM_TYPE -> {
                        val type = if (c.isNull(3)) null else c.getInt(3)
                        val label = Phone.getTypeLabel(
                            context.resources, type ?: Phone.TYPE_MOBILE, c.getString(4),
                        ).toString().lowercase()
                        phones += EditPhone(
                            id, s(1), label, type,
                            if (type == Phone.TYPE_CUSTOM) c.getString(4) else null,
                            isDefault = !c.isNull(12) && c.getInt(12) != 0,
                        )
                    }
                    Email.CONTENT_ITEM_TYPE ->
                        emails += EditEmail(id, s(1), i(2, Email.TYPE_OTHER), s(3))
                    Event.CONTENT_ITEM_TYPE ->
                        events += EditEvent(id, s(1), i(2, Event.TYPE_OTHER), s(3))
                    StructuredPostal.CONTENT_ITEM_TYPE -> {
                        // STREET..POSTCODE are DATA4..DATA9; COUNTRY (DATA10) is column 13.
                        val parts = linkedMapOf<String, String>()
                        POSTAL_PARTS.forEachIndexed { k, col ->
                            val v = (if (k < 6) c.getString(k + 5) else c.getString(13))?.trim()
                            if (!v.isNullOrEmpty()) parts[col] = v
                        }
                        addresses += EditAddress(id, s(1), i(2, StructuredPostal.TYPE_HOME), s(3), parts)
                    }
                    Website.CONTENT_ITEM_TYPE ->
                        websites += EditValue(id, s(1), i(2, Website.TYPE_OTHER))
                    Relation.CONTENT_ITEM_TYPE ->
                        relations += EditRelation(id, s(1), i(2, Relation.TYPE_CUSTOM), s(3))
                    SipAddress.CONTENT_ITEM_TYPE ->
                        sips += EditValue(id, s(1), i(2, SipAddress.TYPE_OTHER))
                    Im.CONTENT_ITEM_TYPE ->
                        ims += EditIm(id, s(1), i(5, Im.PROTOCOL_CUSTOM), s(6), i(2, Im.TYPE_OTHER))
                    GroupMembership.CONTENT_ITEM_TYPE ->
                        if (!c.isNull(2)) groups += c.getLong(2)
                    Organization.CONTENT_ITEM_TYPE -> if (raw.orgDataId == null) {
                        raw = raw.copy(orgDataId = id, company = s(1), jobTitle = s(4))
                    }
                    Nickname.CONTENT_ITEM_TYPE -> if (raw.nicknameDataId == null) {
                        raw = raw.copy(nicknameDataId = id, nickname = s(1))
                    }
                    Photo.CONTENT_ITEM_TYPE -> if (raw.photo == null) {
                        raw = raw.copy(photo = runCatching { c.getBlob(11) }.getOrNull())
                    }
                    // The editor exposes one note per raw contact; extra rows stay untouched.
                    Note.CONTENT_ITEM_TYPE -> if (raw.noteDataId == null) {
                        raw = raw.copy(noteDataId = id, note = s(1))
                    }
                }
            }
        }
        return raw.copy(
            phones = phones, emails = emails, events = events, addresses = addresses,
            websites = websites, relations = relations, sips = sips, ims = ims,
            groupIds = groups,
        )
    }

    /** A raw contact with nothing in it — the "before" side of a new contact. */
    fun blankRaw(account: ContactAccount, rawContactId: Long = -1L): EditRawContact =
        EditRawContact(rawContactId, account, null, EditName(), emptyList())

    // ---- Diff (pure) --------------------------------------------------

    /**
     * Every row write that turns [orig] into [edited] (the same raw contact).
     * Updates carry only the columns that changed, so untouched structured
     * parts (postal city, phone legacy type, ...) are never rewritten.
     */
    internal fun planChanges(orig: EditRawContact, edited: EditRawContact): List<RowChange> {
        val out = mutableListOf<RowChange>()

        // The provider rebuilds DISPLAY_NAME only when an update carries a
        // structured part, so a written name row always carries ALL parts
        // (+ phonetic) with a null display — a phonetic-only edit must not
        // null the display name.
        diffSingle(
            out, StructuredName.CONTENT_ITEM_TYPE, edited.nameDataId,
            if (edited.name.sameParts(orig.name) && edited.name.display == orig.name.display) {
                null
            } else {
                nameValues(orig.name) to nameValues(edited.name)
            },
            newBlank = edited.name.isBlank,
            wholeRow = true,
        )
        diffSingle(
            out, Nickname.CONTENT_ITEM_TYPE, edited.nicknameDataId,
            if (edited.nickname == orig.nickname) null
            else mapOf<String, Any?>(Nickname.NAME to orig.nickname.ifBlank { null }) to
                mapOf<String, Any?>(Nickname.NAME to edited.nickname.ifBlank { null }),
            newBlank = edited.nickname.isBlank(),
            insertExtras = mapOf(Nickname.TYPE to Nickname.TYPE_DEFAULT),
        )
        diffSingle(
            out, Organization.CONTENT_ITEM_TYPE, edited.orgDataId,
            if (edited.company == orig.company && edited.jobTitle == orig.jobTitle) null
            else orgValues(orig) to orgValues(edited),
            newBlank = edited.company.isBlank() && edited.jobTitle.isBlank(),
            insertExtras = mapOf(Organization.TYPE to Organization.TYPE_WORK),
        )

        diffList(
            out, Phone.CONTENT_ITEM_TYPE, orig.phones, edited.phones,
            id = { it.dataId }, blank = { it.number.isBlank() }, values = ::phoneValues,
            insertValues = { p ->
                val v = LinkedHashMap(phoneValues(p))
                if (!v.containsKey(Phone.TYPE)) {
                    v[Phone.TYPE] = Phone.TYPE_MOBILE
                    v[Phone.LABEL] = null
                }
                if (!p.isDefault) {
                    v.remove(ContactsContract.Data.IS_PRIMARY)
                    v.remove(ContactsContract.Data.IS_SUPER_PRIMARY)
                }
                v
            },
        )
        diffList(
            out, Email.CONTENT_ITEM_TYPE, orig.emails, edited.emails,
            id = { it.dataId }, blank = { it.address.isBlank() },
            values = { e ->
                mapOf(
                    Email.ADDRESS to e.address,
                    Email.TYPE to e.type,
                    Email.LABEL to customLabel(e.type == Email.TYPE_CUSTOM, e.customLabel),
                )
            },
        )
        // Address text vs structured parts. An update carrying only
        // FORMATTED_ADDRESS makes the provider re-split it (all text into
        // STREET, city/postcode/country nulled). So:
        //  - text unchanged (type/label edits): text and parts untouched;
        //  - text changed and it still contains every loaded part (a typo
        //    fixed elsewhere): the loaded parts are written back with it;
        //  - otherwise (really rewritten): every part is written as null,
        //    and the provider splits the new text itself.
        diffList(
            out, StructuredPostal.CONTENT_ITEM_TYPE, orig.addresses, edited.addresses,
            // An address stored only as parts (no formatted text) isn't blank.
            id = { it.dataId },
            blank = { it.formatted.isBlank() && it.parts.values.all { v -> v.isBlank() } },
            values = { a ->
                mapOf(
                    StructuredPostal.FORMATTED_ADDRESS to a.formatted,
                    StructuredPostal.TYPE to a.type,
                    StructuredPostal.LABEL to
                        customLabel(a.type == StructuredPostal.TYPE_CUSTOM, a.customLabel),
                )
            },
            update = { before, after, changed ->
                if (StructuredPostal.FORMATTED_ADDRESS !in changed) {
                    changed
                } else {
                    changed + postalParts(before.parts, after.formatted)
                }
            },
        )
        diffList(
            out, Event.CONTENT_ITEM_TYPE, orig.events, edited.events,
            id = { it.dataId }, blank = { it.startDate.isBlank() },
            values = { ev ->
                mapOf(
                    Event.START_DATE to ev.startDate,
                    Event.TYPE to ev.type,
                    Event.LABEL to customLabel(ev.type == Event.TYPE_CUSTOM, ev.customLabel),
                )
            },
        )
        diffList(
            out, Website.CONTENT_ITEM_TYPE, orig.websites, edited.websites,
            id = { it.dataId }, blank = { it.value.isBlank() },
            values = { w -> mapOf(Website.URL to w.value, Website.TYPE to w.type) },
        )
        diffList(
            out, Relation.CONTENT_ITEM_TYPE, orig.relations, edited.relations,
            id = { it.dataId }, blank = { it.name.isBlank() },
            values = { r ->
                mapOf(
                    Relation.NAME to r.name,
                    Relation.TYPE to r.type,
                    Relation.LABEL to customLabel(r.type == Relation.TYPE_CUSTOM, r.customLabel),
                )
            },
        )
        diffList(
            out, SipAddress.CONTENT_ITEM_TYPE, orig.sips, edited.sips,
            id = { it.dataId }, blank = { it.value.isBlank() },
            values = { s -> mapOf(SipAddress.SIP_ADDRESS to s.value, SipAddress.TYPE to s.type) },
        )
        diffList(
            out, Im.CONTENT_ITEM_TYPE, orig.ims, edited.ims,
            id = { it.dataId }, blank = { it.handle.isBlank() },
            values = { im ->
                mapOf(
                    Im.DATA to im.handle,
                    Im.TYPE to im.type,
                    Im.PROTOCOL to im.protocol,
                    Im.CUSTOM_PROTOCOL to customLabel(im.protocol == Im.PROTOCOL_CUSTOM, im.customProtocol),
                )
            },
        )

        diffSingle(
            out, Note.CONTENT_ITEM_TYPE, edited.noteDataId,
            if (edited.note == orig.note) null
            else mapOf<String, Any?>(Note.NOTE to orig.note) to mapOf<String, Any?>(Note.NOTE to edited.note),
            newBlank = edited.note.isBlank(),
        )

        (edited.groupIds - orig.groupIds).forEach { gid ->
            out += RowChange.Insert(
                GroupMembership.CONTENT_ITEM_TYPE,
                mapOf(GroupMembership.GROUP_ROW_ID to gid),
            )
        }
        (orig.groupIds - edited.groupIds).forEach { gid ->
            out += RowChange.DeleteWhere(
                GroupMembership.CONTENT_ITEM_TYPE, GroupMembership.GROUP_ROW_ID, gid.toString(),
            )
        }
        edited.newGroups.map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .forEach { out += RowChange.JoinNewGroup(it) }

        // Photo replacement: drop any existing photo rows on this raw
        // contact, then insert the newly picked bytes as the contact's
        // super-primary photo (so the aggregate shows it over other
        // accounts' photos). save/create apply these in a separate batch.
        if (edited.newPhoto != null) {
            out += RowChange.DeleteWhere(Photo.CONTENT_ITEM_TYPE)
            out += RowChange.Insert(
                Photo.CONTENT_ITEM_TYPE,
                mapOf(
                    Photo.PHOTO to edited.newPhoto,
                    ContactsContract.Data.IS_PRIMARY to 1,
                    ContactsContract.Data.IS_SUPER_PRIMARY to 1,
                ),
            )
        }
        // Keyboards leave trailing spaces after suggestions — never store them.
        return out.map { c ->
            when (c) {
                is RowChange.Insert -> c.copy(values = c.values.trimmed())
                is RowChange.Update -> c.copy(values = c.values.trimmed())
                else -> c
            }
        }
    }

    private fun Map<String, Any?>.trimmed(): Map<String, Any?> =
        mapValues { (_, v) -> if (v is String) v.trim() else v }

    private fun customLabel(isCustom: Boolean, label: String): String? =
        if (isCustom) label.ifBlank { "custom" } else null

    /**
     * Postal parts written with a changed address text: the loaded [parts]
     * when [text] still contains each of them (case-insensitive), else all
     * null. Every part column is always present, so nothing stale survives.
     */
    internal fun postalParts(parts: Map<String, String>, text: String): Map<String, Any?> {
        val keep = parts.isNotEmpty() && parts.values.all { text.contains(it.trim(), ignoreCase = true) }
        return POSTAL_PARTS.associateWith { col -> if (keep) parts[col] else null }
    }

    private fun nameValues(n: EditName): Map<String, Any?> = linkedMapOf(
        // Null display + every part: the provider joins the parts itself.
        StructuredName.DISPLAY_NAME to null,
        StructuredName.PREFIX to n.prefix.trim().ifEmpty { null },
        StructuredName.GIVEN_NAME to n.given.trim().ifEmpty { null },
        StructuredName.MIDDLE_NAME to n.middle.trim().ifEmpty { null },
        StructuredName.FAMILY_NAME to n.family.trim().ifEmpty { null },
        StructuredName.SUFFIX to n.suffix.trim().ifEmpty { null },
        StructuredName.PHONETIC_GIVEN_NAME to n.phoneticGiven.trim().ifEmpty { null },
        StructuredName.PHONETIC_FAMILY_NAME to n.phoneticFamily.trim().ifEmpty { null },
    )

    private fun orgValues(r: EditRawContact): Map<String, Any?> = mapOf(
        Organization.COMPANY to r.company.ifBlank { null },
        Organization.TITLE to r.jobTitle.ifBlank { null },
    )

    /** NUMBER, TYPE/LABEL (only when a type is known) and the default flags. */
    private fun phoneValues(p: EditPhone): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>(Phone.NUMBER to p.number)
        val custom = p.customLabel?.takeIf { it.isNotBlank() }
        if (p.type != null || custom != null) {
            val type = p.type ?: Phone.TYPE_CUSTOM
            m[Phone.TYPE] = type
            m[Phone.LABEL] = if (type == Phone.TYPE_CUSTOM) custom ?: "custom" else null
        }
        val flag = if (p.isDefault) 1 else 0
        m[ContactsContract.Data.IS_PRIMARY] = flag
        m[ContactsContract.Data.IS_SUPER_PRIMARY] = flag
        return m
    }

    /**
     * Single-row field: [values] is (before, after) or null when unchanged.
     * Blank after = delete the row; no row yet = insert. [wholeRow] updates
     * write every column of after, not only the changed ones.
     */
    private fun diffSingle(
        out: MutableList<RowChange>,
        mime: String,
        dataId: Long?,
        values: Pair<Map<String, Any?>, Map<String, Any?>>?,
        newBlank: Boolean,
        insertExtras: Map<String, Any?> = emptyMap(),
        wholeRow: Boolean = false,
    ) {
        val (before, after) = values ?: return
        when {
            dataId != null && newBlank -> out += RowChange.Delete(dataId)
            dataId != null -> (if (wholeRow) after else changedColumns(before, after))
                .takeIf { it.isNotEmpty() }
                ?.let { out += RowChange.Update(dataId, it) }
            !newBlank -> out += RowChange.Insert(mime, after.filterValues { it != null } + insertExtras)
        }
    }

    private fun <T> diffList(
        out: MutableList<RowChange>,
        mime: String,
        orig: List<T>,
        edited: List<T>,
        id: (T) -> Long?,
        blank: (T) -> Boolean,
        values: (T) -> Map<String, Any?>,
        insertValues: (T) -> Map<String, Any?> = values,
        /** Final update columns from (before, after, changed columns); non-empty changed only. */
        update: (T, T, Map<String, Any?>) -> Map<String, Any?> = { _, _, changed -> changed },
    ) {
        edited.forEach { row ->
            val dataId = id(row)
            if (dataId == null) {
                if (!blank(row)) out += RowChange.Insert(mime, insertValues(row))
            } else {
                val before = orig.firstOrNull { id(it) == dataId } ?: return@forEach
                if (blank(row)) {
                    out += RowChange.Delete(dataId)
                } else {
                    changedColumns(values(before), values(row)).takeIf { it.isNotEmpty() }
                        ?.let { out += RowChange.Update(dataId, update(before, row, it)) }
                }
            }
        }
        // Rows removed in the editor.
        orig.forEach { before ->
            val dataId = id(before) ?: return@forEach
            if (edited.none { id(it) == dataId }) out += RowChange.Delete(dataId)
        }
    }

    private fun changedColumns(before: Map<String, Any?>, after: Map<String, Any?>): Map<String, Any?> =
        after.filter { (k, v) -> !before.containsKey(k) || before[k] != v }

    // ---- Writes -------------------------------------------------------

    /**
     * Applies the diff between [original] and [edited]. Every change targets
     * the data row of its own raw contact, so each edit is written back to
     * the account (Google, OneDrive, phone, ...) the contact came from.
     */
    suspend fun save(context: Context, original: EditableContact, edited: EditableContact): SaveResult =
        withContext(Dispatchers.IO) {
            val ops = arrayListOf<ContentProviderOperation>()
            val photoOps = arrayListOf<ContentProviderOperation>()
            edited.raws.forEach { raw ->
                val orig = original.raws.firstOrNull { it.rawContactId == raw.rawContactId }
                    ?: return@forEach
                val (photo, rest) = planChanges(orig, raw).partition { isPhotoChange(it) }
                appendOps(ops, rest, raw.account, rawId = raw.rawContactId)
                appendOps(photoOps, photo, raw.account, rawId = raw.rawContactId)
            }
            val saved = ops.isEmpty() || runCatching {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            }.isSuccess
            val result = when {
                !saved -> SaveResult.FAILED
                else -> applyPhotoOps(context, photoOps)
            }
            Repo.invalidateCaches()
            result
        }

    /** Inserts [raw] as a brand-new raw contact in its account. */
    suspend fun create(context: Context, raw: EditRawContact): SaveResult =
        withContext(Dispatchers.IO) {
            val ops = arrayListOf<ContentProviderOperation>()
            ops += ContentProviderOperation
                .newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, raw.account.name)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, raw.account.type)
                .build()
            val (photo, rest) = planChanges(blankRaw(raw.account), raw).partition { isPhotoChange(it) }
            appendOps(ops, rest, raw.account, rawBackRef = 0)
            val rawId = runCatching {
                val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                results.firstOrNull()?.uri?.let { android.content.ContentUris.parseId(it) }
            }.getOrNull()
            val result = when {
                rawId == null -> SaveResult.FAILED
                photo.isEmpty() -> SaveResult.SAVED
                else -> {
                    val photoOps = arrayListOf<ContentProviderOperation>()
                    appendOps(photoOps, photo, raw.account, rawId = rawId)
                    applyPhotoOps(context, photoOps)
                }
            }
            Repo.invalidateCaches()
            result
        }

    /**
     * Photo rows go in their own batch after the main one: a large photo
     * can exceed the binder limit, and that must not lose the other edits.
     */
    private fun applyPhotoOps(context: Context, photoOps: ArrayList<ContentProviderOperation>): SaveResult =
        if (photoOps.isEmpty()) {
            SaveResult.SAVED
        } else if (runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, photoOps) }.isSuccess) {
            SaveResult.SAVED
        } else {
            SaveResult.PHOTO_FAILED
        }

    private fun isPhotoChange(c: RowChange): Boolean = when (c) {
        is RowChange.Insert -> c.mime == Photo.CONTENT_ITEM_TYPE
        is RowChange.DeleteWhere -> c.mime == Photo.CONTENT_ITEM_TYPE
        else -> false
    }

    /** Turns planned [changes] into provider ops for an existing ([rawId]) or new ([rawBackRef]) raw contact. */
    private fun appendOps(
        ops: ArrayList<ContentProviderOperation>,
        changes: List<RowChange>,
        account: ContactAccount,
        rawId: Long? = null,
        rawBackRef: Int? = null,
    ) {
        fun insert(mime: String): ContentProviderOperation.Builder {
            val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            if (rawId != null) {
                b.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            } else {
                b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawBackRef ?: 0)
            }
            return b.withValue(ContactsContract.Data.MIMETYPE, mime)
        }
        changes.forEach { change ->
            when (change) {
                is RowChange.Insert -> {
                    val b = insert(change.mime)
                    change.values.forEach { (k, v) -> b.withValue(k, v) }
                    ops += b.build()
                }
                is RowChange.Update -> {
                    val b = ContentProviderOperation
                        .newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(change.dataId.toString()))
                    change.values.forEach { (k, v) -> b.withValue(k, v) }
                    ops += b.build()
                }
                is RowChange.Delete -> ops += ContentProviderOperation
                    .newDelete(ContactsContract.Data.CONTENT_URI)
                    .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(change.dataId.toString()))
                    .build()
                is RowChange.DeleteWhere -> {
                    // A new raw contact has nothing to delete yet.
                    if (rawId == null) return@forEach
                    val where = StringBuilder(
                        "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    )
                    val args = arrayListOf(rawId.toString(), change.mime)
                    if (change.column != null) {
                        where.append(" AND ${change.column} = ?")
                        args += change.value.orEmpty()
                    }
                    ops += ContentProviderOperation
                        .newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection(where.toString(), args.toTypedArray())
                        .build()
                }
                is RowChange.JoinNewGroup -> {
                    val groupIndex = ops.size
                    ops += ContentProviderOperation
                        .newInsert(ContactsContract.Groups.CONTENT_URI)
                        .withValue(ContactsContract.Groups.TITLE, change.title)
                        .withValue(ContactsContract.Groups.ACCOUNT_NAME, account.name)
                        .withValue(ContactsContract.Groups.ACCOUNT_TYPE, account.type)
                        .withValue(ContactsContract.Groups.GROUP_VISIBLE, 1)
                        .build()
                    ops += insert(GroupMembership.CONTENT_ITEM_TYPE)
                        .withValueBackReference(GroupMembership.GROUP_ROW_ID, groupIndex)
                        .build()
                }
            }
        }
    }

    // ---- Accounts, labels, capabilities --------------------------------

    /**
     * User labels of [account]: not deleted, not read-only, and neither the
     * auto-add "My Contacts" group nor the starred group — those are
     * system bookkeeping, and the editor never touches their membership.
     */
    suspend fun listGroups(context: Context, account: ContactAccount): List<ContactGroup> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<ContactGroup>()
            val type = account.type
            val name = account.name
            val accountWhere: String
            val args: Array<String>
            when {
                type == null -> {
                    accountWhere = "${ContactsContract.Groups.ACCOUNT_TYPE} IS NULL"
                    args = emptyArray()
                }
                name == null -> {
                    accountWhere = "${ContactsContract.Groups.ACCOUNT_TYPE} = ? AND " +
                        "${ContactsContract.Groups.ACCOUNT_NAME} IS NULL"
                    args = arrayOf(type)
                }
                else -> {
                    accountWhere = "${ContactsContract.Groups.ACCOUNT_TYPE} = ? AND " +
                        "${ContactsContract.Groups.ACCOUNT_NAME} = ?"
                    args = arrayOf(type, name)
                }
            }
            runCatching {
                context.contentResolver.query(
                    ContactsContract.Groups.CONTENT_URI,
                    arrayOf(ContactsContract.Groups._ID, ContactsContract.Groups.TITLE),
                    "$accountWhere AND ${ContactsContract.Groups.DELETED} = 0" +
                        " AND ${ContactsContract.Groups.AUTO_ADD} = 0" +
                        " AND ${ContactsContract.Groups.FAVORITES} = 0" +
                        " AND ${ContactsContract.Groups.GROUP_IS_READ_ONLY} = 0",
                    args,
                    "${ContactsContract.Groups.TITLE} COLLATE NOCASE ASC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        out += ContactGroup(c.getLong(0), title)
                    }
                }
            }
            out
        }

    private val ALL_KINDS = FieldKind.entries.toSet()
    private val SIM_TYPE = Regex("""(^|[._])u?sim($|[._])""")

    /** A SIM card "account" (vnd.sec.contact.sim, com.android.sim, ...). */
    fun isSim(account: ContactAccount): Boolean =
        account.type?.lowercase()?.let { SIM_TYPE.containsMatchIn(it) } == true

    /** Local phone storage: no account, or an OEM local-phone type. */
    fun isLocal(account: ContactAccount): Boolean {
        val t = account.type?.lowercase() ?: return true
        return t.contains("local") || t.endsWith(".phone")
    }

    /**
     * Field kinds [account] can store, by account-type heuristics: SIM
     * cards hold numbers (and sometimes an email); Exchange/Outlook drop
     * relationships, SIP addresses and labels. Everything else (Google,
     * local phone storage, unknown adapters) is offered every kind — the
     * per-adapter contacts.xml schema isn't parsed.
     */
    fun supportedKinds(account: ContactAccount): Set<FieldKind> {
        val t = account.type?.lowercase() ?: return ALL_KINDS
        return when {
            // Before "google": Gmail's own Exchange type is com.google.android.gm.exchange.
            t.contains("exchange") || t.contains("outlook") ||
                t.contains("microsoft") || t.contains("office") ->
                ALL_KINDS - setOf(FieldKind.RELATION, FieldKind.SIP, FieldKind.LABELS)
            t.contains("google") -> ALL_KINDS
            SIM_TYPE.containsMatchIn(t) -> setOf(FieldKind.PHONE, FieldKind.EMAIL)
            else -> ALL_KINDS
        }
    }

    /**
     * Account types owned by messaging apps that expose read-only contact
     * lists; hidden from the "save to" picker. Fallback for adapters that
     * (wrongly) claim upload support.
     */
    private val READ_ONLY_ACCOUNT_MARKERS = listOf(
        "whatsapp", "telegram", "viber", "signal", "threema", "skype",
        "org.thoughtcrime", "com.facebook", "linkedin", "tachyon",
    )

    /** Contacts-authority sync adapters: account type -> supports uploading. */
    private fun contactSyncAdapters(): Map<String, Boolean> = runCatching {
        android.content.ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == ContactsContract.AUTHORITY }
            // Several adapters per type: writable if any of them uploads.
            .groupBy { it.accountType }
            .mapValues { (_, list) -> list.any { it.supportsUploading() } }
    }.getOrDefault(emptyMap())

    /**
     * Read-only when the account's contacts sync adapter can't upload (the
     * platform's own signal), or a known messaging app. Types without any
     * adapter (local/OEM phone storage) count as writable.
     */
    private fun isReadOnly(type: String?, adapters: Map<String, Boolean>): Boolean {
        if (type == null) return false
        if (adapters[type] == false) return true
        val lower = type.lowercase()
        return READ_ONLY_ACCOUNT_MARKERS.any { lower.contains(it) }
    }

    /**
     * Accounts a new contact can be saved to: local phone storage, every
     * device account with a writable contacts sync adapter (even one that
     * holds no contacts yet), plus any writable account already seen in raw
     * contacts (adapter-less OEM types).
     */
    suspend fun listAccounts(context: Context): List<ContactAccount> =
        withContext(Dispatchers.IO) {
            val adapters = contactSyncAdapters()
            val accounts = LinkedHashSet<ContactAccount>()
            accounts += ContactAccount(null, null)
            runCatching {
                // Contacts-syncing account types are visible without
                // GET_ACCOUNTS to an app holding READ_CONTACTS.
                android.accounts.AccountManager.get(context).accounts.forEach { a ->
                    if (adapters[a.type] == true && !isReadOnly(a.type, adapters)) {
                        accounts += ContactAccount(a.name, a.type)
                    }
                }
            }
            runCatching {
                context.contentResolver.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.RawContacts.ACCOUNT_NAME,
                        ContactsContract.RawContacts.ACCOUNT_TYPE,
                    ),
                    "${ContactsContract.RawContacts.DELETED} = 0",
                    null,
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val type = c.getString(1) ?: continue
                        if (isReadOnly(type, adapters)) continue
                        accounts += ContactAccount(c.getString(0), type)
                    }
                }
            }
            accounts.toList()
        }

    // ---- Type tables (keys are English data; display via typeKeyLabel) --

    val PHONE_TYPES = listOf(
        "mobile" to Phone.TYPE_MOBILE,
        "home" to Phone.TYPE_HOME,
        "work" to Phone.TYPE_WORK,
        "main" to Phone.TYPE_MAIN,
        "other" to Phone.TYPE_OTHER,
        "custom" to Phone.TYPE_CUSTOM,
    )

    val EMAIL_TYPES = listOf(
        "personal" to Email.TYPE_HOME,
        "work" to Email.TYPE_WORK,
        "other" to Email.TYPE_OTHER,
        "custom" to Email.TYPE_CUSTOM,
    )

    val EVENT_TYPES = listOf(
        "birthday" to Event.TYPE_BIRTHDAY,
        "anniversary" to Event.TYPE_ANNIVERSARY,
        "other" to Event.TYPE_OTHER,
        "custom" to Event.TYPE_CUSTOM,
    )

    val ADDRESS_TYPES = listOf(
        "home" to StructuredPostal.TYPE_HOME,
        "work" to StructuredPostal.TYPE_WORK,
        "other" to StructuredPostal.TYPE_OTHER,
        "custom" to StructuredPostal.TYPE_CUSTOM,
    )

    val RELATION_TYPES = listOf(
        "spouse" to Relation.TYPE_SPOUSE,
        "partner" to Relation.TYPE_PARTNER,
        "domestic partner" to Relation.TYPE_DOMESTIC_PARTNER,
        "parent" to Relation.TYPE_PARENT,
        "mother" to Relation.TYPE_MOTHER,
        "father" to Relation.TYPE_FATHER,
        "child" to Relation.TYPE_CHILD,
        "sister" to Relation.TYPE_SISTER,
        "brother" to Relation.TYPE_BROTHER,
        "relative" to Relation.TYPE_RELATIVE,
        "friend" to Relation.TYPE_FRIEND,
        "manager" to Relation.TYPE_MANAGER,
        "assistant" to Relation.TYPE_ASSISTANT,
        "referred by" to Relation.TYPE_REFERRED_BY,
        "custom" to Relation.TYPE_CUSTOM,
    )

    /** Chat protocols (deprecated constants, still the stored values). */
    @Suppress("DEPRECATION")
    val IM_PROTOCOLS = listOf(
        "skype" to Im.PROTOCOL_SKYPE,
        "jabber" to Im.PROTOCOL_JABBER,
        "qq" to Im.PROTOCOL_QQ,
        "icq" to Im.PROTOCOL_ICQ,
        "aim" to Im.PROTOCOL_AIM,
        "yahoo" to Im.PROTOCOL_YAHOO,
        "custom" to Im.PROTOCOL_CUSTOM,
    )
}
