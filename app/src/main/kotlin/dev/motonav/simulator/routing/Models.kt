package dev.motonav.simulator.routing

import dev.motonav.simulator.navigation.Maneuver
import dev.motonav.simulator.navigation.RouteContentSource

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude.isFinite() && longitude.isFinite()) { "Coordinates must be finite" }
        require(latitude in -90.0..90.0) { "Latitude must be between -90 and 90" }
        require(longitude in -180.0..180.0) { "Longitude must be between -180 and 180" }
    }
}

/** A stop as Google Routes accepts it; address and place-ID stops are located by Google in the same request. */
sealed interface RouteStop {
    data class At(val point: GeoPoint) : RouteStop
    data class Address(val text: String) : RouteStop
    data class Place(val placeId: String) : RouteStop
}

data class RouteRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    /** Via stops in travel order, e.g. from a shared multi-stop Google Maps link. */
    val intermediates: List<GeoPoint> = emptyList(),
) {
    init {
        require(origin != destination || intermediates.isNotEmpty()) { "Origin and destination must be different" }
        require(intermediates.size <= 25) { "Google Routes accepts at most 25 stops" }
    }
}

enum class ProviderManeuver {
    DEPART,
    STRAIGHT,
    TURN_SLIGHT_LEFT,
    TURN_LEFT,
    TURN_SHARP_LEFT,
    TURN_SLIGHT_RIGHT,
    TURN_RIGHT,
    TURN_SHARP_RIGHT,
    U_TURN_LEFT,
    U_TURN_RIGHT,
    FORK_LEFT,
    FORK_RIGHT,
    MERGE,
    RAMP_LEFT,
    RAMP_RIGHT,
    FERRY,
    FERRY_TRAIN,
    ROUNDABOUT_LEFT,
    ROUNDABOUT_RIGHT,
    NAME_CHANGE,
    ARRIVE,
    UNKNOWN;

    fun oledManeuver(): Maneuver = when (this) {
        TURN_SLIGHT_LEFT, TURN_LEFT, TURN_SHARP_LEFT, U_TURN_LEFT, FORK_LEFT,
        RAMP_LEFT, ROUNDABOUT_LEFT -> Maneuver.LEFT

        TURN_SLIGHT_RIGHT, TURN_RIGHT, TURN_SHARP_RIGHT, U_TURN_RIGHT, FORK_RIGHT,
        RAMP_RIGHT, ROUNDABOUT_RIGHT -> Maneuver.RIGHT

        ARRIVE -> Maneuver.ARRIVE
        DEPART, STRAIGHT, MERGE, FERRY, FERRY_TRAIN, NAME_CHANGE, UNKNOWN -> Maneuver.STRAIGHT
    }
}

data class RoadRouteStep(
    val index: Int,
    val geometryStartIndex: Int,
    val geometryEndIndex: Int,
    val maneuver: ProviderManeuver,
    val instruction: String,
    val metres: Long,
    val durationMillis: Long,
) {
    init {
        require(index >= 0)
        require(geometryStartIndex >= 0 && geometryEndIndex >= geometryStartIndex)
        require(metres >= 0 && durationMillis >= 0)
    }
}

data class RoadRoute(
    val geometry: List<GeoPoint>,
    val steps: List<RoadRouteStep>,
    val totalMetres: Long,
    val totalDurationMillis: Long,
    val attribution: String,
    val source: RouteContentSource,
    val revision: Long,
    /** Where each leg ends (every via stop, then the destination) as Google resolved them. */
    val legEnds: List<GeoPoint> = emptyList(),
) {
    init {
        require(geometry.size >= 2) { "Route geometry must contain at least two points" }
        require(steps.isNotEmpty()) { "A route must contain at least one step" }
        require(totalMetres >= 0 && totalDurationMillis >= 0)
        require(attribution.isNotBlank())
        require(revision > 0) { "Route revision must be positive" }
        require(steps.map { it.index } == steps.indices.toList()) { "Step indices must be contiguous" }
        require(steps.zipWithNext().all { (a, b) -> a.geometryEndIndex <= b.geometryStartIndex }) {
            "Step geometry must be ordered"
        }
        require(steps.all { it.geometryEndIndex in geometry.indices }) { "Step geometry index out of range" }
    }
}

fun interface RoutingProvider {
    @Throws(RoutingException::class)
    fun computeRoute(request: RouteRequest, cancellation: CancellationSignal): RoadRoute
}

fun interface CancellationSignal {
    fun isCancelled(): Boolean

    /** Registers work that can interrupt an in-flight blocking operation. */
    fun invokeOnCancellation(action: () -> Unit): CancellationRegistration = CancellationRegistration.NONE

    companion object {
        val NONE = CancellationSignal { false }
    }
}

fun interface CancellationRegistration {
    fun unregister()

    companion object {
        val NONE = CancellationRegistration { }
    }
}

/** Thread-safe signal used by the route-loading UI and blocking HTTP adapter. */
class RoutingCancellation : CancellationSignal {
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    override fun isCancelled(): Boolean = cancelled.get()

    override fun invokeOnCancellation(action: () -> Unit): CancellationRegistration {
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val listener = {
            if (active.compareAndSet(true, false)) action()
        }
        listeners += listener
        if (cancelled.get()) {
            listeners.remove(listener)
            listener()
        }
        return CancellationRegistration {
            active.set(false)
            listeners.remove(listener)
        }
    }

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        val pending = listeners.toList()
        listeners.clear()
        pending.forEach { runCatching(it) }
    }
}

sealed class RoutingFailure(val userMessage: String) {
    data object InvalidInput : RoutingFailure("Google couldn't use one of the stops. Check the route in Google Maps and share it again.")
    data object MissingCredential : RoutingFailure("No Google Routes API key is configured. Imported GPX / KML routes still work offline.")
    data object AuthenticationOrConfiguration : RoutingFailure("Google rejected the app credential or project configuration.")
    data object QuotaExhausted : RoutingFailure("The routing request budget is exhausted.")
    data class RateLimited(val retryAfterMillis: Long?) :
        RoutingFailure("Google is rate limiting route requests. Retry later.")
    data object NoRoute : RoutingFailure("No motorcycle route was found between these points.")
    data object TimeoutOrNetwork : RoutingFailure("The route request timed out or the network is unavailable.")
    data object MalformedResponse : RoutingFailure("Google returned a route response the app could not safely use.")
    data object ProviderUnavailable : RoutingFailure("Google Routes is temporarily unavailable.")
    data object Cancelled : RoutingFailure("The route request was cancelled.")
}

class RoutingException(val failure: RoutingFailure, cause: Throwable? = null) :
    Exception(failure.userMessage, cause)
