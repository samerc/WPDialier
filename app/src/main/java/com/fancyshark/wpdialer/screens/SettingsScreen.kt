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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
            if (checked) "On" else "Off",
            color = Metro.Foreground,
            fontSize = 22.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.width(52.dp),
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
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text(
            "SETTINGS",
            color = Metro.Foreground,
            fontSize = 15.sp,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            "phone",
            color = Metro.Foreground,
            fontSize = 48.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(18.dp))

        Text(
            "Accent color",
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        Text(
            accent.name,
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
            "Haptic feedback",
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
                if (haptics) "On" else "Off",
                color = Metro.Foreground,
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.width(52.dp),
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
            "vibrate on keypad taps — automatically off in battery saver",
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
                Haptics.LEVELS.forEachIndexed { i, label ->
                    Text(
                        label,
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
            "Font",
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        listOf(
            "Selawik (Windows Phone)" to true,
            "system font" to false,
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
            "Default apps",
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        if (isDefaultDialer) {
            Text(
                "this app is your default phone app",
                color = Metro.Foreground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
            )
        } else {
            Text(
                "to answer calls with the Metro screen, make this your default phone app",
                color = Metro.Foreground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            MetroButton("set as default phone app", modifier = Modifier.fillMaxWidth()) {
                onRequestDefault()
            }
        }

        val googleDialerInstalled = androidx.compose.runtime.remember {
            runCatching {
                context.packageManager.getPackageInfo("com.google.android.dialer", 0)
            }.isSuccess
        }
        if (googleDialerInstalled) {
            Spacer(Modifier.height(30.dp))
            Text(
                "Duplicate call notifications",
                color = accent.color,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Text(
                "Google's phone app posts its own call notifications even when it isn't the default dialer. Turn its notifications off to see only this app's.",
                color = Metro.Foreground,
                fontSize = 15.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            )
            MetroButton(
                "open Google Phone notification settings",
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
        }

        Spacer(Modifier.height(30.dp))
        val light by AppPrefs.light.collectAsState()
        Text(
            "Light theme",
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
            "Tile tilt effect",
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        WpToggleRow(tilt, accent.color) { AppPrefs.setTilt(context, it) }

        Spacer(Modifier.height(30.dp))
        val relativeTimes by AppPrefs.relativeTimes.collectAsState()
        Text(
            "Relative times in history",
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
        WpToggleRow(relativeTimes, accent.color) { AppPrefs.setRelativeTimes(context, it) }
        Text(
            "on: \"2 hours ago\" — off: Windows Phone style \"Sat 01:06\"",
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
            "Text replies (decline with message)",
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
        MetroTextBox(newReply, { newReply = it }, accent.color, placeholder = "New reply")
        Spacer(Modifier.height(8.dp))
        MetroButton("add reply", enabled = newReply.isNotBlank()) {
            AppPrefs.setRejectMessages(context, replies + newReply.trim())
            newReply = ""
        }

        val sims = androidx.compose.runtime.remember { Sims.options(context) }
        if (sims.size > 1) {
            Spacer(Modifier.height(30.dp))
            val globalSim by AppPrefs.globalSim.collectAsState()
            Text(
                "Default SIM for calls",
                color = accent.color,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            (listOf<Pair<String, String?>>("always ask" to null) +
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
            "Blocked numbers",
            color = accent.color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (blocked.isEmpty()) {
            Text(
                "none — block numbers from the history list",
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
                    "unblock",
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
    }
}
