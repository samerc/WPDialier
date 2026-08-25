package com.fancyshark.wpdialer.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * One-shot page shown on the first open after an app update (never on a
 * fresh install — the wizard owns that). Bullets live in strings so they
 * localize; update them per release.
 */
@Composable
fun WhatsNewScreen(accent: Color, versionName: String, onDone: () -> Unit) {
    androidx.activity.compose.BackHandler { onDone() }
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
            stringResource(R.string.whatsnew_title),
            color = Metro.Foreground,
            fontSize = 44.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            stringResource(R.string.whatsnew_version, versionName),
            color = Metro.Subtle,
            fontSize = 16.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            listOf(
                R.string.whatsnew_1_tile,
                R.string.whatsnew_1_backup,
                R.string.whatsnew_1_filter,
                R.string.whatsnew_1_voicemail,
                R.string.whatsnew_1_bluetooth,
                R.string.whatsnew_1_hindi,
            ).forEach { res ->
                Row(Modifier.padding(vertical = 7.dp)) {
                    Text(
                        "■",
                        color = accent,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 7.dp, end = 12.dp),
                    )
                    Text(
                        stringResource(res),
                        color = Metro.Foreground,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Light,
                        lineHeight = 25.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        MetroButton(
            stringResource(R.string.whatsnew_done),
            fill = accent,
            modifier = Modifier.fillMaxWidth(),
        ) { onDone() }
    }
}
