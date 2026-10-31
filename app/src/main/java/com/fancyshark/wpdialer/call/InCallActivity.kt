package com.fancyshark.wpdialer.call

import android.os.Bundle
import android.telecom.Call
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.fancyshark.wpdialer.screens.decodeSampled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import com.fancyshark.wpdialer.data.Repo
import com.fancyshark.wpdialer.ui.AccentStore
import com.fancyshark.wpdialer.ui.ContactTile
import com.fancyshark.wpdialer.ui.FontStore
import com.fancyshark.wpdialer.ui.Selawik
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroButton
import com.fancyshark.wpdialer.ui.MetroConfirm
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

// Digits sent as DTMF during the current keypad session, echoed above the
// pad. File-level so both the activity (hardware keys) and the composables
// can append.
private val dtmfTyped = MutableStateFlow("")

// Set when the in-call UI is opened with "show dialpad" (Telecom's
// onBringToForeground) — consumed by the active screen.
private val dialpadRequest = MutableStateFlow(false)

class InCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeIntent(intent)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        onBackPressedDispatcher.addCallback(this) {
            CallManager.userDismissedUi = true
            finish()
        }
        AccentStore.init(this)
        FontStore.init(this)
        com.fancyshark.wpdialer.data.AppPrefs.init(this)
        Metro.light = com.fancyshark.wpdialer.data.AppPrefs.isLight(this)
        setContent {
            // "Follow system" re-resolves on night-mode flips mid-call.
            com.fancyshark.wpdialer.ui.MetroThemeSync()
            // Light theme needs dark status/nav icons (edge-to-edge on 35+).
            com.fancyshark.wpdialer.ui.MetroSystemBars(window)
            val selawik by FontStore.selawik.collectAsState()
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalTextStyle provides
                    androidx.compose.ui.text.TextStyle(
                        fontFamily = if (selawik) Selawik
                        else androidx.compose.ui.text.font.FontFamily.Default,
                    ),
                androidx.compose.foundation.LocalIndication provides
                    com.fancyshark.wpdialer.ui.MetroIndication,
            ) {
                InCallRoot(onFinished = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(EXTRA_SHOW_DIALPAD, false) == true) {
            dialpadRequest.value = true
        }
    }

    companion object {
        const val EXTRA_SHOW_DIALPAD = "show_dialpad"
    }

    override fun onResume() {
        super.onResume()
        (CallManager.service as? WpInCallService)?.onInCallUiVisibility(true)
    }

    override fun onPause() {
        super.onPause()
        (CallManager.service as? WpInCallService)?.onInCallUiVisibility(false)
    }

    // Flip/keypad phones: the call key answers a ringing call, the end key
    // hangs up (fallback — most devices route ENDCALL through telecom), and
    // hardware digits send DTMF during the call.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_CALL -> {
                when {
                    CallManager.state.value == Call.STATE_RINGING -> CallManager.answer()
                    CallManager.secondState.value == Call.STATE_RINGING ->
                        CallManager.answerWaiting()
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_ENDCALL -> {
                CallManager.hangup()
                return true
            }
        }
        val dtmf = com.fancyshark.wpdialer.ui.DialKeys.charFor(keyCode, event)
        if (dtmf != null && CallManager.state.value == Call.STATE_ACTIVE) {
            CallManager.startDtmf(dtmf)
            dtmfTyped.value += dtmf
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // Unconditional: if the call left ACTIVE between key down and up, the
        // tone would otherwise stay latched on the connection.
        // Shift-agnostic: shift may be released before the digit key.
        if (com.fancyshark.wpdialer.ui.DialKeys.charFor(keyCode, false) != null ||
            com.fancyshark.wpdialer.ui.DialKeys.charFor(keyCode, true) != null
        ) {
            CallManager.stopDtmf()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}

@Composable
private fun InCallRoot(onFinished: () -> Unit) {
    val call by CallManager.call.collectAsState()
    val state by CallManager.state.collectAsState()
    val accent by AccentStore.accent.collectAsState()
    val context = LocalContext.current

    val number = call?.details?.handle?.schemeSpecificPart ?: ""
    val caller by produceState<Pair<String?, String?>>(null to null, number) {
        value = Repo.lookupCaller(context, number)
    }
    // Saved contact first; else Telecom's name (its own contact match, then
    // the network-provided CNAP name) — keeps a name on screen where our
    // lookup finds nothing. Telecom fills its name in asynchronously, so the
    // read is keyed to detailsTick to recompute when it arrives mid-ring.
    val detailsTick by CallManager.detailsTick.collectAsState()
    val resolvedName = remember(caller, call, detailsTick) {
        caller.first ?: CallManager.telecomName(call)
    }
    val unknownLabel = stringResource(com.fancyshark.wpdialer.R.string.call_unknown)
    val isConference =
        call?.details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) == true
    val displayName = when {
        isConference -> stringResource(com.fancyshark.wpdialer.R.string.call_conference)
        else -> resolvedName ?: number.ifBlank { unknownLabel }
    }
    // The call object vanishes on disconnect before the ended screen shows —
    // keep the last caller name so it doesn't fall back to "unknown".
    var lastKnownName by remember { mutableStateOf(unknownLabel) }
    LaunchedEffect(displayName, call) {
        if (call != null && displayName != unknownLabel) lastKnownName = displayName
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Metro.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        when {
            call == null || state == Call.STATE_DISCONNECTED -> {
                EndedScreen(if (call == null) lastKnownName else displayName, onFinished)
            }
            state == Call.STATE_SELECT_PHONE_ACCOUNT -> {
                SimChooserScreen(displayName, call)
            }
            state == Call.STATE_RINGING -> {
                IncomingScreen(
                    contactName = resolvedName,
                    // Telecom/CNAP names are unverified network strings — only
                    // a real saved contact earns the contact tile, and unsaved
                    // callers keep their country line.
                    isSavedContact = caller.first != null,
                    number = number,
                    photoUri = caller.second,
                    accent = accent.color,
                    // Keyed to detailsTick like the name: details can update.
                    verified = remember(call, detailsTick) { CallManager.callerVerified(call) },
                )
            }
            else -> {
                ActiveScreen(displayName, number, state, accent.color, caller.second)
            }
        }
    }
}

