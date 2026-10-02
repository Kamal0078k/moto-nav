package dev.motonav.simulator.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRendererTest {
    @Test fun `default lookahead is 150 metres and marker remains fixed`() {
        val snapshot = NavigationEngine(Fixtures.all.first()).snapshot()
        val rendered = RouteRenderer().render(snapshot)
        assertEquals(150.0, rendered.lookaheadMetres, 0.0)
        assertEquals(64, rendered.markerX)
        assertEquals(96, rendered.markerY)
        assertTrue(rendered.points.first().startsPolyline)
        assertEquals(33026.0, rendered.points.first().x.toDouble(), 1.0)
        assertEquals(32768.0, rendered.points.first().y.toDouble(), 1.0)
    }

    @Test fun `east phone heading rotates an eastbound route toward the top`() {
        val route = RouteGeometry(
            listOf(PointMetres(0.0, 0.0), PointMetres(0.0, 100.0), PointMetres(100.0, 100.0)),
            listOf(ManeuverEvent(200.0, Maneuver.ARRIVE)),
        )
        val snapshot = NavigationSnapshot(
            NavigationState.NAVIGATING, route, LocationSample(PointMetres(0.0, 100.0), 0),
            100.0, Maneuver.ARRIVE, 100.0, 100.0, "corner",
            headingDegrees = 90.0,
        )
        val rendered = RouteRenderer().render(snapshot)
        assertEquals(1.0, rendered.heading.x, 1e-9)
        assertEquals(0.0, rendered.heading.y, 1e-9)
        assertEquals(rendered.points.first().x.toDouble(), rendered.points.last().x.toDouble(), 1.0)
        assertTrue(rendered.points.last().y < rendered.points.first().y)
    }

    @Test fun `centred heading-up viewport retains a winding lookahead`() {
        val route = RouteGeometry(
            listOf(
                PointMetres(0.0, 0.0), PointMetres(0.0, 20.0), PointMetres(0.0, -100.0),
                PointMetres(100.0, -100.0), PointMetres(100.0, 40.0),
            ),
            listOf(ManeuverEvent(380.0, Maneuver.ARRIVE)),
        )
        val snapshot = NavigationSnapshot(
            NavigationState.NAVIGATING, route, LocationSample(PointMetres(0.0, 0.0), 0),
            0.0, Maneuver.ARRIVE, 380.0, 380.0, "clipped",
        )
        val rendered = RouteRenderer().render(snapshot, 400.0)
        assertTrue(rendered.points.isNotEmpty())
        assertTrue(rendered.points.all { it.x in 0..65535 && it.y in 0..65535 })
    }

    @Test fun `live heading rotates the map around the fixed marker`() {
        val snapshot = NavigationEngine(Fixtures.all.first()).snapshot().copy(
            lookaheadMetres = 120.0,
        )
        val north = RouteRenderer().render(snapshot.copy(headingDegrees = 0.0))
        val east = RouteRenderer().render(snapshot.copy(headingDegrees = 90.0))
        assertEquals(120.0, east.lookaheadMetres, 0.0)
        assertEquals(1.0, east.heading.x, 1e-9)
        assertEquals(0.0, east.heading.y, 1e-9)
        assertTrue(north.points.last().y < north.points.first().y)
        assertTrue(east.points.last().x < east.points.first().x)
        assertEquals(east.points.first().y.toDouble(), east.points.last().y.toDouble(), 1.0)
    }
}
