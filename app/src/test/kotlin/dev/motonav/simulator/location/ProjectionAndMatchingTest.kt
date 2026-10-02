package dev.motonav.simulator.location

import dev.motonav.simulator.navigation.RouteContentSource
import dev.motonav.simulator.routing.GeoPoint
import dev.motonav.simulator.routing.ProviderManeuver
import dev.motonav.simulator.routing.RoadRoute
import dev.motonav.simulator.routing.RoadRouteStep
import dev.motonav.simulator.routing.RouteProjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionAndMatchingTest {
    @Test fun `projection has metre scale across equator and longitude sign changes`() {
        val north = RouteProjection.distanceMetres(GeoPoint(-0.001, 0.0), GeoPoint(0.001, 0.0))
        val east = RouteProjection.distanceMetres(GeoPoint(0.0, -0.001), GeoPoint(0.0, 0.001))
        assertEquals(222.39, north, 0.5)
        assertEquals(222.39, east, 0.5)
        val projected = RouteProjection.toMetres(GeoPoint(-0.001, -0.001), GeoPoint(0.001, 0.001))
        assertTrue(projected.x > 200.0)
        assertTrue(projected.y > 200.0)
    }

    @Test fun `reliable heading avoids jumping to a closer parallel road`() {
        val origin = GeoPoint(0.0, 0.0)
        val geometry = points(origin, listOf(0.0 to 0.0, 0.0 to 100.0, 20.0 to 100.0, 20.0 to 0.0))
        val index = GeographicRouteIndex(route(geometry))
        assertEquals(0.0, index.segments[0].headingDegrees, 0.5)
        assertEquals(90.0, index.segments[1].headingDegrees, 0.5)
        val sample = sample(origin, east = 18.0, north = 50.0, elapsed = 1_000, bearing = 0.0)
        val match = requireNotNull(index.bestCandidate(sample, priorProgressMetres = null, searchAll = true))
        assertEquals(0, match.segmentIndex)
    }

    @Test fun `prior progress disambiguates a loop crossing`() {
        val origin = GeoPoint(0.0, 0.0)
        val geometry = points(origin, listOf(
            0.0 to 0.0, 0.0 to 100.0, 100.0 to 100.0, 100.0 to 0.0,
            0.0 to 0.0, 0.0 to -100.0,
        ))
        val index = GeographicRouteIndex(route(geometry))
        val crossing = sample(origin, 0.0, 0.0, 10_000, bearing = 180.0)
        val match = requireNotNull(index.bestCandidate(crossing, priorProgressMetres = 390.0, searchAll = false))
        assertTrue(match.routeDistanceMetres > 350.0)
    }

    companion object {
        fun route(geometry: List<GeoPoint>, maneuverStarts: List<Int> = listOf(0, geometry.lastIndex)): RoadRoute {
            val starts = maneuverStarts.distinct().sorted()
            val steps = starts.mapIndexed { index, start ->
                RoadRouteStep(
                    index,
                    start,
                    if (index + 1 < starts.size) starts[index + 1] else geometry.lastIndex,
                    if (index == starts.lastIndex) ProviderManeuver.ARRIVE else ProviderManeuver.TURN_RIGHT,
                    "step $index",
                    1,
                    1_000,
                )
            }
            return RoadRoute(geometry, steps, 1, 1_000, "Google Maps", RouteContentSource.GOOGLE_MAPS, 1)
        }

        fun points(origin: GeoPoint, offsets: List<Pair<Double, Double>>): List<GeoPoint> = offsets.map { (east, north) ->
            TraceFixture("points", origin, listOf(RelativeTraceSample(east, north, 0, 1.0))).geoSamples().single()
                .let { GeoPoint(it.latitude, it.longitude) }
        }

        fun sample(
            origin: GeoPoint,
            east: Double,
            north: Double,
            elapsed: Long,
            bearing: Double? = null,
            speed: Double? = 10.0,
            accuracy: Double = 2.0,
        ): GeoLocationSample = TraceFixture(
            "sample", origin, listOf(RelativeTraceSample(east, north, elapsed, accuracy, speed, bearing)),
        ).geoSamples().single()
    }
}
