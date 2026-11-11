package io.github.mo3bdlaa.tvmouse.net

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Phone side of the Wi-Fi connection to the TV Mouse receiver app on the TV. */
object NetClient {

    enum class Status { IDLE, CONNECTING, NEED_PIN, CONNECTED }

    data class State(
        val status: Status = Status.IDLE,
        val tvName: String? = null,
        val message: String? = null,
    )

    private const val TAG = "NetClient"

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Short messages from the TV, e.g. "Select a text box first". */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    private lateinit var appContext: Context
    private val sendExecutor = Executors.newSingleThreadExecutor()
    private val pingExecutor = Executors.newSingleThreadScheduledExecutor()
    private val pins = LinkedBlockingQueue<String>()

    @Volatile private var socket: Socket? = null
    @Volatile private var channel: SecureChannel? = null
    @Volatile private var generation = 0

    val isConnected: Boolean get() = channel != null

    @Synchronized
    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        pingExecutor.scheduleWithFixedDelay({ channel?.let { send(msg("t" to "ping")) } }, 5, 5, TimeUnit.SECONDS)
    }

    fun connect(tv: TvStore.Tv) = open(tv.host, tv.port, pairing = false, saved = tv)

    fun pair(host: String, port: Int) = open(host, port, pairing = true, saved = null)

    fun submitPin(pin: String) {
        pins.offer(pin.trim())
    }

    fun disconnect() {
        generation++
        closeSocket()
        _state.value = State()
    }

    private fun closeSocket() {
        channel = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun open(host: String, port: Int, pairing: Boolean, saved: TvStore.Tv?) {
        val gen = ++generation
        closeSocket()
        pins.clear()
        _state.value = State(Status.CONNECTING, saved?.name ?: host)
        thread(name = "tvmouse-client", isDaemon = true) { run(gen, host, port, pairing, saved) }
    }

    private fun setState(gen: Int, state: State) {
        if (gen == generation) _state.value = state
    }

    private fun run(gen: Int, host: String, port: Int, pairing: Boolean, saved: TvStore.Tv?) {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 5_000)
            s.soTimeout = 15_000
            if (gen != generation) return
            socket = s
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(s.getOutputStream()))

            val tv = if (pairing) pairWithTv(gen, s, input, output, host, port) else saved!!
            val secure = authenticate(tv, input, output)
            if (gen != generation) return
            channel = secure
            TvStore.save(appContext, tv)
            setState(gen, State(Status.CONNECTED, tv.name))

            while (gen == generation) {
                val message = secure.receive()
                if (message.optString("t") == "notice") _notices.tryEmit(message.optString("msg"))
            }
        } catch (e: Exception) {
            Log.i(TAG, "Connection ended", e)
            if (gen == generation) {
                channel = null
                _state.value = State(Status.IDLE, message = friendlyError(e, host))
            }
        } finally {
            runCatching { s.close() }
        }
    }

    private fun pairWithTv(
        gen: Int,
        s: Socket,
        input: DataInputStream,
        output: DataOutputStream,
        host: String,
        port: Int,
    ): TvStore.Tv {
        var tvName: String? = null
        s.soTimeout = 0 // waiting for the user to type the code
        val paired = ClientHandshake.pair(
            input, output, TvStore.phoneId(appContext), phoneName(),
            onChallenge = { tvName = it },
        ) { hint ->
            setState(gen, State(Status.NEED_PIN, tvName, hint))
            val pin = pins.poll(120, TimeUnit.SECONDS)
            if (gen != generation) return@pair null
            setState(gen, State(Status.CONNECTING, tvName))
            pin
        }
        s.soTimeout = 15_000
        return TvStore.Tv(paired.tvId, paired.tvName, host, port, paired.token)
    }

    private fun authenticate(tv: TvStore.Tv, input: DataInputStream, output: DataOutputStream): SecureChannel =
        try {
            ClientHandshake.authenticate(input, output, TvStore.phoneId(appContext), tv.id, tv.token)
        } catch (e: ClientHandshake.TvError) {
            if (e.code == "not_paired") TvStore.remove(appContext, tv.id)
            throw e
        }

    private fun friendlyError(e: Exception, host: String): String = when (e) {
        is java.net.ConnectException, is java.net.NoRouteToHostException ->
            "Can't reach the TV at $host. Is the TV on, on the same Wi-Fi, and is TV Mouse enabled on it?"
        is java.net.SocketTimeoutException -> "The TV stopped answering."
        is java.io.EOFException -> "The TV closed the connection."
        else -> e.message ?: "Connection failed."
    }

    private fun phoneName(): String =
        runCatching { Settings.Global.getString(appContext.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    // ------------------------------------------------------------------ sending

    private fun send(message: JSONObject) {
        val current = channel ?: return
        sendExecutor.execute {
            try {
                current.send(message)
            } catch (e: Exception) {
                if (channel === current) runCatching { socket?.close() } // reader thread reports it
            }
        }
    }

    private val lock = Any()
    private var pendingX = 0
    private var pendingY = 0
    private var pendingWheel = 0
    private var pendingPan = 0
    private var flushQueued = false

    fun moveMouse(dx: Int, dy: Int) = queueMotion(dx, dy, 0, 0)

    fun scroll(wheel: Int, pan: Int) = queueMotion(0, 0, wheel, pan)

    private fun queueMotion(dx: Int, dy: Int, wheel: Int, pan: Int) {
        if (channel == null) return
        synchronized(lock) {
            pendingX += dx; pendingY += dy; pendingWheel += wheel; pendingPan += pan
            if (flushQueued) return
            flushQueued = true
        }
        sendExecutor.execute {
            val m: JSONObject?
            val s: JSONObject?
            synchronized(lock) {
                m = if (pendingX != 0 || pendingY != 0) msg("t" to "move", "x" to pendingX, "y" to pendingY) else null
                s = if (pendingWheel != 0 || pendingPan != 0) msg("t" to "scroll", "v" to pendingWheel, "h" to pendingPan) else null
                pendingX = 0; pendingY = 0; pendingWheel = 0; pendingPan = 0
                flushQueued = false
            }
            val current = channel ?: return@execute
            try {
                m?.let(current::send)
                s?.let(current::send)
            } catch (e: Exception) {
                runCatching { socket?.close() }
            }
        }
    }

    fun click(button: String) = send(msg("t" to "click", "b" to button))

    fun button(button: String, down: Boolean) = send(msg("t" to if (down) "down" else "up", "b" to button))

    fun key(name: String) = send(msg("t" to "key", "k" to name))

    fun text(text: String) = send(msg("t" to "text", "s" to text))

    fun backspace(count: Int) = send(msg("t" to "backspace", "n" to count))
}
