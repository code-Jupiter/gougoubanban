package com.littlekitty.xiaokele.debug

import com.littlekitty.xiaokele.automation.AutomationRuleStore
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionNames
import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.AgentDecisionValidation
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.DecisionChannels
import com.littlekitty.xiaokele.core.common.scene.SceneEventTypes
import com.littlekitty.xiaokele.core.common.scene.ScenePriorities
import com.littlekitty.xiaokele.core.common.scene.SceneSources
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.animation.AnimationCatalog
import com.littlekitty.xiaokele.chat.ChatContextBuilder
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.policy.PolicyGateResult
import com.littlekitty.xiaokele.core.database.policy.PolicyResolver
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.event.BackgroundContextBuilder
import com.littlekitty.xiaokele.external.ExternalEventContract
import com.littlekitty.xiaokele.provider.AppModelRouter
import com.littlekitty.xiaokele.provider.TaskKind
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import com.littlekitty.xiaokele.scene.SceneGateResult
import com.littlekitty.xiaokele.scene.ScenePreconditionGate
import com.littlekitty.xiaokele.scene.SceneOutputPolicyEnforcer
import com.littlekitty.xiaokele.automation.AutomationRuleModes
import com.littlekitty.xiaokele.discovery.DiscoveryMentionStore
import com.littlekitty.xiaokele.floating.PendingFloatingBubbleStore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

enum class DebugModelPreviewScenario {
    INTERACTION_GAP,
    WEATHER_BRIEF,
}

data class DebugModelPreviewResult(
    val notice: String,
    val gateSummary: String,
    val decisionJson: String,
    val proactiveMessage: String?,
    val requestBody: String,
    val providerResponseBody: String,
) {
    companion object {
        fun failure(message: String): DebugModelPreviewResult = DebugModelPreviewResult(
            notice = message,
            gateSummary = "",
            decisionJson = "",
            proactiveMessage = null,
            requestBody = "",
            providerResponseBody = "",
        )
    }
}

/**
 * Debug-only dry-run of the production background model decision.
 *
 * It deliberately injects only the scenario event in memory. The context
 * builder still reads the real local conversation/profile/memory/todo history,
 * and AppModelRouter is used directly so DebugSceneModelService cannot replace
 * the configured model. No EventEntity, plan, todo, delivery, or message is
 * persisted by this class.
 */
