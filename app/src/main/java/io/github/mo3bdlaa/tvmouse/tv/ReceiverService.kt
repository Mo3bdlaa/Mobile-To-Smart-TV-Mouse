package io.github.mo3bdlaa.tvmouse.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.mo3bdlaa.tvmouse.R
import io.github.mo3bdlaa.tvmouse.net.Proto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Runs on the TV. Draws a mouse cursor and turns the phone's input into taps, swipes,
 * text edits and navigation using the accessibility APIs. Hosts the [ReceiverServer].
 */
class ReceiverService : AccessibilityService() {

    companion object {
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        @Volatile var port: Int = 0
            private set

        private const val CURSOR_HIDE_MS = 5_000L
        private const val SCROLL_STEP_PX = 120f
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var server: ReceiverServer? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    private var cursor: ImageView? = null
    private val cursorParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }
    private var pinView: View? = null

    private var width = 1920f
    private var height = 1080f
    private var x = 960f
    private var y = 540f

    private var pressX = 0f
    private var pressY = 0f
    private var pressTime = 0L
    private var pressed = false

    private var pendingWheel = 0
    private var pendingPan = 0
    private var scrollQueued = false

    private val hideCursor = Runnable { cursor?.visibility = View.INVISIBLE }

    // ------------------------------------------------------------------ lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WindowManager::class.java)
        updateScreenSize()
        x = width / 2
        y = height / 2
        addCursor()
        val context = this
        val store = object : ReceiverServer.Store {
            override val tvId = PairedPhones.tvId(context)
            override val tvName = PairedPhones.tvName(context)
            override fun phoneToken(phoneId: String) = PairedPhones.get(context, phoneId)?.token
            override fun addPhone(phoneId: String, name: String, token: ByteArray) = PairedPhones.add(context, phoneId, name, token)
        }
        val receiver = ReceiverServer(store, ::onMessage, pairingUi)
        server = receiver
        receiver.start { listening ->
            port = listening
            main.post { registerNsd(listening) }
        }
        _running.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (!_running.value) return
        _running.value = false
        server?.stop()
        server = null
        nsdListener?.let { runCatching { getSystemService(NsdManager::class.java).unregisterService(it) } }
        nsdListener = null
        cursor?.let { runCatching { windowManager.removeView(it) } }
        cursor = null
        removePin()
    }

    private fun registerNsd(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = PairedPhones.tvName(this@ReceiverService)
            serviceType = Proto.SERVICE_TYPE
            setPort(port)
            setAttribute("id", PairedPhones.tvId(this@ReceiverService))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        nsdListener = listener
        runCatching {
            getSystemService(NsdManager::class.java).registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    @Suppress("DEPRECATION")
    private fun updateScreenSize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            width = bounds.width().toFloat()
            height = bounds.height().toFloat()
        } else {
            val metrics = android.util.DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(metrics)
            width = metrics.widthPixels.toFloat()
            height = metrics.heightPixels.toFloat()
        }
    }

    // ------------------------------------------------------------------ cursor

    private fun addCursor() {
        val size = (height / 1080f * 36f).toInt().coerceAtLeast(24)
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_cursor)
            visibility = View.INVISIBLE
        }
        cursorParams.width = size
        cursorParams.height = size
        runCatching { windowManager.addView(view, cursorParams) }.onSuccess { cursor = view }
    }

    private fun showCursor() {
        val view = cursor ?: return
        cursorParams.x = x.toInt()
        cursorParams.y = y.toInt()
        view.visibility = View.VISIBLE
        runCatching { windowManager.updateViewLayout(view, cursorParams) }
        main.removeCallbacks(hideCursor)
        main.postDelayed(hideCursor, CURSOR_HIDE_MS)
    }

    // ------------------------------------------------------------------ input

    private fun onMessage(message: JSONObject, reply: (String) -> Unit) {
        main.post {
            try {
                handle(message, reply)
            } catch (e: Exception) {
                reply("The TV couldn't do that: ${e.message}")
            }
        }
    }

    private fun handle(message: JSONObject, reply: (String) -> Unit) {
        when (message.optString("t")) {
            "move" -> {
                val scale = width / 1920f
                x = (x + message.optInt("x") * scale).coerceIn(0f, width - 1)
                y = (y + message.optInt("y") * scale).coerceIn(0f, height - 1)
                showCursor()
            }
            "click" -> {
                showCursor()
                when (message.optString("b")) {
                    "right" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "middle" -> tap(x, y, 800)
                    else -> tap(x, y, 40)
                }
            }
            "down" -> if (message.optString("b") == "left") {
                pressed = true
                pressX = x
                pressY = y
                pressTime = SystemClock.uptimeMillis()
            }
            "up" -> if (message.optString("b") == "left" && pressed) {
                pressed = false
                val distance = hypot(x - pressX, y - pressY)
                val held = SystemClock.uptimeMillis() - pressTime
                when {
                    distance > 15 -> swipe(pressX, pressY, x, y, (300 + distance).toLong().coerceAtMost(1500))
                    held >= 500 -> tap(x, y, 800)
                    else -> tap(x, y, 40)
                }
            }
            "scroll" -> queueScroll(message.optInt("v"), message.optInt("h"))
            "text" -> insertText(message.optString("s"), reply)
            "backspace" -> deleteText(message.optInt("n", 1), reply)
            "key" -> key(message.optString("k"), reply)
        }
    }

    private fun tap(px: Float, py: Float, duration: Long) {
        val path = Path().apply {
            moveTo(px, py)
            lineTo(px, py)
        }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val path = Path().apply {
            moveTo(x1.coerceIn(0f, width - 1), y1.coerceIn(0f, height - 1))
            lineTo(x2.coerceIn(0f, width - 1), y2.coerceIn(0f, height - 1))
        }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build(), null, null)
    }

    private fun queueScroll(wheel: Int, pan: Int) {
        pendingWheel += wheel
        pendingPan += pan
        if (scrollQueued) return
        scrollQueued = true
        main.postDelayed({
            scrollQueued = false
            val v = pendingWheel
            val h = pendingPan
            pendingWheel = 0
            pendingPan = 0
            scrollBy(v, h)
        }, 50)
    }

    /** Wheel up (positive) = finger swipes down so the content moves down; pan right = swipe left. */
    private fun scrollBy(wheel: Int, pan: Int) {
        if (wheel == 0 && pan == 0) return
        val dy = (wheel * SCROLL_STEP_PX).coerceIn(-height * 0.6f, height * 0.6f)
        val dx = (-pan * SCROLL_STEP_PX).coerceIn(-width * 0.6f, width * 0.6f)
        // Keep the whole swipe on screen.
        val startX = x.coerceIn(abs(minOf(dx, 0f)) + 1, width - 1 - maxOf(dx, 0f))
        val startY = y.coerceIn(abs(minOf(dy, 0f)) + 1, height - 1 - maxOf(dy, 0f))
        swipe(startX, startY, startX + dx, startY + dy, 250)
    }

    // ------------------------------------------------------------------ keys

    private fun key(name: String, reply: (String) -> Unit) {
        when (name) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "menu" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "up", "down", "left", "right" -> dpad(name, reply)
            "enter" -> enter(reply)
            "backspace" -> deleteText(1, reply)
            "delete" -> deleteForward(reply)
            "volume_up" -> volume(AudioManager.ADJUST_RAISE)
            "volume_down" -> volume(AudioManager.ADJUST_LOWER)
            "mute" -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
            "play_pause" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "stop" -> mediaKey(KeyEvent.KEYCODE_MEDIA_STOP)
            "search" -> runCatching {
                startActivity(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { reply("Search isn't available on this TV.") }
            "select_all" -> withTextField(reply) { setSelection(it, 0, currentText(it).length) }
            "copy" -> withTextField(reply) { it.performAction(AccessibilityNodeInfo.ACTION_COPY) }
            "cut" -> withTextField(reply) { it.performAction(AccessibilityNodeInfo.ACTION_CUT) }
            "paste" -> withTextField(reply) { it.performAction(AccessibilityNodeInfo.ACTION_PASTE) }
            "text_start" -> withTextField(reply) { setSelection(it, 0, 0) }
            "text_end" -> withTextField(reply) { currentText(it).length.let { end -> setSelection(it, end, end) } }
            "page_up" -> scrollBy(5, 0)
            "page_down" -> scrollBy(-5, 0)
            else -> reply("\"$name\" isn't supported over Wi-Fi.")
        }
    }

    private fun dpad(direction: String, reply: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val action = when (direction) {
                "up" -> GLOBAL_ACTION_DPAD_UP
                "down" -> GLOBAL_ACTION_DPAD_DOWN
                "left" -> GLOBAL_ACTION_DPAD_LEFT
                else -> GLOBAL_ACTION_DPAD_RIGHT
            }
            performGlobalAction(action)
            return
        }
        // Older Android TV: move focus to the next view in that direction.
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        val viewDirection = when (direction) {
            "up" -> View.FOCUS_UP
            "down" -> View.FOCUS_DOWN
            "left" -> View.FOCUS_LEFT
            else -> View.FOCUS_RIGHT
        }
        val next = focused?.focusSearch(viewDirection)
        if (next == null || !next.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            reply("Arrow keys don't work here on this TV. Use the touchpad.")
        }
    }

    private fun enter(reply: (String) -> Unit) {
        val field = textField()
        if (field != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)
            return
        }
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused == null || !focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            tap(x, y, 40)
        }
    }

    private fun volume(direction: Int) {
        getSystemService(AudioManager::class.java)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
    }

    private fun mediaKey(code: Int) {
        val audio = getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ------------------------------------------------------------------ text

    private fun textField(): AccessibilityNodeInfo? =
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    private inline fun withTextField(reply: (String) -> Unit, action: (AccessibilityNodeInfo) -> Unit) {
        val field = textField()
        if (field == null) {
            reply("Select a text box on the TV first (click it with the touchpad).")
        } else {
            action(field)
        }
    }

    private fun currentText(node: AccessibilityNodeInfo): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText) return ""
        return node.text?.toString().orEmpty()
    }

    private fun selection(node: AccessibilityNodeInfo, length: Int): Pair<Int, Int> {
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        return if (start in 0..length && end in start..length) start to end else length to length
    }

    private fun insertText(text: String, reply: (String) -> Unit) = withTextField(reply) { field ->
        val current = currentText(field)
        val (start, end) = selection(field, current.length)
        setText(field, current.substring(0, start) + text + current.substring(end))
        setSelection(field, start + text.length, start + text.length)
    }

    private fun deleteText(count: Int, reply: (String) -> Unit) = withTextField(reply) { field ->
        val current = currentText(field)
        val (start, end) = selection(field, current.length)
        val from = if (start != end) start else (start - count).coerceAtLeast(0)
        setText(field, current.substring(0, from) + current.substring(end))
        setSelection(field, from, from)
    }

    private fun deleteForward(reply: (String) -> Unit) = withTextField(reply) { field ->
        val current = currentText(field)
        val (start, end) = selection(field, current.length)
        val to = if (start != end) end else (end + 1).coerceAtMost(current.length)
        setText(field, current.substring(0, start) + current.substring(to))
        setSelection(field, start, start)
    }

    private fun setText(node: AccessibilityNodeInfo, text: String) {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun setSelection(node: AccessibilityNodeInfo, start: Int, end: Int) {
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }

    // ------------------------------------------------------------------ pairing UI

    private val pairingUi = object : ReceiverServer.PairingUi {
        override fun showPin(phoneName: String, pin: String) {
            main.post { addPin(phoneName, pin) }
        }

        override fun hidePin() {
            main.post { removePin() }
        }

        override fun paired(phoneName: String) {
            main.post { Toast.makeText(this@ReceiverService, "Paired with $phoneName", Toast.LENGTH_LONG).show() }
        }
    }

    private fun addPin(phoneName: String, pin: String) {
        removePin()
        fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dp(32f).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 20, 30, 45))
                cornerRadius = dp(24f)
            }
            addView(text("Pair with \"$phoneName\"", 22f))
            addView(text("Enter this code on your phone:", 18f))
            addView(text(pin.chunked(3).joinToString(" "), 56f, bold = true))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        runCatching { windowManager.addView(card, params) }.onSuccess { pinView = card }
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(0, 8, 0, 8)
    }

    private fun removePin() {
        pinView?.let { runCatching { windowManager.removeView(it) } }
        pinView = null
    }
}
