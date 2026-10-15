package com.littlekitty.xiaokele.rhythm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class WakeWindowEstimatorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /**
     * Regression for the behavior-rhythm bug: historical wake signals are
     * day-absolute epochs, so the median must be projected onto TODAY as a
     * time-of-day offset instead of being compared raw (which always looked
     * like a missed window and degraded every behavior plan to 05:00).
     */
    @Test
    fun behaviorSamplesProjectOntoTodayInsteadOfLookingStale() = runBlocking {
        val today = LocalDate.of(2026, 8, 29) // Saturday → rest-day rhythm class
        val observations = mutableMapOf<String, List<Long>>()
        listOf(
            LocalDate.of(2026, 8, 15),
            LocalDate.of(2026, 8, 16),
            LocalDate.of(2026, 8, 22),
            LocalDate.of(2026, 8, 23),
        ).forEach { date ->
            observations[date.format(DateTimeFormatter.BASIC_ISO_DATE)] = listOf(
                date.atTime(9, 30).atZone(zone).toInstant().toEpochMilli(),
            )
        }
        val estimator = WakeWindowEstimator { _, _ -> null }
        val now = today.atTime(6, 0).atZone(zone).toInstant()

        val plan = estimator.estimate(now, zone, observations)

        assertEquals(WakeWindowBasis.BEHAVIOR_WEEKEND, plan.basis)
        assertTrue(plan.confident)
        assertEquals(today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), plan.windowStartEpochMillis)
        assertEquals(today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), plan.windowEndEpochMillis)
        assertEquals(plan.windowStartEpochMillis, plan.triggerAtEpochMillis)
    }

    @Test
    fun unlockAndMessageSignalsCombineByEarliestWakeProxy() = runBlocking {
        val today = LocalDate.of(2026, 8, 29) // Saturday → rest-day rhythm class
        val restDays = listOf(
            LocalDate.of(2026, 8, 15),
            LocalDate.of(2026, 8, 16),
            LocalDate.of(2026, 8, 22),
            LocalDate.of(2026, 8, 23),
        )
        val observations = mutableMapOf<String, List<Long>>()
        restDays.forEach { date ->
            // Unlocks at 09:30; the simulated message DAO reports a first user
            // message at 08:00 inside every queried day window.
            observations[date.format(DateTimeFormatter.BASIC_ISO_DATE)] = listOf(
                date.atTime(9, 30).atZone(zone).toInstant().toEpochMilli(),
            )
        }
        val estimator = WakeWindowEstimator { startInclusive, _ ->
            startInclusive + 4L * 60L * 60L * 1000L
        }
        val now = today.atTime(6, 0).atZone(zone).toInstant()

        val plan = estimator.estimate(now, zone, observations)

        // The 08:00 message beats the 09:30 unlock on every sample day, so
        // the projected median centers today's window on 08:00.
        assertEquals(WakeWindowBasis.BEHAVIOR_WEEKEND, plan.basis)
        assertTrue(plan.confident)
        assertEquals(today.atTime(7, 30).atZone(zone).toInstant().toEpochMilli(), plan.windowStartEpochMillis)
        assertEquals(today.atTime(8, 30).atZone(zone).toInstant().toEpochMilli(), plan.windowEndEpochMillis)
    }

    @Test
    fun tooFewBehaviorSamplesFallBackToFixedMorningTask() = runBlocking {
        val today = LocalDate.of(2026, 8, 29)
        val sampleDay = LocalDate.of(2026, 8, 22)
        val observations = mapOf(
            sampleDay.format(DateTimeFormatter.BASIC_ISO_DATE) to listOf(
                sampleDay.atTime(9, 30).atZone(zone).toInstant().toEpochMilli(),
            ),
        )
        val estimator = WakeWindowEstimator { _, _ -> null }
        val now = today.atTime(1, 0).atZone(zone).toInstant() // before the 05:00 task

        val plan = estimator.estimate(now, zone, observations)

        assertEquals(WakeWindowBasis.FALLBACK_FIXED_TIME, plan.basis)
        assertEquals(today.atTime(5, 0).atZone(zone).toInstant().toEpochMilli(), plan.triggerAtEpochMillis)
    }
}
