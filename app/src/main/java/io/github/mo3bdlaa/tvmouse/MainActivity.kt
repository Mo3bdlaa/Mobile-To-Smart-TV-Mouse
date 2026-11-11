package io.github.mo3bdlaa.tvmouse

import android.Manifest
import android.app.UiModeManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mo3bdlaa.tvmouse.crash.IssueReporter
import io.github.mo3bdlaa.tvmouse.hid.HidManager
import io.github.mo3bdlaa.tvmouse.hid.HidService
import io.github.mo3bdlaa.tvmouse.net.NetClient
import io.github.mo3bdlaa.tvmouse.net.TvStore
import io.github.mo3bdlaa.tvmouse.tv.TvReceiverScreen
import io.github.mo3bdlaa.tvmouse.ui.ConnectScreen
import io.github.mo3bdlaa.tvmouse.ui.KeyboardScreen
import io.github.mo3bdlaa.tvmouse.ui.PendingCrashDialog
import io.github.mo3bdlaa.tvmouse.ui.RemoteScreen
import io.github.mo3bdlaa.tvmouse.ui.TouchpadScreen
import io.github.mo3bdlaa.tvmouse.ui.TouchpadSettings
import io.github.mo3bdlaa.tvmouse.ui.TvMouseTheme
import io.github.mo3bdlaa.tvmouse.ui.UpdateDialog
import io.github.mo3bdlaa.tvmouse.ui.netStatusTitle
import io.github.mo3bdlaa.tvmouse.ui.statusTitle
import io.github.mo3bdlaa.tvmouse.update.Updater
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Text shared to the app from another app ("Share → TV Mouse"). */
    private val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val onTv = isTv(this)
        if (!onTv) {
            Input.init(this)
            handleShare(intent)
        }
        setContent {
            TvMouseTheme {
                if (onTv) {
                    TvReceiverScreen()
                } else {
                    PhoneScreen(sharedText = sharedText.value, onSharedTextUsed = { sharedText.value = null })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText.value = it }
        }
    }

    companion object {
        fun isTv(context: Context): Boolean =
            context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    CONNECT("Connect", Icons.Filled.Tv),
    TOUCHPAD("Touchpad", Icons.Filled.TouchApp),
    KEYBOARD("Keyboard", Icons.Filled.Keyboard),
    REMOTE("Remote", Icons.Filled.SettingsRemote),
}

private fun bluetoothPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

