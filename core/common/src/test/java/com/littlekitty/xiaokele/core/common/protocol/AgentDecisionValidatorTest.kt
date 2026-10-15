package com.littlekitty.xiaokele.core.common.protocol

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDecisionValidatorTest {

    private val allowedAnimations = setOf("default_idle")

    @Test
    fun validFrontChatDecisionIsAccepted() {
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.REPLY_CHAT,
                    payload = buildJsonObject {
                        put("content", "测试用户，测试小狗在呢")
                        put("animation_id", "default_idle")
                    },
                ),
            ),
            animationId = "default_idle",
        )

        val result = AgentDecisionValidator.validate(JsonCodec.encode(decision), allowedAnimations)

        assertTrue(result is AgentDecisionValidation.Valid)
        val valid = result as AgentDecisionValidation.Valid
        assertEquals("default_idle", valid.normalizedAnimationId)
        assertTrue(valid.warnings.isEmpty())
    }

    @Test
    fun invalidJsonIsRejected() {
        val result = AgentDecisionValidator.validate("{not-json", allowedAnimations)

        assertTrue(result is AgentDecisionValidation.Invalid)
    }

    @Test
    fun nonWhitelistActionIsRejected() {
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(action = "CREATE_TODO", payload = buildJsonObject { put("todo", "x") }),
            ),
        )

        val result = AgentDecisionValidator.validate(JsonCodec.encode(decision), allowedAnimations)

        assertTrue(result is AgentDecisionValidation.Invalid)
        val invalid = result as AgentDecisionValidation.Invalid
        assertTrue(invalid.violations.any { it.message.contains("不允许 action") })
    }

    @Test
    fun noActionIsRejectedForFrontChat() {
        val decision = AgentDecision(decision = DecisionNames.NO_ACTION)

        val result = AgentDecisionValidator.validate(JsonCodec.encode(decision), allowedAnimations)

        assertTrue(result is AgentDecisionValidation.Invalid)
    }

    @Test
    fun missingReplyOutputIsRejected() {
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(AgentOutput(action = AgentActionNames.PLAY_ANIMATION)),
        )

        val result = AgentDecisionValidator.validate(JsonCodec.encode(decision), allowedAnimations)

        assertTrue(result is AgentDecisionValidation.Invalid)
    }

    @Test
    fun unknownAnimationIsNormalizedWithWarning() {
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.REPLY_CHAT,
                    payload = buildJsonObject { put("content", "测试用户") },
                ),
            ),
            animationId = "dog_not_imported",
        )

        val result = AgentDecisionValidator.validate(JsonCodec.encode(decision), allowedAnimations)

        assertTrue(result is AgentDecisionValidation.Valid)
        val valid = result as AgentDecisionValidation.Valid
        assertEquals(null, valid.normalizedAnimationId)
        assertEquals(1, valid.warnings.size)
    }

    @Test
    fun multipleReplyOutputsAreRejected() {
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(action = AgentActionNames.REPLY_CHAT, payload = buildJsonObject { put("content", "a") }),
                AgentOutput(action = AgentActionNames.REPLY_CHAT, payload = buildJsonObject { put("content", "b") }),
            ),
        )

        val result = AgentDecisionValidator.validate(JsonCodec.encode(decision), allowedAnimations)

        assertTrue(result is AgentDecisionValidation.Invalid)
    }
}
