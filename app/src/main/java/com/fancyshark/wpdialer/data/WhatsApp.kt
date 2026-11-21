package com.fancyshark.wpdialer.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * "Open in WhatsApp" for numbers that aren't saved as contacts, via the
 * public wa.me click-to-chat link. Both packages are already declared in
 * the manifest's <queries>, so the install check works on Android 11+.
 */
object WhatsApp {
    // Consumer app first; WhatsApp Business as the fallback.
    private val PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    fun installedPackage(context: Context): String? = PACKAGES.firstOrNull { pkg ->
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** wa.me wants the full international number as bare digits. */
    fun link(e164: String): String = "https://wa.me/" + e164.filter { it.isDigit() }

    /**
     * Chat intent for [number], or null when WhatsApp isn't installed or the
     * number can't be resolved to a valid international one (short codes,
     * withheld numbers, national numbers with no known country).
     */
    fun chatIntent(context: Context, number: String): Intent? {
        val pkg = installedPackage(context) ?: return null
        val net = Repo.networkPortion(number).trim()
        val e164 = Countries.e164OrNull(net, Countries.defaultRegionCode(context)) ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(link(e164)))
            // Pinned to the app so the link never opens in a browser.
            .setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
