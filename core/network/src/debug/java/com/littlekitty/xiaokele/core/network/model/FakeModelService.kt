package com.littlekitty.xiaokele.core.network.model

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionNames
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.AgentOutput
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.DecisionNames
import com.littlekitty.xiaokele.core.common.protocol.MutationOperationNames
import com.littlekitty.xiaokele.core.common.protocol.TodoMutation
import com.littlekitty.xiaokele.core.common.protocol.TodoTypeNames
import com.littlekitty.xiaokele.core.common.protocol.TriggerMutation
import com.littlekitty.xiaokele.core.common.protocol.TriggerTypeNames
import com.littlekitty.xiaokele.core.common.time.InstantJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

enum class FakeModelMode {
    SUCCESS,
    FAILURE,
    INVALID_JSON,
    NO_ACTION,
    UNSUPPORTED_ACTION,
    BACKGROUND_ACTION,
    BACKGROUND_TODO_TRIGGER,
    BACKGROUND_NO_ACTION,
    BACKGROUND_PROACTIVE,
    BACKGROUND_NOTIFICATION,
    BACKGROUND_WIDGET,
    BACKGROUND_ANIMATION,
}

/**
 * Deterministic fake used to exercise the complete front-chat channel while
 * D-01/D-02/D-03 remain unconfirmed. It is a local test double, not a product
 * fallback for release model outages.
 */
