package io.github.mo3bdlaa.tvmouse

import io.github.mo3bdlaa.tvmouse.update.Versions
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test fun comparesVersions() {
        assertTrue(Versions.isNewer("1.0.1", "1.0.0"))
        assertTrue(Versions.isNewer("v1.10.0", "1.9.9"))
        assertTrue(Versions.isNewer("2.0.0", "1.99.99"))
        assertTrue(Versions.isNewer("0.0.1", "0.0.0-dev"))
        assertFalse(Versions.isNewer("1.0.0", "1.0.0"))
        assertFalse(Versions.isNewer("1.0.0", "1.0.1"))
        assertFalse(Versions.isNewer("1.0", "1.0.0"))
    }
}
