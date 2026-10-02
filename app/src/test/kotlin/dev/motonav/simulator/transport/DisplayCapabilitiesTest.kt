package dev.motonav.simulator.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayCapabilitiesTest {
    @Test fun `verified firmware capability bytes decode and validate`() {
        val capabilities = DisplayCapabilities.decode(
            byteArrayOf(0x03, 0x80.toByte(), 0x00, 0xa0.toByte(), 0x00, 0x20, 0x0a, 0x01, 0xf7.toByte(), 0x01),
        )
        capabilities.requireCompatible()
        assertEquals(128, capabilities.screenWidth)
        assertEquals(160, capabilities.screenHeight)
        assertEquals(32, capabilities.maximumPoints)
        assertEquals(266, capabilities.maximumFrameBytes)
        assertTrue(capabilities.supportsRemainingDistance)
        assertTrue(capabilities.supportsPolylineBreaks)
        assertTrue(capabilities.supportsDestinationMarkers)
        assertFalse(capabilities.supportsGoogleMapsAttribution)
        assertTrue(capabilities.supportsLiveLocationStates)
        assertTrue(capabilities.supportsHeadingUpMap)
        assertTrue(capabilities.supportsTextScreens)
        assertTrue(capabilities.supportsCallAlerts)
        assertTrue(capabilities.supportsClock)
        capabilities.requireLiveLocationStates()
    }

    @Test fun `truncated unsupported and incomplete capabilities are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { DisplayCapabilities.decode(ByteArray(8)) }
        assertThrows(IllegalArgumentException::class.java) {
            DisplayCapabilities.decode(byteArrayOf(1, -128, 0, -96, 0, 32, 10, 1, -1, 1)).requireCompatible()
        }
        assertThrows(IllegalArgumentException::class.java) {
            DisplayCapabilities.decode(byteArrayOf(3, -128, 0, -96, 0, 32, 10, 1, 3)).requireCompatible()
        }
        assertThrows(IllegalArgumentException::class.java) {
            DisplayCapabilities.decode(byteArrayOf(3, -128, 0, -96, 0, 32, 10, 1, 0x27))
                .requireGoogleMapsAttribution()
        }
        assertThrows(IllegalArgumentException::class.java) {
            DisplayCapabilities.decode(byteArrayOf(3, -128, 0, -96, 0, 32, 10, 1, 0x2f))
                .requireLiveLocationStates()
        }
    }
}