class DebugModelPreviewService(
    private val appModelRouter: AppModelRouter,
    private val policyResolver: PolicyResolver,
    private val contextBuilder: BackgroundContextBuilder,
    private val sceneGate: ScenePreconditionGate,
    private val automationRuleStore: AutomationRuleStore,
    private val timeProvider: TimeProvider,
) {

    suspend fun preview(scenario: DebugModelPreviewScenario): DebugModelPreviewResult {
        val now = timeProvider.now()
        val event = buildEvent(scenario, now)
        val automationRule = automationRuleStore.matchingRule(event, now)
            ?: return DebugModelPreviewResult.failure(
                "真实模型预览未执行：当前自动化规则未命中，正式后台链路也不会调用模型",
            )
        val baseGate = policyResolver.gate(policyResolver.resolve(event))
        val gate = automationRuleStore.constrainGate(baseGate, automationRule)
        if (gate is PolicyGateResult.ForceNoAction) {
            return DebugModelPreviewResult(
                notice = "真实模型预览未执行：正式场景门禁判定为 NO_ACTION（不会调用模型）",
                gateSummary = "automation=${automationRule.id}; gate=NO_ACTION:${gate.reason}",
                decisionJson = JsonCodec.pretty(buildJsonObject {
                    put("decision", "NO_ACTION")
                    put("reason", gate.reason)
                }.toString()),
                proactiveMessage = null,
                requestBody = "",
                providerResponseBody = "",
            )
        }

        val sceneResult = sceneGate.evaluate(event, sceneCooldownMillisOverride = 0L)
        if (sceneResult is SceneGateResult.ForceNoAction) {
            val evaluation = sceneResult.evaluation
            return DebugModelPreviewResult(
                notice = "真实模型预览未执行：场景前置门禁判定为 NO_ACTION（不会调用模型）",
                gateSummary = "automation=${automationRule.id}; scene=${evaluation.reasonCode}",
                decisionJson = JsonCodec.pretty(buildJsonObject {
                    put("decision", "NO_ACTION")
                    put("reason", evaluation.reasonCode)
                    put("summary", evaluation.summary.orEmpty())
                }.toString()),
                proactiveMessage = null,
                requestBody = "",
                providerResponseBody = "",
            )
        }

        val evaluation = (sceneResult as? SceneGateResult.Allow)?.evaluation
        val context = contextBuilder.buildFor(event, gate, evaluation)
            .withDebugFacts(scenario, now)
        if (automationRule.mode == AutomationRuleModes.FIXED_MESSAGE) {
            val fixed = automationRule.fixedMessage.orEmpty()
            return DebugModelPreviewResult(
                notice = "当前自动化规则是 FIXED_MESSAGE，正式链路不会调用模型；以下是规则固定消息",
                gateSummary = "automation=${automationRule.id}; mode=${automationRule.mode}",
                decisionJson = JsonCodec.pretty(buildJsonObject {
                    put("decision", "CREATE_PLAN")
                    put("decision_summary", "fixed_rule:${automationRule.id}")
                    put("message", fixed)
                }.toString()),
                proactiveMessage = fixed.takeIf { it.isNotBlank() },
                requestBody = JsonCodec.pretty(JsonCodec.encode(context)),
                providerResponseBody = "",
            )
        }

        val result = appModelRouter.generate(context)
        val trace = when (result) {
            is ModelServiceResult.Success -> result.trace
            is ModelServiceResult.NoAction -> result.trace
            is ModelServiceResult.Failure -> result.trace
        }
        val requestRaw = trace?.requestBody ?: JsonCodec.encode(context)
        val responseRaw = trace?.rawResponseBody ?: when (result) {
            is ModelServiceResult.Success -> result.rawDecisionJson
            is ModelServiceResult.NoAction -> buildJsonObject {
                put("outcome", "NO_ACTION")
                put("reason", result.reason)
            }.toString()
            is ModelServiceResult.Failure -> buildJsonObject {
                put("error", result.error.code.name)
                result.error.technicalDetail?.let { put("technical_detail", it) }
            }.toString()
        }
        return when (result) {
            is ModelServiceResult.Success -> {
                val decision = runCatching { JsonCodec.decode<AgentDecision>(result.rawDecisionJson) }.getOrNull()
                val message = decision?.outputs
                    ?.firstOrNull { it.action == AgentActionNames.SEND_PROACTIVE_MESSAGE }
                    ?.payload
                    ?.get("content")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                DebugModelPreviewResult(
                    notice = "真实模型预览完成：没有写入正式业务数据",
                    gateSummary = "automation=${automationRule.id}; mode=${automationRule.mode}; scene=${evaluation?.reasonCode}",
                    decisionJson = JsonCodec.pretty(result.rawDecisionJson),
                    proactiveMessage = message,
                    requestBody = JsonCodec.pretty(requestRaw),
                    providerResponseBody = JsonCodec.pretty(responseRaw),
                )
            }

            is ModelServiceResult.Failure -> DebugModelPreviewResult(
                notice = "真实模型预览失败：${result.error.safeUserMessage()}",
                gateSummary = "automation=${automationRule.id}; mode=${automationRule.mode}; scene=${evaluation?.reasonCode}",
                decisionJson = "",
                proactiveMessage = null,
                requestBody = JsonCodec.pretty(requestRaw),
                providerResponseBody = JsonCodec.pretty(responseRaw),
            )

            is ModelServiceResult.NoAction -> DebugModelPreviewResult(
                notice = "真实模型判断为 NO_ACTION：没有写入正式业务数据",
                gateSummary = "automation=${automationRule.id}; mode=${automationRule.mode}; scene=${evaluation?.reasonCode}",
                decisionJson = JsonCodec.pretty(responseRaw),
                proactiveMessage = null,
                requestBody = JsonCodec.pretty(requestRaw),
                providerResponseBody = JsonCodec.pretty(responseRaw),
            )
        }
    }

    private fun buildEvent(
        scenario: DebugModelPreviewScenario,
        now: Instant,
    ): EventEntity {
        val id = EntityId.generate("debug_preview").value
        val payload = when (scenario) {
            DebugModelPreviewScenario.INTERACTION_GAP -> buildJsonObject {
                put("debug_preview", true)
                put("debug_last_interaction_at_epoch_millis", now.toEpochMilli() - 25L * 60 * 60 * 1000)
                put("gap_millis", 25L * 60 * 60 * 1000)
            }

            DebugModelPreviewScenario.WEATHER_BRIEF -> buildJsonObject {
                put("debug_preview", true)
            }
        }
        val type = when (scenario) {
            DebugModelPreviewScenario.INTERACTION_GAP -> SceneEventTypes.INTERACTION_GAP
            DebugModelPreviewScenario.WEATHER_BRIEF -> com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames.WAKE_WEATHER_BRIEF
        }
        return EventEntity(
            id = id,
            eventKey = "debug:model_preview:${scenario.name.lowercase()}:$id",
            type = type,
            source = SceneSources.DEBUG_SCENE_SCRIPT,
            payloadJson = payload.toString(),
            occurredAt = now.toEpochMilli(),
            receivedAt = now.toEpochMilli(),
            expiresAt = now.plusSeconds(30 * 60).toEpochMilli(),
            priority = when (scenario) {
                DebugModelPreviewScenario.INTERACTION_GAP -> ScenePriorities.INTERACTION_GAP
                DebugModelPreviewScenario.WEATHER_BRIEF -> ScenePriorities.LOCAL_CANDIDATE_DEFAULT
            },
            status = EventStatuses.RECEIVED,
        )
    }

    private fun ContextPack.withDebugFacts(
        scenario: DebugModelPreviewScenario,
        now: Instant,
    ): ContextPack {
        val current = when (scenario) {
            DebugModelPreviewScenario.INTERACTION_GAP -> currentContext.copy(
                localTime = InstantJson.formatInZone(now),
                lastInteractionAt = InstantJson.formatInZone(now.minusSeconds(25L * 60 * 60)),
            )

            DebugModelPreviewScenario.WEATHER_BRIEF -> currentContext.copy(
                localTime = InstantJson.formatInZone(now),
            )
        }
        return copy(currentContext = current)
    }
}

