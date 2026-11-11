package io.github.mo3bdlaa.tvmouse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mo3bdlaa.tvmouse.hid.HidManager
import io.github.mo3bdlaa.tvmouse.hid.HidManager.Status

@Composable
fun ConnectScreen(
    state: HidManager.State,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onMakeDiscoverable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard(state)

        when {
            !hasPermissions -> {
                Text("TV Mouse needs the Nearby devices (Bluetooth) permission to act as a keyboard and mouse.")
                Button(onClick = onRequestPermissions) { Text("Grant permission") }
            }
            state.status == Status.BLUETOOTH_OFF -> Button(onClick = onEnableBluetooth) { Text("Turn on Bluetooth") }
            state.status == Status.UNSUPPORTED -> Unit
            else -> DeviceList(state)
        }

        if (hasPermissions && state.status != Status.UNSUPPORTED) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("First-time pairing", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "1. Keep TV Mouse open.\n" +
                            "2. Tap \"Make phone visible\" below.\n" +
                            "3. On the TV, open Settings → Bluetooth (on Google TV: Remotes & " +
                            "Accessories → Pair accessory) and choose this phone.\n" +
                            "4. Confirm the pairing code on both screens.\n\n" +
                            "If the TV paired with this phone before, remove (forget) it on the TV and " +
                            "on the phone first, then pair again. Otherwise the TV may not notice that " +
                            "the phone is now a keyboard and mouse.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onMakeDiscoverable, modifier = Modifier.fillMaxWidth()) {
                        Text("Make phone visible (2 minutes)")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: HidManager.State) {
    val connected = state.status == Status.CONNECTED
    Card(
        colors = if (connected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (connected) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
                contentDescription = null,
            )
            Column(Modifier.padding(start = 12.dp)) {
                Text(statusTitle(state), style = MaterialTheme.typography.titleMedium)
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun DeviceList(state: HidManager.State) {
    var refresh by remember { mutableIntStateOf(0) }
    val devices = remember(state.status, refresh) { HidManager.pairedDevices() }
    val canConnect = state.status in setOf(Status.READY, Status.CONNECTED, Status.CONNECTING)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Paired devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
    }
    if (devices.isEmpty()) {
        Text("No paired devices yet. Follow the pairing steps below.", style = MaterialTheme.typography.bodySmall)
    }
    devices.forEach { device ->
        val isCurrent = state.device?.address == device.address
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name)
                Text(device.address, style = MaterialTheme.typography.bodySmall)
            }
            when {
                isCurrent && state.status == Status.CONNECTED ->
                    TextButton(onClick = { HidManager.disconnect() }) { Text("Disconnect") }
                isCurrent && state.status == Status.CONNECTING -> Text("Connecting…")
                else -> Button(onClick = { HidManager.connect(device.address) }, enabled = canConnect) {
                    Text("Connect")
                }
            }
        }
    }
}

fun statusTitle(state: HidManager.State): String = when (state.status) {
    Status.CONNECTED -> "Connected to ${state.device?.name}"
    Status.CONNECTING -> "Connecting to ${state.device?.name ?: "TV"}…"
    Status.READY -> "Ready, not connected"
    Status.STARTING -> "Starting…"
    Status.BLUETOOTH_OFF -> "Bluetooth is off"
    Status.UNSUPPORTED -> "Not supported on this phone"
    Status.ERROR -> "Problem"
    Status.STOPPED -> "Stopped"
}
