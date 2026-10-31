package com.fancyshark.wpdialer.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import com.fancyshark.wpdialer.data.Repo

/** What a contact tile does when tapped. Stored as its [key] (data, never localized). */
enum class TileAction(val key: String) {
    PROFILE("profile"),
    CALL("call");

    companion object {
        fun of(key: String?): TileAction = entries.firstOrNull { it.key == key } ?: PROFILE
    }
}

/**
 * One placed contact tile's binding. Keyed by the contact LOOKUP KEY —
 * aggregate contact IDs change on re-aggregation and must never be
 * persisted. [name] is the last name seen, shown if the contact vanishes.
 */
data class TileBinding(
    val lookupKey: String,
    val name: String,
    val action: TileAction,
    val boundAt: Long = 0L,
)

/** appWidgetId -> [TileBinding], in its own prefs file (not the backed-up "wp" one). */
object ContactTileStore {

    private const val FILE = "contact_tiles"
    private const val PENDING = "pending"

    // A pin requested from a profile is only claimed this long afterwards —
    // a stale one must not hijack a tile later placed from the picker.
    const val PIN_WINDOW_MS = 60_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(context: Context, widgetId: Int): TileBinding? = read(context, "w$widgetId")

    fun put(context: Context, widgetId: Int, binding: TileBinding) =
        write(context, "w$widgetId", binding)

    fun remove(context: Context, widgetId: Int) {
        val p = prefs(context)
        val edit = p.edit()
        p.all.keys.filter { it.startsWith("w$widgetId.") }.forEach { edit.remove(it) }
        edit.apply()
    }

    fun clear(context: Context) = prefs(context).edit().clear().apply()

    /** Every placed tile's binding, by widget id (the pending pin excluded). */
    fun all(context: Context): Map<Int, TileBinding> =
        prefs(context).all.keys
            .filter { it.startsWith("w") && it.endsWith(".lookup") }
            .mapNotNull { k -> k.removePrefix("w").removeSuffix(".lookup").toIntOrNull() }
            .mapNotNull { id -> get(context, id)?.let { id to it } }
            .toMap()

    /** Remembered right before requestPinAppWidget, for the configure step. */
    fun setPendingPin(context: Context, binding: TileBinding) = write(context, PENDING, binding)

    /** The pending pin if still fresh; consumed either way. */
    fun takePendingPin(context: Context, now: Long = System.currentTimeMillis()): TileBinding? {
        val pending = read(context, PENDING) ?: return null
        val p = prefs(context)
        val edit = p.edit()
        p.all.keys.filter { it.startsWith("$PENDING.") }.forEach { edit.remove(it) }
        edit.apply()
        return pending.takeIf { now - it.boundAt in 0..PIN_WINDOW_MS }
    }

    private fun read(context: Context, prefix: String): TileBinding? {
        val p = prefs(context)
        val key = p.getString("$prefix.lookup", null)?.takeIf { it.isNotEmpty() } ?: return null
        return TileBinding(
            lookupKey = key,
            name = p.getString("$prefix.name", "") ?: "",
            action = TileAction.of(p.getString("$prefix.action", null)),
            boundAt = p.getLong("$prefix.at", 0L),
        )
    }

    private fun write(context: Context, prefix: String, binding: TileBinding) {
        prefs(context).edit()
            .putString("$prefix.lookup", binding.lookupKey)
            .putString("$prefix.name", binding.name)
            .putString("$prefix.action", binding.action.key)
            .putLong("$prefix.at", binding.boundAt)
            .apply()
    }
}

/** A contact as a tile / shortcut needs it, resolved fresh from the provider. */
data class TileContact(
    val id: Long,
    val lookupKey: String,
    val name: String,
    val photoUri: String?,
    val numbers: List<String>,
    /** Super-primary number, else primary, else the first one. */
    val primaryNumber: String?,
)

object ContactTiles {

