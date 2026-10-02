package dev.motonav.simulator.routing

import dev.motonav.simulator.navigation.ManeuverEvent
import dev.motonav.simulator.navigation.MeasuredRoute
import dev.motonav.simulator.navigation.PointMetres
import dev.motonav.simulator.navigation.RouteGeometry
import dev.motonav.simulator.navigation.RouteContentSource
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class ProjectedRoadRoute(
    val routeGeometry: RouteGeometry,
    val projectedPointToSourcePoint: List<Int>,
    val projectedPointToStep: List<Int>,
    val source: RoadRoute,
)

object RouteProjection {
    fun project(route: RoadRoute): ProjectedRoadRoute {
        val origin = route.geometry.first()
        val points = route.geometry.map { toMetres(origin, it) }
        val bareGeometry = RouteGeometry(points, emptyList())
        val measured = MeasuredRoute(bareGeometry)
        val maneuvers = route.steps.map { step ->
            ManeuverEvent(
                routeDistanceMetres = measured.cumulativeDistances[step.geometryStartIndex],
                maneuver = step.maneuver.oledManeuver(),
            )
        }
        val stepMapping = points.indices.map { pointIndex ->
            route.steps.indexOfLast { it.geometryStartIndex <= pointIndex }.coerceAtLeast(0)
        }
        return ProjectedRoadRoute(
            routeGeometry = RouteGeometry(points, maneuvers),
            projectedPointToSourcePoint = points.indices.toList(),
            projectedPointToStep = stepMapping,
            source = route,
        )
    }

    /** Local equirectangular projection in metres, adequate for the short navigation corridor. */
    fun toMetres(origin: GeoPoint, point: GeoPoint): PointMetres {
        val meanLatitude = Math.toRadians((origin.latitude + point.latitude) / 2.0)
        val longitudeDelta = normalizeLongitudeDelta(point.longitude - origin.longitude)
        return PointMetres(
            x = Math.toRadians(longitudeDelta) * EARTH_RADIUS_METRES * cos(meanLatitude),
            y = Math.toRadians(point.latitude - origin.latitude) * EARTH_RADIUS_METRES,
        )
    }

    fun distanceMetres(first: GeoPoint, second: GeoPoint): Double {
        val firstLatitude = Math.toRadians(first.latitude)
        val secondLatitude = Math.toRadians(second.latitude)
        val latitudeDelta = secondLatitude - firstLatitude
        val longitudeDelta = Math.toRadians(normalizeLongitudeDelta(second.longitude - first.longitude))
        val a = sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
            cos(firstLatitude) * cos(secondLatitude) * sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
        return 2.0 * EARTH_RADIUS_METRES * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
    }

    private fun normalizeLongitudeDelta(value: Double): Double = when {
        value > 180.0 -> value - 360.0
        value < -180.0 -> value + 360.0
        else -> value
    }

    private const val EARTH_RADIUS_METRES = 6_371_000.0
}
