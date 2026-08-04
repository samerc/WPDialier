package com.fancyshark.wpdialer.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.fancyshark.wpdialer.R

/**
 * Localized display text for a contact type key. The keys themselves stay
 * English — they are data (dropdown tables, stored labels, comparisons).
 */
@Composable
fun typeKeyLabel(key: String): String = when (key) {
    "mobile" -> stringResource(R.string.type_mobile)
    "home" -> stringResource(R.string.type_home)
    "work" -> stringResource(R.string.type_work)
    "main" -> stringResource(R.string.type_main)
    "other" -> stringResource(R.string.type_other)
    "custom" -> stringResource(R.string.type_custom)
    "personal" -> stringResource(R.string.type_personal)
    "birthday" -> stringResource(R.string.type_birthday)
    "anniversary" -> stringResource(R.string.type_anniversary)
    else -> key
}

/** Localized account-kind display; only the local "phone" kind translates. */
@Composable
fun accountKindLabel(kindLabel: String): String =
    if (kindLabel == "phone") stringResource(R.string.account_kind_phone) else kindLabel
