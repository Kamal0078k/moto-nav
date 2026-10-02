package dev.motonav.simulator.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingCancellationTest {
    @Test fun `cancel interrupts current and late registered operations exactly once`() {
        val cancellation = RoutingCancellation()
        var currentCalls = 0
        var lateCalls = 0
        cancellation.invokeOnCancellation { currentCalls++ }

        cancellation.cancel()
        cancellation.cancel()
        cancellation.invokeOnCancellation { lateCalls++ }

        assertTrue(cancellation.isCancelled())
        assertEquals(1, currentCalls)
        assertEquals(1, lateCalls)
    }

    @Test fun `unregistered operation is not interrupted`() {
        val cancellation = RoutingCancellation()
        var calls = 0
        val registration = cancellation.invokeOnCancellation { calls++ }

        registration.unregister()
        cancellation.cancel()

        assertEquals(0, calls)
    }
}
