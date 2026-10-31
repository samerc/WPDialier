package com.fancyshark.wpdialer.screens

import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fancyshark.wpdialer.R
import com.fancyshark.wpdialer.data.ContactAccount
import com.fancyshark.wpdialer.data.ContactEditor
import com.fancyshark.wpdialer.data.Countries
import com.fancyshark.wpdialer.data.EditPhone
import com.fancyshark.wpdialer.data.EditableContact
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroTextBox
import com.fancyshark.wpdialer.ui.MetroToast
import kotlinx.coroutines.launch

/**
 * New contact: the same form as edit (one raw contact in the chosen
 * account), plus the "save to" account and the country used to store
 * numbers in international form. [onDirtyChange] reports whether the form
 * holds unsaved input, so the host can keep the editor alive when a dial
 * intent arrives.
 */
@Composable
fun NewContactScreen(
    accent: Color,
    initialNumber: String = "",
    onDone: () -> Unit,
    onDirtyChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accentText = Metro.textAccent(accent)
    val accounts by produceState(listOf(ContactAccount(null, null))) {
        value = ContactEditor.listAccounts(context)
    }
    // Form state is saveable: the photo/map/location round trips can cost
    // the process, and the typed input must survive that.
    var selectedAccount by rememberSaveable(stateSaver = ContactAccountSaver) {
        mutableStateOf<ContactAccount?>(null)
    }
    val chosen = selectedAccount ?: accounts.first()

    val regions = remember { Countries.all() }
    var regionCode by rememberSaveable { mutableStateOf(Countries.defaultRegionCode(context)) }
    val region = regions.firstOrNull { it.code == regionCode }

    val formSaver = remember { editableContactSaver(context.applicationContext) }
    var formState by rememberSaveable(stateSaver = formSaver) { mutableStateOf<EditableContact?>(null) }
    // The untouched form: one empty raw contact holding the prefilled number.
    val base = EditableContact(
        -1L,
        listOf(
            ContactEditor.blankRaw(chosen).copy(
                phones = listOf(EditPhone(null, initialNumber, "mobile", Phone.TYPE_MOBILE)),
            ),
        ),
    )
    // The raw contact always follows the chosen account.
    val form = (formState ?: base).let { f ->
        f.copy(raws = f.raws.map { it.copy(account = chosen) })
    }
    val dirty = !form.sameFormAs(base)
    LaunchedEffect(dirty) { onDirtyChange(dirty) }

    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var showAccountPicker by rememberSaveable { mutableStateOf(false) }
    var showCountryPicker by rememberSaveable { mutableStateOf(false) }
    val needNameOrNumber = stringResource(R.string.cform_need_name_or_number)

    fun update(transform: (EditableContact) -> EditableContact) {
        val current = (formState ?: base).let { f -> f.copy(raws = f.raws.map { it.copy(account = chosen) }) }
        formState = transform(current)
    }

    ContactFormScaffold(
        caption = stringResource(
            R.string.newcontact_title,
            accountKindLabel(chosen.kindLabel).uppercase(),
        ),
        accent = accent,
        form = form,
        update = { update(it) },
        dirty = dirty,
        saving = saving,
        saveFailedText = if (saveFailed) stringResource(R.string.newcontact_save_failed) else null,
        onSave = {
            val raw = form.raws.first()
            if (raw.name.isBlank && raw.phones.none { it.number.isNotBlank() }) {
                MetroToast.show(needNameOrNumber)
            } else {
                saving = true
                saveFailed = false
                scope.launch {
                    val prepared = raw.copy(
                        account = chosen,
                        phones = raw.phones.map {
                            it.copy(number = Countries.toInternational(it.number.trim(), regionCode))
                        },
                        emails = raw.emails.map { it.copy(address = it.address.trim()) },
                        note = raw.note.trim(),
                    )
                    val ok = ContactEditor.create(context, prepared)
                    if (ok) {
                        onDone()
                    } else {
                        saving = false
                        saveFailed = true
                    }
                }
            }
        },
        onDone = onDone,
        header = {
            FieldLabel(stringResource(R.string.newcontact_save_to), top = 18.dp)
            MetroSelector(
                primary = accountKindLabel(chosen.kindLabel),
                secondary = chosen.name,
                accent = accent,
            ) { showAccountPicker = true }
            FieldLabel(stringResource(R.string.newcontact_country))
            MetroSelector(
                primary = region?.label ?: regionCode,
                secondary = null,
                accent = accent,
            ) { showCountryPicker = true }
        },
        extraOverlayOpen = showAccountPicker || showCountryPicker,
        extraOverlays = {
            if (showAccountPicker) {
                FullScreenPicker(
                    title = stringResource(R.string.newcontact_save_to_caps),
                    onDismiss = { showAccountPicker = false },
                ) {
                    accounts.forEach { account ->
                        val selected = account == chosen
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (account != chosen) {
                                        // Labels belong to an account — drop the old account's.
                                        update { c ->
                                            c.copy(
                                                raws = c.raws.map {
                                                    it.copy(groupIds = emptySet(), newGroups = emptyList())
                                                },
                                            )
                                        }
                                    }
                                    selectedAccount = account
                                    showAccountPicker = false
                                }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(
                                accountKindLabel(account.kindLabel),
                                color = if (selected) accentText else Metro.Foreground,
                                style = Metro.Type.SubHeader,
                            )
                            if (!account.name.isNullOrBlank()) {
                                Text(
                                    account.name,
                                    color = Metro.Subtle,
                                    style = Metro.Type.Small,
                                    fontWeight = FontWeight.Light,
                                )
                            }
                        }
                    }
                }
            }

            if (showCountryPicker) {
                var query by rememberSaveable { mutableStateOf("") }
                FullScreenPicker(
                    title = stringResource(R.string.newcontact_country_caps),
                    onDismiss = { showCountryPicker = false },
                    header = {
                        MetroTextBox(
                            query,
                            { query = it },
                            accent,
                            placeholder = stringResource(R.string.newcontact_search),
                        )
                        Spacer(Modifier.height(10.dp))
                    },
                ) {
                    val filtered = regions.filter {
                        query.isBlank() || it.name.contains(query, ignoreCase = true)
                    }
                    LazyColumn {
                        items(filtered.size, key = { filtered[it].code }) { i ->
                            val r = filtered[i]
                            Text(
                                r.label,
                                color = if (r.code == regionCode) accentText else Metro.Foreground,
                                style = Metro.Type.Large,
                                fontWeight = FontWeight.Light,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        regionCode = r.code
                                        showCountryPicker = false
                                    }
                                    .padding(vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        },
    )
}