enum class DebugCheckStatus { PASS, WARNING, FAIL }

data class DebugValidationCheck(
    val name: String,
    val status: DebugCheckStatus,
    val detail: String,
)

data class DebugPreparedRequest(
    val scenario: DebugAcceptanceScenario,
    val context: ContextPack,
    val gate: PolicyGateResult.AllowAgent,
    val requestBody: String,
    val generatedAt: String,
    val sources: List<String>,
    val frontChat: Boolean,
)

data class DebugRealAiResult(
    val prepared: DebugPreparedRequest,
    val submittedBody: String,
    val requestWasEdited: Boolean,
    val rawResponseBody: String,
    val decisionJson: String,
    val checks: List<DebugValidationCheck>,
    val content: String?,
    val animationId: String?,
    val transportEvidence: String,
    val errorMessage: String? = null,
) {
    val canPreview: Boolean
        get() = errorMessage == null && checks.none { it.status == DebugCheckStatus.FAIL } && !content.isNullOrBlank()
}

private data class DebugBusinessSideEffectSnapshot(
    val messages: Int,
    val unread: Int,
    val proactiveBudgetRows: Int,
    val events: Int,
    val todos: Int,
    val plans: Int,
    val deliveries: Int,
    val discoveryMentionsHash: String,
    val pendingBubble: Boolean,
)

/**
 * Two-stage Debug acceptance coordinator. Preparation is deliberately
 * network-free and side-effect-free; submission sends the exact reviewed JSON
 * through AppModelRouter and only keeps diagnostics/preview state in memory.
 */
