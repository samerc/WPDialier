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

/** A call waiting on the user to choose a SIM. */
data class SimRequest(val number: String, val contactId: Long?)

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

    override fun onResume() {
        super.onResume()
        updatePermissionState()
        refreshDefaultState()
        refreshTick.value += 1
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

    private fun placeCall(number: String) {
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(corePermissions())
            return
        }
        // Emergency numbers bypass SIM preferences and the SIM chooser —
        // telecom routes them itself regardless of phone account.
        val emergency = runCatching {
            getSystemService(android.telephony.TelephonyManager::class.java)
                ?.isEmergencyNumber(number) == true
        }.getOrDefault(false)
        if (emergency) {
            placeCallWith(number, null)
            return
        }
        val sims = Sims.options(this)
        if (sims.size <= 1) {
            placeCallWith(number, null)
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
                placeCallWith(number, preferred.handle)
            } else {
                simRequest.value = SimRequest(number, contactId)
            }
        }
    }

    private fun placeCallWith(number: String, handle: PhoneAccountHandle?) {
        val uri = Uri.fromParts("tel", number, null)
        runCatching {
            val telecom = getSystemService(TelecomManager::class.java)
                ?: throw IllegalStateException("no telecom")
            val extras = Bundle()
            if (handle != null) {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
            telecom.placeCall(uri, extras)
        }.onFailure {
            runCatching { startActivity(Intent(Intent.ACTION_CALL, uri)) }
        }
    }

    private fun callVoicemail() {
        val number = runCatching {
            getSystemService(android.telephony.TelephonyManager::class.java)?.voiceMailNumber
        }.getOrNull()
        if (!number.isNullOrBlank()) {
            // The voicemail number belongs to the default voice subscription —
            // don't let a global SIM preference route it over the other SIM.
            placeCallWith(number, null)
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
            Text(stringResource(R.string.main_return_to_call), color = Color.White, fontSize = 14.sp)
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
        // Dedupe: a double-tap must not push the same screen twice (back
        // would then appear to do nothing).
        fun push(screen: Screen) {
            if (backStack.last() != screen) backStack.add(screen)
        }
        fun popAndRefresh() {
            if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
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

        val pendingDial by dialRequest.collectAsState()
        LaunchedEffect(pendingDial) {
            pendingDial?.let { number ->
                while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
                // Fresh state-holder key: otherwise a Dialpad entry equal to
                // the previous one restores the old typed number instead of
                // this request's.
                dialNonce.value += 1
                push(Screen.Dialpad(number))
                dialRequest.value = null
            }
        }

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
                LaunchedEffect(top) {
                    hardwareDialSafe.value = top is Screen.Home || top is Screen.Dialpad
                }
                var contentHeightPx by remember { mutableStateOf(0) }
                Box(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { contentHeightPx = it.height }
                        .graphicsLayer { translationY = reach.value },
                ) {
                // Keeps each stack entry's rememberSaveable state (typed
                // dialpad number, search query, scroll positions) alive while
                // another screen is pushed on top of it.
                val screenStateHolder =
                    androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                val nonce by dialNonce.collectAsState()
                val stateKey =
                    if (top is Screen.Dialpad) "${backStack.lastIndex}|$top|$nonce"
                    else "${backStack.lastIndex}|$top"
                screenStateHolder.SaveableStateProvider(stateKey) {
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
                                        color = Color.White,
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
                                            onRemoveNumber = {
                                                com.fancyshark.wpdialer.data.AppPrefs
                                                    .removeSpeedDial(this@MainActivity, it)
                                            },
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
                            onCall = { placeCall(it) },
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
                        )

                        is Screen.NewContact -> NewContactScreen(
                            accent = accent.color,
                            initialNumber = top.initialNumber,
                            onDone = { popAndRefresh() },
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
                    BackHandler { confirmDeleteGroup = null }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Metro.Background.copy(alpha = 0.96f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { confirmDeleteGroup = null },
                    ) {
                        Column(
                            Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 32.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { /* consume so taps inside don't dismiss */ },
                        ) {
                            Text(
                                if (group.size > 1) {
                                    stringResource(R.string.main_confirm_delete_calls, group.size)
                                } else {
                                    stringResource(R.string.main_confirm_delete_call)
                                },
                                color = Metro.Foreground,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Light,
                            )
                            Text(
                                item.name?.takeIf { it.isNotBlank() }
                                    ?: Repo.pretty(this@MainActivity, item.number),
                                color = Metro.Subtle,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Light,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                            Row(
                                horizontalArrangement = androidx.compose.foundation.layout
                                    .Arrangement.spacedBy(10.dp),
                            ) {
                                MetroButton(
                                    stringResource(R.string.main_button_delete),
                                    fill = Metro.Red,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    confirmDeleteGroup = null
                                    lifecycleScope.launch {
                                        Repo.deleteHistoryItems(this@MainActivity, group.map { it.id })
                                        refreshTick.value += 1
                                    }
                                }
                                MetroButton(stringResource(R.string.main_button_cancel), modifier = Modifier.weight(1f)) {
                                    confirmDeleteGroup = null
                                }
                            }
                        }
                    }
                }

                if (confirmClearHistory) {
                    BackHandler { confirmClearHistory = false }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Metro.Background.copy(alpha = 0.96f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { confirmClearHistory = false },
                    ) {
                        Column(
                            Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 32.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { /* consume so taps inside don't dismiss */ },
                        ) {
                            Text(
                                stringResource(R.string.main_confirm_delete_all_history),
                                color = Metro.Foreground,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Light,
                            )
                            Text(
                                stringResource(R.string.main_confirm_cant_undo),
                                color = Metro.Subtle,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Light,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                            Row(
                                horizontalArrangement = androidx.compose.foundation.layout
                                    .Arrangement.spacedBy(10.dp),
                            ) {
                                MetroButton(
                                    stringResource(R.string.main_button_delete),
                                    fill = Metro.Red,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    confirmClearHistory = false
                                    lifecycleScope.launch {
                                        Repo.clearHistory(this@MainActivity)
                                        refreshTick.value += 1
                                    }
                                }
                                MetroButton(stringResource(R.string.main_button_cancel), modifier = Modifier.weight(1f)) {
                                    confirmClearHistory = false
                                }
                            }
                        }
                    }
                }

                confirmBlock?.let { number ->
                    BackHandler { confirmBlock = null }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Metro.Background.copy(alpha = 0.96f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { confirmBlock = null },
                    ) {
                        Column(
                            Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 32.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { /* consume so taps inside don't dismiss */ },
                        ) {
                            Text(
                                stringResource(R.string.main_confirm_block),
                                color = Metro.Foreground,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Light,
                            )
                            Text(
                                Repo.pretty(this@MainActivity, number),
                                color = Metro.Subtle,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Light,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                            Row(
                                horizontalArrangement = androidx.compose.foundation.layout
                                    .Arrangement.spacedBy(10.dp),
                            ) {
                                MetroButton(
                                    stringResource(R.string.main_button_block),
                                    fill = Metro.Red,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    confirmBlock = null
                                    lifecycleScope.launch {
                                        Repo.blockNumber(this@MainActivity, number)
                                    }
                                }
                                MetroButton(
                                    stringResource(R.string.main_button_cancel),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    confirmBlock = null
                                }
                            }
                        }
                    }
                }

                val simReq by simRequest.collectAsState()
                simReq?.let { req ->
                    SimPickerOverlay(
                        request = req,
                        accent = accent.color,
                        onPick = { option, remember ->
                            if (remember && req.contactId != null) {
                                SimPrefs.set(this@MainActivity, req.number, option.flat)
                            }
                            simRequest.value = null
                            placeCallWith(req.number, option.handle)
                        },
                        onCancel = { simRequest.value = null },
                    )
                }

                // First-run setup covers the whole app until finished.
                val setupDone by com.fancyshark.wpdialer.data.AppPrefs.setupDone.collectAsState()
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
                            com.fancyshark.wpdialer.data.AppPrefs
                                .setSetupDone(this@MainActivity, true)
                            refreshTick.value += 1
                        },
                    )
                }

                // First open after an update: one-shot what's-new page.
                // Fresh installs never see it (onCreate marks it seen when
                // the wizard is still pending), and the wizard renders on
                // top if both would somehow show.
                val whatsNewVisible by showWhatsNew.collectAsState()
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
        BackHandler { onCancel() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Metro.Background.copy(alpha = 0.96f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onCancel() },
        ) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* consume so taps inside don't dismiss */ },
            ) {
                Text(
                    stringResource(R.string.main_choose_sim_title),
                    color = Metro.Foreground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
                Text(
                    request.number,
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
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { rememberChoice = !rememberChoice },
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
}
