package dev.motonav.simulator.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PolylineCodecTest {
    @Test fun `decodes positive and negative deltas at five digit precision`() {
        val points = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].latitude, 0.000001)
        assertEquals(-120.2, points[0].longitude, 0.000001)
        assertEquals(43.252, points[2].latitude, 0.000001)
        assertEquals(-126.453, points[2].longitude, 0.000001)
    }

    @Test fun `empty truncation overflow range and point limit are strict`() {
        assertEquals(emptyList<GeoPoint>(), PolylineCodec.decode(""))
        assertThrows(IllegalArgumentException::class.java) { PolylineCodec.decode("_p~iF") }
        assertThrows(IllegalArgumentException::class.java) { PolylineCodec.decode("~~~~~~~~~~~~~~A?") }
        assertThrows(IllegalArgumentException::class.java) { PolylineCodec.decode("_mljP?", 10) }
        assertThrows(IllegalArgumentException::class.java) { PolylineCodec.decode("??AA", 1) }
    }
}
