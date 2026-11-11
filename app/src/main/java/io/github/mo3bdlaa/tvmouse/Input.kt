package io.github.mo3bdlaa.tvmouse

import android.content.Context
import io.github.mo3bdlaa.tvmouse.hid.Consumer
import io.github.mo3bdlaa.tvmouse.hid.HidManager
import io.github.mo3bdlaa.tvmouse.hid.Key
import io.github.mo3bdlaa.tvmouse.hid.KeyModifier
import io.github.mo3bdlaa.tvmouse.hid.MouseButton
import io.github.mo3bdlaa.tvmouse.net.NetClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The screens send input here. It goes to the TV over Wi-Fi (TV Mouse app on the TV)
 * or over Bluetooth (phone acting as a keyboard/mouse), depending on [mode].
 */
object Input {

    enum class Mode { WIFI, BLUETOOTH }

    private const val PREFS = "input"
    private lateinit var appContext: Context

    private val _mode = MutableStateFlow(Mode.WIFI)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val saved = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", null)
        _mode.value = saved?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.WIFI
        NetClient.init(appContext)
    }

    fun setMode(mode: Mode) {
        _mode.value = mode
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", mode.name).apply()
    }

    private val wifi get() = _mode.value == Mode.WIFI

    val isConnected: Boolean get() = if (wifi) NetClient.isConnected else HidManager.isConnected

    // Mouse ----------------------------------------------------------------------

    fun moveMouse(dx: Int, dy: Int) = if (wifi) NetClient.moveMouse(dx, dy) else HidManager.moveMouse(dx, dy)

    fun scroll(wheel: Int, pan: Int) = if (wifi) NetClient.scroll(wheel, pan) else HidManager.scroll(wheel, pan)

    fun click(button: Int) = if (wifi) NetClient.click(buttonName(button)) else HidManager.click(button)

    fun setButton(button: Int, down: Boolean) =
        if (wifi) NetClient.button(buttonName(button), down) else HidManager.setButton(button, down)

    private fun buttonName(button: Int) = when (button) {
        MouseButton.RIGHT -> "right"
        MouseButton.MIDDLE -> "middle"
        else -> "left"
    }

    // Keys -----------------------------------------------------------------------

    fun pressKey(key: Int, modifiers: Int = KeyModifier.NONE) {
        if (!wifi) return HidManager.pressKey(key, modifiers)
        val name = if (modifiers and KeyModifier.CTRL != 0) {
            when (key) {
                Key.A -> "select_all"
                Key.C -> "copy"
                Key.X -> "cut"
                Key.V -> "paste"
                Key.Z -> "undo"
                else -> null
            }
        } else {
            when (key) {
                Key.ENTER -> "enter"
                Key.BACKSPACE -> "backspace"
                Key.DELETE -> "delete"
                Key.ESCAPE -> "back"
                Key.TAB -> "tab"
                Key.UP -> "up"
                Key.DOWN -> "down"
                Key.LEFT -> "left"
                Key.RIGHT -> "right"
                Key.HOME -> "text_start"
                Key.END -> "text_end"
                Key.PAGE_UP -> "page_up"
                Key.PAGE_DOWN -> "page_down"
                Key.MENU -> "menu"
                else -> null
            }
        }
        name?.let(NetClient::key)
    }

    fun pressConsumer(usage: Int) {
        if (!wifi) return HidManager.pressConsumer(usage)
        val name = when (usage) {
            Consumer.BACK -> "back"
            Consumer.HOME -> "home"
            Consumer.SEARCH -> "search"
            Consumer.VOLUME_UP -> "volume_up"
            Consumer.VOLUME_DOWN -> "volume_down"
            Consumer.MUTE -> "mute"
            Consumer.PLAY_PAUSE -> "play_pause"
            Consumer.NEXT -> "next"
            Consumer.PREVIOUS -> "previous"
            Consumer.STOP -> "stop"
            else -> null
        }
        name?.let(NetClient::key)
    }

    // Text -----------------------------------------------------------------------

    /** [onDone] gets the number of characters that couldn't be typed (Bluetooth only). */
    fun typeText(text: String, onDone: ((Int) -> Unit)? = null) {
        if (text.isEmpty()) return
        if (wifi) {
            NetClient.text(text)
            onDone?.invoke(0)
        } else {
            HidManager.typeText(text, onDone)
        }
    }

    fun backspace(count: Int) {
        if (count <= 0) return
        if (wifi) NetClient.backspace(count) else HidManager.backspace(count)
    }

    fun cancelTyping() {
        if (!wifi) HidManager.cancelTyping()
    }
}
