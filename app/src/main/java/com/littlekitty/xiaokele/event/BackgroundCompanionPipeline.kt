package com.littlekitty.xiaokele.event

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.littlekitty.xiaokele.animation.AnimationAdapter
import com.littlekitty.xiaokele.automation.AutomationEventTypes
import com.littlekitty.xiaokele.automation.AutomationRule
import com.littlekitty.xiaokele.automation.AutomationRuleModes
import com.littlekitty.xiaokele.automation.AutomationRuleStore
import com.littlekitty.xiaokele.channel.ChannelExecutionCoordinator
import com.littlekitty.xiaokele.channel.ChannelPlanReport
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.AgentOutput
import com.littlekitty.xiaokele.core.common.protocol.AgentDecisionValidation
import com.littlekitty.xiaokele.core.common.protocol.AgentDecisionValidator
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.DecisionChannels
import com.littlekitty.xiaokele.core.common.protocol.DecisionNames
import com.littlekitty.xiaokele.core.common.protocol.MutationOperationNames
import com.littlekitty.xiaokele.core.common.protocol.TodoMutation
import com.littlekitty.xiaokele.core.common.protocol.TriggerMutationValidation
import com.littlekitty.xiaokele.core.common.protocol.TriggerMutationValidator
import com.littlekitty.xiaokele.core.common.protocol.TodoMutationValidation
import com.littlekitty.xiaokele.core.common.protocol.TodoMutationValidator
import com.littlekitty.xiaokele.core.common.protocol.TodoTypeNames
import com.littlekitty.xiaokele.core.common.scene.ScenePolicyContract
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.ActionExecutionEntity
import com.littlekitty.xiaokele.core.database.entity.ActionPlanEntity
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogCategories
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogNames
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.policy.PolicyGateResult
import com.littlekitty.xiaokele.core.database.policy.PolicyResolver
import com.littlekitty.xiaokele.core.database.repository.BackgroundMutationExecutor
import com.littlekitty.xiaokele.core.database.repository.MutationExecutionException
import com.littlekitty.xiaokele.core.database.repository.MutationExecutionReport
import com.littlekitty.xiaokele.core.database.state.ActionExecutionStatuses
import com.littlekitty.xiaokele.core.database.state.ActionPlanStateMachine
import com.littlekitty.xiaokele.core.database.state.ActionPlanStatuses
import com.littlekitty.xiaokele.core.database.state.EventStateMachine
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.network.model.ModelService
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import com.littlekitty.xiaokele.core.network.model.RoundDiagnosticsOwner
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import com.littlekitty.xiaokele.provider.ReplyCoordinator
import com.littlekitty.xiaokele.provider.ReplyEntryPoint
import com.littlekitty.xiaokele.scene.SceneGateResult
import com.littlekitty.xiaokele.scene.SceneOutputPolicyEnforcer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.Instant

sealed interface BackgroundProcessingResult {
    data class NoActionAudited(
        val eventId: String,
        val planId: String,
        val reason: String,
    ) : BackgroundProcessingResult

    data class PlanExecuted(
        val eventId: String,
        val planId: String,
        val mutationReport: MutationExecutionReport,
        val channelReport: ChannelPlanReport,
    ) : BackgroundProcessingResult

    data class AlreadyTerminal(
        val eventId: String,
        val status: String,
    ) : BackgroundProcessingResult

    data class Expired(
        val eventId: String,
    ) : BackgroundProcessingResult

    data class RetryScheduled(
        val eventId: String,
        val errorCode: String,
        val nextRetryAt: Long,
        val attemptCount: Int,
        val technicalDetail: String? = null,
    ) : BackgroundProcessingResult

    data class ActivePlanConflict(
        val eventId: String,
    ) : BackgroundProcessingResult

    data class Failed(
        val eventId: String,
        val errorCode: String,
        val technicalDetail: String? = null,
    ) : BackgroundProcessingResult

    data class UnknownEvent(
        val eventId: String,
    ) : BackgroundProcessingResult
}

/**
 * Phase 5 background Companion Agent pipeline.
 *
 * Model validation and todo/trigger mutation validation happen first. The
 * ActionPlan + PENDING executions + safe mutations are committed in ONE Room
 * transaction. Only after that transaction commits does
 * [ChannelExecutionCoordinator] call notification/widget/system adapters, so no
 * system side effect can be rolled back with chat or PetState.
 */
