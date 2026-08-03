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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.fancyshark.wpdialer.data.ContactItem
import com.fancyshark.wpdialer.data.HistoryItem
import com.fancyshark.wpdialer.data.Repo
import com.fancyshark.wpdialer.data.SimOption
import com.fancyshark.wpdialer.data.SimPrefs
import com.fancyshark.wpdialer.data.Sims
import com.fancyshark.wpdialer.screens.ContactDetailScreen
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
    data object Search : Screen
    data object Settings : Screen
}

/** A call waiting on the user to choose a SIM. */
data class SimRequest(val number: String, val contactId: Long?)

class MainActivity : ComponentActivity() {

    private val dialRequest = MutableStateFlow<String?>(null)
    private val permissionsGranted = MutableStateFlow(false)
    private val isDefaultDialer = MutableStateFlow(false)
    private val refreshTick = MutableStateFlow(0)
    private val simRequest = MutableStateFlow<SimRequest?>(null)
    private var promptedForDefault = false

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
            maybePromptForDefault()
        }
        roleLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { refreshDefaultState() }

        updatePermissionState()
        refreshDefaultState()
        handleIntent(intent)
        setContent { WpApp() }

        if (!permissionsGranted.value) {
            permissionLauncher.launch(corePermissions())
        } else {
            maybePromptForDefault()
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
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_DIAL, Intent.ACTION_VIEW ->
                dialRequest.value = intent.data?.schemeSpecificPart ?: ""
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
    }

    private fun refreshDefaultState() {
        val telecom = getSystemService(TelecomManager::class.java)
        isDefaultDialer.value = telecom?.defaultDialerPackage == packageName
    }

    private fun maybePromptForDefault() {
        if (!promptedForDefault && !isDefaultDialer.value) {
            promptedForDefault = true
            requestDefaultDialer()
        }
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
        val sims = Sims.options(this)
        if (sims.size <= 1) {
            placeCallWith(number, null)
            return
        }
        lifecycleScope.launch {
            val contactId = Repo.contactIdFor(this@MainActivity, number)
            val contactPref = contactId
                ?.let { SimPrefs.get(this@MainActivity, it) }
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
        if (!number.isNullOrBlank()) placeCall(number)
    }

    private fun sendText(number: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
        }
    }

    @Composable
    private fun WpApp() {
        val accent by AccentStore.accent.collectAsState()
        val granted by permissionsGranted.collectAsState()
        val default by isDefaultDialer.collectAsState()
        val tick by refreshTick.collectAsState()

        val backStack = remember { mutableStateListOf<Screen>(Screen.Home) }
        val uiScope = androidx.compose.runtime.rememberCoroutineScope()
        var confirmClearHistory by remember { mutableStateOf(false) }
        var confirmDeleteItem by remember { mutableStateOf<HistoryItem?>(null) }
        fun push(screen: Screen) = backStack.add(screen)
        fun popAndRefresh() {
            if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
            refreshTick.value += 1
        }
        BackHandler(enabled = backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
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
            ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Metro.Background)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                val top = backStack.last()
                key(top) {
                    when (top) {
                        Screen.Home -> Column(Modifier.fillMaxSize()) {
                            Pivot(
                                title = "PHONE",
                                modifier = Modifier.weight(1f),
                                pages = listOf<Pair<String, @Composable () -> Unit>>(
                                    "history" to {
                                        HistoryPage(
                                            items = history,
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
                                            onDelete = { item -> confirmDeleteItem = item },
                                            onBlock = { number ->
                                                uiScope.launch {
                                                    Repo.blockNumber(this@MainActivity, number)
                                                }
                                            },
                                        )
                                    },
                                    "speed dial" to {
                                        SpeedDialPage(contacts, accent.color) {
                                            push(Screen.Contact(it.id))
                                        }
                                    },
                                    "people" to {
                                        PeoplePage(contacts, accent.color) {
                                            push(Screen.Contact(it.id))
                                        }
                                    },
                                ),
                            )
                            MetroAppBar(
                                actions = listOf(
                                    AppBarAction(Icons.Filled.Voicemail, "voicemail") {
                                        callVoicemail()
                                    },
                                    AppBarAction(Icons.Filled.Dialpad, "keypad") {
                                        push(Screen.Dialpad(""))
                                    },
                                    AppBarAction(Icons.Filled.Search, "search") {
                                        push(Screen.Search)
                                    },
                                    AppBarAction(Icons.Filled.Add, "new") {
                                        push(Screen.NewContact())
                                    },
                                ),
                                menu = listOf(
                                    "settings" to { push(Screen.Settings) },
                                    "delete all history" to { confirmClearHistory = true },
                                ),
                            )
                        }

                        is Screen.Dialpad -> DialpadScreen(
                            initial = top.initial,
                            accent = accent.color,
                            onCall = { placeCall(it) },
                            onSave = { push(Screen.NewContact(it)) },
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

                        Screen.Search -> SearchScreen(contacts, accent.color) {
                            push(Screen.Contact(it.id))
                        }

                        Screen.Settings -> SettingsScreen(
                            accent = accent,
                            isDefaultDialer = default,
                            onRequestDefault = { requestDefaultDialer() },
                        )
                    }
                }

                confirmDeleteItem?.let { item ->
                    BackHandler { confirmDeleteItem = null }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Metro.Background.copy(alpha = 0.96f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { confirmDeleteItem = null },
                    ) {
                        Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp)) {
                            Text(
                                "delete this call?",
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
                                    "delete",
                                    fill = Metro.Red,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    confirmDeleteItem = null
                                    lifecycleScope.launch {
                                        Repo.deleteHistoryItem(this@MainActivity, item.id)
                                        refreshTick.value += 1
                                    }
                                }
                                MetroButton("cancel", modifier = Modifier.weight(1f)) {
                                    confirmDeleteItem = null
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
                        Column(Modifier.align(Alignment.Center).padding(horizontal = 32.dp)) {
                            Text(
                                "delete all call history?",
                                color = Metro.Foreground,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Light,
                            )
                            Text(
                                "this can't be undone",
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
                                    "delete",
                                    fill = Metro.Red,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    confirmClearHistory = false
                                    lifecycleScope.launch {
                                        Repo.clearHistory(this@MainActivity)
                                        refreshTick.value += 1
                                    }
                                }
                                MetroButton("cancel", modifier = Modifier.weight(1f)) {
                                    confirmClearHistory = false
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
                                SimPrefs.set(this@MainActivity, req.contactId, option.flat)
                            }
                            simRequest.value = null
                            placeCallWith(req.number, option.handle)
                        },
                        onCancel = { simRequest.value = null },
                    )
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
                    .padding(horizontal = 32.dp),
            ) {
                Text(
                    "CHOOSE A SIM",
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
                            "always use for this contact",
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
