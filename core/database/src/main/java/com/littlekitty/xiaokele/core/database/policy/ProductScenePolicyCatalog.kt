package com.littlekitty.xiaokele.core.database.policy

import com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames
import com.littlekitty.xiaokele.core.common.scene.SceneCooldown
import com.littlekitty.xiaokele.core.common.scene.SceneEventTypes
import com.littlekitty.xiaokele.core.common.scene.ScenePolicyContract
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.ScenarioPolicyEntity
import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import com.littlekitty.xiaokele.core.common.protocol.TodoTypeNames
import com.littlekitty.xiaokele.core.common.protocol.TriggerTypeNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Frozen Phase 7 product scene policies. These rows are seeded into
 * `scenario_policies` (Room v4, no migration) at application startup and in
 * tests. Policy values are candidates only where PHASE_7_GATE.md says so;
 * provider adapters remain copy-free.
 */
object ProductScenePolicyCatalog {

    const val POLICY_ID_INTERACTION_GAP = "product_scene_policy_interaction_gap_v1"
    const val POLICY_ID_WEATHER_CHANGE = "product_scene_policy_weather_change_v1"
    const val POLICY_ID_WEATHER_DAILY = "product_scene_policy_weather_daily_v1"
    const val POLICY_ID_WEATHER_WAKE = "product_scene_policy_weather_wake_v1"
    const val POLICY_ID_DISCOVERY_NUDGE = "product_scene_policy_discovery_nudge_v1"
    const val POLICY_ID_WEATHER_ALERT_HIGH = "product_scene_policy_weather_alert_high_v1"
    const val POLICY_ID_NEARBY = "product_scene_policy_nearby_candidate_v1"

    private val normalOutreachActions: List<String> = listOf(
        AgentActionRegistry.SEND_PROACTIVE_MESSAGE,
        AgentActionRegistry.UPDATE_PET_STATE,
        AgentActionRegistry.PLAY_ANIMATION,
    )

    private val discussTodoTypes: List<String> = listOf(
        TodoTypeNames.DISCUSS_LATER,
        TodoTypeNames.REEVALUATE_LATER,
        TodoTypeNames.IN_APP_ON_OPEN,
    )

    private const val CONTEXT_DRIVEN_TOPIC_RULE =
        "不要把每次触达固定成同一组提醒，也不要仅根据场景名称套用清单。先阅读 event.payload.recent_messages，结合 retrieved_memories、relevant_todos、recent_deliveries、current_context 和本次场景事件，判断最近一次用户与 AI 对话中是否有未完话题、明确提到的事情、情绪或值得自然承接的内容；如果有，优先延续最合适的一项。每次只选择当前最自然、最有帮助的切入点，并参考 recent_deliveries 的 content_excerpt、topic_tags、opening_pattern 避免重复近期主题和开场句式。不要自动附加吃饭、喝水、睡觉或上班提醒。可以猜测用户当前处境或下一步，但必须明确写成推测。天气是可选话题之一，不是固定必提项；只有天气信息可靠、足够新鲜且能让当前对话更有帮助时才提。天气不可用或不确定时不得猜测。"

    suspend fun ensureSeeded(db: AppDatabase) {
        val dao = db.scenarioPolicyDao()
        productPolicies().forEach { policy ->
            // Product policy IDs are owned by the app. Re-seed them so a
            // release that changes the primary channel or prompt does not
            // keep an obsolete policy row from an earlier APK.
            dao.upsert(policy)
        }
    }

