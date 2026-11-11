package io.github.mo3bdlaa.tvmouse.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Registers the phone as a Bluetooth HID keyboard + mouse and sends input reports to the
 * connected TV. Callers must hold the Bluetooth permissions before calling [start].
 */
@SuppressLint("MissingPermission")
object HidManager {

    enum class Status { STOPPED, BLUETOOTH_OFF, UNSUPPORTED, STARTING, READY, CONNECTING, CONNECTED, ERROR }

    data class Device(val address: String, val name: String)

    data class State(
        val status: Status = Status.STOPPED,
        val device: Device? = null,
        val message: String? = null,
    )

    private const val TAG = "HidManager"
    private const val PREFS = "hid"
    private const val PREF_LAST_DEVICE = "last_device"
    private const val KEY_DELAY_MS = 12L

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var appContext: Context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackExecutor = Executors.newSingleThreadExecutor()
    private val sendExecutor = Executors.newSingleThreadExecutor()

    @Volatile private var adapter: BluetoothAdapter? = null
    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    @Volatile private var wanted = false
    @Volatile private var proxyRequested = false
    private var receiverRegistered = false

    val isConnected: Boolean get() = host != null

    // ------------------------------------------------------------------ lifecycle

    fun start(context: Context) {
        appContext = context.applicationContext
        wanted = true
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                appContext, bluetoothStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                // A protected system broadcast: only the system can send it.
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
        }
        val btAdapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (btAdapter == null) {
            setState(Status.UNSUPPORTED, message = "This phone has no Bluetooth.")
            return
        }
        adapter = btAdapter
        if (!btAdapter.isEnabled) {
            setState(Status.BLUETOOTH_OFF)
            return
        }
        if (hid != null || proxyRequested) return
        proxyRequested = true
        setState(Status.STARTING)
        if (!btAdapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)) {
            proxyRequested = false
            setState(
                Status.UNSUPPORTED,
                message = "This phone doesn't let apps act as a Bluetooth keyboard/mouse " +
                    "(the manufacturer disabled the HID Device profile).",
            )
        }
    }

    fun stop() {
        wanted = false
        releaseProfile()
        setState(Status.STOPPED)
    }

    private fun releaseProfile() {
        val current = hid
        hid = null
        host = null
        proxyRequested = false
        if (current != null) {
            runCatching { current.unregisterApp() }
            runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, current) }
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> if (wanted) start(appContext)
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    hid = null
                    host = null
                    proxyRequested = false
                    if (wanted) setState(Status.BLUETOOTH_OFF)
                }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            val hidProxy = proxy as BluetoothHidDevice
            hid = hidProxy
            val sdp = BluetoothHidDeviceAppSdpSettings(
                "TV Mouse", "Phone touchpad and keyboard", "TV Mouse",
                BluetoothHidDevice.SUBCLASS1_COMBO, HidDescriptor.DESCRIPTOR,
            )
            val qos = BluetoothHidDeviceAppQosSettings(
                BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
                800, 9, 0, 11250, BluetoothHidDeviceAppQosSettings.MAX,
            )
            val ok = runCatching { hidProxy.registerApp(sdp, null, qos, callbackExecutor, hidCallback) }
                .getOrDefault(false)
            if (!ok) {
                setState(
                    Status.ERROR,
                    message = "Couldn't register as a keyboard/mouse. Another app may be using " +
                        "this feature. Close it, then restart TV Mouse.",
                )
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            host = null
            proxyRequested = false
            if (wanted && _state.value.status != Status.BLUETOOTH_OFF) setState(Status.STARTING)
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            Log.i(TAG, "App registered=$registered plugged=${pluggedDevice?.address}")
            if (!registered) {
                host = null
                if (wanted) setState(Status.ERROR, message = "Bluetooth keyboard/mouse was turned off by the system.")
                return
            }
            setState(Status.READY)
            val target = pluggedDevice ?: lastDeviceAddress(appContext)?.let { adapter?.getRemoteDevice(it) }
            if (target != null && host == null) {
                // Reconnect to the TV we used last time, if it's on.
                if (hid?.connect(target) == true) setState(Status.CONNECTING, deviceInfo(target))
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            device ?: return
            Log.i(TAG, "Connection ${device.address} -> $state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    saveLastDevice(device.address)
                    setState(Status.CONNECTED, deviceInfo(device))
                }
                BluetoothProfile.STATE_CONNECTING -> setState(Status.CONNECTING, deviceInfo(device))
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == null || host?.address == device.address) {
                        host = null
                        val previous = _state.value
                        val message = if (previous.status == Status.CONNECTED) {
                            "Disconnected from ${deviceInfo(device).name}"
                        } else {
                            "Couldn't connect to ${deviceInfo(device).name}. Make sure it is on " +
                                "and paired, or start the connection from the TV."
                        }
                        setState(Status.READY, message = message)
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            val size = when (id.toInt()) {
                HidDescriptor.ID_KEYBOARD ->
                    if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT) 1 else HidDescriptor.KEYBOARD_SIZE
                HidDescriptor.ID_MOUSE -> HidDescriptor.MOUSE_SIZE
                HidDescriptor.ID_CONSUMER -> HidDescriptor.CONSUMER_SIZE
                else -> -1
            }
            if (size > 0) {
                hid?.replyReport(device, type, id, ByteArray(size))
            } else {
                hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
            }
        }

        override fun onSetReport(device: BluetoothDevice?, type: Byte, id: Byte, data: ByteArray?) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    // ------------------------------------------------------------------ devices

    fun pairedDevices(): List<Device> =
        runCatching { adapter?.bondedDevices.orEmpty().map(::deviceInfo) }
            .getOrDefault(emptyList())
            .sortedBy { it.name.lowercase() }

    fun connect(address: String) {
        val proxy = hid ?: return
        val device = adapter?.getRemoteDevice(address) ?: return
        host?.let { if (it.address != address) proxy.disconnect(it) }
        if (proxy.connect(device)) {
            setState(Status.CONNECTING, deviceInfo(device))
        } else {
            setState(Status.READY, message = "Couldn't start connecting to ${deviceInfo(device).name}.")
        }
    }

    fun disconnect() {
        val device = host ?: return
        hid?.disconnect(device)
    }

    fun lastDeviceAddress(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_LAST_DEVICE, null)

    private fun saveLastDevice(address: String) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_LAST_DEVICE, address).apply()
    }

    private fun deviceInfo(device: BluetoothDevice): Device {
        val name = runCatching { device.name }.getOrNull()
        return Device(device.address, if (name.isNullOrBlank()) device.address else name)
    }

    private fun setState(status: Status, device: Device? = _state.value.device, message: String? = null) {
        val keepDevice = status == Status.CONNECTED || status == Status.CONNECTING
        _state.value = State(status, if (keepDevice) device else null, message)
    }

    // ------------------------------------------------------------------ sending input

    private fun send(id: Int, data: ByteArray): Boolean {
        val proxy = hid ?: return false
        val device = host ?: return false
        return runCatching { proxy.sendReport(device, id, data) }.getOrDefault(false)
    }

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // Keyboard ----------------------------------------------------------------

    private val typingGeneration = AtomicInteger()

    private fun tap(stroke: KeyStroke) {
        send(HidDescriptor.ID_KEYBOARD, HidReports.keyboard(stroke.modifiers, stroke.key))
        pause(KEY_DELAY_MS)
        send(HidDescriptor.ID_KEYBOARD, HidReports.keyboard(KeyModifier.NONE))
        pause(KEY_DELAY_MS)
    }

    fun pressKey(key: Int, modifiers: Int = KeyModifier.NONE) {
        sendExecutor.execute { tap(KeyStroke(modifiers, key)) }
    }

    fun backspace(count: Int) {
        if (count <= 0) return
        sendExecutor.execute { repeat(count) { tap(KeyStroke(KeyModifier.NONE, Key.BACKSPACE)) } }
    }

    /**
     * Types [text] on the TV. [onDone] runs on the main thread with the number of
     * characters that could not be typed with a US keyboard layout.
     */
    fun typeText(text: String, onDone: ((skipped: Int) -> Unit)? = null) {
        if (text.isEmpty()) return
        val generation = typingGeneration.get()
        sendExecutor.execute {
            var skipped = 0
            for (c in KeyMap.normalize(text)) {
                if (typingGeneration.get() != generation) break
                val stroke = KeyMap.strokeFor(c)
                if (stroke == null) skipped++ else tap(stroke)
            }
            if (onDone != null) mainHandler.post { onDone(skipped) }
        }
    }

    /** Stops any text that is still being typed. */
    fun cancelTyping() {
        typingGeneration.incrementAndGet()
    }

    // Consumer keys -------------------------------------------------------------

    fun pressConsumer(usage: Int) {
        sendExecutor.execute {
            send(HidDescriptor.ID_CONSUMER, HidReports.consumer(usage))
            pause(KEY_DELAY_MS * 2)
            send(HidDescriptor.ID_CONSUMER, HidReports.consumer(0))
        }
    }

    // Mouse --------------------------------------------------------------------

    private val mouseLock = Any()
    private var pendingX = 0
    private var pendingY = 0
    private var pendingWheel = 0
    private var pendingPan = 0
    private var flushQueued = false
    @Volatile private var buttons = 0

    fun moveMouse(dx: Int, dy: Int) = queueMouse(dx, dy, 0, 0)

    fun scroll(wheel: Int, pan: Int) = queueMouse(0, 0, wheel, pan)

    private fun queueMouse(dx: Int, dy: Int, wheel: Int, pan: Int) {
        synchronized(mouseLock) {
            pendingX += dx
            pendingY += dy
            pendingWheel += wheel
            pendingPan += pan
            if (flushQueued) return
            flushQueued = true
        }
        sendExecutor.execute(::flushMouse)
    }

    private fun flushMouse() {
        val dx: Int
        val dy: Int
        val wheel: Int
        val pan: Int
        synchronized(mouseLock) {
            dx = pendingX; dy = pendingY; wheel = pendingWheel; pan = pendingPan
            pendingX = 0; pendingY = 0; pendingWheel = 0; pendingPan = 0
            flushQueued = false
        }
        if (dx != 0 || dy != 0) {
            for ((stepX, stepY) in HidReports.splitMovement(dx, dy)) {
                send(HidDescriptor.ID_MOUSE, HidReports.mouse(buttons, stepX, stepY))
            }
        }
        if (wheel != 0 || pan != 0) {
            send(HidDescriptor.ID_MOUSE, HidReports.mouse(buttons, wheel = wheel, pan = pan))
        }
    }

    fun click(button: Int) {
        sendExecutor.execute {
            send(HidDescriptor.ID_MOUSE, HidReports.mouse(buttons or button))
            pause(KEY_DELAY_MS * 2)
            send(HidDescriptor.ID_MOUSE, HidReports.mouse(buttons))
        }
    }

    fun setButton(button: Int, down: Boolean) {
        sendExecutor.execute {
            buttons = if (down) buttons or button else buttons and button.inv()
            send(HidDescriptor.ID_MOUSE, HidReports.mouse(buttons))
        }
    }
}
