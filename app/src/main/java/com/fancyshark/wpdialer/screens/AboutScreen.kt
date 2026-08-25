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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.R
import com.fancyshark.wpdialer.ui.Accent
import com.fancyshark.wpdialer.ui.Metro

private const val CONTACT_EMAIL = "fancyshark505@gmail.com"
private const val PRIVACY_URL = "https://github.com/samerc/WPDialier/blob/main/PRIVACY.md"

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
            stringResource(R.string.about_header),
            color = Metro.Foreground,
            fontSize = 15.sp,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(top = 18.dp),
        )
        Text(
            stringResource(R.string.about_title),
            color = Metro.Foreground,
            fontSize = 48.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.height(30.dp))

        Text(
            stringResource(R.string.about_app_name),
            color = accent.color,
            fontSize = 40.sp,
            fontWeight = FontWeight.Light,
        )
        Text(
            stringResource(R.string.about_tagline),
            color = Metro.Subtle,
            fontSize = 16.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 2.dp),
        )

        Spacer(Modifier.height(30.dp))
        Text(
            stringResource(R.string.about_version),
            color = Metro.Foreground,
            fontSize = 25.sp,
            fontWeight = FontWeight.Light,
        )
        Text(version, color = accent.color, fontSize = 15.sp)

        // Tip jar — appears only when Play returns configured products
        // (hidden on devices without Play or until Console setup is done).
        LaunchedEffect(Unit) { com.fancyshark.wpdialer.data.Tips.init(context) }
        val tipProducts by com.fancyshark.wpdialer.data.Tips.products.collectAsState()
        val thanked by com.fancyshark.wpdialer.data.Tips.thanked.collectAsState()
        if (tipProducts.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text(
                stringResource(R.string.about_tip_title),
                color = Metro.Foreground,
                fontSize = 25.sp,
                fontWeight = FontWeight.Light,
            )
            Text(
                stringResource(R.string.about_tip_hint),
                color = accent.color,
                fontSize = 15.sp,
            )
            tipProducts.forEach { product ->
                val label = when (product.productId) {
                    "tip_small" -> stringResource(R.string.about_tip_small)
                    "tip_medium" -> stringResource(R.string.about_tip_medium)
                    else -> stringResource(R.string.about_tip_large)
                }
                val price = product.oneTimePurchaseOfferDetails?.formattedPrice.orEmpty()
                Text(
                    if (price.isEmpty()) label else "$label — $price",
                    color = Metro.Foreground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            (context as? android.app.Activity)?.let {
                                com.fancyshark.wpdialer.data.Tips.launch(it, product)
                            }
                        }
                        .padding(vertical = 6.dp),
                )
            }
            if (thanked) {
                Text(
                    stringResource(R.string.about_tip_thanks),
                    color = accent.color,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        val feedbackSubject = stringResource(R.string.about_feedback_subject)
        Column(
            Modifier
                .fillMaxWidth()
                .clickable {
                    runCatching {
                        // Subject rides in the mailto URI — some Gmail builds
                        // ignore EXTRA_SUBJECT/EXTRA_TEXT on ACTION_SENDTO.
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_SENDTO,
                                android.net.Uri.parse(
                                    "mailto:${android.net.Uri.encode(CONTACT_EMAIL)}" +
                                        "?subject=${android.net.Uri.encode(feedbackSubject)}",
                                ),
                            ),
                        )
                    }
                }
                .padding(vertical = 4.dp),
        ) {
            Text(
                stringResource(R.string.about_contact),
                color = Metro.Foreground,
                fontSize = 25.sp,
                fontWeight = FontWeight.Light,
            )
            Text(CONTACT_EMAIL, color = accent.color, fontSize = 15.sp)
        }

        Spacer(Modifier.height(22.dp))
        val reportSubject = stringResource(R.string.about_report_subject)
        val reportNoCrash = stringResource(R.string.about_report_no_crash)
        Column(
            Modifier
                .fillMaxWidth()
                .clickable {
                    val log = com.fancyshark.wpdialer.data.CrashLog.tail(context)
                    val body = buildString {
                        append("Dialer 8 $version — Android ")
                        append(android.os.Build.VERSION.RELEASE)
                        append(" — ")
                        append(android.os.Build.MANUFACTURER)
                        append(' ')
                        append(android.os.Build.MODEL)
                        append("\n\n\n\n")
                        append(log ?: reportNoCrash)
                    }
                    runCatching {
                        // Subject/body ride in the mailto URI — some Gmail
                        // builds ignore the intent extras on ACTION_SENDTO.
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_SENDTO,
                                android.net.Uri.parse(
                                    "mailto:${android.net.Uri.encode(CONTACT_EMAIL)}" +
                                        "?subject=${android.net.Uri.encode(reportSubject)}" +
                                        "&body=${android.net.Uri.encode(body)}",
                                ),
                            ),
                        )
                    }
                }
                .padding(vertical = 4.dp),
        ) {
            Text(
                stringResource(R.string.about_report),
                color = Metro.Foreground,
                fontSize = 25.sp,
                fontWeight = FontWeight.Light,
            )
            Text(
                stringResource(R.string.about_report_hint),
                color = accent.color,
                fontSize = 15.sp,
            )
        }

        Spacer(Modifier.height(22.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clickable {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(PRIVACY_URL),
                            ),
                        )
                    }
                }
                .padding(vertical = 4.dp),
        ) {
            Text(
                stringResource(R.string.about_privacy),
                color = Metro.Foreground,
                fontSize = 25.sp,
                fontWeight = FontWeight.Light,
            )
            Text(
                stringResource(R.string.about_privacy_hint),
                color = accent.color,
                fontSize = 15.sp,
            )
        }

        Spacer(Modifier.height(30.dp))
    }
}
