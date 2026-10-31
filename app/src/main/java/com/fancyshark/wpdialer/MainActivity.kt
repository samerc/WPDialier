package com.fancyshark.wpdialer

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.fancyshark.wpdialer.call.CallManager
import com.fancyshark.wpdialer.data.ContactItem
import com.fancyshark.wpdialer.data.HistoryItem
import com.fancyshark.wpdialer.data.Repo
import com.fancyshark.wpdialer.data.SimOption
import com.fancyshark.wpdialer.data.SimPrefs
import com.fancyshark.wpdialer.data.Sims
import com.fancyshark.wpdialer.screens.ContactDetailScreen
import com.fancyshark.wpdialer.screens.DialpadBus
import com.fancyshark.wpdialer.screens.DialpadScreen
import com.fancyshark.wpdialer.screens.EditContactScreen
import com.fancyshark.wpdialer.screens.HistoryPage
import com.fancyshark.wpdialer.screens.NewContactScreen
import com.fancyshark.wpdialer.screens.PeoplePage
import com.fancyshark.wpdialer.screens.SearchScreen
import com.fancyshark.wpdialer.screens.SettingsScreen
import com.fancyshark.wpdialer.screens.SpeedDialPage
import com.fancyshark.wpdialer.ui.AccentStore
import com.fancyshark.wpdialer.ui.AppBarAction
import com.fancyshark.wpdialer.ui.FontStore
import com.fancyshark.wpdialer.ui.Haptics
import com.fancyshark.wpdialer.ui.Selawik
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroAppBar
import com.fancyshark.wpdialer.ui.MetroButton
import com.fancyshark.wpdialer.ui.MetroConfirm
import com.fancyshark.wpdialer.ui.MetroSystemBars
import com.fancyshark.wpdialer.ui.hiddenFromAccessibility
import com.fancyshark.wpdialer.ui.Pivot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class Dialpad(val initial: String) : Screen
    data class Contact(val id: Long) : Screen
    data class EditContact(val id: Long) : Screen
    data class NewContact(val initialNumber: String = "") : Screen
    data class CallDetails(val number: String, val name: String?) : Screen
    data object Search : Screen
    data object Settings : Screen
    data object About : Screen
}

/**
 * A call waiting on the user to choose a SIM. [onPlaced] runs once the call
 * actually goes out (the dialpad clears its number only then).
 */
data class SimRequest(val number: String, val contactId: Long?, val onPlaced: () -> Unit = {})

// Serializes the navigation stack across activity recreation (locale change,
// process death). Field delimiter is a control char that can't appear in
// phone numbers; names get their own trailing slot.
private const val SCREEN_SEP = '\u0001'

private val ScreenStackSaver = androidx.compose.runtime.saveable.listSaver<
    androidx.compose.runtime.snapshots.SnapshotStateList<Screen>, String,
>(
    save = { stack ->
        stack.map { screen ->
            when (screen) {
                Screen.Home -> "home"
                is Screen.Dialpad -> "dialpad$SCREEN_SEP${screen.initial}"
                is Screen.Contact -> "contact$SCREEN_SEP${screen.id}"
                is Screen.EditContact -> "edit$SCREEN_SEP${screen.id}"
                is Screen.NewContact -> "new$SCREEN_SEP${screen.initialNumber}"
                is Screen.CallDetails ->
                    "details$SCREEN_SEP${screen.number}$SCREEN_SEP${screen.name.orEmpty()}"
                Screen.Search -> "search"
                Screen.Settings -> "settings"
                Screen.About -> "about"
            }
        }
    },
    restore = { saved ->
        androidx.compose.runtime.mutableStateListOf<Screen>().apply {
            saved.forEach { entry ->
                val parts = entry.split(SCREEN_SEP)
                add(
                    when (parts[0]) {
                        "dialpad" -> Screen.Dialpad(parts.getOrElse(1) { "" })
                        "contact" -> Screen.Contact(parts.getOrNull(1)?.toLongOrNull() ?: 0L)
                        "edit" -> Screen.EditContact(parts.getOrNull(1)?.toLongOrNull() ?: 0L)
                        "new" -> Screen.NewContact(parts.getOrElse(1) { "" })
                        "details" -> Screen.CallDetails(
                            parts.getOrElse(1) { "" },
                            parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
                        )
                        "search" -> Screen.Search
                        "settings" -> Screen.Settings
                        "about" -> Screen.About
                        else -> Screen.Home
                    },
                )
            }
            if (isEmpty()) add(Screen.Home)
        }
    },
)

