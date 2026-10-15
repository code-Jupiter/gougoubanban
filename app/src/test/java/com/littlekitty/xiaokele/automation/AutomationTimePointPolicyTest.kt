package com.littlekitty.xiaokele.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTimePointPolicyTest {

    @Test
    fun catchUpIsBoundedByScheduledTimeNotRestartTime() {
        val scheduledAt = 1_000_000L

        assertEquals(
            AutomationTimePointState.ELIGIBLE,
            AutomationTimePointPolicy.state(scheduledAt, scheduledAt + 29L * 60L * 1_000L),
        )
        assertEquals(
            AutomationTimePointState.MISSED,
            AutomationTimePointPolicy.state(scheduledAt, scheduledAt + 30L * 60L * 1_000L),
        )
        assertEquals(scheduledAt + 30L * 60L * 1_000L, AutomationTimePointPolicy.expiresAtEpochMillis(scheduledAt))
        assertTrue(AutomationTimePointPolicy.shouldRegister(scheduledAt, scheduledAt + 5L * 60L * 1_000L))
        assertFalse(AutomationTimePointPolicy.shouldRegister(scheduledAt, scheduledAt + 31L * 60L * 1_000L))
    }
}
