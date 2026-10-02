package dev.motonav.simulator.service

import dev.motonav.simulator.location.GeoLocationSample
import dev.motonav.simulator.location.MonotonicClock
import dev.motonav.simulator.location.ProjectionAndMatchingTest
import dev.motonav.simulator.location.RouteMatchObservation
import dev.motonav.simulator.navigation.NavigationState
import dev.motonav.simulator.routing.GeoPoint
import dev.motonav.simulator.routing.RoadRoute
import dev.motonav.simulator.routing.RouteRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReroutingControllerTest {
    private class Clock(var now: Long = 0L) : MonotonicClock { override fun elapsedRealtimeMillis() = now }
    private val origin = GeoPoint(12.0, 77.0)
    private val original = route(
        listOf(0.0 to 0.0, 0.0 to 250.0, 0.0 to 500.0),
        revision = 1,
    )

    @Test fun `one outlier and recovery do not request a route`() {
        val (controller, clock) = controller()
        assertNull(feed(controller, clock, sample(0.0, 0.0, 0)).reroute)
        assertNull(feed(controller, clock, sample(40.0, 20.0, 1_000)).reroute)
        assertEquals(OffRouteState.SUSPECT_OFF_ROUTE, controller.snapshot().offRouteState)
        assertNull(feed(controller, clock, sample(0.0, 30.0, 2_000)).reroute)
        assertEquals(OffRouteState.ON_ROUTE, controller.snapshot().offRouteState)
        assertEquals(0, controller.snapshot().sessionRerouteCount)
    }

    @Test fun `two ordinary on-route fixes clear suspicion`() {
        val (controller, clock) = controller()
        feed(controller, clock, sample(0.0, 0.0, 0))
        feed(controller, clock, sample(40.0, 20.0, 1_000))
        assertEquals(OffRouteState.SUSPECT_OFF_ROUTE, controller.snapshot().offRouteState)

        feed(controller, clock, sample(15.0, 30.0, 2_000))
        assertEquals(OffRouteState.SUSPECT_OFF_ROUTE, controller.snapshot().offRouteState)
        feed(controller, clock, sample(15.0, 40.0, 3_000))
        assertEquals(OffRouteState.ON_ROUTE, controller.snapshot().offRouteState)
    }

    @Test fun `stationary noisy off-route fixes never confirm movement`() {
        val (controller, clock) = controller()
        feed(controller, clock, sample(0.0, 0.0, 0, speed = 0.0))
        listOf(1_000L, 4_000L, 7_000L, 10_000L).forEach { elapsed ->
            assertNull(feed(controller, clock, sample(40.0, 20.0, elapsed, speed = 0.0)).reroute)
        }
        assertEquals(OffRouteState.SUSPECT_OFF_ROUTE, controller.snapshot().offRouteState)
        assertEquals(0, controller.snapshot().sessionRerouteCount)
    }

    @Test fun `three moving fixes over five seconds create exactly one request and keep old route visible`() {
        val (controller, clock) = controller()
        val context = trigger(controller, clock)
        val rerouting = requireNotNull(controller.beginReroute(context))
        assertEquals(NavigationState.REROUTING, rerouting.navigation?.state)
        assertEquals(1L, rerouting.routeRevision)
        assertEquals(1, rerouting.sessionRerouteCount)

        val continued = feed(controller, clock, sample(45.0, 110.0, 9_000))
        assertNull(continued.reroute)
        assertEquals(NavigationState.REROUTING, continued.snapshot.navigation?.state)
        assertEquals(1, continued.snapshot.sessionRerouteCount)
    }

    @Test fun `delayed success atomically swaps once and rematches latest fix`() {
        val (controller, clock) = controller()
        val context = trigger(controller, clock)
        controller.beginReroute(context)
        val replacement = replacementFrom(context.origin, revision = 2)

        val completion = controller.completeReroute(context, replacement)

        assertTrue(completion.accepted)
        assertEquals(2L, completion.snapshot.routeRevision)
        assertEquals(NavigationState.NAVIGATING, completion.snapshot.navigation?.state)
        assertTrue(completion.snapshot.navigation?.isReplacementRoute == true)
        assertNotNull(completion.snapshot.navigation?.location)
        assertFalse(controller.completeReroute(context, replacement.copy(revision = 3)).accepted)
    }

    @Test fun `delayed failure retains prior route and permits only one bounded retry`() {
        val (controller, clock) = controller()
        val context = trigger(controller, clock)
        controller.beginReroute(context)
        val failed = controller.failReroute(
            context,
            RerouteFailureKind.TRANSIENT,
            "network timeout",
        )
        assertEquals(1L, failed.routeRevision)
        assertEquals(NavigationState.NAVIGATING, failed.navigation?.state)

        clock.now += 31_000L
        val retry = controller.onLocations(
            listOf(sample(45.0, 120.0, clock.now, speed = 10.0)),
        ).single().reroute
        assertEquals(RerouteAttemptKind.TRANSIENT_RETRY, retry?.kind)
        controller.beginReroute(requireNotNull(retry))
        controller.failReroute(retry, RerouteFailureKind.TRANSIENT, "second timeout")

        clock.now += 31_000L
        val immediateSecondRetry = controller.onLocations(
            listOf(sample(50.0, 160.0, clock.now, speed = 10.0)),
        ).single().reroute
        assertNull(immediateSecondRetry)
    }

    @Test fun `stop and stale movement make delayed callbacks harmless`() {
        val (controller, clock) = controller()
        val context = trigger(controller, clock)
        controller.beginReroute(context)
        clock.now = context.origin.elapsedRealtimeMillis + 5_000L
        feed(controller, clock, sample(40.0, 220.0, clock.now, speed = 25.0))
        val stale = controller.completeReroute(context, replacementFrom(context.origin, 2))
        assertFalse(stale.accepted)
        assertEquals(1L, stale.snapshot.routeRevision)
        assertTrue(stale.reason!!.contains("stale"))

        val second = controller.requestManualReroute()
        if (second != null) controller.beginReroute(second)
        controller.stop()
        if (second != null) {
            assertFalse(controller.completeReroute(second, replacementFrom(second.origin, 3)).accepted)
        }
        assertNull(controller.snapshot().routeRevision)
    }

    @Test fun `response older than sixty seconds is rejected even without movement`() {
        val (controller, clock) = controller()
        val context = trigger(controller, clock)
        controller.beginReroute(context)
        clock.now = context.origin.elapsedRealtimeMillis + 60_001L

        val stale = controller.completeReroute(context, replacementFrom(context.origin, 2))

        assertFalse(stale.accepted)
        assertEquals(1L, stale.snapshot.routeRevision)
        assertTrue(stale.reason!!.contains("stale"))
    }

    @Test fun `old destination generation cannot replace a new session`() {
        val (controller, clock) = controller()
        val old = trigger(controller, clock)
        controller.beginReroute(old)
        controller.stop()
        val changedDestination = GeoPoint(original.geometry.last().latitude + 0.001, original.geometry.last().longitude)
        controller.start(
            original.copy(revision = 10),
            "changed destination",
            2L,
            request = RouteRequest(original.geometry.first(), changedDestination),
        )

        val late = controller.completeReroute(old, replacementFrom(old.origin, 2))

        assertFalse(late.accepted)
        assertEquals(10L, late.snapshot.routeRevision)
    }

    @Test fun `replacement request uses raw accepted origin original destination and preserves counters`() {
        val (controller, clock) = controller()
        controller.updateProviderRequestCount(42)
        val context = trigger(controller, clock)
        assertEquals(GeoPoint(context.origin.latitude, context.origin.longitude), context.request.origin)
        assertEquals(original.geometry.last(), context.request.destination)
        assertEquals(1L, context.startingRouteRevision)
        assertEquals("pending local daily/monthly check", context.budgetDecision)

        controller.beginReroute(context)
        val completion = controller.completeReroute(context, replacementFrom(context.origin, 2))
        assertTrue(completion.accepted)
        assertEquals(42, completion.snapshot.providerRequestCount)
        assertEquals(1, completion.snapshot.sessionRerouteCount)
    }

    @Test fun `rate limit retry-after prevents automatic calls until fresh evidence after deadline`() {
        val (controller, clock) = controller()
        val context = trigger(controller, clock)
        controller.beginReroute(context)
        controller.failReroute(context, RerouteFailureKind.RATE_LIMITED, "rate limited", retryAfterMillis = 60_000L)

        clock.now += 31_000L
        assertNull(feed(controller, clock, sample(45.0, 120.0, clock.now)).reroute)
        assertEquals(OffRouteState.COOLDOWN, controller.snapshot().offRouteState)
        clock.now = 68_000L
        assertNull(feed(controller, clock, sample(45.0, 150.0, clock.now)).reroute)
        assertEquals(OffRouteState.SUSPECT_OFF_ROUTE, controller.snapshot().offRouteState)
    }

    @Test fun `no-route manual retry is unavailable until rider moves`() {
        val (controller, clock) = controller()
        val context = trigger(controller, clock)
        controller.beginReroute(context)
        controller.failReroute(context, RerouteFailureKind.NO_ROUTE, "no route")
        clock.now += 31_000L
        assertNull(controller.requestManualReroute())

        feed(controller, clock, sample(45.0, 130.0, clock.now, speed = 10.0))
        assertEquals(RerouteAttemptKind.MANUAL, controller.requestManualReroute()?.kind)
    }

    @Test fun `network deferral does not count an attempt and requires fresh evidence`() {
        val (controller, clock) = controller()
        val context = trigger(controller, clock)
        val deferred = controller.deferRerouteBeforeSend(context, "network unavailable")
        assertEquals(0, deferred.sessionRerouteCount)
        assertEquals(OffRouteState.ON_ROUTE, deferred.offRouteState)
        assertNull(feed(controller, clock, sample(45.0, 110.0, 9_000)).reroute)
    }

    @Test fun `automatic cap holds under a high-rate trace`() {
        val policy = ReroutingPolicy()
        val machine = OffRouteReroutingStateMachine(policy)
        var base = 0L
        repeat(policy.maximumAutomaticReroutes) {
            assertNull(machine.observe(observation(base, 40.0), 1_000.0, base))
            assertNull(machine.observe(observation(base + 2_500, 45.0), 1_000.0, base + 2_500))
            assertEquals(
                RerouteAttemptKind.AUTOMATIC,
                machine.observe(observation(base + 5_000, 50.0), 1_000.0, base + 5_000),
            )
            machine.requestStarted(RerouteAttemptKind.AUTOMATIC)
            machine.requestFailed(RerouteFailureKind.NO_ROUTE, base + 5_000)
            base += 36_000L
        }
        assertEquals(policy.maximumAutomaticReroutes, machine.automaticAttempts)
        repeat(3) { index ->
            assertNull(machine.observe(observation(base + index * 3_000L, 60.0), 1_000.0, base + index * 3_000L))
        }
        assertEquals(policy.maximumAutomaticReroutes, machine.automaticAttempts)
    }

    @Test fun `arrival zone and inaccurate observations never accumulate evidence`() {
        val machine = OffRouteReroutingStateMachine()
        assertNull(machine.observe(observation(0L, 80.0), 20.0, 0L))
        assertEquals(OffRouteState.ON_ROUTE, machine.state)
        val inaccurate = observation(1_000L, 80.0).copy(
            sample = observation(1_000L, 80.0).sample.copy(horizontalAccuracyMetres = 51.0),
        )
        assertNull(machine.observe(inaccurate, 1_000.0, 1_000L))
        assertEquals(OffRouteState.ON_ROUTE, machine.state)
    }

    private fun controller(): Pair<NavigationSessionController, Clock> {
        val clock = Clock()
        val controller = NavigationSessionController(clock)
        controller.start(
            original,
            "destination",
            1L,
            request = RouteRequest(original.geometry.first(), original.geometry.last()),
        )
        return controller to clock
    }

    private fun trigger(controller: NavigationSessionController, clock: Clock): RerouteRequestContext {
        feed(controller, clock, sample(0.0, 0.0, 0))
        assertNull(feed(controller, clock, sample(40.0, 20.0, 1_000)).reroute)
        assertNull(feed(controller, clock, sample(40.0, 50.0, 4_000)).reroute)
        return requireNotNull(feed(controller, clock, sample(40.0, 80.0, 7_000)).reroute)
    }

    private fun feed(
        controller: NavigationSessionController,
        clock: Clock,
        sample: GeoLocationSample,
    ): SessionLocationUpdate {
        clock.now = sample.elapsedRealtimeMillis
        return controller.onLocations(listOf(sample)).single()
    }

    private fun replacementFrom(sample: GeoLocationSample, revision: Long): RoadRoute =
        ProjectionAndMatchingTest.route(
            listOf(
                GeoPoint(sample.latitude, sample.longitude),
                original.geometry[1],
                original.geometry.last(),
            ),
            listOf(0, 1, 2),
        ).copy(revision = revision)

    private fun route(offsets: List<Pair<Double, Double>>, revision: Long): RoadRoute =
        ProjectionAndMatchingTest.route(
            ProjectionAndMatchingTest.points(origin, offsets),
            listOf(0, offsets.lastIndex),
        ).copy(revision = revision)

    private fun sample(east: Double, north: Double, elapsed: Long, speed: Double = 10.0): GeoLocationSample =
        ProjectionAndMatchingTest.sample(origin, east, north, elapsed, bearing = 0.0, speed = speed)
            .copy(speedAccuracyMetresPerSecond = 0.5)

    private fun observation(elapsed: Long, distance: Double): RouteMatchObservation = RouteMatchObservation(
        sample = sample(40.0, elapsed / 100.0, elapsed),
        distanceFromRouteMetres = distance,
        routeHeadingDegrees = 0.0,
        stationary = false,
    )
}
