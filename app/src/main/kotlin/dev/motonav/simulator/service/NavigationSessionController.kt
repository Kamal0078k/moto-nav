package dev.motonav.simulator.service

import dev.motonav.simulator.location.GeoLocationSample
import dev.motonav.simulator.location.LiveNavigationEngine
import dev.motonav.simulator.location.MonotonicClock
import dev.motonav.simulator.navigation.NavigationSnapshot
import dev.motonav.simulator.navigation.NavigationState
import dev.motonav.simulator.routing.GeoPoint
import dev.motonav.simulator.routing.RoadRoute
import dev.motonav.simulator.routing.RouteProjection
import dev.motonav.simulator.routing.RouteRequest
import dev.motonav.simulator.transport.TransportState
import java.util.UUID

enum class SessionPhase { IDLE, STARTING, ACTIVE, ARRIVED, FAILED }

data class NavigationSessionSnapshot(
    val phase: SessionPhase,
    val navigation: NavigationSnapshot?,
    val routeRevision: Long?,
    val destinationLabel: String?,
    val startedAtWallClockMillis: Long?,
    val transportState: TransportState,
    val providerRequestCount: Int = 0,
    val sessionRerouteCount: Int = 0,
    val offRouteState: OffRouteState = OffRouteState.ON_ROUTE,
    val reroutingPolicy: String? = null,
    val lastFailure: String? = null,
) {
    val isActive: Boolean get() = phase == SessionPhase.STARTING || phase == SessionPhase.ACTIVE || phase == SessionPhase.ARRIVED
}

data class RerouteRequestContext(
    val sessionId: String,
    val sessionGeneration: Long,
    val requestGeneration: Long,
    val startingRouteRevision: Long,
    val origin: GeoLocationSample,
    val destination: GeoPoint,
    val kind: RerouteAttemptKind,
    val budgetDecision: String,
) {
    val request: RouteRequest get() = RouteRequest(GeoPoint(origin.latitude, origin.longitude), destination)
}

data class SessionLocationUpdate(
    val snapshot: NavigationSessionSnapshot,
    val reroute: RerouteRequestContext? = null,
)

data class RerouteCompletion(
    val accepted: Boolean,
    val snapshot: NavigationSessionSnapshot,
    val reason: String? = null,
)

/**
 * Sole mutable navigation owner during a foreground session. NavigationService serializes every
 * call on one HandlerThread; network callbacks are accepted only through generation-checked APIs.
 */
