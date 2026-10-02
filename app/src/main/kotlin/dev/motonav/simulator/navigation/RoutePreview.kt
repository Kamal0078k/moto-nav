package dev.motonav.simulator.navigation

import dev.motonav.simulator.routing.RoadRoute
import dev.motonav.simulator.routing.RouteProjection

/** What the TFT shows outside a ride: a greeting, then the picked route's From / To. */
object RoutePreview {
    /** Linked but no route picked yet. */
    fun ready(): NavigationSnapshot = NavigationSnapshot(
        state = NavigationState.READY,
        route = RouteGeometry(listOf(PointMetres(0.0, 0.0)), emptyList()),
        location = null,
        progressMetres = 0.0,
        maneuver = Maneuver.STRAIGHT,
        metresToManeuver = 0.0,
        remainingMetres = 0.0,
        routeLabel = "",
    )

    fun snapshot(route: RoadRoute, label: String, from: String, to: String): NavigationSnapshot {
        val geometry = RouteProjection.project(route).routeGeometry
        val measured = MeasuredRoute(geometry)
        val next = geometry.maneuvers.firstOrNull { it.routeDistanceMetres > 1e-7 }
            ?: ManeuverEvent(measured.totalDistance, Maneuver.ARRIVE)
        val heading = measured.forwardHeading(0.0)
        return NavigationSnapshot(
            state = NavigationState.ROUTE_PREVIEW,
            route = geometry,
            location = LocationSample(measured.positionAt(0.0), 0L),
            progressMetres = 0.0,
            maneuver = next.maneuver,
            metresToManeuver = next.routeDistanceMetres,
            remainingMetres = measured.totalDistance,
            routeLabel = label,
            contentSource = route.source,
            primaryText = from,
            secondaryText = to,
            headingDegrees = ((Math.toDegrees(kotlin.math.atan2(heading.x, heading.y)) % 360.0) + 360.0) % 360.0,
        )
    }
}
