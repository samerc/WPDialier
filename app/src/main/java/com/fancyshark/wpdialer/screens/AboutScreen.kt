package com.fancyshark.wpdialer.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.ui.Accent
import com.fancyshark.wpdialer.ui.Metro

private const val CONTACT_EMAIL = "fancyshark505@gmail.com"

@Composable
fun AboutScreen(accent: Accent) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

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
            "about",
            color = Metro.Foreground,
            fontSize = 48.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(30.dp))

        Text(
            "Dialer 8",
            color = accent.color,
            fontSize = 40.sp,
            fontWeight = FontWeight.Light,
        )
        Text(
            "a Windows Phone styled dialer",
            color = Metro.Subtle,
            fontSize = 16.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 2.dp),
        )

        Spacer(Modifier.height(30.dp))
        Text(
            "version",
            color = Metro.Foreground,
            fontSize = 25.sp,
            fontWeight = FontWeight.Light,
        )
        Text(version, color = accent.color, fontSize = 15.sp)

        Spacer(Modifier.height(22.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clickable {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_SENDTO,
                                android.net.Uri.parse(
                                    "mailto:${android.net.Uri.encode(CONTACT_EMAIL)}",
                                ),
                            ).putExtra(
                                android.content.Intent.EXTRA_SUBJECT,
                                "Dialer 8 feedback",
                            ),
                        )
                    }
                }
                .padding(vertical = 4.dp),
        ) {
            Text(
                "contact",
                color = Metro.Foreground,
                fontSize = 25.sp,
                fontWeight = FontWeight.Light,
            )
            Text(CONTACT_EMAIL, color = accent.color, fontSize = 15.sp)
        }

        Spacer(Modifier.height(30.dp))
    }
}