    fun productPolicies(): List<ScenarioPolicyEntity> = listOf(
        scenePolicy(
            id = POLICY_ID_INTERACTION_GAP,
            eventType = SceneEventTypes.INTERACTION_GAP,
            matcher = buildJsonObject { put("type", SceneEventTypes.INTERACTION_GAP) },
            prompt = "CANDIDATE SC-01 久未互动召回：以 event.payload.effective_gap_candidate_millis 为本轮起始阈值，并结合 proactive_contact_level 调整后的本地门禁结果；目的不是报时或责怪用户，而是用低压力、容易接住的新话头挽回对话。$CONTEXT_DRIVEN_TOPIC_RULE 72 小时后结束本轮召回。只使用悬浮窗和 Todo，不使用系统通知。",
            allowedActions = normalOutreachActions,
            constraints = normalSceneConstraints(
                primaryChannel = ScenePolicyContract.PRIMARY_CHANNEL_PROACTIVE,
                cooldownGroup = SceneCooldown.GROUP_INTERACTION_GAP,
                requireTodoForProactive = true,
            ),
        ),
        scenePolicy(
            id = POLICY_ID_WEATHER_CHANGE,
            eventType = ExternalEventTypeNames.WEATHER_CHANGE,
            matcher = buildJsonObject { put("type", ExternalEventTypeNames.WEATHER_CHANGE) },
            prompt = "CANDIDATE 天气变化策略：只有显著变化才允许悬浮窗/Todo，平稳天气保持安静。官方高风险预警只是天气事实，不要求系统必达通知。",
            allowedActions = normalOutreachActions,
            constraints = normalSceneConstraints(
                primaryChannel = ScenePolicyContract.PRIMARY_CHANNEL_PROACTIVE,
                cooldownGroup = SceneCooldown.GROUP_WEATHER_CHANGE,
                requireTodoForProactive = true,
            ),
        ),
        scenePolicy(
            id = POLICY_ID_WEATHER_DAILY,
            eventType = ExternalEventTypeNames.DAILY_WEATHER_BRIEF,
            matcher = buildJsonObject { put("type", ExternalEventTypeNames.DAILY_WEATHER_BRIEF) },
            prompt = "CANDIDATE SC-05 日常天气关心：非白班日 08:00 后首次解锁才触发，只说带不带伞、穿衣和空气质量等可执行建议，不罗列完整预报；每天一次，使用悬浮窗/Todo。",
            allowedActions = normalOutreachActions,
            constraints = normalSceneConstraints(
                primaryChannel = ScenePolicyContract.PRIMARY_CHANNEL_PROACTIVE,
                cooldownGroup = SceneCooldown.GROUP_WEATHER_DAILY,
                requireTodoForProactive = true,
            ),
        ),
        scenePolicy(
            id = POLICY_ID_WEATHER_WAKE,
            eventType = ExternalEventTypeNames.WAKE_WEATHER_BRIEF,
            matcher = buildJsonObject { put("type", ExternalEventTypeNames.WAKE_WEATHER_BRIEF) },
            prompt = "CANDIDATE 起床天气关心：用户推测的起床窗口已到。读取 current_context.weather（含 forecast_hourly 逐小时预报），用伴侣的口吻像聊起来一样自然地带出未来 24 小时天气：只挑真正影响用户安排的几点（降雨时段、明显升降温、空气质量、大风），织进一两句关心里，例如顺口提醒带伞或添衣；禁止罗列逐小时数据表，禁止播报腔，禁止编造天气数字。天气快照缺失或过于陈旧时输出 NO_ACTION，不得猜测。这条消息以无声系统通知送达并写入聊天记录。",
            allowedActions = listOf(
                AgentActionRegistry.SEND_PROACTIVE_MESSAGE,
                AgentActionRegistry.UPDATE_PET_STATE,
                AgentActionRegistry.PLAY_ANIMATION,
            ),
            constraints = normalSceneConstraints(
                primaryChannel = ScenePolicyContract.PRIMARY_CHANNEL_PROACTIVE,
                cooldownGroup = SceneCooldown.GROUP_WEATHER_WAKE,
                requireTodoForProactive = false,
            ),
        ),
        scenePolicy(
            id = POLICY_ID_DISCOVERY_NUDGE,
            eventType = ExternalEventTypeNames.DISCOVERY_TOPIC_NUDGE,
            matcher = buildJsonObject { put("type", ExternalEventTypeNames.DISCOVERY_TOPIC_NUDGE) },
            prompt = "CANDIDATE 本地生活话题轻提：event.payload.items 是今天新发现的本地活动/美食条目。最多挑一条当前最值得聊的，用一句轻松自然的话稍微提起，勾起兴趣即可；禁止展开时间地点价格等细节，禁止罗列清单，禁止连续两天用同一种句式提起。同时必须创建一个 DISCUSS_LATER todo，payload 里完整保存所选条目的 item_id、title、category、source_url 和你打算聊的切入角度，用户追问时再从这里展开。条目都与用户近期话题明显无关或没有值得聊的时输出 NO_ACTION。",
            allowedActions = normalOutreachActions,
            constraints = normalSceneConstraints(
                primaryChannel = ScenePolicyContract.PRIMARY_CHANNEL_PROACTIVE,
                cooldownGroup = SceneCooldown.GROUP_DISCOVERY_NUDGE,
                requireTodoForProactive = true,
            ),
        ),
        scenePolicy(
            id = POLICY_ID_WEATHER_ALERT_HIGH,
            eventType = ExternalEventTypeNames.WEATHER_ALERT,
            matcher = buildJsonObject {
                put("type", ExternalEventTypeNames.WEATHER_ALERT)
                put(
                    "payload",
                    buildJsonObject { put("severity", "HIGH") },
                )
            },
            prompt = "天气预警事实，仅供 AI 在聊天或其他天气场景中参考；本产品不保证把高风险预警主动传达给用户。",
            allowedActions = listOf(AgentActionRegistry.UPDATE_PET_STATE),
            constraints = buildJsonObject {
                put(ScenePolicyContract.KEY_PRIMARY_CHANNEL, ScenePolicyContract.PRIMARY_CHANNEL_PROACTIVE)
                put(ScenePolicyContract.KEY_SCENE_COOLDOWN_GROUP, SceneCooldown.GROUP_WEATHER_ALERT_HIGH)
                put(ScenePolicyContract.KEY_SCENE_COOLDOWN_MILLIS, SceneCooldown.DEFAULT_SCENE_COOLDOWN_MILLIS)
                put(ScenePolicyContract.KEY_ALLOWED_TODO_TYPES, jsonArray(discussTodoTypes))
                put(ScenePolicyContract.KEY_ALLOWED_TRIGGER_TYPES, jsonArray(listOf(TriggerTypeNames.TIME)))
                put(ScenePolicyContract.KEY_MAX_OUTPUTS, 3)
            },
        ),
        scenePolicy(
            id = POLICY_ID_NEARBY,
            eventType = ExternalEventTypeNames.NEARBY_CANDIDATE,
            matcher = buildJsonObject { put("type", ExternalEventTypeNames.NEARBY_CANDIDATE) },
            prompt = "当前版本未启用附近餐饮；本地直接 NO_ACTION，不查询、不调用模型、不产生主动消息。",
            allowedActions = emptyList(),
            constraints = disabledSceneConstraints(SceneCooldown.GROUP_NEARBY),
        ),
    )

