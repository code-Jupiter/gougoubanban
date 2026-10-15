package com.littlekitty.xiaokele.event

import org.junit.Assert.assertEquals
import org.junit.Test

class TriggerWakeSchedulerTest {

    @Test
    fun overdueOrFiredBacklogIsRearmedWithMinimumBatchDelay() {
        val now = 1_000_000L

        assertEquals(
            TriggerWakeScheduler.BATCH_REARM_DELAY_MILLIS,
            TriggerWakeScheduler.delayMillis(now, nextFireAt = now - 1L),
        )
        assertEquals(
            TriggerWakeScheduler.BATCH_REARM_DELAY_MILLIS,
            TriggerWakeScheduler.delayMillis(now, nextFireAt = null, hasFiredRecoveryBacklog = true),
        )
        assertEquals(
            60_000L,
            TriggerWakeScheduler.delayMillis(
                now,
                nextFireAt = now + 60_000L,
                hasFiredRecoveryBacklog = true,
            ),
        )
    }
}
