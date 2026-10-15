package com.littlekitty.xiaokele.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class IdleFollowUpPolicyTest {
    @Test
    fun readingTimeIsFixedToOneScreen() {
        assertEquals(6_000L, IdleFollowUpPolicy.delayMillis("短回复"))
        assertEquals(6_000L, IdleFollowUpPolicy.delayMillis("字".repeat(1_000)))
        assertEquals(true, IdleFollowUpPolicy.isEligible("字".repeat(240)))
        assertEquals(true, IdleFollowUpPolicy.isEligible("字".repeat(241)))
        assertEquals(false, IdleFollowUpPolicy.isEligible("   "))
    }
}
