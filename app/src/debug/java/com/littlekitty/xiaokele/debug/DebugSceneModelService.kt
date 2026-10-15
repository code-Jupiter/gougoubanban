package com.littlekitty.xiaokele.debug

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
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.network.model.FakeModelService
import com.littlekitty.xiaokele.core.network.model.ModelService
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant

enum class DebugSceneMode {
    NONE,
    SCENE_WIDGET_TODO,
    SCENE_INTERACTION_REENGAGE,
    SCENE_DAILY_WEATHER,
    SCENE_WAKE_WEATHER,
    SCENE_DISCOVERY_NUDGE,
    SCENE_WORK_GUARD,
    SCENE_HIGH_ALERT_CARING,
    SCENE_WELCOME_CHAIN,
    SCENE_NEARBY_TODO,
    SCENE_INVALID_OUTPUT,
}

/**
 * Phase 7 debug-only scene script model. It lives in app/src/debug and is
 * never compiled into release APKs. It delegates every non-scene mode to the
 * Phase 2 FakeModelService so existing debug controls keep working.
 */
class DebugSceneModelService(
    private val delegate: FakeModelService,
) : ModelService {

    @Volatile
    var sceneMode: DebugSceneMode = DebugSceneMode.NONE

    override val serviceName: String = "debug_phase7_scene_model"

    override suspend fun generate(context: ContextPack): ModelServiceResult = when (sceneMode) {
        DebugSceneMode.NONE -> delegate.generate(context)
        DebugSceneMode.SCENE_WIDGET_TODO ->
            ModelServiceResult.Success(sceneWidgetTodoDecision(context, stateId = "EXPECTING", shortText = "在等你"))

        DebugSceneMode.SCENE_INTERACTION_REENGAGE ->
            ModelServiceResult.Success(sceneWidgetTodoDecision(context, stateId = "EXPECTING", shortText = "好久没见，想聊点什么"))

        DebugSceneMode.SCENE_DAILY_WEATHER ->
            ModelServiceResult.Success(sceneWidgetTodoDecision(context, stateId = "CARING", shortText = "今天记得带伞和添衣"))

        DebugSceneMode.SCENE_WAKE_WEATHER ->
            ModelServiceResult.Success(sceneWidgetTodoDecision(context, stateId = "CARING", shortText = "早上好，今天午后有雨记得带伞"))

        DebugSceneMode.SCENE_DISCOVERY_NUDGE ->
            ModelServiceResult.Success(sceneWidgetTodoDecision(context, stateId = "EXPECTING", shortText = "发现一个周末市集，想听吗"))

        DebugSceneMode.SCENE_NEARBY_TODO ->
            ModelServiceResult.Success(sceneWidgetTodoDecision(context, stateId = "EXPECTING", shortText = "有吃的在附近"))

        DebugSceneMode.SCENE_WORK_GUARD ->
            ModelServiceResult.Success(sceneWorkGuardDecision(context))

        DebugSceneMode.SCENE_HIGH_ALERT_CARING ->
            ModelServiceResult.Success(sceneHighAlertDecision(context))

        DebugSceneMode.SCENE_WELCOME_CHAIN ->
            ModelServiceResult.Success(sceneWelcomeChainDecision(context))

        DebugSceneMode.SCENE_INVALID_OUTPUT ->
            ModelServiceResult.Success("debug intentionally invalid background output")
    }

    private fun sceneWidgetTodoDecision(
        context: ContextPack,
        stateId: String,
        shortText: String,
    ): String {
        val baseTime = contextEventInstant(context)
        val todoId = EntityId.generate("todo").value
        val eventId = context.event?.get("id")?.let { it as? JsonPrimitive }?.contentOrNull
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            decisionSummary = "DEBUG_FAKE Phase 7 场景：Widget + 待承接 Todo",
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.SEND_PROACTIVE_MESSAGE,
                    payload = buildJsonObject {
                        put("content", "你好，${shortText}。这是 DEBUG_FAKE 的悬浮窗场景承接。")
                        put("state_id", stateId)
                        put("animation_id", "default_idle")
                        put("short_text", shortText)
                        put("expires_at", InstantJson.formatUtc(baseTime.plusSeconds(30 * 60)))
                        put(
                            "click_target",
                            buildJsonObject {
                                put("event_id", eventId ?: "")
                                put("todo_id", todoId)
                            },
                        )
                    },
                ),
            ),
            todoMutations = listOf(
                TodoMutation(
                    operation = MutationOperationNames.CREATE,
                    todo = buildJsonObject {
                        put("id", todoId)
                        put("type", TodoTypeNames.DISCUSS_LATER)
                        put("title", shortText)
                        put("priority", 50)
                        put("expires_at", InstantJson.formatUtc(baseTime.plusSeconds(30 * 60)))
                        put(
                            "payload",
                            buildJsonObject {
                                put("handoff_text", "你好，${shortText}。这是 DEBUG_FAKE 场景承接内容，不是通用提醒话术。")
                                put("preferred_channels", "IN_APP_ON_OPEN")
                            },
                        )
                    },
                ),
            ),
        )
        return JsonCodec.encode(decision)
    }

    private fun sceneWorkGuardDecision(context: ContextPack): String {
        val baseTime = contextEventInstant(context)
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            decisionSummary = "DEBUG_FAKE Phase 7 场景：进入 WORK_GUARD",
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.UPDATE_PET_STATE,
                    payload = buildJsonObject {
                        put("state_id", "WORK_GUARD")
                        put("animation_id", "default_idle")
                        put("expires_at", InstantJson.formatUtc(baseTime.plusSeconds(12 * 3600)))
                    },
                ),
            ),
        )
        return JsonCodec.encode(decision)
    }

    private fun sceneHighAlertDecision(context: ContextPack): String {
        val baseTime = contextEventInstant(context)
        val todoId = EntityId.generate("todo").value
        val eventId = context.event?.get("id")?.let { it as? JsonPrimitive }?.contentOrNull
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            decisionSummary = "DEBUG_FAKE Phase 7 场景：高风险天气 quota-exempt 通知 + 短暂 CARING",
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.SEND_NOTIFICATION,
                    payload = buildJsonObject {
                        put("title", "天气提醒（DEBUG_FAKE）")
                        put("body", "短时强降雨，出门注意安全。这是 DEBUG_FAKE 本地链路。")
                    },
                ),
                AgentOutput(
                    action = AgentActionNames.UPDATE_PET_STATE,
                    payload = buildJsonObject {
                        put("state_id", "CARING")
                        put("animation_id", "default_idle")
                        put("short_text", "短时强降雨")
                        put("expires_at", InstantJson.formatUtc(baseTime.plusSeconds(30 * 60)))
                        put("click_target", buildJsonObject { put("event_id", eventId ?: "") })
                    },
                ),
            ),
            todoMutations = listOf(
                TodoMutation(
                    operation = MutationOperationNames.CREATE,
                    todo = buildJsonObject {
                        put("id", todoId)
                        put("type", TodoTypeNames.DISCUSS_LATER)
                        put("title", "短时强降雨")
                        put("priority", 90)
                        put("expires_at", InstantJson.formatUtc(baseTime.plusSeconds(30 * 60)))
                        put(
                            "payload",
                            buildJsonObject {
                                put("handoff_text", "刚才那条短时强降雨预警，在这里继续确认一下。这是 DEBUG_FAKE。")
                            },
                        )
                    },
                ),
            ),
        )
        return JsonCodec.encode(decision)
    }

    private fun sceneWelcomeChainDecision(context: ContextPack): String {
        val baseTime = contextEventInstant(context)
        val todoId = EntityId.generate("todo").value
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            decisionSummary = "DEBUG_FAKE Phase 7 场景：离院到家同一事件链承接",
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.SEND_PROACTIVE_MESSAGE,
                    payload = buildJsonObject {
                        put("content", "回来了，今天辛苦了。这是 DEBUG_FAKE 的悬浮窗承接。")
                        put("state_id", "WELCOME_BACK")
                        put("animation_id", "default_idle")
                        put("short_text", "回来啦")
                        put("expires_at", InstantJson.formatUtc(baseTime.plusSeconds(30 * 60)))
                        put("click_target", buildJsonObject { put("todo_id", todoId) })
                    },
                ),
            ),
            todoMutations = listOf(
                TodoMutation(
                    operation = MutationOperationNames.CREATE,
                    todo = buildJsonObject {
                        put("id", todoId)
                        put("type", TodoTypeNames.DISCUSS_LATER)
                        put("title", "到家承接")
                        put("priority", 75)
                        put("expires_at", InstantJson.formatUtc(baseTime.plusSeconds(30 * 60)))
                        put(
                            "payload",
                            buildJsonObject {
                                put("handoff_text", "刚才那条预警一直留在这里。这是 DEBUG_FAKE。")
                            },
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
            ?.let { it as? JsonPrimitive }
            ?.takeIf { it.isString }
            ?.contentOrNull
        return raw?.let { InstantJson.parseOrNull(it) } ?: Instant.now()
    }
}
