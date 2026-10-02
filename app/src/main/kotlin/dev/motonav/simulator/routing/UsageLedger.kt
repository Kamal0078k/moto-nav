package dev.motonav.simulator.routing

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class UsageLedgerState(
    val billingMonth: String,
    val attempts: Int,
    val lastObservedEpochMillis: Long,
    val billingDay: String = "$billingMonth-01",
    val dailyAttempts: Int = 0,
)

interface UsageLedgerStore {
    fun read(): UsageLedgerState?
    fun write(state: UsageLedgerState)
}

data class UsageSnapshot(
    val billingMonth: String,
    val attempts: Int,
    val warning: Boolean,
    val blocked: Boolean,
    val resetDate: String,
    val billingDay: String,
    val dailyAttempts: Int,
    val dailyWarning: Boolean,
    val dailyBlocked: Boolean,
)

class UsageLedger(
    private val store: UsageLedgerStore,
    private val clock: Clock = Clock.system(INDIA_ZONE),
) {
    @Synchronized
    fun recordAttempt(): UsageSnapshot {
        val now = clock.instant()
        val currentMonth = YearMonth.from(now.atZone(INDIA_ZONE))
        val currentDay = LocalDate.from(now.atZone(INDIA_ZONE))
        val stored = store.read()
        val effectiveMonth = when {
            stored == null -> currentMonth
            now.toEpochMilli() < stored.lastObservedEpochMillis -> YearMonth.parse(stored.billingMonth)
            currentMonth.isBefore(YearMonth.parse(stored.billingMonth)) -> YearMonth.parse(stored.billingMonth)
            else -> currentMonth
        }
        val previousCount = if (stored?.billingMonth == effectiveMonth.toString()) stored.attempts else 0
        val storedDay = stored?.billingDay?.let(LocalDate::parse)
        val effectiveDay = when {
            storedDay == null -> currentDay
            now.toEpochMilli() < stored.lastObservedEpochMillis -> storedDay
            currentDay.isBefore(storedDay) -> storedDay
            else -> currentDay
        }
        val previousDailyCount = if (stored?.billingDay == effectiveDay.toString()) stored.dailyAttempts else 0
        val count = Math.addExact(previousCount, 1)
        val dailyCount = Math.addExact(previousDailyCount, 1)
        val state = UsageLedgerState(
            billingMonth = effectiveMonth.toString(),
            attempts = count,
            lastObservedEpochMillis = maxOf(now.toEpochMilli(), stored?.lastObservedEpochMillis ?: Long.MIN_VALUE),
            billingDay = effectiveDay.toString(),
            dailyAttempts = dailyCount,
        )
        store.write(state)
        return snapshot(state)
    }

    @Synchronized
    fun current(): UsageSnapshot {
        val now = clock.instant()
        val currentMonth = YearMonth.from(now.atZone(INDIA_ZONE))
        val currentDay = LocalDate.from(now.atZone(INDIA_ZONE))
        val stored = store.read()
        val state = if (stored == null) {
            UsageLedgerState(currentMonth.toString(), 0, now.toEpochMilli(), currentDay.toString(), 0)
        } else if (
            now.toEpochMilli() >= stored.lastObservedEpochMillis &&
            currentMonth.isAfter(YearMonth.parse(stored.billingMonth))
        ) {
            UsageLedgerState(currentMonth.toString(), 0, now.toEpochMilli(), currentDay.toString(), 0).also(store::write)
        } else if (now.toEpochMilli() >= stored.lastObservedEpochMillis &&
            currentDay.isAfter(LocalDate.parse(stored.billingDay))
        ) {
            stored.copy(
                billingDay = currentDay.toString(),
                dailyAttempts = 0,
                lastObservedEpochMillis = now.toEpochMilli(),
            ).also(store::write)
        } else {
            stored
        }
        return snapshot(state)
    }

    private fun snapshot(state: UsageLedgerState): UsageSnapshot {
        val month = YearMonth.parse(state.billingMonth)
        return UsageSnapshot(
            billingMonth = state.billingMonth,
            attempts = state.attempts,
            warning = state.attempts >= WARNING_ATTEMPTS,
            blocked = state.attempts >= STOP_ATTEMPTS || state.dailyAttempts >= DAILY_STOP_ATTEMPTS,
            resetDate = month.plusMonths(1).atDay(1).toString(),
            billingDay = state.billingDay,
            dailyAttempts = state.dailyAttempts,
            dailyWarning = state.dailyAttempts >= DAILY_WARNING_ATTEMPTS,
            dailyBlocked = state.dailyAttempts >= DAILY_STOP_ATTEMPTS,
        )
    }

    companion object {
        val INDIA_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
        const val WARNING_ATTEMPTS = 5_500
        const val STOP_ATTEMPTS = 6_000
        const val DAILY_WARNING_ATTEMPTS = 180
        const val DAILY_STOP_ATTEMPTS = 200
    }
}
