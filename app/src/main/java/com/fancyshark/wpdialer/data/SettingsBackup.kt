package com.fancyshark.wpdialer.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Explicit settings export/import (auto-backup is off by policy). Everything
 * the app persists lives in the single "wp" SharedPreferences file — accent,
 * font, haptics, toggles, reject messages, speed dial, per-number SIM prefs.
 * Blocked numbers live in the system provider and ride along as a list.
 * Values are type-tagged so numeric prefs round-trip as the exact type
 * SharedPreferences expects (a Long read back as Int would throw later).
 */
object SettingsBackup {

    const val MIME = "application/json"
    const val SUGGESTED_NAME = "dialer8-settings.json"
    private const val FORMAT = 1
    private const val APP_ID = "com.fancyshark.wpdialer"

    suspend fun exportTo(context: Context, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val json = build(context)
                context.contentResolver.openOutputStream(uri, "wt")!!.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                }
                true
            }.getOrDefault(false)
        }

    private suspend fun build(context: Context): String {
        val prefs = JSONArray()
        context.getSharedPreferences("wp", Context.MODE_PRIVATE).all.forEach { (k, v) ->
            val t = when (v) {
                is Boolean -> "b"
                is Int -> "i"
                is Long -> "l"
                is Float -> "f"
                is String -> "s"
                else -> return@forEach // string sets aren't used
            }
            prefs.put(JSONObject().put("k", k).put("t", t).put("v", v))
        }
        val blocked = JSONArray()
        Repo.listBlocked(context).forEach { (_, number) -> blocked.put(number) }
        return JSONObject()
            .put("app", APP_ID)
            .put("format", FORMAT)
            .put("prefs", prefs)
            .put("blocked", blocked)
            .toString(2)
    }

    /** Parsed but not yet applied — lets the UI confirm before overwriting. */
    class Payload internal constructor(
        internal val prefs: JSONArray,
        internal val blocked: JSONArray,
    )

    suspend fun readFrom(context: Context, uri: Uri): Payload? =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)!!.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
                val o = JSONObject(text)
                if (o.optString("app") != APP_ID) return@withContext null
                if (o.optInt("format") > FORMAT) return@withContext null
                Payload(o.getJSONArray("prefs"), o.optJSONArray("blocked") ?: JSONArray())
            }.getOrNull()
        }

    /**
     * Replaces the whole prefs file and re-blocks the exported numbers
     * (blockNumber dedupes; blocking silently no-ops when not the default
     * dialer). Callers must re-init the pref-backed stores afterwards.
     */
    suspend fun apply(context: Context, payload: Payload): Unit =
        withContext(Dispatchers.IO) {
            val edit = context.getSharedPreferences("wp", Context.MODE_PRIVATE).edit().clear()
            for (i in 0 until payload.prefs.length()) {
                val e = payload.prefs.getJSONObject(i)
                val k = e.getString("k")
                when (e.getString("t")) {
                    "b" -> edit.putBoolean(k, e.getBoolean("v"))
                    "i" -> edit.putInt(k, e.getInt("v"))
                    "l" -> edit.putLong(k, e.getLong("v"))
                    "f" -> edit.putFloat(k, e.getDouble("v").toFloat())
                    "s" -> edit.putString(k, e.getString("v"))
                }
            }
            edit.commit() // synchronous: stores re-init right after
            for (i in 0 until payload.blocked.length()) {
                Repo.blockNumber(context, payload.blocked.getString(i))
            }
        }
}
