package io.github.mo3bdlaa.tvmouse

import io.github.mo3bdlaa.tvmouse.hid.HidDescriptor
import io.github.mo3bdlaa.tvmouse.hid.HidReports
import io.github.mo3bdlaa.tvmouse.hid.Key
import io.github.mo3bdlaa.tvmouse.hid.KeyMap
import io.github.mo3bdlaa.tvmouse.hid.KeyModifier
import io.github.mo3bdlaa.tvmouse.hid.KeyStroke
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KeyMapTest {
    @Test fun lettersAndDigits() {
        assertEquals(KeyStroke(KeyModifier.NONE, 0x04), KeyMap.strokeFor('a'))
        assertEquals(KeyStroke(KeyModifier.NONE, 0x1D), KeyMap.strokeFor('z'))
        assertEquals(KeyStroke(KeyModifier.SHIFT, 0x04), KeyMap.strokeFor('A'))
        assertEquals(KeyStroke(KeyModifier.NONE, 0x1E), KeyMap.strokeFor('1'))
        assertEquals(KeyStroke(KeyModifier.NONE, 0x27), KeyMap.strokeFor('0'))
    }

    @Test fun symbols() {
        assertEquals(KeyStroke(KeyModifier.SHIFT, 0x1E), KeyMap.strokeFor('!'))
        assertEquals(KeyStroke(KeyModifier.SHIFT, 0x1F), KeyMap.strokeFor('@'))
        assertEquals(KeyStroke(KeyModifier.SHIFT, 0x27), KeyMap.strokeFor(')'))
        assertEquals(KeyStroke(KeyModifier.NONE, 0x38), KeyMap.strokeFor('/'))
        assertEquals(KeyStroke(KeyModifier.SHIFT, 0x38), KeyMap.strokeFor('?'))
        assertEquals(KeyStroke(KeyModifier.NONE, Key.ENTER), KeyMap.strokeFor('\n'))
        assertEquals(KeyStroke(KeyModifier.NONE, Key.SPACE), KeyMap.strokeFor(' '))
    }

    @Test fun unsupportedCharacters() {
        assertNull(KeyMap.strokeFor('ع'))
        assertNull(KeyMap.strokeFor('é'))
    }

    @Test fun normalizesSmartPunctuation() {
        assertEquals("it's \"ok\" - yes...", KeyMap.normalize("it’s “ok” – yes…"))
        assertEquals("a\nb", KeyMap.normalize("a\r\nb"))
    }

    @Test fun everyAsciiPrintableIsTypeable() {
        for (c in ' '..'~') assertTrue("'$c' should be typeable", KeyMap.strokeFor(c) != null)
    }
}

class HidReportsTest {
    @Test fun keyboardReport() {
        assertArrayEquals(byteArrayOf(2, 0, 4, 0, 0, 0, 0, 0), HidReports.keyboard(KeyModifier.SHIFT, 0x04))
        assertEquals(HidDescriptor.KEYBOARD_SIZE, HidReports.keyboard(0).size)
    }

    @Test fun mouseReportClampsValues() {
        assertArrayEquals(byteArrayOf(1, 127, -127, 3, -2), HidReports.mouse(1, 500, -500, 3, -2))
        assertEquals(HidDescriptor.MOUSE_SIZE, HidReports.mouse(0).size)
    }

    @Test fun consumerReportIsLittleEndian() {
        assertArrayEquals(byteArrayOf(0x24, 0x02), HidReports.consumer(0x224))
    }

    @Test fun splitMovementKeepsTotalsAndLimits() {
        for ((dx, dy) in listOf(0 to 0, 5 to -3, 300 to 0, -1000 to 250, 127 to 128)) {
            val steps = HidReports.splitMovement(dx, dy)
            assertEquals(dx, steps.sumOf { it.first })
            assertEquals(dy, steps.sumOf { it.second })
            assertTrue(steps.all { abs(it.first) <= 127 && abs(it.second) <= 127 })
        }
    }
}