class NavigationSessionController(
    private val clock: MonotonicClock,
    val reroutingPolicy: ReroutingPolicy = ReroutingPolicy(),
) {
    private var engine: LiveNavigationEngine? = null
    private var route: RoadRoute? = null
    private var originalRequest: RouteRequest? = null
    private var destinationLabel: String? = null
    private var startedAtWallClockMillis: Long? = null
    private var phase = SessionPhase.IDLE
    private var transportState: TransportState = TransportState.Idle
    private var lastFailure: String? = null
    private var localRequestCount = 0
    private var sessionId = ""
    private var sessionGeneration = 0L
    private var requestGeneration = 0L
    private var inFlight: RerouteRequestContext? = null
    private var lastFailedOrigin: GeoLocationSample? = null
    private var deviceHeadingDegrees: Double? = null
    private var requireMovementForManualRetry = false
    private val offRoute = OffRouteReroutingStateMachine(reroutingPolicy)

    fun start(
        route: RoadRoute,
        destinationLabel: String,
        wallClockMillis: Long,
        localRequestCount: Int = 0,
        request: RouteRequest = RouteRequest(route.geometry.first(), route.geometry.last()),
    ): NavigationSessionSnapshot {
        if (phase == SessionPhase.STARTING || phase == SessionPhase.ACTIVE || phase == SessionPhase.ARRIVED) return snapshot()
        this.route = route
        this.originalRequest = request
        this.destinationLabel = destinationLabel
        this.startedAtWallClockMillis = wallClockMillis
        this.localRequestCount = localRequestCount
        sessionId = UUID.randomUUID().toString()
        sessionGeneration++
        requestGeneration = 0L
        inFlight = null
        lastFailedOrigin = null
        deviceHeadingDegrees = null
        requireMovementForManualRetry = false
        offRoute.cancel()
        lastFailure = null
        phase = SessionPhase.STARTING
        engine = LiveNavigationEngine(route, clock)
        val navigation = requireNotNull(engine).start()
        phase = SessionPhase.ACTIVE
        return snapshot(navigation)
    }

    fun onLocations(samples: List<GeoLocationSample>): List<SessionLocationUpdate> {
        if (phase != SessionPhase.ACTIVE) return emptyList()
        return samples.sortedBy { it.elapsedRealtimeMillis }.map { sample ->
            val update = requireNotNull(engine).processDetailed(sample)
            if (update.snapshot.state == NavigationState.ARRIVED) phase = SessionPhase.ARRIVED
            val trigger = update.observation?.let { observation ->
                offRoute.observe(observation, update.snapshot.remainingMetres, clock.elapsedRealtimeMillis())
            }?.let(::newRerouteContext)
            SessionLocationUpdate(snapshot(update.snapshot), trigger)
        }
    }

    fun onDeviceHeading(headingDegrees: Double): NavigationSessionSnapshot {
        deviceHeadingDegrees = headingDegrees
        val navigation = if (phase == SessionPhase.ACTIVE) engine?.updateDeviceHeading(headingDegrees) else null
        return snapshot(navigation)
    }

    fun beginReroute(context: RerouteRequestContext): NavigationSessionSnapshot? {
        if (!accepts(context) || inFlight != null || phase != SessionPhase.ACTIVE) return null
        inFlight = context
        lastFailure = null
        offRoute.requestStarted(context.kind)
        return snapshot(requireNotNull(engine).rerouting("${offRoute.lastDecision}; ${reroutingPolicy.diagnostics()}"))
    }

    fun deferRerouteBeforeSend(context: RerouteRequestContext, reason: String): NavigationSessionSnapshot {
        if (accepts(context)) {
            inFlight = null
            offRoute.requestDeferredBeforeSend(reason)
            lastFailure = reason
            engine?.resumeAfterReroute(reason)
        }
        return snapshot()
    }

    fun completeReroute(context: RerouteRequestContext, replacement: RoadRoute): RerouteCompletion {
        if (!accepts(context) || inFlight != context || phase != SessionPhase.ACTIVE) {
            return RerouteCompletion(false, snapshot(), "obsolete replacement callback")
        }
        inFlight = null
        val currentEngine = requireNotNull(engine)
        val latest = currentEngine.latestAcceptedLocation
            ?: return staleReplacement(context, "no accepted location remains")
        val age = clock.elapsedRealtimeMillis() - context.origin.elapsedRealtimeMillis
        val movement = RouteProjection.distanceMetres(
            GeoPoint(context.origin.latitude, context.origin.longitude),
            GeoPoint(latest.latitude, latest.longitude),
        )
        if (age > reroutingPolicy.staleOriginMillis || movement > reroutingPolicy.staleMovementMetres) {
            return staleReplacement(context, "replacement response became stale")
        }
        val destination = requireNotNull(originalRequest).destination
        if (RouteProjection.distanceMetres(replacement.geometry.last(), destination) >
            reroutingPolicy.destinationConsistencyMetres
        ) return invalidReplacement(context, "replacement destination was inconsistent")
        if (RouteProjection.distanceMetres(
                replacement.geometry.first(),
                GeoPoint(context.origin.latitude, context.origin.longitude),
            ) > reroutingPolicy.originCaptureMetres
        ) return invalidReplacement(context, "replacement did not capture its requested origin")
        if (replacement.revision == context.startingRouteRevision) {
            return invalidReplacement(context, "replacement route revision was not new")
        }

        val candidateEngine = LiveNavigationEngine(replacement, clock, replacementRoute = true)
        candidateEngine.start()
        deviceHeadingDegrees?.let(candidateEngine::updateDeviceHeading)
        val rematched = candidateEngine.processDetailed(latest)
        if (rematched.observation == null ||
            rematched.observation.distanceFromRouteMetres > reroutingPolicy.originCaptureMetres
        ) return invalidReplacement(context, "latest location could not be rematched to replacement")

        // Atomic publication point: all geometry, steps, progress and maneuver state are consistent.
        route = replacement
        engine = candidateEngine
        lastFailure = null
        requireMovementForManualRetry = false
        offRoute.requestSucceeded(clock.elapsedRealtimeMillis())
        return RerouteCompletion(true, snapshot(rematched.snapshot))
    }

    fun failReroute(
        context: RerouteRequestContext,
        kind: RerouteFailureKind,
        message: String,
        retryAfterMillis: Long? = null,
    ): NavigationSessionSnapshot {
        if (!accepts(context) || inFlight != context) return snapshot()
        inFlight = null
        lastFailure = message
        lastFailedOrigin = context.origin
        requireMovementForManualRetry = kind == RerouteFailureKind.NO_ROUTE
        offRoute.requestFailed(kind, clock.elapsedRealtimeMillis(), retryAfterMillis)
        val navigation = engine?.resumeAfterReroute("$message; ${offRoute.lastDecision}")
        return snapshot(navigation)
    }

    fun requestManualReroute(): RerouteRequestContext? {
        if (phase != SessionPhase.ACTIVE || inFlight != null) return null
        val latest = engine?.latestAcceptedLocation ?: return null
        if (requireMovementForManualRetry) {
            val failed = lastFailedOrigin ?: return null
            val moved = RouteProjection.distanceMetres(
                GeoPoint(failed.latitude, failed.longitude),
                GeoPoint(latest.latitude, latest.longitude),
            ) - failed.horizontalAccuracyMetres - latest.horizontalAccuracyMetres
            if (moved < reroutingPolicy.requiredMovementBeyondAccuracyMetres) {
                lastFailure = "Move farther before retrying a no-route result"
                return null
            }
        }
        return offRoute.manualRequest(clock.elapsedRealtimeMillis())?.let(::newRerouteContext)
    }

    fun updateProviderRequestCount(count: Int): NavigationSessionSnapshot {
        localRequestCount = count
        return snapshot()
    }

    fun onLocationDisabled(): NavigationSessionSnapshot {
        val navigation = engine?.locationDisabled()
        return snapshot(navigation)
    }

    fun onLocationPermissionRevoked(): NavigationSessionSnapshot {
        lastFailure = "Precise location permission was revoked; reopen the app to continue"
        val navigation = engine?.waitingForPermission()
        return snapshot(navigation)
    }

    fun onLocationError(message: String): NavigationSessionSnapshot {
        lastFailure = message
        return snapshot()
    }

    fun onTransportState(state: TransportState): NavigationSessionSnapshot {
        transportState = state
        return snapshot()
    }

    fun checkForGpsLoss(): NavigationSessionSnapshot {
        val navigation = if (phase == SessionPhase.ACTIVE) engine?.checkForGpsLoss() else null
        return snapshot(navigation)
    }

    fun stop(failure: String? = null): NavigationSessionSnapshot {
        sessionGeneration++
        requestGeneration++
        inFlight = null
        offRoute.cancel()
        lastFailure = failure
        val navigation = engine?.stop()
        phase = if (failure == null) SessionPhase.IDLE else SessionPhase.FAILED
        engine = null
        route = null
        originalRequest = null
        return snapshot(navigation)
    }

    fun snapshot(navigation: NavigationSnapshot? = engine?.snapshot()): NavigationSessionSnapshot =
        NavigationSessionSnapshot(
            phase = phase,
            navigation = navigation,
            routeRevision = route?.revision,
            destinationLabel = destinationLabel,
            startedAtWallClockMillis = startedAtWallClockMillis,
            transportState = transportState,
            providerRequestCount = localRequestCount,
            sessionRerouteCount = offRoute.automaticAttempts,
            offRouteState = offRoute.state,
            reroutingPolicy = "${offRoute.lastDecision}; ${reroutingPolicy.diagnostics()}",
            lastFailure = lastFailure,
        )

    private fun newRerouteContext(kind: RerouteAttemptKind): RerouteRequestContext? {
        val origin = engine?.latestAcceptedLocation ?: return null
        val currentRoute = route ?: return null
        requestGeneration++
        return RerouteRequestContext(
            sessionId = sessionId,
            sessionGeneration = sessionGeneration,
            requestGeneration = requestGeneration,
            startingRouteRevision = currentRoute.revision,
            origin = origin,
            destination = requireNotNull(originalRequest).destination,
            kind = kind,
            budgetDecision = "pending local daily/monthly check",
        )
    }

    private fun accepts(context: RerouteRequestContext): Boolean =
        context.sessionId == sessionId && context.sessionGeneration == sessionGeneration &&
            context.requestGeneration == requestGeneration && context.startingRouteRevision == route?.revision

    private fun staleReplacement(context: RerouteRequestContext, reason: String): RerouteCompletion {
        lastFailure = reason
        lastFailedOrigin = context.origin
        offRoute.requestFailed(RerouteFailureKind.STALE_RESPONSE, clock.elapsedRealtimeMillis())
        val navigation = engine?.resumeAfterReroute(reason)
        return RerouteCompletion(false, snapshot(navigation), reason)
    }

    private fun invalidReplacement(context: RerouteRequestContext, reason: String): RerouteCompletion {
        lastFailure = reason
        lastFailedOrigin = context.origin
        offRoute.requestFailed(RerouteFailureKind.MALFORMED, clock.elapsedRealtimeMillis())
        val navigation = engine?.resumeAfterReroute(reason)
        return RerouteCompletion(false, snapshot(navigation), reason)
    }
}
