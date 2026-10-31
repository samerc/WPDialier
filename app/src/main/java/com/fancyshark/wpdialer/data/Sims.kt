package com.fancyshark.wpdialer.data

import android.content.Context
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/** A SIM / phone account calls can be placed through. */
data class SimOption(val handle: PhoneAccountHandle, val label: String, val flat: String)

object Sims {

    fun options(context: Context): List<SimOption> = runCatching {
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: return emptyList()
        telecom.callCapablePhoneAccounts.mapIndexed { i, handle ->
            val label = runCatching {
                telecom.getPhoneAccount(handle)?.label?.toString()
            }.getOrNull()
            SimOption(
                handle = handle,
                label = label?.takeIf { it.isNotBlank() } ?: "SIM ${i + 1}",
                flat = flatten(handle),
            )
        }
    }.getOrElse { emptyList() }

    fun flatten(handle: PhoneAccountHandle): String =
        handle.componentName.flattenToString() + "/" + handle.id
}

/**
 * Per-contact preferred SIM, keyed by the number's exact identity (E.164
 * when it parses, see [Repo.exactNumberKey]). Aggregate contact ids are
 * unstable across re-aggregation/sync — an id key could silently route
 * another contact's calls to the wrong SIM.
 *
 * Legacy installs keyed by the 7-digit [Repo.numberKey], which collides
 * across area codes. Those entries are still honored as a fallback; new
 * writes go to the exact key only, so a collided legacy entry keeps
 * serving whichever other number shared it.
 */
object SimPrefs {

    private const val PREFIX = "simn_"

    /** Stored under the exact key to mean "explicitly no preference" —
     *  stops a cleared number from falling back to its legacy entry. */
    private const val CLEARED = ""

    private fun key(context: Context, number: String): String? =
        Repo.exactNumberKey(context, number).takeIf { it.isNotEmpty() }?.let { PREFIX + it }

    private fun legacyKey(number: String): String? =
        Repo.numberKey(number).takeIf { it.isNotEmpty() }?.let { PREFIX + it }

    fun get(context: Context, number: String): String? {
        val prefs = context.getSharedPreferences("wp", Context.MODE_PRIVATE)
        fun read(k: String?) = k?.let { runCatching { prefs.getString(it, null) }.getOrNull() }
        val exact = key(context, number)
        read(exact)?.let { return it.takeIf { v -> v != CLEARED } }
        val legacy = legacyKey(number)
        // Same string means the exact key IS the legacy one (opaque codes).
        return if (legacy == exact) null else read(legacy)?.takeIf { it != CLEARED }
    }

    fun set(context: Context, number: String, flat: String?) {
        val k = key(context, number) ?: return
        val legacy = legacyKey(number)
        val prefs = context.getSharedPreferences("wp", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        when {
            flat != null -> edit.putString(k, flat)
            // Only a tombstone when a legacy entry would otherwise resurface.
            legacy != null && legacy != k && prefs.contains(legacy) -> edit.putString(k, CLEARED)
            else -> edit.remove(k)
        }
        edit.apply()
    }
}
