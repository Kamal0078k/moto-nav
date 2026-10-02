package dev.motonav.simulator.navigation

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class NormalizedRoutePoint(
    val x: Int,
    val y: Int,
    val startsPolyline: Boolean = false,
    val destination: Boolean = false,
)

data class RenderedRoute(
    val points: List<NormalizedRoutePoint>,
    val lookaheadMetres: Double,
    val metresPerPixel: Double,
    val heading: PointMetres,
    val markerX: Int = 64,
    val markerY: Int = 94,
)

class RouteRenderer(
    private val defaultLookaheadMetres: Double = 250.0,
) {
    companion object {
        const val WIDTH = 128
        const val HEIGHT = 160
        // Hero (turn arrow + distance) sits above, the footer (remaining / ETA / caller) below.
        const val ROUTE_TOP = 55
        const val ROUTE_BOTTOM = 125
        const val MARKER_X = 64
        // Five-sixths down the route viewport, leaving most of the map for the road ahead.
        const val MARKER_Y = ROUTE_TOP + (ROUTE_BOTTOM - ROUTE_TOP) * 5 / 6
        const val MAX_PROTOCOL_POINTS = 32
    }

    fun render(snapshot: NavigationSnapshot, lookaheadMetres: Double = snapshot.lookaheadMetres ?: defaultLookaheadMetres): RenderedRoute {
        require(lookaheadMetres > 0.0)
        if (snapshot.lookaheadMetres != null) require(lookaheadMetres in 200.0..300.0) {
            "Live lookahead must remain between 200 and 300 metres"
        }
        val route = MeasuredRoute(snapshot.route)
        val progress = snapshot.progressMetres.coerceIn(0.0, route.totalDistance)
        // Rotate world geometry into the phone's heading before clipping. Doing this
        // here preserves route segments that would be lost by rotating an already
        // clipped north-up frame on the display.
        val headingRadians = Math.toRadians(snapshot.headingDegrees ?: 0.0)
        val heading = PointMetres(sin(headingRadians), cos(headingRadians))
        val metresPerPixel = lookaheadMetres / (MARKER_Y - ROUTE_TOP).toDouble()
        val worldPoints = route.slice(progress, lookaheadMetres)
        val screenPoints = worldPoints.map { world ->
            val delta = world - route.positionAt(progress)
            val forward = delta.x * heading.x + delta.y * heading.y
            val right = delta.x * heading.y - delta.y * heading.x
            ScreenPoint(MARKER_X + right / metresPerPixel, MARKER_Y - forward / metresPerPixel)
        }

        val destinationVisible = progress + lookaheadMetres >= route.totalDistance - 1e-7
        val clipped = clipPolyline(screenPoints)
        val limited = limitPoints(clipped, MAX_PROTOCOL_POINTS)
        val normalized = limited.mapIndexed { index, point ->
            NormalizedRoutePoint(
                x = (point.point.x.coerceIn(0.0, WIDTH - 1.0) * 65535.0 / (WIDTH - 1)).roundToInt(),
                y = ((point.point.y.coerceIn(ROUTE_TOP.toDouble(), ROUTE_BOTTOM.toDouble()) - ROUTE_TOP) *
                    65535.0 / (ROUTE_BOTTOM - ROUTE_TOP)).roundToInt(),
                startsPolyline = point.startsPolyline || index == 0,
                destination = destinationVisible && index == limited.lastIndex &&
                    point.point.distanceTo(screenPoints.last()) < 1e-5,
            )
        }
        return RenderedRoute(normalized, lookaheadMetres, metresPerPixel, heading)
    }

    private data class ScreenPoint(val x: Double, val y: Double) {
        fun distanceTo(other: ScreenPoint): Double = kotlin.math.hypot(other.x - x, other.y - y)
    }

    private data class ClippedPoint(val point: ScreenPoint, val startsPolyline: Boolean)

    private fun clipPolyline(points: List<ScreenPoint>): List<ClippedPoint> {
        if (points.size < 2) {
            return points.filter(::inside).map { ClippedPoint(it, true) }
        }
        val result = mutableListOf<ClippedPoint>()
        var connected = false
        points.zipWithNext().forEach { (start, end) ->
            val clipped = clipSegment(start, end)
            if (clipped == null) {
                connected = false
            } else {
                val (clippedStart, clippedEnd) = clipped
                val joinsPrevious = connected && result.isNotEmpty() && result.last().point.distanceTo(clippedStart) < 1e-5
                if (!joinsPrevious) result += ClippedPoint(clippedStart, true)
                if (result.last().point.distanceTo(clippedEnd) > 1e-7) {
                    result += ClippedPoint(clippedEnd, false)
                }
                connected = end.distanceTo(clippedEnd) < 1e-5 && inside(end)
            }
        }
        return result
    }

    // Liang-Barsky clipping keeps exact intersections and exposes each gap as a new polyline.
    private fun clipSegment(start: ScreenPoint, end: ScreenPoint): Pair<ScreenPoint, ScreenPoint>? {
        val dx = end.x - start.x
        val dy = end.y - start.y
        var entering = 0.0
        var leaving = 1.0
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(start.x, WIDTH - 1.0 - start.x, start.y - ROUTE_TOP, ROUTE_BOTTOM - start.y)
        for (index in p.indices) {
            if (abs(p[index]) < 1e-12) {
                if (q[index] < 0.0) return null
                continue
            }
            val ratio = q[index] / p[index]
            if (p[index] < 0.0) entering = maxOf(entering, ratio) else leaving = minOf(leaving, ratio)
            if (entering > leaving) return null
        }
        return ScreenPoint(start.x + entering * dx, start.y + entering * dy) to
            ScreenPoint(start.x + leaving * dx, start.y + leaving * dy)
    }

    private fun inside(point: ScreenPoint): Boolean =
        point.x in 0.0..(WIDTH - 1.0) && point.y in ROUTE_TOP.toDouble()..ROUTE_BOTTOM.toDouble()

    private fun limitPoints(points: List<ClippedPoint>, limit: Int): List<ClippedPoint> {
        if (points.size <= limit) return points
        val chosen = linkedSetOf<Int>()
        chosen += 0
        chosen += points.lastIndex
        points.forEachIndexed { index, point -> if (point.startsPolyline) chosen += index }
        val remaining = limit - chosen.size
        if (remaining > 0) {
            for (slot in 1..remaining) {
                chosen += (slot.toDouble() * points.lastIndex / (remaining + 1)).roundToInt()
            }
        }
        return chosen.sorted().take(limit).mapIndexed { index, originalIndex ->
            val point = points[originalIndex]
            point.copy(startsPolyline = point.startsPolyline || index == 0 || originalIndex > chosen.sorted()[index - 1] + 1)
        }
    }
}
