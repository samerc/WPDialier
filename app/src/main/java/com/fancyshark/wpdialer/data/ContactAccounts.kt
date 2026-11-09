package com.fancyshark.wpdialer.data

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.AggregationExceptions
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * Account management for one contact: which raw contacts ("parts") it is
 * made of, which account each lives in, and the operations that move,
 * copy, link, unlink, merge and delete those parts.
 *
 * Data safety rules every write here follows:
 *  - read-only parts (WhatsApp, Telegram, ...) are never written or deleted;
 *    they can only be linked/unlinked (aggregation exceptions);
 *  - nothing is deleted before the copy it depends on has been VERIFIED by
 *    reading it back from the provider;
 *  - deletions go through the normal (non sync-adapter) path, so the
 *    account's sync adapter propagates them to its cloud.
 *
 * The top of the file is pure (no Android calls) so it runs in JVM tests.
 */

/** Contacts-provider mimetypes, spelled out so the pure logic runs in JVM tests. */
object Mime {
    const val NAME = "vnd.android.cursor.item/name"
    const val PHONE = "vnd.android.cursor.item/phone_v2"
    const val EMAIL = "vnd.android.cursor.item/email_v2"
    const val PHOTO = "vnd.android.cursor.item/photo"
    const val ORGANIZATION = "vnd.android.cursor.item/organization"
    const val IM = "vnd.android.cursor.item/im"
    const val NICKNAME = "vnd.android.cursor.item/nickname"
    const val NOTE = "vnd.android.cursor.item/note"
    const val POSTAL = "vnd.android.cursor.item/postal-address_v2"
    const val GROUP = "vnd.android.cursor.item/group_membership"
    const val WEBSITE = "vnd.android.cursor.item/website"
    const val EVENT = "vnd.android.cursor.item/contact_event"
    const val RELATION = "vnd.android.cursor.item/relation"
    const val SIP = "vnd.android.cursor.item/sip_address"
    const val IDENTITY = "vnd.android.cursor.item/identity"

    /** The platform's CommonDataKinds — understood by every account. */
    val STANDARD = setOf(
        NAME, PHONE, EMAIL, PHOTO, ORGANIZATION, IM, NICKNAME, NOTE, POSTAL,
        GROUP, WEBSITE, EVENT, RELATION, SIP, IDENTITY,
    )

    /**
     * Copied row-for-row into another account. Photo is re-written from the
     * full-size display photo, group membership is remapped by title, and
     * Identity is namespace-bound to its source — none are copied raw. Any
     * other mimetype is sync-adapter/app private and stays behind.
     */
    val COPYABLE = STANDARD - PHOTO - GROUP - IDENTITY
}

/** Data-table column names (ContactsContract.Data.DATA1.. = "data1"..). */
object Cols {
    const val IS_PRIMARY = "is_primary"
    const val IS_SUPER_PRIMARY = "is_super_primary"
    fun data(i: Int) = "data$i"
}

/** A contacts sync account. A null [type] is device-local storage. */
data class AccountRef(val name: String?, val type: String?)

enum class AccountKind { GOOGLE, EXCHANGE, OUTLOOK, WHATSAPP, TELEGRAM, SIGNAL, LOCAL, SIM, OTHER }

/** Account-type classification, colors and monograms (pure). */
object AccountStyle {

    // OEM "phone only" storage types (null is the AOSP local account).
    private val LOCAL_TYPES = setOf(
        "vnd.sec.contact.phone",
        "com.android.huawei.phone",
        "com.android.localphone",
        "local phone account",
        "com.oplus.contacts.phone",
        "com.oneplus.contacts.phone",
        "com.coloros.contacts.phone",
    )

    fun kindOf(type: String?): AccountKind {
        if (type.isNullOrBlank()) return AccountKind.LOCAL
        val t = type.lowercase()
        val tokens = t.split('.', '_', '-', ' ')
        return when {
            t in LOCAL_TYPES -> AccountKind.LOCAL
            t.contains("exchange") -> AccountKind.EXCHANGE
            t.contains("whatsapp") -> AccountKind.WHATSAPP
            t.contains("telegram") -> AccountKind.TELEGRAM
            t.contains("thoughtcrime") || t.contains("signal") -> AccountKind.SIGNAL
            t.contains("google") -> AccountKind.GOOGLE
            t.contains("outlook") || t.contains("microsoft") || t.contains("office") ||
                t.contains("hotmail") -> AccountKind.OUTLOOK
            tokens.any { it == "sim" || (it.startsWith("sim") && it.length <= 4) } ->
                AccountKind.SIM
            tokens.contains("phone") && (t.contains("local") || t.contains("contact")) ->
                AccountKind.LOCAL
            else -> AccountKind.OTHER
        }
    }

    private val HASHED = longArrayOf(
        0xFF8E44AD, 0xFFD35400, 0xFF16A085, 0xFFC0392B,
        0xFF6D8764, 0xFF87794E, 0xFF1BA1E2, 0xFF6A00FF,
    )

    /** ARGB square color for an account type. */
    fun color(type: String?): Long = when (kindOf(type)) {
        AccountKind.GOOGLE -> 0xFF2F6FDE
        AccountKind.EXCHANGE, AccountKind.OUTLOOK -> 0xFF0A64B8
        AccountKind.WHATSAPP -> 0xFF1F8F4E
        AccountKind.LOCAL, AccountKind.SIM -> 0xFF555555
        else -> HASHED[Math.floorMod((type ?: "").hashCode(), HASHED.size)]
    }

    /** Brand names (never translated); null where the label comes from elsewhere. */
    fun brandLabel(kind: AccountKind): String? = when (kind) {
        AccountKind.GOOGLE -> "Google"
        AccountKind.EXCHANGE -> "Exchange"
        AccountKind.OUTLOOK -> "Outlook"
        AccountKind.WHATSAPP -> "WhatsApp"
        AccountKind.TELEGRAM -> "Telegram"
        AccountKind.SIGNAL -> "Signal"
        AccountKind.SIM -> "SIM"
        else -> null
    }

    /** Letters drawn on the account square ("G", "WA"). */
    fun monogram(kind: AccountKind, label: String): String = when (kind) {
        AccountKind.WHATSAPP -> "WA"
        else -> label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    }

    /**
     * Same account, tolerating providers that rewrite the null local
     * account to their own local type on insert.
     */
    fun sameAccount(expected: AccountRef, actual: AccountRef): Boolean {
        if (expected == actual) return true
        return expected.type == null && kindOf(actual.type) == AccountKind.LOCAL
    }

    /** SIM storage can't hold a full contact — never a move/copy/merge target. */
    fun canHoldFullContact(type: String?): Boolean = kindOf(type) != AccountKind.SIM
}

