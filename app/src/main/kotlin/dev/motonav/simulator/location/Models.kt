package dev.motonav.simulator.location

data class GeoLocationSample(
    val latitude: Double,
    val longitude: Double,
    val wallClockMillis: Long,
    val elapsedRealtimeMillis: Long,
    val horizontalAccuracyMetres: Double,
    val bearingDegrees: Double? = null,
    val bearingAccuracyDegrees: Double? = null,
    val speedMetresPerSecond: Double? = null,
    val speedAccuracyMetresPerSecond: Double? = null,
    val isMock: Boolean = false,
)

fun interface MonotonicClock {
    fun elapsedRealtimeMillis(): Long
}

interface LocationSource {
    interface Listener {
        fun onSamples(samples: List<GeoLocationSample>)
        fun onLocationDisabled(resolution: (() -> Unit)?)
        fun onError(message: String)
    }

    fun start(listener: Listener)
    fun stop()
}

data class LocationQualityPolicy(
    val maximumAgeMillis: Long = 10_000,
    val maximumAccuracyMetres: Double = 50.0,
    val maximumImpliedSpeedMetresPerSecond: Double = 80.0,
    /** Speed is only an immediate movement hint; slower travel can still move by displacement. */
    val stationarySpeedMetresPerSecond: Double = 0.5,
    val minimumCumulativeMovementMetres: Double = 3.0,
    /** Bounds display latency when Android reports a large horizontal-accuracy radius. */
    val maximumMovementThresholdMetres: Double = 5.0,
    val movementAccuracyMultiplier: Double = 0.6,
    val gpsLossTimeoutMillis: Long = 20_000,
) {
    init {
        require(maximumAgeMillis > 0 && maximumAccuracyMetres > 0.0)
        require(maximumImpliedSpeedMetresPerSecond > 0.0)
        require(stationarySpeedMetresPerSecond >= 0.0 && minimumCumulativeMovementMetres > 0.0)
        require(maximumMovementThresholdMetres >= minimumCumulativeMovementMetres)
        require(movementAccuracyMultiplier in 0.0..1.0)
        require(gpsLossTimeoutMillis >= maximumAgeMillis)
    }
}

enum class RejectionReason {
    INVALID_COORDINATE,
    STALE,
    INACCURATE,
    DUPLICATE,
    BACKWARD_TIMESTAMP,
    IMPLAUSIBLE_JUMP,
}

sealed interface QualityDecision {
    data class Accepted(val sample: GeoLocationSample, val stationary: Boolean) : QualityDecision
    data class Rejected(val reason: RejectionReason) : QualityDecision
}
