package dev.motonav.simulator.routing

import dev.motonav.simulator.navigation.RouteContentSource
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Turns an imported route line into a navigable [RoadRoute] entirely on-device. Maneuvers come from
 * the line's shape: wherever the heading changes sharply, a turn step begins. No hosted API is used.
 */
class TrackRouteBuilder(
    private val revisions: RouteRevisionSource = MonotonicRouteRevisionSource(),
    private val simplifyToleranceMetres: Double = 4.0,
    private val headingWindowMetres: Double = 22.0,
    private val minimumTurnDegrees: Double = 32.0,
    private val turnMergeMetres: Double = 35.0,
    private val averageSpeedMetresPerSecond: Double = 25.0 / 3.6,
) {
    fun build(points: List<GeoPoint>, attribution: String = "Imported route"): RoadRoute {
        require(points.size >= 2) { "A route needs at least two points" }
        val geometry = simplify(points).let {
            if (it.size <= PolylineCodec.DEFAULT_MAX_POINTS) it else it.filterIndexed { i, _ -> i % (it.size / PolylineCodec.DEFAULT_MAX_POINTS + 1) == 0 } + it.last()
        }.distinctConsecutive()
        require(geometry.size >= 2) { "The imported route is too short" }
        val cumulative = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) cumulative[i] = cumulative[i - 1] + distanceMetres(geometry[i - 1], geometry[i])
        val total = cumulative.last()

        val turns = detectTurns(geometry, cumulative)
        val boundaries = listOf(0) + turns.map { it.first } + geometry.lastIndex
        val maneuvers = listOf(ProviderManeuver.DEPART) + turns.map { it.second }
        val steps = maneuvers.indices.map { index ->
            val start = boundaries[index]
            val end = boundaries[index + 1]
            val metres = (cumulative[end] - cumulative[start]).coerceAtLeast(0.0)
            RoadRouteStep(
                index = index,
                geometryStartIndex = start,
                geometryEndIndex = end,
                maneuver = maneuvers[index],
                instruction = instructionFor(maneuvers[index]),
                metres = metres.roundToLong(),
                durationMillis = (metres / averageSpeedMetresPerSecond * 1000).roundToLong(),
            )
        } + RoadRouteStep(
            index = maneuvers.size,
            geometryStartIndex = geometry.lastIndex,
            geometryEndIndex = geometry.lastIndex,
            maneuver = ProviderManeuver.ARRIVE,
            instruction = "Arrive at destination",
            metres = 0,
            durationMillis = 0,
        )
        return RoadRoute(
            geometry = geometry,
            steps = steps,
            totalMetres = total.roundToLong(),
            totalDurationMillis = (total / averageSpeedMetresPerSecond * 1000).roundToLong(),
            attribution = attribution,
            source = RouteContentSource.IMPORTED,
            revision = revisions.nextRevision(),
        )
    }

    private fun detectTurns(points: List<GeoPoint>, cumulative: DoubleArray): List<Pair<Int, ProviderManeuver>> {
        val candidates = (1 until points.lastIndex).mapNotNull { i ->
            if (cumulative[i] < headingWindowMetres / 2 || cumulative.last() - cumulative[i] < headingWindowMetres / 2) {
                return@mapNotNull null
            }
            val before = indexAtDistance(cumulative, cumulative[i] - headingWindowMetres, i, backwards = true)
            val after = indexAtDistance(cumulative, cumulative[i] + headingWindowMetres, i, backwards = false)
            val delta = signedDelta(bearing(points[before], points[i]), bearing(points[i], points[after]))
            if (abs(delta) >= minimumTurnDegrees) Triple(i, delta, cumulative[i]) else null
        }
        // Several vertices of one curved junction all see the turn; keep the sharpest of each cluster.
        val clustered = mutableListOf<Triple<Int, Double, Double>>()
        for (candidate in candidates) {
            val last = clustered.lastOrNull()
            if (last != null && candidate.third - last.third <= turnMergeMetres && (candidate.second > 0) == (last.second > 0)) {
                if (abs(candidate.second) > abs(last.second)) clustered[clustered.lastIndex] = candidate
            } else {
                clustered += candidate
            }
        }
        return clustered.map { (index, delta, _) -> index to classify(delta) }
    }

    private fun indexAtDistance(cumulative: DoubleArray, target: Double, from: Int, backwards: Boolean): Int {
        var index = from
        if (backwards) {
            while (index > 0 && cumulative[index] > target) index--
        } else {
            while (index < cumulative.lastIndex && cumulative[index] < target) index++
        }
        return index
    }

    private fun classify(delta: Double): ProviderManeuver {
        val right = delta > 0
        val magnitude = abs(delta)
        return when {
            magnitude >= 155 -> if (right) ProviderManeuver.U_TURN_RIGHT else ProviderManeuver.U_TURN_LEFT
            magnitude >= 120 -> if (right) ProviderManeuver.TURN_SHARP_RIGHT else ProviderManeuver.TURN_SHARP_LEFT
            magnitude >= 55 -> if (right) ProviderManeuver.TURN_RIGHT else ProviderManeuver.TURN_LEFT
            else -> if (right) ProviderManeuver.TURN_SLIGHT_RIGHT else ProviderManeuver.TURN_SLIGHT_LEFT
        }
    }

    private fun instructionFor(maneuver: ProviderManeuver): String = when (maneuver) {
        ProviderManeuver.DEPART -> "Head out along the route"
        ProviderManeuver.TURN_SLIGHT_LEFT -> "Keep slightly left"
        ProviderManeuver.TURN_LEFT -> "Turn left"
        ProviderManeuver.TURN_SHARP_LEFT -> "Turn sharp left"
        ProviderManeuver.TURN_SLIGHT_RIGHT -> "Keep slightly right"
        ProviderManeuver.TURN_RIGHT -> "Turn right"
        ProviderManeuver.TURN_SHARP_RIGHT -> "Turn sharp right"
        ProviderManeuver.U_TURN_LEFT, ProviderManeuver.U_TURN_RIGHT -> "Make a U-turn"
        else -> "Continue"
    }

    /** Douglas–Peucker simplification removes GPS jitter that would otherwise read as turns. */
    private fun simplify(points: List<GeoPoint>): List<GeoPoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var maxDistance = 0.0
            var maxIndex = -1
            for (i in first + 1 until last) {
                val distance = crossTrackMetres(points[i], points[first], points[last])
                if (distance > maxDistance) {
                    maxDistance = distance
                    maxIndex = i
                }
            }
            if (maxIndex >= 0 && maxDistance > simplifyToleranceMetres) {
                keep[maxIndex] = true
                stack.addLast(first to maxIndex)
                stack.addLast(maxIndex to last)
            }
        }
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun crossTrackMetres(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
        val origin = start
        val p = RouteProjection.toMetres(origin, point)
        val b = RouteProjection.toMetres(origin, end)
        val lengthSquared = b.x * b.x + b.y * b.y
        if (lengthSquared == 0.0) return kotlin.math.hypot(p.x, p.y)
        val t = ((p.x * b.x + p.y * b.y) / lengthSquared).coerceIn(0.0, 1.0)
        return kotlin.math.hypot(p.x - t * b.x, p.y - t * b.y)
    }

    private fun bearing(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        return Math.toDegrees(atan2(y, x))
    }

    private fun signedDelta(from: Double, to: Double): Double {
        var delta = (to - from) % 360.0
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        return delta
    }

    private fun List<GeoPoint>.distinctConsecutive(): List<GeoPoint> =
        filterIndexed { index, point -> index == 0 || point != this[index - 1] }
}
