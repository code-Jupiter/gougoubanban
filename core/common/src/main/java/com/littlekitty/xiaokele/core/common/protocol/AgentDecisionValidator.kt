package com.littlekitty.xiaokele.core.common.protocol

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class AgentDecisionViolation(
    val path: String,
    val message: String,
)

sealed interface AgentDecisionValidation {
    data class Valid(
        val decision: AgentDecision,
        /** Unknown animation ids are normalized to null by the validator. */
        val normalizedAnimationId: String?,
        val warnings: List<String>,
    ) : AgentDecisionValidation

    data class Invalid(
        val violations: List<AgentDecisionViolation>,
    ) : AgentDecisionValidation
}

/**
 * Independent channel allowlist (I-02). The registry is shared; each channel
 * only executes its own allowlist. Front chat never executes backend actions;
 * todo/trigger mutations are backend-only and never accepted by front chat.
 */
data class DecisionChannel(
    val name: String,
    val allowedOutputActions: Set<String>,
    val requireFrontChatReply: Boolean,
    val maxReplyChatOutputs: Int = 1,
    val requireAtLeastOneOutputOrMutation: Boolean = true,
    val allowMutations: Boolean = false,
    val validateMutations: Boolean = false,
)

object DecisionChannels {
    val FRONT_CHAT = DecisionChannel(
        name = "FRONT_CHAT",
        allowedOutputActions = AgentActionRegistry.frontChatAllowed,
        requireFrontChatReply = true,
        maxReplyChatOutputs = 1,
        requireAtLeastOneOutputOrMutation = true,
        allowMutations = false,
        validateMutations = false,
    )

    val BACKGROUND = DecisionChannel(
        name = "BACKGROUND",
        allowedOutputActions = AgentActionRegistry.backgroundAllowed,
        requireFrontChatReply = false,
        maxReplyChatOutputs = 0,
        requireAtLeastOneOutputOrMutation = true,
        allowMutations = true,
        validateMutations = true,
    )
}

/**
 * Deterministic structured-output validation (C4). The model never chooses
 * which actions are executable; the channel validator does.
 */
object AgentDecisionValidator {

    const val MAX_REPLY_LENGTH = 4_000

    fun validate(
        rawDecisionJson: String,
        allowedAnimationIds: Set<String>,
        requireFrontChatReply: Boolean = true,
        nowEpochMillis: Long? = null,
        currentEventId: String? = null,
    ): AgentDecisionValidation = validate(
        rawDecisionJson = rawDecisionJson,
        allowedAnimationIds = allowedAnimationIds,
        channel = if (requireFrontChatReply) DecisionChannels.FRONT_CHAT else DecisionChannels.BACKGROUND,
        nowEpochMillis = nowEpochMillis,
        currentEventId = currentEventId,
    )

