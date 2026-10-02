package dev.motonav.simulator.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMathTest {
    private val straight = MeasuredRoute(
        RouteGeometry(listOf(PointMetres(0.0, 0.0), PointMetres(0.0, 500.0)), emptyList()),
    )

    @Test fun `slices exactly 100 150 and 200 metres`() {
        listOf(100.0, 150.0, 200.0).forEach { lookahead ->
            val slice = straight.slice(25.0, lookahead)
            assertPoint(0.0, 25.0, slice.first())
            assertPoint(0.0, 25.0 + lookahead, slice.last())
        }
    }

    @Test fun `slice interpolates both endpoints and keeps corners`() {
        val measured = MeasuredRoute(
            RouteGeometry(
                listOf(PointMetres(0.0, 0.0), PointMetres(0.0, 80.0), PointMetres(60.0, 80.0)),
                emptyList(),
            ),
        )
        val slice = measured.slice(20.0, 100.0)
        assertEquals(3, slice.size)
        assertPoint(0.0, 20.0, slice[0])
        assertPoint(0.0, 80.0, slice[1])
        assertPoint(40.0, 80.0, slice[2])
    }

    @Test fun `projection reports segment fraction and cumulative progress`() {
        val progress = straight.project(PointMetres(8.0, 123.0))
        assertEquals(0, progress.segmentIndex)
        assertEquals(0.246, progress.segmentFraction, 1e-9)
        assertEquals(123.0, progress.routeDistanceMetres, 1e-9)
        assertPoint(0.0, 123.0, progress.projected)
    }

    @Test fun `heading rotates onto a turn at the corner`() {
        val measured = MeasuredRoute(
            RouteGeometry(
                listOf(PointMetres(0.0, 0.0), PointMetres(0.0, 100.0), PointMetres(80.0, 100.0)),
                emptyList(),
            ),
        )
        assertPoint(0.0, 1.0, measured.forwardHeading(95.0))
        assertPoint(1.0, 0.0, measured.forwardHeading(100.0))
    }

    @Test fun `duplicates short routes loops and consecutive junctions stay finite`() {
        val duplicate = MeasuredRoute(
            RouteGeometry(listOf(PointMetres(0.0, 0.0), PointMetres(0.0, 0.0), PointMetres(0.0, 5.0)), emptyList()),
        )
        assertEquals(5.0, duplicate.totalDistance, 0.0)
        assertPoint(0.0, 5.0, duplicate.slice(0.0, 150.0).last())

        val loop = MeasuredRoute(
            RouteGeometry(
                listOf(PointMetres(0.0, 0.0), PointMetres(0.0, 10.0), PointMetres(10.0, 10.0), PointMetres(0.0, 0.0)),
                emptyList(),
            ),
        )
        assertTrue(loop.project(PointMetres(0.0, 0.0), loop.totalDistance).routeDistanceMetres > 20.0)

        val consecutive = MeasuredRoute(Fixtures.all[2].route).slice(0.0, 200.0)
        assertTrue(consecutive.contains(PointMetres(0.0, 70.0)))
        assertTrue(consecutive.contains(PointMetres(55.0, 70.0)))
        assertTrue(consecutive.contains(PointMetres(55.0, 125.0)))
    }

    private fun assertPoint(x: Double, y: Double, actual: PointMetres) {
        assertEquals(x, actual.x, 1e-6)
        assertEquals(y, actual.y, 1e-6)
    }
}
