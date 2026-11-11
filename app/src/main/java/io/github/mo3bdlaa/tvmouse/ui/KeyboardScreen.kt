package io.github.mo3bdlaa.tvmouse.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.mo3bdlaa.tvmouse.Input
import io.github.mo3bdlaa.tvmouse.hid.Key
import io.github.mo3bdlaa.tvmouse.hid.KeyModifier

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardScreen(
    sharedText: String?,
    onSharedTextUsed: () -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var live by remember { mutableStateOf(TextFieldValue("")) }
    var block by rememberSaveable { mutableStateOf("") }
    var pressEnter by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            block = sharedText
            onSharedTextUsed()
        }
    }

    val reportSkipped: (Int) -> Unit = { skipped ->
        if (skipped > 0) {
            onMessage(
                "$skipped character(s) couldn't be typed. Bluetooth mode only sends English (US) " +
                    "keyboard characters. Wi-Fi mode types any language.",
            )
        }
    }

    fun typeOnTv(text: String) {
        if (!Input.isConnected) {
            onMessage("Not connected to a TV yet.")
            return
        }
        Input.typeText(text, reportSkipped)
        if (pressEnter) Input.pressKey(Key.ENTER)
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Live typing", style = MaterialTheme.typography.titleMedium)
        Text(
            "What you type here is typed on the TV right away. Delete works too.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = live,
            onValueChange = { new ->
                sendDiff(live.text, new.text, reportSkipped)
                live = new
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            placeholder = { Text("Tap here and type…") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
            ),
            trailingIcon = {
                IconButton(onClick = { live = TextFieldValue("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Clear box (does not delete on TV)")
                }
            },
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyChip("Enter") { Input.pressKey(Key.ENTER) }
            KeyChip("⌫ Backspace") { Input.pressKey(Key.BACKSPACE) }
            KeyChip("Del") { Input.pressKey(Key.DELETE) }
            KeyChip("Tab") { Input.pressKey(Key.TAB) }
            KeyChip("Esc") { Input.pressKey(Key.ESCAPE) }
            KeyChip("←") { Input.pressKey(Key.LEFT) }
            KeyChip("→") { Input.pressKey(Key.RIGHT) }
            KeyChip("↑") { Input.pressKey(Key.UP) }
            KeyChip("↓") { Input.pressKey(Key.DOWN) }
            KeyChip("Home") { Input.pressKey(Key.HOME) }
            KeyChip("End") { Input.pressKey(Key.END) }
            KeyChip("PgUp") { Input.pressKey(Key.PAGE_UP) }
            KeyChip("PgDn") { Input.pressKey(Key.PAGE_DOWN) }
        }

        Text("Shortcuts on the TV", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KeyChip("Select all") { Input.pressKey(Key.A, KeyModifier.CTRL) }
            KeyChip("Copy") { Input.pressKey(Key.C, KeyModifier.CTRL) }
            KeyChip("Cut") { Input.pressKey(Key.X, KeyModifier.CTRL) }
            KeyChip("Paste") { Input.pressKey(Key.V, KeyModifier.CTRL) }
            KeyChip("Undo") { Input.pressKey(Key.Z, KeyModifier.CTRL) }
        }

        HorizontalDivider()

        Text("Send text from the phone", style = MaterialTheme.typography.titleMedium)
        Text(
            "Write or paste text here, then send it. It is typed on the TV as if you typed it. " +
                "You can also share text to TV Mouse from any app.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = block,
            onValueChange = { block = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            placeholder = { Text("Text to send…") },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = pressEnter, onCheckedChange = { pressEnter = it })
            Text("Press Enter after sending")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { typeOnTv(block) }, enabled = block.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text("Type on TV")
            }
            OutlinedButton(onClick = { Input.cancelTyping() }) { Text("Stop") }
        }
        FilledTonalButton(
            onClick = {
                val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrEmpty()) onMessage("The phone clipboard is empty.") else typeOnTv(text)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.ContentPaste, contentDescription = null)
            Text("  Paste phone clipboard on TV")
        }
    }
}

@Composable
private fun KeyChip(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { Text(label) }
}

/** Sends the difference between the old and new text as backspaces plus typed characters. */
private fun sendDiff(old: String, new: String, onDone: (Int) -> Unit) {
    if (old == new) return
    var prefix = 0
    val max = minOf(old.length, new.length)
    while (prefix < max && old[prefix] == new[prefix]) prefix++
    Input.backspace(old.length - prefix)
    val added = new.substring(prefix)
    if (added.isNotEmpty()) Input.typeText(added, onDone)
}