class BackgroundCompanionPipeline(
    private val db: AppDatabase,
    private val modelService: ModelService,
    private val policyResolver: PolicyResolver,
    private val contextBuilder: BackgroundContextBuilder,
    private val mutationExecutor: BackgroundMutationExecutor,
    private val animationAdapter: AnimationAdapter,
    private val timeProvider: TimeProvider,
    private val channelCoordinator: ChannelExecutionCoordinator,
    private val sceneGate: com.littlekitty.xiaokele.scene.ScenePreconditionGate? = null,
    private val triggerWakeReconcile: suspend () -> Unit = {},
    private val diagnosticsLogger: DiagnosticsLogger? = null,
    private val automationRuleStore: AutomationRuleStore? = null,
    /** Production migration switch; tests and explicit legacy callers can opt out. */
    private val replyCoordinator: ReplyCoordinator? = null,
    private val useProfiledReplyCoordinator: Boolean = false,
) {

    private val mutex = Mutex()

    suspend fun process(eventId: String): BackgroundProcessingResult = mutex.withLock {
        val now = timeProvider.now().toEpochMilli()
        val event = db.eventDao().byId(eventId) ?: return@withLock BackgroundProcessingResult.UnknownEvent(eventId)

        diagnosticsLogger?.record(
            category = DiagnosticLogCategories.SCENE,
            name = DiagnosticLogNames.SCENE_GATE,
            status = DiagnosticLogStatuses.STARTED,
            subjectId = event.id,
            summary = "后台事件进入场景门禁与决策链路",
            payloadJson = stagePayload(event.id, "PIPELINE_STARTED") {
                put("event_type", event.type)
                put("event_source", event.source)
                put("event_key", event.eventKey)
                put("event_status", event.status)
                put("attempt_count", event.attemptCount)
                put("raw_event_payload", event.payloadJson)
            },
        )

        if (event.status in EventStatuses.terminal) {
            return@withLock BackgroundProcessingResult.AlreadyTerminal(eventId, event.status)
        }

        val eventExpiresAt = event.expiresAt
        if (eventExpiresAt != null && eventExpiresAt <= now) {
            expireEvent(event)
            return@withLock BackgroundProcessingResult.Expired(eventId)
        }

        if (event.status == EventStatuses.RECEIVED) {
            transitionEvent(event, EventStatuses.PROCESSING)
        }

        val automationRule = if (event.type in AutomationEventTypes.supported) {
            automationRuleStore?.matchingRule(event, Instant.ofEpochMilli(now))
        } else {
            null
        }
        if (automationRuleStore != null && event.type in AutomationEventTypes.supported) {
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.SCENE,
                name = DiagnosticLogNames.POLICY_GATE,
                status = if (automationRule == null) DiagnosticLogStatuses.NO_ACTION else DiagnosticLogStatuses.STARTED,
                subjectId = event.id,
                summary = automationRule?.let { "自动化规则命中：${it.id}" } ?: "自动化规则未命中",
                payloadJson = stagePayload(event.id, "AUTOMATION_RULE_MATCHED") {
                    put("event_type", event.type)
                    automationRule?.let {
                        put("automation_rule_id", it.id)
                        put("automation_rule_name", it.name)
                        put("automation_rule_mode", it.mode)
                    }
                },
            )
        }
        if (automationRuleStore != null && event.type in AutomationEventTypes.supported && automationRule == null) {
            return@withLock auditNoAction(event, "automation_rule_not_matched", now)
        }

        val sceneResult = sceneGate?.evaluate(
            event,
            automationRule?.cooldownMinutes?.times(60_000L),
        )
        if (sceneResult is SceneGateResult.ForceNoAction) {
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.SCENE,
                name = DiagnosticLogNames.SCENE_GATE,
                status = DiagnosticLogStatuses.NO_ACTION,
                subjectId = event.id,
                summary = "场景前置门禁阻断：${sceneResult.evaluation.reasonCode}",
                payloadJson = stagePayload(event.id, "SCENE_GATE_NO_ACTION") {
                    put("evaluation", sceneResult.evaluation.toContextJson())
                },
            )
            return@withLock auditNoAction(
                event,
                sceneResult.evaluation.reasonCode,
                now,
            )
        }

        (sceneResult as? SceneGateResult.Allow)?.let { allowed ->
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.SCENE,
                name = DiagnosticLogNames.SCENE_GATE,
                status = DiagnosticLogStatuses.SUCCESS,
                subjectId = event.id,
                summary = "场景前置门禁允许继续：${allowed.evaluation.reasonCode}",
                payloadJson = stagePayload(event.id, "SCENE_GATE_ALLOW") {
                    put("evaluation", allowed.evaluation.toContextJson())
                },
            )
        }

        val resolution = policyResolver.resolve(event)
        val baseGate = policyResolver.gate(resolution)
        val gate = automationRuleStore?.let { store ->
            automationRule?.let { store.constrainGate(baseGate, it) } ?: baseGate
        } ?: baseGate
        diagnosticsLogger?.record(
            category = DiagnosticLogCategories.SCENE,
            name = DiagnosticLogNames.POLICY_GATE,
            status = if (gate is PolicyGateResult.ForceNoAction) {
                DiagnosticLogStatuses.NO_ACTION
            } else {
                DiagnosticLogStatuses.SUCCESS
            },
            subjectId = event.id,
            summary = "场景策略门禁：${gate.javaClass.simpleName}",
            payloadJson = stagePayload(event.id, "POLICY_GATE_EVALUATED") {
                put("resolution", resolution.toString().take(4_000))
                put("gate", gate.toString().take(4_000))
                when (gate) {
                    is PolicyGateResult.AllowAgent -> {
                        put("available_actions", gate.availableActions.sorted().joinToString(","))
                        put("policy", gate.policyJson)
                        gate.maxOutputs?.let { put("max_outputs", it) }
                    }
                    is PolicyGateResult.ForceNoAction -> {
                        put("reason", gate.reason)
                        put("policy", gate.policyJson)
                    }
                }
            },
        )
        if (gate is PolicyGateResult.ForceNoAction) {
            return@withLock auditNoAction(event, gate.reason, now)
        }

        val sceneEvaluation = (sceneResult as? SceneGateResult.Allow)?.evaluation
        val context = try {
            contextBuilder.buildFor(event, gate, sceneEvaluation)
        } catch (cancelled: CancellationException) {
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.AI,
                name = DiagnosticLogNames.CONTEXT_BUILT,
                status = DiagnosticLogStatuses.FAILED,
                subjectId = event.id,
                summary = "后台 AI 上下文构建被取消",
                payloadJson = stagePayload(event.id, "CONTEXT_BUILD_CANCELLED"),
            )
            throw cancelled
        } catch (throwable: Throwable) {
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.AI,
                name = DiagnosticLogNames.CONTEXT_BUILT,
                status = DiagnosticLogStatuses.FAILED,
                subjectId = event.id,
                summary = "后台 AI 上下文构建失败",
                payloadJson = stagePayload(event.id, "CONTEXT_BUILD_FAILED") {
                    put("error_code", BACKGROUND_LOCAL_FAILURE)
                    put("technical_detail", throwable.message ?: "context build failed")
                },
            )
            return@withLock failTerminalEvent(event, BACKGROUND_LOCAL_FAILURE, throwable.message)
        }
        diagnosticsLogger?.record(
            category = DiagnosticLogCategories.AI,
            name = DiagnosticLogNames.CONTEXT_BUILT,
            status = DiagnosticLogStatuses.SUCCESS,
            subjectId = event.id,
            summary = "后台 AI 上下文已构建",
            payloadJson = stagePayload(event.id, "CONTEXT_BUILT") {
                put("raw_context", JsonCodec.encode(context))
                put("available_actions", context.availableActions.joinToString(","))
                put("has_scene_evaluation", context.sceneEvaluation != null)
            },
        )
        val fixedMessageRule = automationRule?.takeIf { it.mode == AutomationRuleModes.FIXED_MESSAGE }
        if (fixedMessageRule != null) {
            val outputs = buildList {
                add(
                    AgentOutput(
                        action = AgentActionRegistry.SEND_PROACTIVE_MESSAGE,
                        payload = buildJsonObject { put("content", fixedMessageRule.fixedMessage.orEmpty()) },
                    ),
                )
                fixedMessageRule.animationId?.let { animationId ->
                    add(
                        AgentOutput(
                            action = AgentActionRegistry.PLAY_ANIMATION,
                            payload = buildJsonObject { put("animation_id", animationId) },
                        ),
                    )
                }
            }
            return@withLock executeValidatedPlan(
                event = event,
                rawDecisionJson = JsonCodec.encode(
                    AgentDecision(
                        decision = DecisionNames.CREATE_PLAN,
                        decisionSummary = "fixed_rule:${fixedMessageRule.id}",
                        outputs = outputs,
                    ),
                ),
                gate = gate,
                context = context,
                allowRepair = false,
            )
        }
        return@withLock when (
            val modelResult = generateWithDiagnostics(
                operation = "background_decision",
                correlationId = event.id,
                subjectId = event.id,
                context = context,
                attempt = 1,
            )
        ) {
            is ModelServiceResult.NoAction -> auditNoAction(event, modelResult.reason, now)

            is ModelServiceResult.Failure -> if (modelResult.error.code == ErrorCode.MODEL_OUTPUT_INVALID) {
                repairModelOutputFailure(
                    event = event,
                    context = context,
                    gate = gate,
                    technicalDetail = modelResult.error.technicalDetail,
                )
            } else {
                handleModelFailure(
                    event = event,
                    errorCode = modelResult.error.code.name,
                    technicalDetail = modelResult.error.technicalDetail,
                )
            }

            is ModelServiceResult.Success -> executeValidatedPlan(
                event = event,
                rawDecisionJson = modelResult.rawDecisionJson,
                gate = gate,
                context = context,
                allowRepair = true,
            )
        }
    }

    private suspend fun handleModelFailure(
        event: EventEntity,
        errorCode: String,
        technicalDetail: String?,
    ): BackgroundProcessingResult {
        // Re-read the clock after the model returned; the transaction below
        // re-validates both status and expiry before scheduling any retry.
        val failureNow = timeProvider.now().toEpochMilli()
        if (technicalDetail != null && technicalDetail in TERMINAL_BUDGET_FAILURE_DETAILS) {
            // Quota/budget denials are deliberate non-retryable outcomes. A
            // retry would attempt to spend another provider request after the
            // persistent reservation already said no (and could look like a
            // silent top-up to the user).
            return failTerminalEvent(event, technicalDetail, technicalDetail)
        }
        if ((errorCode in RETRYABLE_MODEL_ERROR_CODES).not()) {
            return failTerminalEvent(event, errorCode, technicalDetail)
        }
        val nextAttempt = event.attemptCount + 1
        if (nextAttempt >= MAX_BACKGROUND_MODEL_ATTEMPTS) {
            return failTerminalEvent(event, errorCode, technicalDetail)
        }
        val nextRetryAt = failureNow + modelRetryBackoffMillis(event.attemptCount)
        val updated = db.withTransaction {
            val current = db.eventDao().byId(event.id) ?: return@withTransaction null
            if (current.status in EventStatuses.terminal) {
                return@withTransaction current
            }
            val expiresAt = current.expiresAt
            if (expiresAt != null && expiresAt <= failureNow) {
                expireInTransaction(current)
                return@withTransaction current.copy(status = EventStatuses.EXPIRED)
            }
            check(
                db.eventDao().scheduleRetryableFailure(
                    id = current.id,
                    errorCode = errorCode,
                    errorDetail = technicalDetail?.take(MAX_TECHNICAL_DETAIL_LENGTH),
                    nextRetryAt = nextRetryAt,
                    maxAttempts = MAX_BACKGROUND_MODEL_ATTEMPTS,
                ) == 1,
            ) { "retry schedule affected 0 rows: ${current.id}" }
            db.eventDao().byId(current.id)
        } ?: return BackgroundProcessingResult.UnknownEvent(event.id)

        if (updated.status in EventStatuses.terminal) {
            return if (updated.status == EventStatuses.EXPIRED) {
                BackgroundProcessingResult.Expired(event.id)
            } else {
                BackgroundProcessingResult.AlreadyTerminal(event.id, updated.status)
            }
        }
        return BackgroundProcessingResult.RetryScheduled(
            eventId = updated.id,
            errorCode = errorCode,
            nextRetryAt = updated.nextRetryAt ?: nextRetryAt,
            attemptCount = updated.attemptCount,
            technicalDetail = technicalDetail,
        )
    }

    private suspend fun executeValidatedPlan(
        event: EventEntity,
        rawDecisionJson: String,
        gate: PolicyGateResult,
        context: ContextPack,
        allowRepair: Boolean,
    ): BackgroundProcessingResult {
        // Re-read the clock AFTER the model returned. Expiry/status is checked
        // again inside the commit transaction so a long model call cannot write
        // plan/mutations for an event that expired while it was in flight.
        val now = timeProvider.now().toEpochMilli()
        val validation = AgentDecisionValidator.validate(
            rawDecisionJson = rawDecisionJson,
            allowedAnimationIds = ALLOWED_ANIMATION_IDS,
            channel = DecisionChannels.BACKGROUND,
            nowEpochMillis = now,
            currentEventId = event.id,
        )
        if (validation is AgentDecisionValidation.Invalid) {
            val detail = validation.violations.joinToString("; ") { "${it.path}: ${it.message}" }
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.AI,
                name = DiagnosticLogNames.DECISION_VALIDATION,
                status = DiagnosticLogStatuses.FAILED,
                subjectId = event.id,
                summary = "后台 AgentDecision 校验失败，准备修复：$detail",
                payloadJson = stagePayload(event.id, "DECISION_VALIDATION_FAILED") {
                    put("raw_output", rawDecisionJson)
                    put("violations", detail)
                    put("repair_allowed", allowRepair)
                },
            )
            return repairInvalidDecision(event, context, gate, rawDecisionJson, detail, allowRepair)
        }

        var decision = (validation as AgentDecisionValidation.Valid).decision
        diagnosticsLogger?.record(
            category = DiagnosticLogCategories.AI,
            name = DiagnosticLogNames.DECISION_VALIDATION,
            status = DiagnosticLogStatuses.SUCCESS,
            subjectId = event.id,
            summary = "后台 AgentDecision 基础结构校验通过",
            payloadJson = stagePayload(event.id, "DECISION_VALIDATION_SUCCESS") {
                put("normalized_decision", JsonCodec.encode(decision))
            },
        )
        if (decision.decision == DecisionNames.NO_ACTION) {
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.AI,
                name = DiagnosticLogNames.DECISION_VALIDATION,
                status = DiagnosticLogStatuses.NO_ACTION,
                subjectId = event.id,
                summary = "AI 判断无需触达",
                payloadJson = stagePayload(event.id, "AI_NO_ACTION") {
                    put("decision", JsonCodec.encode(decision))
                    put("reason", decision.decisionSummary ?: "agent_no_action")
                },
            )
            return auditNoAction(event, decision.decisionSummary ?: "agent_no_action", now)
        }

        decision = ensureRequiredTodoForProactive(decision, event, gate)
        val effectiveDecisionJson = JsonCodec.encode(decision)

        val availableActions = (gate as? PolicyGateResult.AllowAgent)?.availableActions ?: emptySet()
        val maxOutputs = (gate as? PolicyGateResult.AllowAgent)?.maxOutputs
        val policyViolation = decision.outputs.firstOrNull { output ->
            availableActions.contains(output.action).not()
        }
        val sceneOutputViolations = SceneOutputPolicyEnforcer.validate(decision, gate)
        if (sceneOutputViolations.isNotEmpty()) {
            val detail = sceneOutputViolations.joinToString("; ") { "${it.path}: ${it.message}" }
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.AI,
                name = DiagnosticLogNames.DECISION_VALIDATION,
                status = DiagnosticLogStatuses.FAILED,
                subjectId = event.id,
                summary = "后台场景输出策略校验失败，准备修复：$detail",
                payloadJson = stagePayload(event.id, "SCENE_OUTPUT_POLICY_FAILED") {
                    put("violations", detail)
                    put("normalized_decision", effectiveDecisionJson)
                },
            )
            return repairInvalidDecision(event, context, gate, rawDecisionJson, detail, allowRepair)
        }
        if (policyViolation != null) {
            val detail = "policy_disallows_action:${policyViolation.action}, allowed=$availableActions"
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.AI,
                name = DiagnosticLogNames.DECISION_VALIDATION,
                status = DiagnosticLogStatuses.FAILED,
                subjectId = event.id,
                summary = "后台策略拒绝 AI action，准备修复：$detail",
                payloadJson = stagePayload(event.id, "POLICY_OUTPUT_REJECTED") {
                    put("violations", detail)
                    put("normalized_decision", effectiveDecisionJson)
                },
            )
            return repairInvalidDecision(event, context, gate, rawDecisionJson, detail, allowRepair)
        }
        if (maxOutputs != null && decision.outputs.size > maxOutputs) {
            val detail = "policy_max_outputs_exceeded:${decision.outputs.size}>$maxOutputs"
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.AI,
                name = DiagnosticLogNames.DECISION_VALIDATION,
                status = DiagnosticLogStatuses.FAILED,
                subjectId = event.id,
                summary = "后台输出数量超过策略上限，准备修复：$detail",
                payloadJson = stagePayload(event.id, "POLICY_OUTPUT_LIMIT_REJECTED") {
                    put("violations", detail)
                    put("normalized_decision", effectiveDecisionJson)
                },
            )
            return repairInvalidDecision(event, context, gate, rawDecisionJson, detail, allowRepair)
        }

        val todoValidation = TodoMutationValidator.validate(decision.todoMutations, event.id, now)
        if (todoValidation is TodoMutationValidation.Invalid) {
            val detail = todoValidation.violations.joinToString("; ") { "${it.path}: ${it.message}" }
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.AI,
                name = DiagnosticLogNames.DECISION_VALIDATION,
                status = DiagnosticLogStatuses.FAILED,
                subjectId = event.id,
                summary = "Todo mutation 校验失败：$detail",
                payloadJson = stagePayload(event.id, "TODO_MUTATION_INVALID") { put("violations", detail) },
            )
            return failTerminalEvent(event, MUTATION_INVALID, detail)
        }
        val triggerValidation = TriggerMutationValidator.validate(decision.triggerMutations, now)
        if (triggerValidation is TriggerMutationValidation.Invalid) {
            val detail = triggerValidation.violations.joinToString("; ") { "${it.path}: ${it.message}" }
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.AI,
                name = DiagnosticLogNames.DECISION_VALIDATION,
                status = DiagnosticLogStatuses.FAILED,
                subjectId = event.id,
                summary = "Trigger mutation 校验失败：$detail",
                payloadJson = stagePayload(event.id, "TRIGGER_MUTATION_INVALID") { put("violations", detail) },
            )
            return failTerminalEvent(event, MUTATION_INVALID, detail)
        }
        val todoCommands = (todoValidation as TodoMutationValidation.Valid).commands
        val triggerCommands = (triggerValidation as TriggerMutationValidation.Valid).commands

        val persisted = try {
            persistPlan(
                event = event,
                decision = decision,
                rawDecisionJson = effectiveDecisionJson,
                todoCommands = todoCommands,
                triggerCommands = triggerCommands,
                now = now,
            )
        } catch (mutationError: MutationExecutionException) {
            val detail = mutationError.violations.joinToString("; ") { "${it.path}: ${it.message}" }
            return failTerminalEvent(event, MUTATION_INVALID, detail)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            return failTerminalEvent(event, BACKGROUND_LOCAL_FAILURE, throwable.message)
        }
        if (persisted is PersistPlanResult.AlreadyTerminal) {
            return BackgroundProcessingResult.AlreadyTerminal(persisted.eventId, persisted.status)
        }
        if (persisted is PersistPlanResult.Expired) {
            return BackgroundProcessingResult.Expired(persisted.eventId)
        }
        if (persisted is PersistPlanResult.Conflict) {
            return BackgroundProcessingResult.ActivePlanConflict(event.id)
        }

        val plan = (persisted as PersistPlanResult.Persisted)
        diagnosticsLogger?.record(
            category = DiagnosticLogCategories.AI,
            name = DiagnosticLogNames.PLAN_PERSISTED,
            status = DiagnosticLogStatuses.SUCCESS,
            subjectId = event.id,
            summary = "后台 ActionPlan 与 Todo/Trigger mutation 已原子提交",
            payloadJson = stagePayload(event.id, "PLAN_PERSISTED") {
                put("plan_id", plan.planId)
                put("mutation_report", plan.mutationReport.toString())
                put("decision", effectiveDecisionJson)
            },
        )
        if (plan.mutationReport.triggersCreated > 0) {
            runCatching { triggerWakeReconcile() }
        }
        return try {
            val channelReport = channelCoordinator.executePlan(plan.planId)
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.DELIVERY,
                name = DiagnosticLogNames.CHANNEL_EXECUTION,
                status = DiagnosticLogStatuses.SUCCESS,
                subjectId = event.id,
                summary = "后台 ActionPlan 渠道执行完成",
                payloadJson = stagePayload(event.id, "CHANNEL_EXECUTION_COMPLETED") {
                    put("plan_id", plan.planId)
                    put("channel_report", channelReport.toString())
                },
            )
            BackgroundProcessingResult.PlanExecuted(
                eventId = event.id,
                planId = plan.planId,
                mutationReport = plan.mutationReport,
                channelReport = channelReport,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            failInterruptedPlan(plan.planId, event, throwable.message ?: "channel execution failed")
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.DELIVERY,
                name = DiagnosticLogNames.CHANNEL_EXECUTION,
                status = DiagnosticLogStatuses.FAILED,
                subjectId = event.id,
                summary = "后台 ActionPlan 渠道执行失败",
                payloadJson = stagePayload(event.id, "CHANNEL_EXECUTION_FAILED") {
                    put("plan_id", plan.planId)
                    put("technical_detail", throwable.message ?: "channel execution failed")
                },
            )
            BackgroundProcessingResult.Failed(event.id, BACKGROUND_LOCAL_FAILURE, throwable.message)
        }
    }

    /**
     * A model-format or policy violation gets one bounded repair request. The
     * previous decision and exact local validation errors are carried in the
     * context, so the model can correct REPLY_CHAT/shape mistakes without an
     * unbounded retry loop.
     */
    private suspend fun repairInvalidDecision(
        event: EventEntity,
        context: ContextPack,
        gate: PolicyGateResult,
        previousDecisionJson: String,
        validationDetail: String,
        allowRepair: Boolean,
    ): BackgroundProcessingResult {
        if (!allowRepair) {
            return failTerminalEvent(
                event,
                MODEL_OUTPUT_INVALID,
                "repair_failed:$validationDetail",
            )
        }
        val repairContext = buildRepairContext(
            context = context,
            validationDetail = validationDetail,
            previousDecisionJson = previousDecisionJson,
        )
        diagnosticsLogger?.record(
            category = DiagnosticLogCategories.AI,
            name = DiagnosticLogNames.DECISION_VALIDATION,
            status = DiagnosticLogStatuses.RETRY,
            subjectId = event.id,
            summary = "触发一次有界 AI 决策修复",
            payloadJson = stagePayload(event.id, "MODEL_REPAIR_TRIGGERED") {
                put("validation_detail", validationDetail)
                put("previous_decision", previousDecisionJson)
            },
        )
        return when (
            val repaired = generateWithDiagnostics(
                operation = "background_decision_repair",
                correlationId = event.id,
                subjectId = event.id,
                context = repairContext,
                attempt = 2,
            )
        ) {
            is ModelServiceResult.NoAction -> auditNoAction(event, repaired.reason, timeProvider.now().toEpochMilli())

            is ModelServiceResult.Failure -> handleModelFailure(
                event = event,
                errorCode = repaired.error.code.name,
                technicalDetail = repaired.error.technicalDetail,
            )

            is ModelServiceResult.Success -> executeValidatedPlan(
                event = event,
                rawDecisionJson = repaired.rawDecisionJson,
                gate = gate,
                context = repairContext,
                allowRepair = false,
            )
        }
    }

    /** Handles adapter-level extraction failures where no AgentDecision JSON
     * was available to the normal validator. This still consumes at most one
     * additional model request for the event attempt. */
    private suspend fun repairModelOutputFailure(
        event: EventEntity,
        context: ContextPack,
        gate: PolicyGateResult,
        technicalDetail: String?,
    ): BackgroundProcessingResult {
        val detail = technicalDetail ?: "model_decision_extraction_failed"
        val repairContext = buildRepairContext(
            context = context,
            validationDetail = detail,
            previousDecisionJson = null,
        )
        diagnosticsLogger?.record(
            category = DiagnosticLogCategories.AI,
            name = DiagnosticLogNames.DECISION_VALIDATION,
            status = DiagnosticLogStatuses.RETRY,
            subjectId = event.id,
            summary = "模型输出提取失败，触发一次有界修复",
            payloadJson = stagePayload(event.id, "MODEL_OUTPUT_REPAIR_TRIGGERED") {
                put("validation_detail", detail)
            },
        )
        return when (
            val repaired = generateWithDiagnostics(
                operation = "background_output_repair",
                correlationId = event.id,
                subjectId = event.id,
                context = repairContext,
                attempt = 2,
            )
        ) {
            is ModelServiceResult.NoAction -> auditNoAction(event, repaired.reason, timeProvider.now().toEpochMilli())

            is ModelServiceResult.Failure -> handleModelFailure(
                event = event,
                errorCode = repaired.error.code.name,
                technicalDetail = "repair_failed:${repaired.error.technicalDetail ?: detail}",
            )

            is ModelServiceResult.Success -> executeValidatedPlan(
                event = event,
                rawDecisionJson = repaired.rawDecisionJson,
                gate = gate,
                context = repairContext,
                allowRepair = false,
            )
        }
    }

    private fun buildRepairContext(
        context: ContextPack,
        validationDetail: String,
        previousDecisionJson: String?,
    ): ContextPack = context.copy(
        event = buildJsonObject {
            context.event?.forEach { (key, value) -> put(key, value) }
            put("model_repair", buildJsonObject {
                put("validation_errors", validationDetail.take(MAX_REPAIR_CONTEXT_LENGTH))
                previousDecisionJson?.let {
                    put("previous_decision", it.take(MAX_REPAIR_CONTEXT_LENGTH))
                }
                put("attempt", 1)
            })
        },
    )

    /**
     * Product scene policies can require a durable Todo whenever a proactive
     * message is accepted. The model still writes the copy; local code owns
     * the persistence guarantee and links the delivery to the generated Todo.
     */
    private fun ensureRequiredTodoForProactive(
        decision: AgentDecision,
        event: EventEntity,
        gate: PolicyGateResult,
    ): AgentDecision {
        val constraints = (gate as? PolicyGateResult.AllowAgent)?.policyJson
            ?.get("constraints") as? JsonObject
            ?: return decision
        val required = (constraints[ScenePolicyContract.KEY_REQUIRE_TODO_FOR_PROACTIVE] as? JsonPrimitive)
            ?.booleanOrNull == true
        if (!required) return decision

        val proactiveIndex = decision.outputs.indexOfFirst {
            it.action == AgentActionRegistry.SEND_PROACTIVE_MESSAGE
        }
        if (proactiveIndex < 0) return decision
        if (decision.todoMutations.any { it.operation == MutationOperationNames.CREATE }) return decision

        val content = (decision.outputs[proactiveIndex].payload["content"] as? JsonPrimitive)
            ?.contentOrNull
            .orEmpty()
        val todoId = EntityId.generate("todo").value
        val output = decision.outputs[proactiveIndex]
        val linkedOutput = output.copy(
            payload = buildJsonObject {
                output.payload.forEach { (key, value) -> put(key, value) }
                put("todo_id", todoId)
            },
        )
        val todo = TodoMutation(
            operation = MutationOperationNames.CREATE,
            todo = buildJsonObject {
                put("id", todoId)
                put("type", TodoTypeNames.DISCUSS_LATER)
                put("title", content.take(MAX_TODO_TITLE_LENGTH))
                put("source_event_id", event.id)
                put("priority", event.priority)
                put("payload", buildJsonObject {
                    put("scene_type", event.type)
                    put("content", content)
                })
            },
        )
        return decision.copy(
            outputs = decision.outputs.toMutableList().also { it[proactiveIndex] = linkedOutput },
            todoMutations = decision.todoMutations + todo,
        )
    }

    private suspend fun persistPlan(
        event: EventEntity,
        decision: AgentDecision,
        rawDecisionJson: String,
        todoCommands: List<com.littlekitty.xiaokele.core.common.protocol.TodoMutationCommand>,
        triggerCommands: List<com.littlekitty.xiaokele.core.common.protocol.TriggerMutationCommand>,
        now: Long,
    ): PersistPlanResult = db.withTransaction {
        val current = db.eventDao().byId(event.id) ?: error("event disappeared")
        if (current.status in EventStatuses.terminal) {
            return@withTransaction PersistPlanResult.AlreadyTerminal(current.id, current.status)
        }
        val expiresAt = current.expiresAt
        if (expiresAt != null && expiresAt <= now) {
            expireInTransaction(current)
            return@withTransaction PersistPlanResult.Expired(current.id)
        }
        if (current.status != EventStatuses.PROCESSING) {
            EventStateMachine.requireTransition(current.status, EventStatuses.PROCESSING)
            check(db.eventDao().updateStatus(current.id, EventStatuses.PROCESSING) == 1)
        }

        val planId = EntityId.generate("plan").value
        try {
            db.actionPlanDao().insert(
                ActionPlanEntity(
                    id = planId,
                    eventId = event.id,
                    agentOutputJson = rawDecisionJson,
                    decisionSummary = decision.decisionSummary,
                    status = ActionPlanStatuses.PENDING,
                    activeEventKey = event.id,
                    createdAt = now,
                ),
            )
        } catch (constraint: SQLiteConstraintException) {
            return@withTransaction PersistPlanResult.Conflict(event.id)
        }
        ActionPlanStateMachine.requireTransition(ActionPlanStatuses.PENDING, ActionPlanStatuses.RUNNING)
        check(db.actionPlanDao().updateStatusWithActiveKey(planId, ActionPlanStatuses.RUNNING) == 1)

        decision.outputs.forEach { output ->
            db.actionExecutionDao().upsert(
                ActionExecutionEntity(
                    id = EntityId.generate("exec").value,
                    actionPlanId = planId,
                    actionType = output.action,
                    payloadJson = output.payload.toString(),
                    status = ActionExecutionStatuses.PENDING,
                ),
            )
        }

        val mutationReport = mutationExecutor.applyInTransaction(
            event = current,
            todoCommands = todoCommands,
            triggerCommands = triggerCommands,
            now = now,
        )

        PersistPlanResult.Persisted(planId, mutationReport)
    }

    private suspend fun auditNoAction(event: EventEntity, reason: String, now: Long): BackgroundProcessingResult =
        try {
            db.withTransaction {
                val current = db.eventDao().byId(event.id) ?: error("event disappeared")
                if (current.status in EventStatuses.terminal) {
                    return@withTransaction BackgroundProcessingResult.AlreadyTerminal(current.id, current.status)
                }
                val expiresAt = current.expiresAt
                if (expiresAt != null && expiresAt <= now) {
                    expireInTransaction(current)
                    return@withTransaction BackgroundProcessingResult.Expired(current.id)
                }
                val planId = EntityId.generate("plan").value
                val auditJson = JsonCodec.encode(
                    AgentDecision(
                        decision = DecisionNames.NO_ACTION,
                        decisionSummary = reason,
                    ),
                )
                try {
                    db.actionPlanDao().insert(
                        ActionPlanEntity(
                            id = planId,
                            eventId = event.id,
                            agentOutputJson = auditJson,
                            decisionSummary = reason,
                            status = ActionPlanStatuses.PENDING,
                            activeEventKey = event.id,
                            createdAt = now,
                        ),
                    )
                } catch (constraint: SQLiteConstraintException) {
                    return@withTransaction BackgroundProcessingResult.ActivePlanConflict(event.id)
                }
                ActionPlanStateMachine.requireTransition(ActionPlanStatuses.PENDING, ActionPlanStatuses.RUNNING)
                check(db.actionPlanDao().updateStatusWithActiveKey(planId, ActionPlanStatuses.RUNNING) == 1)
                ActionPlanStateMachine.requireTransition(ActionPlanStatuses.RUNNING, ActionPlanStatuses.COMPLETED)
                check(db.actionPlanDao().updateStatusWithActiveKey(planId, ActionPlanStatuses.COMPLETED) == 1)

                EventStateMachine.requireTransition(current.status, EventStatuses.FILTERED)
                check(db.eventDao().updateStatus(event.id, EventStatuses.FILTERED) == 1)
                BackgroundProcessingResult.NoActionAudited(event.id, planId, reason)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            failTerminalEvent(event, BACKGROUND_LOCAL_FAILURE, throwable.message)
        }

    private suspend fun transitionEvent(event: EventEntity, newStatus: String) {
        db.withTransaction {
            val current = db.eventDao().byId(event.id) ?: error("event disappeared")
            EventStateMachine.requireTransition(current.status, newStatus)
            check(db.eventDao().updateStatus(event.id, newStatus) == 1)
        }
    }

    private suspend fun expireEvent(event: EventEntity) {
        val current = db.eventDao().byId(event.id) ?: return
        if (EventStateMachine.canTransition(current.status, EventStatuses.EXPIRED)) {
            db.eventDao().updateStatus(event.id, EventStatuses.EXPIRED)
        }
    }

    private suspend fun expireInTransaction(event: EventEntity) {
        if (EventStateMachine.canTransition(event.status, EventStatuses.EXPIRED)) {
            check(db.eventDao().updateStatus(event.id, EventStatuses.EXPIRED) == 1)
        }
    }

    private suspend fun failTerminalEvent(
        event: EventEntity,
        errorCode: String,
        technicalDetail: String?,
    ): BackgroundProcessingResult = db.withTransaction {
        val current = db.eventDao().byId(event.id) ?: return@withTransaction BackgroundProcessingResult.Failed(
            event.id,
            errorCode,
            technicalDetail,
        )
        if (current.status in EventStatuses.terminal) {
            return@withTransaction BackgroundProcessingResult.AlreadyTerminal(
                current.id,
                current.status,
            )
        }
        val expiresAt = current.expiresAt
        if (expiresAt != null && expiresAt <= timeProvider.now().toEpochMilli()) {
            expireInTransaction(current)
            return@withTransaction BackgroundProcessingResult.Expired(current.id)
        }
        EventStateMachine.requireTransition(current.status, EventStatuses.FAILED)
        check(
            db.eventDao().markTerminalFailure(
                id = current.id,
                errorCode = errorCode,
                errorDetail = technicalDetail?.take(MAX_TECHNICAL_DETAIL_LENGTH),
            ) == 1,
        )
        BackgroundProcessingResult.Failed(current.id, errorCode, technicalDetail)
    }

    private suspend fun failInterruptedPlan(planId: String, event: EventEntity, technicalDetail: String) {
        db.withTransaction {
            val plan = db.actionPlanDao().byId(planId)
            if (plan != null && ActionPlanStateMachine.isActive(plan.status)) {
                ActionPlanStateMachine.requireTransition(plan.status, ActionPlanStatuses.FAILED)
                db.actionPlanDao().updateStatusWithActiveKey(planId, ActionPlanStatuses.FAILED)
            }
            val current = db.eventDao().byId(event.id)
            if (current != null && EventStateMachine.canTransition(current.status, EventStatuses.FAILED)) {
                db.eventDao().markTerminalFailure(
                    id = current.id,
                    errorCode = BACKGROUND_LOCAL_FAILURE,
                    errorDetail = technicalDetail.take(MAX_TECHNICAL_DETAIL_LENGTH),
                )
            }
        }
    }

    private sealed interface PersistPlanResult {
        data class Persisted(val planId: String, val mutationReport: MutationExecutionReport) : PersistPlanResult
        data class AlreadyTerminal(val eventId: String, val status: String) : PersistPlanResult
        data class Expired(val eventId: String) : PersistPlanResult
        data class Conflict(val eventId: String) : PersistPlanResult
    }

    private suspend fun generateWithDiagnostics(
        operation: String,
        correlationId: String,
        subjectId: String?,
        context: ContextPack,
        attempt: Int,
    ): ModelServiceResult {
        if (useProfiledReplyCoordinator && replyCoordinator != null) {
            return replyCoordinator.generate(
                context = context,
                operation = operation,
                correlationId = correlationId,
                entryPoint = ReplyEntryPoint.PROACTIVE,
                requestGate = { reserveBackgroundGenerationRequest(correlationId) },
            )
        }
        if ((modelService as? RoundDiagnosticsOwner)?.recordsOwnModelRounds == true) {
            return modelService.generate(context)
        }
        val requestBody = JsonCodec.encode(context)
        diagnosticsLogger?.aiRequestStarted(
            operation = operation,
            correlationId = correlationId,
            subjectId = subjectId,
            serviceName = modelService.serviceName,
            requestBody = requestBody,
            attempt = attempt,
        )
        val result = modelService.generate(context)
        diagnosticsLogger?.recordAiResult(
            operation = operation,
            correlationId = correlationId,
            subjectId = subjectId,
            context = context,
            result = result,
            attempt = attempt,
        )
        return result
    }

    private fun stagePayload(
        correlationId: String,
        marker: String,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): String = buildJsonObject {
        put("marker", marker)
        put("correlation_id", correlationId)
        fields()
    }.toString()

    private suspend fun reserveBackgroundGenerationRequest(eventId: String): Boolean =
        db.eventDao().reserveGenerationRequests(
            id = eventId,
            amount = 1,
            maxRequests = MAX_BACKGROUND_GENERATION_REQUESTS,
        ) == 1

    companion object {
        const val MODEL_OUTPUT_INVALID = "MODEL_OUTPUT_INVALID"
        const val MUTATION_INVALID = "MUTATION_INVALID"
        const val BACKGROUND_LOCAL_FAILURE = "BACKGROUND_LOCAL_FAILURE"
        const val MAX_BACKGROUND_MODEL_ATTEMPTS = 3
        const val MAX_BACKGROUND_GENERATION_REQUESTS = 12
        const val BACKGROUND_GENERATION_LIFECYCLE_QUOTA_EXHAUSTED = "background_generation_lifecycle_quota_exhausted"
        const val PERSISTENT_GENERATION_QUOTA_EXHAUSTED = "persistent_generation_quota_exhausted"
        const val GENERATION_REQUEST_BUDGET_EXHAUSTED = "generation_request_budget_exhausted"
        val TERMINAL_BUDGET_FAILURE_DETAILS: Set<String> = setOf(
            BACKGROUND_GENERATION_LIFECYCLE_QUOTA_EXHAUSTED,
            PERSISTENT_GENERATION_QUOTA_EXHAUSTED,
            GENERATION_REQUEST_BUDGET_EXHAUSTED,
            "reply_c_decision_revision_budget",
        )
        const val BASE_MODEL_RETRY_BACKOFF_MILLIS = 60 * 1_000L
        const val MAX_TECHNICAL_DETAIL_LENGTH = 500
        const val MAX_REPAIR_CONTEXT_LENGTH = 4_000
        const val MAX_TODO_TITLE_LENGTH = 80
        val RETRYABLE_MODEL_ERROR_CODES: Set<String> = setOf(
            ErrorCode.NETWORK_UNAVAILABLE.name,
            ErrorCode.TIMEOUT.name,
            ErrorCode.MODEL_FAILURE.name,
        )
        val ALLOWED_ANIMATION_IDS: Set<String> = com.littlekitty.xiaokele.animation.AnimationCatalog.knownIds

        private fun modelRetryBackoffMillis(attemptCount: Int): Long =
            BASE_MODEL_RETRY_BACKOFF_MILLIS shl attemptCount.coerceAtMost(4)
    }
}
