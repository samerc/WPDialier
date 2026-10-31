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
 * Call notes ("call_notes") and call-back reminders ("reminders") live in
 * their own files on purpose and are never exported: they are personal
 * content tied to this phone's call log, not settings.
 */
object SettingsBackup {

    const val MIME = "application/json"
    const val SUGGESTED_NAME = "dialer8-settings.json"
    private const val FORMAT = 1
    private const val APP_ID = "com.fancyshark.wpdialer"

    /** A real export is a few KB; anything bigger is not ours. */
    private const val MAX_BYTES = 1L shl 20

    // Per-install bookkeeping: restoring these would skip the setup wizard
    // (and its role grant) or replay/suppress what's-new on another device.
    private val SKIP_KEYS = setOf("setup_done", "wizard_seen", "whats_new_seen")

    // PhoneAccountHandles flatten a device-specific component + subscription
    // id — on another phone (or after a SIM swap) they'd route nowhere.
    private const val SIM_GLOBAL_KEY = "sim_global"
    private const val SIM_NUMBER_PREFIX = "simn_"

    private fun skipped(key: String): Boolean =
        key in SKIP_KEYS || key == SIM_GLOBAL_KEY || key.startsWith(SIM_NUMBER_PREFIX)

    // Every typed pref the app reads, with the tag it must be stored as. A
    // mismatched type would make the reader throw ClassCastException on
    // every launch, so restore refuses it even when the file says otherwise.
    private val KNOWN_TYPES = mapOf(
        "accent" to "s",
        "font_selawik" to "b",
        "haptics" to "b",
        "haptics_level" to "i",
        "reject_msgs" to "s",
        "speed_dial" to "s",
        "theme_light" to "b",
        "theme_mode" to "s",
        "screen_hidden" to "b",
        "screen_unknown" to "b",
        "screen_spoofed" to "b",
        "tilt" to "b",
        "relative_times" to "b",
        "one_handed_lists" to "b",
        "reach_gesture" to "b",
        "tiles_animate" to "b",
        "tiles_transparent" to "b",
    )

    /**
     * Whether a backup entry may be restored: not bookkeeping/device-bound,
     * a known value tag, and the type the app reads it with (or, for keys
     * not in [KNOWN_TYPES], the type already stored under that key).
     */
    internal fun accepts(key: String, tag: String, currentTag: String?): Boolean {
        if (key.isBlank() || skipped(key)) return false
        if (tag !in setOf("b", "i", "l", "f", "s")) return false
        val expected = KNOWN_TYPES[key] ?: currentTag
        return expected == null || expected == tag
    }

    internal fun legacyThemeOnly(restoredKeys: Set<String>): Boolean =
        "theme_light" in restoredKeys && "theme_mode" !in restoredKeys

    internal fun tagOf(v: Any?): String? = when (v) {
        is Boolean -> "b"
        is Int -> "i"
        is Long -> "l"
        is Float -> "f"
        is String -> "s"
        else -> null
    }

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
            if (skipped(k)) return@forEach
            val t = tagOf(v) ?: return@forEach // string sets aren't used
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
                    // Bounded read: a huge or endless stream (wrong file,
                    // misbehaving provider) must not OOM the app.
                    val bytes = it.readNBytes((MAX_BYTES + 1).toInt())
                    if (bytes.size > MAX_BYTES) return@withContext null
                    bytes.toString(Charsets.UTF_8)
                }
                val o = JSONObject(text)
                if (o.optString("app") != APP_ID) return@withContext null
                if (o.optInt("format") > FORMAT) return@withContext null
                Payload(o.getJSONArray("prefs"), o.optJSONArray("blocked") ?: JSONArray())
            }.getOrNull()
        }

    /**
     * Merges the exported prefs over the current ones (keys absent from the
     * file are kept — bookkeeping and SIM handles are never in it) and
     * re-blocks the exported numbers
     * (blockNumber dedupes and ADDS — it never unblocks, and it no-ops
     * when not the default dialer; the restore hint says so). Malformed
     * or wrong-typed entries are skipped, never thrown — a hand-edited or
     * corrupted file must not crash the restore (or every later launch). Callers re-init the pref-backed stores.
     * Returns false only when nothing could be applied at all.
     */
    suspend fun apply(context: Context, payload: Payload): Boolean =
        withContext(Dispatchers.IO) {
            var applied = 0
            val sp = context.getSharedPreferences("wp", Context.MODE_PRIVATE)
            val current = sp.all
            val edit = sp.edit()
            val restoredKeys = mutableSetOf<String>()
            for (i in 0 until payload.prefs.length()) {
                runCatching {
                    val e = payload.prefs.getJSONObject(i)
                    val k = e.getString("k")
                    val t = e.getString("t")
                    if (!accepts(k, t, current[k]?.let(::tagOf))) return@runCatching
                    when (t) {
                        "b" -> edit.putBoolean(k, e.getBoolean("v"))
                        "i" -> edit.putInt(k, e.getInt("v"))
                        "l" -> edit.putLong(k, e.getLong("v"))
                        "f" -> edit.putFloat(k, e.getDouble("v").toFloat())
                        "s" -> edit.putString(k, e.getString("v"))
                        else -> return@runCatching
                    }
                    applied++
                    restoredKeys += k
                }
            }
            if (applied == 0) return@withContext false
            // A pre-1.2 export carries only theme_light: drop the newer
            // theme_mode so the restored boolean (its fallback) decides.
            if (legacyThemeOnly(restoredKeys)) edit.remove("theme_mode")
            edit.commit() // synchronous: stores re-init right after
            for (i in 0 until payload.blocked.length()) {
                runCatching { Repo.blockNumber(context, payload.blocked.getString(i)) }
            }
            true
        }
}