@Composable
private fun EndedScreen(name: String, onFinished: () -> Unit) {
    // Busy / no signal / blocked: the network's reason replaces the generic
    // line and stays up long enough to read.
    val reason by CallManager.endReason.collectAsState()
    LaunchedEffect(Unit) {
        delay(if (CallManager.endWasError) 3000 else 1200)
        onFinished()
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.call_app_title),
            color = Metro.Foreground,
            style = Metro.Type.AppTitle,
        )
        Spacer(Modifier.height(24.dp))
        Text(name, color = Metro.Foreground, style = Metro.Type.Header)
        Text(
            reason ?: stringResource(com.fancyshark.wpdialer.R.string.call_ended),
            color = Metro.Subtle,
            style = Metro.Type.Body,
            fontWeight = FontWeight.Light,
        )
    }
}

/**
 * A call Telecom parked waiting for a SIM (placed without one on an "ask
 * every time" device and no saved preference applied). Back/cancel ends it.
 */
@Composable
private fun SimChooserScreen(name: String, call: Call?) {
    val context = LocalContext.current
    val accent by AccentStore.accent.collectAsState()
    val options = remember(call) {
        val choices = CallManager.accountChoices(call)
        val sims = com.fancyshark.wpdialer.data.Sims.options(context)
        if (choices.isEmpty()) sims else sims.filter { it.handle in choices }
    }
    androidx.activity.compose.BackHandler { CallManager.hangup() }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.main_choose_sim_title),
            color = Metro.Foreground,
            style = Metro.Type.AppTitle,
        )
        Spacer(Modifier.height(24.dp))
        Text(name, color = Metro.Foreground, style = Metro.Type.Header)
        Spacer(Modifier.height(24.dp))
        options.forEach { sim ->
            Text(
                sim.label,
                color = Metro.Foreground,
                style = Metro.Type.SubHeader,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = androidx.compose.ui.semantics.Role.Button) {
                        CallManager.selectAccount(sim.handle)
                    }
                    .padding(vertical = 12.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        MetroButton(
            stringResource(com.fancyshark.wpdialer.R.string.call_end_call),
            fill = accent.color,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { CallManager.hangup() }
        Spacer(Modifier.height(16.dp))
    }
}

// Full-bleed caller photo (WP8 in-call): decoded off the main thread at
// screen-ish resolution — a display photo is up to 1080px or more, and
// inSampleSize keeps a huge one from costing a full-size bitmap.
private const val HERO_PHOTO_PX = 1080

// Below this height the photo band can't hold the caller lines; the
// screen falls back to the classic layout instead of clipping them.
private val HERO_MIN_HEIGHT = 220.dp

// Under this height the hero name drops to one line.
private val HERO_ROOMY_HEIGHT = 280.dp

/** The caller's contact photo for the hero: loading, decoded, or none. */
private sealed interface HeroPhoto {
    data object None : HeroPhoto
    data object Loading : HeroPhoto
    class Ready(val bitmap: ImageBitmap) : HeroPhoto
}

@Composable
private fun rememberHeroPhoto(photoUri: String?): HeroPhoto {
    val context = LocalContext.current
    val photo by produceState<HeroPhoto>(
        if (photoUri == null) HeroPhoto.None else HeroPhoto.Loading,
        photoUri,
    ) {
        value = if (photoUri == null) {
            HeroPhoto.None
        } else {
            // A swap to another caller must not keep showing the old face.
            value = HeroPhoto.Loading
            withContext(Dispatchers.IO) {
                decodeSampled(context, android.net.Uri.parse(photoUri), HERO_PHOTO_PX)
            }?.asImageBitmap()?.let { HeroPhoto.Ready(it) } ?: HeroPhoto.None
        }
    }
    return photo
}

/**
 * WP8 caller hero: the photo fills the full width (center-cropped to
 * [height]) with gradient scrims so white text stays readable on any
 * photo. While the photo decodes it's a plain accent band (text in the
 * accent's contrast color), so the layout doesn't jump when it lands.
 * [content] gets the text and subtle-text colors to use on top.
 */
@Composable
private fun CallerHero(
    photo: ImageBitmap?,
    accent: Color,
    height: Dp,
    content: @Composable BoxScope.(fg: Color, subtle: Color) -> Unit,
) {
    val fg = if (photo != null) Color.White else Metro.onAccent(accent)
    val subtle = fg.copy(alpha = 0.8f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds()
            .background(accent),
    ) {
        if (photo != null) {
            Image(
                photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            // Bottom scrim under the caller lines, a lighter one under the
            // app title at the top.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                        ),
                    ),
            )
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent),
                        ),
                    ),
            )
        }
        content(fg, subtle)
    }
}

