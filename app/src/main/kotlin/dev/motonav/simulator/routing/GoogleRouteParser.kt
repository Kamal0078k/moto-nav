package dev.motonav.simulator.routing

import dev.motonav.simulator.navigation.RouteContentSource
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

fun interface RouteRevisionSource {
    fun nextRevision(): Long
}

class MonotonicRouteRevisionSource(initialRevision: Long = 0) : RouteRevisionSource {
    private val value = AtomicLong(initialRevision)
    override fun nextRevision(): Long = value.incrementAndGet()
}

class GoogleRouteParser(
    private val revisions: RouteRevisionSource = MonotonicRouteRevisionSource(),
) {
    fun parse(responseBody: String): RoadRoute = try {
        val root = StrictJsonParser(responseBody).parse().objectOrThrow()
        val routes = root["routes"].arrayOrThrow()
        if (routes.isEmpty()) throw RoutingException(RoutingFailure.NoRoute)
        require(routes.size == 1) { "Expected exactly one route" }
        val route = routes.single().objectOrThrow()
        val totalMetres = route["distanceMeters"].nonNegativeLong()
        val totalDuration = parseDuration(route["duration"].stringOrThrow())
        val routeGeometry = PolylineCodec.decode(
            route["polyline"].objectOrThrow()["encodedPolyline"].stringOrThrow(),
        )
        require(routeGeometry.size >= 2) { "Missing route geometry" }

        val legs = route["legs"].arrayOrThrow()
        require(legs.isNotEmpty()) { "Missing route legs" }
        val rawSteps = legs.flatMap { leg -> leg.objectOrThrow()["steps"].arrayOrThrow() }
        val legEnds = legs.mapNotNull { leg -> leg.objectOrThrow().optional("endLocation")?.let(::parseLocation) }
            .takeIf { it.size == legs.size }.orEmpty()
        require(rawSteps.isNotEmpty()) { "Missing route steps" }

        val geometry = mutableListOf<GeoPoint>()
        val steps = rawSteps.mapIndexed { index, raw ->
            val step = raw.objectOrThrow()
            step.optional("travelMode")?.let {
                require(it.stringOrThrow() == "TWO_WHEELER") { "Unexpected step travel mode" }
            }
            val metres = step["distanceMeters"].nonNegativeLong()
            val duration = parseDuration(step["staticDuration"].stringOrThrow())
            val start = parseLocation(step["startLocation"])
            val end = parseLocation(step["endLocation"])
            val decoded = PolylineCodec.decode(
                step["polyline"].objectOrThrow()["encodedPolyline"].stringOrThrow(),
            )
            require(decoded.size >= 2) { "Missing step geometry" }
            require(distanceMetres(decoded.first(), start) <= ENDPOINT_TOLERANCE_METRES)
            require(distanceMetres(decoded.last(), end) <= ENDPOINT_TOLERANCE_METRES)

            val startIndex = if (geometry.isEmpty()) {
                geometry += decoded
                0
            } else {
                val duplicateBoundary = geometry.last() == decoded.first()
                val result = if (duplicateBoundary) geometry.lastIndex else geometry.size
                geometry += if (duplicateBoundary) decoded.drop(1) else decoded
                result
            }
            require(geometry.size <= PolylineCodec.DEFAULT_MAX_POINTS) { "Route exceeds point limit" }
            val instruction = step.optional("navigationInstruction")?.objectOrThrow()
            RoadRouteStep(
                index = index,
                geometryStartIndex = startIndex,
                geometryEndIndex = geometry.lastIndex,
                maneuver = parseManeuver(instruction?.optional("maneuver")?.stringOrThrow()),
                instruction = instruction?.optional("instructions")?.stringOrThrow().orEmpty(),
                metres = metres,
                durationMillis = duration,
            )
        }

        require(geometry.size >= 2)
        require(distanceMetres(routeGeometry.first(), geometry.first()) <= ROUTE_TOLERANCE_METRES)
        require(distanceMetres(routeGeometry.last(), geometry.last()) <= ROUTE_TOLERANCE_METRES)
        require(geometriesAgree(routeGeometry, geometry)) { "Route and step geometry are inconsistent" }
        require(steps.sumOf { it.metres } <= totalMetres + DISTANCE_SUM_TOLERANCE_METRES) {
            "Step distance exceeds route summary"
        }

        RoadRoute(
            geometry = geometry,
            steps = steps,
            totalMetres = totalMetres,
            totalDurationMillis = totalDuration,
            attribution = "Google Maps",
            source = RouteContentSource.GOOGLE_MAPS,
            revision = revisions.nextRevision(),
            legEnds = legEnds,
        )
    } catch (failure: RoutingException) {
        throw failure
    } catch (failure: RuntimeException) {
        throw RoutingException(RoutingFailure.MalformedResponse, failure)
    }

    private fun parseLocation(value: JsonValue): GeoPoint {
        val latLng = value.objectOrThrow()["latLng"].objectOrThrow()
        return GeoPoint(latLng["latitude"].numberOrThrow(), latLng["longitude"].numberOrThrow())
    }

    private fun parseManeuver(value: String?): ProviderManeuver = when (value) {
        null, "MANEUVER_UNSPECIFIED" -> ProviderManeuver.UNKNOWN
        "DEPART" -> ProviderManeuver.DEPART
        "STRAIGHT" -> ProviderManeuver.STRAIGHT
        "TURN_SLIGHT_LEFT" -> ProviderManeuver.TURN_SLIGHT_LEFT
        "TURN_LEFT" -> ProviderManeuver.TURN_LEFT
        "TURN_SHARP_LEFT" -> ProviderManeuver.TURN_SHARP_LEFT
        "TURN_SLIGHT_RIGHT" -> ProviderManeuver.TURN_SLIGHT_RIGHT
        "TURN_RIGHT" -> ProviderManeuver.TURN_RIGHT
        "TURN_SHARP_RIGHT" -> ProviderManeuver.TURN_SHARP_RIGHT
        "UTURN_LEFT" -> ProviderManeuver.U_TURN_LEFT
        "UTURN_RIGHT" -> ProviderManeuver.U_TURN_RIGHT
        "FORK_LEFT" -> ProviderManeuver.FORK_LEFT
        "FORK_RIGHT" -> ProviderManeuver.FORK_RIGHT
        "MERGE" -> ProviderManeuver.MERGE
        "RAMP_LEFT" -> ProviderManeuver.RAMP_LEFT
        "RAMP_RIGHT" -> ProviderManeuver.RAMP_RIGHT
        "FERRY" -> ProviderManeuver.FERRY
        "FERRY_TRAIN" -> ProviderManeuver.FERRY_TRAIN
        "ROUNDABOUT_LEFT" -> ProviderManeuver.ROUNDABOUT_LEFT
        "ROUNDABOUT_RIGHT" -> ProviderManeuver.ROUNDABOUT_RIGHT
        "NAME_CHANGE" -> ProviderManeuver.NAME_CHANGE
        "ARRIVE" -> ProviderManeuver.ARRIVE
        else -> ProviderManeuver.UNKNOWN
    }

    private fun geometriesAgree(route: List<GeoPoint>, steps: List<GeoPoint>): Boolean {
        fun sampled(points: List<GeoPoint>): List<GeoPoint> {
            val stride = (points.size / MAX_CROSS_CHECK_SAMPLES).coerceAtLeast(1)
            return points.filterIndexed { index, _ -> index % stride == 0 }.let {
                if (it.last() == points.last()) it else it + points.last()
            }
        }
        fun closeToPolyline(point: GeoPoint, polyline: List<GeoPoint>): Boolean =
            polyline.zipWithNext().minOf { (start, end) ->
                distanceToSegmentMetres(point, start, end)
            } <= GEOMETRY_TOLERANCE_METRES
        return sampled(route).all { closeToPolyline(it, steps) } &&
            sampled(steps).all { closeToPolyline(it, route) }
    }

    private fun parseDuration(value: String): Long {
        val match = DURATION.matchEntire(value) ?: error("Invalid duration")
        val seconds = match.groupValues[1].toLong()
        val fraction = match.groupValues[2].padEnd(9, '0').take(9).toLongOrNull() ?: 0
        return Math.addExact(Math.multiplyExact(seconds, 1_000), fraction / 1_000_000)
    }

    private fun JsonValue.nonNegativeLong(): Long {
        val number = numberOrThrow()
        require(number >= 0 && number <= Long.MAX_VALUE && number % 1.0 == 0.0)
        return number.toLong()
    }

    companion object {
        private val DURATION = Regex("([0-9]+)(?:\\.([0-9]{1,9}))?s")
        private const val ENDPOINT_TOLERANCE_METRES = 5.0
        private const val ROUTE_TOLERANCE_METRES = 30.0
        private const val DISTANCE_SUM_TOLERANCE_METRES = 100L
        private const val MAX_CROSS_CHECK_SAMPLES = 256
        private const val GEOMETRY_TOLERANCE_METRES = 50.0
    }
}

