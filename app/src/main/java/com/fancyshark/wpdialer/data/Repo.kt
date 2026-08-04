package com.fancyshark.wpdialer.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.text.format.DateUtils
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactItem(
    val id: Long,
    val name: String,
    val photoUri: String?,
    val starred: Boolean = false,
)

data class ContactPhone(val number: String, val label: String)

data class ContactDetail(
    val id: Long,
    val name: String,
    val photoUri: String?,
    val phones: List<ContactPhone>,
    val note: String? = null,
    val address: String? = null,
    val lookupKey: String? = null,
    val starred: Boolean = false,
    val emails: List<ContactEmail> = emptyList(),
    val events: List<ContactEvent> = emptyList(),
    val websites: List<String> = emptyList(),
    val organization: String? = null,
    val nickname: String? = null,
    val appActions: List<ContactAppAction> = emptyList(),
)

data class ContactEmail(val address: String, val label: String)

data class ContactEvent(val label: String, val date: String)

/** Third-party row (WhatsApp/Telegram/Signal/Meet call & message actions). */
data class ContactAppAction(
    val dataId: Long,
    val mimetype: String,
    val app: String,
    val label: String,
    /** DATA1, e.g. "96170996669@s.whatsapp.net" — used to match phone/email rows. */
    val data1: String? = null,
    /** Sync account type; for these apps it equals the package name. */
    val accountType: String? = null,
)

data class HistoryItem(
    val id: Long,
    val number: String,
    val name: String?,
    val type: Int,
    val date: Long,
    val duration: Long = 0,
)

/** One phone number of one contact, for smart-dial matching. */
data class DialEntry(val contactId: Long, val name: String, val number: String)

object Repo {

