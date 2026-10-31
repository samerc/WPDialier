package com.fancyshark.wpdialer.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.fancyshark.wpdialer.MainActivity
import com.fancyshark.wpdialer.R
import com.fancyshark.wpdialer.data.HistoryItem
import com.fancyshark.wpdialer.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Launcher shortcuts: dynamic "call <name>" entries for the most-called
 * contacts, and pinned call shortcuts from a profile. Static ones (dialpad,
 * missed calls) live in res/xml/shortcuts.xml.
 *
 * Every shortcut targets the non-exported ShortcutLauncher alias — the
 * launcher starts shortcuts as our own package, so the alias is reachable
 * for it but not for other apps, and only the alias may place calls.
 */
object Shortcuts {

    /** Static shortcuts in shortcuts.xml — they share the per-activity cap. */
    private const val STATIC_COUNT = 2
    private const val MAX_DYNAMIC = 3
    private const val ICON_PX = 192

    // Last published set; skips a redundant (rate-limited) publish on every
    // resume when nothing changed.
    @Volatile
    private var lastSignature: String? = null

    /** An explicit intent into our ShortcutLauncher alias. */
    fun launchIntent(context: Context, action: String, data: Uri? = null): Intent =
        Intent(action, data).setClassName(context.packageName, MainActivity.SHORTCUT_LAUNCHER)

    /** A number that came up often in history, with how often. */
    data class Callee(val number: String, val count: Int)

    /**
     * The [limit] most frequent conversation partners in [history] (newest
     * first), keyed by [Repo.numberKey] so national and E.164 spellings
     * count together. Missed / rejected / blocked rows aren't conversations
     * and don't count; ties go to the more recent contact. The returned
     * number is the newest spelling seen for that line.
     */
    fun topCallees(history: List<HistoryItem>, limit: Int): List<Callee> {
        val counted = LinkedHashMap<String, Pair<String, Int>>()
        history.forEach { item ->
            if (item.number.isBlank()) return@forEach
            if (item.type == CallLog.Calls.MISSED_TYPE ||
                item.type == CallLog.Calls.REJECTED_TYPE ||
                item.type == CallLog.Calls.BLOCKED_TYPE ||
                item.type == CallLog.Calls.VOICEMAIL_TYPE
            ) {
                return@forEach
            }
            val key = Repo.numberKey(item.number).ifEmpty { return@forEach }
            val prev = counted[key]
            counted[key] = (prev?.first ?: item.number) to ((prev?.second ?: 0) + 1)
        }
        // sortedByDescending is stable: equal counts keep first-seen
        // (= most recent) order.
        return counted.values
            .sortedByDescending { it.second }
            .take(limit)
            .map { Callee(it.first, it.second) }
    }

    private data class Picked(val number: String, val lookupKey: String, val name: String, val photo: String?)

    private fun byNumber(context: Context, number: String): Picked? = runCatching {
        context.contentResolver.query(
            Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(
                ContactsContract.PhoneLookup.LOOKUP_KEY,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_URI,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@runCatching null
            val key = c.getString(0) ?: return@runCatching null
            val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: return@runCatching null
            Picked(number, key, name, c.getString(2))
        }
    }.getOrNull()

    /**
     * Republishes the "call <name>" dynamic shortcuts from already-loaded
     * history. Cheap when unchanged (a few PhoneLookups, no publish); only
     * contacts get shortcuts — an unknown number has no name to show.
     */
    suspend fun refreshDynamic(context: Context, history: List<HistoryItem>) =
        withContext(Dispatchers.IO) {
            runCatching {
                val app = context.applicationContext
                val room = (ShortcutManagerCompat.getMaxShortcutCountPerActivity(app) - STATIC_COUNT)
                    .coerceIn(0, MAX_DYNAMIC)
                val picked = mutableListOf<Picked>()
                // A few spare candidates: numbers without a contact are skipped,
                // and two numbers of one contact collapse to one shortcut.
                for (callee in topCallees(history, room + 5)) {
                    if (picked.size >= room) break
                    val p = byNumber(app, callee.number) ?: continue
                    if (picked.none { it.lookupKey == p.lookupKey }) picked += p
                }
                val accent = com.fancyshark.wpdialer.ui.AccentStore.accent.value
                val signature = accent.name + picked.joinToString("") {
                    "\u0001${it.number}\u0001${it.name}\u0001${it.photo}"
                }
                if (signature == lastSignature) return@runCatching
                val shortcuts = picked.mapIndexed { rank, p ->
                    callShortcut(app, "call_" + Repo.exactNumberKey(app, p.number), p.name, p.number, p.photo)
                        .setRank(rank)
                        .build()
                }
                if (ShortcutManagerCompat.setDynamicShortcuts(app, shortcuts)) {
                    lastSignature = signature
                }
            }
        }

    /**
     * Asks the launcher to pin a "call <name>" shortcut. False when the
     * launcher doesn't support pinning (caller shows a hint).
     */
    suspend fun requestPinCall(
        context: Context,
        name: String,
        number: String,
        photoUri: String?,
    ): Boolean {
        val app = context.applicationContext
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(app)) return false
        val info = withContext(Dispatchers.IO) {
            callShortcut(app, "pin_call_" + Repo.exactNumberKey(app, number), name, number, photoUri)
                .build()
        }
        return runCatching { ShortcutManagerCompat.requestPinShortcut(app, info, null) }
            .getOrDefault(false)
    }

    private fun callShortcut(
        context: Context,
        id: String,
        name: String,
        number: String,
        photoUri: String?,
    ): ShortcutInfoCompat.Builder {
        val intent = launchIntent(
            context, MainActivity.ACTION_SHORTCUT_CALL, Uri.fromParts("tel", number, null),
        ).putExtra(MainActivity.EXTRA_SHORTCUT_ID, id)
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(name)
            .setLongLabel(context.getString(R.string.contact_call_label, name))
            .setIcon(iconFor(context, name, photoUri))
            .setIntent(intent)
    }

    /**
     * Adaptive bitmap: the photo full-bleed, else a WP accent square with
     * the initials kept inside the adaptive safe zone (center 2/3).
     */
    private fun iconFor(context: Context, name: String, photoUri: String?): IconCompat {
        ContactTiles.decodeSquare(context, photoUri, ICON_PX)?.let {
            return IconCompat.createWithAdaptiveBitmap(it)
        }
        val accent = com.fancyshark.wpdialer.ui.AccentStore.accent.value.color
        val bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(accent.toArgb())
        val initials = com.fancyshark.wpdialer.ui.initialsOf(name)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = com.fancyshark.wpdialer.ui.Metro.onAccent(accent).toArgb()
            textAlign = Paint.Align.CENTER
            textSize = ICON_PX * if (initials.length > 1) 0.24f else 0.30f
            typeface = runCatching {
                androidx.core.content.res.ResourcesCompat.getFont(context, R.font.selawkl)
            }.getOrNull()
        }
        val y = ICON_PX / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initials, ICON_PX / 2f, y, paint)
        return IconCompat.createWithAdaptiveBitmap(bmp)
    }
}
