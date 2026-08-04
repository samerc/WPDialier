package com.fancyshark.wpdialer.data

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
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

data class EditPhone(val dataId: Long?, val number: String, val label: String)

data class EditEmail(
    val dataId: Long?,
    val address: String,
    val type: Int,
    val customLabel: String = "",
)

data class EditEvent(
    val dataId: Long?,
    val startDate: String,
    val type: Int,
    val customLabel: String = "",
)

data class EditRawContact(
    val rawContactId: Long,
    val account: ContactAccount,
    val nameDataId: Long?,
    val name: String,
    val phones: List<EditPhone>,
    val noteDataId: Long? = null,
    val note: String = "",
    val emails: List<EditEmail> = emptyList(),
    val events: List<EditEvent> = emptyList(),
    val addressDataId: Long? = null,
    val address: String = "",
    /** Existing photo bytes (thumbnail from the data row), for preview only. */
    val photo: ByteArray? = null,
    /** Newly picked photo bytes; non-null means "replace the photo on save". */
    val newPhoto: ByteArray? = null,
)

data class EditableContact(val contactId: Long, val raws: List<EditRawContact>)

object ContactEditor {

    suspend fun loadEditable(context: Context, contactId: Long): EditableContact? =
        withContext(Dispatchers.IO) {
            val raws = mutableListOf<EditRawContact>()
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
                        raws += loadRawContact(
                            context,
                            rawContactId = c.getLong(0),
                            account = ContactAccount(c.getString(1), c.getString(2)),
                        )
                    }
                }
            }
            if (raws.isEmpty()) null else EditableContact(contactId, raws)
        }

    private fun loadRawContact(
        context: Context,
        rawContactId: Long,
        account: ContactAccount,
    ): EditRawContact {
        var nameDataId: Long? = null
        var name = ""
        var noteDataId: Long? = null
        var note = ""
        var addressDataId: Long? = null
        var address = ""
        var photo: ByteArray? = null
        val phones = mutableListOf<EditPhone>()
        val emails = mutableListOf<EditEmail>()
        val events = mutableListOf<EditEvent>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA15,
            ),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ?",
            arrayOf(rawContactId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                when (c.getString(1)) {
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        nameDataId = c.getLong(0)
                        name = c.getString(2) ?: ""
                    }
                    Phone.CONTENT_ITEM_TYPE -> {
                        val label = Phone.getTypeLabel(
                            context.resources, c.getInt(3), c.getString(4),
                        ).toString().lowercase()
                        phones += EditPhone(c.getLong(0), c.getString(2) ?: "", label)
                    }
                    Email.CONTENT_ITEM_TYPE -> {
                        emails += EditEmail(
                            c.getLong(0),
                            c.getString(2) ?: "",
                            if (c.isNull(3)) Email.TYPE_OTHER else c.getInt(3),
                            c.getString(4) ?: "",
                        )
                    }
                    Event.CONTENT_ITEM_TYPE -> {
                        events += EditEvent(
                            c.getLong(0),
                            c.getString(2) ?: "",
                            if (c.isNull(3)) Event.TYPE_OTHER else c.getInt(3),
                            c.getString(4) ?: "",
                        )
                    }
                    StructuredPostal.CONTENT_ITEM_TYPE -> {
                        // The editor exposes a single formatted address per raw
                        // contact; keep the first row and leave others untouched.
                        if (addressDataId == null) {
                            addressDataId = c.getLong(0)
                            address = c.getString(2) ?: ""
                        }
                    }
                    Photo.CONTENT_ITEM_TYPE -> {
                        if (photo == null) photo = runCatching { c.getBlob(5) }.getOrNull()
                    }
                    Note.CONTENT_ITEM_TYPE -> {
                        noteDataId = c.getLong(0)
                        note = c.getString(2) ?: ""
                    }
                }
            }
        }
        return EditRawContact(
            rawContactId, account, nameDataId, name, phones, noteDataId, note,
            emails, events, addressDataId, address, photo,
        )
    }

    /**
     * Applies the diff between [original] and [edited]. Every change targets
     * the data row of its own raw contact, so each edit is written back to
     * the account (Google, OneDrive, phone, ...) the contact came from.
     */
    suspend fun save(context: Context, original: EditableContact, edited: EditableContact): Boolean =
        withContext(Dispatchers.IO) {
            val ops = arrayListOf<ContentProviderOperation>()
            edited.raws.forEach { raw ->
                val orig = original.raws.firstOrNull { it.rawContactId == raw.rawContactId }
                    ?: return@forEach

                if (raw.name != orig.name) {
                    if (raw.nameDataId != null) {
                        ops += ContentProviderOperation
                            .newUpdate(ContactsContract.Data.CONTENT_URI)
                            .withSelection(
                                "${ContactsContract.Data._ID} = ?",
                                arrayOf(raw.nameDataId.toString()),
                            )
                            .withValue(StructuredName.DISPLAY_NAME, raw.name)
                            .build()
                    } else if (raw.name.isNotBlank()) {
                        ops += ContentProviderOperation
                            .newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValue(ContactsContract.Data.RAW_CONTACT_ID, raw.rawContactId)
                            .withValue(
                                ContactsContract.Data.MIMETYPE,
                                StructuredName.CONTENT_ITEM_TYPE,
                            )
                            .withValue(StructuredName.DISPLAY_NAME, raw.name)
                            .build()
                    }
                }

                raw.phones.forEach { phone ->
                    if (phone.dataId == null) {
                        if (phone.number.isNotBlank()) {
                            ops += ContentProviderOperation
                                .newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValue(ContactsContract.Data.RAW_CONTACT_ID, raw.rawContactId)
                                .withValue(
                                    ContactsContract.Data.MIMETYPE,
                                    Phone.CONTENT_ITEM_TYPE,
                                )
                                .withValue(Phone.NUMBER, phone.number)
                                .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                                .build()
                        }
                    } else {
                        val origPhone = orig.phones.firstOrNull { it.dataId == phone.dataId }
                        if (phone.number.isBlank()) {
                            ops += deleteDataRow(phone.dataId)
                        } else if (origPhone != null && origPhone.number != phone.number) {
                            ops += ContentProviderOperation
                                .newUpdate(ContactsContract.Data.CONTENT_URI)
                                .withSelection(
                                    "${ContactsContract.Data._ID} = ?",
                                    arrayOf(phone.dataId.toString()),
                                )
                                .withValue(Phone.NUMBER, phone.number)
                                .build()
                        }
                    }
                }

                // Phones removed in the editor.
                orig.phones.forEach { origPhone ->
                    if (origPhone.dataId != null &&
                        raw.phones.none { it.dataId == origPhone.dataId }
                    ) {
                        ops += deleteDataRow(origPhone.dataId)
                    }
                }

                if (raw.note != orig.note) {
                    when {
                        raw.noteDataId != null && raw.note.isBlank() ->
                            ops += deleteDataRow(raw.noteDataId)
                        raw.noteDataId != null ->
                            ops += ContentProviderOperation
                                .newUpdate(ContactsContract.Data.CONTENT_URI)
                                .withSelection(
                                    "${ContactsContract.Data._ID} = ?",
                                    arrayOf(raw.noteDataId.toString()),
                                )
                                .withValue(Note.NOTE, raw.note)
                                .build()
                        raw.note.isNotBlank() ->
                            ops += ContentProviderOperation
                                .newInsert(ContactsContract.Data.CONTENT_URI)
                                .withValue(ContactsContract.Data.RAW_CONTACT_ID, raw.rawContactId)
                                .withValue(ContactsContract.Data.MIMETYPE, Note.CONTENT_ITEM_TYPE)
                                .withValue(Note.NOTE, raw.note)
                                .build()
                    }
                }

                raw.emails.forEach { email ->
                    val label =
                        if (email.type == Email.TYPE_CUSTOM) email.customLabel.ifBlank { "custom" }
                        else null
                    if (email.dataId == null) {
                        if (email.address.isNotBlank()) {
                            ops += insertRow(raw.rawContactId, Email.CONTENT_ITEM_TYPE)
                                .withValue(Email.ADDRESS, email.address)
                                .withValue(Email.TYPE, email.type)
                                .withValue(Email.LABEL, label)
                                .build()
                        }
                    } else {
                        val origEmail = orig.emails.firstOrNull { it.dataId == email.dataId }
                        if (email.address.isBlank()) {
                            ops += deleteDataRow(email.dataId)
                        } else if (origEmail != null &&
                            (origEmail.address != email.address ||
                                origEmail.type != email.type ||
                                origEmail.customLabel != email.customLabel)
                        ) {
                            ops += updateDataRow(email.dataId)
                                .withValue(Email.ADDRESS, email.address)
                                .withValue(Email.TYPE, email.type)
                                .withValue(Email.LABEL, label)
                                .build()
                        }
                    }
                }

                // Emails removed in the editor.
                orig.emails.forEach { origEmail ->
                    if (origEmail.dataId != null &&
                        raw.emails.none { it.dataId == origEmail.dataId }
                    ) {
                        ops += deleteDataRow(origEmail.dataId)
                    }
                }

                raw.events.forEach { event ->
                    val label =
                        if (event.type == Event.TYPE_CUSTOM) event.customLabel.ifBlank { "custom" }
                        else null
                    if (event.dataId == null) {
                        if (event.startDate.isNotBlank()) {
                            ops += insertRow(raw.rawContactId, Event.CONTENT_ITEM_TYPE)
                                .withValue(Event.START_DATE, event.startDate)
                                .withValue(Event.TYPE, event.type)
                                .withValue(Event.LABEL, label)
                                .build()
                        }
                    } else {
                        val origEvent = orig.events.firstOrNull { it.dataId == event.dataId }
                        if (event.startDate.isBlank()) {
                            ops += deleteDataRow(event.dataId)
                        } else if (origEvent != null &&
                            (origEvent.startDate != event.startDate ||
                                origEvent.type != event.type ||
                                origEvent.customLabel != event.customLabel)
                        ) {
                            ops += updateDataRow(event.dataId)
                                .withValue(Event.START_DATE, event.startDate)
                                .withValue(Event.TYPE, event.type)
                                .withValue(Event.LABEL, label)
                                .build()
                        }
                    }
                }

                // Dates removed in the editor.
                orig.events.forEach { origEvent ->
                    if (origEvent.dataId != null &&
                        raw.events.none { it.dataId == origEvent.dataId }
                    ) {
                        ops += deleteDataRow(origEvent.dataId)
                    }
                }

                if (raw.address != orig.address) {
                    when {
                        raw.addressDataId != null && raw.address.isBlank() ->
                            ops += deleteDataRow(raw.addressDataId)
                        raw.addressDataId != null ->
                            ops += updateDataRow(raw.addressDataId)
                                .withValue(StructuredPostal.FORMATTED_ADDRESS, raw.address)
                                .build()
                        raw.address.isNotBlank() ->
                            ops += insertRow(raw.rawContactId, StructuredPostal.CONTENT_ITEM_TYPE)
                                .withValue(StructuredPostal.FORMATTED_ADDRESS, raw.address)
                                .withValue(StructuredPostal.TYPE, StructuredPostal.TYPE_HOME)
                                .build()
                    }
                }

                // Photo replacement: drop any existing photo rows on this raw
                // contact, then insert the newly picked bytes.
                if (raw.newPhoto != null) {
                    ops += ContentProviderOperation
                        .newDelete(ContactsContract.Data.CONTENT_URI)
                        .withSelection(
                            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND " +
                                "${ContactsContract.Data.MIMETYPE} = ?",
                            arrayOf(raw.rawContactId.toString(), Photo.CONTENT_ITEM_TYPE),
                        )
                        .build()
                    ops += insertRow(raw.rawContactId, Photo.CONTENT_ITEM_TYPE)
                        .withValue(Photo.PHOTO, raw.newPhoto)
                        .build()
                }
            }
            if (ops.isEmpty()) return@withContext true
            runCatching {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            }.isSuccess
        }

    private fun deleteDataRow(dataId: Long): ContentProviderOperation =
        ContentProviderOperation
            .newDelete(ContactsContract.Data.CONTENT_URI)
            .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(dataId.toString()))
            .build()

    private fun updateDataRow(dataId: Long): ContentProviderOperation.Builder =
        ContentProviderOperation
            .newUpdate(ContactsContract.Data.CONTENT_URI)
            .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(dataId.toString()))

    private fun insertRow(rawContactId: Long, mimeType: String): ContentProviderOperation.Builder =
        ContentProviderOperation
            .newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
            .withValue(ContactsContract.Data.MIMETYPE, mimeType)

    /**
     * Account types owned by messaging apps that expose read-only contact
     * lists; hidden from the "save to" picker.
     */
    private val READ_ONLY_ACCOUNT_MARKERS = listOf(
        "whatsapp", "telegram", "viber", "signal", "threema", "skype",
        "org.thoughtcrime", "com.facebook", "linkedin", "tachyon",
    )

    /**
     * Accounts a new contact can be saved to: local phone storage plus every
     * writable sync account that already stores contacts on this device.
     */
    suspend fun listAccounts(context: Context): List<ContactAccount> =
        withContext(Dispatchers.IO) {
            val accounts = LinkedHashSet<ContactAccount>()
            accounts += ContactAccount(null, null)
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
                        val lower = type.lowercase()
                        if (READ_ONLY_ACCOUNT_MARKERS.any { lower.contains(it) }) continue
                        accounts += ContactAccount(c.getString(0), type)
                    }
                }
            }
            accounts.toList()
        }

    data class NewPhone(val number: String, val type: Int, val customLabel: String? = null)
    data class NewEmail(val address: String, val type: Int, val customLabel: String? = null)
    data class NewEvent(val date: String, val type: Int, val customLabel: String? = null)

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

    suspend fun create(
        context: Context,
        account: ContactAccount,
        name: String,
        phones: List<NewPhone> = emptyList(),
        emails: List<NewEmail> = emptyList(),
        events: List<NewEvent> = emptyList(),
        photo: ByteArray? = null,
        note: String? = null,
        address: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val ops = arrayListOf<ContentProviderOperation>()
        ops += ContentProviderOperation
            .newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account.name)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account.type)
            .build()
        fun insert(mime: String) = ContentProviderOperation
            .newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
            .withValue(ContactsContract.Data.MIMETYPE, mime)
        if (name.isNotBlank()) {
            ops += insert(StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, name)
                .build()
        }
        phones.filter { it.number.isNotBlank() }.forEach { p ->
            ops += insert(Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, p.number)
                .withValue(Phone.TYPE, p.type)
                .apply { if (p.type == Phone.TYPE_CUSTOM) withValue(Phone.LABEL, p.customLabel ?: "custom") }
                .build()
        }
        emails.filter { it.address.isNotBlank() }.forEach { e ->
            ops += insert(Email.CONTENT_ITEM_TYPE)
                .withValue(Email.ADDRESS, e.address)
                .withValue(Email.TYPE, e.type)
                .apply { if (e.type == Email.TYPE_CUSTOM) withValue(Email.LABEL, e.customLabel ?: "custom") }
                .build()
        }
        events.filter { it.date.isNotBlank() }.forEach { ev ->
            ops += insert(Event.CONTENT_ITEM_TYPE)
                .withValue(Event.START_DATE, ev.date)
                .withValue(Event.TYPE, ev.type)
                .apply { if (ev.type == Event.TYPE_CUSTOM) withValue(Event.LABEL, ev.customLabel ?: "custom") }
                .build()
        }
        if (!note.isNullOrBlank()) {
            ops += insert(Note.CONTENT_ITEM_TYPE)
                .withValue(Note.NOTE, note)
                .build()
        }
        if (!address.isNullOrBlank()) {
            ops += insert(StructuredPostal.CONTENT_ITEM_TYPE)
                .withValue(StructuredPostal.FORMATTED_ADDRESS, address)
                .withValue(StructuredPostal.TYPE, StructuredPostal.TYPE_HOME)
                .build()
        }
        if (photo != null) {
            ops += insert(Photo.CONTENT_ITEM_TYPE)
                .withValue(Photo.PHOTO, photo)
                .build()
        }
        runCatching {
            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        }.isSuccess
    }
}
