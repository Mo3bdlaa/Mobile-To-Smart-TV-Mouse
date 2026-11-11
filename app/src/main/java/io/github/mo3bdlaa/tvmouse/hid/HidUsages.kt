package io.github.mo3bdlaa.tvmouse.hid

/** Keyboard modifier bits (first byte of the keyboard report). */
object KeyModifier {
    const val NONE = 0
    const val CTRL = 0x01
    const val SHIFT = 0x02
    const val ALT = 0x04
    const val GUI = 0x08
}

/** USB HID keyboard usage IDs (usage page 0x07). */
object Key {
    const val A = 0x04
    const val C = 0x06
    const val V = 0x19
    const val X = 0x1B
    const val Z = 0x1D
    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val F1 = 0x3A
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val MENU = 0x65
}

/** Consumer control usage IDs (usage page 0x0C): media and TV keys. */
object Consumer {
    const val POWER = 0x30
    const val MENU = 0x40
    const val NEXT = 0xB5
    const val PREVIOUS = 0xB6
    const val STOP = 0xB7
    const val PLAY_PAUSE = 0xCD
    const val MUTE = 0xE2
    const val VOLUME_UP = 0xE9
    const val VOLUME_DOWN = 0xEA
    const val SEARCH = 0x221
    const val HOME = 0x223
    const val BACK = 0x224
}

object MouseButton {
    const val LEFT = 0x01
    const val RIGHT = 0x02
    const val MIDDLE = 0x04
}

/** One key press: a key usage plus the modifiers held with it. */
data class KeyStroke(val modifiers: Int, val key: Int)