/** One provider data row: its mimetype and DATA1..DATA15 / primary flags. */
data class DataRow(
    val dataId: Long,
    val rawId: Long,
    val mimetype: String,
    val values: Map<String, Any?>,
) {
    fun text(col: String): String? = when (val v = values[col]) {
        null, is ByteArray -> null
        else -> v.toString().takeIf { it.isNotBlank() }
    }

    fun int(col: String): Int? = when (val v = values[col]) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    fun blob(col: String): ByteArray? = values[col] as? ByteArray

    val data1: String? get() = text("data1")
}

data class GroupInfo(val id: Long, val title: String?, val systemId: String?, val account: AccountRef)

/** One raw contact ("part") of an aggregate contact. */
data class RawSource(
    val rawId: Long,
    val contactId: Long,
    val account: AccountRef,
    val readOnly: Boolean,
    val starred: Boolean,
    val ringtone: String?,
    val sendToVoicemail: Boolean,
    val rows: List<DataRow>,
) {
    val displayName: String? get() = rows.firstOrNull { it.mimetype == Mime.NAME }?.data1
}

data class ContactSources(
    val contactId: Long,
    val lookupKey: String?,
    val displayName: String,
    val raws: List<RawSource>,
    val groups: Map<Long, GroupInfo>,
)

/** What one raw contact holds, for the "saved in" line and "what moves". */
data class RawSummary(
    val phones: Int,
    val emails: Int,
    val photo: Boolean,
    val birthday: Boolean,
    val otherDates: Int,
    val addresses: Int,
    val note: Boolean,
    val company: Boolean,
    val jobTitle: Boolean,
    val websites: Int,
    val labels: List<String>,
    val otherFields: Int,
    /** Lowercased action names of app rows ("message", "voice call"). */
    val appActions: List<String>,
    /** App/sync-private rows that a move leaves behind. */
    val privateRows: Int,
)

/** Pure derivations over raw rows, for the profile page. */
object ProfileFacts {

    /** User labels (group titles) of these raw contacts; system groups hidden. */
    fun userLabels(raws: List<RawSource>, groups: Map<Long, GroupInfo>): List<String> =
        raws.flatMap { labelsOf(it, groups) }.distinctBy { it.lowercase() }

    fun labelsOf(raw: RawSource, groups: Map<Long, GroupInfo>): List<String> =
        raw.rows.filter { it.mimetype == Mime.GROUP }
            .mapNotNull { row -> row.text("data1")?.toLongOrNull()?.let { groups[it] } }
            .filter { it.systemId == null }
            .mapNotNull { it.title?.trim()?.takeIf { t -> t.isNotEmpty() } }

    fun summarize(raw: RawSource, groups: Map<Long, GroupInfo>): RawSummary {
        val rows = raw.rows
        fun count(m: String) = rows.count { it.mimetype == m }
        val events = rows.filter { it.mimetype == Mime.EVENT && it.data1 != null }
        val org = rows.filter { it.mimetype == Mime.ORGANIZATION }
        val private = rows.filter { it.mimetype !in Mime.STANDARD }
        return RawSummary(
            phones = rows.count { it.mimetype == Mime.PHONE && it.data1 != null },
            emails = rows.count { it.mimetype == Mime.EMAIL && it.data1 != null },
            photo = count(Mime.PHOTO) > 0,
            birthday = events.any { it.int("data2") == EVENT_BIRTHDAY },
            otherDates = events.count { it.int("data2") != EVENT_BIRTHDAY },
            addresses = rows.count { it.mimetype == Mime.POSTAL && it.data1 != null },
            note = rows.any { it.mimetype == Mime.NOTE && it.data1 != null },
            company = org.any { it.data1 != null },
            jobTitle = org.any { it.text("data4") != null },
            websites = rows.count { it.mimetype == Mime.WEBSITE && it.data1 != null },
            labels = labelsOf(raw, groups),
            otherFields = rows.count {
                it.mimetype in setOf(Mime.NICKNAME, Mime.IM, Mime.SIP, Mime.RELATION) && it.data1 != null
            },
            appActions = private.mapNotNull { actionName(it.text("data3")) }.distinct(),
            privateRows = private.size,
        )
    }

    /** "Voice call +961 3 039 056" -> "voice call". */
    fun actionName(label: String?): String? {
        if (label.isNullOrBlank()) return null
        val stripped = label.replace(Regex("""\+?\d[\d\s()\-]{3,}"""), "").trim().trim(',').trim()
        return stripped.ifEmpty { null }?.lowercase()
    }

    /** "title · company" parts of the first organization row. */
    fun jobLine(raws: List<RawSource>): Pair<String?, String?>? {
        val org = raws.flatMap { it.rows }.firstOrNull {
            it.mimetype == Mime.ORGANIZATION && (it.data1 != null || it.text("data4") != null)
        } ?: return null
        return org.text("data4") to org.data1
    }

    /** Phonetic given/middle/family name (DATA7..9), joined. */
    fun phoneticName(raws: List<RawSource>): String? =
        raws.flatMap { it.rows }.filter { it.mimetype == Mime.NAME }.firstNotNullOfOrNull { row ->
            listOfNotNull(row.text("data7"), row.text("data8"), row.text("data9"))
                .joinToString(" ").takeIf { it.isNotBlank() }
        }

    const val EVENT_ANNIVERSARY = 1
    const val EVENT_BIRTHDAY = 3

    /** (year or null, month, day) of a provider date ("1990-03-04", "--03-04"). */
    fun parseDate(raw: String): Triple<Int?, Int, Int>? {
        val s = raw.trim()
        Regex("""^--(\d{2})-?(\d{2})""").find(s)?.let { m ->
            val month = m.groupValues[1].toInt()
            val day = m.groupValues[2].toInt()
            return if (valid(2000, month, day)) Triple(null, month, day) else null
        }
        Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(s)?.let { m ->
            val year = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val day = m.groupValues[3].toInt()
            return if (valid(year, month, day)) Triple(year, month, day) else null
        }
        return null
    }

    private fun valid(year: Int, month: Int, day: Int): Boolean =
        runCatching { LocalDate.of(year, month, day) }.isSuccess

    private val NOTE_EVENT_LINE =
        Regex("""^\s*(birthday|anniversary)\s*=\s*(\d{4}-\d{2}-\d{2})""", RegexOption.IGNORE_CASE)

    /** Outlook-style "birthday = 1990-03-04 ..." lines dumped into notes. */
    fun noteEvents(note: String?): List<Pair<Int, String>> =
        note.orEmpty().lines().mapNotNull { line ->
            val m = NOTE_EVENT_LINE.find(line) ?: return@mapNotNull null
            val type = if (m.groupValues[1].equals("birthday", true)) EVENT_BIRTHDAY else EVENT_ANNIVERSARY
            type to m.groupValues[2]
        }

