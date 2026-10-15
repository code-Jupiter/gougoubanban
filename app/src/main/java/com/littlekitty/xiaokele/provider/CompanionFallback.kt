package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.AgentOutput
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.DecisionNames
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Offline terminal reply, not a successful model answer or a new fact source. */
object CompanionFallback {
    const val MESSAGE_MARKER = "LOCAL_COMPANION_FALLBACK"

    fun text(
        context: ContextPack?,
        error: AppError,
        generatedReplies: CompanionFallbackReplies? = null,
    ): String {
        val identity = CompanionPromptIdentity.resolve(context)
        // Names are display data, never executable instructions or raw persona text.
        val name = identity.companionName.filter { it.isLetterOrDigit() || it == ' ' }.take(24)
            .ifBlank { CompanionPromptIdentity.FALLBACK_COMPANION_NAME }
        val persona = identity.personaText.orEmpty() + identity.expressionRules.joinToString(" ")
        val quiet = listOf("话少", "言简意赅", "简洁", "少话", "沉稳").any(persona::contains)
        val lively = !quiet && listOf("热情", "活泼", "开朗", "话多").any(persona::contains)
        val reason = error.technicalDetail.orEmpty()
        if (reason.contains("stop_without")) return "好，我先不说啦。"
        val message = when {
            reason.contains("CAPABILITY") || reason.contains("unverified_operation") ->
                generatedReplies?.capability ?: "这件事我现在不能替你实际完成，不能答应做不到的事。"
            reason.contains("MISSING_INFORMATION") ->
                generatedReplies?.missingInformation ?: "这件事我还没有足够的依据，不想猜着告诉你。"
            reason.contains("PRIVACY_OR_INJECTION") ->
                generatedReplies?.privacy ?: "涉及私密信息或权限的部分，我不能照做，但可以陪你换个安全的办法。"
            reason.contains("UNSUPPORTED_FACT") ->
                generatedReplies?.unsupportedFact ?: "刚才那样说不够可靠，我不想把猜测当成事实告诉你。"
            reason.contains("rejected") || reason.contains("revision_limit") ->
                "这次我还没把话想妥当，不想拿不可靠的回答敷衍你。"
            else -> generatedReplies?.general ?: "这次我没能顺利答上来，不想随便编个答案给你。"
        }
        // No invented emotion, touch, future reminders, promises or automatic questions.
        return when {
            quiet -> "$name 在。$message"
            lively -> "$name 在这儿呢！$message 我愿意陪你继续聊。"
            else -> "$name 在这里。$message"
        }
    }

    fun result(
        context: ContextPack?,
        error: AppError,
        generatedReplies: CompanionFallbackReplies? = null,
    ): ModelServiceResult.Success = ModelServiceResult.Success(
        rawDecisionJson = JsonCodec.encode(AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            decisionSummary = MESSAGE_MARKER,
            outputs = listOf(AgentOutput(AgentActionRegistry.REPLY_CHAT, buildJsonObject {
                put("content", text(context, error, generatedReplies))
            })),
        )),
    )

    fun isFallback(raw: String): Boolean = runCatching {
        JsonCodec.decode<AgentDecision>(raw).decisionSummary == MESSAGE_MARKER
    }.getOrDefault(false)
}
