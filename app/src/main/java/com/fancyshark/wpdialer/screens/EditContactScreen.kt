package com.fancyshark.wpdialer.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
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
import com.fancyshark.wpdialer.data.ContactEditor
import com.fancyshark.wpdialer.data.EditableContact
import com.fancyshark.wpdialer.data.SaveResult
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroToast
import kotlinx.coroutines.launch

/**
 * Edits every writable raw contact of [contactId] in one form; each field
 * saves back to its own account. [onDirtyChange] reports whether the form
 * holds unsaved edits, so the host can keep the editor alive when a dial
 * intent arrives.
 */
@Composable
fun EditContactScreen(
    contactId: Long,
    accent: Color,
    onDone: () -> Unit,
    onDirtyChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loadFailed by remember { mutableStateOf(false) }
    val original by produceState<EditableContact?>(null, contactId) {
        value = ContactEditor.loadEditable(context, contactId)
        if (value == null) loadFailed = true
    }
    // The working copy is saveable: the photo picker round trip can cost
    // the process, and the edits must survive it. Null = untouched so far.
    val formSaver = remember { editableContactSaver(context.applicationContext) }
    var editedState by rememberSaveable(contactId, stateSaver = formSaver) {
        mutableStateOf<EditableContact?>(null)
    }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val photoFailed = stringResource(R.string.cform_photo_failed)

    if (loadFailed) {
        // Deleted or unreadable contact: say so, then leave instead of
        // stranding the user on a blank page. The page stays composed for
        // the nav animation after a back press, so a [left] flag makes sure
        // only one of back / the timer ever leaves (never popping twice).
        var left by remember { mutableStateOf(false) }
        fun leave() {
            if (left) return
            left = true
            onDone()
        }
        BackHandler(enabled = !left) { leave() }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(1500)
            leave()
        }
        Text(
            stringResource(R.string.editcontact_load_failed),
            color = Metro.Subtle,
            style = Metro.Type.Large,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    val loaded = original ?: return
    val edited = editedState ?: loaded
    val dirty = editedState?.let { !it.sameFormAs(loaded) } ?: false
    LaunchedEffect(dirty) { onDirtyChange(dirty) }

    val caption = if (edited.raws.size == 1) {
        stringResource(
            R.string.editcontact_title_kind,
            accountKindLabel(edited.raws.first().account.kindLabel).uppercase(),
        )
    } else {
        stringResource(R.string.editcontact_title)
    }

    ContactFormScaffold(
        caption = caption,
        accent = accent,
        form = edited,
        // Reads the live state at call time — two edits landing before the
        // next recomposition must both stick.
        update = { transform -> editedState = transform(editedState ?: loaded) },
        dirty = dirty,
        saving = saving,
        saveFailedText = if (saveFailed) stringResource(R.string.editcontact_save_failed) else null,
        onSave = {
            saving = true
            saveFailed = false
            scope.launch {
                val result = ContactEditor.save(context, loaded, editedState ?: loaded)
                if (result != SaveResult.FAILED) {
                    // The other edits are in; only the photo batch failed.
                    if (result == SaveResult.PHOTO_FAILED) MetroToast.show(photoFailed)
                    onDone()
                } else {
                    saving = false
                    saveFailed = true
                }
            }
        },
        onDone = onDone,
    )
}