/**
 * STIR/SHAKEN: the network vouches for (or rejects) the number. Nothing is
 * shown for unverified calls, which are most of them. [onHero] is the text
 * color when drawn over the caller photo/band (accent text would vanish
 * on an accent band).
 */
@Composable
private fun VerifiedBadge(verified: Boolean, accent: Color, onHero: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (verified) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = when {
                !verified -> Metro.Red
                onHero != null -> onHero
                else -> Metro.textAccent(accent)
            },
            modifier = Modifier.size(18.dp),
        )
        Text(
            stringResource(
                if (verified) {
                    com.fancyshark.wpdialer.R.string.call_verified
                } else {
                    com.fancyshark.wpdialer.R.string.call_spoof_warning
                },
            ),
            color = if (verified) onHero?.copy(alpha = 0.8f) ?: Metro.Subtle else Metro.Red,
            style = Metro.Type.Small,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun IncomingScreen(
    contactName: String?,
    isSavedContact: Boolean,
    number: String,
    photoUri: String?,
    accent: Color,
    verified: Boolean? = null,
) {
    val context = LocalContext.current
    val country by produceState<String?>(null, number, isSavedContact) {
        if (!isSavedContact) value = Repo.callerCountry(context, number)
    }
    // Only a real saved contact earns the photo (CNAP names never do).
    val heroPhoto = rememberHeroPhoto(if (isSavedContact) photoUri else null)
    val shownName = contactName
        ?: number.ifBlank { stringResource(com.fancyshark.wpdialer.R.string.call_unknown) }
    val nameStyle = if ((contactName ?: number).length > 14) Metro.Type.Header else Metro.Type.PageTitle

    Column(Modifier.fillMaxSize()) {
        // Caller area takes whatever the buttons leave — the answer/ignore
        // row can never be pushed off a short screen.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val heroHeight = minOf(maxWidth, maxHeight)
            if (heroPhoto != HeroPhoto.None && heroHeight >= HERO_MIN_HEIGHT) {
                CallerHero(
                    photo = (heroPhoto as? HeroPhoto.Ready)?.bitmap,
                    accent = accent,
                    height = heroHeight,
                ) { fg, subtle ->
                    Column(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 24.dp, end = 24.dp, top = 24.dp),
                    ) {
                        Text(
                            stringResource(com.fancyshark.wpdialer.R.string.call_app_title),
                            color = fg,
                            style = Metro.Type.AppTitle,
                        )
                        Text(
                            stringResource(com.fancyshark.wpdialer.R.string.call_incoming),
                            color = subtle,
                            style = Metro.Type.Body,
                            fontWeight = FontWeight.Light,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 18.dp),
                    ) {
                        Text(
                            shownName,
                            color = fg,
                            style = nameStyle,
                            lineHeight = 52.sp,
                            maxLines = if (heroHeight >= HERO_ROOMY_HEIGHT) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (contactName != null && number.isNotBlank()) {
                            Text(
                                number,
                                color = subtle,
                                style = Metro.Type.Large,
                                fontWeight = FontWeight.Light,
                                maxLines = 1,
                            )
                        }
                        if (verified != null) {
                            Spacer(Modifier.height(8.dp))
                            VerifiedBadge(verified, accent, onHero = fg)
                        }
                    }
                }
            } else {
                IncomingClassic(
                    contactName = contactName,
                    shownName = shownName,
                    nameStyle = nameStyle,
                    isSavedContact = isSavedContact,
                    number = number,
                    photoUri = photoUri,
                    country = country,
                    accent = accent,
                    verified = verified,
                )
            }
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Spacer(Modifier.height(16.dp))
            var showReplies by remember { mutableStateOf(false) }
            val replies by com.fancyshark.wpdialer.data.AppPrefs.rejectMessages.collectAsState()
            if (showReplies) {
                replies.forEach { message ->
                    Text(
                        message,
                        color = Metro.textAccent(accent),
                        style = Metro.Type.Body,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { CallManager.rejectWithMessage(message) }
                            .padding(vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetroButton(
                    stringResource(com.fancyshark.wpdialer.R.string.call_answer),
                    fill = accent,
                    modifier = Modifier.weight(1f),
                ) { CallManager.answer() }
                MetroButton(
                    stringResource(com.fancyshark.wpdialer.R.string.call_ignore),
                    modifier = Modifier.weight(1f),
                ) { CallManager.reject() }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (showReplies) {
                    stringResource(com.fancyshark.wpdialer.R.string.call_hide_text_replies)
                } else {
                    stringResource(com.fancyshark.wpdialer.R.string.call_text_reply)
                },
                color = Metro.Subtle,
                style = Metro.Type.Body,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showReplies = !showReplies }
                    .padding(vertical = 6.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** No photo (or no room for one): title top-left, caller block centered. */
@Composable
private fun IncomingClassic(
    contactName: String?,
    shownName: String,
    nameStyle: androidx.compose.ui.text.TextStyle,
    isSavedContact: Boolean,
    number: String,
    photoUri: String?,
    country: String?,
    accent: Color,
    verified: Boolean?,
) {
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 24.dp)) {
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.call_app_title),
            color = Metro.Foreground,
            style = Metro.Type.AppTitle,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.call_incoming),
            color = Metro.Subtle,
            style = Metro.Type.Body,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (isSavedContact && contactName != null) {
                ContactTile(contactName, photoUri, accent, 148.dp)
                Spacer(Modifier.height(24.dp))
            }
            Text(
                shownName,
                color = Metro.Foreground,
                style = nameStyle,
                lineHeight = 52.sp,
                textAlign = TextAlign.Center,
            )
            if (contactName != null && number.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    number,
                    color = Metro.Subtle,
                    style = Metro.Type.Large,
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.Center,
                )
            }
            if (!isSavedContact && country != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    country,
                    color = Metro.Subtle,
                    style = Metro.Type.Small,
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.Center,
                )
            }
            if (verified != null) {
                Spacer(Modifier.height(10.dp))
                VerifiedBadge(verified, accent)
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/**
 * Classic WP 8.1 caller panel on chrome gray: used when there's no photo,
 * no room for the hero, or during RTT. A photo that failed to decode still
 * gets its small tile (initials fallback).
 */
@Composable
private fun CallInfoPanel(
    status: String,
    name: String,
    numberLine: String?,
    simLabel: String?,
    accent: Color,
    photoUri: String?,
) {
    Column(Modifier.fillMaxWidth().background(Metro.Key).padding(24.dp)) {
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.call_app_title),
            color = Metro.Foreground,
            style = Metro.Type.AppTitle,
        )
        Spacer(Modifier.height(28.dp))
        CallInfoLines(
            status = status,
            statusColor = Metro.textAccent(accent),
            name = name,
            numberLine = numberLine,
            simLabel = simLabel,
            fg = Metro.Foreground,
            subtle = Metro.Subtle,
            leading = if (photoUri != null) {
                { ContactTile(name, photoUri, accent, 72.dp) }
            } else {
                null
            },
        )
    }
}

/** Status/timer, name, number and SIM lines, shared by panel and hero. */
@Composable
private fun CallInfoLines(
    status: String,
    statusColor: Color,
    name: String,
    numberLine: String?,
    simLabel: String?,
    fg: Color,
    subtle: Color,
    nameMaxLines: Int = 2,
    leading: (@Composable () -> Unit)? = null,
) {
    Text(status, color = statusColor, style = Metro.Type.Body)
    Row(verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Text(
            name,
            color = fg,
            style = Metro.Type.Header,
            lineHeight = 46.sp,
            maxLines = nameMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = if (leading != null) Modifier.padding(start = 14.dp) else Modifier,
        )
    }
    if (numberLine != null) {
        Text(
            numberLine,
            color = subtle,
            style = Metro.Type.Body,
            fontWeight = FontWeight.Light,
            maxLines = 1,
        )
    }
    if (simLabel != null) {
        Text(
            simLabel,
            color = subtle,
            style = Metro.Type.Small,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ActiveScreen(
    name: String,
    number: String,
    state: Int,
    accent: Color,
    photoUri: String? = null,
) {
    val route by CallManager.route.collectAsState()
    val available by CallManager.availableRoutes.collectAsState()
    val muted by CallManager.muted.collectAsState()
    val call by CallManager.call.collectAsState()
    var showKeypad by remember { mutableStateOf(false) }
    val wantDialpad by dialpadRequest.collectAsState()
    LaunchedEffect(wantDialpad) {
        if (wantDialpad) {
            showKeypad = true
            dialpadRequest.value = false
        }
    }
    // Capabilities change with the network's state (e.g. hold unavailable
    // while dialing, merge once the second call connects) — keyed to ticks.
    val capsTick by CallManager.detailsTick.collectAsState()
    val secondForCaps by CallManager.secondCall.collectAsState()
    val holdAllowed = remember(call, state, capsTick) {
        state == Call.STATE_HOLDING || CallManager.canHold(call)
    }
    val mergeAllowed = remember(call, secondForCaps, state, capsTick) { CallManager.canMerge() }

    // The digit echo belongs to one call: reset when the primary call
    // changes (swap/new call), never on a keypad toggle — hardware-typed
    // digits from this call must survive opening the on-screen pad.
    LaunchedEffect(call) { dtmfTyped.value = "" }

    val speakerOn = route == AudioRoute.SPEAKER
    val bluetoothOn = route == AudioRoute.BLUETOOTH
    val bluetoothAvailable = AudioRoute.BLUETOOTH in available
    val held = state == Call.STATE_HOLDING

    // Tick once a second while connected so the timer advances.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val connectTime = call?.details?.connectTimeMillis ?: 0L
    val statusLine = when (state) {
        Call.STATE_DIALING, Call.STATE_CONNECTING ->
            stringResource(com.fancyshark.wpdialer.R.string.call_calling)
        Call.STATE_HOLDING -> stringResource(com.fancyshark.wpdialer.R.string.call_on_hold)
        Call.STATE_ACTIVE -> {
            if (connectTime > 0) formatDuration((now - connectTime) / 1000) else "00:00"
        }
        else -> ""
    }

    val secondCall by CallManager.secondCall.collectAsState()
    val secondState by CallManager.secondState.collectAsState()
    val context2 = LocalContext.current
    val secondNumber = secondCall?.details?.handle?.schemeSpecificPart ?: ""
    val secondCaller by produceState<Pair<String?, String?>>(null to null, secondNumber) {
        value = Repo.lookupCaller(context2, secondNumber)
    }

    // RTT: offered when the call's account supports it and the call is live.
    val rttActive by CallManager.rttActive.collectAsState()
    val rttSupported = remember(call, state, capsTick) {
        state == Call.STATE_ACTIVE && CallManager.supportsRtt(context2, call)
    }
    val postDialWait by CallManager.postDialWait.collectAsState()
    val rttRequest by CallManager.rttRequest.collectAsState()

    // On dual-SIM devices, show which SIM carries this call.
    val simLabel = remember(call) {
        runCatching {
            val sims = com.fancyshark.wpdialer.data.Sims.options(context2)
            if (sims.size > 1) {
                sims.firstOrNull { it.handle == call?.details?.accountHandle }?.label
            } else {
                null
            }
        }.getOrNull()
    }
    val numberLine = if (number.isNotBlank() && name != number) Repo.pretty(context2, number) else null
    val heroPhoto = rememberHeroPhoto(photoUri)

    var showBtPicker by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().imePadding()) {
    Column(Modifier.fillMaxSize()) {
        if (rttActive && !showKeypad) {
            // The conversation needs the room: compact panel, no photo.
            CallInfoPanel(statusLine, name, numberLine, simLabel, accent, photoUri)
            RttPanel(accent, Modifier.weight(1f))
        } else {
            // Caller area takes whatever the controls leave: the photo is a
            // full-width square when there's room, a center-cropped band
            // when the keypad or a waiting call needs space, and the classic
            // gray panel when even that won't hold the caller lines.
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val heroHeight = minOf(maxWidth, maxHeight)
                if (heroPhoto != HeroPhoto.None && heroHeight >= HERO_MIN_HEIGHT) {
                    CallerHero(
                        photo = (heroPhoto as? HeroPhoto.Ready)?.bitmap,
                        accent = accent,
                        height = heroHeight,
                    ) { fg, subtle ->
                        Text(
                            stringResource(com.fancyshark.wpdialer.R.string.call_app_title),
                            color = fg,
                            style = Metro.Type.AppTitle,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 24.dp, end = 24.dp, top = 24.dp),
                        )
                        Column(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 18.dp),
                        ) {
                            CallInfoLines(
                                status = statusLine,
                                statusColor = fg,
                                name = name,
                                numberLine = numberLine,
                                simLabel = simLabel,
                                fg = fg,
                                subtle = subtle,
                                nameMaxLines = if (heroHeight >= HERO_ROOMY_HEIGHT) 2 else 1,
                            )
                        }
                    }
                } else {
                    CallInfoPanel(statusLine, name, numberLine, simLabel, accent, photoUri)
                }
            }
        }

        if (secondCall != null && secondState == Call.STATE_RINGING) {
            Column(
                Modifier
                    .padding(start = 24.dp, end = 24.dp, top = 12.dp)
                    .fillMaxWidth()
                    .border(2.dp, accent)
                    .padding(12.dp),
            ) {
                Text(
                    stringResource(com.fancyshark.wpdialer.R.string.call_incoming),
                    color = Metro.Subtle,
                    style = Metro.Type.Small,
                    fontWeight = FontWeight.Light,
                )
                // Keyed to detailsTick: Telecom's name may arrive mid-ring.
                val secondTick by CallManager.detailsTick.collectAsState()
                val secondTelecomName = remember(secondCall, secondTick) {
                    CallManager.telecomName(secondCall)
                }
                Text(
                    secondCaller.first
                        ?: secondTelecomName
                        ?: secondNumber.ifBlank {
                            stringResource(com.fancyshark.wpdialer.R.string.call_unknown)
                        },
                    color = Metro.Foreground,
                    style = Metro.Type.SubHeader,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetroButton(
                        stringResource(com.fancyshark.wpdialer.R.string.call_answer_and_hold),
                        fill = accent,
                        modifier = Modifier.weight(1f),
                    ) { CallManager.answerWaiting() }
                    MetroButton(
                        stringResource(com.fancyshark.wpdialer.R.string.call_ignore),
                        modifier = Modifier.weight(1f),
                    ) {
                        CallManager.rejectWaiting()
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 24.dp)) {

        if (showKeypad) {
            // A tester read pre-answer DTMF silence as a bug — the network
            // only carries tones once the call connects, so say so.
            if (state == Call.STATE_DIALING || state == Call.STATE_CONNECTING) {
                Text(
                    stringResource(com.fancyshark.wpdialer.R.string.call_dtmf_after_answer),
                    color = Metro.Subtle,
                    style = Metro.Type.Small,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            }
            // Echo the digits sent so far — IVR menus ("press 1 for…") give
            // no other feedback that the press registered.
            val typed by dtmfTyped.collectAsState()
            Text(
                if (typed.isEmpty()) " " else typed.takeLast(18),
                color = Metro.Foreground,
                style = Metro.Type.SubHeader,
                maxLines = 1,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            DtmfPad()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconTile(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    stringResource(com.fancyshark.wpdialer.R.string.call_speaker),
                    speakerOn, accent,
                    toggle = true,
                    modifier = Modifier.weight(1f),
                ) { CallManager.setSpeaker(!speakerOn) }
                IconTile(
                    Icons.Filled.MicOff,
                    stringResource(com.fancyshark.wpdialer.R.string.call_mute),
                    muted, accent,
                    toggle = true,
                    modifier = Modifier.weight(1f),
                ) { CallManager.setMuted(!muted) }
                IconTile(
                    Icons.Filled.Bluetooth,
                    stringResource(com.fancyshark.wpdialer.R.string.call_bluetooth),
                    bluetoothOn, accent,
                    toggle = true,
                    enabled = bluetoothAvailable,
                    modifier = Modifier.weight(1f),
                ) {
                    // Several headsets connected (34+): pick one by name.
                    if (CallManager.bluetoothNames().size > 1) {
                        showBtPicker = true
                    } else {
                        CallManager.setBluetooth(!bluetoothOn)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val context = androidx.compose.ui.platform.LocalContext.current
                IconTile(
                    Icons.Filled.Pause,
                    stringResource(com.fancyshark.wpdialer.R.string.call_hold),
                    held, accent,
                    toggle = true,
                    enabled = holdAllowed,
                    modifier = Modifier.weight(1f),
                ) { CallManager.setHold(!held) }
                if (secondCall != null && secondState == Call.STATE_HOLDING) {
                    IconTile(
                        Icons.Filled.SwapCalls,
                        stringResource(com.fancyshark.wpdialer.R.string.call_swap),
                        false, accent,
                        modifier = Modifier.weight(1f),
                    ) { CallManager.swap() }
                    IconTile(
                        Icons.Filled.CallMerge,
                        stringResource(com.fancyshark.wpdialer.R.string.call_merge),
                        false, accent,
                        enabled = mergeAllowed,
                        modifier = Modifier.weight(1f),
                    ) { CallManager.merge() }
                } else {
                    IconTile(
                        Icons.Filled.Add,
                        stringResource(com.fancyshark.wpdialer.R.string.call_add_call),
                        false, accent,
                        modifier = Modifier.weight(1f),
                    ) {
                        // Open the dialpad to dial the second call; telecom
                        // puts the current call on hold automatically.
                        CallManager.userDismissedUi = true
                        context.startActivity(
                            android.content.Intent(
                                context,
                                com.fancyshark.wpdialer.MainActivity::class.java,
                            )
                                .setAction(android.content.Intent.ACTION_DIAL)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    // Third slot: RTT upgrade when the network offers it.
                    if (rttSupported && !rttActive) {
                        IconTile(
                            Icons.Filled.Keyboard,
                            stringResource(com.fancyshark.wpdialer.R.string.call_rtt),
                            false, accent,
                            modifier = Modifier.weight(1f),
                        ) { CallManager.requestRtt() }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetroButton(
                stringResource(com.fancyshark.wpdialer.R.string.call_end_call),
                fill = accent,
                modifier = Modifier.weight(2f).height(64.dp),
            ) {
                CallManager.hangup()
            }
            IconTile(
                Icons.Filled.Dialpad,
                stringResource(com.fancyshark.wpdialer.R.string.call_keypad),
                showKeypad, accent,
                toggle = true,
                modifier = Modifier.weight(1f),
            ) { showKeypad = !showKeypad }
        }
        Spacer(Modifier.height(16.dp))
        }
    }

    if (showBtPicker) {
        androidx.activity.compose.BackHandler { showBtPicker = false }
        Box(
            Modifier
                .fillMaxSize()
                .background(Metro.Background.copy(alpha = 0.97f))
                .clickable(
                    interactionSource = remember {
                        androidx.compose.foundation.interaction.MutableInteractionSource()
                    },
                    indication = null,
                ) { showBtPicker = false },
        ) {
            Column(Modifier.align(Alignment.Center).padding(horizontal = 24.dp)) {
                Text(
                    stringResource(com.fancyshark.wpdialer.R.string.call_choose_device),
                    color = Metro.Foreground,
                    style = Metro.Type.AppTitle,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                // "phone" drops Bluetooth entirely (default route).
                Text(
                    stringResource(com.fancyshark.wpdialer.R.string.call_device_phone),
                    color = Metro.Foreground,
                    style = Metro.Type.SubHeader,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            CallManager.setBluetooth(false)
                            showBtPicker = false
                        }
                        .padding(vertical = 10.dp),
                )
                // Snapshot at overlay open; selection matches by name so a
                // list change between render and tap can't misroute.
                val btDevices = remember { CallManager.bluetoothNames() }
                btDevices.forEach { deviceName ->
                    Text(
                        deviceName,
                        color = Metro.Foreground,
                        style = Metro.Type.SubHeader,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                CallManager.selectBluetooth(deviceName)
                                showBtPicker = false
                            }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }
    }

    // A wait (';') in the dialed number: Telecom holds the remaining tones
    // until the user confirms (e.g. once an IVR has answered).
    postDialWait?.let { wait ->
        MetroConfirm(
            title = stringResource(com.fancyshark.wpdialer.R.string.call_send_tones_title),
            message = wait.remaining,
            confirmLabel = stringResource(com.fancyshark.wpdialer.R.string.call_send),
            cancelLabel = stringResource(com.fancyshark.wpdialer.R.string.main_button_cancel),
            confirmFill = accent,
            dismissOnBackdrop = false,
            onConfirm = { CallManager.postDialContinue(true) },
            onCancel = { CallManager.postDialContinue(false) },
        )
    }

    if (rttRequest != null && postDialWait == null) {
        MetroConfirm(
            title = stringResource(com.fancyshark.wpdialer.R.string.call_rtt_request_title),
            message = stringResource(com.fancyshark.wpdialer.R.string.call_rtt_request_message),
            confirmLabel = stringResource(com.fancyshark.wpdialer.R.string.call_rtt_accept),
            cancelLabel = stringResource(com.fancyshark.wpdialer.R.string.notif_decline),
            confirmFill = accent,
            dismissOnBackdrop = false,
            onConfirm = { CallManager.respondToRttRequest(true) },
            onCancel = { CallManager.respondToRttRequest(false) },
        )
    }
    }
}

/**
 * Live RTT conversation: the other side's text as it arrives, and a field
 * whose every edit goes out immediately (new characters, or backspaces for
 * deletions; RTT has no "send" step).
 */
@Composable
private fun RttPanel(accent: Color, modifier: Modifier = Modifier) {
    val transcript by CallManager.rttTranscript.collectAsState()
    val call by CallManager.call.collectAsState()
    // Per call: a swap to another RTT call starts a fresh field.
    var typed by remember(call) { mutableStateOf("") }
    val scroll = androidx.compose.foundation.rememberScrollState()
    LaunchedEffect(transcript) { scroll.animateScrollTo(scroll.maxValue) }
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(
            stringResource(com.fancyshark.wpdialer.R.string.call_rtt_title),
            color = Metro.Foreground,
            style = Metro.Type.AppTitle,
        )
        Text(
            transcript,
            color = Metro.Foreground,
            style = Metro.Type.Large,
            fontWeight = FontWeight.Light,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .verticalScroll(scroll),
        )
        com.fancyshark.wpdialer.ui.MetroTextBox(
            value = typed,
            onValueChange = { next ->
                CallManager.rttWrite(RttText.delta(typed, next))
                typed = next
            },
            accent = accent,
            placeholder = stringResource(com.fancyshark.wpdialer.R.string.call_rtt_hint),
        )
    }
}

@Composable
private fun IconTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    toggle: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val fg = if (enabled) Metro.Foreground else Metro.Dim
    Box(
        modifier
            .height(64.dp)
            .background(if (active) accent else Metro.Key)
            .then(
                // Toggles announce on/off to TalkBack; the label Text below
                // is the accessible name (the icon stays silent — no echo).
                if (toggle) {
                    Modifier.toggleable(
                        value = active,
                        enabled = enabled,
                        role = androidx.compose.ui.semantics.Role.Switch,
                    ) { onClick() }
                } else {
                    Modifier.clickable(
                        enabled = enabled,
                        role = androidx.compose.ui.semantics.Role.Button,
                    ) { onClick() }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Text(label, color = fg, style = Metro.Type.Tiny, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun DtmfPad() {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
    // Keypads never mirror in RTL locales.
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides
            androidx.compose.ui.unit.LayoutDirection.Ltr,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            keys.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { key ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(56.dp)
                                .background(Metro.Key)
                                // pointerInput carries no semantics — give
                                // TalkBack a button whose activation sends a
                                // short tone (IVR menus must stay usable).
                                .semantics(mergeDescendants = true) {
                                    role = androidx.compose.ui.semantics.Role.Button
                                    onClick {
                                        CallManager.startDtmf(key[0])
                                        dtmfTyped.value += key
                                        scope.launch {
                                            delay(150)
                                            CallManager.stopDtmf()
                                        }
                                        true
                                    }
                                }
                                .pointerInput(key) {
                                    detectTapGestures(
                                        onPress = {
                                            CallManager.startDtmf(key[0])
                                            dtmfTyped.value += key
                                            tryAwaitRelease()
                                            CallManager.stopDtmf()
                                        },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                key,
                                color = Metro.Foreground,
                                style = Metro.Type.SubHeader,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
