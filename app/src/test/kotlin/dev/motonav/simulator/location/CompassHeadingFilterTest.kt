package dev.motonav.simulator.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassHeadingFilterTest {
    @Test fun `smooths across geographic north without jumping south`() {
        val filter = CompassHeadingFilter(smoothing = 0.5, minimumChangeDegrees = 0.0, minimumIntervalMillis = 0)
        assertEquals(359.0, filter.update(359.0, 0)!!, 0.01)
        val crossed = filter.update(1.0, 1)!!
        assertTrue(crossed < 2.0 || crossed > 358.0)
    }

    @Test fun `suppresses tiny and overly frequent sensor changes`() {
        val filter = CompassHeadingFilter(smoothing = 1.0, minimumChangeDegrees = 3.0, minimumIntervalMillis = 200)
        assertEquals(10.0, filter.update(10.0, 0)!!, 0.0)
        assertNull(filter.update(20.0, 100))
        assertNull(filter.update(12.0, 250))
        assertEquals(20.0, filter.update(20.0, 500)!!, 0.0)
    }
}
