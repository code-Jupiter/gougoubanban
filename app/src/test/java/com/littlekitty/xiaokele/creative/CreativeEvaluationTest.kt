package com.littlekitty.xiaokele.creative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CreativeEvaluationTest {

    @Test
    fun noActionIsAValidTerminalEvaluation() {
        val output = CreativeEvaluationValidator.parse(
            """{"outcome":"NO_ACTION","source_ids":[],"risk_level":"LOW"}""",
            allowedSourceIds = setOf("msg_1"),
        )

        assertEquals(CreativeEvaluationOutcomes.NO_ACTION, output?.outcome)
    }

    @Test
    fun realModelLowercaseRiskIsNormalizedWithoutWeakeningValidation() {
        val output = CreativeEvaluationValidator.parse(
            """{"outcome":"NO_ACTION","source_ids":["msg_1"],"risk_level":"medium","scores":{"newness":0.2}}""",
            allowedSourceIds = setOf("msg_1"),
        )

        assertEquals("MEDIUM", output?.riskLevel)
    }

    @Test
    fun ordinaryLowValueWeekCannotPretendToBeAWork() {
        val output = CreativeEvaluationValidator.parse(
            """{"outcome":"WEEKLY_DIARY","work_type":"WEEKLY_DIARY","source_ids":["msg_1"]}""",
            allowedSourceIds = setOf("msg_1"),
        )

        assertTrue(output == null)
    }

    @Test
    fun diaryRequiresFirstPersonFieldsAndRealSourceIds() {
        val output = CreativeEvaluationValidator.parse(
            """
            {
              "outcome":"DAILY_DIARY",
              "work_type":"DAILY_DIARY",
              "title":"风经过窗边",
              "content":"我记得窗边的风，也记得自己想安静陪一会儿。",
              "summary":"测试小狗记下一个被风吹亮的晚上。",
              "dog_emotion_summary":"我有一点想陪伴的心情。",
              "source_ids":["msg_missing"],
              "risk_level":"LOW",
              "new_meaning":"这个细节让我更清楚自己想如何陪伴。"
            }
            """.trimIndent(),
            allowedSourceIds = setOf("msg_1"),
        )

        assertFalse(output != null)
    }

    @Test
    fun inspiredCandidateMustCiteAtLeastOneSource() {
        val output = CreativeEvaluationValidator.parse(
            """{"outcome":"INSPIRED_SHORT_STORY_CANDIDATE","theme":"无名路口","idea_seed":"替人保管犹豫的小动物"}""",
            allowedSourceIds = setOf("msg_1"),
        )

        assertTrue(output == null)
    }

    @Test
    fun sleepIntentRejectsQuotedGoodnightAndRandomIsStable() {
        assertTrue(SleepIntentDetector.detect("我要睡了，晚安"))
        assertFalse(SleepIntentDetector.detect("她说晚安，我只是转述"))
        val first = StableCreativeRandom.hit("message-1", java.time.LocalDate.of(2026, 9, 4), 0.30)
        val second = StableCreativeRandom.hit("message-1", java.time.LocalDate.of(2026, 9, 4), 0.30)
        assertEquals(first, second)
    }
}
