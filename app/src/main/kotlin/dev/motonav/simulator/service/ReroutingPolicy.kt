package dev.motonav.simulator.service

import dev.motonav.simulator.location.RouteMatchObservation
import dev.motonav.simulator.routing.RouteProjection
import kotlin.math.max

data class ReroutingPolicy(
    val minimumOffRouteMetres: Double = 25.0,
    val accuracyMultiplier: Double = 2.0,
    val requiredOffRouteFixes: Int = 3,
    val requiredEvidenceSpanMillis: Long = 5_000L,
    val requiredMovementBeyondAccuracyMetres: Double = 15.0,
    val reliableSpeedMetresPerSecond: Double = 2.5,
    val maximumReliableSpeedAccuracyMetresPerSecond: Double = 1.5,
    val maximumFixAccuracyMetres: Double = 50.0,
    val credibleRecoveryFixes: Int = 2,
    val highConfidenceCaptureMetres: Double = 10.0,
    val automaticCooldownMillis: Long = 30_000L,
    val transientRetryMinimumMillis: Long = 10_000L,
    val maximumAutomaticReroutes: Int = 20,
    val staleOriginMillis: Long = 60_000L,
    val staleMovementMetres: Double = 100.0,
    val destinationConsistencyMetres: Double = 100.0,
    val originCaptureMetres: Double = 100.0,
    val arrivalFreezeMetres: Double = 30.0,
) {
    init {
        require(requiredOffRouteFixes >= 2 && requiredEvidenceSpanMillis > 0)
        require(automaticCooldownMillis >= transientRetryMinimumMillis)
        require(maximumAutomaticReroutes > 0)
    }

    fun diagnostics(): String =
        "off>${minimumOffRouteMetres.toInt()}m/2xacc, fixes=$requiredOffRouteFixes/${requiredEvidenceSpanMillis / 1_000}s, " +
            "move>${requiredMovementBeyondAccuracyMetres.toInt()}m or ${reliableSpeedMetresPerSecond}m/s, " +
            "cooldown=${automaticCooldownMillis / 1_000}s, cap=$maximumAutomaticReroutes"
}

enum class OffRouteState { ON_ROUTE, SUSPECT_OFF_ROUTE, REROUTING, COOLDOWN }
enum class RerouteAttemptKind { AUTOMATIC, TRANSIENT_RETRY, MANUAL }

enum class RerouteFailureKind {
    NETWORK_UNAVAILABLE,
    TRANSIENT,
    RATE_LIMITED,
    AUTHENTICATION,
    BUDGET,
    INVALID_REQUEST,
    NO_ROUTE,
    MALFORMED,
    STALE_RESPONSE,
    CANCELLED,
}

/** Pure deterministic evidence/cooldown state. Provider work is owned by NavigationService. */
class OffRouteReroutingStateMachine(val policy: ReroutingPolicy = ReroutingPolicy()) {
    var state: OffRouteState = OffRouteState.ON_ROUTE
        private set
    var automaticAttempts: Int = 0
        private set
    var lastDecision: String = "on route"
        private set
    var cooldownUntilMillis: Long = Long.MIN_VALUE
        private set

    private var firstOffRoute: RouteMatchObservation? = null
    private var offRouteFixes = 0
    private var recoveryFixes = 0
    private var previousOffRouteDistance: Double? = null
    private var retryAvailable = false
    private var transientRetryUsed = false
    private var permanentlyBlocked = false

