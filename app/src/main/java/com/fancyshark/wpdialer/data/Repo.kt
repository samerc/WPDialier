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
                    val key = c.getLong(0) to numberKey(num).ifEmpty { num }
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
                    while (c.moveToNext()) {
                        val number = c.getString(0) ?: continue
                        // Raw contacts from chat apps repeat the same number
                        // unformatted (national vs E.164) — pairwise suffix
                        // matching dedupes those without collapsing numbers
                        // that merely share trailing digits across country
                        // codes.
                        if (phones.any { numbersMatch(it.number, number) }) continue
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
                val region = Countries.defaultRegionCode(context)
                val phoneForms = phones
                    .map { numberDigits(it.number) to internationalForm(it.number, region) }
                    .filter { it.first.length >= 7 }
                if (phoneForms.isNotEmpty()) {
                    val seenKinds = appActions
                        .map {
                            it.mimetype to
                                (actionNumberKey(it.label, it.data1)?.let(::numberKey) ?: "")
                        }
                        .toMutableSet()
                    for (row in sweepRows(context)) {
                        if (row.contactId == id) continue
                        // Exact E.164 when both sides are international;
                        // suffix only unifies a national spelling.
                        if (phoneForms.none { (pd, pIntl) ->
                                sweepMatch(pd, pIntl, row.key, row.intl)
                            }
                        ) {
                            continue
                        }
                        if (!seenKinds.add(row.mimetype to numberKey(row.key))) continue
                        appActions += ContactAppAction(
                            row.dataId,
                            row.mimetype,
                            row.app ?: context.getString(
                                com.fancyshark.wpdialer.R.string.data_app_fallback,
                            ),
                            row.label,
                            row.data1,
                            row.accountType,
                        )
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

    private data class SweepRow(
        val dataId: Long,
        val contactId: Long,
        val mimetype: String,
        val app: String?,
        val label: String,
        val data1: String?,
        val accountType: String?,
        val key: String,
        /** E.164 when the label writes the number internationally. */
        val intl: String?,
    )

    // The sibling-action sweep reads every third-party Data row in the
    // contacts provider — too heavy to repeat on each profile open, so the
    // parsed rows are cached briefly and filtered per contact in memory.
    @Volatile
    private var sweepCache: Pair<Long, List<SweepRow>>? = null
    private const val SWEEP_TTL_MS = 60_000L

    private fun sweepRows(context: Context): List<SweepRow> {
        sweepCache?.let { (loadedAt, rows) ->
            if (android.os.SystemClock.elapsedRealtime() - loadedAt < SWEEP_TTL_MS) return rows
        }
        val rows = mutableListOf<SweepRow>()
        val region = Countries.defaultRegionCode(context)
        var queried = false
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
                ContactsContract.RawContacts.ACCOUNT_TYPE,
                ContactsContract.Data.CONTACT_ID,
            ),
            "${ContactsContract.Data.MIMETYPE} LIKE 'vnd.android.cursor.item/vnd.%' " +
                "OR ${ContactsContract.Data.MIMETYPE} LIKE 'vnd.android.cursor.item/com.%'",
            null,
            null,
        )?.use { c ->
            queried = true
            while (c.moveToNext()) {
                val mimetype = c.getString(1) ?: continue
                val label = c.getString(4)?.takeIf { it.isNotBlank() } ?: continue
                val key = actionNumberKey(label, c.getString(2)) ?: continue
                rows += SweepRow(
                    c.getLong(0),
                    c.getLong(6),
                    mimetype,
                    c.getString(3)?.takeIf { it.isNotBlank() },
                    label,
                    c.getString(2),
                    c.getString(5),
                    key,
                    // DATA1-derived keys may be app user IDs — no E.164 claim.
                    if (textDigits(label).length >= 5) labelInternationalForm(label, region) else null,
                )
            }
        }
        // A null cursor (transient provider failure) must not pin an empty
        // result for a whole TTL window.
        if (queried) sweepCache = android.os.SystemClock.elapsedRealtime() to rows
        return rows
    }

    /** The dialable network part: everything before the first post-dial
     *  pause/wait ("5551234,123#" -> "5551234") — extension digits must
     *  never leak into comparisons or keys. */
    fun networkPortion(raw: String): String {
        val cut = raw.indexOfFirst { it == ',' || it == ';' || it in "pPwW" }
        return if (cut < 0) raw else raw.substring(0, cut)
    }

    /** USSD / service codes ("*123#") aren't phone numbers — their digits
     *  carry meaning in position, so they only ever match exactly. */
    private fun isOpaque(net: String): Boolean = net.any { it == '*' || it == '#' }

    private fun opaqueForm(net: String): String =
        net.filterNot { it.isWhitespace() || it in "-()." }

    /** Digits with national trunk zeros stripped — the comparable core.
     *  "03 039 056" -> "3039056"; "+961 3 039 056" -> "9613039056". */
    fun numberDigits(raw: String): String =
        networkPortion(raw).filter { it.isDigit() }.trimStart('0')

    /** Set/dedupe key: last 7 significant digits — unifies the national
     *  and E.164 spellings of the same line. Service codes key as their
     *  full cleaned string ("*123#" must not collide with "123"). */
    fun numberKey(raw: String): String {
        val net = networkPortion(raw)
        if (isOpaque(net)) return opaqueForm(net)
        return numberDigits(net).takeLast(7)
    }

    /** Same-line test: one significant-digit string suffixes the other
     *  (national form vs full international form). */
    fun numbersMatch(a: String, b: String): Boolean {
        val na = networkPortion(a)
        val nb = networkPortion(b)
        if (isOpaque(na) || isOpaque(nb)) {
            val oa = opaqueForm(na)
            return oa.isNotEmpty() && oa == opaqueForm(nb)
        }
        val da = numberDigits(na)
        val db = numberDigits(nb)
        if (da.isEmpty() || db.isEmpty()) return false
        if (da.length < 5 || db.length < 5) return da == db
        return da.endsWith(db) || db.endsWith(da)
    }

    /**
     * Exact identity for storage keys (blocked list, SIM prefs): strict
     * E.164 when the number parses, else digits with any leading "+" —
     * never a suffix, so "+1 212 555 1234" and "+1 646 555 1234" differ.
     */
    fun exactNumberKey(context: Context, raw: String): String {
        val net = networkPortion(raw).trim()
        if (isOpaque(net)) return opaqueForm(net)
        Countries.e164OrNull(net, Countries.defaultRegionCode(context))?.let { return it }
        val digits = net.filter { it.isDigit() }
        return if (net.startsWith("+")) "+$digits" else digits
    }

    // Digits of free text (app-action labels like "WhatsApp voice call +961
    // ..."): no post-dial cut — "p"/"w" here are letters, not pauses.
    private fun textDigits(raw: String): String = raw.filter { it.isDigit() }.trimStart('0')

    /**
     * The phone number an app action targets, as significant digits. The
     * label ("Voice call +961 3 039 056") is preferred because DATA1 may be
     * an app-internal ID (Telegram) rather than a number.
     */
    fun actionNumberKey(label: String, data1: String?): String? {
        val fromLabel = textDigits(label)
        if (fromLabel.length >= 5) return fromLabel
        val fromData = textDigits((data1 ?: "").substringBefore('@'))
        return if (fromData.length >= 5) fromData else null
    }

    private val LABEL_NUMBER = Regex("""\+?\d[\d\s().\-]*\d""")

    /**
     * E.164 of a number written in international form ("+961 ...", "00961
     * ..."); null for national spellings, whose country is a guess — those
     * fall back to suffix matching.
     */
    private fun internationalForm(raw: String, region: String): String? {
        val net = networkPortion(raw).trim()
        val digits = when {
            net.startsWith("+") -> net.filter { it.isDigit() }
            net.startsWith("00") -> net.filter { it.isDigit() }.drop(2)
            else -> return null
        }
        if (digits.length < 7) return null
        return Countries.e164OrNull("+$digits", region) ?: "+$digits"
    }

    /** [internationalForm] of the longest number-looking run in a label. */
    private fun labelInternationalForm(label: String, region: String): String? =
        LABEL_NUMBER.findAll(label)
            .maxByOrNull { m -> m.value.count { it.isDigit() } }
            ?.let { internationalForm(it.value, region) }

    /**
     * Sweep matching: at least 7 significant digits on both sides; exact
     * E.164 when both are written internationally (suffixes would merge
     * "+1 555 1234567" with "+44 555 1234567"); suffix only when one side
     * is national.
     */
    private fun sweepMatch(aDigits: String, aIntl: String?, bDigits: String, bIntl: String?): Boolean {
        if (aDigits.length < 7 || bDigits.length < 7) return false
        if (aIntl != null && bIntl != null) return aIntl == bIntl
        return aDigits.endsWith(bDigits) || bDigits.endsWith(aDigits)
    }

    /** Drops in-memory provider caches — call after any contacts write so
     *  the next profile open sees fresh sibling rows. */
    fun invalidateCaches() {
        sweepCache = null
        synchronized(this) { callerCacheAt = 0L }
    }

    private var callerCache = HashMap<String, CallerMatch>()
    private var callerCacheAt = 0L
    private const val CALLER_TTL_MS = 60_000L

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


    /** Current contacts-provider identity of a number, for call-log refresh. */
    private sealed interface CallerMatch {
        /** Lookup failed (no READ_CONTACTS, provider down) — trust the cache. */
        data object Unknown : CallerMatch
        data object NoContact : CallerMatch
        data class Contact(
            val name: String?,
            val type: Int?,
            val label: String?,
            val lookupUri: String?,
        ) : CallerMatch
    }

    private fun matchCaller(context: Context, number: String): CallerMatch = runCatching {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.TYPE,
                ContactsContract.PhoneLookup.LABEL,
                ContactsContract.PhoneLookup.CONTACT_ID,
                ContactsContract.PhoneLookup.LOOKUP_KEY,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@runCatching CallerMatch.NoContact
            val lookupUri = c.getString(4)?.let { key ->
                ContactsContract.Contacts.getLookupUri(c.getLong(3), key)?.toString()
            }
            CallerMatch.Contact(
                c.getString(0),
                if (c.isNull(1)) null else c.getInt(1),
                c.getString(2),
                lookupUri,
            )
        } ?: CallerMatch.Unknown
    }.getOrDefault(CallerMatch.Unknown)

    /**
     * Newest [limit] calls ([missedOnly]: newest [limit] MISSED rows, so the
     * filter reaches past the first page). Load-more grows [limit] by a page
     * and reloads (see [HistoryPaging]) instead of offset paging: rows that
     * land while the list is open would shift offsets and duplicate or skip
     * entries. CACHED_NAME is only written when the call
     * lands, so renamed/deleted contacts would show stale names forever —
     * each distinct number is re-resolved once per load and drifted rows are
     * written back (WRITE_CALL_LOG, held as default dialer).
     */
    suspend fun loadHistory(
        context: Context,
        limit: Int = HistoryPaging.PAGE_SIZE,
        missedOnly: Boolean = false,
    ): List<HistoryItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<HistoryItem>()
        // Telecom-set CACHED_LOOKUP_URI per row: non-null means the name came
        // from a contact (vs a CNAP name, which a lookup miss must not erase).
        val lookupUris = mutableListOf<String?>()
        runCatching {
            val args = android.os.Bundle().apply {
                putString(
                    android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${CallLog.Calls.DATE} DESC",
                )
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
                if (missedOnly) {
                    putString(
                        android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}",
                    )
                }
            }
            // The call-log provider ignores QUERY_ARG_LIMIT on some builds;
            // its own "limit" URI parameter is the long-standing contract.
            val uri = CallLog.Calls.CONTENT_URI.buildUpon()
                .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, limit.toString())
                .build()
            context.contentResolver.query(
                uri,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.CACHED_LOOKUP_URI,
                ),
                args,
                null,
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out += HistoryItem(
                        id = c.getLong(0),
                        number = c.getString(1) ?: "",
                        name = c.getString(2),
                        type = c.getInt(3),
                        date = c.getLong(4),
                        duration = c.getLong(5),
                    )
                    lookupUris += c.getString(6)
                }
            }
        }
        // One lookup per distinct number, not per row — and memoized across
        // loads (history reloads on every resume; ~100 lookups each time
        // would delay the list). Our own contact writes invalidate it; edits
        // made in other apps show up within the TTL.
        val matches = synchronized(this) {
            if (android.os.SystemClock.elapsedRealtime() - callerCacheAt > CALLER_TTL_MS) {
                callerCache = HashMap()
                callerCacheAt = android.os.SystemClock.elapsedRealtime()
            }
            callerCache
        }
        val stale = LinkedHashMap<String, CallerMatch>()
        val refreshed = out.mapIndexed { i, item ->
            if (item.number.isBlank()) return@mapIndexed item
            val match = synchronized(matches) { matches[item.number] }
                ?: matchCaller(context, item.number).also { m ->
                    // Failed lookups aren't cached — retry next load.
                    if (m != CallerMatch.Unknown) synchronized(matches) { matches[item.number] = m }
                }
            val name = when (match) {
                CallerMatch.Unknown -> item.name
                // Only clear names that came from a (now deleted) contact.
                CallerMatch.NoContact -> if (lookupUris[i] != null) null else item.name
                is CallerMatch.Contact -> match.name ?: item.name
            }
            if (name != item.name) {
                stale[item.number] = match
                item.copy(name = name)
            } else item
        }
        if (stale.isNotEmpty()) {
            runCatching {
                stale.forEach { (number, match) ->
                    val values = android.content.ContentValues()
                    var selection = "${CallLog.Calls.NUMBER} = ?"
                    when (match) {
                        is CallerMatch.Contact -> {
                            values.put(CallLog.Calls.CACHED_NAME, match.name)
                            match.type?.let { values.put(CallLog.Calls.CACHED_NUMBER_TYPE, it) }
                            values.put(CallLog.Calls.CACHED_NUMBER_LABEL, match.label)
                            match.lookupUri?.let { values.put(CallLog.Calls.CACHED_LOOKUP_URI, it) }
                        }
                        else -> {
                            values.putNull(CallLog.Calls.CACHED_NAME)
                            values.putNull(CallLog.Calls.CACHED_NUMBER_TYPE)
                            values.putNull(CallLog.Calls.CACHED_NUMBER_LABEL)
                            values.putNull(CallLog.Calls.CACHED_LOOKUP_URI)
                            selection += " AND ${CallLog.Calls.CACHED_LOOKUP_URI} IS NOT NULL"
                        }
                    }
                    runCatching {
                        context.contentResolver.update(
                            CallLog.Calls.CONTENT_URI, values, selection, arrayOf(number),
                        )
                    }
                }
            }
        }
        refreshed
    }

    /**
     * Call screening's "known caller" test: a contact, or a number this
     * phone has dialed (newest 1000 outgoing rows, matched in code via
     * [numbersMatch] — SQL can't unify national/E.164 spellings). Blocking
     * on purpose (the screening service runs it off the main thread with a
     * deadline); null when either provider can't be read, so the caller
     * can fall back to letting the call ring.
     */
    fun isKnownNumber(context: Context, number: String): Boolean? {
        if (number.isBlank()) return false
        val inContacts = runCatching {
            context.contentResolver.query(
                Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number),
                ),
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null,
            )?.use { it.moveToFirst() }
        }.getOrNull() ?: return null
        if (inContacts) return true
        return runCatching {
            val args = android.os.Bundle().apply {
                putString(
                    android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${CallLog.Calls.TYPE} = ${CallLog.Calls.OUTGOING_TYPE}",
                )
                putString(
                    android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${CallLog.Calls.DATE} DESC",
                )
            }
            val uri = CallLog.Calls.CONTENT_URI.buildUpon()
                .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "1000")
                .build()
            context.contentResolver.query(uri, arrayOf(CallLog.Calls.NUMBER), args, null)
                ?.use { c ->
                    var found = false
                    while (!found && c.moveToNext()) {
                        found = numbersMatch(c.getString(0) ?: "", number)
                    }
                    found
                }
        }.getOrNull()
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
        return "$LRI$formatted$PDI"
    }

    // Named, not literal: invisible bidi controls in source trip lint's
    // BidiSpoofing check and are impossible to review.
    private val LRI = Char(0x2066)
    private val PDI = Char(0x2069)

    /**
     * Opening the app counts as seeing missed calls (home IS history) —
     * clears the NEW/unread flags the tile widget and system badges count.
     */
    suspend fun markMissedSeen(context: Context): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val values = android.content.ContentValues().apply {
                put(CallLog.Calls.NEW, 0)
                put(CallLog.Calls.IS_READ, 1)
            }
            context.contentResolver.update(
                CallLog.Calls.CONTENT_URI, values,
                "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE} AND " +
                    "(${CallLog.Calls.NEW} = 1 OR ${CallLog.Calls.IS_READ} = 0)",
                null,
            )
        }
    }

    suspend fun clearHistory(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.delete(CallLog.Calls.CONTENT_URI, null, null) >= 0
        }.getOrDefault(false).also { if (it) CallNotes.clear(context) }
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
            }.getOrDefault(false).also { invalidateCaches() }
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
            if (number.isBlank()) return@withContext false
            // The provider doesn't enforce uniqueness — a duplicate row would
            // survive one "unblock" and keep silently rejecting the caller.
            // Exact identity, not a suffix match — blocking one number must
            // never no-op because a different line shares its trailing digits.
            val key = exactNumberKey(context, number)
            val already = runCatching {
                listBlocked(context).any { exactNumberKey(context, it.second) == key }
            }.getOrDefault(false)
            if (already) return@withContext true
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
            }.getOrDefault(false).also { if (it) CallNotes.remove(context, listOf(id)) }
        }

    /**
     * Resolves the country a phone number belongs to (e.g. "Germany"), for
     * showing callers that aren't in the contacts. Local numbers without a
     * country prefix resolve via the same default region [pretty] formats
     * with (SIM first), so the country line and the number agree.
     */
    suspend fun callerCountry(context: Context, number: String): String? =
        withContext(Dispatchers.IO) {
            if (number.isBlank()) return@withContext null
            runCatching {
                val defaultRegion = Countries.defaultRegionCode(context)
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
            }.getOrDefault(false).also { if (it) CallNotes.remove(context, ids) }
        }

    /**
     * Every call-log row with any of [numbers] — one person's calls across
     * all their lines — newest first, with the SIM each went through.
     * Matching is in code via [numbersMatch] (SQL can't unify national and
     * E.164 spellings), over the newest [scanLimit] rows; the platform
     * caps the log near that size anyway. Empty when no number is usable
     * or the log can't be read.
     */
    suspend fun callsWith(
        context: Context,
        numbers: Collection<String>,
        scanLimit: Int = 5000,
    ): List<ContactCall> = withContext(Dispatchers.IO) {
        val wanted = numbers.filter { numberDigits(it).isNotEmpty() || isOpaque(networkPortion(it)) }
            .distinctBy { exactNumberKey(context, it) }
        if (wanted.isEmpty()) return@withContext emptyList()
        val out = mutableListOf<ContactCall>()
        runCatching {
            val uri = CallLog.Calls.CONTENT_URI.buildUpon()
                .appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, scanLimit.toString())
                .build()
            context.contentResolver.query(
                uri,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,
                    CallLog.Calls.PHONE_ACCOUNT_ID,
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                var scanned = 0
                while (c.moveToNext() && scanned++ < scanLimit) {
                    val number = c.getString(1) ?: continue
                    if (number.isBlank()) continue
                    val ours = wanted.firstOrNull { numbersMatch(it, number) } ?: continue
                    val component = c.getString(6)
                    val accountId = c.getString(7)
                    out += ContactCall(
                        item = HistoryItem(
                            id = c.getLong(0),
                            number = number,
                            name = c.getString(2),
                            type = c.getInt(3),
                            date = c.getLong(4),
                            duration = c.getLong(5),
                        ),
                        contactNumber = ours,
                        phoneAccount = if (component != null && accountId != null) {
                            "$component/$accountId"
                        } else {
                            null
                        },
                    )
                }
            }
        }
        out
    }
}

/**
 * One call with a contact, from [Repo.callsWith]: the log row, which of the
 * contact's own numbers it matched (for the type label), and the phone
 * account flattened like [Sims.flatten] (null when the log has none).
 */
data class ContactCall(
    val item: HistoryItem,
    val contactNumber: String,
    val phoneAccount: String?,
)
