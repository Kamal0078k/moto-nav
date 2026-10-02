package dev.motonav.simulator.location

import dev.motonav.simulator.navigation.LocationSample
import dev.motonav.simulator.navigation.Maneuver
import dev.motonav.simulator.navigation.MeasuredRoute
import dev.motonav.simulator.navigation.NavigationSnapshot
import dev.motonav.simulator.navigation.NavigationState
import dev.motonav.simulator.navigation.PointMetres
import dev.motonav.simulator.routing.RoadRoute
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

data class RouteMatchObservation(
    val sample: GeoLocationSample,
    val distanceFromRouteMetres: Double,
    val routeHeadingDegrees: Double,
    val stationary: Boolean,
)

data class LiveNavigationUpdate(
    val snapshot: NavigationSnapshot,
    /** Null means the fix was rejected by the shared location-quality filter. */
    val observation: RouteMatchObservation?,
)

class LiveNavigationEngine(
    val roadRoute: RoadRoute,
    private val clock: MonotonicClock,
    private val qualityPolicy: LocationQualityPolicy = LocationQualityPolicy(),
    private val replacementRoute: Boolean = false,
) {
    private val index = GeographicRouteIndex(roadRoute)
    private val quality = LocationQualityFilter(clock, qualityPolicy)
    private val measured = MeasuredRoute(index.projected.routeGeometry)
    private var state = NavigationState.WAITING_PERMISSION
    private var committedProgress = 0.0
    private var rawCandidateProgress: Double? = null
    private var lastAccepted: GeoLocationSample? = null
    private var lastReliableHeading: Double? = null
    private var deviceHeading: Double? = null
    private var lastMovementSample: GeoLocationSample? = null
    private var mismatchCount = 0
    private val firstManeuverIndex = initialManeuverIndex()
    private var nextManeuverIndex = firstManeuverIndex
    private var reverseMode = false
    private var reverseEvidenceFixes = 0
    private var reverseEvidenceMetres = 0.0
    private var reverseEvidenceLastProgress: Double? = null
    private var forwardEvidenceFixes = 0
    private var forwardEvidenceMetres = 0.0
    private var forwardEvidenceLastProgress: Double? = null
    private var lookaheadMetres = 250.0
    private var lastDecision = "waiting for precise location permission"

    val latestAcceptedLocation: GeoLocationSample? get() = lastAccepted

    fun waitingForPermission(): NavigationSnapshot {
        state = NavigationState.WAITING_PERMISSION
        lastDecision = "precise location permission required"
        return snapshot()
    }

    fun locationDisabled(): NavigationSnapshot {
        state = NavigationState.LOCATION_DISABLED
        lastDecision = "phone location services disabled"
        return snapshot()
    }

    fun start(): NavigationSnapshot {
        quality.reset()
        resetDirectionEvidence()
        reverseMode = false
        state = NavigationState.ACQUIRING_GPS
        lastDecision = "waiting for first usable GPS fix"
        return snapshot()
    }

    fun stop(): NavigationSnapshot {
        state = NavigationState.DISCONNECTED
        lastDecision = "live navigation stopped"
        return snapshot()
    }

    /** Batches are always evaluated in sensor-monotonic order. */
    fun processBatch(samples: List<GeoLocationSample>): List<NavigationSnapshot> = samples
        .sortedBy { it.elapsedRealtimeMillis }
        .map { processDetailed(it).snapshot }

    fun processDetailed(sample: GeoLocationSample): LiveNavigationUpdate = process(sample)

    fun rerouting(message: String): NavigationSnapshot {
        state = NavigationState.REROUTING
        lastDecision = message
        return snapshot()
    }

    fun resumeAfterReroute(message: String): NavigationSnapshot {
        if (state == NavigationState.REROUTING) state = NavigationState.NAVIGATING
        lastDecision = message
        return snapshot()
    }

    fun updateDeviceHeading(headingDegrees: Double): NavigationSnapshot {
        require(headingDegrees.isFinite())
        deviceHeading = GeographicRouteIndex.normalizeDegrees(headingDegrees)
        return snapshot()
    }

    fun checkForGpsLoss(): NavigationSnapshot {
        val last = lastAccepted
        if (state !in setOf(NavigationState.DISCONNECTED, NavigationState.WAITING_PERMISSION, NavigationState.LOCATION_DISABLED, NavigationState.ARRIVED) &&
            (last == null || clock.elapsedRealtimeMillis() - last.elapsedRealtimeMillis > qualityPolicy.gpsLossTimeoutMillis)
        ) {
            state = if (last == null) NavigationState.ACQUIRING_GPS else NavigationState.GPS_DEGRADED
            lastDecision = if (last == null) "waiting for first usable GPS fix" else "GPS fix is stale"
        }
        return snapshot()
    }

    fun snapshot(): NavigationSnapshot {
        val next = index.projected.routeGeometry.maneuvers.getOrNull(nextManeuverIndex)
        val maneuver = next?.maneuver ?: Maneuver.ARRIVE
        val maneuverDistance = next?.routeDistanceMetres ?: measured.totalDistance
        val local = lastAccepted?.let { index.point(it) }
        val remainingMetres = (measured.totalDistance - committedProgress).coerceAtLeast(0.0)
        val remainingDurationMillis = if (roadRoute.totalDurationMillis > 0 && measured.totalDistance > 0.0) {
            (roadRoute.totalDurationMillis * (remainingMetres / measured.totalDistance))
                .roundToLong()
                .coerceAtLeast(0L)
        } else null
        return NavigationSnapshot(
            state = state,
            route = index.projected.routeGeometry,
            location = local?.let { LocationSample(it, lastAccepted!!.elapsedRealtimeMillis, lastAccepted!!.horizontalAccuracyMetres) },
            progressMetres = committedProgress,
            maneuver = maneuver,
            metresToManeuver = (maneuverDistance - committedProgress).coerceAtLeast(0.0),
            remainingMetres = remainingMetres,
            routeLabel = "Google motorcycle route · live GPS",
            isReplacementRoute = replacementRoute,
            contentSource = roadRoute.source,
            headingDegrees = deviceHeading ?: lastReliableHeading ?: routeHeadingAt(committedProgress),
            lookaheadMetres = lookaheadMetres,
            diagnostic = "$lastDecision; raw=${rawCandidateProgress?.let { "%.1f m".format(it) } ?: "none"}",
            remainingDurationMillis = remainingDurationMillis,
        )
    }

    private fun process(sample: GeoLocationSample): LiveNavigationUpdate {
        val rerouteInProgress = state == NavigationState.REROUTING
        when (val decision = quality.evaluate(sample)) {
            is QualityDecision.Rejected -> {
                lastDecision = "ignored ${decision.reason.name.lowercase().replace('_', ' ')} fix"
                return LiveNavigationUpdate(snapshot(), null)
            }
            is QualityDecision.Accepted -> {
                lastAccepted = sample
                val searchAll = committedProgress == 0.0 || mismatchCount >= 3
                val candidate = index.bestCandidate(sample, committedProgress.takeIf { rawCandidateProgress != null }, searchAll)
                if (candidate == null) {
                    mismatchCount++
                    rawCandidateProgress = null
                    if (!rerouteInProgress && state == NavigationState.GPS_DEGRADED) {
                        state = NavigationState.NAVIGATING
                    }
                    lastDecision = "usable fix did not match route corridor"
                    updateHeading(sample, decision.stationary, null)
                    val nearest = index.nearestPlausibleCandidate(sample, committedProgress)
                    return LiveNavigationUpdate(
                        snapshot(),
                        nearest?.let {
                            RouteMatchObservation(
                                sample,
                                it.distanceFromRouteMetres,
                                index.segments[it.segmentIndex].headingDegrees,
                                decision.stationary,
                            )
                        },
                    )
                }

                mismatchCount = 0
                val acquiring = rawCandidateProgress == null
                rawCandidateProgress = candidate.routeDistanceMetres
                if (acquiring) {
                    committedProgress = maxOf(committedProgress, candidate.routeDistanceMetres)
                } else if (!decision.stationary) {
                    updateCommittedProgress(candidate.routeDistanceMetres)
                }
                updateHeading(sample, decision.stationary, candidate)
                advanceOneManeuverBoundary()
                updateLookahead(sample)
                val arrivalManeuverReached = nextManeuverIndex >= index.projected.routeGeometry.maneuvers.lastIndex
                state = if (arrivalManeuverReached && committedProgress >= measured.totalDistance - 5.0) {
                    NavigationState.ARRIVED
                } else if (rerouteInProgress) {
                    NavigationState.REROUTING
                } else {
                    NavigationState.NAVIGATING
                }
                lastDecision = "accepted fix ${"%.1f".format(candidate.distanceFromRouteMetres)} m from route"
                return LiveNavigationUpdate(
                    snapshot(),
                    RouteMatchObservation(
                        sample,
                        candidate.distanceFromRouteMetres,
                        index.segments[candidate.segmentIndex].headingDegrees,
                        decision.stationary,
                    ),
                )
            }
        }
    }

    private fun advanceOneManeuverBoundary() {
        val next = index.projected.routeGeometry.maneuvers.getOrNull(nextManeuverIndex) ?: return
        if (committedProgress >= next.routeDistanceMetres + 5.0) nextManeuverIndex++
    }

    private fun updateCommittedProgress(candidateProgress: Double) {
        if (reverseMode) {
            when {
                candidateProgress < committedProgress -> {
                    committedProgress = candidateProgress
                    resetForwardEvidence()
                    rewindManeuverBoundaries()
                }
                candidateProgress > committedProgress + DIRECTION_EPSILON_METRES -> {
                    val priorEvidence = forwardEvidenceLastProgress
                    if (priorEvidence == null || candidateProgress > priorEvidence + DIRECTION_EPSILON_METRES) {
                        forwardEvidenceFixes++
                    } else {
                        forwardEvidenceFixes = 1
                    }
                    forwardEvidenceLastProgress = candidateProgress
                    forwardEvidenceMetres = candidateProgress - committedProgress
                    if (forwardEvidenceFixes >= REQUIRED_DIRECTION_FIXES &&
                        forwardEvidenceMetres >= DIRECTION_CONFIRMATION_METRES - DIRECTION_EPSILON_METRES
                    ) {
                        reverseMode = false
                        committedProgress = maxOf(committedProgress, candidateProgress)
                        resetDirectionEvidence()
                    }
                }
                else -> resetForwardEvidence()
            }
            return
        }

        if (candidateProgress >= committedProgress) {
            committedProgress = candidateProgress
            resetReverseEvidence()
            return
        }
        if (candidateProgress < committedProgress - DIRECTION_EPSILON_METRES) {
            val priorEvidence = reverseEvidenceLastProgress
            if (priorEvidence == null || candidateProgress < priorEvidence - DIRECTION_EPSILON_METRES) {
                reverseEvidenceFixes++
            } else {
                reverseEvidenceFixes = 1
            }
            reverseEvidenceLastProgress = candidateProgress
            reverseEvidenceMetres = committedProgress - candidateProgress
            if (reverseEvidenceFixes >= REQUIRED_DIRECTION_FIXES &&
                reverseEvidenceMetres >= DIRECTION_CONFIRMATION_METRES - DIRECTION_EPSILON_METRES
            ) {
                reverseMode = true
                committedProgress = candidateProgress
                resetDirectionEvidence()
                rewindManeuverBoundaries()
            }
        } else resetReverseEvidence()
    }

    private fun rewindManeuverBoundaries() {
        while (nextManeuverIndex > firstManeuverIndex) {
            val previous = index.projected.routeGeometry.maneuvers[nextManeuverIndex - 1]
            if (committedProgress >= previous.routeDistanceMetres + 5.0) break
            nextManeuverIndex--
        }
    }

    private fun resetDirectionEvidence() {
        resetReverseEvidence()
        resetForwardEvidence()
    }

    private fun resetReverseEvidence() {
        reverseEvidenceFixes = 0
        reverseEvidenceMetres = 0.0
        reverseEvidenceLastProgress = null
    }

    private fun resetForwardEvidence() {
        forwardEvidenceFixes = 0
        forwardEvidenceMetres = 0.0
        forwardEvidenceLastProgress = null
    }

    private fun updateHeading(sample: GeoLocationSample, stationary: Boolean, candidate: MatchCandidate?) {
        if (stationary) return
        val bearing = sample.bearingDegrees?.takeIf {
            (sample.speedMetresPerSecond ?: 0.0) >= 2.5 && (sample.bearingAccuracyDegrees ?: 180.0) <= 30.0
        } ?: movementBearing(sample)
            ?: candidate?.let { index.segments[it.segmentIndex].headingDegrees }
            ?: return
        lastReliableHeading = lastReliableHeading?.let { smoothDegrees(it, bearing, 0.25) } ?: bearing
    }

    private fun movementBearing(sample: GeoLocationSample): Double? {
        val previous = lastMovementSample
        lastMovementSample = sample
        if (previous == null) return null
        val from = index.point(previous)
        val to = index.point(sample)
        val distance = from.distanceTo(to)
        if (distance <= maxOf(5.0, previous.horizontalAccuracyMetres + sample.horizontalAccuracyMetres)) return null
        return GeographicRouteIndex.normalizeDegrees(Math.toDegrees(atan2(to.x - from.x, to.y - from.y)))
    }

    private fun updateLookahead(sample: GeoLocationSample) {
        val distanceToTurn = index.projected.routeGeometry.maneuvers.getOrNull(nextManeuverIndex)
            ?.routeDistanceMetres?.minus(committedProgress) ?: measured.totalDistance - committedProgress
        val speed = sample.speedMetresPerSecond ?: 0.0
        val tightTurnAhead = isTightTurn(nextManeuverIndex) && distanceToTurn < 100.0
        val target = when {
            distanceToTurn < 60.0 || tightTurnAhead || speed < 5.0 -> 200.0
            distanceToTurn > 120.0 && speed > 15.0 -> 300.0
            else -> 250.0
        }
        if (kotlin.math.abs(target - lookaheadMetres) >= 15.0) {
            lookaheadMetres += (target - lookaheadMetres).coerceIn(-10.0, 10.0)
        }
    }

    private fun isTightTurn(maneuverIndex: Int): Boolean {
        val maneuverDistance = index.projected.routeGeometry.maneuvers.getOrNull(maneuverIndex)
            ?.routeDistanceMetres ?: return false
        val after = index.segments.indexOfFirst { it.cumulativeStartMetres >= maneuverDistance - 0.5 }
        if (after <= 0 || after >= index.segments.size) return false
        return GeographicRouteIndex.angularDifference(
            index.segments[after - 1].headingDegrees,
            index.segments[after].headingDegrees,
        ) >= 45.0
    }

    private fun initialManeuverIndex(): Int = index.projected.routeGeometry.maneuvers
        .indexOfFirst { it.routeDistanceMetres > 1.0 }
        .let { if (it < 0) index.projected.routeGeometry.maneuvers.size else it }

    private fun routeHeadingAt(distance: Double): Double {
        val vector: PointMetres = measured.forwardHeading(distance)
        return GeographicRouteIndex.normalizeDegrees(Math.toDegrees(atan2(vector.x, vector.y)))
    }

    private fun smoothDegrees(previous: Double, next: Double, alpha: Double): Double {
        val previousRadians = Math.toRadians(previous)
        val nextRadians = Math.toRadians(next)
        val x = (1.0 - alpha) * sin(previousRadians) + alpha * sin(nextRadians)
        val y = (1.0 - alpha) * cos(previousRadians) + alpha * cos(nextRadians)
        return GeographicRouteIndex.normalizeDegrees(Math.toDegrees(atan2(x, y)))
    }

    companion object {
        private const val DIRECTION_EPSILON_METRES = 1.0
        private const val DIRECTION_CONFIRMATION_METRES = 10.0
        private const val REQUIRED_DIRECTION_FIXES = 2
    }
}
