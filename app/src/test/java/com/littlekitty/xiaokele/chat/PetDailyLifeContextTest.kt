package com.littlekitty.xiaokele.chat

import com.littlekitty.xiaokele.core.database.state.PetStateIds
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PetDailyLifeContextTest {
    @Test
    fun ordinaryLifeIsStableForTheDayAndKeepsDogEmbodiment() {
        val zone = ZoneId.of("Asia/Shanghai")
        val morning = PetDailyLifeContext.build(
            Instant.parse("2026-08-22T01:00:00Z"),
            zone,
            PetStateIds.IDLE,
        )
        val evening = PetDailyLifeContext.build(
            Instant.parse("2026-08-22T12:00:00Z"),
            zone,
            PetStateIds.IDLE,
        )

        assertEquals(morning["activity"], evening["activity"])
        assertTrue(morning["activity"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals("LOCAL_DAILY_CANON", morning["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun situationalStateGroundsTheDailyActivityWithoutInventingAnotherStory() {
        val context = PetDailyLifeContext.build(
            Instant.parse("2026-08-22T12:00:00Z"),
            ZoneId.of("Asia/Shanghai"),
            PetStateIds.WORK_GUARD,
        )

        val activity = context["activity"]!!.jsonPrimitive.content
        // The activity must ground the current state without inventing a
        // persona detail: no fixed appearance, and no fixed user situation.
        assertTrue(activity.isNotBlank())
        FORBIDDEN_PERSONA_TOKENS.forEach { token ->
            assertTrue("activity must not presume $token", activity.contains(token).not())
        }
    }

    @Test
    fun everyPetStateActivityStaysFreeOfFixedAppearance() {
        // The daily life anchor is injected into every front-chat prompt as
        // current_context.pet_daily_life, so one uncovered branch is enough to
        // make the companion describe a body the user never confirmed. Iterate
        // every declared state instead of sampling one.
        val zone = ZoneId.of("Asia/Shanghai")
        // Driven by the declared state set, so a newly added state is covered
        // automatically instead of silently escaping this gate.
        val states = PetStateIds.all
        states.forEach { state ->
            // Sweep dates so the rotating ordinary-activity pool is fully covered.
            (0L until 16L).forEach { dayOffset ->
                val context = PetDailyLifeContext.build(
                    Instant.parse("2026-08-22T12:00:00Z").plusSeconds(dayOffset * 86_400L),
                    zone,
                    state,
                )
                val activity = context["activity"]!!.jsonPrimitive.content
                val mood = context["mood"]!!.jsonPrimitive.content
                assertTrue("activity for $state must not be blank", activity.isNotBlank())
                FORBIDDEN_PERSONA_TOKENS.forEach { token ->
                    assertTrue(
                        "state $state must not presume $token but was: $activity | $mood",
                        activity.contains(token).not() && mood.contains(token).not(),
                    )
                }
            }
        }
    }

    private companion object {
        /**
         * Physical traits belonging to the retired private persona. The public
         * edition derives appearance from the user's own first-create input, so
         * no shipping prompt anchor may assert these.
         */
        val FORBIDDEN_PERSONA_TOKENS = listOf(
            "长发",
            "马尾",
            "头发",
            "梳开",
            "扎起",
            "扎头发",
            "狗耳朵",
            "猫猫头",
            "小可乐",
            "夜班",
        )
    }
}
