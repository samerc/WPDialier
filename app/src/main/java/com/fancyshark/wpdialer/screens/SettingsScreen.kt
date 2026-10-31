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
import androidx.compose.foundation.layout.heightIn
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
import com.fancyshark.wpdialer.ui.MetroCloseMark
import com.fancyshark.wpdialer.ui.MetroConfirm
import com.fancyshark.wpdialer.ui.MetroDialog
import com.fancyshark.wpdialer.ui.MetroTextBox
import com.fancyshark.wpdialer.ui.hiddenFromAccessibility
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
        "taupe" -> R.string.settings_accent_taupe
        else -> return name
    }
    return stringResource(id)
}

/**
 * The WP settings toggle: accent title, On/Off label + bordered track with
 * square thumb. One switch for TalkBack ("title, switch, on"), the title
 * included in the tap target.
 */
@Composable
private fun WpToggleRow(
    title: String,
    checked: Boolean,
    accent: Color,
    onChange: (Boolean) -> Unit,
) {
    Column(
        Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    ) {
    Text(
        title,
        color = Metro.textAccent(accent),
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp),
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
                .padding(end = 10.dp)
                // The switch state is announced already.
                .clearAndSetSemantics {},
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
}

@Composable
fun SettingsScreen(
    accent: Accent,
    isDefaultDialer: Boolean,
    onRequestDefault: () -> Unit,
    onAbout: () -> Unit = {},
    onRerunSetup: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val accentText = Metro.textAccent(accent.color)
    var showLanguagePicker by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    var showRepliesEditor by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // A parsed-but-unapplied settings file waiting on the confirm overlay.
    var pendingRestore by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.fancyshark.wpdialer.data.SettingsBackup.Payload?>(null)
    }
    // Index into the replies list awaiting the delete confirm.
    var confirmDeleteReply by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Int?>(null)
    }
    var blockedTick by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableIntStateOf(0)
    }
    // (provider row id, number) awaiting the unblock confirm.
    var confirmUnblock by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Pair<Long, String>?>(null)
    }
    val localeManager = androidx.compose.runtime.remember {
        context.getSystemService(android.app.LocaleManager::class.java)
    }
    var currentLangTag by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            localeManager?.applicationLocales?.toLanguageTags().orEmpty(),
        )
    }
    // Language names are shown as endonyms on purpose — never translated.
    val languageOptions = listOf(
        "" to stringResource(R.string.settings_language_system),
        "en" to "English",
        "fr" to "français",
        "hi" to "हिन्दी",
        "ar" to "العربية",
    )
    fun langSelected(tag: String): Boolean =
        if (tag.isEmpty()) currentLangTag.isEmpty() else currentLangTag.startsWith(tag)
    val replies by AppPrefs.rejectMessages.collectAsState()

    val overlayOpen = showLanguagePicker || showRepliesEditor || pendingRestore != null ||
        confirmDeleteReply != null || confirmUnblock != null
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .hiddenFromAccessibility(overlayOpen)
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
            color = accentText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        Text(
            accentDisplayName(accent.name),
            color = accentText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WpAccents.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { option ->
                        val swatchName = accentDisplayName(option.name)
                        Box(
                            Modifier
                                .size(56.dp)
                                .background(option.color)
                                .then(
                                    if (option.name == accent.name) {
                                        Modifier.border(3.dp, Metro.Foreground)
                                    } else Modifier,
                                )
                                .selectable(
                                    selected = option.name == accent.name,
                                    role = Role.RadioButton,
                                ) { AccentStore.set(context, option) }
                                .semantics { contentDescription = swatchName },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(30.dp))
        val haptics by Haptics.enabled.collectAsState()
        WpToggleRow(stringResource(R.string.settings_haptic_title), haptics, accent.color) {
            Haptics.set(context, it)
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
                        color = if (level == i) accentText else Metro.Subtle,
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
            color = accentText,
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
                color = if (selawik == value) accentText else Metro.Foreground,
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
            color = accentText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        Text(
            languageOptions.firstOrNull { langSelected(it.first) }?.second
                ?: stringResource(R.string.settings_language_system),
            color = Metro.Foreground,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showLanguagePicker = true }
                .padding(vertical = 8.dp),
        )

        Spacer(Modifier.height(30.dp))
        Text(
            stringResource(R.string.settings_default_apps_title),
            color = accentText,
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
        // Re-queried on resume: the section's own deep link lets the user
        // flip the state and come straight back.
        var resumeTick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val settingsLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(settingsLifecycle) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
            }
            settingsLifecycle.lifecycle.addObserver(observer)
            onDispose { settingsLifecycle.lifecycle.removeObserver(observer) }
        }
        val googleDialerEnabled = androidx.compose.runtime.remember(resumeTick) {
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
                color = accentText,
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
                color = accentText,
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
        // Same WP list style as the font choice; MetroThemeSync applies it.
        val themeMode by AppPrefs.themeMode.collectAsState()
        Text(
            stringResource(R.string.settings_theme_title),
            color = accentText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        listOf(
            stringResource(R.string.settings_theme_dark) to AppPrefs.THEME_DARK,
            stringResource(R.string.settings_theme_light) to AppPrefs.THEME_LIGHT,
            stringResource(R.string.settings_theme_system) to AppPrefs.THEME_SYSTEM,
        ).forEach { (label, mode) ->
            Text(
                label,
                color = if (themeMode == mode) accentText else Metro.Foreground,
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = themeMode == mode, role = Role.RadioButton) {
                        AppPrefs.setThemeMode(context, mode)
                    }
                    .padding(vertical = 7.dp),
            )
        }

        Spacer(Modifier.height(30.dp))
        val tilt by AppPrefs.tilt.collectAsState()
        WpToggleRow(stringResource(R.string.settings_tilt_title), tilt, accent.color) { AppPrefs.setTilt(context, it) }

        Spacer(Modifier.height(30.dp))
        val relativeTimes by AppPrefs.relativeTimes.collectAsState()
        WpToggleRow(stringResource(R.string.settings_relative_times_title), relativeTimes, accent.color) { AppPrefs.setRelativeTimes(context, it) }
        Text(
            stringResource(R.string.settings_relative_times_hint),
            color = Metro.Subtle,
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(30.dp))
        val oneHandedLists by AppPrefs.oneHandedLists.collectAsState()
        WpToggleRow(stringResource(R.string.settings_one_handed_lists_title), oneHandedLists, accent.color) { AppPrefs.setOneHandedLists(context, it) }
        Text(
            stringResource(R.string.settings_one_handed_lists_hint),
            color = Metro.Subtle,
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(30.dp))
        val reachGesture by AppPrefs.reachGesture.collectAsState()
        WpToggleRow(stringResource(R.string.settings_reach_title), reachGesture, accent.color) { AppPrefs.setReachGesture(context, it) }
        Text(
            stringResource(R.string.settings_reach_hint),
            color = Metro.Subtle,
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(30.dp))
        Text(
            stringResource(R.string.settings_text_replies_title),
            color = accentText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        Text(
            stringResource(R.string.settings_replies_manage, replies.size),
            color = Metro.Foreground,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showRepliesEditor = true }
                .padding(vertical = 8.dp),
        )

        val sims = androidx.compose.runtime.remember { Sims.options(context) }
        if (sims.size > 1) {
            Spacer(Modifier.height(30.dp))
            val globalSim by AppPrefs.globalSim.collectAsState()
            Text(
                stringResource(R.string.settings_default_sim_title),
                color = accentText,
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
                    color = if (globalSim == flat) accentText else Metro.Foreground,
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
        Text(
            stringResource(R.string.settings_screening_title),
            color = accentText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        Text(
            stringResource(
                if (isDefaultDialer) R.string.settings_screening_hint
                else R.string.settings_screening_needs_default,
            ),
            color = Metro.Subtle,
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
        )
        val screenHidden by AppPrefs.screenHidden.collectAsState()
        WpToggleRow(stringResource(R.string.settings_screen_hidden_title), screenHidden, accent.color) {
            AppPrefs.setScreenHidden(context, it)
        }
        Spacer(Modifier.height(20.dp))
        val screenUnknown by AppPrefs.screenUnknown.collectAsState()
        WpToggleRow(stringResource(R.string.settings_screen_unknown_title), screenUnknown, accent.color) {
            AppPrefs.setScreenUnknown(context, it)
        }
        Text(
            stringResource(R.string.settings_screen_unknown_hint),
            color = Metro.Subtle,
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(20.dp))
        val screenSpoofed by AppPrefs.screenSpoofed.collectAsState()
        WpToggleRow(stringResource(R.string.settings_screen_spoofed_title), screenSpoofed, accent.color) {
            AppPrefs.setScreenSpoofed(context, it)
        }
        Text(
            stringResource(R.string.settings_screen_spoofed_hint),
            color = Metro.Subtle,
            fontSize = 13.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(30.dp))
        // Keyed on the role too: the provider only answers the default
        // dialer, so becoming default must re-query instead of staying empty.
        val blocked by androidx.compose.runtime.produceState(
            emptyList<Pair<Long, String>>(), blockedTick, isDefaultDialer,
        ) {
            value = Repo.listBlocked(context)
        }
        val settingsScope = androidx.compose.runtime.rememberCoroutineScope()
        Text(
            stringResource(R.string.settings_blocked_title),
            color = accentText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (!isDefaultDialer) {
            // Non-default dialers can't read the blocked list (the provider
            // throws) — an empty list here would be a lie.
            Text(
                stringResource(R.string.settings_blocked_needs_default),
                color = Metro.Subtle,
                fontSize = 17.sp,
                fontWeight = FontWeight.Light,
            )
        } else if (blocked.isEmpty()) {
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
                modifier = Modifier.fillMaxWidth(),
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
                    color = accentText,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clickable(role = Role.Button) { confirmUnblock = rowId to number }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(30.dp))
        Text(
            stringResource(R.string.settings_backup_title),
            color = accentText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        val backupScope = androidx.compose.runtime.rememberCoroutineScope()
        fun backupToast(resId: Int) {
            android.widget.Toast
                .makeText(context, resources.getString(resId), android.widget.Toast.LENGTH_SHORT)
                .show()
        }
        val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .CreateDocument(com.fancyshark.wpdialer.data.SettingsBackup.MIME),
        ) { uri ->
            if (uri != null) {
                backupScope.launch {
                    val ok = com.fancyshark.wpdialer.data.SettingsBackup.exportTo(context, uri)
                    backupToast(
                        if (ok) R.string.settings_backup_done else R.string.settings_backup_failed,
                    )
                }
            }
        }
        val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                backupScope.launch {
                    val payload = com.fancyshark.wpdialer.data.SettingsBackup.readFrom(context, uri)
                    // Applying is destructive — always behind the confirm overlay.
                    if (payload == null) {
                        backupToast(R.string.settings_backup_failed)
                    } else {
                        pendingRestore = payload
                    }
                }
            }
        }
        Text(
            stringResource(R.string.settings_backup_export),
            color = Metro.Foreground,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    exportLauncher.launch(com.fancyshark.wpdialer.data.SettingsBackup.SUGGESTED_NAME)
                }
                .padding(vertical = 8.dp),
        )
        Text(
            stringResource(R.string.settings_backup_restore),
            color = Metro.Foreground,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier
                .fillMaxWidth()
                // Broad mime filter: file managers often report json as
                // octet-stream; validity is checked by the parser instead.
                .clickable { importLauncher.launch(arrayOf("*/*")) }
                .padding(vertical = 8.dp),
        )

        Spacer(Modifier.height(30.dp))
        Text(
            stringResource(R.string.settings_setup_title),
            color = accentText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.settings_setup_rerun),
            color = Metro.Foreground,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onRerunSetup?.invoke() ?: AppPrefs.setSetupDone(context, false)
                }
                .padding(vertical = 8.dp),
        )

        Spacer(Modifier.height(30.dp))
        Text(
            stringResource(R.string.settings_about_title),
            color = accentText,
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

    pendingRestore?.let { payload ->
        val restoreScope = androidx.compose.runtime.rememberCoroutineScope()
        // Applying replaces settings wholesale — confirm only, a stray tap on
        // the backdrop must not decide it either way.
        MetroConfirm(
            title = stringResource(R.string.settings_restore_confirm),
            message = stringResource(R.string.settings_restore_hint),
            confirmLabel = stringResource(R.string.settings_restore_button),
            cancelLabel = stringResource(R.string.main_button_cancel),
            confirmFill = accent.color,
            dismissOnBackdrop = false,
            onConfirm = {
                restoreScope.launch {
                    val ok = runCatching {
                        com.fancyshark.wpdialer.data.SettingsBackup.apply(context, payload)
                    }.getOrDefault(false)
                    if (ok) {
                        // Every pref-backed store re-reads the restored file.
                        AppPrefs.init(context)
                        com.fancyshark.wpdialer.ui.AccentStore.init(context)
                        com.fancyshark.wpdialer.ui.FontStore.init(context)
                        com.fancyshark.wpdialer.ui.Haptics.init(context)
                        // Metro.light follows via MetroThemeSync.
                        // The home-screen tile wears the restored accent.
                        com.fancyshark.wpdialer.widget.TileWidget.updateAll(context)
                    }
                    pendingRestore = null
                    android.widget.Toast.makeText(
                        context,
                        resources.getString(
                            if (ok) R.string.settings_restore_done
                            else R.string.settings_backup_failed,
                        ),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onCancel = { pendingRestore = null },
        )
    }

    if (showLanguagePicker) {
        MetroDialog(onDismiss = { showLanguagePicker = false }, backdropAlpha = 0.97f) {
            Text(
                stringResource(R.string.settings_language_title),
                color = Metro.Foreground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 14.dp),
            )
            languageOptions.forEach { (tag, label) ->
                Text(
                    label,
                    color = if (langSelected(tag)) accentText else Metro.Foreground,
                    fontSize = 24.sp,
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
                            showLanguagePicker = false
                        }
                        .padding(vertical = 10.dp),
                )
            }
        }
    }

    if (showRepliesEditor) {
        var newReply by androidx.compose.runtime.saveable.rememberSaveable {
            androidx.compose.runtime.mutableStateOf("")
        }
        MetroDialog(onDismiss = { showRepliesEditor = false }, backdropAlpha = 0.97f) {
            Text(
                stringResource(R.string.settings_text_replies_title),
                color = Metro.Foreground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Column(
                Modifier
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                replies.forEachIndexed { index, message ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            message,
                            color = Metro.Foreground,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.weight(1f),
                        )
                        MetroCloseMark(
                            stringResource(R.string.ui_remove),
                            fontSize = 18.sp,
                        ) { confirmDeleteReply = index }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
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
        }
    }

    // After the replies editor so it stacks (and takes back) above it.
    confirmDeleteReply?.let { index ->
        MetroConfirm(
            title = stringResource(R.string.settings_confirm_delete_reply),
            message = replies.getOrNull(index),
            confirmLabel = stringResource(R.string.main_button_delete),
            cancelLabel = stringResource(R.string.main_button_cancel),
            onConfirm = {
                confirmDeleteReply = null
                // By position: identical templates must not all vanish.
                AppPrefs.setRejectMessages(context, replies.filterIndexed { i, _ -> i != index })
            },
            onCancel = { confirmDeleteReply = null },
        )
    }

    confirmUnblock?.let { (rowId, number) ->
        val unblockScope = androidx.compose.runtime.rememberCoroutineScope()
        MetroConfirm(
            title = stringResource(R.string.settings_confirm_unblock),
            message = Repo.pretty(context, number),
            confirmLabel = stringResource(R.string.settings_unblock),
            cancelLabel = stringResource(R.string.main_button_cancel),
            confirmFill = accent.color,
            onConfirm = {
                unblockScope.launch {
                    Repo.unblockNumber(context, rowId)
                    blockedTick++
                    confirmUnblock = null
                }
            },
            onCancel = { confirmUnblock = null },
        )
    }
    }
}
