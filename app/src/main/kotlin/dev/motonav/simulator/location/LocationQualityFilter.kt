package dev.motonav.simulator.location

import dev.motonav.simulator.routing.GeoPoint
import dev.motonav.simulator.routing.RouteProjection

class LocationQualityFilter(
    private val clock: MonotonicClock,
    val policy: LocationQualityPolicy = LocationQualityPolicy(),
) {
    private var previousAccepted: GeoLocationSample? = null
    private var movementAnchor: GeoLocationSample? = null

    fun evaluate(sample: GeoLocationSample): QualityDecision {
        if (!sample.latitude.isFinite() || !sample.longitude.isFinite() ||
            sample.latitude !in -90.0..90.0 || sample.longitude !in -180.0..180.0 ||
            !sample.horizontalAccuracyMetres.isFinite() || sample.horizontalAccuracyMetres < 0.0
        ) return QualityDecision.Rejected(RejectionReason.INVALID_COORDINATE)

        val age = clock.elapsedRealtimeMillis() - sample.elapsedRealtimeMillis
        if (age > policy.maximumAgeMillis) return QualityDecision.Rejected(RejectionReason.STALE)
        if (sample.horizontalAccuracyMetres > policy.maximumAccuracyMetres) {
            return QualityDecision.Rejected(RejectionReason.INACCURATE)
        }

        val previous = previousAccepted
        if (previous != null) {
            if (sample.elapsedRealtimeMillis < previous.elapsedRealtimeMillis) {
                return QualityDecision.Rejected(RejectionReason.BACKWARD_TIMESTAMP)
            }
            if (sample.elapsedRealtimeMillis == previous.elapsedRealtimeMillis &&
                sample.latitude == previous.latitude && sample.longitude == previous.longitude
            ) return QualityDecision.Rejected(RejectionReason.DUPLICATE)

            val elapsedSeconds = (sample.elapsedRealtimeMillis - previous.elapsedRealtimeMillis) / 1_000.0
            if (elapsedSeconds > 0.0) {
                val distance = RouteProjection.distanceMetres(
                    GeoPoint(previous.latitude, previous.longitude),
                    GeoPoint(sample.latitude, sample.longitude),
                )
                val unexplainedDistance = (distance - previous.horizontalAccuracyMetres - sample.horizontalAccuracyMetres)
                    .coerceAtLeast(0.0)
                if (unexplainedDistance / elapsedSeconds > policy.maximumImpliedSpeedMetresPerSecond) {
                    return QualityDecision.Rejected(RejectionReason.IMPLAUSIBLE_JUMP)
                }
            }
        }

        val movingBySpeed = sample.speedMetresPerSecond?.let {
            it >= policy.stationarySpeedMetresPerSecond
        } == true
        val anchor = movementAnchor
        val movingByAccumulatedDisplacement = anchor?.let {
            val distance = RouteProjection.distanceMetres(
                GeoPoint(it.latitude, it.longitude),
                GeoPoint(sample.latitude, sample.longitude),
            )
            val accuracyBasedThreshold = maxOf(
                it.horizontalAccuracyMetres,
                sample.horizontalAccuracyMetres,
            ) * policy.movementAccuracyMultiplier
            val movementThreshold = maxOf(
                policy.minimumCumulativeMovementMetres,
                minOf(policy.maximumMovementThresholdMetres, accuracyBasedThreshold),
            )
            distance >= movementThreshold
        } ?: false
        val stationary = !movingBySpeed && !movingByAccumulatedDisplacement
        if (movementAnchor == null || !stationary) movementAnchor = sample
        previousAccepted = sample
        return QualityDecision.Accepted(sample, stationary)
    }

    fun reset() {
        previousAccepted = null
        movementAnchor = null
    }
}
