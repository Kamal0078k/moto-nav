package dev.motonav.simulator.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleRouteParserTest {
    @Test fun `parses ordered steps removes only duplicate boundaries and preserves maneuvers`() {
        val route = GoogleRouteParser(RouteRevisionSource { 7 }).parse(response())
        assertEquals(3, route.geometry.size)
        assertEquals(2, route.steps.size)
        assertEquals(0, route.steps[0].geometryStartIndex)
        assertEquals(1, route.steps[0].geometryEndIndex)
        assertEquals(1, route.steps[1].geometryStartIndex)
        assertEquals(2, route.steps[1].geometryEndIndex)
        assertEquals(ProviderManeuver.TURN_LEFT, route.steps[0].maneuver)
        assertEquals(ProviderManeuver.UNKNOWN, route.steps[1].maneuver)
        assertEquals("Turn left", route.steps[0].instruction)
        assertEquals("", route.steps[1].instruction)
        assertEquals(7, route.revision)
        assertEquals("Google Maps", route.attribution)
    }

    @Test fun `projection retains point and step mappings`() {
        val projected = RouteProjection.project(GoogleRouteParser().parse(response()))
        assertEquals(listOf(0, 1, 2), projected.projectedPointToSourcePoint)
        assertEquals(listOf(0, 1, 1), projected.projectedPointToStep)
        assertEquals(2, projected.routeGeometry.maneuvers.size)
        assertTrue(projected.routeGeometry.points[1].distanceTo(projected.routeGeometry.points[0]) > 0)
    }

    @Test fun `roundabout and U turn variants remain distinguishable`() {
        val roundabout = GoogleRouteParser().parse(response().replace("TURN_LEFT", "ROUNDABOUT_RIGHT"))
        val uTurn = GoogleRouteParser().parse(response().replace("TURN_LEFT", "UTURN_LEFT"))
        assertEquals(ProviderManeuver.ROUNDABOUT_RIGHT, roundabout.steps.first().maneuver)
        assertEquals(ProviderManeuver.U_TURN_LEFT, uTurn.steps.first().maneuver)
        assertEquals(dev.motonav.simulator.navigation.Maneuver.RIGHT, roundabout.steps.first().maneuver.oledManeuver())
        assertEquals(dev.motonav.simulator.navigation.Maneuver.LEFT, uTurn.steps.first().maneuver.oledManeuver())
    }

    @Test fun `accepts equivalent route and step geometry sampled at different densities`() {
        val points = listOf(
            GeoPoint(12.0, 77.0),
            GeoPoint(12.001, 77.001),
            GeoPoint(12.002, 77.002),
        )
        val body = """{"routes":[{"distanceMeters":320,"duration":"20s","polyline":{"encodedPolyline":"${encode(listOf(points.first(), points.last()))}"},"legs":[{"steps":[{"distanceMeters":320,"staticDuration":"20s","polyline":{"encodedPolyline":"${encode(points)}"},"startLocation":{"latLng":{"latitude":12.0,"longitude":77.0}},"endLocation":{"latLng":{"latitude":12.002,"longitude":77.002}},"travelMode":"TWO_WHEELER","navigationInstruction":{"maneuver":"NAME_CHANGE","instructions":"Continue"}}]}]}]}"""

        val route = GoogleRouteParser().parse(body)

        assertEquals(3, route.geometry.size)
        assertEquals(ProviderManeuver.NAME_CHANGE, route.steps.single().maneuver)
    }

    @Test fun `rejects missing geometry malformed duration extra routes mode and inconsistency`() {
        assertMalformed(response().replace("\"0.5s\"", "\"later\""))
        assertMalformed(response().replace("\"distanceMeters\":30,", ""))
        assertMalformed(response().replace("\"encodedPolyline\":\"${encode(STEP_ONE)}\"", "\"encodedPolyline\":\"\""))
        assertMalformed(response().replace("\"routes\":[", "\"routes\":[${routeObject()},"))
        assertMalformed(response().replaceFirst("TWO_WHEELER", "DRIVE"))
        assertMalformed(response().replace("\"latitude\":12.0002", "\"latitude\":13.0"))
        val noRoute = assertThrows(RoutingException::class.java) {
            GoogleRouteParser().parse("{\"routes\":[]}")
        }
        assertEquals(RoutingFailure.NoRoute, noRoute.failure)
    }

    private fun assertMalformed(body: String) {
        val error = assertThrows(RoutingException::class.java) { GoogleRouteParser().parse(body) }
        assertEquals(RoutingFailure.MalformedResponse, error.failure)
    }

    private fun response() = "{\"routes\":[${routeObject()}]}"

    private fun routeObject() = """
        {"distanceMeters":30,"duration":"2.25s","polyline":{"encodedPolyline":"${encode(ALL)}"},"legs":[{"steps":[
        {"distanceMeters":10,"staticDuration":"0.5s","polyline":{"encodedPolyline":"${encode(STEP_ONE)}"},"startLocation":{"latLng":{"latitude":12.0,"longitude":77.0}},"endLocation":{"latLng":{"latitude":12.0001,"longitude":77.0001}},"travelMode":"TWO_WHEELER","navigationInstruction":{"maneuver":"TURN_LEFT","instructions":"Turn left"}},
        {"distanceMeters":20,"staticDuration":"1.75s","polyline":{"encodedPolyline":"${encode(STEP_TWO)}"},"startLocation":{"latLng":{"latitude":12.0001,"longitude":77.0001}},"endLocation":{"latLng":{"latitude":12.0002,"longitude":77.0002}},"travelMode":"TWO_WHEELER"}
        ]}]}
    """.trimIndent()

    companion object {
        private val STEP_ONE = listOf(GeoPoint(12.0, 77.0), GeoPoint(12.0001, 77.0001))
        private val STEP_TWO = listOf(GeoPoint(12.0001, 77.0001), GeoPoint(12.0002, 77.0002))
        private val ALL = STEP_ONE + STEP_TWO.drop(1)

        private fun encode(points: List<GeoPoint>): String {
            var oldLatitude = 0L
            var oldLongitude = 0L
            return buildString {
                points.forEach { point ->
                    val latitude = kotlin.math.round(point.latitude * 100_000).toLong()
                    val longitude = kotlin.math.round(point.longitude * 100_000).toLong()
                    appendComponent(latitude - oldLatitude)
                    appendComponent(longitude - oldLongitude)
                    oldLatitude = latitude
                    oldLongitude = longitude
                }
            }
        }

        private fun StringBuilder.appendComponent(delta: Long) {
            var value = if (delta < 0) (delta shl 1).inv() else delta shl 1
            while (value >= 0x20) {
                append(((0x20 or (value and 0x1f).toInt()) + 63).toChar())
                value = value shr 5
            }
            append((value.toInt() + 63).toChar())
        }
    }
}