class MainActivity : ComponentActivity() {

    private val dialRequest = MutableStateFlow<String?>(null)
    private val permissionsGranted = MutableStateFlow(false)
    private val allCorePermissionsGranted = MutableStateFlow(false)
    private val isDefaultDialer = MutableStateFlow(false)
    private val canUseFullScreen = MutableStateFlow(true)
    // Hardware digit/CALL keys act as dial shortcuts only on Home/Dialpad —
    // from an editor they would destroy the back stack (and unsaved edits).
    private val hardwareDialSafe = MutableStateFlow(true)
    // Bumped per dial request so the dialpad's saved state can't shadow the
    // requested number when the navigation key happens to be identical.
    private val dialNonce = MutableStateFlow(0)
    private val refreshTick = MutableStateFlow(0)
    // An open contact editor holding unsaved input — a dial intent must not
    // wipe it by clearing the back stack.
    private val editorDirty = MutableStateFlow(false)
    private val simRequest = MutableStateFlow<SimRequest?>(null)
    // One-shot what's-new page after an app update (never on fresh install).
    private val showWhatsNew = MutableStateFlow(false)
    private var appVersionCode = 0
    private var appVersionName = ""

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var roleLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AccentStore.init(this)
        Haptics.init(this)
        FontStore.init(this)
        com.fancyshark.wpdialer.data.AppPrefs.init(this)
        Metro.light = com.fancyshark.wpdialer.data.AppPrefs.light.value

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            updatePermissionState()
        }
        roleLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { refreshDefaultState() }

        updatePermissionState()
        refreshDefaultState()
        // Existing installs predate the wizard — if the role is already held
        // AND the wizard has never been shown, this phone was set up before
        // the wizard existed. (A user killed mid-wizard after granting the
        // role must resume it instead — the remaining steps still matter.)
        if (!com.fancyshark.wpdialer.data.AppPrefs.setupDone.value &&
            !com.fancyshark.wpdialer.data.AppPrefs.wizardSeen &&
            isDefaultDialer.value
        ) {
            com.fancyshark.wpdialer.data.AppPrefs.setSetupDone(this, true)
        }
        // What's-new bookkeeping (after grandfathering, which decides whether
        // this is an update or a fresh install). Fresh installs get the
        // wizard as their intro — mark the page seen silently; anyone whose
        // stored version trails the running one sees it once.
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            appVersionCode = info.longVersionCode.toInt()
            appVersionName = info.versionName ?: ""
        }
        run {
            val p = getSharedPreferences("wp", MODE_PRIVATE)
            val seen = p.getInt("whats_new_seen", 0)
            if (seen == 0 && !com.fancyshark.wpdialer.data.AppPrefs.setupDone.value) {
                p.edit().putInt("whats_new_seen", appVersionCode).apply()
            } else if (seen < appVersionCode) {
                showWhatsNew.value = true
            }
        }
        // Only on a fresh launch — recreation (locale change) redelivers the
        // original intent, which must not re-fire a stale dial request.
        if (savedInstanceState == null) handleIntent(intent)
        setContent { WpApp() }

        // First run goes through the setup wizard instead of ad-hoc dialogs.
        // Users who skipped the role there get a calm home banner, never a
        // repeated system dialog (nag-loops are a Play review red flag).
        // Cold starts only — recreation (language/theme switch) must not
        // re-fire the request over whatever the user was doing.
        if (savedInstanceState == null &&
            com.fancyshark.wpdialer.data.AppPrefs.setupDone.value &&
            !permissionsGranted.value
        ) {
            permissionLauncher.launch(corePermissions())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // cancelMissedCallsNotification: default-dialer privilege, gated on
    // isDefaultDialer and wrapped in runCatching.
    @android.annotation.SuppressLint("MissingPermission")
    override fun onResume() {
        super.onResume()
        updatePermissionState()
        refreshDefaultState()
        refreshTick.value += 1
        // Consumes tips that completed while the app was closed (killed
        // mid-flow, pending payment settling later) — unconsumed purchases
        // are auto-refunded by Play after 3 days. No-op once connected.
        com.fancyshark.wpdialer.data.Tips.init(applicationContext)
        // Opening the app counts as seeing the call log (home IS history):
        // clear our missed-call notifications and reset Telecom's unread
        // counter so its restore broadcast doesn't re-post them later.
        if (isDefaultDialer.value) {
            runCatching {
                getSystemService(TelecomManager::class.java)?.cancelMissedCallsNotification()
            }
            com.fancyshark.wpdialer.call.MissedCalls.cancelAll(this)
        }
        // WP live-tile behavior: opening the phone app clears the tile's
        // missed count. NOT role-gated — home shows history either way, and
        // a role-gated tile would freeze stale when the user switches
        // default dialers. Detached scope: a quick resume/pause must not
        // cancel the provider write mid-flight (needs only WRITE_CALL_LOG;
        // no-ops pre-wizard).
        val app = applicationContext
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            Repo.markMissedSeen(app)
            com.fancyshark.wpdialer.widget.TileWidget.updateAll(app)
        }
        // Reopening the app during a call goes back to the call screen,
        // unless the user backed out of it on purpose.
        if (CallManager.call.value != null &&
            CallManager.state.value != android.telecom.Call.STATE_DISCONNECTED &&
            !CallManager.userDismissedUi
        ) {
            startActivity(Intent(this, com.fancyshark.wpdialer.call.InCallActivity::class.java))
        }
    }

    // Hardware keypad support (flip/keypad phones): digits open or type into
    // the dialpad, the call key opens it or dials the entered number. Only
    // reached when no focused field consumed the key.
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // Dial shortcuts only apply on Home/Dialpad — from other screens
        // (e.g. mid-edit in the contact editor) a stray CALL key would
        // destroy the back stack and any unsaved input.
        if (!hardwareDialSafe.value) return super.onKeyDown(keyCode, event)
        val digit = when (keyCode) {
            in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 ->
                ('0' + (keyCode - android.view.KeyEvent.KEYCODE_0)).toString()
            android.view.KeyEvent.KEYCODE_STAR -> "*"
            android.view.KeyEvent.KEYCODE_POUND -> "#"
            else -> null
        }
        if (digit != null) {
            if (DialpadBus.open) DialpadBus.events.tryEmit(digit) else dialRequest.value = digit
            return true
        }
        if (DialpadBus.open && keyCode == android.view.KeyEvent.KEYCODE_DEL) {
            DialpadBus.events.tryEmit("del")
            return true
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_CALL) {
            if (DialpadBus.open) DialpadBus.events.tryEmit("call") else dialRequest.value = ""
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_DIAL, Intent.ACTION_VIEW ->
                dialRequest.value = intent.data?.schemeSpecificPart ?: ""
            // Headset / hardware call button.
            Intent.ACTION_CALL_BUTTON -> dialRequest.value = ""
        }
    }

    private fun corePermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }

    private fun updatePermissionState() {
        permissionsGranted.value = listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
        ).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        // The wizard's permission step must not report success while e.g.
        // CALL_PHONE or notifications are still denied.
        allCorePermissionsGranted.value = corePermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        // canUseFullScreenIntent is API 34+; on Android 13 the permission is
        // a normal install-time grant, so it's always "on" (wizard step and
        // its 34+ settings deep link auto-skip).
        canUseFullScreen.value = android.os.Build.VERSION.SDK_INT < 34 ||
            getSystemService(android.app.NotificationManager::class.java)
                ?.canUseFullScreenIntent() != false
    }

    private fun refreshDefaultState() {
        val telecom = getSystemService(TelecomManager::class.java)
        isDefaultDialer.value = telecom?.defaultDialerPackage == packageName
    }

    private fun requestDefaultDialer() {
        val roleManager = getSystemService(RoleManager::class.java) ?: return
        if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
            !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
        ) {
            roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }
    }

    /** [onPlaced] runs only once the call really went out. */
    // Emergency calls deliberately skip the CALL_PHONE gate (a failure
    // falls back to the permission request); everything else checks it.
    @android.annotation.SuppressLint("MissingPermission")
    private fun placeCall(number: String, onPlaced: () -> Unit = {}) {
        // Emergency numbers go first — before the permission gate (a revoked
        // phone permission must never turn 112 into a dialog) and bypassing
        // SIM preferences: telecom routes them itself regardless of account.
        val emergency = runCatching {
            getSystemService(android.telephony.TelephonyManager::class.java)
                ?.isEmergencyNumber(number) == true
        }.getOrDefault(false)
        if (emergency) {
            val placed = runCatching {
                getSystemService(TelecomManager::class.java)!!
                    .placeCall(Uri.fromParts("tel", number, null), Bundle())
            }.isSuccess
            if (placed) onPlaced() else permissionLauncher.launch(corePermissions())
            return
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(corePermissions())
            return
        }
        val sims = Sims.options(this)
        if (sims.size <= 1) {
            if (placeCallWith(number, null)) onPlaced()
            return
        }
        lifecycleScope.launch {
            val contactId = Repo.contactIdFor(this@MainActivity, number)
            val contactPref = SimPrefs.get(this@MainActivity, number)
                ?.let { flat -> sims.firstOrNull { it.flat == flat } }
            val globalPref = com.fancyshark.wpdialer.data.AppPrefs.globalSim.value
                ?.let { flat -> sims.firstOrNull { it.flat == flat } }
            val preferred = contactPref ?: globalPref
            if (preferred != null) {
                if (placeCallWith(number, preferred.handle)) onPlaced()
            } else {
                simRequest.value = SimRequest(number, contactId, onPlaced)
            }
        }
    }

    /** True when the call was handed to Telecom (or the ACTION_CALL fallback). */
    // Only reached after placeCall's CALL_PHONE check; wrapped anyway.
    @android.annotation.SuppressLint("MissingPermission")
    private fun placeCallWith(number: String, handle: PhoneAccountHandle?): Boolean {
        val uri = Uri.fromParts("tel", number, null)
        return runCatching {
            val telecom = getSystemService(TelecomManager::class.java)
                ?: throw IllegalStateException("no telecom")
            val extras = Bundle()
            if (handle != null) {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
            telecom.placeCall(uri, extras)
        }.recoverCatching {
            startActivity(Intent(Intent.ACTION_CALL, uri))
        }.isSuccess
    }

    // Failure (no CALL_PHONE) falls back to placeCallWith, wrapped.
    @android.annotation.SuppressLint("MissingPermission")
    private fun callVoicemail() {
        val number = runCatching {
            getSystemService(android.telephony.TelephonyManager::class.java)?.voiceMailNumber
        }.getOrNull()
        if (!number.isNullOrBlank()) {
            // voicemail: lets Telecom resolve the box of the right SIM (and
            // ask via our in-call SIM chooser on "ask every time" devices) —
            // a global SIM preference must not route it over the other SIM.
            runCatching {
                getSystemService(TelecomManager::class.java)!!
                    .placeCall(Uri.fromParts("voicemail", "", null), Bundle())
            }.onFailure { placeCallWith(number, null) }
        } else {
            android.widget.Toast.makeText(
                this, getString(R.string.main_toast_no_voicemail), android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun sendText(number: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
        }
    }

    /** WP-style accent strip shown while a call is in progress. */
    @Composable
    private fun ReturnToCallBanner(accent: Color) {
        val call by CallManager.call.collectAsState()
        val callState by CallManager.state.collectAsState()
        if (call == null || callState == android.telecom.Call.STATE_DISCONNECTED) return
        Box(
            Modifier
                .fillMaxWidth()
                .background(accent)
                .clickable {
                    CallManager.userDismissedUi = false
                    startActivity(
                        Intent(this@MainActivity, com.fancyshark.wpdialer.call.InCallActivity::class.java),
                    )
                }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.main_return_to_call),
                color = Metro.onAccent(accent),
                fontSize = 14.sp,
            )
        }
    }

    @Composable
    private fun WpApp() {
        val accent by AccentStore.accent.collectAsState()
        val granted by permissionsGranted.collectAsState()
        val default by isDefaultDialer.collectAsState()
        val tick by refreshTick.collectAsState()

        // Saveable so activity recreation (e.g. an in-app language switch)
        // restores the navigation stack instead of resetting to Home.
        val backStack = androidx.compose.runtime.saveable.rememberSaveable(
            saver = ScreenStackSaver,
        ) { mutableStateListOf<Screen>(Screen.Home) }
        val uiScope = androidx.compose.runtime.rememberCoroutineScope()
        var confirmClearHistory by remember { mutableStateOf(false) }
        var confirmDeleteGroup by remember { mutableStateOf<List<HistoryItem>?>(null) }
        var confirmBlock by remember { mutableStateOf<String?>(null) }
        var confirmRemoveSpeedDial by remember { mutableStateOf<String?>(null) }
        // Set when Settings re-runs the wizard: back from its first page
        // then closes it instead of leaving the app.
        var wizardRerun by androidx.compose.runtime.saveable.rememberSaveable {
            mutableStateOf(false)
        }
        MetroSystemBars(window)

        // Keeps each stack entry's rememberSaveable state (typed dialpad
        // number, search query, scroll positions) alive while another
        // screen is pushed on top of it.
        val screenStateHolder =
            androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        val nonce by dialNonce.collectAsState()
        fun stateKeyOf(index: Int, screen: Screen): String =
            if (screen is Screen.Dialpad) "$index|$screen|$nonce" else "$index|$screen"
        // Popped entries drop their saved state — reopening Search (same
        // index + screen = same key) must start fresh, not show the old query.
        fun popTop() {
            val index = backStack.lastIndex
            val popped = backStack.removeAt(index)
            screenStateHolder.removeState(stateKeyOf(index, popped))
            if (popped is Screen.EditContact || popped is Screen.NewContact) {
                editorDirty.value = false
            }
        }
        // Dedupe: a double-tap must not push the same screen twice (back
        // would then appear to do nothing).
        fun push(screen: Screen) {
            if (backStack.last() != screen) backStack.add(screen)
        }
        fun popAndRefresh() {
            if (backStack.size > 1) popTop()
            refreshTick.value += 1
        }
        BackHandler(enabled = backStack.size > 1) {
            // Refresh on plain back too — e.g. pin/unpin on a profile must
            // show up on the speed dial page immediately.
            popAndRefresh()
        }

        val contacts by produceState(emptyList<ContactItem>(), granted, tick) {
            if (granted) value = Repo.loadContacts(this@MainActivity)
        }
        val history by produceState(emptyList<HistoryItem>(), granted, tick) {
            if (granted) value = Repo.loadHistory(this@MainActivity)
        }

        val setupDone by com.fancyshark.wpdialer.data.AppPrefs.setupDone.collectAsState()
        val whatsNewVisible by showWhatsNew.collectAsState()
        // The wizard / what's-new page cover the whole app.
        val fullPageOverlay = !setupDone || whatsNewVisible
        val pendingDial by dialRequest.collectAsState()
        LaunchedEffect(pendingDial, fullPageOverlay) {
            val number = pendingDial ?: return@LaunchedEffect
            // Held until the full-page overlay is gone — a dialpad opened
            // hidden beneath it would just be a surprise later.
            if (fullPageOverlay) return@LaunchedEffect
            // An editor with unsaved input stays on the stack; the dialpad
            // opens on top and back returns to the edits.
            val keepEditor = editorDirty.value &&
                backStack.any { it is Screen.EditContact || it is Screen.NewContact }
            if (!keepEditor) while (backStack.size > 1) popTop()
            // Fresh state-holder key: otherwise a Dialpad entry equal to
            // the previous one restores the old typed number instead of
            // this request's.
            dialNonce.value += 1
            push(Screen.Dialpad(number))
            dialRequest.value = null
        }
        val simReq by simRequest.collectAsState()
        val overlayOpen = fullPageOverlay || simReq != null || confirmClearHistory ||
            confirmDeleteGroup != null || confirmBlock != null || confirmRemoveSpeedDial != null

        val selawik by FontStore.selawik.collectAsState()
        MaterialTheme(colorScheme = darkColorScheme(primary = accent.color)) {
            CompositionLocalProvider(
                LocalTextStyle provides TextStyle(
                    fontFamily = if (selawik) Selawik else FontFamily.Default,
                ),
                androidx.compose.foundation.LocalIndication provides
                    com.fancyshark.wpdialer.ui.MetroIndication,
            ) {
            // WP10M-style reachability: a swipe down on the app bar slides the
            // whole screen down so top content lands under the thumb, then
            // springs back after a timeout or a tap on the vacated gap.
            val reachEnabled by com.fancyshark.wpdialer.data.AppPrefs.reachGesture.collectAsState()
            val reach = remember { androidx.compose.animation.core.Animatable(0f) }
            var reachTimer by remember {
                mutableStateOf<kotlinx.coroutines.Job?>(null)
            }
            fun dismissReach() {
                reachTimer?.cancel()
                reachTimer = uiScope.launch {
                    reach.animateTo(0f, androidx.compose.animation.core.tween(180))
                }
            }
            fun triggerReach(targetPx: Float) {
                reachTimer?.cancel()
                reachTimer = uiScope.launch {
                    reach.animateTo(targetPx, androidx.compose.animation.core.tween(220))
                    kotlinx.coroutines.delay(5000)
                    reach.animateTo(0f, androidx.compose.animation.core.tween(220))
                }
            }
            LaunchedEffect(backStack.lastIndex) {
                if (reach.value > 0f) dismissReach()
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Metro.Background)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                val top = backStack.last()
                // Not under an overlay either: digits typed at the wizard or
                // a confirm must not land in (or open) a hidden dialpad.
                LaunchedEffect(top, overlayOpen) {
                    hardwareDialSafe.value =
                        (top is Screen.Home || top is Screen.Dialpad) && !overlayOpen
                }
                var contentHeightPx by remember { mutableStateOf(0) }
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        // sw600dp+ ignores the portrait lock under targetSdk
                        // 36 — keep the phone layout from stretching.
                        .widthIn(max = com.fancyshark.wpdialer.ui.MetroMaxContentWidth)
                        .fillMaxSize()
                        .hiddenFromAccessibility(overlayOpen)
                        .onSizeChanged { contentHeightPx = it.height }
                        .graphicsLayer { translationY = reach.value },
                ) {
                screenStateHolder.SaveableStateProvider(stateKeyOf(backStack.lastIndex, top)) {
                    when (top) {
                        Screen.Home -> Column(Modifier.fillMaxSize()) {
                            ReturnToCallBanner(accent.color)
                            // Passive nudge when another app holds the dialer
                            // role — a tap re-asks; it never auto-pops.
                            if (!default) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(accent.color)
                                        .clickable { requestDefaultDialer() }
                                        .padding(horizontal = 20.dp, vertical = 10.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.main_make_default_banner),
                                        color = Metro.onAccent(accent.color),
                                        fontSize = 14.sp,
                                    )
                                }
                            }
                            // WP-style history filter: session-scoped, toggled
                            // from the app-bar menu.
                            var missedOnly by androidx.compose.runtime.saveable
                                .rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
                            Pivot(
                                title = stringResource(R.string.main_pivot_title),
                                modifier = Modifier.weight(1f),
                                pages = listOf<Pair<String, @Composable () -> Unit>>(
                                    stringResource(R.string.main_page_history) to {
                                        HistoryPage(
                                            items = if (missedOnly) {
                                                history.filter {
                                                    it.type ==
                                                        android.provider.CallLog.Calls.MISSED_TYPE
                                                }
                                            } else {
                                                history
                                            },
                                            emptyText = if (missedOnly) {
                                                stringResource(R.string.home_history_empty_missed)
                                            } else {
                                                null
                                            },
                                            accent = accent.color,
                                            onCall = { placeCall(it) },
                                            onText = { sendText(it) },
                                            onSave = { push(Screen.NewContact(it)) },
                                            onProfile = { number ->
                                                uiScope.launch {
                                                    val id = Repo.contactIdFor(this@MainActivity, number)
                                                    if (id != null) push(Screen.Contact(id))
                                                    else push(Screen.NewContact(number))
                                                }
                                            },
                                            onDelete = { group -> confirmDeleteGroup = group },
                                            onBlock = { number -> confirmBlock = number },
                                            onDetails = { item ->
                                                push(Screen.CallDetails(item.number, item.name))
                                            },
                                            onAddSpeedDial = { number ->
                                                com.fancyshark.wpdialer.data.AppPrefs
                                                    .addSpeedDial(this@MainActivity, number)
                                            },
                                        )
                                    },
                                    stringResource(R.string.main_page_speed_dial) to {
                                        SpeedDialPage(
                                            contacts = contacts,
                                            accent = accent.color,
                                            onOpen = { push(Screen.Contact(it.id)) },
                                            onCallNumber = { placeCall(it) },
                                            onRemoveNumber = { confirmRemoveSpeedDial = it },
                                        )
                                    },
                                    stringResource(R.string.main_page_people) to {
                                        PeoplePage(contacts, accent.color) {
                                            push(Screen.Contact(it.id))
                                        }
                                    },
                                ),
                            )
                            MetroAppBar(
                                actions = listOf(
                                    AppBarAction(Icons.Filled.Voicemail, stringResource(R.string.main_action_voicemail)) {
                                        callVoicemail()
                                    },
                                    AppBarAction(Icons.Filled.Dialpad, stringResource(R.string.main_action_keypad)) {
                                        push(Screen.Dialpad(""))
                                    },
                                    AppBarAction(Icons.Filled.Search, stringResource(R.string.main_action_search)) {
                                        push(Screen.Search)
                                    },
                                    AppBarAction(Icons.Filled.Add, stringResource(R.string.main_action_new)) {
                                        push(Screen.NewContact())
                                    },
                                ),
                                menu = listOf(
                                    stringResource(R.string.main_menu_settings) to { push(Screen.Settings) },
                                    stringResource(
                                        if (missedOnly) R.string.main_menu_all_calls
                                        else R.string.main_menu_missed_only,
                                    ) to { missedOnly = !missedOnly },
                                    stringResource(R.string.main_menu_delete_all_history) to { confirmClearHistory = true },
                                ),
                                onSwipeDown = if (reachEnabled) {
                                    { triggerReach(contentHeightPx * 0.42f) }
                                } else {
                                    null
                                },
                            )
                        }

                        is Screen.Dialpad -> DialpadScreen(
                            initial = top.initial,
                            accent = accent.color,
                            onCall = { number, onPlaced -> placeCall(number, onPlaced) },
                            onSave = { push(Screen.NewContact(it)) },
                            onVoicemail = { callVoicemail() },
                        )

                        is Screen.Contact -> ContactDetailScreen(
                            contactId = top.id,
                            accent = accent.color,
                            refreshKey = tick,
                            history = history,
                            onCall = { placeCall(it) },
                            onText = { sendText(it) },
                            onEdit = { push(Screen.EditContact(top.id)) },
                            onDeleted = { popAndRefresh() },
                            onReachDown = if (reachEnabled) {
                                { triggerReach(contentHeightPx * 0.42f) }
                            } else {
                                null
                            },
                        )

                        is Screen.EditContact -> EditContactScreen(
                            contactId = top.id,
                            accent = accent.color,
                            onDone = { popAndRefresh() },
                            onDirtyChange = { editorDirty.value = it },
                        )

                        is Screen.NewContact -> NewContactScreen(
                            accent = accent.color,
                            initialNumber = top.initialNumber,
                            onDone = { popAndRefresh() },
                            onDirtyChange = { editorDirty.value = it },
                        )

                        is Screen.CallDetails -> com.fancyshark.wpdialer.screens.CallDetailsScreen(
                            number = top.number,
                            name = top.name,
                            history = history,
                            accent = accent.color,
                            onCall = { placeCall(it) },
                            onText = { sendText(it) },
                        )

                        Screen.Search -> SearchScreen(contacts, accent.color) {
                            push(Screen.Contact(it.id))
                        }

                        Screen.Settings -> SettingsScreen(
                            accent = accent,
                            isDefaultDialer = default,
                            onRequestDefault = { requestDefaultDialer() },
                            onAbout = { push(Screen.About) },
                            onRerunSetup = {
                                wizardRerun = true
                                com.fancyshark.wpdialer.data.AppPrefs
                                    .setSetupDone(this@MainActivity, false)
                            },
                        )

                        Screen.About -> com.fancyshark.wpdialer.screens.AboutScreen(accent)
                    }
                }
                }

                // Tapping the gap the content vacated snaps it back up.
                if (reach.value > 0.5f) {
                    val density = LocalDensity.current
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(with(density) { reach.value.toDp() })
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { dismissReach() },
                    )
                }

                confirmDeleteGroup?.let { group ->
                    val item = group.first()
                    MetroConfirm(
                        title = if (group.size > 1) {
                            stringResource(R.string.main_confirm_delete_calls, group.size)
                        } else {
                            stringResource(R.string.main_confirm_delete_call)
                        },
                        message = item.name?.takeIf { it.isNotBlank() }
                            ?: Repo.pretty(this@MainActivity, item.number),
                        confirmLabel = stringResource(R.string.main_button_delete),
                        cancelLabel = stringResource(R.string.main_button_cancel),
                        onConfirm = {
                            confirmDeleteGroup = null
                            lifecycleScope.launch {
                                Repo.deleteHistoryItems(this@MainActivity, group.map { it.id })
                                refreshTick.value += 1
                            }
                        },
                        onCancel = { confirmDeleteGroup = null },
                    )
                }

                if (confirmClearHistory) {
                    MetroConfirm(
                        title = stringResource(R.string.main_confirm_delete_all_history),
                        message = stringResource(R.string.main_confirm_cant_undo),
                        confirmLabel = stringResource(R.string.main_button_delete),
                        cancelLabel = stringResource(R.string.main_button_cancel),
                        onConfirm = {
                            confirmClearHistory = false
                            lifecycleScope.launch {
                                Repo.clearHistory(this@MainActivity)
                                refreshTick.value += 1
                            }
                        },
                        onCancel = { confirmClearHistory = false },
                    )
                }

                confirmBlock?.let { number ->
                    MetroConfirm(
                        title = stringResource(R.string.main_confirm_block),
                        message = Repo.pretty(this@MainActivity, number),
                        confirmLabel = stringResource(R.string.main_button_block),
                        cancelLabel = stringResource(R.string.main_button_cancel),
                        onConfirm = {
                            confirmBlock = null
                            lifecycleScope.launch {
                                Repo.blockNumber(this@MainActivity, number)
                            }
                        },
                        onCancel = { confirmBlock = null },
                    )
                }

                confirmRemoveSpeedDial?.let { number ->
                    MetroConfirm(
                        title = stringResource(R.string.home_confirm_remove_speed_dial),
                        message = Repo.pretty(this@MainActivity, number),
                        confirmLabel = stringResource(R.string.ui_remove),
                        cancelLabel = stringResource(R.string.main_button_cancel),
                        onConfirm = {
                            confirmRemoveSpeedDial = null
                            com.fancyshark.wpdialer.data.AppPrefs
                                .removeSpeedDial(this@MainActivity, number)
                        },
                        onCancel = { confirmRemoveSpeedDial = null },
                    )
                }

                simReq?.let { req ->
                    SimPickerOverlay(
                        request = req,
                        accent = accent.color,
                        onPick = { option, remember ->
                            if (remember && req.contactId != null) {
                                SimPrefs.set(this@MainActivity, req.number, option.flat)
                            }
                            simRequest.value = null
                            if (placeCallWith(req.number, option.handle)) req.onPlaced()
                        },
                        // Cancelling never consumes the dialpad's number.
                        onCancel = { simRequest.value = null },
                    )
                }

                // First-run setup covers the whole app until finished.
                if (!setupDone) {
                    val fsi by canUseFullScreen.collectAsState()
                    val allGranted by allCorePermissionsGranted.collectAsState()
                    com.fancyshark.wpdialer.screens.SetupWizardScreen(
                        accent = accent.color,
                        isDefaultDialer = default,
                        permissionsGranted = allGranted,
                        canUseFullScreen = fsi,
                        onRequestRole = { requestDefaultDialer() },
                        onRequestPermissions = { permissionLauncher.launch(corePermissions()) },
                        onOpenBannerSetting = {
                            // Unreachable on Android 13 (the wizard step
                            // auto-skips) — the FSI settings page is 34+.
                            if (android.os.Build.VERSION.SDK_INT >= 34) runCatching {
                                startActivity(
                                    Intent(
                                        android.provider.Settings
                                            .ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                        Uri.parse("package:$packageName"),
                                    ),
                                )
                            }
                        },
                        onFinish = {
                            wizardRerun = false
                            com.fancyshark.wpdialer.data.AppPrefs
                                .setSetupDone(this@MainActivity, true)
                            refreshTick.value += 1
                        },
                        onExit = {
                            if (wizardRerun) {
                                // Re-run from Settings: back closes it again.
                                wizardRerun = false
                                com.fancyshark.wpdialer.data.AppPrefs
                                    .setSetupDone(this@MainActivity, true)
                            } else {
                                // First run: leave the app, as back would
                                // from any root screen. It resumes next time.
                                finish()
                            }
                        },
                    )
                }

                // First open after an update: one-shot what's-new page.
                // Fresh installs never see it (onCreate marks it seen when
                // the wizard is still pending), and the wizard renders on
                // top if both would somehow show.
                if (setupDone && whatsNewVisible) {
                    com.fancyshark.wpdialer.screens.WhatsNewScreen(
                        accent = accent.color,
                        versionName = appVersionName,
                    ) {
                        getSharedPreferences("wp", MODE_PRIVATE).edit()
                            .putInt("whats_new_seen", appVersionCode).apply()
                        showWhatsNew.value = false
                    }
                }
            }
            }
        }
    }

    @Composable
    private fun SimPickerOverlay(
        request: SimRequest,
        accent: Color,
        onPick: (SimOption, Boolean) -> Unit,
        onCancel: () -> Unit,
    ) {
        val options = remember { Sims.options(this) }
        var rememberChoice by remember { mutableStateOf(false) }
        com.fancyshark.wpdialer.ui.MetroDialog(onDismiss = onCancel) {
            Text(
                stringResource(R.string.main_choose_sim_title),
                color = Metro.Foreground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Text(
                // pretty() wraps the number in LRI/PDI for RTL locales.
                Repo.pretty(this@MainActivity, request.number),
                color = Metro.Subtle,
                fontSize = 17.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            options.forEach { option ->
                MetroButton(
                    option.label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                ) { onPick(option, rememberChoice) }
            }
            if (request.contactId != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .toggleable(
                            value = rememberChoice,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = androidx.compose.ui.semantics.Role.Checkbox,
                        ) { rememberChoice = it },
                ) {
                    Box(
                        Modifier
                            .size(24.dp)
                            .border(3.dp, Metro.Foreground)
                            .background(if (rememberChoice) accent else Color.Transparent),
                    )
                    Text(
                        stringResource(R.string.main_choose_sim_remember),
                        color = Metro.Foreground,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
    }
}
