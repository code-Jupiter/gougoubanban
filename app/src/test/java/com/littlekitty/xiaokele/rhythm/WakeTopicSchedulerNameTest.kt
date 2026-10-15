package com.littlekitty.xiaokele.rhythm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeTopicSchedulerNameTest {

    /**
     * Regression for the self-rescheduling loop: the unique work name must be
     * stable within a day (no millisecond trigger component), so a repeated
     * register with KEEP reuses one task instead of stacking a new immediate
     * run every time the estimate clamps to `now`.
     */
    @Test
    fun workNameIsStablePerDayAndCarriesNoTriggerMillis() {
        assertEquals("wake_topic_20260829", WakeTopicScheduler.uniqueWorkName("20260829"))
        // A millisecond trigger component would make every register create a
        // brand-new immediate work (the original self-rescheduling loop bug).
        assertTrue(Regex("^wake_topic_\\d{8}$").matches(WakeTopicScheduler.uniqueWorkName("20260830")))
    }

    @Test
    fun executionPolicyRejectsExpiredWindowBeforeWeatherCanRun() {
        assertEquals(
            WakeTopicWindowState.IN_WINDOW,
            WakeTopicExecutionPolicy.windowState(nowEpochMillis = 150L, windowStartEpochMillis = 100L, windowEndEpochMillis = 200L),
        )
        assertEquals(
            WakeTopicWindowState.EXPIRED,
            WakeTopicExecutionPolicy.windowState(nowEpochMillis = 201L, windowStartEpochMillis = 100L, windowEndEpochMillis = 200L),
        )
        assertEquals(
            WakeTopicWindowState.INVALID,
            WakeTopicExecutionPolicy.windowState(nowEpochMillis = 150L, windowStartEpochMillis = 0L, windowEndEpochMillis = 200L),
        )
    }
}