    fun observe(
        observation: RouteMatchObservation,
        remainingMetres: Double,
        nowMillis: Long,
    ): RerouteAttemptKind? {
        if (remainingMetres <= policy.arrivalFreezeMetres) {
            clearEvidence("arrival decision active")
            return null
        }
        if (state == OffRouteState.REROUTING || permanentlyBlocked) return null
        if (observation.sample.horizontalAccuracyMetres > policy.maximumFixAccuracyMetres) return null

        val threshold = max(
            policy.minimumOffRouteMetres,
            policy.accuracyMultiplier * observation.sample.horizontalAccuracyMetres,
        )
        val offRoute = observation.distanceFromRouteMetres > threshold
        if (!offRoute) {
            if (state == OffRouteState.COOLDOWN && nowMillis >= cooldownUntilMillis) {
                state = OffRouteState.ON_ROUTE
                retryAvailable = false
                transientRetryUsed = false
            }
            val highConfidence = observation.distanceFromRouteMetres <= max(
                policy.highConfidenceCaptureMetres,
                observation.sample.horizontalAccuracyMetres,
            )
            recoveryFixes++
            if (highConfidence || recoveryFixes >= policy.credibleRecoveryFixes) {
                clearEvidence("credible route capture recovered")
            } else {
                lastDecision = "one credible on-route recovery fix"
            }
            return null
        }
        recoveryFixes = 0

        if (state == OffRouteState.COOLDOWN) {
            if (nowMillis < cooldownUntilMillis) {
                lastDecision = "off route; cooldown ${(cooldownUntilMillis - nowMillis + 999) / 1_000}s"
                return null
            }
            if (retryAvailable && !transientRetryUsed && automaticAttempts < policy.maximumAutomaticReroutes) {
                transientRetryUsed = true
                retryAvailable = false
                state = OffRouteState.REROUTING
                lastDecision = "continued deviation triggered bounded retry"
                return RerouteAttemptKind.TRANSIENT_RETRY
            }
            state = OffRouteState.ON_ROUTE
            resetSuspicion()
        }

        if (automaticAttempts >= policy.maximumAutomaticReroutes) {
            permanentlyBlocked = true
            lastDecision = "automatic reroute session cap reached"
            return null
        }

        if (state == OffRouteState.ON_ROUTE) {
            state = OffRouteState.SUSPECT_OFF_ROUTE
            firstOffRoute = observation
            offRouteFixes = 1
            previousOffRouteDistance = observation.distanceFromRouteMetres
            lastDecision = "suspected off route 1/${policy.requiredOffRouteFixes}"
            return null
        }

        offRouteFixes++
        val first = requireNotNull(firstOffRoute)
        val span = observation.sample.elapsedRealtimeMillis - first.sample.elapsedRealtimeMillis
        val displacement = RouteProjection.distanceMetres(
            dev.motonav.simulator.routing.GeoPoint(first.sample.latitude, first.sample.longitude),
            dev.motonav.simulator.routing.GeoPoint(observation.sample.latitude, observation.sample.longitude),
        )
        val displacementBeyondAccuracy = displacement -
            first.sample.horizontalAccuracyMetres - observation.sample.horizontalAccuracyMetres
        val speedReliable = (observation.sample.speedMetresPerSecond ?: 0.0) > policy.reliableSpeedMetresPerSecond &&
            (observation.sample.speedAccuracyMetresPerSecond ?: Double.POSITIVE_INFINITY) <=
            policy.maximumReliableSpeedAccuracyMetresPerSecond
        val moving = displacementBeyondAccuracy >= policy.requiredMovementBeyondAccuracyMetres || speedReliable
        val headingAway = observation.sample.bearingDegrees?.let {
            dev.motonav.simulator.location.GeographicRouteIndex.angularDifference(it, observation.routeHeadingDegrees) >= 60.0
        } == true
        val increasing = previousOffRouteDistance?.let { observation.distanceFromRouteMetres > it + 2.0 } == true
        previousOffRouteDistance = observation.distanceFromRouteMetres

        if (offRouteFixes >= policy.requiredOffRouteFixes &&
            span >= policy.requiredEvidenceSpanMillis && moving
        ) {
            state = OffRouteState.REROUTING
            lastDecision = "confirmed deviation" + when {
                headingAway && increasing -> "; heading away and distance increasing"
                headingAway -> "; heading away"
                increasing -> "; cross-track distance increasing"
                else -> ""
            }
            return RerouteAttemptKind.AUTOMATIC
        }
        lastDecision = "suspected off route $offRouteFixes/${policy.requiredOffRouteFixes}; " +
            "${span / 1_000}s; ${if (moving) "moving" else "movement unconfirmed"}"
        return null
    }

    fun requestStarted(kind: RerouteAttemptKind) {
        check(state == OffRouteState.REROUTING)
        if (kind != RerouteAttemptKind.MANUAL) automaticAttempts++
        lastDecision = "replacement request in flight"
    }

    fun requestDeferredBeforeSend(reason: String) {
        state = OffRouteState.ON_ROUTE
        resetSuspicion()
        lastDecision = reason
    }

    fun requestSucceeded(nowMillis: Long) {
        state = OffRouteState.COOLDOWN
        cooldownUntilMillis = nowMillis + policy.automaticCooldownMillis
        retryAvailable = false
        transientRetryUsed = false
        resetSuspicion()
        lastDecision = "replacement route active; cooldown"
    }

    fun requestFailed(kind: RerouteFailureKind, nowMillis: Long, retryAfterMillis: Long? = null) {
        state = OffRouteState.COOLDOWN
        cooldownUntilMillis = max(
            nowMillis + policy.automaticCooldownMillis,
            retryAfterMillis?.let { nowMillis + it } ?: Long.MIN_VALUE,
        )
        retryAvailable = kind == RerouteFailureKind.TRANSIENT && !transientRetryUsed
        if (kind == RerouteFailureKind.AUTHENTICATION || kind == RerouteFailureKind.BUDGET) permanentlyBlocked = true
        resetSuspicion()
        lastDecision = when (kind) {
            RerouteFailureKind.TRANSIENT -> "replacement failed; one bounded retry available after cooldown"
            RerouteFailureKind.RATE_LIMITED -> "provider rate limited; automatic retry paused"
            RerouteFailureKind.AUTHENTICATION -> "provider authentication/configuration blocked"
            RerouteFailureKind.BUDGET -> "routing request budget blocked"
            RerouteFailureKind.NO_ROUTE -> "no replacement route found"
            RerouteFailureKind.STALE_RESPONSE -> "replacement response became stale"
            else -> "replacement failed: ${kind.name.lowercase().replace('_', ' ')}"
        }
    }

    fun manualRequest(nowMillis: Long): RerouteAttemptKind? {
        if (state == OffRouteState.REROUTING || permanentlyBlocked || nowMillis < cooldownUntilMillis) return null
        state = OffRouteState.REROUTING
        lastDecision = "manual replacement requested"
        return RerouteAttemptKind.MANUAL
    }

    fun cancel() {
        state = OffRouteState.ON_ROUTE
        permanentlyBlocked = false
        retryAvailable = false
        transientRetryUsed = false
        automaticAttempts = 0
        cooldownUntilMillis = Long.MIN_VALUE
        clearEvidence("session stopped")
    }

    private fun clearEvidence(reason: String) {
        if (state != OffRouteState.COOLDOWN && state != OffRouteState.REROUTING) state = OffRouteState.ON_ROUTE
        resetSuspicion()
        lastDecision = reason
    }

    private fun resetSuspicion() {
        firstOffRoute = null
        offRouteFixes = 0
        recoveryFixes = 0
        previousOffRouteDistance = null
    }
}