    private fun scenePolicy(
        id: String,
        eventType: String,
        matcher: JsonObject,
        prompt: String,
        allowedActions: List<String>,
        constraints: JsonObject,
    ): ScenarioPolicyEntity = ScenarioPolicyEntity(
        id = id,
        eventType = eventType,
        mode = PolicyModes.GUIDED,
        matcherJson = matcher.toString(),
        promptTemplate = prompt,
        examplesJson = backgroundDecisionExamplesJson(),
        allowedActionsJson = jsonArray(allowedActions).toString(),
        constraintsJson = constraints.toString(),
        version = 1,
    )

    private fun normalSceneConstraints(
        primaryChannel: String,
        cooldownGroup: String,
        requireTodoForProactive: Boolean = false,
    ): JsonObject = buildJsonObject {
        put(ScenePolicyContract.KEY_PRIMARY_CHANNEL, primaryChannel)
        put(ScenePolicyContract.KEY_SCENE_COOLDOWN_GROUP, cooldownGroup)
        put(ScenePolicyContract.KEY_SCENE_COOLDOWN_MILLIS, SceneCooldown.DEFAULT_SCENE_COOLDOWN_MILLIS)
        put(ScenePolicyContract.KEY_ALLOWED_TODO_TYPES, jsonArray(discussTodoTypes))
        put(ScenePolicyContract.KEY_ALLOWED_TRIGGER_TYPES, jsonArray(listOf(TriggerTypeNames.TIME)))
        put(ScenePolicyContract.KEY_MAX_OUTPUTS, 3)
        put(ScenePolicyContract.KEY_REQUIRE_TODO_FOR_PROACTIVE, requireTodoForProactive)
    }

    private fun disabledSceneConstraints(cooldownGroup: String): JsonObject = buildJsonObject {
        put("no_action", true)
        put(ScenePolicyContract.KEY_SCENE_COOLDOWN_GROUP, cooldownGroup)
        put(ScenePolicyContract.KEY_SCENE_COOLDOWN_MILLIS, SceneCooldown.DEFAULT_SCENE_COOLDOWN_MILLIS)
        put(ScenePolicyContract.KEY_MAX_OUTPUTS, 1)
        put(ScenePolicyContract.KEY_REQUIRE_TODO_FOR_PROACTIVE, false)
    }

    private fun backgroundDecisionExamplesJson(): String = buildJsonArray {
        add(buildJsonObject {
            put("decision", "NO_ACTION")
            put("decision_summary", "没有足够自然且可靠的触达理由")
            put("outputs", buildJsonArray { })
            put("todo_mutations", buildJsonArray { })
            put("trigger_mutations", buildJsonArray { })
        })
        add(buildJsonObject {
            put("decision", "CREATE_PLAN")
            put("outputs", buildJsonArray {
                add(buildJsonObject {
                    put("action", AgentActionRegistry.SEND_PROACTIVE_MESSAGE)
                    put("payload", buildJsonObject { put("content", "结合真实上下文生成的简短内容") })
                })
            })
            put("todo_mutations", buildJsonArray {
                add(buildJsonObject {
                    put("operation", "CREATE")
                    put("todo", buildJsonObject {
                        put("type", TodoTypeNames.DISCUSS_LATER)
                        put("title", "稍后自然承接")
                        put("priority", 50)
                        put("payload", buildJsonObject { put("topic", "基于当前场景的待承接内容") })
                    })
                })
            })
            put("trigger_mutations", buildJsonArray { })
        })
    }.toString()

    private fun jsonArray(values: List<String>): JsonArray = buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }
}
