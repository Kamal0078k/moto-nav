package dev.motonav.simulator.location

import dev.motonav.simulator.routing.GeoPoint
import kotlin.math.cos

data class RelativeTraceSample(
    val eastMetres: Double,
    val northMetres: Double,
    val elapsedMillis: Long,
    val accuracyMetres: Double,
    val speedMetresPerSecond: Double? = null,
    val bearingDegrees: Double? = null,
)

data class TraceFixture(
    val name: String,
    val syntheticOrigin: GeoPoint,
    val samples: List<RelativeTraceSample>,
) {
    fun geoSamples(wallClockStartMillis: Long = 0L): List<GeoLocationSample> = samples.map { sample ->
        val latitudeScale = Math.PI * 6_371_000.0 / 180.0
        GeoLocationSample(
            latitude = syntheticOrigin.latitude + sample.northMetres / latitudeScale,
            longitude = syntheticOrigin.longitude + sample.eastMetres /
                (latitudeScale * cos(Math.toRadians(syntheticOrigin.latitude))),
            wallClockMillis = wallClockStartMillis + sample.elapsedMillis,
            elapsedRealtimeMillis = sample.elapsedMillis,
            horizontalAccuracyMetres = sample.accuracyMetres,
            speedMetresPerSecond = sample.speedMetresPerSecond,
            bearingDegrees = sample.bearingDegrees,
            bearingAccuracyDegrees = sample.bearingDegrees?.let { 10.0 },
        )
    }
}

class TracePlayer(private val trace: TraceFixture) {
    var speedMultiplier: Double = 1.0
        set(value) { require(value > 0.0); field = value }
    var paused: Boolean = true
        private set
    private var playheadMillis = 0.0
    private var nextIndex = 0

    fun play() { paused = false }
    fun pause() { paused = true }
    fun seekToStart() { playheadMillis = 0.0; nextIndex = 0 }

    fun advance(realDeltaMillis: Long): List<RelativeTraceSample> {
        require(realDeltaMillis >= 0)
        if (paused) return emptyList()
        playheadMillis += realDeltaMillis * speedMultiplier
        val emitted = mutableListOf<RelativeTraceSample>()
        while (nextIndex < trace.samples.size && trace.samples[nextIndex].elapsedMillis <= playheadMillis) {
            emitted += trace.samples[nextIndex++]
        }
        return emitted
    }
}

object TraceFixtures {
    private val origin = GeoPoint(12.0, 77.0)
    private fun point(east: Double, north: Double, time: Long, accuracy: Double = 3.0, speed: Double? = 10.0, bearing: Double? = 0.0) =
        RelativeTraceSample(east, north, time, accuracy, speed, bearing)

    val stationaryNoise = TraceFixture("stationary-noise", origin, listOf(
        point(0.0, 0.0, 0, speed = 0.0), point(2.0, -1.0, 1_000, speed = 0.2), point(-2.0, 1.0, 2_000, speed = 0.0),
    ))
    val cleanRoute = TraceFixture("clean-route", origin, (0..8).map { point(0.0, it * 25.0, it * 2_500L) })
    val parallelRoad = TraceFixture("parallel-road", origin, (0..5).map { point(18.0, it * 25.0, it * 2_500L) })
    val loopCrossing = TraceFixture("loop-crossing", origin, listOf(
        point(0.0, 0.0, 0), point(0.0, 40.0, 4_000), point(40.0, 40.0, 8_000, bearing = 90.0),
        point(40.0, 0.0, 12_000, bearing = 180.0), point(0.0, 0.0, 16_000, bearing = 270.0),
    ))
    val tunnelGap = TraceFixture("tunnel-gap", origin, listOf(point(0.0, 0.0, 0), point(0.0, 30.0, 3_000), point(0.0, 120.0, 18_000)))
    val duplicateTimestamp = TraceFixture("duplicate-timestamp", origin, listOf(point(0.0, 0.0, 0), point(0.0, 0.0, 0), point(0.0, 20.0, 2_000)))
    val largeJump = TraceFixture("large-jump", origin, listOf(point(0.0, 0.0, 0), point(1_000.0, 1_000.0, 1_000)))
    val consecutiveTurns = TraceFixture("consecutive-turns", origin, listOf(
        point(0.0, 0.0, 0), point(0.0, 50.0, 5_000), point(15.0, 55.0, 7_000, bearing = 90.0),
        point(20.0, 70.0, 9_000, bearing = 0.0),
    ))
    val all = listOf(stationaryNoise, cleanRoute, parallelRoad, loopCrossing, tunnelGap, duplicateTimestamp, largeJump, consecutiveTurns)
}