class DebugRealAiAcceptanceService(
    private val db: AppDatabase,
    private val appContext: android.content.Context,
    private val appModelRouter: AppModelRouter,
    private val policyResolver: PolicyResolver,
    private val backgroundContextBuilder: BackgroundContextBuilder,
    private val chatContextBuilder: ChatContextBuilder,
    private val sceneGate: ScenePreconditionGate,
    private val automationRuleStore: com.littlekitty.xiaokele.automation.AutomationRuleStore,
    private val timeProvider: TimeProvider,
    private val byokSettingsStore: com.littlekitty.xiaokele.settings.ByokSettingsStore,
) {
    suspend fun prepareScenario(scenario: DebugAcceptanceScenario): Result<DebugPreparedRequest> {
        val now = timeProvider.now()
        val frontChat = scenario == DebugAcceptanceScenario.FRONT_CHAT
        val event = eventFor(scenario, now)
        val baseGate = if (frontChat) {
            null
        } else {
            val automation = automationRuleStore.matchingRule(event, now)
            if (automation != null && automation.mode == AutomationRuleModes.FIXED_MESSAGE) {
                return Result.failure(IllegalStateException("当前自动化规则为 FIXED_MESSAGE，真实 AI 验收拒绝固定消息路径"))
            }
            val resolved = policyResolver.resolve(event)
            val gate = automation?.let { automationRuleStore.constrainGate(policyResolver.gate(resolved), it) }
                ?: policyResolver.gate(resolved)
            gate
        }

        val context = if (frontChat) {
            chatContextBuilder.buildFor(
                MessageEntity(
                    id = "debug_preview_user_${now.toEpochMilli()}",
                    role = "USER",
                    content = "请基于当前真实上下文自然回应我。",
                    createdAt = now.toEpochMilli(),
                ),
                allowNetworkMemorySearch = false,
            )
        } else {
            val gate = baseGate ?: error("background gate missing")
            if (gate is PolicyGateResult.ForceNoAction) {
                return Result.failure(IllegalStateException("策略门禁 NO_ACTION：${gate.reason}"))
            }
            when (val scene = sceneGate.evaluate(event, sceneCooldownMillisOverride = 0L)) {
                is SceneGateResult.ForceNoAction -> return Result.failure(
                    IllegalStateException("场景门禁 NO_ACTION：${scene.evaluation.reasonCode} ${scene.evaluation.summary.orEmpty()}"),
                )
                is SceneGateResult.Allow -> backgroundContextBuilder.buildFor(event, gate, scene.evaluation, allowNetworkMemorySearch = false)
            }
        }
        val body = if (frontChat) {
            appModelRouter.buildPreparedFrontChatRequestBody(context)
        } else {
            appModelRouter.buildPreparedBackgroundRequestBody(context)
        }
        val sources = buildSources(context, frontChat)
        return Result.success(
            DebugPreparedRequest(
                scenario = scenario,
                context = context,
                gate = if (frontChat) {
                    PolicyGateResult.AllowAgent(
                        availableActions = AgentActionRegistry.frontChatAllowed,
                        policyJson = buildJsonObject { put("mode", "FRONT_CHAT_PREVIEW") },
                    )
                } else baseGate as PolicyGateResult.AllowAgent,
                requestBody = pretty(body),
                generatedAt = InstantJson.formatInZone(now),
                sources = sources,
                frontChat = frontChat,
            ),
        )
    }

    suspend fun submitPreparedRequest(
        prepared: DebugPreparedRequest,
        editedBody: String,
    ): DebugRealAiResult {
        val beforeSideEffects = businessSideEffectSnapshot()
        val requestErrors = DebugRequestBodyValidator.validate(editedBody, expectedModel(prepared.frontChat))
        if (requestErrors.isNotEmpty()) {
            return failureResult(prepared, editedBody, requestErrors.joinToString(";"))
        }
        val result = if (prepared.frontChat) {
            appModelRouter.generatePreparedFrontChat(prepared.context, editedBody)
        } else {
            appModelRouter.generatePreparedBackground(prepared.context, editedBody)
        }
        val trace = when (result) {
            is ModelServiceResult.Success -> result.trace
            is ModelServiceResult.NoAction -> result.trace
            is ModelServiceResult.Failure -> result.trace
        }
        val rawResponse = trace?.rawResponseBody.orEmpty()
        val evidence = if (trace?.responseStatusCode != null) {
            "已发起真实 HTTP：${trace.serviceName} · HTTP ${trace.responseStatusCode} · " +
                "耗时 ${trace.latencyMillis ?: "?"}ms · request_sha256=${sha256(editedBody).take(16)}…"
        } else {
            "未取得真实 HTTP 成功证据；service=${trace?.serviceName ?: "none"}"
        }
        val sideEffectCheck = businessSideEffectCheck(beforeSideEffects)
        if (result is ModelServiceResult.Failure) {
            return failureResult(
                prepared,
                editedBody,
                "真实模型请求失败：${result.error.code.name}（${result.error.safeUserMessage()}）",
                rawResponse,
                evidence,
                sideEffectCheck,
            )
        }
        val success = result as ModelServiceResult.Success
        val checks = mutableListOf(
            DebugValidationCheck("HTTP/传输成功", if (trace?.responseStatusCode in 200..299) DebugCheckStatus.PASS else DebugCheckStatus.FAIL, evidence),
            DebugValidationCheck("响应 JSON 可解析", if (rawResponse.isNotBlank() && runCatching { com.littlekitty.xiaokele.core.common.json.JsonCodec.json.parseToJsonElement(rawResponse) }.isSuccess) DebugCheckStatus.PASS else DebugCheckStatus.FAIL, "应用已收到响应正文"),
        )
        val validation = com.littlekitty.xiaokele.core.common.protocol.AgentDecisionValidator.validate(
            rawDecisionJson = success.rawDecisionJson,
            allowedAnimationIds = AnimationCatalog.knownIds,
            channel = if (prepared.frontChat) DecisionChannels.FRONT_CHAT else DecisionChannels.BACKGROUND,
            nowEpochMillis = timeProvider.now().toEpochMilli(),
            currentEventId = prepared.context.event?.get("id")?.jsonPrimitive?.contentOrNull,
        )
        val decision = (validation as? AgentDecisionValidation.Valid)?.decision
        checks += DebugValidationCheck(
            "AgentDecision schema 合法",
            if (validation is AgentDecisionValidation.Valid) DebugCheckStatus.PASS else DebugCheckStatus.FAIL,
            if (validation is AgentDecisionValidation.Valid) "生产校验器通过" else (validation as AgentDecisionValidation.Invalid).violations.joinToString { it.message },
        )
        if (decision != null && !prepared.frontChat) {
            val violations = SceneOutputPolicyEnforcer.validate(decision, prepared.gate)
            checks += DebugValidationCheck("后台策略校验", if (violations.isEmpty()) DebugCheckStatus.PASS else DebugCheckStatus.FAIL, violations.joinToString { it.message }.ifBlank { "生产输出策略通过" })
        }
        val content = decision?.outputs?.firstOrNull { it.action == if (prepared.frontChat) AgentActionRegistry.REPLY_CHAT else AgentActionRegistry.SEND_PROACTIVE_MESSAGE }
            ?.payload?.get("content")?.jsonPrimitive?.contentOrNull
        val requestedAnimation = decision?.outputs?.firstOrNull()?.payload?.get("animation_id")?.jsonPrimitive?.contentOrNull
            ?: decision?.animationId
        val normalizedAnimation = (validation as? AgentDecisionValidation.Valid)?.normalizedAnimationId
        val animation = normalizedAnimation ?: AnimationCatalog.ID_DEFAULT_IDLE
        checks += DebugValidationCheck(
            "animation ID 合法",
            when {
                validation !is AgentDecisionValidation.Valid -> DebugCheckStatus.FAIL
                requestedAnimation.isNullOrBlank() -> DebugCheckStatus.WARNING
                requestedAnimation == normalizedAnimation -> DebugCheckStatus.PASS
                else -> DebugCheckStatus.WARNING
            },
            if (requestedAnimation == normalizedAnimation) "使用模型返回的合法 animation_id"
            else "模型返回=${requestedAnimation ?: "空"}；使用合法降级 ${AnimationCatalog.ID_DEFAULT_IDLE}",
        )
        checks += contentChecks(prepared, decision, content)
        checks += sideEffectCheck
        val finalError = checks.firstOrNull { it.status == DebugCheckStatus.FAIL }?.detail
        return DebugRealAiResult(
            prepared = prepared,
            submittedBody = editedBody,
            requestWasEdited = editedBody != prepared.requestBody,
            rawResponseBody = rawResponse,
            decisionJson = success.rawDecisionJson,
            checks = checks,
            content = content,
            animationId = animation,
            transportEvidence = evidence,
            errorMessage = finalError,
        )
    }

    /** Model identity used by both the editor and the submit-time validator. */
    fun expectedModel(frontChat: Boolean): String = byokSettingsStore.agentModelRoute(
        if (frontChat) TaskKind.B2_EXPRESSION else TaskKind.B1_DECISION,
    ).defaultModelId

    private fun eventFor(scenario: DebugAcceptanceScenario, now: Instant): EventEntity {
        val type = when (scenario) {
            DebugAcceptanceScenario.INTERACTION_GAP, DebugAcceptanceScenario.LIVE_INTERACTION_GAP -> SceneEventTypes.INTERACTION_GAP
            DebugAcceptanceScenario.WAKE_WEATHER -> com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames.WAKE_WEATHER_BRIEF
            DebugAcceptanceScenario.DISCOVERY_NUDGE -> com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames.DISCOVERY_TOPIC_NUDGE
            DebugAcceptanceScenario.FRONT_CHAT -> "USER_MESSAGE"
            DebugAcceptanceScenario.INVALID_BACKGROUND_OUTPUT -> SceneEventTypes.INTERACTION_GAP
            else -> SceneEventTypes.INTERACTION_GAP
        }
        val payload = buildJsonObject {
            put("debug_preview", true)
            when (scenario) {
                DebugAcceptanceScenario.INTERACTION_GAP, DebugAcceptanceScenario.LIVE_INTERACTION_GAP, DebugAcceptanceScenario.INVALID_BACKGROUND_OUTPUT -> {
                    put("debug_last_interaction_at_epoch_millis", now.toEpochMilli() - 25L * 60 * 60 * 1000)
                    put("gap_millis", 25L * 60 * 60 * 1000)
                }
                else -> Unit
            }
        }
        return EventEntity(
            id = "debug_preview_${now.toEpochMilli()}",
            eventKey = "debug_preview:${scenario.name}:${now.toEpochMilli()}",
            type = type,
            source = SceneSources.DEBUG_SCENE_SCRIPT,
            payloadJson = payload.toString(),
            occurredAt = now.toEpochMilli(),
            receivedAt = now.toEpochMilli(),
            expiresAt = now.plusSeconds(30 * 60).toEpochMilli(),
            priority = ScenePriorities.LOCAL_CANDIDATE_DEFAULT,
            status = EventStatuses.RECEIVED,
        )
    }

    private fun buildSources(context: ContextPack, frontChat: Boolean): List<String> = buildList {
        add("DEBUG_SCENARIO_TRIGGER")
        add("REAL_MODEL_PENDING_SUBMIT")
        if (context.currentContext.weather != null) add("REAL_WEATHER_API") else add("MISSING_REAL_WEATHER")
        if (context.localDiscoveries.isNotEmpty()) add("REAL_DISCOVERY_API") else add("MISSING_REAL_DISCOVERY")
        if (frontChat) add("REAL_LOCAL_CONTEXT")
    }

    private fun contentChecks(prepared: DebugPreparedRequest, decision: AgentDecision?, content: String?): List<DebugValidationCheck> {
        val checks = mutableListOf<DebugValidationCheck>()
        val text = content.orEmpty()
        val forbidden = listOf("DEBUG_FAKE", "example.com", "示例网址", "固定测试话术")
        checks += DebugValidationCheck("禁止 Fake/示例泄漏", if (forbidden.none { text.contains(it, ignoreCase = true) }) DebugCheckStatus.PASS else DebugCheckStatus.FAIL, "用户可见内容未包含禁止词")
        if (prepared.scenario == DebugAcceptanceScenario.WAKE_WEATHER) {
            checks += DebugValidationCheck("起床天气无普通气泡语义", if (decision?.outputs?.any { it.action == AgentActionRegistry.SEND_NOTIFICATION } == true) DebugCheckStatus.FAIL else DebugCheckStatus.PASS, "沿用起床天气无声通知策略")
            if (prepared.context.currentContext.weather == null) {
                val inventedWeather = text.contains(Regex("\\d+°|下雨|晴天|多云|降温|升温|空气质量"))
                checks += DebugValidationCheck(
                    "天气事实来自真实输入",
                    if (inventedWeather) DebugCheckStatus.FAIL else DebugCheckStatus.WARNING,
                    "真实天气输入缺失；未允许模型补造天气事实",
                )
            } else {
                checks += DebugValidationCheck("天气事实来自真实输入", DebugCheckStatus.PASS, "已提供真实天气上下文，需结合响应正文人工复核")
            }
        }
        if (prepared.scenario == DebugAcceptanceScenario.DISCOVERY_NUDGE) {
            val todoItems = decision?.todoMutations.orEmpty().mapNotNull { it.todo["payload"]?.jsonObject?.get("item_id")?.jsonPrimitive?.contentOrNull }
            val candidateIds = prepared.context.localDiscoveries.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.toSet()
            val valid = todoItems.size <= 1 && todoItems.all { it in candidateIds }
            checks += DebugValidationCheck(
                "本地生活 item_id 来源关联",
                when {
                    !valid -> DebugCheckStatus.FAIL
                    candidateIds.isEmpty() -> DebugCheckStatus.WARNING
                    else -> DebugCheckStatus.PASS
                },
                if (candidateIds.isEmpty()) "本次没有真实候选，不能证明引用来源" else "候选=${candidateIds.size} 条",
            )
        }
        return checks
    }

    private suspend fun businessSideEffectSnapshot(): DebugBusinessSideEffectSnapshot = DebugBusinessSideEffectSnapshot(
        messages = db.messageDao().count(),
        unread = db.messageDao().countUnreadProactive(),
        proactiveBudgetRows = db.messageDao().countProactiveAssistantBetween(Long.MIN_VALUE, Long.MAX_VALUE),
        events = db.eventDao().count(),
        todos = db.todoDao().count(),
        plans = db.actionPlanDao().count(),
        deliveries = db.deliveryHistoryDao().count(),
        discoveryMentionsHash = DiscoveryMentionStore(appContext).diagnosticSnapshotHash(),
        pendingBubble = PendingFloatingBubbleStore(appContext).hasValidPending(),
    )

    private suspend fun businessSideEffectCheck(before: DebugBusinessSideEffectSnapshot): DebugValidationCheck {
        val after = businessSideEffectSnapshot()
        return DebugValidationCheck(
            "Debug dry-run 业务副作用隔离",
            if (before == after) DebugCheckStatus.PASS else DebugCheckStatus.FAIL,
            if (before == after) "messages/未读/主动预算/事件/todo/plan/delivery/已提登记/pending 均未变化"
            else "检测到业务状态变化，拒绝将本次结果视为 Debug dry-run",
        )
    }

    private fun failureResult(
        prepared: DebugPreparedRequest,
        body: String,
        message: String,
        response: String = "",
        evidence: String = "未发起请求",
        extraCheck: DebugValidationCheck? = null,
    ) = DebugRealAiResult(
        prepared = prepared,
        submittedBody = body,
        requestWasEdited = body != prepared.requestBody,
        rawResponseBody = response,
        decisionJson = "",
        checks = listOfNotNull(DebugValidationCheck("提交", DebugCheckStatus.FAIL, message), extraCheck),
        content = null,
        animationId = null,
        transportEvidence = evidence,
        errorMessage = message,
    )

    private fun pretty(raw: String): String = runCatching {
        com.littlekitty.xiaokele.core.common.json.JsonCodec.pretty(raw)
    }.getOrDefault(raw)

    private fun sha256(raw: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
}

object DebugRequestBodyValidator {
    private val sensitive = setOf("authorization", "api_key", "apikey", "access_token", "secret", "password", "x-api-key", "headers", "endpoint")

    fun validate(raw: String, expectedModel: String): List<String> {
        val root = runCatching { com.littlekitty.xiaokele.core.common.json.JsonCodec.json.parseToJsonElement(raw).jsonObject }.getOrElse { return listOf("JSON 格式无效") }
        val errors = mutableListOf<String>()
        if ((root["model"]?.jsonPrimitive?.contentOrNull ?: "") != expectedModel) errors += "model 必须等于当前配置模型"
        if (root["messages"]?.jsonArray.isNullOrEmpty()) errors += "messages 不能为空"
        if (raw.length > 120_000) errors += "请求正文超过 120KB"
        walk(root, "", errors)
        return errors.distinct()
    }

    private fun walk(element: kotlinx.serialization.json.JsonElement, path: String, errors: MutableList<String>) {
        when (element) {
            is kotlinx.serialization.json.JsonObject -> element.forEach { (key, value) ->
                if (key.lowercase() in sensitive) errors += "禁止敏感字段：${if (path.isBlank()) key else "$path.$key"}"
                walk(value, if (path.isBlank()) key else "$path.$key", errors)
            }
            is kotlinx.serialization.json.JsonArray -> element.forEachIndexed { index, value -> walk(value, "$path[$index]", errors) }
            else -> Unit
        }
    }
}
