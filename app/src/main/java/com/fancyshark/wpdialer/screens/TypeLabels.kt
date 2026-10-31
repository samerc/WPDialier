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
    "spouse" -> stringResource(R.string.type_rel_spouse)
    "partner" -> stringResource(R.string.type_rel_partner)
    "domestic partner" -> stringResource(R.string.type_rel_domestic_partner)
    "parent" -> stringResource(R.string.type_rel_parent)
    "mother" -> stringResource(R.string.type_rel_mother)
    "father" -> stringResource(R.string.type_rel_father)
    "child" -> stringResource(R.string.type_rel_child)
    "sister" -> stringResource(R.string.type_rel_sister)
    "brother" -> stringResource(R.string.type_rel_brother)
    "relative" -> stringResource(R.string.type_rel_relative)
    "friend" -> stringResource(R.string.type_rel_friend)
    "manager" -> stringResource(R.string.type_rel_manager)
    "assistant" -> stringResource(R.string.type_rel_assistant)
    "referred by" -> stringResource(R.string.type_rel_referred_by)
    else -> key
}

/** Chat protocol display: brand names stay as-is, only "custom" translates. */
@Composable
fun imProtocolLabel(key: String): String = when (key) {
    "skype" -> "Skype"
    "jabber" -> "Jabber"
    "qq" -> "QQ"
    "icq" -> "ICQ"
    "aim" -> "AIM"
    "yahoo" -> "Yahoo"
    else -> typeKeyLabel(key)
}

/** Localized account-kind display; only the local "phone" kind translates. */
@Composable
fun accountKindLabel(kindLabel: String): String =
    if (kindLabel == "phone") stringResource(R.string.account_kind_phone) else kindLabel
