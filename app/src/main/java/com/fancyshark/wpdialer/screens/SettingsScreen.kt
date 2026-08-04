package com.fancyshark.wpdialer.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.fancyshark.wpdialer.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.data.AppPrefs
import com.fancyshark.wpdialer.data.Repo
import com.fancyshark.wpdialer.data.Sims
import com.fancyshark.wpdialer.ui.Accent
import com.fancyshark.wpdialer.ui.AccentStore
import com.fancyshark.wpdialer.ui.FontStore
import com.fancyshark.wpdialer.ui.Haptics
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroButton
import com.fancyshark.wpdialer.ui.MetroTextBox
import com.fancyshark.wpdialer.ui.WpAccents
import kotlinx.coroutines.launch

/** Localized display name for a WP accent color; the key stays English. */
@Composable
private fun accentDisplayName(name: String): String {
    val id = when (name) {
        "lime" -> R.string.settings_accent_lime
        "green" -> R.string.settings_accent_green
        "emerald" -> R.string.settings_accent_emerald
        "teal" -> R.string.settings_accent_teal
        "cyan" -> R.string.settings_accent_cyan
        "cobalt" -> R.string.settings_accent_cobalt
        "indigo" -> R.string.settings_accent_indigo
        "violet" -> R.string.settings_accent_violet
        "pink" -> R.string.settings_accent_pink
        "magenta" -> R.string.settings_accent_magenta
        "crimson" -> R.string.settings_accent_crimson
        "red" -> R.string.settings_accent_red
        "orange" -> R.string.settings_accent_orange
        "amber" -> R.string.settings_accent_amber
        "yellow" -> R.string.settings_accent_yellow
        "brown" -> R.string.settings_accent_brown
        "olive" -> R.string.settings_accent_olive
        "steel" -> R.string.settings_accent_steel
        "mauve" -> R.string.settings_accent_mauve
        "sienna" -> R.string.settings_accent_sienna
        else -> return name
    }
    return stringResource(id)
}

/** The WP settings toggle: On/Off label + bordered track with square thumb. */
@Composable
private fun WpToggleRow(checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 8.dp)
            .clickable { onChange(!checked) },
    ) {
        Text(
            if (checked) {
                stringResource(R.string.settings_toggle_on)
            } else {
                stringResource(R.string.settings_toggle_off)
            },
            color = Metro.Foreground,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            maxLines = 1,
            modifier = Modifier
                .widthIn(min = 52.dp)
                .padding(end = 10.dp),
        )
        Box(
            Modifier
                .size(width = 58.dp, height = 26.dp)
                .border(2.dp, Metro.Foreground)
                .background(if (checked) accent else Color.Transparent),
        ) {
            Box(
                Modifier
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(3.dp)
                    .size(width = 18.dp, height = 20.dp)
                    .background(Metro.Foreground),
            )
        }
    }
}

