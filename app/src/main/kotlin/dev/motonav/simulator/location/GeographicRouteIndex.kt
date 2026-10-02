package dev.motonav.simulator.location

import dev.motonav.simulator.navigation.PointMetres
import dev.motonav.simulator.routing.GeoPoint
import dev.motonav.simulator.routing.ProjectedRoadRoute
import dev.motonav.simulator.routing.RoadRoute
import dev.motonav.simulator.routing.RouteProjection
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

data class RouteSegmentIndex(
    val index: Int,
    val start: PointMetres,
    val end: PointMetres,
    val lengthMetres: Double,
    val headingDegrees: Double,
    val cumulativeStartMetres: Double,
    val cumulativeEndMetres: Double,
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
)

data class MatchCandidate(
    val segmentIndex: Int,
    val fraction: Double,
    val routeDistanceMetres: Double,
    val distanceFromRouteMetres: Double,
    val projected: PointMetres,
    val score: Double,
)

class GeographicRouteIndex(val route: RoadRoute) {
    val projected: ProjectedRoadRoute = RouteProjection.project(route)
    val segments: List<RouteSegmentIndex>
    val totalDistanceMetres: Double

    init {
        var cumulative = 0.0
        segments = projected.routeGeometry.points.zipWithNext().mapIndexed { index, (start, end) ->
            val length = start.distanceTo(end)
            val segment = RouteSegmentIndex(
                index = index,
                start = start,
                end = end,
                lengthMetres = length,
                headingDegrees = normalizeDegrees(Math.toDegrees(atan2(end.x - start.x, end.y - start.y))),
                cumulativeStartMetres = cumulative,
                cumulativeEndMetres = cumulative + length,
                minX = minOf(start.x, end.x),
                maxX = maxOf(start.x, end.x),
                minY = minOf(start.y, end.y),
                maxY = maxOf(start.y, end.y),
            )
            cumulative += length
            segment
        }
        totalDistanceMetres = cumulative
    }

    fun point(location: GeoLocationSample): PointMetres = RouteProjection.toMetres(
        route.geometry.first(),
        GeoPoint(location.latitude, location.longitude),
    )

    fun bestCandidate(
        location: GeoLocationSample,
        priorProgressMetres: Double?,
        searchAll: Boolean,
    ): MatchCandidate? {
        val position = point(location)
        val speed = location.speedMetresPerSecond?.coerceAtLeast(0.0) ?: 0.0
        val behind = priorProgressMetres?.minus(50.0)
        val ahead = priorProgressMetres?.plus(maxOf(300.0, speed * 20.0))
        val captureRadius = maxOf(20.0, location.horizontalAccuracyMetres * 1.5).coerceAtMost(75.0)
        return segments.asSequence()
            .filter { segment -> searchAll || priorProgressMetres == null ||
                (segment.cumulativeEndMetres >= requireNotNull(behind) && segment.cumulativeStartMetres <= requireNotNull(ahead))
            }
            .filter { segment ->
                position.x in (segment.minX - captureRadius)..(segment.maxX + captureRadius) &&
                    position.y in (segment.minY - captureRadius)..(segment.maxY + captureRadius)
            }
            .map { segment -> candidate(segment, position, location, priorProgressMetres) }
            .filter { it.distanceFromRouteMetres <= captureRadius }
            .minByOrNull { it.score }
    }

    /**
     * Finds the best plausible segment without applying the normal capture radius. This is used
     * only to measure off-route evidence; navigation progress still requires [bestCandidate].
     */
    fun nearestPlausibleCandidate(
        location: GeoLocationSample,
        priorProgressMetres: Double?,
    ): MatchCandidate? {
        val position = point(location)
        val speed = location.speedMetresPerSecond?.coerceAtLeast(0.0) ?: 0.0
        val behind = priorProgressMetres?.minus(100.0)
        val ahead = priorProgressMetres?.plus(maxOf(500.0, speed * 30.0))
        val local = segments.asSequence()
            .filter { segment -> priorProgressMetres == null ||
                (segment.cumulativeEndMetres >= requireNotNull(behind) &&
                    segment.cumulativeStartMetres <= requireNotNull(ahead))
            }
            .map { segment -> candidate(segment, position, location, priorProgressMetres) }
            .minByOrNull { it.score }
        return local ?: segments.asSequence()
            .map { segment -> candidate(segment, position, location, priorProgressMetres) }
            .minByOrNull { it.score }
    }

    private fun candidate(
        segment: RouteSegmentIndex,
        position: PointMetres,
        location: GeoLocationSample,
        priorProgressMetres: Double?,
    ): MatchCandidate {
        val dx = segment.end.x - segment.start.x
        val dy = segment.end.y - segment.start.y
        val squaredLength = dx * dx + dy * dy
        val fraction = if (squaredLength == 0.0) 0.0 else (
            ((position.x - segment.start.x) * dx + (position.y - segment.start.y) * dy) / squaredLength
        ).coerceIn(0.0, 1.0)
        val projected = PointMetres(segment.start.x + dx * fraction, segment.start.y + dy * fraction)
        val routeDistance = segment.cumulativeStartMetres + segment.lengthMetres * fraction
        val perpendicular = hypot(position.x - projected.x, position.y - projected.y)
        val progressPenalty = priorProgressMetres?.let { abs(routeDistance - it) * 0.04 } ?: 0.0
        val reliableBearing = location.bearingDegrees?.takeIf {
            (location.speedMetresPerSecond ?: 0.0) >= 2.5 && (location.bearingAccuracyDegrees ?: 180.0) <= 30.0
        }
        val headingPenalty = reliableBearing?.let { angularDifference(it, segment.headingDegrees) * 0.12 } ?: 0.0
        return MatchCandidate(
            segment.index,
            fraction,
            routeDistance,
            perpendicular,
            projected,
            perpendicular + progressPenalty + headingPenalty,
        )
    }

    companion object {
        fun normalizeDegrees(value: Double): Double = ((value % 360.0) + 360.0) % 360.0
        fun angularDifference(first: Double, second: Double): Double =
            abs((normalizeDegrees(first - second + 180.0) - 180.0))
    }
}
