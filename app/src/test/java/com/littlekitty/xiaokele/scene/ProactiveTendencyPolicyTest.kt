package com.littlekitty.xiaokele.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class ProactiveTendencyPolicyTest {
    @Test
    fun learnedLevelScalesGapAndCooldownAroundNeutralDefault() {
        val day = 24L * 60L * 60L * 1_000L
        val sixHours = 6L * 60L * 60L * 1_000L

        assertEquals(day * 2, ProactiveTendencyPolicy.interactionGapMillis(day, 0.0))
        assertEquals(day, ProactiveTendencyPolicy.interactionGapMillis(day, 0.5))
        assertEquals(day / 2, ProactiveTendencyPolicy.interactionGapMillis(day, 1.0))
        assertEquals(sixHours * 2, ProactiveTendencyPolicy.cooldownMillis(sixHours, 0.0))
        assertEquals(sixHours / 2, ProactiveTendencyPolicy.cooldownMillis(sixHours, 1.0))
    }

    @Test
    fun malformedOrOutOfRangeValuesAreSafe() {
        assertEquals(0.5, ProactiveTendencyPolicy.normalizedLevel("not-a-number"), 0.0)
        assertEquals(0.0, ProactiveTendencyPolicy.normalizedLevel("-3"), 0.0)
        assertEquals(1.0, ProactiveTendencyPolicy.normalizedLevel("8"), 0.0)
    }
}
