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
import com.fancyshark.wpdialer.data.FieldKind
import com.fancyshark.wpdialer.data.SaveResult
import com.fancyshark.wpdialer.data.keepingOnly
import com.fancyshark.wpdialer.data.unsupportedFilled
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroConfirm
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
    // A "save to" switch waiting on the remove-unsupported-fields confirm.
    var pendingAccount by rememberSaveable(stateSaver = ContactAccountSaver) {
        mutableStateOf<ContactAccount?>(null)
    }
    val needNameOrNumber = stringResource(R.string.cform_need_name_or_number)
    val photoFailed = stringResource(R.string.cform_photo_failed)

    fun update(transform: (EditableContact) -> EditableContact) {
        val current = (formState ?: base).let { f -> f.copy(raws = f.raws.map { it.copy(account = chosen) }) }
        formState = transform(current)
    }

    /**
     * Moves the draft to [account]: labels belong to the old account, and
     * fields the new one can't store (SIM: no address, notes, ...) go.
     */
    fun switchAccount(account: ContactAccount) {
        if (account != chosen) {
            val kinds = ContactEditor.supportedKinds(account) - FieldKind.LABELS
            update { c -> c.copy(raws = c.raws.map { it.keepingOnly(kinds) }) }
        }
        selectedAccount = account
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
                    val result = ContactEditor.create(context, prepared)
                    if (result != SaveResult.FAILED) {
                        // The contact exists; only the photo batch failed.
                        if (result == SaveResult.PHOTO_FAILED) MetroToast.show(photoFailed)
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
        extraOverlayOpen = showAccountPicker || showCountryPicker || pendingAccount != null,
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
                                    val lost = if (account == chosen) emptyList()
                                    else form.raws.first().unsupportedFilled(ContactEditor.supportedKinds(account))
                                    // Removing typed input is destructive: confirm first.
                                    if (lost.isEmpty()) switchAccount(account) else pendingAccount = account
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

            pendingAccount?.let { next ->
                val lost = form.raws.first().unsupportedFilled(ContactEditor.supportedKinds(next))
                    .map { fieldKindName(it) }
                MetroConfirm(
                    title = stringResource(R.string.cform_unsupported_title),
                    message = stringResource(
                        R.string.cform_unsupported_message,
                        accountKindLabel(next.kindLabel),
                        lost.joinToString(", "),
                    ),
                    confirmLabel = stringResource(R.string.ui_remove),
                    cancelLabel = stringResource(R.string.editcontact_cancel),
                    onConfirm = {
                        pendingAccount = null
                        switchAccount(next)
                    },
                    // Cancel keeps the old account and every field.
                    onCancel = { pendingAccount = null },
                )
            }
        },
    )
}

/** The form's name for a field kind, as in its section headers. */
@Composable
private fun fieldKindName(kind: FieldKind): String = stringResource(
    when (kind) {
        FieldKind.PHONE -> R.string.cform_section_phone
        FieldKind.EMAIL -> R.string.cform_section_email
        FieldKind.ADDRESS -> R.string.cform_section_address
        FieldKind.DATE -> R.string.cform_section_dates
        FieldKind.WEBSITE -> R.string.cform_website
        FieldKind.RELATION -> R.string.cform_relationship
        FieldKind.SIP -> R.string.cform_internet_call
        FieldKind.IM -> R.string.cform_chat
        FieldKind.NICKNAME -> R.string.cform_nickname
        FieldKind.WORK -> R.string.cform_section_work
        FieldKind.NOTE -> R.string.cform_notes
        FieldKind.LABELS -> R.string.cform_labels
    },
)
