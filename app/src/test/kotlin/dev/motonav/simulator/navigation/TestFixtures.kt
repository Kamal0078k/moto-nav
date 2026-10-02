package dev.motonav.simulator.navigation

/** Test-only synthetic routes and a replay engine; not part of the app. */
data class RouteFixture(
    val label: String,
    val route: RouteGeometry,
    val gpsLossRange: ClosedFloatingPointRange<Double>? = null,
    val reroutingUntilMetres: Double? = null,
    val isReplacementRoute: Boolean = false,
    val contentSource: RouteContentSource = RouteContentSource.SYNTHETIC,
)

object Fixtures {
    private fun route(points: List<PointMetres>, turns: List<ManeuverEvent>) = RouteGeometry(points, turns)

    val all: List<RouteFixture> = listOf(
        RouteFixture(
            "Right turn · 1.2 km transition",
            route(
                listOf(PointMetres(0.0, 0.0), PointMetres(0.0, 1200.0), PointMetres(120.0, 1200.0)),
                listOf(ManeuverEvent(1200.0, Maneuver.RIGHT), ManeuverEvent(1320.0, Maneuver.ARRIVE)),
            ),
        ),
        RouteFixture(
            "Left turn",
            route(
                listOf(PointMetres(0.0, 0.0), PointMetres(0.0, 60.0), PointMetres(-100.0, 60.0)),
                listOf(ManeuverEvent(60.0, Maneuver.LEFT), ManeuverEvent(160.0, Maneuver.ARRIVE)),
            ),
        ),
        RouteFixture(
            "Consecutive junctions",
            route(
                listOf(
                    PointMetres(0.0, 0.0), PointMetres(0.0, 70.0), PointMetres(55.0, 70.0),
                    PointMetres(55.0, 125.0), PointMetres(10.0, 125.0),
                ),
                listOf(
                    ManeuverEvent(70.0, Maneuver.RIGHT), ManeuverEvent(125.0, Maneuver.LEFT),
                    ManeuverEvent(180.0, Maneuver.LEFT), ManeuverEvent(225.0, Maneuver.ARRIVE),
                ),
            ),
        ),
        RouteFixture(
            "GPS loss and recovery",
            route(
                listOf(PointMetres(0.0, 0.0), PointMetres(0.0, 120.0), PointMetres(80.0, 120.0)),
                listOf(ManeuverEvent(120.0, Maneuver.RIGHT), ManeuverEvent(200.0, Maneuver.ARRIVE)),
            ),
            gpsLossRange = 40.0..65.0,
        ),
        RouteFixture(
            "Replacement route · missed turn",
            route(
                listOf(PointMetres(0.0, 0.0), PointMetres(0.0, 45.0), PointMetres(-35.0, 75.0), PointMetres(-35.0, 150.0)),
                listOf(ManeuverEvent(45.0, Maneuver.LEFT), ManeuverEvent(121.1, Maneuver.RIGHT), ManeuverEvent(196.1, Maneuver.ARRIVE)),
            ),
            reroutingUntilMetres = 18.0,
            isReplacementRoute = true,
        ),
    )
}

class NavigationEngine(
    fixture: RouteFixture,
    var speedMetresPerSecond: Double = 12.0,
) {
    var fixture: RouteFixture = fixture
        private set
    var progressMetres: Double = 0.0
        private set
    private var elapsedMillis: Long = 0L

    fun replaceFixture(newFixture: RouteFixture) {
        fixture = newFixture
        restart()
    }

    fun restart(): NavigationSnapshot {
        progressMetres = 0.0
        elapsedMillis = 0L
        return snapshot()
    }

    fun step(deltaMillis: Long): NavigationSnapshot {
        require(deltaMillis >= 0)
        elapsedMillis += deltaMillis
        val measured = MeasuredRoute(fixture.route)
        progressMetres = (progressMetres + speedMetresPerSecond * deltaMillis / 1000.0)
            .coerceAtMost(measured.totalDistance)
        return snapshot()
    }

    fun snapshot(): NavigationSnapshot {
        val measured = MeasuredRoute(fixture.route)
        val arrived = measured.totalDistance == 0.0 || progressMetres >= measured.totalDistance
        val gpsLost = fixture.gpsLossRange?.contains(progressMetres) == true
        val rerouting = fixture.reroutingUntilMetres?.let { progressMetres < it } == true
        val state = when {
            arrived -> NavigationState.ARRIVED
            gpsLost -> NavigationState.ACQUIRING_GPS
            rerouting -> NavigationState.REROUTING
            else -> NavigationState.NAVIGATING
        }
        val next = fixture.route.maneuvers.firstOrNull { it.routeDistanceMetres > progressMetres + 1e-7 }
            ?: ManeuverEvent(measured.totalDistance, Maneuver.ARRIVE)
        val location = if (gpsLost) null else LocationSample(measured.positionAt(progressMetres), elapsedMillis)
        return NavigationSnapshot(
            state = state,
            route = fixture.route,
            location = location,
            progressMetres = progressMetres,
            maneuver = next.maneuver,
            metresToManeuver = (next.routeDistanceMetres - progressMetres).coerceAtLeast(0.0),
            remainingMetres = (measured.totalDistance - progressMetres).coerceAtLeast(0.0),
            routeLabel = fixture.label,
            isReplacementRoute = fixture.isReplacementRoute,
            contentSource = fixture.contentSource,
            headingDegrees = measured.forwardHeading(progressMetres).let { heading ->
                GeographicHeading.fromVector(heading)
            },
        )
    }
}

private object GeographicHeading {
    fun fromVector(vector: PointMetres): Double =
        ((Math.toDegrees(kotlin.math.atan2(vector.x, vector.y)) % 360.0) + 360.0) % 360.0
}
