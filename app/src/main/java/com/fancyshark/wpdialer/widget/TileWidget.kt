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
        appWidgetIds.forEach { push(context, appWidgetManager, it) }
    }

    companion object {

        /** Refresh every placed tile; safe no-op when none are placed. */
        fun updateAll(context: Context) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(context) ?: return
                val ids = mgr.getAppWidgetIds(ComponentName(context, TileWidget::class.java))
                ids.forEach { push(context, mgr, it) }
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
        // when READ_CALL_LOG is missing (fresh install pre-wizard).
        private fun missedCount(context: Context): Int = runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE} AND " +
                    "${CallLog.Calls.NEW} = 1 AND ${CallLog.Calls.IS_READ} = 0",
                null, null,
            )?.use { it.count } ?: 0
        }.getOrDefault(0)
    }
}