    /** (event type, raw date) pairs of birthday/anniversary rows plus note dumps. */
    fun datedEvents(raws: List<RawSource>): List<Pair<Int, String>> {
        val rows = raws.flatMap { it.rows }
        val events = rows.filter { it.mimetype == Mime.EVENT }.mapNotNull { row ->
            val date = row.data1 ?: return@mapNotNull null
            (row.int("data2") ?: 0) to date
        }
        val notes = rows.filter { it.mimetype == Mime.NOTE }.flatMap { noteEvents(it.data1) }
        return (events + notes).distinct()
    }

    /**
     * The next birthday/anniversary within [window] days of [today], or
     * null. A Feb 29 date falls on Feb 28 in common years. Ties prefer the
     * birthday.
     */
    fun upcoming(events: List<Pair<Int, String>>, today: LocalDate, window: Int = 30): UpcomingDate? =
        events.mapNotNull { (type, raw) ->
            if (type != EVENT_BIRTHDAY && type != EVENT_ANNIVERSARY) return@mapNotNull null
            val (year, month, day) = parseDate(raw) ?: return@mapNotNull null
            var next = occurrence(today.year, month, day)
            if (next.isBefore(today)) next = occurrence(today.year + 1, month, day)
            val days = ChronoUnit.DAYS.between(today, next).toInt()
            if (days > window) return@mapNotNull null
            UpcomingDate(type, days, month, day, year?.let { next.year - it }?.takeIf { it > 0 })
        }.minWithOrNull(
            compareBy<UpcomingDate> { it.daysUntil }.thenBy { if (it.type == EVENT_BIRTHDAY) 0 else 1 },
        )

    private fun occurrence(year: Int, month: Int, day: Int): LocalDate =
        if (month == 2 && day == 29 && !java.time.Year.isLeap(year.toLong())) {
            LocalDate.of(year, 2, 28)
        } else {
            LocalDate.of(year, month, day)
        }
}

/** A coming-up date: [years] = the age turned / years married, when known. */
data class UpcomingDate(val type: Int, val daysUntil: Int, val month: Int, val day: Int, val years: Int?)

/** Outcome of an account write. */
sealed interface AccountOp {
    /**
     * Succeeded. [contactId] is the aggregate the resulting part belongs to
     * now (may differ from before). [sourceKept] = the copy is safe but the
     * original part couldn't be removed afterwards.
     */
    data class Done(val contactId: Long?, val rawId: Long?, val sourceKept: Boolean = false) : AccountOp

    /** Nothing was deleted. */
    data class Failed(val stage: Stage) : AccountOp

    enum class Stage { READ, WRITE, VERIFY }
}

/** Provider I/O for the operations above. */
object ContactAccounts {

    private val READ_ONLY_MARKERS = listOf(
        "whatsapp", "telegram", "viber", "signal", "threema", "skype",
        "org.thoughtcrime", "com.facebook", "linkedin", "tachyon",
    )

    /** Contacts-authority sync adapters: account type -> supports uploading. */
    private fun syncAdapters(): Map<String, Boolean> = runCatching {
        android.content.ContentResolver.getSyncAdapterTypes()
            .filter { it.authority == ContactsContract.AUTHORITY }
            .groupBy { it.accountType }
            .mapValues { (_, list) -> list.any { it.supportsUploading() } }
    }.getOrDefault(emptyMap())

    /** Same rule as ContactEditor: adapter can't upload, or a known messaging app. */
    private fun isReadOnly(type: String?, adapters: Map<String, Boolean>): Boolean {
        if (type == null) return false
        if (adapters[type] == false) return true
        val lower = type.lowercase()
        return READ_ONLY_MARKERS.any { lower.contains(it) }
    }

    private val labelCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Short account-kind label: "Google", "Outlook", "Phone only", an app's name. */
    fun accountLabel(context: Context, account: AccountRef): String {
        val kind = AccountStyle.kindOf(account.type)
        if (kind == AccountKind.LOCAL) {
            return context.getString(com.fancyshark.wpdialer.R.string.acc_phone_only)
        }
        AccountStyle.brandLabel(kind)?.let { return it }
        val type = account.type ?: return context.getString(com.fancyshark.wpdialer.R.string.acc_phone_only)
        labelCache[type]?.let { return it }
        val label = runCatching {
            val auth = android.accounts.AccountManager.get(context).authenticatorTypes
                .firstOrNull { it.type == type }
            auth?.let {
                context.packageManager.getResourcesForApplication(it.packageName)
                    .getString(it.labelId)
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(type, 0),
                ).toString()
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: type.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        labelCache[type] = label
        return label
    }

    /** "Google · alice@gmail.com" (just the kind when there's no name). */
    fun accountFullLabel(context: Context, account: AccountRef): String {
        val kind = accountLabel(context, account)
        val name = account.name?.takeIf {
            it.isNotBlank() && AccountStyle.kindOf(account.type) != AccountKind.LOCAL
        }
        return if (name == null) kind else "$kind · $name"
    }

    // ---- reading ---------------------------------------------------------

    private fun selectAccount(account: AccountRef): Pair<String, Array<String>> {
        val parts = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (account.type == null) {
            parts += "${RawContacts.ACCOUNT_TYPE} IS NULL"
        } else {
            parts += "${RawContacts.ACCOUNT_TYPE} = ?"
            args += account.type
        }
        if (account.name == null) {
            parts += "${RawContacts.ACCOUNT_NAME} IS NULL"
        } else {
            parts += "${RawContacts.ACCOUNT_NAME} = ?"
            args += account.name
        }
        return parts.joinToString(" AND ") to args.toTypedArray()
    }

    private fun cursorValue(c: Cursor, i: Int): Any? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
        else -> c.getString(i)
    }

    private val DATA_COLUMNS: Array<String> =
        (1..15).map { Cols.data(it) }.toTypedArray() + arrayOf(Cols.IS_PRIMARY, Cols.IS_SUPER_PRIMARY)