    /** The contact's custom ringtone URI string, or null for default. */
    suspend fun contactRingtone(context: Context, id: Long): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts.CUSTOM_RINGTONE),
                    "${ContactsContract.Contacts._ID} = ?",
                    arrayOf(id.toString()),
                    null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        }

    /** Telecom's ringer honors this when the contact calls. */
    suspend fun setContactRingtone(context: Context, id: Long, uri: String?): Unit =
        withContext(Dispatchers.IO) {
            runCatching {
                val values = android.content.ContentValues().apply {
                    put(ContactsContract.Contacts.CUSTOM_RINGTONE, uri)
                }
                context.contentResolver.update(
                    android.content.ContentUris.withAppendedId(
                        ContactsContract.Contacts.CONTENT_URI, id,
                    ),
                    values, null, null,
                )
            }
        }

    /** All contact phone numbers, deduped, for T9 smart dial. */
    suspend fun loadDialEntries(context: Context): List<DialEntry> = withContext(Dispatchers.IO) {
        val out = mutableListOf<DialEntry>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
            )?.use { c ->
                val seen = mutableSetOf<Pair<Long, String>>()
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val num = c.getString(2) ?: continue
                    val key = c.getLong(0) to
                        num.filter { it.isDigit() }.takeLast(9).ifEmpty { num }
                    if (seen.add(key)) out += DialEntry(c.getLong(0), name, num)
                }
            }
        }
        out
    }

    suspend fun loadContacts(context: Context): List<ContactItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ContactItem>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                    ContactsContract.Contacts.STARRED,
                ),
                "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
                null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    out += ContactItem(c.getLong(0), name, c.getString(2), c.getInt(3) == 1)
                }
            }
        }
        out
    }

    suspend fun loadContactDetail(context: Context, id: Long): ContactDetail? =
        withContext(Dispatchers.IO) {
            var name: String? = null
            var photo: String? = null
            var lookupKey: String? = null
            var starred = false
            runCatching {
                context.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                        ContactsContract.Contacts.PHOTO_URI,
                        ContactsContract.Contacts.LOOKUP_KEY,
                        ContactsContract.Contacts.STARRED,
                    ),
                    "${ContactsContract.Contacts._ID} = ?",
                    arrayOf(id.toString()),
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        photo = c.getString(1)
                        lookupKey = c.getString(2)
                        starred = c.getInt(3) == 1
                    }
                }
            }
            val contactName = name ?: return@withContext null
            val phones = mutableListOf<ContactPhone>()
            runCatching {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.LABEL,
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(id.toString()),
                    null,
                )?.use { c ->
                    val seen = mutableSetOf<String>()
                    while (c.moveToNext()) {
                        val number = c.getString(0) ?: continue
                        // Raw contacts from chat apps repeat the same number
                        // unformatted — dedupe on trailing digits.
                        val key = number.filter { it.isDigit() }.takeLast(9).ifEmpty { number }
                        if (!seen.add(key)) continue
                        val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                            context.resources, c.getInt(1), c.getString(2),
                        ).toString().lowercase()
                        phones += ContactPhone(number, label)
                    }
                }
            }
            val emails = mutableListOf<ContactEmail>()
            runCatching {
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Email.ADDRESS,
                        ContactsContract.CommonDataKinds.Email.TYPE,
                        ContactsContract.CommonDataKinds.Email.LABEL,
                    ),
                    "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                    arrayOf(id.toString()),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val addr = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                        if (emails.any { it.address.equals(addr, ignoreCase = true) }) continue
                        val label = ContactsContract.CommonDataKinds.Email.getTypeLabel(
                            context.resources, c.getInt(1), c.getString(2),
                        ).toString().lowercase()
                        emails += ContactEmail(addr, label)
                    }
                }
            }
            var note: String? = null
            var address: String? = null
            val events = mutableListOf<ContactEvent>()
            val websites = mutableListOf<String>()
            var organization: String? = null
            var nickname: String? = null
            val appActions = mutableListOf<ContactAppAction>()
            runCatching {
                context.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Data._ID,
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.Data.DATA1,
                        ContactsContract.Data.DATA2,
                        ContactsContract.Data.DATA3,
                        ContactsContract.Data.DATA4,
                        ContactsContract.RawContacts.ACCOUNT_TYPE,
                    ),
                    "${ContactsContract.Data.CONTACT_ID} = ?",
                    arrayOf(id.toString()),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val mimetype = c.getString(1) ?: continue
                        val value = c.getString(2)?.takeIf { it.isNotBlank() }
                        when (mimetype) {
                            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE ->
                                if (note == null && value != null) note = value
                            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE ->
                                if (address == null && value != null) address = value
                            ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE ->
                                if (value != null) {
                                    events += ContactEvent(
                                        eventTypeLabel(context, c.getInt(3), c.getString(4)),
                                        formatEventDate(value),
                                    )
                                }
                            ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE ->
                                if (value != null &&
                                    websites.none { it.equals(value, ignoreCase = true) }
                                ) {
                                    websites += value
                                }
                            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE -> {
                                // DATA1 = company, DATA4 = job title.
                                val title = c.getString(5)?.takeIf { it.isNotBlank() }
                                val combined = listOfNotNull(value, title).joinToString(", ")
                                if (organization == null && combined.isNotBlank()) {
                                    organization = combined
                                }
                            }
                            ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE ->
                                if (nickname == null && value != null) nickname = value
                            in STANDARD_MIMETYPES -> Unit
                            else -> {
                                // Third-party rows: DATA2 = app/summary,
                                // DATA3 = action label ("Voice call +961 ...").
                                val label = c.getString(4)?.takeIf { it.isNotBlank() }
                                if (label != null) {
                                    appActions += ContactAppAction(
                                        c.getLong(0),
                                        mimetype,
                                        c.getString(3)?.takeIf { it.isNotBlank() }
                                            ?: context.getString(
                                                com.fancyshark.wpdialer.R.string.data_app_fallback,
                                            ),
                                        label,
                                        value,
                                        c.getString(6),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Apps like Telegram register their rows on their own raw contact,
            // which Android may fail to aggregate with this one (no phone row
            // to match on). Sweep third-party rows from other contacts and
            // attach any whose action label carries one of our numbers.
            runCatching {
                val phoneKeys = phones
                    .map { it.number.filter(Char::isDigit) }
                    .filter { it.length >= 5 }
                    .map { it.takeLast(9) }
                    .toSet()
                if (phoneKeys.isNotEmpty()) {
                    context.contentResolver.query(
                        ContactsContract.Data.CONTENT_URI,
                        arrayOf(
                            ContactsContract.Data._ID,
                            ContactsContract.Data.MIMETYPE,
                            ContactsContract.Data.DATA1,
                            ContactsContract.Data.DATA2,
                            ContactsContract.Data.DATA3,
                            ContactsContract.RawContacts.ACCOUNT_TYPE,
                        ),
                        "(${ContactsContract.Data.MIMETYPE} LIKE 'vnd.android.cursor.item/vnd.%' " +
                            "OR ${ContactsContract.Data.MIMETYPE} LIKE 'vnd.android.cursor.item/com.%') " +
                            "AND ${ContactsContract.Data.CONTACT_ID} != ?",
                        arrayOf(id.toString()),
                        null,
                    )?.use { c ->
                        val seenKinds = appActions
                            .map { it.mimetype to actionNumberKey(it.label, it.data1) }
                            .toMutableSet()
                        while (c.moveToNext()) {
                            val mimetype = c.getString(1) ?: continue
                            val label = c.getString(4)?.takeIf { it.isNotBlank() } ?: continue
                            val key = actionNumberKey(label, c.getString(2)) ?: continue
                            if (key !in phoneKeys) continue
                            if (!seenKinds.add(mimetype to key)) continue
                            appActions += ContactAppAction(
                                c.getLong(0),
                                mimetype,
                                c.getString(3)?.takeIf { it.isNotBlank() }
                                            ?: context.getString(
                                                com.fancyshark.wpdialer.R.string.data_app_fallback,
                                            ),
                                label,
                                c.getString(2),
                                c.getString(5),
                            )
                        }
                    }
                }
            }
            appActions.sortBy { it.app.lowercase() }
            // Some sync sources (e.g. Outlook) dump events into the note as
            // "anniversary = 2016-06-09 09:00:00 +0000" lines — surface those
            // as date rows and keep only human-written text in the note.
            note = note?.lines()?.filter { line ->
                val m = NOTE_EVENT_LINE.matchEntire(line.trim())
                // Only consume the line when the date actually parses —
                // "PIN = 1234-56-78" must stay in the note untouched.
                val parsedDate = m?.let { tryFormatEventDate(it.groupValues[2]) }
                if (m != null && parsedDate != null) {
                    // Localize the well-known dump keys; keep anything else raw.
                    val label = when (val key = m.groupValues[1].trim().lowercase()) {
                        "anniversary" ->
                            context.getString(
                                com.fancyshark.wpdialer.R.string.data_event_anniversary,
                            )
                        "birthday" ->
                            context.getString(
                                com.fancyshark.wpdialer.R.string.data_event_birthday,
                            )
                        else -> key
                    }
                    events += ContactEvent(label, parsedDate)
                    false
                } else {
                    true // keep everything else, including blank lines
                }
            }?.joinToString("\n")?.trim()?.takeIf { it.isNotBlank() }
            val uniqueEvents = events.distinctBy { it.label to it.date }
            ContactDetail(
                id, contactName, photo, phones, note, address, lookupKey, starred,
                emails, uniqueEvents, websites, organization, nickname, appActions,
            )
        }

    /**
     * The phone number an app action targets, as a trailing-digits key. The
     * label ("Voice call +961 3 039 056") is preferred because DATA1 may be
     * an app-internal ID (Telegram) rather than a number.
     */
    fun actionNumberKey(label: String, data1: String?): String? {
        val fromLabel = label.filter { it.isDigit() }
        if (fromLabel.length >= 5) return fromLabel.takeLast(9)
        val fromData = (data1 ?: "").substringBefore('@').filter { it.isDigit() }
        return if (fromData.length >= 5) fromData.takeLast(9) else null
    }

    /** Built-in mimetypes that are either handled above or not displayable. */
    private val STANDARD_MIMETYPES = setOf(
        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Im.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Identity.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
        ContactsContract.CommonDataKinds.Relation.CONTENT_ITEM_TYPE,
        "vnd.android.cursor.item/calling_card",
        "vnd.com.google.cursor.item/contact_misc",
    )

    private val NOTE_EVENT_LINE =
        Regex("""([^=]+?)\s*=\s*(\d{4}-\d{2}-\d{2})(?:[T ][\d:.]+\s*(?:[+-]\d{2}:?\d{2}|Z)?)?""")

    private fun eventTypeLabel(context: Context, type: Int, custom: String?): String =
        when (type) {
            ContactsContract.CommonDataKinds.Event.TYPE_ANNIVERSARY ->
                context.getString(com.fancyshark.wpdialer.R.string.data_event_anniversary)
            ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY ->
                context.getString(com.fancyshark.wpdialer.R.string.data_event_birthday)
            ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM ->
                custom?.takeIf { it.isNotBlank() }?.lowercase()
                    ?: context.getString(com.fancyshark.wpdialer.R.string.data_event_date)
            else -> context.getString(com.fancyshark.wpdialer.R.string.data_event_date)
        }

    /**
     * "1987-05-22" -> locale-ordered long date ("May 22, 1987" / "22 mai
     * 1987"); "--05-22" (yearless) -> month + day in locale order. Null when
     * the input isn't a plausible date.
     */
    private fun tryFormatEventDate(raw: String): String? {
        val locale = java.util.Locale.getDefault()
        fun format(skeleton: String, year: Int, month: Int, day: Int): String {
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
            return java.text.SimpleDateFormat(pattern, locale)
                .format(java.util.GregorianCalendar(year, month - 1, day).time)
        }
        Regex("""^--(\d{2})-(\d{2})""").find(raw.trim())?.let { m ->
            val month = m.groupValues[1].toInt()
            val day = m.groupValues[2].toInt()
            if (month in 1..12 && day in 1..31) return format("MMMMd", 2000, month, day)
        }
        Regex("""(\d{4})-(\d{2})-(\d{2})""").find(raw)?.let { m ->
            val month = m.groupValues[2].toInt()
            val day = m.groupValues[3].toInt()
            if (month in 1..12 && day in 1..31) {
                return format("yMMMMd", m.groupValues[1].toInt(), month, day)
            }
        }
        return null
    }

    private fun formatEventDate(raw: String): String = tryFormatEventDate(raw) ?: raw

    suspend fun loadHistory(context: Context): List<HistoryItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<HistoryItem>()
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 300) {
                    out += HistoryItem(
                        id = c.getLong(0),
                        number = c.getString(1) ?: "",
                        name = c.getString(2),
                        type = c.getInt(3),
                        date = c.getLong(4),
                        duration = c.getLong(5),
                    )
                }
            }
        }
        out
    }

    /** Returns display name + photo uri for a phone number, if it belongs to a contact. */
    suspend fun lookupCaller(context: Context, number: String): Pair<String?, String?> =
        withContext(Dispatchers.IO) {
            if (number.isBlank()) return@withContext null to null
            runCatching {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number),
                )
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        ContactsContract.PhoneLookup.DISPLAY_NAME,
                        ContactsContract.PhoneLookup.PHOTO_URI,
                    ),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) return@withContext c.getString(0) to c.getString(1)
                }
            }
            null to null
        }

    /** Formats a number for display using the device's region. */
    fun pretty(context: Context, number: String): String {
        val formatted = runCatching {
            android.telephony.PhoneNumberUtils.formatNumber(
                number, Countries.defaultRegionCode(context),
            )
        }.getOrNull() ?: number
        // LTR-isolate wrapping (LRI/PDI) keeps "+961 3 ..." from being
        // visually reordered inside RTL (Arabic) text runs.
        return "⁦$formatted⁩"
    }

    suspend fun clearHistory(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null) >= 0
        }.getOrDefault(false)
    }

    suspend fun deleteContact(context: Context, id: Long): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.delete(
                    android.content.ContentUris.withAppendedId(
                        ContactsContract.Contacts.CONTENT_URI, id,
                    ),
                    null, null,
                ) > 0
            }.getOrDefault(false)
        }

    suspend fun setStarred(context: Context, id: Long, starred: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val values = android.content.ContentValues().apply {
                    put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0)
                }
                context.contentResolver.update(
                    android.content.ContentUris.withAppendedId(
                        ContactsContract.Contacts.CONTENT_URI, id,
                    ),
                    values, null, null,
                ) > 0
            }.getOrDefault(false)
        }

    /** Blocked-number management; available to the default dialer. */
    suspend fun blockNumber(context: Context, number: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val values = android.content.ContentValues().apply {
                    put(
                        android.provider.BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER,
                        number,
                    )
                }
                context.contentResolver.insert(
                    android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI, values,
                ) != null
            }.getOrDefault(false)
        }

    suspend fun listBlocked(context: Context): List<Pair<Long, String>> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<Pair<Long, String>>()
            runCatching {
                context.contentResolver.query(
                    android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                    arrayOf(
                        android.provider.BlockedNumberContract.BlockedNumbers.COLUMN_ID,
                        android.provider.BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER,
                    ),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) out += c.getLong(0) to (c.getString(1) ?: "")
                }
            }
            out
        }

    suspend fun unblockNumber(context: Context, rowId: Long): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.delete(
                    android.content.ContentUris.withAppendedId(
                        android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI, rowId,
                    ),
                    null, null,
                ) > 0
            }.getOrDefault(false)
        }

    /** Contact ids whose phone numbers match [query] (partial). */
    suspend fun contactIdsByNumber(context: Context, query: String): Set<Long> =
        withContext(Dispatchers.IO) {
            val ids = mutableSetOf<Long>()
            runCatching {
                context.contentResolver.query(
                    Uri.withAppendedPath(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                        Uri.encode(query),
                    ),
                    arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID),
                    null, null, null,
                )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
            }
            ids
        }

    /** Deletes one call log entry. Requires WRITE_CALL_LOG. */
    suspend fun deleteHistoryItem(context: Context, id: Long): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.delete(
                    CallLog.Calls.CONTENT_URI,
                    "${CallLog.Calls._ID} = ?",
                    arrayOf(id.toString()),
                ) > 0
            }.getOrDefault(false)
        }

    /**
     * Resolves the country a phone number belongs to (e.g. "Germany"), for
     * showing callers that aren't in the contacts. Local numbers without a
     * country prefix resolve via the current network's country.
     */
    suspend fun callerCountry(context: Context, number: String): String? =
        withContext(Dispatchers.IO) {
            if (number.isBlank()) return@withContext null
            runCatching {
                val telephony = context.getSystemService(android.telephony.TelephonyManager::class.java)
                val defaultRegion = telephony?.networkCountryIso
                    ?.takeIf { it.isNotBlank() }?.uppercase()
                    ?: java.util.Locale.getDefault().country.ifBlank { "US" }
                val util = com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
                val parsed = util.parse(number, defaultRegion)
                val region = util.getRegionCodeForNumber(parsed) ?: return@withContext null
                java.util.Locale("", region).displayCountry.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

    /** Returns the contact id owning [number], or null. */
    suspend fun contactIdFor(context: Context, number: String): Long? =
        withContext(Dispatchers.IO) {
            if (number.isBlank()) return@withContext null
            runCatching {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number),
                )
                context.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup.CONTACT_ID),
                    null, null, null,
                )?.use { c -> if (c.moveToFirst()) return@withContext c.getLong(0) }
            }
            null
        }

    suspend fun loadPhoto(context: Context, photoUri: String?): ImageBitmap? =
        withContext(Dispatchers.IO) {
            if (photoUri == null) return@withContext null
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(photoUri))?.use {
                    BitmapFactory.decodeStream(it)?.asImageBitmap()
                }
            }.getOrNull()
        }

    fun historyTypeLabel(context: Context, type: Int): String = context.getString(
        when (type) {
            CallLog.Calls.INCOMING_TYPE -> com.fancyshark.wpdialer.R.string.data_call_incoming
            CallLog.Calls.OUTGOING_TYPE -> com.fancyshark.wpdialer.R.string.data_call_outgoing
            CallLog.Calls.MISSED_TYPE -> com.fancyshark.wpdialer.R.string.data_call_missed
            CallLog.Calls.REJECTED_TYPE -> com.fancyshark.wpdialer.R.string.data_call_declined
            CallLog.Calls.VOICEMAIL_TYPE -> com.fancyshark.wpdialer.R.string.data_call_voicemail
            CallLog.Calls.BLOCKED_TYPE -> com.fancyshark.wpdialer.R.string.data_call_blocked
            else -> com.fancyshark.wpdialer.R.string.data_call_generic
        },
    )

    /** Short capitalized form for history sublines ("Outgoing, Sat 01:06"). */
    fun historyTypeShort(context: Context, type: Int): String = context.getString(
        when (type) {
            CallLog.Calls.INCOMING_TYPE -> com.fancyshark.wpdialer.R.string.data_call_incoming_short
            CallLog.Calls.OUTGOING_TYPE -> com.fancyshark.wpdialer.R.string.data_call_outgoing_short
            CallLog.Calls.MISSED_TYPE -> com.fancyshark.wpdialer.R.string.data_call_missed_short
            CallLog.Calls.REJECTED_TYPE -> com.fancyshark.wpdialer.R.string.data_call_declined_short
            CallLog.Calls.VOICEMAIL_TYPE -> com.fancyshark.wpdialer.R.string.data_call_voicemail_short
            CallLog.Calls.BLOCKED_TYPE -> com.fancyshark.wpdialer.R.string.data_call_blocked_short
            else -> com.fancyshark.wpdialer.R.string.data_call_generic_short
        },
    )

    fun relativeTime(date: Long): String =
        DateUtils.getRelativeTimeSpanString(
            date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        ).toString().lowercase()

    /** WP-style timestamp: "01:06" today, "Sat 01:06" this week, "Aug 3" older. */
    fun wpTime(date: Long): String {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = date }
        val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
            now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
        val withinWeek = now.timeInMillis - date < 6 * 24 * 3600_000L
        val pattern = when {
            sameDay -> "HH:mm"
            withinWeek -> "EEE HH:mm"
            else -> "MMM d"
        }
        return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(date)
    }

    /** History subline honoring the user's time-format preference. */
    fun historyWhen(date: Long): String =
        if (AppPrefs.relativeTimes.value) relativeTime(date) else wpTime(date)

    fun formatDuration(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }

    /** Deletes several call log entries (a grouped history row). */
    suspend fun deleteHistoryItems(context: Context, ids: List<Long>): Boolean =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext false
            runCatching {
                context.contentResolver.delete(
                    CallLog.Calls.CONTENT_URI,
                    "${CallLog.Calls._ID} IN (${ids.joinToString(",")})",
                    null,
                ) > 0
            }.getOrDefault(false)
        }
}
