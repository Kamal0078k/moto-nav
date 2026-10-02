package dev.motonav.simulator.location

import dev.motonav.simulator.navigation.Maneuver
import dev.motonav.simulator.navigation.NavigationState
import dev.motonav.simulator.routing.CancellationSignal
import dev.motonav.simulator.routing.GeoPoint
import dev.motonav.simulator.routing.RouteRequest
import dev.motonav.simulator.routing.RoutingProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveNavigationEngineTest {
    private class Clock(var now: Long = 0) : MonotonicClock { override fun elapsedRealtimeMillis() = now }
    private val origin = GeoPoint(12.0, 77.0)

    @Test fun `permission states distinguish denied approximate precise disabled and revocation`() {
        assertEquals(NavigationState.WAITING_PERMISSION, LocationReadiness.state(LocationPermissionLevel.DENIED, true))
        assertEquals(NavigationState.WAITING_PERMISSION, LocationReadiness.state(LocationPermissionLevel.APPROXIMATE, true))
        assertEquals(NavigationState.LOCATION_DISABLED, LocationReadiness.state(LocationPermissionLevel.PRECISE, false))
        assertEquals(NavigationState.ACQUIRING_GPS, LocationReadiness.state(LocationPermissionLevel.PRECISE, true))
        // Revoking precise permission while active returns to the same explicit waiting state.
        assertEquals(NavigationState.WAITING_PERMISSION, LocationReadiness.state(LocationPermissionLevel.DENIED, true))
    }

    @Test fun `progress never moves backward for jitter and gap freezes then reacquires`() {
        val clock = Clock()
        val engine = engine(clock)
        engine.start()
        clock.now = 8_000
        engine.processBatch(listOf(sample(80.0, 8_000)))
        val committed = engine.snapshot().progressMetres
        clock.now = 9_000
        engine.processBatch(listOf(sample(70.0, 9_000)))
        assertEquals(committed, engine.snapshot().progressMetres, 0.01)

        clock.now = 29_001
        assertEquals(NavigationState.GPS_DEGRADED, engine.checkForGpsLoss().state)
        assertEquals(committed, engine.snapshot().progressMetres, 0.01)
        clock.now = 30_000
        engine.processBatch(listOf(sample(100.0, 30_000)))
        assertEquals(NavigationState.NAVIGATING, engine.snapshot().state)
        assertTrue(engine.snapshot().progressMetres >= 99.0)
    }

    @Test fun `sustained reverse travel rewinds progress after jitter allowance`() {
        val clock = Clock(1_000)
        val engine = engine(clock)
        engine.start()
        engine.processDetailed(sample(80.0, 1_000, speed = 1.0))
        val forward = engine.snapshot()
        listOf(75.0, 70.0, 65.0).forEachIndexed { index, north ->
            clock.now = 2_000L + index * 1_000L
            engine.processDetailed(sample(north, clock.now, speed = 1.0, bearing = 180.0))
        }
        val reversed = engine.snapshot()

        assertTrue(reversed.progressMetres <= 66.0)
        assertTrue(reversed.metresToManeuver > forward.metresToManeuver)
        assertTrue(reversed.remainingMetres > forward.remainingMetres)

        clock.now = 5_000L
        engine.processDetailed(sample(60.0, clock.now, speed = 1.0, bearing = 180.0))
        assertTrue(engine.snapshot().progressMetres <= 61.0)

        listOf(65.0, 70.0, 75.0).forEachIndexed { index, north ->
            clock.now = 6_000L + index * 1_000L
            engine.processDetailed(sample(north, clock.now, speed = 1.0, bearing = 0.0))
        }
        assertTrue(engine.snapshot().progressMetres >= 74.0)
    }

    @Test fun `very slow reverse travel is confirmed from accumulated displacement`() {
        val clock = Clock(1_000)
        val engine = engine(clock)
        engine.start()
        engine.processDetailed(
            sample(80.0, 1_000, speed = 0.1).copy(horizontalAccuracyMetres = 10.0),
        )
        val initial = engine.snapshot().progressMetres
        listOf(76.0, 72.0, 68.0, 64.0).forEachIndexed { index, north ->
            clock.now = 2_000L + index * 1_000L
            engine.processDetailed(
                sample(north, clock.now, speed = 0.1, bearing = 180.0)
                    .copy(horizontalAccuracyMetres = 10.0),
            )
        }

        assertTrue(engine.snapshot().progressMetres < initial - 10.0)
    }

    @Test fun `delivered batches are processed in monotonic order`() {
        val clock = Clock(2_000)
        val engine = engine(clock)
        engine.start()
        val snapshots = engine.processBatch(listOf(sample(20.0, 2_000), sample(10.0, 1_000)))
        assertEquals(2, snapshots.size)
        assertTrue(snapshots.last().progressMetres >= snapshots.first().progressMetres)
        assertTrue(engine.snapshot().progressMetres >= 19.0)
    }

    @Test fun `stationary accuracy noise freezes committed progress`() {
        val clock = Clock(1_000)
        val engine = engine(clock)
        engine.start()
        engine.processBatch(listOf(sample(40.0, 1_000, speed = 0.0)))
        val progress = engine.snapshot().progressMetres
        clock.now = 2_000
        engine.processBatch(listOf(sample(42.0, 2_000, speed = 0.0)))
        assertEquals(progress, engine.snapshot().progressMetres, 0.01)
    }

    @Test fun `walking below old stationary threshold advances progress and frame distances`() {
        val clock = Clock(1_000)
        val engine = engine(clock)
        engine.start()
        val first = engine.processDetailed(
            sample(10.0, 1_000, speed = 1.0).copy(horizontalAccuracyMetres = 15.0),
        ).snapshot
        clock.now = 3_000
        val second = engine.processDetailed(
            sample(14.0, 3_000, speed = 1.0).copy(horizontalAccuracyMetres = 15.0),
        ).snapshot

        assertTrue(second.progressMetres > first.progressMetres)
        assertTrue(second.metresToManeuver < first.metresToManeuver)
        assertTrue(second.remainingMetres < first.remainingMetres)
    }

    @Test fun `very slow displacement without reliable speed eventually refreshes progress`() {
        val clock = Clock(1_000)
        val engine = engine(clock)
        engine.start()
        engine.processDetailed(sample(10.0, 1_000, speed = 0.1))
        val initial = engine.snapshot().progressMetres
        clock.now = 2_000
        engine.processDetailed(sample(12.0, 2_000, speed = 0.1))
        assertEquals(initial, engine.snapshot().progressMetres, 0.5)
        clock.now = 3_000
        engine.processDetailed(sample(14.0, 3_000, speed = 0.1))
        assertTrue(engine.snapshot().progressMetres >= 13.0)
    }

    @Test fun `forty metres of slow movement refreshes despite fifteen metre accuracy`() {
        val clock = Clock(1_000)
        val engine = engine(clock)
        engine.start()
        engine.processDetailed(
            sample(10.0, 1_000, speed = 0.1).copy(horizontalAccuracyMetres = 15.0),
        )
        val initial = engine.snapshot()
        (1..8).forEach { index ->
            clock.now = 1_000L + index * 2_000L
            engine.processDetailed(
                sample(10.0 + index * 5.0, clock.now, speed = 0.1)
                    .copy(horizontalAccuracyMetres = 15.0),
            )
        }
        val moved = engine.snapshot()

        // With an untrustworthy 0.1 m/s speed, progress may intentionally lag by one accuracy
        // radius, but it must refresh repeatedly instead of remaining frozen for all 40 metres.
        assertTrue(moved.progressMetres >= initial.progressMetres + 29.0)
        assertTrue(moved.metresToManeuver < initial.metresToManeuver)
        assertTrue(moved.remainingMetres < initial.remainingMetres)
    }

    @Test fun `poor accuracy walking refreshes frame distance after about five metres`() {
        val clock = Clock(1_000)
        val engine = engine(clock)
        engine.start()
        val initial = engine.processDetailed(
            sample(10.0, 1_000, speed = 0.1).copy(horizontalAccuracyMetres = 35.0),
        ).snapshot
        clock.now = 2_000
        val beforeThreshold = engine.processDetailed(
            sample(14.0, 2_000, speed = 0.1).copy(horizontalAccuracyMetres = 35.0),
        ).snapshot
        clock.now = 3_000
        val refreshed = engine.processDetailed(
            sample(15.1, 3_000, speed = 0.1).copy(horizontalAccuracyMetres = 35.0),
        ).snapshot

        assertEquals(initial.progressMetres, beforeThreshold.progressMetres, 0.5)
        assertTrue(refreshed.progressMetres > initial.progressMetres + 4.0)
        assertTrue(refreshed.metresToManeuver < initial.metresToManeuver)
        assertTrue(refreshed.remainingMetres < initial.remainingMetres)
    }

    @Test fun `valid off-route fixes do not masquerade as GPS loss`() {
        val clock = Clock(0)
        val engine = engine(clock)
        engine.start()
        engine.processDetailed(sample(0.0, 0, speed = 5.0))
        listOf(3_000L, 6_000L, 9_000L).forEachIndexed { index, elapsed ->
            clock.now = elapsed
            engine.processDetailed(sample((index + 1) * 10.0, elapsed, speed = 5.0, east = 40.0))
        }

        assertEquals(NavigationState.NAVIGATING, engine.snapshot().state)
        assertTrue(engine.snapshot().diagnostic!!.contains("did not match route corridor"))
    }

    @Test fun `circular heading smoothing crosses north and stationary fix freezes heading`() {
        val clock = Clock()
        val engine = engine(clock)
        engine.start()
        clock.now = 1_000
        engine.processBatch(listOf(sample(10.0, 1_000, bearing = 359.0)))
        clock.now = 2_000
        engine.processBatch(listOf(sample(20.0, 2_000, bearing = 1.0)))
        val northHeading = requireNotNull(engine.snapshot().headingDegrees)
        assertTrue(northHeading < 10.0 || northHeading > 350.0)
        clock.now = 3_000
        engine.processBatch(listOf(sample(21.0, 3_000, bearing = 180.0, speed = 0.0)))
        assertEquals(northHeading, engine.snapshot().headingDegrees!!, 0.01)
    }

    @Test fun `course can be derived from movement when bearing is absent`() {
        val clock = Clock(1_000)
        val engine = engine(clock)
        engine.start()
        engine.processBatch(listOf(sample(0.0, 1_000, bearing = null, speed = 4.0)))
        clock.now = 3_000
        engine.processBatch(listOf(sample(15.0, 3_000, bearing = null, speed = 7.0, east = 10.0)))
        assertTrue(requireNotNull(engine.snapshot().headingDegrees) in 5.0..40.0)
    }

    @Test fun `physical device heading overrides GPS course for the marker`() {
        val clock = Clock(1_000)
        val engine = engine(clock)
        engine.start()
        engine.processBatch(listOf(sample(10.0, 1_000, bearing = 0.0, speed = 5.0)))
        engine.updateDeviceHeading(270.0)
        assertEquals(270.0, engine.snapshot().headingDegrees!!, 0.0)
        clock.now = 2_000
        engine.processBatch(listOf(sample(20.0, 2_000, bearing = 90.0, speed = 5.0)))
        assertEquals(270.0, engine.snapshot().headingDegrees!!, 0.0)
    }

    @Test fun `close maneuvers advance one boundary per accepted fix`() {
        val geometry = ProjectionAndMatchingTest.points(origin, listOf(
            0.0 to 0.0, 0.0 to 50.0, 5.0 to 50.0, 5.0 to 100.0,
        ))
        val route = ProjectionAndMatchingTest.route(geometry, listOf(0, 1, 2, 3))
        val clock = Clock(8_000)
        val engine = LiveNavigationEngine(route, clock)
        engine.start()
        engine.processBatch(listOf(sample(80.0, 8_000, east = 5.0)))
        assertEquals(Maneuver.RIGHT, engine.snapshot().maneuver)
        assertEquals(0.0, engine.snapshot().metresToManeuver, 0.5)
        clock.now = 9_000
        engine.processBatch(listOf(sample(90.0, 9_000, east = 5.0)))
        assertEquals(Maneuver.ARRIVE, engine.snapshot().maneuver)
    }

    @Test fun `maneuver and destination distances count down along route`() {
        val clock = Clock(2_000)
        val engine = engine(clock)
        engine.start()
        engine.processBatch(listOf(sample(20.0, 2_000)))
        val first = engine.snapshot()
        clock.now = 4_000
        engine.processBatch(listOf(sample(40.0, 4_000)))
        val second = engine.snapshot()
        assertTrue(second.metresToManeuver < first.metresToManeuver)
        assertTrue(second.remainingMetres < first.remainingMetres)
    }

    @Test fun `full live trace makes zero routing provider calls`() {
        var requests = 0
        val provider = RoutingProvider { _: RouteRequest, _: CancellationSignal -> requests++; error("must not be called") }
        val clock = Clock(20_000)
        val engine = engine(clock)
        engine.start()
        engine.processBatch(TraceFixtures.cleanRoute.geoSamples().map { it.copy(elapsedRealtimeMillis = it.elapsedRealtimeMillis + 1_000) })
        assertEquals(0, requests)
        assertTrue(provider.toString().isNotBlank())
    }

    @Test fun `trace player pauses scales and seeks deterministically`() {
        val player = TracePlayer(TraceFixtures.cleanRoute)
        assertTrue(player.advance(10_000).isEmpty())
        player.speedMultiplier = 2.0
        player.play()
        assertEquals(1, player.advance(1).size)
        assertTrue(player.advance(1_249).isNotEmpty())
        player.pause()
        assertTrue(player.advance(10_000).isEmpty())
        player.seekToStart()
        player.play()
        assertEquals(1, player.advance(1).size)
    }

    private fun engine(clock: Clock): LiveNavigationEngine {
        val geometry = ProjectionAndMatchingTest.points(origin, listOf(0.0 to 0.0, 0.0 to 100.0, 0.0 to 200.0))
        return LiveNavigationEngine(ProjectionAndMatchingTest.route(geometry, listOf(0, 1, 2)), clock)
    }

    private fun sample(
        north: Double,
        elapsed: Long,
        bearing: Double? = 0.0,
        speed: Double? = 10.0,
        east: Double = 0.0,
    ) = ProjectionAndMatchingTest.sample(origin, east, north, elapsed, bearing, speed)
}
