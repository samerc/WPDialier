package com.fancyshark.wpdialer.data

import android.content.Context
import android.telephony.TelephonyManager
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

data class Region(val code: String, val name: String, val callingCode: Int) {
    val label: String get() = "$name (+$callingCode)"
}

object Countries {

    fun all(): List<Region> {
        val util = PhoneNumberUtil.getInstance()
        return util.supportedRegions
            .mapNotNull { code ->
                val name = Locale("", code).displayCountry
                if (name.isBlank()) null
                else Region(code, name, util.getCountryCodeForRegion(code))
            }
            .sortedBy { it.name.lowercase() }
    }

    fun defaultRegionCode(context: Context): String {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return telephony?.simCountryIso?.takeIf { it.isNotBlank() }?.uppercase()
            ?: telephony?.networkCountryIso?.takeIf { it.isNotBlank() }?.uppercase()
            ?: Locale.getDefault().country.ifBlank { "US" }
    }

    /**
     * Normalizes a number to international E.164 using [regionCode] when it
     * has no explicit country prefix. Falls back to the raw input.
     */
    fun toInternational(number: String, regionCode: String): String {
        if (number.isBlank()) return number
        return runCatching {
            val util = PhoneNumberUtil.getInstance()
            val parsed = util.parse(number, regionCode)
            if (util.isValidNumber(parsed)) {
                util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
            } else number
        }.getOrDefault(number)
    }
}
