package dev.motonav.simulator.transport

import dev.motonav.simulator.navigation.Maneuver
import dev.motonav.simulator.navigation.NavigationState
import dev.motonav.simulator.navigation.NormalizedRoutePoint
import dev.motonav.simulator.protocol.DisplayFrame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCadenceTest {
    private val frame = DisplayFrame(
        0, NavigationState.NAVIGATING, Maneuver.RIGHT, 120, 1_500,
        listOf(NormalizedRoutePoint(32_768, 65_535, startsPolyline = true)),
    )

    @Test fun `changed frames are limited to once per 400 milliseconds`() {
        val cadence = FrameCadence()
        assertTrue(cadence.shouldSend(frame, 0))
        cadence.markSent(frame, 0)
        val changed = frame.copy(nextTurnMetres = 110)
        assertFalse(cadence.shouldSend(changed, 399))
        assertTrue(cadence.shouldSend(changed, 400))
    }

    @Test fun `unchanged paused frame is sent every 400 milliseconds`() {
        val cadence = FrameCadence()
        cadence.markSent(frame, 10_000)
        assertFalse(cadence.shouldSend(frame.copy(sequence = 99), 10_399))
        assertTrue(cadence.shouldSend(frame.copy(sequence = 99), 10_400))
    }

    @Test fun `reset forces latest frame immediately after reconnect`() {
        val cadence = FrameCadence()
        cadence.markSent(frame, 10_000)
        cadence.reset()
        assertTrue(cadence.shouldSend(frame, 10_001))
    }

    @Test fun `high rate ordinary changes emit at most once per 400 milliseconds`() {
        val cadence = FrameCadence()
        var sent = 0
        for (now in 0L..1_100L step 100L) {
            val candidate = frame.copy(nextTurnMetres = 120 - (now / 100).toInt())
            if (cadence.shouldSend(candidate, now)) {
                cadence.markSent(candidate, now)
                sent++
            }
        }
        assertEquals(3, sent)
    }

    @Test fun `state and maneuver changes bypass ordinary throttle`() {
        val cadence = FrameCadence()
        cadence.markSent(frame, 0)
        assertTrue(cadence.shouldSend(frame.copy(state = NavigationState.GPS_DEGRADED), 100))
        cadence.markSent(frame, 200)
        assertTrue(cadence.shouldSend(frame.copy(maneuver = Maneuver.LEFT), 300))
    }
}
