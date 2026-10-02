package dev.motonav.simulator.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQualityFilterTest {
    private class Clock(var now: Long) : MonotonicClock {
        override fun elapsedRealtimeMillis() = now
    }

    @Test fun `rejects stale inaccurate duplicate backward and implausible fixes`() {
        val clock = Clock(20_000)
        assertReason(LocationQualityFilter(clock), sample(0.0, elapsed = 9_999), RejectionReason.STALE)
        assertReason(LocationQualityFilter(clock), sample(0.0, elapsed = 20_000, accuracy = 51.0), RejectionReason.INACCURATE)

        val duplicate = LocationQualityFilter(clock)
        assertTrue(duplicate.evaluate(sample(0.0, elapsed = 20_000)) is QualityDecision.Accepted)
        assertReason(duplicate, sample(0.0, elapsed = 20_000), RejectionReason.DUPLICATE)

        val backward = LocationQualityFilter(clock)
        assertTrue(backward.evaluate(sample(0.0, elapsed = 20_000)) is QualityDecision.Accepted)
        assertReason(backward, sample(1.0, elapsed = 19_999), RejectionReason.BACKWARD_TIMESTAMP)

        val jump = LocationQualityFilter(clock)
        assertTrue(jump.evaluate(sample(0.0, elapsed = 19_000)) is QualityDecision.Accepted)
        assertReason(jump, sample(1_000.0, elapsed = 20_000), RejectionReason.IMPLAUSIBLE_JUMP)
    }

    @Test fun `stationary decision considers speed and accuracy noise`() {
        val clock = Clock(2_000)
        val filter = LocationQualityFilter(clock)
        filter.evaluate(sample(0.0, elapsed = 1_000, speed = 0.0))
        val result = filter.evaluate(sample(2.0, elapsed = 2_000, speed = 0.2)) as QualityDecision.Accepted
        assertTrue(result.stationary)
    }

    @Test fun `walking speed advances even when each fix is inside its accuracy radius`() {
        val clock = Clock(2_000)
        val filter = LocationQualityFilter(clock)
        filter.evaluate(sample(0.0, elapsed = 1_000, accuracy = 15.0, speed = 0.0))

        val result = filter.evaluate(
            sample(2.0, elapsed = 2_000, accuracy = 15.0, speed = 1.0),
        ) as QualityDecision.Accepted

        assertTrue(!result.stationary)
    }

    @Test fun `very slow movement accumulates from a stable anchor instead of freezing forever`() {
        val clock = Clock(4_000)
        val filter = LocationQualityFilter(clock)
        filter.evaluate(sample(0.0, elapsed = 1_000, accuracy = 3.0, speed = 0.1))
        val two = filter.evaluate(sample(2.0, elapsed = 2_000, accuracy = 3.0, speed = 0.1)) as QualityDecision.Accepted
        val three = filter.evaluate(sample(3.1, elapsed = 3_000, accuracy = 3.0, speed = 0.1)) as QualityDecision.Accepted
        val five = filter.evaluate(sample(5.0, elapsed = 4_000, accuracy = 3.0, speed = 0.1)) as QualityDecision.Accepted

        assertTrue(two.stationary)
        assertTrue(!three.stationary)
        assertTrue(five.stationary)
    }

    @Test fun `slow movement refresh threshold is smaller than the full accuracy radius`() {
        val clock = Clock(3_000)
        val filter = LocationQualityFilter(clock)
        filter.evaluate(sample(0.0, elapsed = 1_000, accuracy = 10.0, speed = 0.1))
        val four = filter.evaluate(sample(4.0, elapsed = 2_000, accuracy = 10.0, speed = 0.1)) as QualityDecision.Accepted
        val seven = filter.evaluate(sample(7.0, elapsed = 3_000, accuracy = 10.0, speed = 0.1)) as QualityDecision.Accepted

        assertTrue(four.stationary)
        assertTrue(!seven.stationary)
    }

    @Test fun `poor accuracy cannot postpone movement refresh beyond five metres`() {
        val clock = Clock(4_000)
        val filter = LocationQualityFilter(clock)
        filter.evaluate(sample(0.0, elapsed = 1_000, accuracy = 35.0, speed = 0.1))
        val four = filter.evaluate(sample(4.0, elapsed = 2_000, accuracy = 35.0, speed = 0.1)) as QualityDecision.Accepted
        val five = filter.evaluate(sample(5.1, elapsed = 3_000, accuracy = 35.0, speed = 0.1)) as QualityDecision.Accepted

        assertTrue(four.stationary)
        assertTrue(!five.stationary)
    }

    private fun assertReason(filter: LocationQualityFilter, sample: GeoLocationSample, reason: RejectionReason) {
        assertEquals(reason, (filter.evaluate(sample) as QualityDecision.Rejected).reason)
    }

    private fun sample(north: Double, elapsed: Long, accuracy: Double = 3.0, speed: Double? = 10.0): GeoLocationSample =
        TraceFixture("test", dev.motonav.simulator.routing.GeoPoint(0.0, 0.0), listOf(
            RelativeTraceSample(0.0, north, elapsed, accuracy, speed),
        )).geoSamples().single()
}
