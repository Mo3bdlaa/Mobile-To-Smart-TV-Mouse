package io.github.mo3bdlaa.tvmouse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mo3bdlaa.tvmouse.Input
import io.github.mo3bdlaa.tvmouse.hid.HidManager
import io.github.mo3bdlaa.tvmouse.hid.HidManager.Status
import io.github.mo3bdlaa.tvmouse.net.NetClient
import io.github.mo3bdlaa.tvmouse.net.Proto
import io.github.mo3bdlaa.tvmouse.net.TvDiscovery
import io.github.mo3bdlaa.tvmouse.net.TvStore

@Composable
fun ConnectScreen(
    mode: Input.Mode,
    onModeChange: (Input.Mode) -> Unit,
    btState: HidManager.State,
    hasBtPermissions: Boolean,
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
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == Input.Mode.WIFI,
                onClick = { onModeChange(Input.Mode.WIFI) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = { Icon(Icons.Filled.Wifi, contentDescription = null) },
            ) { Text("Wi-Fi") }
            SegmentedButton(
                selected = mode == Input.Mode.BLUETOOTH,
                onClick = { onModeChange(Input.Mode.BLUETOOTH) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = { Icon(Icons.Filled.Bluetooth, contentDescription = null) },
            ) { Text("Bluetooth") }
        }
        when (mode) {
            Input.Mode.WIFI -> WifiPanel()
            Input.Mode.BLUETOOTH -> BluetoothPanel(btState, hasBtPermissions, onRequestPermissions, onEnableBluetooth, onMakeDiscoverable)
        }
    }
}

// ---------------------------------------------------------------------- Wi-Fi

@Composable
private fun WifiPanel() {
    val context = LocalContext.current
    val state by NetClient.state.collectAsStateWithLifecycle()
    val discovery = remember { TvDiscovery(context) }
    val found by discovery.found.collectAsStateWithLifecycle()
    var saved by remember { mutableStateOf(TvStore.all(context)) }
    var showAddByIp by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        discovery.start()
        onDispose { discovery.stop() }
    }
    LaunchedEffect(found, state.status) {
        // Remember new IP addresses of paired TVs; refresh the list after pairing.
        found.forEach { tv -> tv.id?.let { TvStore.updateAddress(context, it, tv.host, tv.port) } }
        saved = TvStore.all(context)
    }

    StatusCard(
        connected = state.status == NetClient.Status.CONNECTED,
        icon = Icons.Filled.Tv,
        title = netStatusTitle(state),
        message = state.message,
    )
    if (state.status == NetClient.Status.CONNECTED) {
        OutlinedButton(onClick = { NetClient.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("TVs on this Wi-Fi", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = {
            discovery.stop()
            discovery.start()
        }) { Icon(Icons.Filled.Refresh, contentDescription = "Search again") }
    }
    if (found.isEmpty()) {
        Text(
            "Searching… Make sure TV Mouse is installed and turned on on the TV and both are on the same Wi-Fi.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    val busy = state.status == NetClient.Status.CONNECTING || state.status == NetClient.Status.NEED_PIN
    found.forEach { tv ->
        val pairedTv = tv.id?.let { id -> saved.firstOrNull { it.id == id } }
        TvRow(name = tv.name, detail = tv.host) {
            if (pairedTv != null) {
                Button(enabled = !busy, onClick = { NetClient.connect(pairedTv.copy(host = tv.host, port = tv.port)) }) { Text("Connect") }
            } else {
                Button(enabled = !busy, onClick = { NetClient.pair(tv.host, tv.port) }) { Text("Pair") }
            }
        }
    }

    val offline = saved.filter { s -> found.none { it.id == s.id } }
    if (offline.isNotEmpty()) {
        Text("Paired TVs", style = MaterialTheme.typography.titleMedium)
        offline.forEach { tv ->
            TvRow(name = tv.name, detail = "last seen at ${tv.host}") {
                TextButton(onClick = {
                    TvStore.remove(context, tv.id)
                    saved = TvStore.all(context)
                }) { Text("Forget") }
                Button(enabled = !busy, onClick = { NetClient.connect(tv) }) { Text("Connect") }
            }
        }
    }

    OutlinedButton(onClick = { showAddByIp = true }, modifier = Modifier.fillMaxWidth()) { Text("Add TV by IP address") }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Set up the TV (once)", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. On the TV, install the \"Downloader\" app from the Play Store and open this address:\n" +
                    "   github.com/Mo3bdlaa/Mobile-To-Smart-TV-Mouse/releases/latest/download/TV-Mouse.apk\n" +
                    "2. Install it, open TV Mouse on the TV and follow the steps to turn it on in Accessibility.\n" +
                    "3. Back here, tap Pair next to your TV and type the code shown on the TV.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (state.status == NetClient.Status.NEED_PIN) PinDialog(tvName = state.tvName, hint = state.message)
    if (showAddByIp) AddByIpDialog(onDismiss = { showAddByIp = false })
}

@Composable
private fun TvRow(name: String, detail: String, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        actions()
    }
}

@Composable
private fun PinDialog(tvName: String?, hint: String?) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Pair with ${tvName ?: "TV"}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type the 6-digit code shown on the TV.")
                hint?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    label = { Text("Code") },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = pin.length == 6, onClick = {
                NetClient.submitPin(pin)
                pin = ""
            }) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = { NetClient.disconnect() }) { Text("Cancel") } },
    )
}

@Composable
private fun AddByIpDialog(onDismiss: () -> Unit) {
    var address by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add TV by IP address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Open TV Mouse on the TV to see its address, for example 192.168.1.20.")
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.trim() },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    label = { Text("IP address (optionally :port)") },
                )
            }
        },
        confirmButton = {
            TextButton(enabled = address.isNotBlank(), onClick = {
                val host = address.substringBefore(':')
                val port = address.substringAfter(':', "").toIntOrNull() ?: Proto.DEFAULT_PORT
                NetClient.pair(host, port)
                onDismiss()
            }) { Text("Pair") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

fun netStatusTitle(state: NetClient.State): String = when (state.status) {
    NetClient.Status.CONNECTED -> "Connected to ${state.tvName}"
    NetClient.Status.CONNECTING -> "Connecting to ${state.tvName ?: "TV"}…"
    NetClient.Status.NEED_PIN -> "Enter the code shown on ${state.tvName ?: "the TV"}"
    NetClient.Status.IDLE -> "Not connected"
}

// ---------------------------------------------------------------------- Bluetooth

@Composable
private fun BluetoothPanel(
    state: HidManager.State,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onMakeDiscoverable: () -> Unit,
) {
    StatusCard(
        connected = state.status == Status.CONNECTED,
        icon = if (state.status == Status.CONNECTED) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
        title = statusTitle(state),
        message = state.message,
    )
    when {
        !hasPermissions -> {
            Text("Bluetooth mode needs the Nearby devices permission to act as a keyboard and mouse.")
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
                        "Many TVs don't list phones. If yours doesn't, use Wi-Fi mode instead.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onMakeDiscoverable, modifier = Modifier.fillMaxWidth()) {
                    Text("Make phone visible (2 minutes)")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(connected: Boolean, icon: ImageVector, title: String, message: String?) {
    Card(
        colors = if (connected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
        TvRow(name = device.name, detail = device.address) {
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
    Status.STOPPED -> "Bluetooth mode is off"
}