@Composable
fun SettingsScreen(
    accent: Accent,
    isDefaultDialer: Boolean,
    onRequestDefault: () -> Unit,
    onAbout: () -> Unit = {},
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text(
            stringResource(R.string.settings_header),
            color = Metro.Foreground,
            fontSize = 15.sp,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            stringResource(R.string.settings_title),
            color = Metro.Foreground,
            fontSize = 48.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(18.dp))

        Text(
            stringResource(R.string.settings_accent_title),
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        Text(
            accentDisplayName(accent.name),
            color = accent.color,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WpAccents.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { option ->
                        Box(
                            Modifier
                                .size(56.dp)
                                .background(option.color)
                                .then(
                                    if (option.name == accent.name) {
                                        Modifier.border(3.dp, Metro.Foreground)
                                    } else Modifier,
                                )
                                .clickable { AccentStore.set(context, option) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(30.dp))
        val haptics by Haptics.enabled.collectAsState()
        Text(
            stringResource(R.string.settings_haptic_title),
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = 8.dp)
                .clickable { Haptics.set(context, !haptics) },
        ) {
            Text(
                if (haptics) {
                    stringResource(R.string.settings_toggle_on)
                } else {
                    stringResource(R.string.settings_toggle_off)
                },
                color = Metro.Foreground,
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
                maxLines = 1,
                modifier = Modifier
                    .widthIn(min = 52.dp)
                    .padding(end = 10.dp),
            )
            // WP-style toggle: bordered track, square thumb, accent when on.
            Box(
                Modifier
                    .size(width = 58.dp, height = 26.dp)
                    .border(2.dp, Metro.Foreground)
                    .background(if (haptics) accent.color else Color.Transparent),
            ) {
                Box(
                    Modifier
                        .align(if (haptics) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(3.dp)
                        .size(width = 18.dp, height = 20.dp)
                        .background(Metro.Foreground),
                )
            }
        }
        Text(
            stringResource(R.string.settings_haptic_hint),
            color = Metro.Subtle,
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (haptics) {
            val level by Haptics.level.collectAsState()
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Haptics.LEVELS.forEachIndexed { i, _ ->
                    Text(
                        stringResource(
                            when (i) {
                                0 -> R.string.settings_haptic_low
                                1 -> R.string.settings_haptic_medium
                                else -> R.string.settings_haptic_high
                            },
                        ),
                        color = if (level == i) accent.color else Metro.Subtle,
                        fontSize = 20.sp,
                        fontWeight = if (level == i) FontWeight.Normal else FontWeight.Light,
                        modifier = Modifier.clickable {
                            Haptics.setLevel(context, i)
                            Haptics.tick(context)
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(30.dp))
        val selawik by FontStore.selawik.collectAsState()
        Text(
            stringResource(R.string.settings_font_title),
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        listOf(
            stringResource(R.string.settings_font_selawik) to true,
            stringResource(R.string.settings_font_system) to false,
        ).forEach { (label, value) ->
            Text(
                label,
                color = if (selawik == value) accent.color else Metro.Foreground,
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { FontStore.set(context, value) }
                    .padding(vertical = 7.dp),
            )
        }

        Spacer(Modifier.height(30.dp))
        Text(
            stringResource(R.string.settings_language_title),
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        val localeManager = androidx.compose.runtime.remember {
            context.getSystemService(android.app.LocaleManager::class.java)
        }
        var currentLangTag by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(
                localeManager?.applicationLocales?.toLanguageTags().orEmpty(),
            )
        }
        // Language names are shown as endonyms on purpose — never translated.
        listOf(
            "" to stringResource(R.string.settings_language_system),
            "en" to "English",
            "fr" to "français",
            "ar" to "العربية",
        ).forEach { (tag, label) ->
            val selected = if (tag.isEmpty()) {
                currentLangTag.isEmpty()
            } else {
                currentLangTag.startsWith(tag)
            }
            Text(
                label,
                color = if (selected) accent.color else Metro.Foreground,
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        currentLangTag = tag
                        localeManager?.applicationLocales = if (tag.isEmpty()) {
                            android.os.LocaleList.getEmptyLocaleList()
                        } else {
                            android.os.LocaleList.forLanguageTags(tag)
                        }
                    }
                    .padding(vertical = 6.dp),
            )
        }

        Spacer(Modifier.height(30.dp))
        Text(
            stringResource(R.string.settings_default_apps_title),
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        if (isDefaultDialer) {
            Text(
                stringResource(R.string.settings_default_dialer_yes),
                color = Metro.Foreground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
            )
        } else {
            Text(
                stringResource(R.string.settings_default_dialer_prompt),
                color = Metro.Foreground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            MetroButton(
                stringResource(R.string.settings_default_dialer_button),
                modifier = Modifier.fillMaxWidth(),
            ) {
                onRequestDefault()
            }
        }

        // enabled=true -> duplicate-notification guidance; enabled=false ->
        // reminder to re-enable before uninstalling; null -> not installed.
        val googleDialerEnabled = androidx.compose.runtime.remember {
            runCatching {
                context.packageManager
                    .getApplicationInfo("com.google.android.dialer", 0)
                    .enabled
            }.getOrNull()
        }
        if (googleDialerEnabled == false) {
            Spacer(Modifier.height(30.dp))
            Text(
                stringResource(R.string.settings_google_disabled_title),
                color = accent.color,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Text(
                stringResource(R.string.settings_google_disabled_body),
                color = Metro.Foreground,
                fontSize = 15.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            )
            MetroButton(
                stringResource(R.string.settings_google_app_info_button),
                modifier = Modifier.fillMaxWidth(),
            ) {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:com.google.android.dialer"),
                        ),
                    )
                }
            }
        }
        if (googleDialerEnabled == true) {
            Spacer(Modifier.height(30.dp))
            Text(
                stringResource(R.string.settings_dup_notif_title),
                color = accent.color,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Text(
                stringResource(R.string.settings_dup_notif_body),
                color = Metro.Foreground,
                fontSize = 15.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            )
            MetroButton(
                stringResource(R.string.settings_dup_notif_button),
                modifier = Modifier.fillMaxWidth(),
            ) {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                        ).putExtra(
                            android.provider.Settings.EXTRA_APP_PACKAGE,
                            "com.google.android.dialer",
                        ),
                    )
                }
            }
            Text(
                stringResource(R.string.settings_dup_notif_locked_hint),
                color = Metro.Subtle,
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
            )
            MetroButton(
                stringResource(R.string.settings_google_app_info_button),
                modifier = Modifier.fillMaxWidth(),
            ) {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:com.google.android.dialer"),
                        ),
                    )
                }
            }
        }

        Spacer(Modifier.height(30.dp))
        val light by AppPrefs.light.collectAsState()
        Text(
            stringResource(R.string.settings_light_theme_title),
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        WpToggleRow(light, accent.color) {
            AppPrefs.setLight(context, it)
            Metro.light = it
        }

        Spacer(Modifier.height(30.dp))
        val tilt by AppPrefs.tilt.collectAsState()
        Text(
            stringResource(R.string.settings_tilt_title),
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        WpToggleRow(tilt, accent.color) { AppPrefs.setTilt(context, it) }

        Spacer(Modifier.height(30.dp))
        val relativeTimes by AppPrefs.relativeTimes.collectAsState()
        Text(
            stringResource(R.string.settings_relative_times_title),
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        WpToggleRow(relativeTimes, accent.color) { AppPrefs.setRelativeTimes(context, it) }
        Text(
            stringResource(R.string.settings_relative_times_hint),
            color = Metro.Subtle,
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(30.dp))
        val replies by AppPrefs.rejectMessages.collectAsState()
        var newReply by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf("")
        }
        Text(
            stringResource(R.string.settings_text_replies_title),
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        replies.forEach { message ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            ) {
                Text(
                    message,
                    color = Metro.Foreground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "✕",
                    color = Metro.Subtle,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clickable {
                            AppPrefs.setRejectMessages(context, replies - message)
                        }
                        .padding(start = 10.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        MetroTextBox(
            newReply,
            { newReply = it },
            accent.color,
            placeholder = stringResource(R.string.settings_new_reply_placeholder),
        )
        Spacer(Modifier.height(8.dp))
        MetroButton(
            stringResource(R.string.settings_add_reply_button),
            enabled = newReply.isNotBlank(),
        ) {
            AppPrefs.setRejectMessages(context, replies + newReply.trim())
            newReply = ""
        }

        val sims = androidx.compose.runtime.remember { Sims.options(context) }
        if (sims.size > 1) {
            Spacer(Modifier.height(30.dp))
            val globalSim by AppPrefs.globalSim.collectAsState()
            Text(
                stringResource(R.string.settings_default_sim_title),
                color = accent.color,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            (listOf<Pair<String, String?>>(
                stringResource(R.string.settings_sim_always_ask) to null,
            ) +
                sims.map { it.label to it.flat }
                ).forEach { (label, flat) ->
                Text(
                    label,
                    color = if (globalSim == flat) accent.color else Metro.Foreground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { AppPrefs.setGlobalSim(context, flat) }
                        .padding(vertical = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(30.dp))
        var blockedTick by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableIntStateOf(0)
        }
        val blocked by androidx.compose.runtime.produceState(
            emptyList<Pair<Long, String>>(), blockedTick,
        ) {
            value = Repo.listBlocked(context)
        }
        val settingsScope = androidx.compose.runtime.rememberCoroutineScope()
        Text(
            stringResource(R.string.settings_blocked_title),
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (blocked.isEmpty()) {
            Text(
                stringResource(R.string.settings_blocked_none),
                color = Metro.Foreground,
                fontSize = 17.sp,
                fontWeight = FontWeight.Light,
            )
        }
        blocked.forEach { (rowId, number) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            ) {
                Text(
                    Repo.pretty(context, number),
                    color = Metro.Foreground,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.settings_unblock),
                    color = accent.color,
                    fontSize = 16.sp,
                    modifier = Modifier.clickable {
                        settingsScope.launch {
                            Repo.unblockNumber(context, rowId)
                            blockedTick++
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(30.dp))
        Text(
            stringResource(R.string.settings_about_title),
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.settings_about_link),
            color = Metro.Foreground,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAbout() }
                .padding(vertical = 8.dp),
        )

        Spacer(Modifier.height(30.dp))
    }
}
