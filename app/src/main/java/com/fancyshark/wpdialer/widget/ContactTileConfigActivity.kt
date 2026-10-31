package com.fancyshark.wpdialer.widget

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.fancyshark.wpdialer.R
import com.fancyshark.wpdialer.data.ContactItem
import com.fancyshark.wpdialer.data.Repo
import com.fancyshark.wpdialer.ui.AccentStore
import com.fancyshark.wpdialer.ui.ContactTile
import com.fancyshark.wpdialer.ui.FontStore
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroButton
import com.fancyshark.wpdialer.ui.MetroMaxContentWidth
import com.fancyshark.wpdialer.ui.MetroSystemBars
import com.fancyshark.wpdialer.ui.MetroTextBox
import com.fancyshark.wpdialer.ui.Selawik
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Configure step for a contact tile placed from the launcher's widget
 * picker (and its "reconfigure" action on Android 12+): pick the contact
 * and what a tap does. A tile pinned from a profile arrives already bound
 * and finishes immediately.
 *
 * Exported because the launcher starts it; it only ever (re)binds a widget
 * id that belongs to our own [ContactTileWidget] provider.
 */
class ContactTileConfigActivity : ComponentActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val hasContacts = MutableStateFlow(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out cancels the placement (the launcher drops the tile).
        setResult(RESULT_CANCELED, resultIntent())
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID ||
            !ContactTileWidget.isOurs(this, widgetId)
        ) {
            finish()
            return
        }
        // Pinned from a profile: claim the pending pin (or the binding the
        // pin callback already wrote) instead of asking again.
        if (savedInstanceState == null) {
            val pending = ContactTileStore.takePendingPin(this)
            if (pending != null) {
                ContactTileStore.put(this, widgetId, pending)
                done()
                return
            }
            val existing = ContactTileStore.get(this, widgetId)
            if (existing != null &&
                System.currentTimeMillis() - existing.boundAt in 0..ContactTileStore.PIN_WINDOW_MS
            ) {
                done()
                return
            }
        }

        AccentStore.init(this)
        FontStore.init(this)
        com.fancyshark.wpdialer.data.AppPrefs.init(this)
        Metro.light = com.fancyshark.wpdialer.data.AppPrefs.light.value
        hasContacts.value = granted()
        val permission = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { hasContacts.value = granted() }

        val current = ContactTileStore.get(this, widgetId)
        setContent {
            val accent by AccentStore.accent.collectAsState()
            val selawik by FontStore.selawik.collectAsState()
            MetroSystemBars(window)
            CompositionLocalProvider(
                LocalTextStyle provides TextStyle(
                    fontFamily = if (selawik) Selawik else FontFamily.Default,
                ),
                androidx.compose.foundation.LocalIndication provides
                    com.fancyshark.wpdialer.ui.MetroIndication,
            ) {
                ConfigScreen(
                    accent = accent.color,
                    initialAction = current?.action ?: TileAction.PROFILE,
                    currentKey = current?.lookupKey,
                    onRequestPermission = { permission.launch(Manifest.permission.READ_CONTACTS) },
                    onPick = { contact, action -> bind(contact, action) },
                )
            }
        }
    }

    private fun granted() =
        checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun resultIntent() =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private fun bind(contact: ContactItem, action: TileAction) {
        lifecycleScope.launch {
            // Persist the lookup key, never the (unstable) aggregate id.
            val resolved = withContext(Dispatchers.IO) {
                ContactTiles.lookupKeyFor(this@ContactTileConfigActivity, contact.id)
            } ?: return@launch
            ContactTileStore.put(
                this@ContactTileConfigActivity, widgetId,
                // boundAt 0: a later reconfigure must show this screen again.
                TileBinding(resolved.first, resolved.second.ifEmpty { contact.name }, action),
            )
            done()
        }
    }

    private fun done() {
        ContactTileWidget.update(this, widgetId)
        setResult(RESULT_OK, resultIntent())
        finish()
    }

    @Composable
    private fun ConfigScreen(
        accent: Color,
        initialAction: TileAction,
        currentKey: String?,
        onRequestPermission: () -> Unit,
        onPick: (ContactItem, TileAction) -> Unit,
    ) {
        val allowed by hasContacts.collectAsState()
        var action by remember { mutableStateOf(initialAction) }
        var query by remember { mutableStateOf("") }
        val contacts by produceState(emptyList<ContactItem>(), allowed) {
            if (allowed) value = Repo.loadContacts(this@ContactTileConfigActivity)
        }
        // The tile being reconfigured, to mark its current contact.
        val currentId by produceState<Long?>(null, allowed, currentKey) {
            value = currentKey?.takeIf { allowed }?.let { key ->
                withContext(Dispatchers.IO) {
                    ContactTiles.resolve(this@ContactTileConfigActivity, key)?.id
                }
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(Metro.Background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = MetroMaxContentWidth).fillMaxSize()) {
                Text(
                    stringResource(R.string.contact_tile_config_title),
                    color = Metro.Foreground,
                    fontSize = 15.sp,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 18.dp),
                )
                Text(
                    stringResource(R.string.contact_tile_config_header),
                    color = Metro.Foreground,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Light,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp),
                )
                Text(
                    stringResource(R.string.contact_tile_config_tap_label),
                    color = Metro.Subtle,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp),
                )
                Row(
                    Modifier.padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetroButton(
                        stringResource(R.string.contact_tile_action_profile),
                        fill = if (action == TileAction.PROFILE) accent else null,
                    ) { action = TileAction.PROFILE }
                    MetroButton(
                        stringResource(R.string.contact_tile_action_call),
                        fill = if (action == TileAction.CALL) accent else null,
                    ) { action = TileAction.CALL }
                }
                if (!allowed) {
                    Text(
                        stringResource(R.string.contact_tile_config_permission),
                        color = Metro.Foreground,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp),
                    )
                    MetroButton(
                        stringResource(R.string.contact_tile_config_allow),
                        fill = accent,
                        modifier = Modifier.padding(start = 20.dp, top = 16.dp),
                    ) { onRequestPermission() }
                    return@Column
                }
                MetroTextBox(
                    value = query,
                    onValueChange = { query = it },
                    accent = accent,
                    placeholder = stringResource(R.string.contact_tile_config_search),
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
                )
                val shown = remember(contacts, query) {
                    val q = query.trim()
                    if (q.isEmpty()) contacts
                    else contacts.filter { it.name.contains(q, ignoreCase = true) }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(shown, key = { it.id }) { contact ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(contact, action) }
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ContactTile(contact.name, contact.photoUri, accent, 56.dp)
                            Text(
                                contact.name,
                                color = if (contact.id == currentId) {
                                    Metro.textAccent(accent)
                                } else {
                                    Metro.Foreground
                                },
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Light,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
