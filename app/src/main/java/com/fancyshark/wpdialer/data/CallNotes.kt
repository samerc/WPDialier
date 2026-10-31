package com.fancyshark.wpdialer.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Short per-call notes, keyed by the call-log row _ID, in their own prefs
 * file. Deliberately outside "wp": notes are personal content, so the
 * settings backup never exports them. Every Repo delete path drops the
 * notes of the rows it removes.
 */
object CallNotes {

    private const val PREFS = "call_notes"
    const val MAX_LENGTH = 500

    private val _notes = MutableStateFlow<Map<Long, String>>(emptyMap())
    val notes: StateFlow<Map<Long, String>> = _notes

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(context: Context) {
        _notes.value = runCatching {
            prefs(context).all.mapNotNull { (k, v) ->
                val id = k.toLongOrNull() ?: return@mapNotNull null
                (v as? String)?.takeIf { it.isNotBlank() }?.let { id to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Trimmed and capped; blank means "no note". */
    internal fun clean(text: String): String = text.trim().take(MAX_LENGTH)

    @Synchronized
    fun set(context: Context, callId: Long, text: String) {
        val note = clean(text)
        if (note.isEmpty()) {
            remove(context, listOf(callId))
            return
        }
        prefs(context).edit().putString(callId.toString(), note).apply()
        _notes.value = _notes.value + (callId to note)
    }

    @Synchronized
    fun remove(context: Context, callIds: Collection<Long>) {
        if (callIds.isEmpty()) return
        val edit = prefs(context).edit()
        callIds.forEach { edit.remove(it.toString()) }
        edit.apply()
        _notes.value = _notes.value - callIds.toSet()
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        _notes.value = emptyMap()
    }
}
