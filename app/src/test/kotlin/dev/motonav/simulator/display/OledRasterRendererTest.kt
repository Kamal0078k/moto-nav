package dev.motonav.simulator.display

import dev.motonav.simulator.navigation.Fixtures
import dev.motonav.simulator.navigation.Maneuver
import dev.motonav.simulator.navigation.NavigationEngine
import dev.motonav.simulator.navigation.RouteRenderer
import dev.motonav.simulator.navigation.RouteContentSource
import java.security.MessageDigest
import java.util.Properties
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OledRasterRendererTest {
    @Test fun `metre and kilometre transition matches reference rasters`() {
        val base = NavigationEngine(Fixtures.all.first()).snapshot()
        val renderer = RouteRenderer()
        val metreSnapshot = base.copy(metresToManeuver = 999.0)
        val kilometreSnapshot = base.copy(metresToManeuver = 1000.0)
        val references = Properties().apply {
            load(requireNotNull(OledRasterRendererTest::class.java.getResourceAsStream("/oled-reference-sha256.properties")))
        }
        assertEquals(references.getProperty("999m"), digest(OledRasterRenderer.render(metreSnapshot, renderer.render(metreSnapshot))))
        assertEquals(references.getProperty("1000m"), digest(OledRasterRenderer.render(kilometreSnapshot, renderer.render(kilometreSnapshot))))
    }

    @Test fun `distance formatter switches and rounds like firmware`() {
        assertEquals("999 m", OledRasterRenderer.formatDistance(999))
        assertEquals("1.0 km", OledRasterRenderer.formatDistance(1000))
        assertEquals("1.2 km", OledRasterRenderer.formatDistance(1150))
    }

    @Test fun `route source does not reserve a bottom attribution band`() {
        val base = NavigationEngine(Fixtures.all.first()).snapshot()
        val google = base.copy(contentSource = RouteContentSource.GOOGLE_MAPS)
        val syntheticFrame = OledRasterRenderer.render(base, RouteRenderer().render(base))
        val googleFrame = OledRasterRenderer.render(google, RouteRenderer().render(google))
        assertArrayEquals(syntheticFrame.packedPages(), googleFrame.packedPages())
    }

    @Test fun `centred marker stays pointed up for every phone heading`() {
        val base = NavigationEngine(Fixtures.all.first()).snapshot()
        val route = RouteRenderer().render(base).copy(points = emptyList())
        val north = OledRasterRenderer.render(base.copy(headingDegrees = 0.0), route)
        val east = OledRasterRenderer.render(base.copy(headingDegrees = 90.0), route)

        assertTrue(north.isLit(64, 89))
        assertTrue(east.isLit(64, 89))
        assertArrayEquals(north.packedPages(), east.packedPages())
    }

    @Test fun `left and right turn symbols are exact aligned mirrors`() {
        val base = NavigationEngine(Fixtures.all.first()).snapshot()
        val route = RouteRenderer().render(base).copy(points = emptyList())
        val left = OledRasterRenderer.render(base.copy(maneuver = Maneuver.LEFT), route)
        val right = OledRasterRenderer.render(base.copy(maneuver = Maneuver.RIGHT), route)

        for (y in 4..27) for (x in 2..25) {
            assertEquals("icon pixel ($x,$y)", left.isLit(x, y), right.isLit(27 - x, y))
        }
    }

    private fun digest(frame: MonochromeFrame): String = MessageDigest.getInstance("SHA-256")
        .digest(frame.packedPages())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