    private fun loadRows(context: Context, rawIds: Collection<Long>): Map<Long, List<DataRow>> {
        if (rawIds.isEmpty()) return emptyMap()
        val out = HashMap<Long, MutableList<DataRow>>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.RAW_CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
            ) + DATA_COLUMNS,
            "${ContactsContract.Data.RAW_CONTACT_ID} IN (${rawIds.joinToString(",")})",
            null,
            "${ContactsContract.Data._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(2) ?: continue
                val values = HashMap<String, Any?>()
                DATA_COLUMNS.forEachIndexed { i, col -> values[col] = cursorValue(c, i + 3) }
                out.getOrPut(c.getLong(1)) { mutableListOf() } +=
                    DataRow(c.getLong(0), c.getLong(1), mime, values)
            }
        } ?: throw IllegalStateException("data query failed")
        return out
    }

    private fun loadGroups(context: Context, ids: Collection<Long>): Map<Long, GroupInfo> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, GroupInfo>()
        runCatching {
            context.contentResolver.query(
                Groups.CONTENT_URI,
                arrayOf(Groups._ID, Groups.TITLE, Groups.SYSTEM_ID, Groups.ACCOUNT_NAME, Groups.ACCOUNT_TYPE),
                "${Groups._ID} IN (${ids.joinToString(",")})",
                null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    out[c.getLong(0)] = GroupInfo(
                        c.getLong(0), c.getString(1), c.getString(2),
                        AccountRef(c.getString(3), c.getString(4)),
                    )
                }
            }
        }
        return out
    }

    private fun groupIdsOf(rows: Collection<DataRow>): Set<Long> =
        rows.filter { it.mimetype == Mime.GROUP }.mapNotNull { it.text("data1")?.toLongOrNull() }.toSet()

    /** Every live part of contact [contactId], with all rows; null if it's gone. */
    suspend fun loadSources(context: Context, contactId: Long): ContactSources? =
        withContext(Dispatchers.IO) {
            runCatching { loadSourcesSync(context, contactId) }.getOrNull()
        }

    private fun loadSourcesSync(context: Context, contactId: Long): ContactSources? {
        var lookupKey: String? = null
        var name: String? = null
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                lookupKey = c.getString(0)
                name = c.getString(1)
            }
        }
        if (lookupKey == null && name == null) return null
        val adapters = syncAdapters()
        data class Head(val id: Long, val account: AccountRef, val starred: Boolean, val ring: String?, val vm: Boolean)
        val heads = mutableListOf<Head>()
        context.contentResolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(
                RawContacts._ID, RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE,
                RawContacts.STARRED, RawContacts.CUSTOM_RINGTONE, RawContacts.SEND_TO_VOICEMAIL,
            ),
            "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0",
            arrayOf(contactId.toString()),
            "${RawContacts._ID} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                heads += Head(
                    c.getLong(0), AccountRef(c.getString(1), c.getString(2)),
                    c.getInt(3) == 1, c.getString(4), c.getInt(5) == 1,
                )
            }
        } ?: return null
        val rows = loadRows(context, heads.map { it.id })
        val raws = heads.map { h ->
            RawSource(
                rawId = h.id,
                contactId = contactId,
                account = h.account,
                readOnly = isReadOnly(h.account.type, adapters),
                starred = h.starred,
                ringtone = h.ring,
                sendToVoicemail = h.vm,
                rows = rows[h.id].orEmpty(),
            )
        }
        val groups = loadGroups(context, groupIdsOf(raws.flatMap { it.rows }))
        return ContactSources(contactId, lookupKey, name ?: "", raws, groups)
    }

    /**
     * [raw] re-read from the provider right before a write (screens hold a
     * snapshot from when they opened; the part may have been edited or
     * synced since). Null when the part is gone, moved account, or the read
     * failed — the caller must then write nothing.
     */
    private fun reloadRaw(context: Context, raw: RawSource): RawSource? = runCatching {
        var head: RawSource? = null
        context.contentResolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(
                RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE,
                RawContacts.STARRED, RawContacts.CUSTOM_RINGTONE, RawContacts.SEND_TO_VOICEMAIL,
            ),
            "${RawContacts._ID} = ? AND ${RawContacts.DELETED} = 0",
            arrayOf(raw.rawId.toString()),
            null,
        )?.use { c ->
            if (c.moveToFirst() && AccountRef(c.getString(0), c.getString(1)) == raw.account) {
                head = raw.copy(starred = c.getInt(2) == 1, ringtone = c.getString(3), sendToVoicemail = c.getInt(4) == 1)
            }
        } ?: throw IllegalStateException("raw contact query failed")
        val fresh = head ?: return@runCatching null
        fresh.copy(rows = loadRows(context, listOf(raw.rawId))[raw.rawId].orEmpty())
    }.getOrNull()

    /** Group infos for [rows]' memberships, on top of [known] (a snapshot's map). */
    private fun withFreshGroups(context: Context, known: Map<Long, GroupInfo>, rows: Collection<DataRow>): Map<Long, GroupInfo> =
        known + loadGroups(context, groupIdsOf(rows) - known.keys)

    /**
     * Accounts a contact can be moved/copied/merged into: every writable
     * contacts account on the device (visible to a READ_CONTACTS holder
     * without GET_ACCOUNTS), writable types already seen in raw contacts,
     * and phone-only storage. SIM storage is excluded (it can't hold a full
     * contact).
     */
    suspend fun targetAccounts(context: Context): List<AccountRef> = withContext(Dispatchers.IO) {
        val adapters = syncAdapters()
        val accounts = LinkedHashSet<AccountRef>()
        runCatching {
            android.accounts.AccountManager.get(context).accounts.forEach { a ->
                if (adapters[a.type] == true && !isReadOnly(a.type, adapters)) {
                    accounts += AccountRef(a.name, a.type)
                }
            }
        }
        var typedLocal: AccountRef? = null
        runCatching {
            context.contentResolver.query(
                RawContacts.CONTENT_URI,
                arrayOf(RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE),
                "${RawContacts.DELETED} = 0",
                null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val type = c.getString(1) ?: continue
                    if (isReadOnly(type, adapters)) continue
                    val ref = AccountRef(c.getString(0), type)
                    if (AccountStyle.kindOf(type) == AccountKind.LOCAL) {
                        if (typedLocal == null) typedLocal = ref
                    } else {
                        accounts += ref
                    }
                }
            }
        }
        // One phone-only entry: the OEM's typed local account when the
        // device uses one, else the AOSP null account.
        val local = typedLocal ?: AccountRef(null, null)
        (accounts.filter { AccountStyle.canHoldFullContact(it.type) } + local).distinct()
    }

    // ---- small helpers ---------------------------------------------------

    fun contactIdOfRaw(context: Context, rawId: Long): Long? = runCatching {
        context.contentResolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts.CONTACT_ID),
            "${RawContacts._ID} = ? AND ${RawContacts.DELETED} = 0",
            arrayOf(rawId.toString()),
            null,
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    }.getOrNull()

    fun lookupKeyOf(context: Context, contactId: Long): String? = runCatching {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** Current aggregate id for a lookup key (survives re-aggregation), or null. */
    suspend fun resolveLookup(context: Context, lookupKey: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
            context.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull()
    }

    private fun keepOp(type: Int, raw1: Long?, raw1BackRef: Int?, raw2: Long): ContentProviderOperation {
        val b = ContentProviderOperation.newUpdate(AggregationExceptions.CONTENT_URI)
            .withValue(AggregationExceptions.TYPE, type)
            .withValue(AggregationExceptions.RAW_CONTACT_ID2, raw2)
        if (raw1BackRef != null) {
            b.withValueBackReference(AggregationExceptions.RAW_CONTACT_ID1, raw1BackRef)
        } else {
            b.withValue(AggregationExceptions.RAW_CONTACT_ID1, raw1)
        }
        return b.build()
    }

    // ---- link / unlink / delete -------------------------------------------

    /**
     * Shows these parts as one contact: KEEP_TOGETHER for every pair.
     * Nothing is copied or deleted.
     */
    suspend fun link(context: Context, rawIds: List<Long>): Boolean = withContext(Dispatchers.IO) {
        val ids = rawIds.distinct()
        if (ids.size < 2) return@withContext false
        val ops = arrayListOf<ContentProviderOperation>()
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            ops += keepOp(AggregationExceptions.TYPE_KEEP_TOGETHER, ids[i], null, ids[j])
        }
        runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops) }
            .isSuccess.also { Repo.invalidateCaches() }
    }

    /** Splits [rawId] off: KEEP_SEPARATE against every other part. */
    suspend fun unlink(context: Context, rawId: Long, others: List<Long>): Boolean =
        withContext(Dispatchers.IO) {
            val ops = others.filter { it != rawId }.distinct().map {
                keepOp(AggregationExceptions.TYPE_KEEP_SEPARATE, rawId, null, it)
            }
            if (ops.isEmpty()) return@withContext false
            runCatching { context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops)) }
                .isSuccess.also { Repo.invalidateCaches() }
        }

    /**
     * Deletes one part from its account. Not a sync-adapter call, so the
     * deletion syncs to the account's cloud. Never used on read-only parts.
     */
    suspend fun deleteRaw(context: Context, raw: RawSource): Boolean = withContext(Dispatchers.IO) {
        if (raw.readOnly) return@withContext false
        deleteRawSync(context, raw.rawId).also { Repo.invalidateCaches() }
    }

    private fun deleteRawSync(context: Context, rawId: Long): Boolean = runCatching {
        context.contentResolver.delete(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId), null, null,
        ) > 0
    }.getOrDefault(false)

    // ---- groups ----------------------------------------------------------

    private fun groupsIn(context: Context, account: AccountRef): List<GroupInfo> {
        val (sel, args) = selectAccount(account)
        val out = mutableListOf<GroupInfo>()
        runCatching {
            context.contentResolver.query(
                Groups.CONTENT_URI,
                arrayOf(Groups._ID, Groups.TITLE, Groups.SYSTEM_ID, Groups.ACCOUNT_NAME, Groups.ACCOUNT_TYPE),
                // Groups share the RawContacts account column names.
                "$sel AND ${Groups.DELETED} = 0",
                args, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    out += GroupInfo(
                        c.getLong(0), c.getString(1), c.getString(2),
                        AccountRef(c.getString(3), c.getString(4)),
                    )
                }
            }
        }
        return out
    }

    /** User label titles that already exist in [account] (merge preview). */
    suspend fun labelTitlesIn(context: Context, account: AccountRef): List<String> =
        withContext(Dispatchers.IO) {
            groupsIn(context, account).filter { it.systemId == null }.mapNotNull { it.title }
        }

    /**
     * Target-account group ids for [titles] (matched case-insensitively by
     * title, created when missing) and [systemIds] (matched by SYSTEM_ID,
     * never created). Titles whose group can't be created are skipped.
     */
    private fun ensureGroups(
        context: Context,
        target: AccountRef,
        titles: List<String>,
        systemIds: List<String>,
    ): Set<Long> {
        if (titles.isEmpty() && systemIds.isEmpty()) return emptySet()
        val existing = groupsIn(context, target)
        val out = mutableSetOf<Long>()
        systemIds.forEach { sid -> existing.firstOrNull { it.systemId == sid }?.let { out += it.id } }
        val userGroups = existing.filter { it.systemId == null && !it.title.isNullOrBlank() }
        val plan = ContactMerge.mapLabels(titles, userGroups.mapNotNull { it.title })
        plan.matched.values.forEach { title ->
            userGroups.firstOrNull { it.title == title }?.let { out += it.id }
        }
        plan.create.forEach { title ->
            runCatching {
                val cv = ContentValues().apply {
                    put(Groups.ACCOUNT_NAME, target.name)
                    put(Groups.ACCOUNT_TYPE, target.type)
                    put(Groups.TITLE, title)
                    put(Groups.GROUP_VISIBLE, 1)
                }
                context.contentResolver.insert(Groups.CONTENT_URI, cv)?.let { out += ContentUris.parseId(it) }
            }
        }
        return out
    }

    /**
     * [account]'s auto-add groups (Google's "My Contacts"). A new part in
     * that account must join them, or it ends up a hidden "other contact".
     */
    private fun autoAddGroups(context: Context, account: AccountRef): Set<Long> {
        val (sel, args) = selectAccount(account)
        val out = mutableSetOf<Long>()
        runCatching {
            context.contentResolver.query(
                Groups.CONTENT_URI,
                arrayOf(Groups._ID),
                "$sel AND ${Groups.AUTO_ADD} = 1 AND ${Groups.DELETED} = 0",
                args, null,
            )?.use { c -> while (c.moveToNext()) out += c.getLong(0) }
        }
        return out
    }

    private fun sourceGroupRefs(raws: List<RawSource>, groups: Map<Long, GroupInfo>): Pair<List<String>, List<String>> {
        val infos = groupIdsOf(raws.flatMap { it.rows }).mapNotNull { groups[it] }
        val titles = infos.filter { it.systemId == null }.mapNotNull { it.title?.trim()?.takeIf { t -> t.isNotEmpty() } }
            .distinctBy { it.lowercase() }
        val systemIds = infos.mapNotNull { it.systemId }.distinct()
        return titles to systemIds
    }

    // ---- photos ----------------------------------------------------------

    /** Full-size display photo of a part, else its thumbnail; null without one. */
    private fun readPhotoBytes(context: Context, raw: RawSource): ByteArray? {
        // A photo row without an image (no blob, no display-photo file) is no photo.
        val row = raw.rows.firstOrNull { ContactMerge.hasPhoto(it) } ?: return null
        val uri = Uri.withAppendedPath(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, raw.rawId),
            RawContacts.DisplayPhoto.CONTENT_DIRECTORY,
        )
        val full = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
                fd.createInputStream().use { it.readBytes() }
            }
        }.getOrNull()
        val bytes = full?.takeIf { it.isNotEmpty() } ?: row.blob("data15") ?: return null
        return shrinkIfHuge(bytes)
    }

    // A single insert carries the blob across binder (~1 MB limit).
    private const val MAX_PHOTO_BYTES = 800_000

    private fun shrinkIfHuge(bytes: ByteArray): ByteArray {
        if (bytes.size <= MAX_PHOTO_BYTES) return bytes
        return runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1080) sample *= 2
            val bmp = android.graphics.BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return bytes
            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        }.getOrDefault(bytes)
    }

    private fun insertPhoto(context: Context, rawId: Long, bytes: ByteArray): Boolean = runCatching {
        val cv = ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(ContactsContract.Data.MIMETYPE, Mime.PHOTO)
            put(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes)
        }
        context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, cv) != null
    }.getOrDefault(false)

    // ---- row copying -----------------------------------------------------

    private fun putValue(cv: ContentValues, key: String, v: Any?) {
        when (v) {
            null -> Unit
            is Long -> cv.put(key, v)
            is Int -> cv.put(key, v)
            is Double -> cv.put(key, v)
            is String -> cv.put(key, v)
            is ByteArray -> cv.put(key, v)
            else -> cv.put(key, v.toString())
        }
    }

    /** DATA1..DATA14 + primary flags of [row] (sync columns never copied). */
    private fun rowValues(row: DataRow): ContentValues {
        val cv = ContentValues()
        for (i in 1..14) putValue(cv, Cols.data(i), row.values[Cols.data(i)])
        row.int(Cols.IS_PRIMARY)?.let { cv.put(Cols.IS_PRIMARY, it) }
        row.int(Cols.IS_SUPER_PRIMARY)?.let { cv.put(Cols.IS_SUPER_PRIMARY, it) }
        return cv
    }

    private fun insertRowOp(row: DataRow, rawId: Long?, backRef: Int?): ContentProviderOperation {
        val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValues(rowValues(row))
            .withValue(ContactsContract.Data.MIMETYPE, row.mimetype)
        if (backRef != null) {
            b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
        } else {
            b.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
        }
        return b.build()
    }

    private fun noteOp(note: String, rawId: Long?, backRef: Int?): ContentProviderOperation {
        val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.MIMETYPE, Mime.NOTE)
            .withValue(Cols.data(1), note)
        if (backRef != null) {
            b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
        } else {
            b.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
        }
        return b.build()
    }

    private fun groupOp(groupId: Long, rawId: Long?, backRef: Int?): ContentProviderOperation {
        val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValue(ContactsContract.Data.MIMETYPE, Mime.GROUP)
            .withValue(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID, groupId)
        if (backRef != null) {
            b.withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, backRef)
        } else {
            b.withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
        }
        return b.build()
    }

    private fun deleteRowOp(dataId: Long): ContentProviderOperation =
        ContentProviderOperation.newDelete(
            ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, dataId),
        ).build()

    private fun newRawOp(target: AccountRef, starred: Boolean, ringtone: String?, toVoicemail: Boolean) =
        ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
            .withValue(RawContacts.ACCOUNT_NAME, target.name)
            .withValue(RawContacts.ACCOUNT_TYPE, target.type)
            .withValue(RawContacts.STARRED, if (starred) 1 else 0)
            .withValue(RawContacts.CUSTOM_RINGTONE, ringtone)
            .withValue(RawContacts.SEND_TO_VOICEMAIL, if (toVoicemail) 1 else 0)
            .build()

    // ---- verification ----------------------------------------------------

    /** What a written part must contain, checked by reading it back. */
    private data class Expectation(
        val account: AccountRef,
        val minCounts: Map<String, Int>,
        val phones: Set<String>,
        val emails: Set<String>,
        val note: String?,
    )

    private fun expectationFor(
        account: AccountRef,
        rows: List<DataRow>,
        note: String?,
        photo: Boolean,
        groups: Int,
    ): Expectation {
        val counts = rows.groupingBy { it.mimetype }.eachCount().toMutableMap()
        if (note != null) counts[Mime.NOTE] = 1
        if (photo) counts[Mime.PHOTO] = 1
        if (groups > 0) counts[Mime.GROUP] = groups
        return Expectation(
            account,
            counts,
            rows.filter { it.mimetype == Mime.PHONE }.mapNotNull { it.data1 }.toSet(),
            rows.filter { it.mimetype == Mime.EMAIL }.mapNotNull { it.data1?.lowercase() }.toSet(),
            note,
        )
    }

    private fun verify(context: Context, rawId: Long, e: Expectation): Boolean = runCatching {
        var account: AccountRef? = null
        context.contentResolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts.ACCOUNT_NAME, RawContacts.ACCOUNT_TYPE),
            "${RawContacts._ID} = ? AND ${RawContacts.DELETED} = 0",
            arrayOf(rawId.toString()),
            null,
        )?.use { c -> if (c.moveToFirst()) account = AccountRef(c.getString(0), c.getString(1)) }
        val actual = account ?: return@runCatching false
        if (!AccountStyle.sameAccount(e.account, actual)) return@runCatching false
        val rows = loadRows(context, listOf(rawId))[rawId].orEmpty()
        val counts = rows.groupingBy { it.mimetype }.eachCount()
        if (e.minCounts.any { (m, n) -> (counts[m] ?: 0) < n }) return@runCatching false
        val phones = rows.filter { it.mimetype == Mime.PHONE }.mapNotNull { it.data1 }.toSet()
        if (!phones.containsAll(e.phones)) return@runCatching false
        val emails = rows.filter { it.mimetype == Mime.EMAIL }.mapNotNull { it.data1?.lowercase() }.toSet()
        if (!emails.containsAll(e.emails)) return@runCatching false
        if (e.note != null && rows.none { it.mimetype == Mime.NOTE && it.data1 == e.note }) {
            return@runCatching false
        }
        true
    }.getOrDefault(false)

    // ---- copy / move -----------------------------------------------------

    /**
     * Copies part [src] into [target] as a new part kept together with
     * [keepWith]: all standard rows, labels (by title), the full-size photo,
     * starred / ringtone / send-to-voicemail. Verified by read-back; a
     * failed copy is removed again. Returns the new raw id in [AccountOp.Done].
     */
    private fun copySync(
        context: Context,
        snapshot: RawSource,
        snapshotGroups: Map<Long, GroupInfo>,
        target: AccountRef,
        keepWith: List<Long>,
    ): AccountOp {
        if (snapshot.readOnly) return AccountOp.Failed(AccountOp.Stage.READ)
        // Copy what the part holds NOW, not the screen's snapshot.
        val src = reloadRaw(context, snapshot) ?: return AccountOp.Failed(AccountOp.Stage.READ)
        val groups = withFreshGroups(context, snapshotGroups, src.rows)
        val rows = src.rows.filter { it.mimetype in Mime.COPYABLE }
        val photo = readPhotoBytes(context, src)
        val (titles, systemIds) = sourceGroupRefs(listOf(src), groups)
        val groupIds = ensureGroups(context, target, titles, systemIds) + autoAddGroups(context, target)
        val ops = arrayListOf(newRawOp(target, src.starred, src.ringtone, src.sendToVoicemail))
        rows.forEach { ops += insertRowOp(it, null, 0) }
        groupIds.forEach { ops += groupOp(it, null, 0) }
        keepWith.filter { it != src.rawId }.forEach {
            ops += keepOp(AggregationExceptions.TYPE_KEEP_TOGETHER, null, 0, it)
        }
        // Keep the copy with its source too (matters for copy; a moved
        // source's exceptions vanish with it).
        ops += keepOp(AggregationExceptions.TYPE_KEEP_TOGETHER, null, 0, src.rawId)
        val newId = runCatching {
            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            results.firstOrNull()?.uri?.let { ContentUris.parseId(it) }
        }.getOrNull() ?: return AccountOp.Failed(AccountOp.Stage.WRITE)
        if (photo != null) insertPhoto(context, newId, photo)
        val expected = expectationFor(target, rows, null, photo != null, groupIds.size)
        if (!verify(context, newId, expected)) {
            // Ours, unverified: remove it so a retry doesn't leave duplicates.
            deleteRawSync(context, newId)
            return AccountOp.Failed(AccountOp.Stage.VERIFY)
        }
        return AccountOp.Done(contactIdOfRaw(context, newId), newId)
    }

    /** Copy (keeps the original). */
    suspend fun copy(context: Context, sources: ContactSources, src: RawSource, target: AccountRef): AccountOp =
        withContext(Dispatchers.IO) {
            val siblings = sources.raws.map { it.rawId }
            copySync(context, src, sources.groups, target, siblings).also { Repo.invalidateCaches() }
        }

    /**
     * Move = verified copy, then delete the original part (synced deletion).
     * Remaining siblings stay linked to the copy (KEEP_TOGETHER). If the
     * copy fails nothing is deleted.
     */
    suspend fun move(context: Context, sources: ContactSources, src: RawSource, target: AccountRef): AccountOp =
        withContext(Dispatchers.IO) {
            val siblings = sources.raws.map { it.rawId }.filter { it != src.rawId }
            val copied = copySync(context, src, sources.groups, target, siblings)
            if (copied !is AccountOp.Done) return@withContext copied.also { Repo.invalidateCaches() }
            val deleted = deleteRawSync(context, src.rawId)
            Repo.invalidateCaches()
            val newRaw = copied.rawId ?: return@withContext copied
            AccountOp.Done(contactIdOfRaw(context, newRaw), newRaw, sourceKept = !deleted)
        }

    // ---- merge -----------------------------------------------------------

    /** Pure merge input for a part (labels resolved to titles). */
    fun mergeInput(raw: RawSource, groups: Map<Long, GroupInfo>): MergeInput =
        MergeInput(raw.rawId, raw.rows, ProfileFacts.labelsOf(raw, groups))

    /**
     * Merges every WRITABLE part of [contacts] into one part in [target]:
     * the kept part there is updated in place (or a new one created), the
     * result verified by read-back, and only then the other writable parts
     * deleted. Read-only parts are just linked to the result.
     */
    suspend fun merge(
        context: Context,
        contacts: List<ContactSources>,
        target: AccountRef,
        choices: MergeChoices,
    ): AccountOp = withContext(Dispatchers.IO) {
        val snapshot = contacts.flatMap { it.raws }.distinctBy { it.rawId }
        // Merge what the parts hold NOW, not the preview's snapshot. A
        // writable part that vanished means the preview is stale: write
        // nothing. A vanished read-only part is just no longer linked.
        val fresh = snapshot.map { it to reloadRaw(context, it) }
        if (fresh.any { (old, now) -> now == null && !old.readOnly }) {
            return@withContext AccountOp.Failed(AccountOp.Stage.READ)
        }
        val all = fresh.mapNotNull { it.second }
        if (all.isEmpty()) return@withContext AccountOp.Failed(AccountOp.Stage.READ)
        val groups = withFreshGroups(
            context,
            contacts.flatMap { it.groups.entries }.associate { it.key to it.value },
            all.flatMap { it.rows },
        )
        val readOnly = all.filter { it.readOnly }
        // The kept part (if any) goes first, so ties keep its rows.
        val writableAll = all.filter { !it.readOnly }
        val kept = writableAll.firstOrNull { it.account == target }
            ?: writableAll.firstOrNull { AccountStyle.sameAccount(target, it.account) }
        val writable = listOfNotNull(kept) + writableAll.filter { it.rawId != kept?.rawId }
        if (writable.isEmpty()) {
            // Nothing to combine — linking is all a merge can do.
            val ok = link(context, all.map { it.rawId })
            return@withContext if (ok) {
                AccountOp.Done(contactIdOfRaw(context, all.first().rawId), all.first().rawId)
            } else {
                AccountOp.Failed(AccountOp.Stage.WRITE)
            }
        }
        val combined = ContactMerge.combine(writable.map { mergeInput(it, groups) }, choices)
        val finalRows = combined.rows()
        val (_, systemIds) = sourceGroupRefs(writable, groups)
        // Groups live in the kept part's own account (which may be the OEM's
        // typed spelling of the target's local storage).
        val groupAccount = kept?.account ?: target
        val groupIds = runCatching {
            ensureGroups(context, groupAccount, combined.labels, systemIds) + autoAddGroups(context, groupAccount)
        }.getOrDefault(emptySet())
        val photoRaw = combined.photo?.rawId
        val photoBytes = if (photoRaw != null && photoRaw != kept?.rawId) {
            writable.firstOrNull { it.rawId == photoRaw }?.let { readPhotoBytes(context, it) }
        } else {
            null
        }
        val flags = ContactMerge.combineFlags(
            writable.map { PartFlags(it.starred, it.ringtone, it.sendToVoicemail) },
        )
        val starred = flags.starred
        val ringtone = flags.ringtone
        val toVoicemail = flags.sendToVoicemail

        val ops = arrayListOf<ContentProviderOperation>()
        val rawRef: Long?
        val backRef: Int?
        if (kept != null) {
            rawRef = kept.rawId
            backRef = null
            val keepIds = finalRows.filter { it.rawId == kept.rawId }.map { it.dataId }.toSet()
            // Kept rows superseded by the combined set (deduped duplicates,
            // a name the user chose from another part).
            kept.rows.filter { it.mimetype in Mime.COPYABLE && it.mimetype != Mime.NOTE && it.dataId !in keepIds }
                .forEach { ops += deleteRowOp(it.dataId) }
            // Kept rows whose primary flag the combine changed (the chosen
            // organization becomes primary, the others secondary).
            val keptById = kept.rows.associateBy { it.dataId }
            finalRows.filter { it.rawId == kept.rawId }.forEach { row ->
                val old = keptById[row.dataId] ?: return@forEach
                val primary = row.int(Cols.IS_PRIMARY) ?: 0
                val superPrimary = row.int(Cols.IS_SUPER_PRIMARY) ?: 0
                if (primary != (old.int(Cols.IS_PRIMARY) ?: 0) || superPrimary != (old.int(Cols.IS_SUPER_PRIMARY) ?: 0)) {
                    ops += ContentProviderOperation
                        .newUpdate(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, row.dataId))
                        .withValue(Cols.IS_PRIMARY, primary)
                        .withValue(Cols.IS_SUPER_PRIMARY, superPrimary)
                        .build()
                }
            }
            finalRows.filter { it.rawId != kept.rawId }.forEach { ops += insertRowOp(it, kept.rawId, null) }
            val keptNotes = kept.rows.filter { it.mimetype == Mime.NOTE }
            val noteUnchanged = keptNotes.size == 1 && keptNotes[0].data1 == combined.note
            if (!noteUnchanged) {
                keptNotes.forEach { ops += deleteRowOp(it.dataId) }
                combined.note?.let { ops += noteOp(it, kept.rawId, null) }
            }
            // The kept photo is replaced only after the new one is written
            // (below) — never deleted on the strength of a read alone.
            val existingGroups = groupIdsOf(kept.rows)
            groupIds.filter { it !in existingGroups }.forEach { ops += groupOp(it, kept.rawId, null) }
            ops += ContentProviderOperation
                .newUpdate(ContentUris.withAppendedId(RawContacts.CONTENT_URI, kept.rawId))
                .withValue(RawContacts.STARRED, if (starred) 1 else 0)
                .withValue(RawContacts.CUSTOM_RINGTONE, ringtone)
                .withValue(RawContacts.SEND_TO_VOICEMAIL, if (toVoicemail) 1 else 0)
                .build()
        } else {
            rawRef = null
            backRef = 0
            ops += newRawOp(target, starred, ringtone, toVoicemail)
            finalRows.forEach { ops += insertRowOp(it, null, 0) }
            combined.note?.let { ops += noteOp(it, null, 0) }
            groupIds.forEach { ops += groupOp(it, null, 0) }
        }
        // Read-only parts (WhatsApp...) can't be merged — they stay linked.
        readOnly.forEach { ops += keepOp(AggregationExceptions.TYPE_KEEP_TOGETHER, rawRef, backRef, it.rawId) }

        val keptId = runCatching {
            val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            kept?.rawId ?: results.firstOrNull()?.uri?.let { ContentUris.parseId(it) }
        }.getOrNull()
        if (keptId == null) {
            Repo.invalidateCaches()
            return@withContext AccountOp.Failed(AccountOp.Stage.WRITE)
        }
        val photoWritten = photoBytes != null && insertPhoto(context, keptId, photoBytes)
        if (photoWritten && kept != null) {
            // The chosen photo is saved: now the kept part's old one can go.
            kept.rows.filter { it.mimetype == Mime.PHOTO }.forEach { old ->
                runCatching {
                    context.contentResolver.delete(
                        ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, old.dataId), null, null,
                    )
                }
            }
        }
        // A chosen photo that couldn't be read/written degrades to the kept
        // part's own photo (still there) rather than failing the merge.
        val keptHasPhoto = kept?.rows?.any { ContactMerge.hasPhoto(it) } == true
        val expected = expectationFor(
            groupAccount, finalRows, combined.note,
            photo = photoWritten || keptHasPhoto,
            groups = (groupIds + if (kept != null) groupIdsOf(kept.rows) else emptySet()).size,
        )
        if (!verify(context, keptId, expected)) {
            if (kept == null) deleteRawSync(context, keptId)
            Repo.invalidateCaches()
            return@withContext AccountOp.Failed(AccountOp.Stage.VERIFY)
        }
        // Verified — only now remove the other writable parts.
        var allDeleted = true
        writable.filter { it.rawId != keptId }.forEach { if (!deleteRawSync(context, it.rawId)) allDeleted = false }
        Repo.invalidateCaches()
        AccountOp.Done(contactIdOfRaw(context, keptId), keptId, sourceKept = !allDeleted)
    }

    // ---- live tiles ------------------------------------------------------

    /**
     * Contact live tiles are bound by lookup key; a move/merge can retire
     * the old key. Re-points tiles bound to any of [oldKeys] at
     * [newContactId]'s current key and refreshes them. Blocking.
     */
    fun rebindTiles(context: Context, oldKeys: Collection<String>, newContactId: Long?) {
        if (oldKeys.isEmpty() || newContactId == null) return
        runCatching {
            val newKey = lookupKeyOf(context, newContactId) ?: return
            val name = com.fancyshark.wpdialer.widget.ContactTiles.lookupKeyFor(context, newContactId)?.second ?: ""
            val changed = com.fancyshark.wpdialer.widget.ContactTiles.rebind(context, oldKeys.toSet(), newKey, name)
            if (changed) com.fancyshark.wpdialer.widget.ContactTileWidget.updateAllSync(context)
        }
    }
}

/**
 * In-memory "this contact is now that one" map: after a move/merge/link
 * the aggregate id can change, and profiles still on the back stack follow
 * the redirect instead of finding their contact gone. Not persisted (ids
 * are unstable) — after process death a profile falls back to its lookup key.
 */
object ContactRedirects {
    private val _map = kotlinx.coroutines.flow.MutableStateFlow<Map<Long, Long>>(emptyMap())
    val map: kotlinx.coroutines.flow.StateFlow<Map<Long, Long>> = _map

    fun record(old: Collection<Long>, new: Long?) {
        if (new == null) return
        val add = old.filter { it != new }.associateWith { new }
        if (add.isNotEmpty()) _map.value = _map.value + add - new
    }

    /** Follows redirects from [id] (cycle-safe). */
    fun resolve(map: Map<Long, Long>, id: Long): Long {
        var cur = id
        repeat(16) { cur = map[cur] ?: return cur }
        return cur
    }
}