internal fun distanceMetres(a: GeoPoint, b: GeoPoint): Double {
    val latitudeDelta = Math.toRadians(b.latitude - a.latitude)
    val longitudeDelta = Math.toRadians(b.longitude - a.longitude)
    val latitudeA = Math.toRadians(a.latitude)
    val latitudeB = Math.toRadians(b.latitude)
    val haversine = sin(latitudeDelta / 2).let { it * it } +
        cos(latitudeA) * cos(latitudeB) * sin(longitudeDelta / 2).let { it * it }
    return 2 * 6_371_000.0 * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
}

private fun distanceToSegmentMetres(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
    val latitudeScale = Math.PI * 6_371_000.0 / 180.0
    val longitudeScale = latitudeScale * cos(Math.toRadians(point.latitude))
    fun longitudeDelta(from: Double, to: Double): Double {
        var delta = to - from
        if (delta > 180.0) delta -= 360.0
        if (delta < -180.0) delta += 360.0
        return delta
    }

    val startX = longitudeDelta(point.longitude, start.longitude) * longitudeScale
    val startY = (start.latitude - point.latitude) * latitudeScale
    val endX = longitudeDelta(point.longitude, end.longitude) * longitudeScale
    val endY = (end.latitude - point.latitude) * latitudeScale
    val segmentX = endX - startX
    val segmentY = endY - startY
    val lengthSquared = segmentX * segmentX + segmentY * segmentY
    if (lengthSquared == 0.0) return kotlin.math.hypot(startX, startY)
    val fraction = (-(startX * segmentX + startY * segmentY) / lengthSquared).coerceIn(0.0, 1.0)
    return kotlin.math.hypot(startX + fraction * segmentX, startY + fraction * segmentY)
}
