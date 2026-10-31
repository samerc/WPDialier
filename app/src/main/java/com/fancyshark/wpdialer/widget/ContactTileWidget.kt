package com.fancyshark.wpdialer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.fancyshark.wpdialer.MainActivity
import com.fancyshark.wpdialer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * WP "pin to Start" people tile: one contact's photo (or accent square with
 * initials), the name bottom-left, and the count of unseen missed calls from
 * that contact. Bound per appWidgetId to a contact lookup key in
 * [ContactTileStore]; refreshed on the same events as [TileWidget] (which
 * forwards to [updateAllSync]), never on a timer.
 */
class ContactTileWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        // requestPinAppWidget's success callback (non-exported receiver —
        // only our own PendingIntent can deliver it). The host fills in the
        // new widget id; extras we set take precedence over fill-ins.
        if (intent.action == ACTION_PINNED) {
            val id = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
            )
            val key = intent.getStringExtra(EXTRA_LOOKUP_KEY)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && !key.isNullOrEmpty() &&
                isOurs(context, id)
            ) {
                ContactTileStore.takePendingPin(context)
                ContactTileStore.put(
                    context, id,
                    TileBinding(
                        lookupKey = key,
                        name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
                        action = TileAction.PROFILE,
                        boundAt = System.currentTimeMillis(),
                    ),
                )
                val pending = goAsync()
                val app = context.applicationContext
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        updateSync(app, intArrayOf(id))
                    } finally {
                        pending.finish()
                    }
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Contacts + call-log queries and a photo decode: off the receiver
        // main thread, inside the goAsync window.
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateSync(app, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { ContactTileStore.remove(context, it) }
    }

    override fun onDisabled(context: Context) {
        // Last tile removed: nothing left to keep (pending pin included).
        ContactTileStore.clear(context)
    }

    companion object {

        private const val ACTION_PINNED = "com.fancyshark.wpdialer.CONTACT_TILE_PINNED"
        private const val EXTRA_LOOKUP_KEY = "lookup_key"
        private const val EXTRA_NAME = "name"

        // Tile photo edge in px: crisp on a 2x2 tile, ~350 KB as ARGB —
        // comfortably inside the RemoteViews binder budget.
        private const val PHOTO_PX = 300

        private fun component(context: Context) =
            ComponentName(context, ContactTileWidget::class.java)

        /** The id really is one of our contact tiles (not the app tile / garbage). */
        fun isOurs(context: Context, widgetId: Int): Boolean = runCatching {
            AppWidgetManager.getInstance(context)
                ?.getAppWidgetInfo(widgetId)?.provider == component(context)
        }.getOrDefault(false)

        /**
         * Asks the launcher to pin a tile for this contact. False when the
         * launcher can't pin widgets (caller shows a hint instead).
         */
        fun requestPin(context: Context, lookupKey: String, name: String): Boolean {
            val mgr = AppWidgetManager.getInstance(context) ?: return false
            if (!runCatching { mgr.isRequestPinAppWidgetSupported }.getOrDefault(false)) return false
            // For launchers that still run the configure activity on a pin:
            // it claims this instead of asking for the contact again.
            ContactTileStore.setPendingPin(
                context,
                TileBinding(lookupKey, name, TileAction.PROFILE, System.currentTimeMillis()),
            )
            // MUTABLE is required: the host fills in EXTRA_APPWIDGET_ID. The
            // intent is explicit (our own non-exported receiver).
            val callback = PendingIntent.getBroadcast(
                context, lookupKey.hashCode(),
                Intent(context, ContactTileWidget::class.java)
                    .setAction(ACTION_PINNED)
                    .putExtra(EXTRA_LOOKUP_KEY, lookupKey)
                    .putExtra(EXTRA_NAME, name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            return runCatching { mgr.requestPinAppWidget(component(context), null, callback) }
                .getOrDefault(false)
        }

        /** Async refresh of one tile (configure activity, main thread). */
        fun update(context: Context, widgetId: Int) {
            val app = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch { updateSync(app, intArrayOf(widgetId)) }
        }

        /**
         * Refresh every placed contact tile; no-op when none are placed.
         * Blocking — background threads only (called from
         * [TileWidget.updateAllSync], so every app-tile hook covers these too).
         */
        fun updateAllSync(context: Context) {
            val app = context.applicationContext
            runCatching {
                val mgr = AppWidgetManager.getInstance(app) ?: return
                val ids = mgr.getAppWidgetIds(component(app))
                if (ids.isNotEmpty()) updateSync(app, ids)
            }
        }

        private fun updateSync(context: Context, ids: IntArray) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val missed = ContactTiles.unseenMissedNumbers(context)
            com.fancyshark.wpdialer.ui.AccentStore.init(context)
            ids.forEach { id -> runCatching { push(context, mgr, id, missed) } }
        }

        private fun push(context: Context, mgr: AppWidgetManager, id: Int, missed: List<String>) {
            // Unbound (placed, configure still open): keep the initial layout.
            val binding = ContactTileStore.get(context, id) ?: return
            val contact = ContactTiles.resolve(context, binding.lookupKey)
            // Follow renames and lookup-key drift (re-aggregation re-keys).
            if (contact != null &&
                (contact.name != binding.name || contact.lookupKey != binding.lookupKey) &&
                contact.name.isNotEmpty()
            ) {
                ContactTileStore.put(
                    context, id,
                    binding.copy(lookupKey = contact.lookupKey, name = contact.name),
                )
            }
            val name = contact?.name?.takeIf { it.isNotEmpty() } ?: binding.name
            val accent = com.fancyshark.wpdialer.ui.AccentStore.accent.value.color
            val onAccent = com.fancyshark.wpdialer.ui.Metro.onAccent(accent).toArgb()
            val style = com.fancyshark.wpdialer.data.AppPrefs.tileStyle(context)

            val photo = contact?.let { ContactTiles.decodeSquare(context, it.photoUri, PHOTO_PX) }
            val count = contact?.let { ContactTiles.missedFrom(missed, it.numbers) } ?: 0
            // The back face (name / missed count) is only worth flipping to
            // when the front hides the name behind a photo or there's news.
            val flip = (photo != null || count > 0) && TileWidget.mayFlip(context, style)
            val views = RemoteViews(
                context.packageName,
                if (flip) R.layout.widget_contact_tile_flip else R.layout.widget_contact_tile,
            )
            views.setInt(
                R.id.ct_root, "setBackgroundColor",
                TileWidget.tileBackground(accent, style.transparent),
            )

            if (photo != null) {
                views.setImageViewBitmap(R.id.ct_photo, photo)
                views.setViewVisibility(R.id.ct_photo, View.VISIBLE)
                views.setViewVisibility(R.id.ct_scrim, View.VISIBLE)
                views.setViewVisibility(R.id.ct_initials, View.GONE)
            } else {
                views.setViewVisibility(R.id.ct_photo, View.GONE)
                views.setViewVisibility(R.id.ct_scrim, View.GONE)
                views.setViewVisibility(R.id.ct_initials, View.VISIBLE)
                views.setTextViewText(R.id.ct_initials, com.fancyshark.wpdialer.ui.initialsOf(name))
                views.setTextColor(R.id.ct_initials, onAccent)
            }
            // Over a photo the text is white on the scrim; on the bare accent
            // it follows the lime/yellow contrast rule.
            val textColor = if (photo != null) android.graphics.Color.WHITE else onAccent
            views.setTextViewText(R.id.ct_name, name)
            views.setTextColor(R.id.ct_name, textColor)

            views.setTextViewText(R.id.ct_count, count.toString())
            views.setTextColor(R.id.ct_count, textColor)
            views.setViewVisibility(R.id.ct_count, if (count > 0) View.VISIBLE else View.GONE)

            if (flip) {
                // Back face sits on the bare accent: contrast rule throughout.
                views.setTextViewText(R.id.ct_back_name, name)
                views.setTextColor(R.id.ct_back_name, onAccent)
                views.setViewVisibility(
                    R.id.ct_back_missed, if (count > 0) View.VISIBLE else View.GONE,
                )
                if (count > 0) {
                    views.setTextViewText(R.id.ct_back_count, count.toString())
                    views.setTextColor(R.id.ct_back_count, onAccent)
                    views.setTextViewText(
                        R.id.ct_back_label,
                        context.resources.getQuantityString(R.plurals.tile_back_missed, count),
                    )
                    views.setTextColor(R.id.ct_back_label, onAccent)
                }
            }
            views.setContentDescription(
                R.id.ct_root,
                if (count > 0) {
                    context.resources.getQuantityString(
                        R.plurals.contact_tile_a11y_missed, count, name, count,
                    )
                } else {
                    name
                },
            )

            views.setOnClickPendingIntent(R.id.ct_root, tapIntent(context, id, binding, contact))
            runCatching { mgr.updateAppWidget(id, views) }
        }

        private fun tapIntent(
            context: Context,
            id: Int,
            binding: TileBinding,
            contact: TileContact?,
        ): PendingIntent {
            val number = contact?.primaryNumber
            val intent = when {
                // Deleted contact (or no permission): the tile just opens the app.
                contact == null -> Intent(context, MainActivity::class.java)
                binding.action == TileAction.CALL && number != null ->
                    Shortcuts.launchIntent(
                        context, MainActivity.ACTION_SHORTCUT_CALL,
                        android.net.Uri.fromParts("tel", number, null),
                    )
                else -> Shortcuts.launchIntent(
                    context, MainActivity.ACTION_OPEN_CONTACT,
                    ContactsContract.Contacts.getLookupUri(contact.id, contact.lookupKey),
                )
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Request code per widget id: each tile keeps its own target.
            return PendingIntent.getActivity(
                context, REQUEST_BASE + id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        // Clear of TileWidget's request code (6) and the notification ones.
        private const val REQUEST_BASE = 0x5100_0000
    }
}
