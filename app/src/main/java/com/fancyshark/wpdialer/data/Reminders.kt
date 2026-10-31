package com.fancyshark.wpdialer.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A pending "call back" reminder. [id] is also its alarm/notification key. */
data class Reminder(val id: Long, val number: String, val at: Long)

/** The history picker's choices (the missed-call notification uses IN_1_HOUR). */
enum class ReminderDelay { IN_15_MIN, IN_1_HOUR, IN_3_HOURS, TOMORROW_9 }

/**
 * "Remind me to call back": pending reminders persist in their own prefs
 * file (re-armed after reboot, listed on the history page) and fire through
 * inexact AlarmManager alarms — no exact-alarm permission. Not part of the
 * settings backup: they are tied to this phone's calls.
 */
object Reminders {

    private const val PREFS = "reminders"
    private const val KEY = "pending"
    // Control chars can't occur in phone numbers (which may contain ',').
    private const val ENTRY_SEP = "\u0001"
    private const val FIELD_SEP = "\u0002"

    const val ACTION_FIRE = "com.fancyshark.wpdialer.REMINDER_FIRE"
    const val EXTRA_ID = "com.fancyshark.wpdialer.REMINDER_ID"

    private val _pending = MutableStateFlow<List<Reminder>>(emptyList())
    val pending: StateFlow<List<Reminder>> = _pending

    // ---- pure helpers (unit tested) ----

    internal fun encode(list: List<Reminder>): String = list.joinToString(ENTRY_SEP) {
        "${it.id}$FIELD_SEP${it.at}$FIELD_SEP${it.number}"
    }

    internal fun decode(flat: String?): List<Reminder> = flat.orEmpty()
        .split(ENTRY_SEP)
        .mapNotNull { entry ->
            val parts = entry.split(FIELD_SEP, limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val id = parts[0].toLongOrNull() ?: return@mapNotNull null
            val at = parts[1].toLongOrNull() ?: return@mapNotNull null
            parts[2].takeIf { it.isNotBlank() }?.let { Reminder(id, it, at) }
        }
        .sortedBy { it.at }

    /** When a reminder picked at [now] should fire, in [zone]'s wall-clock time. */
    internal fun fireTime(
        delay: ReminderDelay,
        now: Long,
        zone: java.util.TimeZone = java.util.TimeZone.getDefault(),
    ): Long = when (delay) {
        ReminderDelay.IN_15_MIN -> now + 15 * 60_000L
        ReminderDelay.IN_1_HOUR -> now + 60 * 60_000L
        ReminderDelay.IN_3_HOURS -> now + 3 * 60 * 60_000L
        ReminderDelay.TOMORROW_9 -> java.util.Calendar.getInstance(zone).run {
            timeInMillis = now
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 9)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            timeInMillis
        }
    }

    /**
     * Re-arm time after a reboot: future reminders keep their time; ones
     * that came due while the phone was off fire shortly after boot.
     */
    internal fun rearmTime(at: Long, now: Long): Long = maxOf(at, now + 10_000L)

    /** Adding a reminder for a number that already has one replaces it. */
    internal fun withAdded(list: List<Reminder>, r: Reminder): List<Reminder> =
        (list.filterNot { Repo.numbersMatch(it.number, r.number) } + r).sortedBy { it.at }

    // ---- storage + alarms ----

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(context: Context): List<Reminder> =
        decode(runCatching { prefs(context).getString(KEY, null) }.getOrNull())

    @Synchronized
    private fun store(context: Context, list: List<Reminder>) {
        // commit: receivers call this right before their process may freeze.
        prefs(context).edit().putString(KEY, encode(list)).commit()
        _pending.value = list
    }

    fun init(context: Context) {
        _pending.value = load(context)
    }

    @Synchronized
    fun schedule(context: Context, number: String, delay: ReminderDelay): Reminder? {
        if (number.isBlank()) return null
        val now = System.currentTimeMillis()
        val list = load(context)
        // Replaced reminders for the same line lose their alarm too.
        list.filter { Repo.numbersMatch(it.number, number) }.forEach { cancelAlarm(context, it.id) }
        // Unique, increasing ids (two picks in the same millisecond can't collide).
        val id = maxOf(now, (list.maxOfOrNull { it.id } ?: 0L) + 1)
        val r = Reminder(id, number, fireTime(delay, now))
        store(context, withAdded(list, r))
        arm(context, r)
        return r
    }

    @Synchronized
    fun cancel(context: Context, id: Long) {
        cancelAlarm(context, id)
        store(context, load(context).filterNot { it.id == id })
    }

    /** Removes a fired reminder from the pending list; returns it, if it was pending. */
    @Synchronized
    fun take(context: Context, id: Long): Reminder? {
        val list = load(context)
        val r = list.firstOrNull { it.id == id } ?: return null
        store(context, list - r)
        return r
    }

    /** After boot: the system drops every alarm on shutdown. */
    fun rearmAll(context: Context) {
        val now = System.currentTimeMillis()
        load(context).forEach { arm(context, it.copy(at = rearmTime(it.at, now))) }
    }

    private fun alarmIntent(context: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, id.hashCode(),
            Intent(ACTION_FIRE)
                .setClass(context, com.fancyshark.wpdialer.call.ReminderReceiver::class.java)
                // Data makes each reminder's PendingIntent distinct even if
                // two ids hash to the same request code.
                .setData(android.net.Uri.parse("wpreminder:$id"))
                .putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun arm(context: Context, r: Reminder) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        // Inexact on purpose: a call-back nudge doesn't need the exact-alarm
        // permission; Doze may defer it by a few minutes.
        runCatching {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, alarmIntent(context, r.id))
        }
    }

    private fun cancelAlarm(context: Context, id: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { am.cancel(alarmIntent(context, id)) }
    }
}
