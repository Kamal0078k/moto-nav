package dev.motonav.simulator.navigation

import kotlin.math.max
import kotlin.math.min

data class RouteProgress(
    val segmentIndex: Int,
    val segmentFraction: Double,
    val routeDistanceMetres: Double,
    val projected: PointMetres,
    val distanceFromRouteMetres: Double,
)

class MeasuredRoute(val geometry: RouteGeometry) {
    val cumulativeDistances: List<Double>
    val totalDistance: Double

    init {
        val values = ArrayList<Double>(geometry.points.size)
        var distance = 0.0
        values += distance
        for (index in 1 until geometry.points.size) {
            distance += geometry.points[index - 1].distanceTo(geometry.points[index])
            values += distance
        }
        cumulativeDistances = values
        totalDistance = distance
    }

    fun positionAt(distanceMetres: Double): PointMetres {
        if (geometry.points.size == 1 || totalDistance == 0.0) return geometry.points.first()
        val target = distanceMetres.coerceIn(0.0, totalDistance)
        for (index in 0 until geometry.points.lastIndex) {
            val startDistance = cumulativeDistances[index]
            val endDistance = cumulativeDistances[index + 1]
            if (target <= endDistance && endDistance > startDistance) {
                val fraction = (target - startDistance) / (endDistance - startDistance)
                val start = geometry.points[index]
                return start + (geometry.points[index + 1] - start) * fraction
            }
        }
        return geometry.points.last()
    }

    fun project(position: PointMetres, hintDistanceMetres: Double? = null): RouteProgress {
        if (geometry.points.size == 1) {
            return RouteProgress(0, 0.0, 0.0, geometry.points.first(), position.distanceTo(geometry.points.first()))
        }

        var best: RouteProgress? = null
        for (index in 0 until geometry.points.lastIndex) {
            val start = geometry.points[index]
            val delta = geometry.points[index + 1] - start
            val squaredLength = delta.x * delta.x + delta.y * delta.y
            val fraction = if (squaredLength == 0.0) 0.0 else {
                (((position.x - start.x) * delta.x + (position.y - start.y) * delta.y) / squaredLength)
                    .coerceIn(0.0, 1.0)
            }
            val projected = start + delta * fraction
            val distanceFromRoute = position.distanceTo(projected)
            val routeDistance = cumulativeDistances[index] +
                (cumulativeDistances[index + 1] - cumulativeDistances[index]) * fraction
            val candidate = RouteProgress(index, fraction, routeDistance, projected, distanceFromRoute)
            val current = best
            val winsByPosition = current == null || distanceFromRoute < current.distanceFromRouteMetres - 1e-7
            val tiesAndNearHint = current != null && kotlin.math.abs(distanceFromRoute - current.distanceFromRouteMetres) <= 1e-7 &&
                hintDistanceMetres != null &&
                kotlin.math.abs(routeDistance - hintDistanceMetres) < kotlin.math.abs(current.routeDistanceMetres - hintDistanceMetres)
            if (winsByPosition || tiesAndNearHint) best = candidate
        }
        return requireNotNull(best)
    }

    fun slice(startDistanceMetres: Double, lookaheadMetres: Double): List<PointMetres> {
        require(lookaheadMetres >= 0.0)
        val startDistance = startDistanceMetres.coerceIn(0.0, totalDistance)
        val endDistance = min(totalDistance, startDistance + lookaheadMetres)
        val result = mutableListOf(positionAt(startDistance))
        cumulativeDistances.forEachIndexed { index, distance ->
            if (distance > startDistance && distance < endDistance) addDistinct(result, geometry.points[index])
        }
        addDistinct(result, positionAt(endDistance))
        return result
    }

    fun forwardHeading(distanceMetres: Double): PointMetres {
        if (totalDistance == 0.0) return PointMetres(0.0, 1.0)
        val distance = distanceMetres.coerceIn(0.0, totalDistance)
        val forward = positionAt(min(totalDistance, distance + max(0.5, min(3.0, totalDistance / 20.0))))
        val current = positionAt(distance)
        var delta = forward - current
        if (delta.distanceTo(PointMetres(0.0, 0.0)) < 1e-9) {
            delta = current - positionAt(max(0.0, distance - 3.0))
        }
        val length = delta.distanceTo(PointMetres(0.0, 0.0))
        return if (length < 1e-9) PointMetres(0.0, 1.0) else PointMetres(delta.x / length, delta.y / length)
    }

    private fun addDistinct(points: MutableList<PointMetres>, point: PointMetres) {
        if (points.isEmpty() || points.last().distanceTo(point) > 1e-7) points += point
    }
}
