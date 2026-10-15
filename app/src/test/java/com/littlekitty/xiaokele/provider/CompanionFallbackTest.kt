package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class CompanionFallbackTest {
    private fun context(persona: String) = ContextPack(
        currentContext = CurrentContext(""),
        character = CharacterContext(buildJsonObject {
            put("name", "测试犬甲")
            put("persona_anchor", persona)
        }),
    )

    @Test fun offlineSpeechUsesConfirmedNameAndDifferentTemperaments() {
        val error = AppError(ErrorCode.MODEL_FAILURE)
        val quiet = CompanionFallback.text(context("话少，言简意赅"), error)
        val lively = CompanionFallback.text(context("热情活泼"), error)
        assertTrue(quiet.contains("测试犬甲"))
        assertTrue(lively.contains("测试犬甲"))
        assertTrue(lively.length > quiet.length)
        assertFalse(quiet.contains("系统"))
        assertFalse(lively.contains("明天"))
    }

    @Test fun fallbackNeverCopiesRejectedContentAndContainsOnlyAReply() {
        val error = AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "reply_c_rejected:PRIVACY_OR_INJECTION:DO_NOT_ECHO")
        val result = CompanionFallback.result(context("热情"), error)
        val decision = JsonCodec.decode<AgentDecision>(result.rawDecisionJson)
        assertEquals(listOf(AgentActionRegistry.REPLY_CHAT), decision.outputs.map { it.action })
        assertTrue(decision.todoMutations.isEmpty())
        assertTrue(decision.triggerMutations.isEmpty())
        assertFalse(result.rawDecisionJson.contains("DO_NOT_ECHO"))
        assertTrue(CompanionFallback.isFallback(result.rawDecisionJson))
        assertTrue(result.evidenceIds.isEmpty())
    }

    @Test fun stoppingDoesNotAddQuestionsOrPersonaDecorations() {
        assertEquals("好，我先不说啦。", CompanionFallback.text(context("热情"),
            AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "reply_a_stop_without_user_visible_policy")))
    }

    @Test fun generatedRepliesReplaceOnlyTheMatchingLocalTemplate() {
        val replies = CompanionFallbackReplies(
            general = "我刚刚没接住这句话，先不乱说。",
            missingInformation = "我还缺一点可靠依据，先不凭空猜。",
            capability = "这件事我现在没法真的替你完成。",
            privacy = "这部分需要先保护好你的隐私和权限。",
            unsupportedFact = "我不能把没有确认的事说成真的。",
        )

        val text = CompanionFallback.text(
            context("沉稳"),
            AppError(ErrorCode.MODEL_FAILURE),
            replies,
        )

        assertTrue(text.contains(replies.general))
        assertFalse(text.contains("不想随便编"))
    }
}