class FakeModelService(
    initialMode: FakeModelMode = FakeModelMode.SUCCESS,
) : ModelService {

    @Volatile
    var mode: FakeModelMode = initialMode

    override val serviceName: String = "fake_model"

    override suspend fun generate(context: ContextPack): ModelServiceResult =
        when (mode) {
            FakeModelMode.SUCCESS -> ModelServiceResult.Success(successDecision(context))
            FakeModelMode.FAILURE -> ModelServiceResult.Failure(
                AppError(ErrorCode.MODEL_FAILURE, technicalDetail = "fake_model_failure"),
            )
            FakeModelMode.INVALID_JSON -> ModelServiceResult.Success("{not-valid-json")
            FakeModelMode.NO_ACTION -> ModelServiceResult.Success(
                JsonCodec.encode(AgentDecision(decision = DecisionNames.NO_ACTION)),
            )
            FakeModelMode.UNSUPPORTED_ACTION -> ModelServiceResult.Success(
                JsonCodec.encode(
                    AgentDecision(
                        decision = DecisionNames.CREATE_PLAN,
                        outputs = listOf(
                            AgentOutput(
                                action = "CREATE_TODO",
                                payload = buildJsonObject { put("todo", "not-allowed") },
                            ),
                        ),
                    ),
                ),
            )
            FakeModelMode.BACKGROUND_ACTION -> ModelServiceResult.Success(backgroundActionDecision(context))
            FakeModelMode.BACKGROUND_TODO_TRIGGER -> ModelServiceResult.Success(backgroundTodoTriggerDecision(context))
            FakeModelMode.BACKGROUND_NO_ACTION -> ModelServiceResult.Success(
                JsonCodec.encode(
                    AgentDecision(
                        decision = DecisionNames.NO_ACTION,
                        decisionSummary = "fake 后台判定：保持安静",
                    ),
                ),
            )
            FakeModelMode.BACKGROUND_PROACTIVE -> ModelServiceResult.Success(backgroundProactiveDecision(context))
            FakeModelMode.BACKGROUND_NOTIFICATION -> ModelServiceResult.Success(backgroundNotificationDecision(context))
            FakeModelMode.BACKGROUND_WIDGET -> ModelServiceResult.Success(backgroundWidgetDecision(context))
            FakeModelMode.BACKGROUND_ANIMATION -> ModelServiceResult.Success(backgroundAnimationDecision(context))
        }

    private fun successDecision(context: ContextPack): String {
        val isIdleFollowUp = context.event
            ?.get("type")
            ?.jsonPrimitive
            ?.contentOrNull == "IDLE_FOLLOW_UP"
        val userText = context.event
            ?.get("payload")
            ?.jsonObject
            ?.get("user_message")
            ?.jsonObject
            ?.get("content")
            ?.jsonPrimitive
            ?.takeIf { it.isString }
            ?.contentOrNull
            .orEmpty()
        val expiresAt = InstantJson.formatUtc(Instant.now().plusSeconds(5 * 60))
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            decisionSummary = if (isIdleFollowUp) "fake 前台停顿续话" else "fake 前台回复与状态更新",
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.REPLY_CHAT,
                    payload = buildJsonObject {
                        put(
                            "content",
                            if (isIdleFollowUp) {
                                "刚刚想到一个轻松的：如果现在立刻放空十分钟，你最想做什么？"
                            } else {
                                "测试用户，测试小狗在呢。你说：「$userText」"
                            },
                        )
                        put("animation_id", "default_idle")
                    },
                ),
                AgentOutput(
                    action = AgentActionNames.UPDATE_PET_STATE,
                    payload = buildJsonObject {
                        put("state_id", "EXPECTING")
                        put("animation_id", "default_idle")
                        put("short_text", "测试小狗在听你说话")
                        put("expires_at", expiresAt)
                        put("click_target", JsonObject(emptyMap()))
                    },
                ),
            ),
            animationId = "default_idle",
        )
        return JsonCodec.encode(decision)
    }

    private fun backgroundActionDecision(context: ContextPack): String {
        val baseTime = contextEventInstant(context)
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            decisionSummary = "fake 后台动作：更新待机状态",
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.UPDATE_PET_STATE,
                    payload = buildJsonObject {
                        put("state_id", "IDLE")
                        put("animation_id", "default_idle")
                        put("short_text", "后台候选动作")
                        put("expires_at", InstantJson.formatUtc(baseTime.plusSeconds(5 * 60)))
                        put("click_target", JsonObject(emptyMap()))
                    },
                ),
            ),
        )
        return JsonCodec.encode(decision)
    }

    private fun backgroundProactiveDecision(context: ContextPack): String {
        val baseTime = contextEventInstant(context)
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            decisionSummary = "fake 后台动作：主动消息",
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.SEND_PROACTIVE_MESSAGE,
                    payload = buildJsonObject {
                        put("content", "测试用户，测试小狗来主动说一句：Phase 5 主动消息通道已接通。")
                        put("animation_id", "default_idle")
                    },
                ),
            ),
            animationId = "default_idle",
        )
        return JsonCodec.encode(decision)
    }

    private fun backgroundNotificationDecision(context: ContextPack): String {
        val baseTime = contextEventInstant(context)
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            decisionSummary = "fake 后台动作：通知",
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.SEND_NOTIFICATION,
                    payload = buildJsonObject {
                        put("title", "测试小狗提醒")
                        put("body", "这是 debug 构建的 Phase 5 通知通道。")
                        put("notification_type", "debug")
                        put("expires_at", InstantJson.formatUtc(baseTime.plusSeconds(30 * 60)))
                    },
                ),
            ),
        )
        return JsonCodec.encode(decision)
    }

    private fun backgroundWidgetDecision(context: ContextPack): String {
        val baseTime = contextEventInstant(context)
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            decisionSummary = "fake 后台动作：Widget 状态",
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.SET_WIDGET_STATE,
                    payload = buildJsonObject {
                        put("state_id", "WAITING")
                        put("animation_id", "default_idle")
                        put("short_text", "桌面气泡候选")
                        put("expires_at", InstantJson.formatUtc(baseTime.plusSeconds(10 * 60)))
                    },
                ),
            ),
        )
        return JsonCodec.encode(decision)
    }

    private fun backgroundAnimationDecision(context: ContextPack): String {
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            decisionSummary = "fake 后台动作：动画语义目录",
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.PLAY_ANIMATION,
                    payload = buildJsonObject {
                        put("animation_id", "dog_not_imported")
                    },
                ),
            ),
        )
        return JsonCodec.encode(decision)
    }

    private fun backgroundTodoTriggerDecision(context: ContextPack): String {
        val baseTime = contextEventInstant(context)
        val todoId = EntityId.generate("todo").value
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            decisionSummary = "fake 后台计划：写入待行动与未来重判 Trigger",
            todoMutations = listOf(
                TodoMutation(
                    operation = MutationOperationNames.CREATE,
                    todo = buildJsonObject {
                        put("id", todoId)
                        put("type", TodoTypeNames.DISCUSS_LATER)
                        put("title", "fake 待聊话题")
                        put("priority", 50)
                        put("expires_at", InstantJson.formatUtc(baseTime.plusSeconds(30 * 60)))
                        put(
                            "payload",
                            buildJsonObject {
                                put("topic", "Phase 4 本地闭环")
                                put("preferred_channels", "CHAT_OPPORTUNISTIC")
                            },
                        )
                    },
                ),
            ),
            triggerMutations = listOf(
                TriggerMutation(
                    operation = MutationOperationNames.CREATE,
                    trigger = buildJsonObject {
                        put("todo_id", todoId)
                        put("trigger_type", TriggerTypeNames.TIME)
                        put(
                            "trigger_config_json",
                            buildJsonObject { put("delay", "PT2M") },
                        )
                    },
                ),
            ),
        )
        return JsonCodec.encode(decision)
    }

    private fun contextEventInstant(context: ContextPack): Instant {
        val raw = context.event
            ?.get("occurred_at")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.takeIf { it.isString }
            ?.contentOrNull
        return raw?.let { InstantJson.parseOrNull(it) } ?: Instant.now()
    }
}
