package io.github.mo3bdlaa.tvmouse.hid

/**
 * Maps characters to key strokes for a US keyboard layout, which is what TVs assume by
 * default. A HID keyboard sends key positions, not characters, so characters that do not
 * exist on a US keyboard (for example Arabic letters) cannot be typed this way.
 */
object KeyMap {

    private val symbols: Map<Char, KeyStroke> = buildMap {
        fun plain(c: Char, key: Int) = put(c, KeyStroke(KeyModifier.NONE, key))
        fun shifted(c: Char, key: Int) = put(c, KeyStroke(KeyModifier.SHIFT, key))

        plain('\n', Key.ENTER)
        plain('\t', Key.TAB)
        plain(' ', Key.SPACE)
        plain('-', 0x2D); shifted('_', 0x2D)
        plain('=', 0x2E); shifted('+', 0x2E)
        plain('[', 0x2F); shifted('{', 0x2F)
        plain(']', 0x30); shifted('}', 0x30)
        plain('\\', 0x31); shifted('|', 0x31)
        plain(';', 0x33); shifted(':', 0x33)
        plain('\'', 0x34); shifted('"', 0x34)
        plain('`', 0x35); shifted('~', 0x35)
        plain(',', 0x36); shifted('<', 0x36)
        plain('.', 0x37); shifted('>', 0x37)
        plain('/', 0x38); shifted('?', 0x38)
        "!@#$%^&*()".forEachIndexed { i, c -> shifted(c, 0x1E + i) }
    }

    /** Typographic characters phone keyboards like to insert, mapped to plain ASCII. */
    private val replacements = mapOf(
        ' ' to " ", '‘' to "'", '’' to "'", '“' to "\"", '”' to "\"",
        '–' to "-", '—' to "-", '…' to "...", '\r' to "",
    )

    fun normalize(text: String): String {
        if (text.none { it in replacements }) return text
        return buildString { text.forEach { c -> append(replacements[c] ?: c) } }
    }

    fun strokeFor(c: Char): KeyStroke? = when (c) {
        in 'a'..'z' -> KeyStroke(KeyModifier.NONE, Key.A + (c - 'a'))
        in 'A'..'Z' -> KeyStroke(KeyModifier.SHIFT, Key.A + (c - 'A'))
        in '1'..'9' -> KeyStroke(KeyModifier.NONE, 0x1E + (c - '1'))
        '0' -> KeyStroke(KeyModifier.NONE, 0x27)
        else -> symbols[c]
    }
}
