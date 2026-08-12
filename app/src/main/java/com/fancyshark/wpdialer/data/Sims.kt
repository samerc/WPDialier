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
 * Per-contact preferred SIM, keyed by the number's significant digits.
 * Aggregate contact ids are unstable across re-aggregation/sync — an id
 * key could silently route another contact's calls to the wrong SIM.
 */
object SimPrefs {

    private fun key(number: String): String? =
        Repo.numberKey(number).takeIf { it.isNotEmpty() }?.let { "simn_$it" }

    fun get(context: Context, number: String): String? =
        key(number)?.let {
            context.getSharedPreferences("wp", Context.MODE_PRIVATE).getString(it, null)
        }

    fun set(context: Context, number: String, flat: String?) {
        val k = key(number) ?: return
        val prefs = context.getSharedPreferences("wp", Context.MODE_PRIVATE).edit()
        if (flat == null) prefs.remove(k) else prefs.putString(k, flat)
        prefs.apply()
    }
}