    /**
     * Resolves a lookup key. The lookup URI survives re-aggregation (the
     * provider falls back to matching the key's raw-contact parts), so a
     * merged or split contact still finds its tile. Null when the contact
     * is gone or READ_CONTACTS is missing.
     */
    fun resolve(context: Context, lookupKey: String): TileContact? = runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
        context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_URI,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@runCatching null
            val id = c.getLong(0)
            val numbers = phonesOf(context, id)
            TileContact(
                id = id,
                lookupKey = c.getString(1) ?: lookupKey,
                name = c.getString(2) ?: "",
                photoUri = c.getString(3),
                numbers = numbers,
                primaryNumber = numbers.firstOrNull(),
            )
        }
    }.getOrNull()

    /**
     * Re-points tiles bound to any of [oldKeys] at [newKey] (a move/merge
     * replaced the contact's parts, retiring its lookup key). Keeps each
     * tile's action. True when any binding changed.
     */
    fun rebind(context: Context, oldKeys: Set<String>, newKey: String, newName: String): Boolean {
        var changed = false
        ContactTileStore.all(context).forEach { (id, binding) ->
            if (binding.lookupKey in oldKeys && binding.lookupKey != newKey) {
                ContactTileStore.put(
                    context, id,
                    binding.copy(lookupKey = newKey, name = newName.ifBlank { binding.name }),
                )
                changed = true
            }
        }
        return changed
    }

    /** LOOKUP_KEY + display name for an aggregate id (picker rows carry ids only). */
    fun lookupKeyFor(context: Context, contactId: Long): Pair<String, String>? = runCatching {
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return@runCatching null
            val key = c.getString(0) ?: return@runCatching null
            key to (c.getString(1) ?: "")
        }
    }.getOrNull()

    /** The contact's numbers, primary first, deduped by [Repo.numbersMatch]. */
    fun phonesOf(context: Context, contactId: Long): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, " +
                    "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    if (out.none { Repo.numbersMatch(it, n) }) out += n
                }
            }
        }
        return out
    }

    /**
     * Numbers of the unseen missed calls — the same NEW / IS_READ notion
     * [TileWidget] counts (NULL is_read included). Queried once per refresh
     * and shared by every placed tile.
     */
    fun unseenMissedNumbers(context: Context): List<String> = runCatching {
        val out = mutableListOf<String>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER),
            "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE} AND " +
                "${CallLog.Calls.NEW} = 1 AND " +
                "(${CallLog.Calls.IS_READ} = 0 OR ${CallLog.Calls.IS_READ} IS NULL)",
            null, null,
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { out += it } }
        out
    }.getOrDefault(emptyList())

    /** How many of [missed] came from one of [contactNumbers]. */
    fun missedFrom(missed: List<String>, contactNumbers: List<String>): Int =
        missed.count { m -> contactNumbers.any { Repo.numbersMatch(it, m) } }

    /**
     * Largest power-of-two subsample that keeps the SHORT side at or above
     * [target] — the result is then center-cropped/scaled to exactly target.
     */
    fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        val short = minOf(width, height)
        var sample = 1
        while (short / (sample * 2) >= target) sample *= 2
        return sample
    }

    /**
     * A [size]-px square, center-cropped from the contact photo. Bounds
     * decode + subsample first: a full-resolution display photo inside
     * RemoteViews (or a shortcut icon) can blow the binder transaction limit.
     */
    fun decodeSquare(context: Context, photoUri: String?, size: Int): Bitmap? {
        if (photoUri == null) return null
        return runCatching {
            val uri = Uri.parse(photoUri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, size)
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching null
            val side = minOf(decoded.width, decoded.height)
            val square = Bitmap.createBitmap(
                decoded,
                (decoded.width - side) / 2, (decoded.height - side) / 2, side, side,
            )
            if (side > size) Bitmap.createScaledBitmap(square, size, size, true) else square
        }.getOrNull()
    }
}
