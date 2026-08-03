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
)

data class HistoryItem(
    val id: Long,
    val number: String,
    val name: String?,
    val type: Int,
    val date: Long,
)

object Repo {

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
                        val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                            context.resources, c.getInt(1), c.getString(2),
                        ).toString().lowercase()
                        phones += ContactPhone(number, label)
                    }
                }
            }
            var note: String? = null
            var address: String? = null
            runCatching {
                context.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.Data.DATA1),
                    "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} IN (?, ?)",
                    arrayOf(
                        id.toString(),
                        ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
                        ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
                    ),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val value = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        when (c.getString(0)) {
                            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE ->
                                if (note == null) note = value
                            ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE ->
                                if (address == null) address = value
                        }
                    }
                }
            }
            ContactDetail(id, contactName, photo, phones, note, address, lookupKey, starred)
        }

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
    fun pretty(context: Context, number: String): String =
        runCatching {
            android.telephony.PhoneNumberUtils.formatNumber(
                number, Countries.defaultRegionCode(context),
            )
        }.getOrNull() ?: number

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

    fun historyTypeLabel(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming call"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing call"
        CallLog.Calls.MISSED_TYPE -> "missed call"
        CallLog.Calls.REJECTED_TYPE -> "declined call"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.BLOCKED_TYPE -> "blocked call"
        else -> "call"
    }

    fun relativeTime(date: Long): String =
        DateUtils.getRelativeTimeSpanString(
            date, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        ).toString().lowercase()
}
