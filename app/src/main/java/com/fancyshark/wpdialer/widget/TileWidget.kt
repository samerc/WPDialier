package com.fancyshark.wpdialer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.CallLog
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import com.fancyshark.wpdialer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * WP-style start-screen tile: accent square with the app glyph and a live
 * missed-call count. Updated from missed-call events and app opens, not a
 * timer (updatePeriodMillis is 0).
 */
class TileWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // The call-log query must not run on the receiver main thread
        // (cold provider at boot can eat the whole ANR window).
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                appWidgetIds.forEach { id ->
                    runCatching { push(app, appWidgetManager, id) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {

        /**
         * Refresh every placed tile; safe no-op when none are placed.
         * Dispatches to IO internally — callers may be on the main thread
         * (accent tap, onResume) and the count is a cross-process query.
         * NEVER call from inside a manifest-receiver's goAsync window —
         * the launched work would outlive it; use [updateAllSync] there.
         */
        fun updateAll(context: Context) {
            val app = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch { updateAllSync(app) }
        }

        /** Inline variant for background threads whose lifetime is managed
         *  by the caller (goAsync windows, existing IO coroutines). */
        fun updateAllSync(context: Context) {
            val app = context.applicationContext
            runCatching {
                val mgr = AppWidgetManager.getInstance(app) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(app, TileWidget::class.java))
                ids.forEach { push(app, mgr, it) }
            }
        }

        private fun push(context: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_tile)
            com.fancyshark.wpdialer.ui.AccentStore.init(context)
            views.setInt(
                R.id.widget_root, "setBackgroundColor",
                com.fancyshark.wpdialer.ui.AccentStore.accent.value.color.toArgb(),
            )
            val missed = missedCount(context)
            views.setTextViewText(R.id.widget_count, missed.toString())
            views.setViewVisibility(
                R.id.widget_count,
                if (missed > 0) View.VISIBLE else View.GONE,
            )
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context, 6,
                    Intent(context, com.fancyshark.wpdialer.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            runCatching { mgr.updateAppWidget(id, views) }
        }

        // Unseen missed calls, same notion the system badge uses. Returns 0
        // when READ_CALL_LOG is missing (fresh install pre-wizard). is_read
        // can be NULL on OEM/restored call logs — "IS_READ = 0" alone would
        // silently exclude those rows (AOSP Dialer guards the same way).
        private fun missedCount(context: Context): Int = runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE} AND " +
                    "${CallLog.Calls.NEW} = 1 AND " +
                    "(${CallLog.Calls.IS_READ} = 0 OR ${CallLog.Calls.IS_READ} IS NULL)",
                null, null,
            )?.use { it.count } ?: 0
        }.getOrDefault(0)
    }
}