    fun validate(
        rawDecisionJson: String,
        allowedAnimationIds: Set<String>,
        channel: DecisionChannel,
        nowEpochMillis: Long? = null,
        currentEventId: String? = null,
    ): AgentDecisionValidation {
        val decision = try {
            JsonCodec.decode<AgentDecision>(rawDecisionJson)
        } catch (_: SerializationException) {
            return AgentDecisionValidation.Invalid(
                listOf(AgentDecisionViolation("$", "决策 JSON 无法解析")),
            )
        } catch (_: IllegalArgumentException) {
            return AgentDecisionValidation.Invalid(
                listOf(AgentDecisionViolation("$", "决策 JSON 无法解析")),
            )
        }

        val violations = mutableListOf<AgentDecisionViolation>()
        val warnings = mutableListOf<String>()

        if (decision.decision !in DecisionNames.all) {
            violations += AgentDecisionViolation("decision", "未知 decision: ${decision.decision}")
        }

        val replyOutputs = decision.outputs.filter { it.action == AgentActionRegistry.REPLY_CHAT }

        if (channel.requireFrontChatReply) {
            if (decision.decision != DecisionNames.CREATE_PLAN) {
                violations += AgentDecisionViolation("decision", "前台聊天要求 decision=CREATE_PLAN")
            }
            if (replyOutputs.isEmpty()) {
                violations += AgentDecisionViolation("outputs", "缺少 REPLY_CHAT 输出")
            }
        }

        if (channel.maxReplyChatOutputs >= 0 && replyOutputs.size > channel.maxReplyChatOutputs) {
            violations += AgentDecisionViolation("outputs", "REPLY_CHAT 输出数量超过通道上限 ${channel.maxReplyChatOutputs}")
        }

        if (decision.decision == DecisionNames.CREATE_PLAN &&
            channel.requireAtLeastOneOutputOrMutation &&
            decision.outputs.isEmpty() && decision.todoMutations.isEmpty() && decision.triggerMutations.isEmpty()
        ) {
            violations += AgentDecisionViolation("outputs", "CREATE_PLAN 至少需要一个输出或 mutation")
        }

        if (channel.allowMutations.not()) {
            if (decision.todoMutations.isNotEmpty()) {
                violations += AgentDecisionViolation(
                    "todo_mutations",
                    "todo_mutations 是后台安全 mutation，前台通道不允许",
                )
            }
            if (decision.triggerMutations.isNotEmpty()) {
                violations += AgentDecisionViolation(
                    "trigger_mutations",
                    "trigger_mutations 是后台安全 mutation，前台通道不允许",
                )
            }
        }

        decision.outputs.forEachIndexed { index, output ->
            val path = "outputs[$index]"
            if (channel.allowedOutputActions.contains(output.action).not()) {
                violations += AgentDecisionViolation(path, "通道 ${channel.name} 不允许 action: ${output.action}")
            }
            when (output.action) {
                AgentActionRegistry.REPLY_CHAT -> validateReplyPayload(output.payload, path, violations)
                AgentActionRegistry.UPDATE_PET_STATE -> validatePetStatePayload(output.payload, path, violations)
                AgentActionRegistry.SEND_PROACTIVE_MESSAGE -> {
                    val content = output.payload["content"] as? JsonPrimitive
                    if (content == null || !content.isString || content.content.isBlank()) {
                        violations += AgentDecisionViolation("$path.payload.content", "主动消息 content 必须是非空字符串")
                    }
                }

                AgentActionRegistry.SEND_NOTIFICATION -> {
                    val title = (output.payload["title"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    val body = (output.payload["body"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    if (title.isNullOrBlank() && body.isNullOrBlank()) {
                        violations += AgentDecisionViolation("$path.payload", "通知至少需要 title 或 body")
                    }
                }

                AgentActionRegistry.SET_WIDGET_STATE -> {
                    val state = output.payload["state_id"] as? JsonPrimitive
                    if (state == null || !state.isString || state.content.isBlank()) {
                        violations += AgentDecisionViolation("$path.payload.state_id", "小组件 state_id 必须是非空字符串")
                    }
                }

                AgentActionRegistry.PLAY_ANIMATION -> {
                    val animation = output.payload["animation_id"] as? JsonPrimitive
                    if (animation == null || !animation.isString || animation.content.isBlank()) {
                        violations += AgentDecisionViolation(
                            "$path.payload.animation_id",
                            "PLAY_ANIMATION 需要 animation_id",
                        )
                    }
                }
            }
        }

        if (decision.decision == DecisionNames.NO_ACTION) {
            if (decision.outputs.isNotEmpty()) {
                violations += AgentDecisionViolation("outputs", "NO_ACTION 不允许输出 Action")
            }
            if (channel.allowMutations && (decision.todoMutations.isNotEmpty() || decision.triggerMutations.isNotEmpty())) {
                violations += AgentDecisionViolation("mutations", "NO_ACTION 不允许 todo/trigger mutations")
            }
        }

        if (channel.allowMutations && channel.validateMutations && decision.decision == DecisionNames.CREATE_PLAN) {
            val todoValidation = TodoMutationValidator.validate(
                mutations = decision.todoMutations,
                currentEventId = currentEventId.orEmpty(),
                nowEpochMillis = nowEpochMillis,
            )
            if (todoValidation is TodoMutationValidation.Invalid) {
                todoValidation.violations.forEach { violation ->
                    violations += AgentDecisionViolation(violation.path, violation.message)
                }
            }
            val triggerValidation = TriggerMutationValidator.validate(
                mutations = decision.triggerMutations,
                nowEpochMillis = nowEpochMillis,
            )
            if (triggerValidation is TriggerMutationValidation.Invalid) {
                triggerValidation.violations.forEach { violation ->
                    violations += AgentDecisionViolation(violation.path, violation.message)
                }
            }
        }

        var normalizedAnimationId = decision.animationId
        decision.animationId?.let { requested ->
            if (allowedAnimationIds.contains(requested).not()) {
                normalizedAnimationId = null
                warnings += "未知 animation_id `$requested` 已降级为默认待机"
            }
        }

        if (violations.isNotEmpty()) {
            return AgentDecisionValidation.Invalid(violations)
        }
        return AgentDecisionValidation.Valid(
            decision = decision,
            normalizedAnimationId = normalizedAnimationId,
            warnings = warnings,
        )
    }

    private fun validateReplyPayload(
        payload: kotlinx.serialization.json.JsonObject,
        path: String,
        violations: MutableList<AgentDecisionViolation>,
    ) {
        val content = payload["content"] as? JsonPrimitive
        val text = content?.takeIf { it.isString }?.contentOrNull
        if (text.isNullOrBlank()) {
            violations += AgentDecisionViolation("$path.payload.content", "回复内容不能为空")
        } else if (text.length > MAX_REPLY_LENGTH) {
            violations += AgentDecisionViolation("$path.payload.content", "回复内容超过 $MAX_REPLY_LENGTH 字符")
        }
        payload["animation_id"]?.let { animation ->
            if (animation !is JsonPrimitive || !animation.isString) {
                violations += AgentDecisionViolation("$path.payload.animation_id", "animation_id 必须是字符串")
            }
        }
        payload["todo_id"]?.let { todoId ->
            if (todoId !is JsonPrimitive || !todoId.isString || todoId.content.isBlank()) {
                violations += AgentDecisionViolation("$path.payload.todo_id", "todo_id 必须是非空字符串")
            }
        }
    }

    private fun validatePetStatePayload(
        payload: kotlinx.serialization.json.JsonObject,
        path: String,
        violations: MutableList<AgentDecisionViolation>,
    ) {
        val state = payload["state_id"] as? JsonPrimitive
        if (state == null || !state.isString || state.content.isBlank()) {
            violations += AgentDecisionViolation("$path.payload.state_id", "state_id 必须是非空字符串")
        }
    }
}
