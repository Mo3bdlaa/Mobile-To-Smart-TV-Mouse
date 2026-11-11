package io.github.mo3bdlaa.tvmouse.hid

import kotlin.math.abs
import kotlin.math.max

/** Builds the raw report payloads (without the report ID byte) for [HidDescriptor]. */
object HidReports {

    fun keyboard(modifiers: Int, vararg keys: Int): ByteArray {
        val report = ByteArray(HidDescriptor.KEYBOARD_SIZE)
        report[0] = modifiers.toByte()
        keys.take(6).forEachIndexed { i, key -> report[2 + i] = key.toByte() }
        return report
    }

    fun mouse(buttons: Int, dx: Int = 0, dy: Int = 0, wheel: Int = 0, pan: Int = 0): ByteArray =
        byteArrayOf(buttons.toByte(), clamp(dx), clamp(dy), clamp(wheel), clamp(pan))

    fun consumer(usage: Int): ByteArray =
        byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte())

    fun clamp(value: Int): Byte = value.coerceIn(-127, 127).toByte()

    /** Splits a movement into evenly sized steps that each fit in a signed 8-bit report. */
    fun splitMovement(dx: Int, dy: Int): List<Pair<Int, Int>> {
        val steps = max(max((abs(dx) + 126) / 127, (abs(dy) + 126) / 127), 1)
        val result = ArrayList<Pair<Int, Int>>(steps)
        var remainingX = dx
        var remainingY = dy
        for (left in steps downTo 1) {
            val stepX = remainingX / left
            val stepY = remainingY / left
            result += stepX to stepY
            remainingX -= stepX
            remainingY -= stepY
        }
        return result
    }
}
