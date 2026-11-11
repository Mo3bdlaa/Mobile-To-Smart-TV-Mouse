package io.github.mo3bdlaa.tvmouse.tv

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mo3bdlaa.tvmouse.BuildConfig
import io.github.mo3bdlaa.tvmouse.ui.PendingCrashDialog
import io.github.mo3bdlaa.tvmouse.ui.UpdateDialog
import io.github.mo3bdlaa.tvmouse.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the app shows when it's opened on the TV: setup steps and paired phones. */
@Composable
fun TvReceiverScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val running by ReceiverService.running.collectAsStateWithLifecycle()
    var phones by remember { mutableStateOf(PairedPhones.all(context)) }
    var address by remember { mutableStateOf(PairedPhones.localAddress(context)) }
    var update by remember { mutableStateOf<Updater.Release?>(null) }
    val firstButton = remember { FocusRequester() }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    LaunchedEffect(Unit) {
        runCatching { firstButton.requestFocus() }
        if (!BuildConfig.DEBUG) (Updater.check() as? Updater.Result.Available)?.let { update = it.release }
    }
    LaunchedEffect(Unit) {
        while (true) { // pick up new pairings and address changes
            phones = PairedPhones.all(context)
            address = PairedPhones.localAddress(context)
            delay(3_000)
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Row(Modifier.padding(48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(
                Modifier.weight(1.3f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("TV Mouse", style = MaterialTheme.typography.displaySmall)
                if (running) {
                    Text("✓ Ready. Your phone can control this TV.", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "On your phone, open TV Mouse → Connect → Wi-Fi and pick \"${PairedPhones.tvName(context)}\". " +
                            "The first time, a code appears on this screen. Type it on the phone.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "TV address: ${address ?: "not connected to a network"}" +
                            (if (ReceiverService.port != 0) "   Port: ${ReceiverService.port}" else ""),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    Text("One step left: turn on TV Mouse in Accessibility", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "TV Mouse needs Accessibility access to show a cursor, tap and type on the TV.\n\n" +
                            "1. Open Accessibility settings (button below).\n" +
                            "2. Choose TV Mouse and turn it on.\n\n" +
                            "If it's greyed out or blocked: Settings → Apps → TV Mouse → (⋮ menu) → " +
                            "Allow restricted settings, then try again.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }.onFailure { toast("Open Settings → Accessibility on the TV.") }
                        },
                        modifier = Modifier.focusRequester(firstButton),
                    ) { Text(if (running) "Accessibility settings" else "Open Accessibility settings") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            when (val result = Updater.check()) {
                                is Updater.Result.Available -> update = result.release
                                Updater.Result.UpToDate -> toast("You have the latest version (${BuildConfig.VERSION_NAME}).")
                                is Updater.Result.Failed -> toast("Update check failed: ${result.reason}")
                            }
                        }
                    }) { Text("Check for updates") }
                }
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall)
            }

            Card(Modifier.weight(1f)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Paired phones", style = MaterialTheme.typography.titleLarge)
                    if (phones.isEmpty()) Text("None yet.")
                    phones.forEach { phone ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(phone.name, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = {
                                PairedPhones.remove(context, phone.id)
                                phones = PairedPhones.all(context)
                            }) { Text("Forget") }
                        }
                    }
                    Text(
                        "A forgotten phone has to pair again with a new code.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(320.dp),
                    )
                }
            }
        }
    }

    update?.let { release ->
        UpdateDialog(release, onDismiss = { update = null }, onMessage = ::toast)
    }
    PendingCrashDialog()
}