/** Notifications are optional; only the Bluetooth permissions are required. */
private fun hasBluetoothPermissions(context: Context): Boolean =
    bluetoothPermissions()
        .filter { it != Manifest.permission.POST_NOTIFICATIONS }
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneScreen(sharedText: String?, onSharedTextUsed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val mode by Input.mode.collectAsStateWithLifecycle()
    val btState by HidManager.state.collectAsStateWithLifecycle()
    val netState by NetClient.state.collectAsStateWithLifecycle()

    var hasBtPermissions by remember { mutableStateOf(hasBluetoothPermissions(context)) }
    var tab by rememberSaveable {
        val known = TvStore.last(context) != null || HidManager.lastDeviceAddress(context) != null
        mutableStateOf(if (known) Tab.TOUCHPAD else Tab.CONNECT)
    }
    var settings by remember { mutableStateOf(TouchpadSettings.load(context)) }
    var showMenu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<Updater.Release?>(null) }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasBtPermissions = hasBluetoothPermissions(context)
        if (hasBtPermissions && Input.mode.value == Input.Mode.BLUETOOTH) HidService.start(context)
    }
    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

    // Start whichever connection the chosen mode needs.
    LaunchedEffect(mode) {
        when (mode) {
            Input.Mode.WIFI -> {
                HidService.stop(context)
                if (!NetClient.isConnected && NetClient.state.value.status == NetClient.Status.IDLE) {
                    TvStore.last(context)?.let(NetClient::connect)
                }
            }
            Input.Mode.BLUETOOTH -> {
                NetClient.disconnect()
                if (hasBtPermissions) HidService.start(context) else permissionLauncher.launch(bluetoothPermissions())
            }
        }
    }
    LaunchedEffect(Unit) {
        if (!BuildConfig.DEBUG) (Updater.check() as? Updater.Result.Available)?.let { update = it.release }
    }
    LaunchedEffect(Unit) {
        NetClient.notices.collect { message(it) }
    }
    LaunchedEffect(sharedText) {
        if (sharedText != null) tab = Tab.KEYBOARD
    }
    val connected = if (mode == Input.Mode.WIFI) {
        netState.status == NetClient.Status.CONNECTED
    } else {
        btState.status == HidManager.Status.CONNECTED
    }
    var wasConnected by remember { mutableStateOf(false) }
    LaunchedEffect(connected) {
        if (connected && !wasConnected && tab == Tab.CONNECT) tab = Tab.TOUCHPAD
        wasConnected = connected
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(tab.label)
                        Text(
                            if (mode == Input.Mode.WIFI) netStatusTitle(netState) else statusTitle(btState),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Touchpad settings") }, onClick = {
                            showMenu = false
                            showSettings = true
                        })
                        DropdownMenuItem(text = { Text("Check for updates") }, onClick = {
                            showMenu = false
                            scope.launch {
                                when (val result = Updater.check()) {
                                    is Updater.Result.Available -> update = result.release
                                    Updater.Result.UpToDate -> message("You have the latest version (${BuildConfig.VERSION_NAME}).")
                                    is Updater.Result.Failed -> message("Update check failed: ${result.reason}")
                                }
                            }
                        })
                        DropdownMenuItem(text = { Text("Report a problem") }, onClick = {
                            showMenu = false
                            IssueReporter.openBugIssue(context)
                        })
                        DropdownMenuItem(text = { Text("Disconnect and exit") }, onClick = {
                            showMenu = false
                            NetClient.disconnect()
                            HidService.stop(context)
                            (context as? ComponentActivity)?.finishAndRemoveTask()
                        })
                        DropdownMenuItem(
                            text = { Text("Version ${BuildConfig.VERSION_NAME}") },
                            onClick = { showMenu = false },
                            enabled = false,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
        when (tab) {
            Tab.CONNECT -> ConnectScreen(
                mode = mode,
                onModeChange = Input::setMode,
                btState = btState,
                hasBtPermissions = hasBtPermissions,
                onRequestPermissions = { permissionLauncher.launch(bluetoothPermissions()) },
                onEnableBluetooth = { enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                onMakeDiscoverable = {
                    discoverable.launch(
                        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120),
                    )
                },
                modifier = contentModifier,
            )
            Tab.TOUCHPAD -> TouchpadScreen(settings, contentModifier)
            Tab.KEYBOARD -> KeyboardScreen(
                sharedText = sharedText,
                onSharedTextUsed = onSharedTextUsed,
                onMessage = ::message,
                modifier = contentModifier.imePadding(),
            )
            Tab.REMOTE -> RemoteScreen(contentModifier)
        }
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            onChange = {
                settings = it
                it.save(context)
            },
            onDismiss = { showSettings = false },
        )
    }
    update?.let { release -> UpdateDialog(release, onDismiss = { update = null }, onMessage = ::message) }
    PendingCrashDialog()
}

@Composable
private fun SettingsDialog(
    settings: TouchpadSettings,
    onChange: (TouchpadSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Touchpad settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Pointer speed: ${"%.1f".format(settings.sensitivity)}")
                Slider(
                    value = settings.sensitivity,
                    onValueChange = { onChange(settings.copy(sensitivity = it)) },
                    valueRange = 0.3f..5f,
                )
                Text("Acceleration: ${"%.1f".format(settings.acceleration)}")
                Slider(
                    value = settings.acceleration,
                    onValueChange = { onChange(settings.copy(acceleration = it)) },
                    valueRange = 0f..2f,
                )
                Text("Scroll speed")
                Slider(
                    // Shown as speed: a smaller step means faster scrolling.
                    value = 48f - settings.scrollStep,
                    onValueChange = { onChange(settings.copy(scrollStep = 48f - it)) },
                    valueRange = 8f..40f,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Natural scrolling", modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.naturalScroll,
                        onCheckedChange = { onChange(settings.copy(naturalScroll = it)) },
                    )
                }
                OutlinedButton(
                    onClick = { onChange(TouchpadSettings()) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text("Reset to defaults") }
                TextButton(
                    onClick = { throw RuntimeException("Test crash from settings (crash reporting check)") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Test crash reporting") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
