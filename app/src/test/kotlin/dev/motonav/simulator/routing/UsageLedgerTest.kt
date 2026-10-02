package dev.motonav.simulator.routing

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLedgerTest {
    @Test fun `resets on India month boundary and resists clock rollback`() {
        val store = MemoryStore()
        val clock = MutableClock(Instant.parse("2026-01-31T18:29:59Z"))
        val ledger = UsageLedger(store, clock)
        assertEquals("2026-01", ledger.recordAttempt().billingMonth)
        clock.instant = Instant.parse("2026-01-31T18:30:01Z")
        assertEquals(1, ledger.recordAttempt().attempts)
        assertEquals("2026-02", ledger.current().billingMonth)
        clock.instant = Instant.parse("2026-01-01T00:00:00Z")
        assertEquals("2026-02", ledger.recordAttempt().billingMonth)
        assertEquals(2, ledger.current().attempts)
    }

    @Test fun `diagnostics current resets an expired month without counting an attempt`() {
        val store = MemoryStore(
            UsageLedgerState("2026-01", 42, Instant.parse("2026-01-20T00:00:00Z").toEpochMilli()),
        )
        val ledger = UsageLedger(
            store,
            Clock.fixed(Instant.parse("2026-01-31T18:30:01Z"), UsageLedger.INDIA_ZONE),
        )

        val current = ledger.current()

        assertEquals("2026-02", current.billingMonth)
        assertEquals(0, current.attempts)
        assertEquals("2026-03-01", current.resetDate)
        assertEquals(current.billingMonth, store.state?.billingMonth)
    }

    @Test fun `warns and stops at configured thresholds`() {
        val store = MemoryStore(UsageLedgerState("2026-09", 5_498, 0))
        val ledger = UsageLedger(store, Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), UsageLedger.INDIA_ZONE))
        assertFalse(ledger.recordAttempt().warning)
        assertTrue(ledger.recordAttempt().warning)
        store.state = store.state!!.copy(attempts = 5_999)
        assertTrue(ledger.recordAttempt().blocked)
    }

    @Test fun `concurrent calls cannot lose attempts`() {
        val store = MemoryStore()
        val ledger = UsageLedger(store, Clock.fixed(Instant.parse("2026-09-22T00:00:00Z"), UsageLedger.INDIA_ZONE))
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(100)
        repeat(100) { executor.execute { ledger.recordAttempt(); ready.countDown() } }
        ready.await()
        executor.shutdown()
        assertEquals(100, ledger.current().attempts)
    }

    @Test fun `daily warning stop and midnight reset are independent of monthly total`() {
        val clock = MutableClock(Instant.parse("2026-09-22T12:00:00Z"))
        val store = MemoryStore(
            UsageLedgerState("2026-09", 500, 0, "2026-09-22", 179),
        )
        val ledger = UsageLedger(store, clock)
        assertTrue(ledger.recordAttempt().dailyWarning)
        store.state = store.state!!.copy(dailyAttempts = 199)
        assertTrue(ledger.recordAttempt().dailyBlocked)
        assertTrue(ledger.current().blocked)

        clock.instant = Instant.parse("2026-09-22T18:30:01Z") // 23 September in India.
        val reset = ledger.current()
        assertEquals("2026-09-23", reset.billingDay)
        assertEquals(0, reset.dailyAttempts)
        assertEquals(502, reset.attempts)
        assertFalse(reset.blocked)
    }

    private class MemoryStore(var state: UsageLedgerState? = null) : UsageLedgerStore {
        override fun read() = state
        override fun write(state: UsageLedgerState) { this.state = state }
    }

    private class MutableClock(var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = UsageLedger.INDIA_ZONE
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = instant
    }
}
