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

/** Per-contact preferred SIM, stored by contact id. */
object SimPrefs {

    fun get(context: Context, contactId: Long): String? =
        context.getSharedPreferences("wp", Context.MODE_PRIVATE)
            .getString("sim_$contactId", null)

    fun set(context: Context, contactId: Long, flat: String?) {
        val prefs = context.getSharedPreferences("wp", Context.MODE_PRIVATE).edit()
        if (flat == null) prefs.remove("sim_$contactId") else prefs.putString("sim_$contactId", flat)
        prefs.apply()
    }
}
