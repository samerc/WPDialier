package com.fancyshark.wpdialer.data

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Per-app language (Android 13+ LocaleManager). Callers never see the
 * LocaleManager type: even a null-valued local of that type makes ART
 * resolve the class, which crashes on Android 10-12 (seen on an A02).
 */
object AppLanguage {

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33

    /** Current language tag, "" = follow the system. */
    fun get(context: Context): String =
        if (supported) Api33.get(context) else ""

    /** Sets the app language; "" = follow the system. No-op below 13. */
    fun set(context: Context, tag: String) {
        if (supported) Api33.set(context, tag)
    }

    @RequiresApi(33)
    private object Api33 {
        fun get(context: Context): String =
            context.getSystemService(android.app.LocaleManager::class.java)
                ?.applicationLocales?.toLanguageTags().orEmpty()

        fun set(context: Context, tag: String) {
            context.getSystemService(android.app.LocaleManager::class.java)
                ?.applicationLocales = if (tag.isEmpty()) {
                android.os.LocaleList.getEmptyLocaleList()
            } else {
                android.os.LocaleList.forLanguageTags(tag)
            }
        }
    }
}
