package dev.motonav.simulator.navigation

import kotlin.math.hypot

data class PointMetres(val x: Double, val y: Double) {
    fun distanceTo(other: PointMetres): Double = hypot(other.x - x, other.y - y)
    operator fun plus(other: PointMetres) = PointMetres(x + other.x, y + other.y)
    operator fun minus(other: PointMetres) = PointMetres(x - other.x, y - other.y)
    operator fun times(scale: Double) = PointMetres(x * scale, y * scale)
}

enum class NavigationState {
    DISCONNECTED,
    ACQUIRING_GPS,
    NAVIGATING,
    REROUTING,
    ARRIVED,
    WAITING_PERMISSION,
    LOCATION_DISABLED,
    GPS_DEGRADED,
    /** Display linked, no route picked yet: the TFT greets the rider. */
    READY,
    /** Route picked, ride not started: the TFT shows From / To and the distance. */
    ROUTE_PREVIEW,
}
enum class Maneuver { STRAIGHT, LEFT, RIGHT, ARRIVE }
/** IMPORTED routes come from a rider-supplied file and never touch a hosted routing API. */
enum class RouteContentSource { SYNTHETIC, GOOGLE_MAPS, IMPORTED }

data class ManeuverEvent(
    val routeDistanceMetres: Double,
    val maneuver: Maneuver,
)

data class RouteGeometry(
    val points: List<PointMetres>,
    val maneuvers: List<ManeuverEvent>,
) {
    init {
        require(points.isNotEmpty()) { "A route needs at least one point" }
        require(maneuvers.zipWithNext().all { it.first.routeDistanceMetres <= it.second.routeDistanceMetres }) {
            "Maneuvers must be ordered"
        }
    }
}

data class LocationSample(
    val position: PointMetres,
    val elapsedMillis: Long,
    val accuracyMetres: Double = 3.0,
)

data class NavigationSnapshot(
    val state: NavigationState,
    val route: RouteGeometry,
    val location: LocationSample?,
    val progressMetres: Double,
    val maneuver: Maneuver,
    val metresToManeuver: Double,
    val remainingMetres: Double,
    val routeLabel: String,
    val isReplacementRoute: Boolean = false,
    val contentSource: RouteContentSource = RouteContentSource.SYNTHETIC,
    /** Clockwise degrees from geographic north for the top of the heading-up map. */
    val headingDegrees: Double? = null,
    /** Stable live viewport selected by the navigation engine. */
    val lookaheadMetres: Double? = null,
    val diagnostic: String? = null,
    /** Display text: the From place for a route preview, or the destination on arrival. */
    val primaryText: String? = null,
    /** Display text: the To place for a route preview. */
    val secondaryText: String? = null,
    /** Provider-backed estimate remaining on a live route; null when no duration data exists. */
    val remainingDurationMillis: Long? = null,
)
