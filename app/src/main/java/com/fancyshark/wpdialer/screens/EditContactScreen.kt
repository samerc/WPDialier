package com.fancyshark.wpdialer.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fancyshark.wpdialer.data.ContactEditor
import com.fancyshark.wpdialer.data.EditPhone
import com.fancyshark.wpdialer.data.EditableContact
import com.fancyshark.wpdialer.ui.Metro
import com.fancyshark.wpdialer.ui.MetroButton
import com.fancyshark.wpdialer.ui.MetroTextBox
import kotlinx.coroutines.launch

@Composable
fun EditContactScreen(
    contactId: Long,
    accent: Color,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val original by produceState<EditableContact?>(null, contactId) {
        value = ContactEditor.loadEditable(context, contactId)
    }
    val loaded = original ?: return

    var edited by remember(loaded) { mutableStateOf(loaded) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        val title = if (edited.raws.size == 1) {
            "EDIT ${edited.raws.first().account.kindLabel.uppercase()} CONTACT"
        } else {
            "EDIT CONTACT"
        }
        Text(
            title,
            color = Metro.Foreground,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            edited.raws.forEachIndexed { rawIndex, raw ->
                Spacer(Modifier.height(14.dp))
                if (edited.raws.size > 1) {
                    Text(
                        "saved to ${raw.account.fullLabel}",
                        color = accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text("name", color = Metro.Subtle, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                MetroTextBox(
                    value = raw.name,
                    onValueChange = { newName ->
                        edited = edited.copy(
                            raws = edited.raws.mapIndexed { i, r ->
                                if (i == rawIndex) r.copy(name = newName) else r
                            },
                        )
                    },
                    accent = accent,
                    placeholder = "name",
                    keyboardOptions = KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                    ),
                )
                raw.phones.forEachIndexed { phoneIndex, phone ->
                    Spacer(Modifier.height(10.dp))
                    Text("phone (${phone.label})", color = Metro.Subtle, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MetroTextBox(
                            value = phone.number,
                            onValueChange = { newNumber ->
                                edited = edited.copy(
                                    raws = edited.raws.mapIndexed { i, r ->
                                        if (i != rawIndex) r
                                        else r.copy(
                                            phones = r.phones.mapIndexed { j, p ->
                                                if (j == phoneIndex) p.copy(number = newNumber) else p
                                            },
                                        )
                                    },
                                )
                            },
                            accent = accent,
                            placeholder = "number",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "✕",
                            color = Metro.Subtle,
                            fontSize = 20.sp,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) {
                                    edited = edited.copy(
                                        raws = edited.raws.mapIndexed { i, r ->
                                            if (i != rawIndex) r
                                            else r.copy(
                                                phones = r.phones.filterIndexed { j, _ -> j != phoneIndex },
                                            )
                                        },
                                    )
                                }
                                .padding(start = 14.dp, end = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "+ add phone",
                    color = accent,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            edited = edited.copy(
                                raws = edited.raws.mapIndexed { i, r ->
                                    if (i != rawIndex) r
                                    else r.copy(phones = r.phones + EditPhone(null, "", "mobile"))
                                },
                            )
                        }
                        .padding(vertical = 4.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text("Note", color = Metro.Subtle, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                MetroTextBox(
                    value = raw.note,
                    onValueChange = { newNote ->
                        edited = edited.copy(
                            raws = edited.raws.mapIndexed { i, r ->
                                if (i == rawIndex) r.copy(note = newNote) else r
                            },
                        )
                    },
                    accent = accent,
                    placeholder = "Note",
                    singleLine = false,
                    minLines = 2,
                )
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(20.dp))
        }
        if (saveFailed) {
            Text(
                "couldn't save the changes — try again",
                color = Metro.Red,
                fontSize = 14.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(vertical = 14.dp),
        ) {
            MetroButton(
                if (saving) "saving..." else "save",
                fill = accent,
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) {
                saving = true
                saveFailed = false
                scope.launch {
                    val ok = ContactEditor.save(context, loaded, edited)
                    if (ok) {
                        onDone()
                    } else {
                        saving = false
                        saveFailed = true
                    }
                }
            }
            MetroButton("cancel", modifier = Modifier.weight(1f)) { onDone() }
        }
    }
}
