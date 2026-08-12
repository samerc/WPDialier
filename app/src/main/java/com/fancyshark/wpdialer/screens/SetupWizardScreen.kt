package com.fancyshark.wpdialer.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.R
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroButton

/**
 * First-run setup, WP8 out-of-box style: role first (its grant carries most
 * phone permissions with it), then the leftover permissions, then the
 * full-screen-intent health check. Every step is skippable — declining must
 * never trap or nag-loop (Play reviewers check this).
 */
@Composable
fun SetupWizardScreen(
    accent: Color,
    isDefaultDialer: Boolean,
    permissionsGranted: Boolean,
    canUseFullScreen: Boolean,
    onRequestRole: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenBannerSetting: () -> Unit,
    onFinish: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val wizardContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        // Lets MainActivity distinguish a pre-wizard install from a user
        // whose process died mid-wizard (the latter must resume, not skip).
        com.fancyshark.wpdialer.data.AppPrefs.markWizardSeen(wizardContext)
    }
    // Back must not fight the auto-advance below — it jumps straight to the
    // nearest previous page whose ask is still open (or the welcome page),
    // instead of decrementing into a page that instantly re-advances.
    fun stepBack() {
        step = when {
            step > 3 && !canUseFullScreen -> 3
            step > 2 && !permissionsGranted -> 2
            step > 1 && !isDefaultDialer -> 1
            else -> 0
        }
    }

    // Advance automatically when the system grant lands, and skip pages
    // whose ask is already satisfied.
    LaunchedEffect(step, isDefaultDialer) {
        if (step == 1 && isDefaultDialer) step = 2
    }
    LaunchedEffect(step, permissionsGranted) {
        if (step == 2 && permissionsGranted) step = 3
    }
    LaunchedEffect(step, canUseFullScreen) {
        if (step == 3 && canUseFullScreen) step = 4
    }

    BackHandler(enabled = step > 0) { stepBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Metro.Background)
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Text(
            stringResource(R.string.wizard_app_title),
            color = Metro.Foreground,
            fontSize = 15.sp,
            letterSpacing = 2.sp,
        )
        Text(
            when (step) {
                0 -> stringResource(R.string.wizard_welcome_title)
                1 -> stringResource(R.string.wizard_role_title)
                2 -> stringResource(R.string.wizard_perms_title)
                3 -> stringResource(R.string.wizard_banner_title)
                else -> stringResource(R.string.wizard_done_title)
            },
            color = Metro.Foreground,
            fontSize = 44.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
        )
        Text(
            when (step) {
                0 -> stringResource(R.string.wizard_welcome_body)
                1 -> stringResource(R.string.wizard_role_body)
                2 -> stringResource(R.string.wizard_perms_body)
                3 -> stringResource(R.string.wizard_banner_body)
                else -> stringResource(R.string.wizard_done_body)
            },
            color = Metro.Foreground,
            fontSize = 18.sp,
            fontWeight = FontWeight.Light,
            lineHeight = 26.sp,
        )
        if (step == 2) {
            Text(
                stringResource(R.string.wizard_perms_privacy),
                color = Metro.Subtle,
                fontSize = 15.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 22.sp,
                modifier = Modifier.padding(top = 14.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        // Step dots, WP-flat.
        Row(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 20.dp)) {
            repeat(5) { i ->
                androidx.compose.foundation.Canvas(
                    Modifier.padding(horizontal = 4.dp).size(8.dp),
                ) {
                    drawCircle(if (i == step) accent else Metro.Subtle)
                }
            }
        }

        when (step) {
            0 -> MetroButton(
                stringResource(R.string.wizard_next),
                fill = accent,
                modifier = Modifier.fillMaxWidth(),
            ) { step = 1 }

            1 -> {
                MetroButton(
                    stringResource(R.string.wizard_role_button),
                    fill = accent,
                    modifier = Modifier.fillMaxWidth(),
                ) { onRequestRole() }
                Spacer(Modifier.height(10.dp))
                MetroButton(
                    stringResource(R.string.wizard_skip),
                    modifier = Modifier.fillMaxWidth(),
                ) { step = 2 }
            }

            2 -> {
                MetroButton(
                    stringResource(R.string.wizard_perms_button),
                    fill = accent,
                    modifier = Modifier.fillMaxWidth(),
                ) { onRequestPermissions() }
                Spacer(Modifier.height(10.dp))
                MetroButton(
                    stringResource(R.string.wizard_skip),
                    modifier = Modifier.fillMaxWidth(),
                ) { step = 3 }
            }

            3 -> {
                MetroButton(
                    stringResource(R.string.wizard_banner_button),
                    fill = accent,
                    modifier = Modifier.fillMaxWidth(),
                ) { onOpenBannerSetting() }
                Spacer(Modifier.height(10.dp))
                MetroButton(
                    stringResource(R.string.wizard_skip),
                    modifier = Modifier.fillMaxWidth(),
                ) { step = 4 }
            }

            else -> MetroButton(
                stringResource(R.string.wizard_start),
                fill = accent,
                modifier = Modifier.fillMaxWidth(),
            ) { onFinish() }
        }
    }
}
