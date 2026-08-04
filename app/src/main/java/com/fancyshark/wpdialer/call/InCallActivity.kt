package com.fancyshark.wpdialer.call

import android.os.Bundle
import android.telecom.Call
import android.telecom.CallEndpoint
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
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
import kotlinx.coroutines.delay

class InCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        onBackPressedDispatcher.addCallback(this) {
            CallManager.userDismissedUi = true
            finish()
        }
        AccentStore.init(this)
        FontStore.init(this)
        com.fancyshark.wpdialer.data.AppPrefs.init(this)
        Metro.light = com.fancyshark.wpdialer.data.AppPrefs.light.value
        setContent {
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

    // Flip/keypad phones: the call key answers a ringing call, the end key
    // hangs up (fallback — most devices route ENDCALL through telecom), and
    // hardware digits send DTMF during the call.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_CALL -> {
                if (CallManager.state.value == Call.STATE_RINGING) CallManager.answer()
                return true
            }
            android.view.KeyEvent.KEYCODE_ENDCALL -> {
                CallManager.hangup()
                return true
            }
        }
        val dtmf = dtmfChar(keyCode)
        if (dtmf != null && CallManager.state.value == Call.STATE_ACTIVE) {
            CallManager.startDtmf(dtmf)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (dtmfChar(keyCode) != null && CallManager.state.value == Call.STATE_ACTIVE) {
            CallManager.stopDtmf()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun dtmfChar(keyCode: Int): Char? = when (keyCode) {
        in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 ->
            '0' + (keyCode - android.view.KeyEvent.KEYCODE_0)
        android.view.KeyEvent.KEYCODE_STAR -> '*'
        android.view.KeyEvent.KEYCODE_POUND -> '#'
        else -> null
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
    val displayName = caller.first ?: number.ifBlank { "unknown" }

    Box(
        Modifier
            .fillMaxSize()
            .background(Metro.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        when {
            call == null || state == Call.STATE_DISCONNECTED -> {
                EndedScreen(displayName, onFinished)
            }
            state == Call.STATE_RINGING -> {
                IncomingScreen(
                    contactName = caller.first,
                    number = number,
                    photoUri = caller.second,
                    accent = accent.color,
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
    LaunchedEffect(Unit) {
        delay(1200)
        onFinished()
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("PHONE", color = Metro.Foreground, fontSize = 15.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(24.dp))
        Text(name, color = Metro.Foreground, fontSize = 40.sp, fontWeight = FontWeight.Light)
        Text("call ended", color = Metro.Subtle, fontSize = 18.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun IncomingScreen(
    contactName: String?,
    number: String,
    photoUri: String?,
    accent: Color,
) {
    val context = LocalContext.current
    val country by produceState<String?>(null, number, contactName) {
        if (contactName == null) value = Repo.callerCountry(context, number)
    }

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("PHONE", color = Metro.Foreground, fontSize = 15.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "incoming call",
            color = Metro.Subtle,
            fontSize = 17.sp,
            fontWeight = FontWeight.Light,
        )
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (contactName != null) {
                ContactTile(contactName, photoUri, accent, 148.dp)
                Spacer(Modifier.height(24.dp))
            }
            Text(
                contactName ?: number.ifBlank { "unknown" },
                color = Metro.Foreground,
                fontSize = if ((contactName ?: number).length > 14) 38.sp else 48.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 52.sp,
                textAlign = TextAlign.Center,
            )
            if (contactName != null && number.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    number,
                    color = Metro.Subtle,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.Center,
                )
            }
            if (contactName == null && country != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    country ?: "",
                    color = Metro.Subtle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        var showReplies by remember { mutableStateOf(false) }
        val replies by com.fancyshark.wpdialer.data.AppPrefs.rejectMessages.collectAsState()
        if (showReplies) {
            replies.forEach { message ->
                Text(
                    message,
                    color = accent,
                    fontSize = 19.sp,
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
                "answer",
                fill = accent,
                modifier = Modifier.weight(1f),
            ) { CallManager.answer() }
            MetroButton(
                "ignore",
                modifier = Modifier.weight(1f),
            ) { CallManager.reject() }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (showReplies) "hide text replies" else "text reply",
            color = Metro.Subtle,
            fontSize = 17.sp,
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

@Composable
private fun ActiveScreen(
    name: String,
    number: String,
    state: Int,
    accent: Color,
    photoUri: String? = null,
) {
    val endpoint by CallManager.endpoint.collectAsState()
    val available by CallManager.availableEndpoints.collectAsState()
    val muted by CallManager.muted.collectAsState()
    val call by CallManager.call.collectAsState()
    var showKeypad by remember { mutableStateOf(false) }

    val speakerOn = endpoint?.endpointType == CallEndpoint.TYPE_SPEAKER
    val bluetoothOn = endpoint?.endpointType == CallEndpoint.TYPE_BLUETOOTH
    val bluetoothAvailable = available.any { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
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
        Call.STATE_DIALING, Call.STATE_CONNECTING -> "calling..."
        Call.STATE_HOLDING -> "on hold"
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

    Column(Modifier.fillMaxSize()) {
        // WP 8.1 places the caller info on a chrome-gray panel.
        Column(Modifier.fillMaxWidth().background(Metro.Key).padding(24.dp)) {
        Text("PHONE", color = Metro.Foreground, fontSize = 15.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(28.dp))
        Text(statusLine, color = accent, fontSize = 19.sp, fontWeight = FontWeight.Normal)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (photoUri != null) {
                ContactTile(name, photoUri, accent, 72.dp)
                Spacer(Modifier.height(0.dp))
            }
            Text(
                name,
                color = Metro.Foreground,
                fontSize = 42.sp,
                fontWeight = FontWeight.Light,
                lineHeight = 46.sp,
                modifier = if (photoUri != null) Modifier.padding(start = 14.dp) else Modifier,
            )
        }
        if (number.isNotBlank() && name != number) {
            Text(number, color = Metro.Subtle, fontSize = 18.sp, fontWeight = FontWeight.Light)
        }
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
        if (simLabel != null) {
            Text(
                simLabel,
                color = Metro.Subtle,
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (secondCall != null && secondState == Call.STATE_RINGING) {
            Spacer(Modifier.height(20.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(2.dp, accent)
                    .padding(12.dp),
            ) {
                Text(
                    "incoming call",
                    color = Metro.Subtle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light,
                )
                Text(
                    secondCaller.first ?: secondNumber.ifBlank { "unknown" },
                    color = Metro.Foreground,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetroButton(
                        "answer & hold",
                        fill = accent,
                        modifier = Modifier.weight(1f),
                    ) { CallManager.answerWaiting() }
                    MetroButton("ignore", modifier = Modifier.weight(1f)) {
                        CallManager.rejectWaiting()
                    }
                }
            }
        }
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.padding(horizontal = 24.dp)) {

        if (showKeypad) {
            DtmfPad()
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconTile(
                    Icons.AutoMirrored.Filled.VolumeUp, "speaker", speakerOn, accent,
                    modifier = Modifier.weight(1f),
                ) { CallManager.setSpeaker(!speakerOn) }
                IconTile(
                    Icons.Filled.MicOff, "mute", muted, accent,
                    modifier = Modifier.weight(1f),
                ) { CallManager.setMuted(!muted) }
                IconTile(
                    Icons.Filled.Bluetooth, "Bluetooth", bluetoothOn, accent,
                    enabled = bluetoothAvailable,
                    modifier = Modifier.weight(1f),
                ) { CallManager.setBluetooth(!bluetoothOn) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val context = androidx.compose.ui.platform.LocalContext.current
                IconTile(
                    Icons.Filled.Pause, "hold", held, accent,
                    modifier = Modifier.weight(1f),
                ) { CallManager.setHold(!held) }
                if (secondCall != null && secondState == Call.STATE_HOLDING) {
                    IconTile(
                        Icons.Filled.SwapCalls, "swap", false, accent,
                        modifier = Modifier.weight(1f),
                    ) { CallManager.swap() }
                    IconTile(
                        Icons.Filled.CallMerge, "merge", false, accent,
                        modifier = Modifier.weight(1f),
                    ) { CallManager.merge() }
                } else {
                    IconTile(
                        Icons.Filled.Add, "add call", false, accent,
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
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetroButton("end call", fill = accent, modifier = Modifier.weight(2f).height(64.dp)) {
                CallManager.hangup()
            }
            IconTile(
                Icons.Filled.Dialpad, "keypad", showKeypad, accent,
                modifier = Modifier.weight(1f),
            ) { showKeypad = !showKeypad }
        }
        Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun IconTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val fg = if (enabled) Metro.Foreground else Metro.Dim
    Box(
        modifier
            .height(64.dp)
            .background(if (active) accent else Metro.Key)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(20.dp))
            Text(label, color = fg, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun DtmfPad() {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { key ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(56.dp)
                            .background(Metro.Key)
                            .pointerInput(key) {
                                detectTapGestures(
                                    onPress = {
                                        CallManager.startDtmf(key[0])
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
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Light,
                        )
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
