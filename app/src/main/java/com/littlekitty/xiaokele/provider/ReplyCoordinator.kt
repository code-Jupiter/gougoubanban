package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.AgentOutput
import com.littlekitty.xiaokele.core.common.protocol.AgentDecisionValidator
import com.littlekitty.xiaokele.core.common.protocol.DecisionChannels
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.DecisionNames
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import com.littlekitty.xiaokele.core.network.ServiceCallTrace
import com.littlekitty.xiaokele.animation.AnimationCatalog
import com.littlekitty.xiaokele.chat.FrontChatToolCall
import com.littlekitty.xiaokele.chat.FrontChatToolRegistry
import com.littlekitty.xiaokele.chat.LocalChatToolExecutor
import com.littlekitty.xiaokele.chat.ToolCallBudget
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

@Serializable
data class ReplyIntake(
    val goal: String = "",
    val emotionCues: List<String> = emptyList(),
    val informationNeeds: List<String> = emptyList(),
    val memoryQuery: String = "",
    val task: String = "A_INTAKE",
    val purpose: String = "DIRECT",
    val supportLevel: String = "BRIEF_ACK",
    val action: String = "ANSWER",
    val materials: List<String> = emptyList(),
    val uncertainties: List<String> = emptyList(),
    val boundaries: List<String> = emptyList(),
    val toolRequests: List<ReplyToolRequest> = emptyList(),
    val candidates: List<ReplyDraftCandidate> = emptyList(),
    /** v3 compatibility only; v4 DIRECT uses goal without duplicating a B1 plan. */
    val directResponseBrief: ResponseBrief? = null,
)

@Serializable
data class ReplyToolRequest(
    val name: String = "",
    val arguments: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ReplyDraftCandidate(
    val kind: String = "ACTION",
    val action: String = "",
    val payload: JsonObject = JsonObject(emptyMap()),
    val evidenceIds: List<String> = emptyList(),
)

@Serializable
data class BriefItem(
    val text: String = "",
    val evidenceId: String = "UNKNOWN",
    val kind: String = "OBSERVED",
)

@Serializable
data class ResponseBrief(
    val replyGoal: String = "",
    val mustAddress: List<BriefItem> = emptyList(),
    val mayExpress: List<BriefItem> = emptyList(),
    val internalOnly: List<BriefItem> = emptyList(),
    val action: String = "ANSWER",
    val uncertainties: List<String> = emptyList(),
    val candidates: List<ReplyDraftCandidate> = emptyList(),
    /** A-only fields that make a DIRECT brief usable without B1. */
    val supportMode: String = "BRIEF_ACK",
    val communicativeAct: String = "ANSWER",
    val continuation: String = "NONE",
    val behaviorBoundaries: List<String> = emptyList(),
    val constructScope: List<String> = emptyList(),
    val lengthBudget: Int = 800,
)

@Serializable
data class ReplyReview(
    /** Required on the wire; null lets validation reject legacy/ambiguous output. */
    val reviewKind: String? = null,
    val verdict: String = "REJECT",
    val issues: List<ReviewIssue> = emptyList(),
    val evidenceIds: List<String> = emptyList(),
    val reasonCode: String = "NONE",
)

@Serializable
data class ReviewIssue(
    val location: String = "candidate",
    val type: String = "unknown",
    val minimumChange: String = "",
)

/**
 * Production front-chat chain: A -> B1 -> B2/HER -> C. HER receives only the
 * minimal brief and is never offered tools or a structured-output contract.
 */
class ReplyCoordinator(
    private val gateway: TaskModelGateway,
    private val diagnosticsLogger: DiagnosticsLogger? = null,
    private val localChatToolExecutor: LocalChatToolExecutor? = null,
    private val memoryLookup: ReplyMemoryLookup? = null,
    private val attemptTimeoutMillis: Long = MAX_ATTEMPT_DURATION_MILLIS,
) {
    suspend fun generate(
        context: ContextPack,
        operation: String = "front_chat",
        correlationId: String = "reply",
        entryPoint: ReplyEntryPoint = ReplyEntryPoint.FOREGROUND,
        requestGate: suspend () -> Boolean = { true },
    ): ModelServiceResult {
        return withTimeoutOrNull(attemptTimeoutMillis.coerceIn(1, MAX_ATTEMPT_DURATION_MILLIS)) {
            try {
                generateAttempt(context, operation, correlationId, entryPoint, requestGate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                invalid("reply_attempt_exception")
            }
        } ?: invalid("reply_attempt_timeout")
    }

    private suspend fun generateAttempt(
        context: ContextPack,
        operation: String,
        correlationId: String,
        entryPoint: ReplyEntryPoint,
        requestGate: suspend () -> Boolean,
    ): ModelServiceResult {
        val budget = GenerationBudget(maxRequests = MAX_GENERATION_REQUESTS)
        // The gateway normally reserves this same budget, but counting at the
        // coordinator boundary is intentional too: deterministic fakes and
        // provider failures must not be able to bypass the eight-request
        // attempt cap or make a revision appear cheaper than it is.
        val callCounter = CallCounter()
        var contextPayload = JsonCodec.encode(context)
        val capabilities = buildJsonObject {
            put("readOnlyTools", JsonArray(if (localChatToolExecutor == null) emptyList() else
                allowedToolNames(context, entryPoint).sorted().map(::JsonPrimitive)))
            put("memoryLookup", memoryLookup != null)
            put("visualInput", false)
            put("writeOperations", false)
            put("scheduledReminders", false)
            put("permissionRule", "工具可请求不等于运行时权限已授予；只有本轮 toolResults 的 succeeded=true 才能证明查询完成。")
        }
        contextPayload = JsonObject(JsonCodec.json.parseToJsonElement(contextPayload).jsonObject +
            ("capabilities" to capabilities)).toString()
        val evidenceAllowlist = collectEvidenceIds(context).toMutableSet()
        var commitGuard: (() -> Boolean)? = null
        var finalExpressionTrace: ServiceCallTrace? = null
        val intakeResult = call(
            operation,
            correlationId,
            "A_INTAKE",
            TaskModelRequest(
                task = TaskKind.A_INTAKE,
                userPayload = taskPayload(contextPayload, "A", ReplyPromptPack.A_SCHEMA),
            ),
            budget,
            callCounter,
            requestGate,
            responseIsUsable = { runCatching { JsonCodec.decode<ReplyIntake>(normalizeIntakePayload(it)) }.isSuccess },
        )
        val attemptSnapshot = (intakeResult as? TaskModelResult.Success)?.snapshot
            ?: return intakeResult.toModelFailure()
        commitGuard = (intakeResult as? TaskModelResult.Success)?.commitGuard
        val intake = intakeResult.contentOrFailure() ?: return intakeResult.toModelFailure()
        val intakePayload = normalizeIntakePayload(intake)
        val intakeValue = runCatching { JsonCodec.decode<ReplyIntake>(intakePayload) }.getOrElse { error ->
            // Retain only key/type metadata: it diagnoses provider contract
            // drift without putting user text or model content into logs.
            return invalid("reply_a_schema_invalid:${jsonShape(intakePayload)};${decodeFailurePath(error)}")
        }
        validateIntake(intakeValue, evidenceAllowlist)?.let { return invalid(it) }
        if (intakeValue.action == "NO_ACTION") {
            if (entryPoint == ReplyEntryPoint.FOREGROUND) {
                return invalid("reply_a_no_action_for_foreground")
            }
            val noActionGuard = gatewayGuard(commitGuard, attemptSnapshot)
            if (noActionGuard?.invoke() == false) return invalid("stale_model_route")
            return ModelServiceResult.NoAction(
                reason = intakeValue.uncertainties.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "agent_no_action",
                trace = (intakeResult as? TaskModelResult.Success)?.trace,
                commitGuard = noActionGuard,
                evidenceIds = intakeValue.candidates.flatMap { it.evidenceIds }.distinct(),
            )
        }
        if (intakeValue.action == "STOP") {
            return invalid("reply_a_stop_without_user_visible_policy")
        }

        suspend fun supplement(query: String) {
            if (query.isBlank() || callCounter.memoryLookups >= 1) return
            callCounter.memoryLookups++
            val records = try {
                memoryLookup?.search(query)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            val payload = buildJsonObject {
                put("status", if (records == null) "FAILED" else if (records.isEmpty()) "NO_MATCH" else "FOUND")
                put("records", records ?: JsonArray(emptyList()))
                put("rule", "仅作资料；核对原始消息。空结果或失败不能成为否定用户经历的证据。")
            }
            records?.forEach { record ->
                listOf("id", "source_message_id").forEach { key ->
                    (record as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull?.let(evidenceAllowlist::add)
                }
            }
            contextPayload = JsonObject(JsonCodec.json.parseToJsonElement(contextPayload).jsonObject +
                ("supplementalMemory" to payload)).toString()
        }
        supplement(intakeValue.memoryQuery)

        val toolBundle = when (val result = executeToolRequests(
            operation = operation,
            correlationId = correlationId,
            requests = intakeValue.toolRequests,
            context = context,
            entryPoint = entryPoint,
        )) {
            is ToolExecutionResult.Failure -> return invalid(result.detail)
            is ToolExecutionResult.Success -> {
                evidenceAllowlist += result.evidenceIds
                result
            }
        }

        var brief: ResponseBrief
        // DIRECT normally remains A-owned.  Some MiniMax responses use the
        // compact route form without the required brief; recover it through
        // B1 rather than discarding a valid user turn.
        val needsB1 = intakeValue.purpose == "PLAN" || intakeValue.memoryQuery.isNotBlank() ||
            intakeValue.toolRequests.isNotEmpty() || (intakeValue.goal.isBlank() && intakeValue.directResponseBrief == null)
        if (needsB1) {
            val b1Result = call(
                operation,
                correlationId,
                "B1_DECISION",
                TaskModelRequest(
                    task = TaskKind.B1_DECISION,
                    complexity = if (intakeValue.purpose == "PLAN" || intakeValue.materials.isNotEmpty()) RouteComplexity.COMPLEX else RouteComplexity.DEFAULT,
                    userPayload = taskPayload(
                        contextPayload,
                        "B1",
                        "replyTask=${taskDescription(intakeValue)}\ntoolResults=${toolBundle.payload}\nschema=${ReplyPromptPack.B1_SCHEMA}",
                    ),
                ),
                budget,
                callCounter,
                requestGate,
                responseIsUsable = { runCatching { JsonCodec.decode<ResponseBrief>(normalizeBriefPayload(it)) }.isSuccess },
            )
            val b1 = b1Result.contentOrFailure() ?: return b1Result.toModelFailure()
            commitGuard = (b1Result as? TaskModelResult.Success)?.commitGuard ?: commitGuard
            brief = runCatching { JsonCodec.decode<ResponseBrief>(normalizeBriefPayload(b1)) }.getOrElse {
                return invalid("reply_b1_schema_invalid")
            }
        } else {
            brief = intakeValue.directResponseBrief ?: ResponseBrief(
                replyGoal = intakeValue.goal,
                uncertainties = intakeValue.informationNeeds,
                behaviorBoundaries = intakeValue.boundaries,
            )
        }
        validateBrief(brief, evidenceAllowlist)?.let { return invalid(it) }
        if (intakeValue.goal.isNotBlank() && brief.candidates.isNotEmpty()) return invalid("reply_v4_side_effect_candidate")

        var expression = generateExpression(operation, correlationId, contextPayload, intakeValue, brief, toolBundle.payload, budget, callCounter, requestGate)
            ?: return invalid("reply_b2_generation_failed")
        var candidate = expression.content
        finalExpressionTrace = expression.trace
        commitGuard = gatewayGuard(commitGuard, attemptSnapshot)
        if (!shouldReview(intakeValue, brief, toolBundle) && !hasExpressionRisk(candidate, brief)) {
            return success(candidate, brief, intakeValue, commitGuard, finalExpressionTrace, entryPoint)
        }
        repeat(MAX_REVIEW_REVISIONS + 1) { reviewAttempt ->
            val reviewResult = call(
                operation,
                correlationId,
                "C_REVIEW",
                TaskModelRequest(
                    task = TaskKind.C_REVIEW,
                    systemPrompt = ReplyPromptPack.reviewPrompt(ReviewKind.FINAL_REPLY),
                    userPayload = taskPayload(
                        contextPayload,
                        "C",
                        "reviewKind=${ReviewKind.FINAL_REPLY.name}\nreplyTask=${taskDescription(intakeValue)}\nresponseBrief=${briefDescription(brief)}\ntoolResults=${toolBundle.payload}\ncurrentUserOriginal=${currentUserOriginal(context)}\nrecentConversationOriginal=${recentConversationOriginal(context)}\nexpressionLedger=${submittedExpressionLedgerFromPayload(contextPayload)}\ncandidate=${candidate}\nschema=${ReplyPromptPack.C_SCHEMA}",
                    ),
                    reviewKind = ReviewKind.FINAL_REPLY,
                ),
                budget,
                callCounter,
                requestGate,
                responseIsUsable = { runCatching { JsonCodec.decode<ReplyReview>(it) }.isSuccess },
            )
            // C is advisory once a user-visible draft exists. A review outage
            // must not replace that draft with an unrelated local error line.
            val reviewRaw = reviewResult.contentOrFailure()
                ?: return success(candidate, brief, intakeValue, commitGuard, finalExpressionTrace, entryPoint)
            val review = runCatching { JsonCodec.decode<ReplyReview>(reviewRaw) }.getOrElse {
                return success(candidate, brief, intakeValue, commitGuard, finalExpressionTrace, entryPoint)
            }
            validateReview(review, evidenceAllowlist, ReviewKind.FINAL_REPLY)?.let {
                return success(candidate, brief, intakeValue, commitGuard, finalExpressionTrace, entryPoint)
            }
            commitGuard = (reviewResult as? TaskModelResult.Success)?.commitGuard ?: commitGuard
            when (review.verdict) {
                "PASS" -> {
                    if (!gateway.isRouteCurrent(attemptSnapshot) || commitGuard?.invoke() == false) return invalid("stale_model_route")
                    return success(candidate, brief, intakeValue, commitGuard, finalExpressionTrace, entryPoint)
                }
                "REVISE" -> if (reviewAttempt < MAX_REVIEW_REVISIONS) {
                    if (review.reasonCode == "MISSING_INFORMATION") {
                        supplement(intakeValue.memoryQuery.ifBlank { intakeValue.goal.ifBlank { brief.replyGoal } })
                    }
                    // Only EXPRESSION issues may go straight back to HER; all
                    // fact/boundary/injection/decision issues return to the
                    // structured decision stage first.
                    val decisionIssues = review.issues.filter { it.type != "EXPRESSION" || review.reasonCode == "MISSING_INFORMATION" }
                    if (decisionIssues.isNotEmpty()) {
                        if (MAX_GENERATION_REQUESTS - callCounter.value < 3) {
                            return success(candidate, brief, intakeValue, commitGuard, finalExpressionTrace, entryPoint)
                        }
                        val b1Result = call(
                            operation,
                            correlationId,
                            "B1_DECISION_REVISION",
                            TaskModelRequest(
                                task = TaskKind.B1_DECISION,
                                // The attempt may already have used its one Pro
                                // reservation for the initial PLAN decision.
                                complexity = if (budget.proRequestsUsed() == 0) RouteComplexity.COMPLEX else RouteComplexity.DEFAULT,
                                userPayload = taskPayload(
                                    contextPayload,
                                    "B1",
                                    "replyTask=${taskDescription(intakeValue)}\npreviousBrief=${briefDescription(brief)}\ncandidate=${candidate}\ntoolResults=${toolBundle.payload}\nreviewIssues=${JsonCodec.encode(decisionIssues)}\ncurrentUserOriginal=${currentUserOriginal(context)}\nrecentConversationOriginal=${recentConversationOriginal(context)}\nschema=${ReplyPromptPack.B1_SCHEMA}",
                                ),
                            ),
                            budget,
                            callCounter,
                            requestGate,
                            responseIsUsable = { runCatching { JsonCodec.decode<ResponseBrief>(normalizeBriefPayload(it)) }.isSuccess },
                        )
                        val revisedBriefRaw = b1Result.contentOrFailure() ?: return b1Result.toModelFailure()
                        commitGuard = (b1Result as? TaskModelResult.Success)?.commitGuard ?: commitGuard
                        brief = runCatching { JsonCodec.decode<ResponseBrief>(revisedBriefRaw) }.getOrElse {
                            return invalid("reply_b1_revision_schema_invalid")
                        }
                        val expressionConstraints = review.issues
                            .filter { it.type == "EXPRESSION" }
                            .map { it.minimumChange }
                            .filter { it.isNotBlank() }
                        if (expressionConstraints.isNotEmpty()) {
                            brief = brief.copy(uncertainties = brief.uncertainties + expressionConstraints)
                        }
                        validateBrief(brief, evidenceAllowlist)?.let { return invalid(it) }
                        if (intakeValue.goal.isNotBlank() && brief.candidates.isNotEmpty()) return invalid("reply_v4_side_effect_candidate")
                    } else {
                        brief = brief.copy(uncertainties = brief.uncertainties + review.issues.map { it.minimumChange })
                    }
                    expression = generateExpression(
                        operation,
                        correlationId,
                        contextPayload,
                        intakeValue,
                        brief,
                        toolBundle.payload,
                        budget,
                        callCounter,
                        requestGate,
                    ) ?: return invalid("reply_b2_revision_failed")
                    candidate = expression.content
                    finalExpressionTrace = expression.trace
                } else return success(candidate, brief, intakeValue, commitGuard, finalExpressionTrace, entryPoint)
                else -> return success(candidate, brief, intakeValue, commitGuard, finalExpressionTrace, entryPoint)
            }
        }
        return invalid("reply_c_no_verdict")
    }

    private suspend fun generateExpression(
        operation: String,
        correlationId: String,
        contextPayload: String,
        intake: ReplyIntake,
        brief: ResponseBrief,
        toolResults: String,
        budget: GenerationBudget,
        callCounter: CallCounter,
        requestGate: suspend () -> Boolean,
    ): ExpressionResult? {
        val result = call(
            operation,
            correlationId,
            "B2_EXPRESSION",
            TaskModelRequest(
                task = TaskKind.B2_EXPRESSION,
                structured = false,
                    userPayload = taskPayload(
                        contextPayload,
                        "B2",
                        "currentUserOriginal=${currentUserOriginalFromPayload(contextPayload)}\nrecentConversationOriginal=${recentConversationOriginalFromPayload(contextPayload)}\nreplyTask=${taskDescription(intake)}\nresponseBrief=${briefDescription(brief)}\ntoolResults=$toolResults\nexpressionLedger=${submittedExpressionLedgerFromPayload(contextPayload)}",
                    ),
            ),
            budget,
            callCounter,
            requestGate,
        )
        val text = result.contentOrFailure() ?: return null
        return text.trim().takeIf(::isSafePlainText)?.let {
            ExpressionResult(it, (result as? TaskModelResult.Success)?.trace)
        }
    }

    private suspend fun call(
        operation: String,
        correlationId: String,
        stage: String,
        request: TaskModelRequest,
        budget: GenerationBudget,
        callCounter: CallCounter,
        requestGate: suspend () -> Boolean,
        allowRecovery: Boolean = true,
        responseIsUsable: (String) -> Boolean = { true },
    ): TaskModelResult {
        if (callCounter.value >= MAX_GENERATION_REQUESTS ||
            System.currentTimeMillis() - callCounter.startedAtMillis >= MAX_ATTEMPT_DURATION_MILLIS
        ) {
            return TaskModelResult.Failure(
                AppError(
                    ErrorCode.MODEL_FAILURE,
                    userMessage = "本轮模型请求预算已用尽，请稍后重试",
                    technicalDetail = "generation_request_budget_exhausted",
                ),
            )
        }
        if (!requestGate()) {
            return TaskModelResult.Failure(
                AppError(
                    ErrorCode.MODEL_FAILURE,
                    userMessage = "后台模型任务已达到生命周期请求上限，请稍后处理",
                    technicalDetail = "background_generation_lifecycle_quota_exhausted",
                ),
            )
        }
        callCounter.value++
        // Keep acceptance metadata on the provider-neutral request so every
        // task (including A/B1 revisions and C) is exportable without putting
        // raw request bodies into production diagnostics.
        val tracedRequest = request.copy(
            turnId = correlationId,
            scenarioId = operation,
            userInput = extractUserInput(request.userPayload),
        )
        if (!request.redactDiagnostics) diagnosticsLogger?.aiRequestStarted(
            operation = operation,
            correlationId = correlationId,
            subjectId = correlationId,
            serviceName = "profiled_task_model_gateway",
            requestBody = tracedRequest.userPayload,
            requestKind = "reply_$stage",
            attempt = budget.usedRequests() + 1,
        )
        val result = gateway.generate(tracedRequest, budget)
        if (!request.redactDiagnostics) diagnosticsLogger?.aiResponseRecorded(
            operation = operation,
            correlationId = correlationId,
            subjectId = correlationId,
            status = if (result is TaskModelResult.Success) DiagnosticLogStatuses.SUCCESS else DiagnosticLogStatuses.FAILED,
            trace = when (result) {
                is TaskModelResult.Success -> result.trace
                is TaskModelResult.Failure -> result.trace
            },
            fallbackRequestBody = request.userPayload,
            normalizedOutput = (result as? TaskModelResult.Success)?.content,
            errorCode = (result as? TaskModelResult.Failure)?.error?.code?.name,
            technicalDetail = (result as? TaskModelResult.Failure)?.error?.technicalDetail,
            attempt = budget.usedRequests(),
        )
        if (!allowRecovery) return result
        if (!request.structured) {
            val invalidText = result is TaskModelResult.Success && !isSafePlainText(result.content)
            val invalidOutput = result is TaskModelResult.Failure && result.error.code == ErrorCode.MODEL_OUTPUT_INVALID
            if ((invalidText || invalidOutput) && callCounter.regenerations < 1 && callCounter.value < MAX_GENERATION_REQUESTS) {
                callCounter.regenerations++
                return call(operation, correlationId, "${stage}_REGENERATION", request, budget, callCounter,
                    requestGate, allowRecovery = false)
            }
            return result
        }
        if (result is TaskModelResult.Success) {
            if (responseIsUsable(result.content)) return result
            return if (callCounter.value < MAX_GENERATION_REQUESTS && callCounter.regenerations < 1) {
                callCounter.regenerations++
                call(operation, correlationId, "${stage}_SCHEMA_REGENERATION", request, budget, callCounter, requestGate,
                    allowRecovery = false, responseIsUsable = responseIsUsable)
            } else result
        }
        if (result !is TaskModelResult.Failure || result.error.code != ErrorCode.MODEL_OUTPUT_INVALID) return result

        val source = result.recoverableContent
        if (source != null && StructuredJsonRepair.eligible(source) && callCounter.value < MAX_GENERATION_REQUESTS && callCounter.repairs < 1) {
            callCounter.repairs++
            val repair = call(
                operation, correlationId, "${stage}_FORMAT_REPAIR",
                request.copy(
                    complexity = RouteComplexity.DEFAULT,
                    systemPrompt = "只修复给定完整对象的 JSON 标点语法。原样保留所有字段、字符串、数字、布尔值及其顺序；不能增删或改写内容。只输出修复后的 JSON 对象。",
                    userPayload = source,
                    redactDiagnostics = true,
                ),
                budget, callCounter, requestGate, allowRecovery = false,
            )
            if (repair is TaskModelResult.Success && StructuredJsonRepair.lossless(source, repair.content) &&
                responseIsUsable(repair.content)
            ) {
                return repair
            }
        }
        // A truncated or lossy response is never completed by another model.
        // Regenerate only this failed stage once, within the same route/budget.
        return if (callCounter.value < MAX_GENERATION_REQUESTS && callCounter.regenerations < 1) {
            callCounter.regenerations++
            call(operation, correlationId, "${stage}_REGENERATION", request, budget, callCounter, requestGate,
                allowRecovery = false, responseIsUsable = responseIsUsable)
        } else result
    }

    private fun extractUserInput(payload: String): String = runCatching {
        val root = JsonCodec.json.parseToJsonElement(payload).jsonObject
        val recent = root["recent_messages"]?.jsonArray.orEmpty()
        recent.lastOrNull { element ->
            element.jsonObject["role"]?.jsonPrimitive?.contentOrNull == "USER"
        }?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
    }.getOrDefault("")

    private fun success(
        candidate: String,
        brief: ResponseBrief,
        intake: ReplyIntake,
        commitGuard: (() -> Boolean)?,
        trace: ServiceCallTrace?,
        entryPoint: ReplyEntryPoint,
    ): ModelServiceResult {
        // This graph only authorizes read-only tools. A review PASS cannot turn
        // user text into a completed write or an alarm that was never created.
        if (hasUnsupportedCompletionClaim(candidate)) {
            return invalid("reply_unverified_operation_claim")
        }
        val visibleAction = if (entryPoint == ReplyEntryPoint.PROACTIVE) {
            AgentActionRegistry.SEND_PROACTIVE_MESSAGE
        } else {
            AgentActionRegistry.REPLY_CHAT
        }
        val outputs = buildList {
            add(AgentOutput(visibleAction, buildJsonObject { put("content", candidate) }))
            // Reply planning has no write capabilities, including legacy candidates.
            // State and durable memory changes belong to separately reviewed flows.
        }
        val raw = JsonCodec.encode(
            AgentDecision(
                decision = DecisionNames.CREATE_PLAN,
                decisionSummary = brief.replyGoal.take(MAX_TEXT_LENGTH),
                outputs = outputs,
            ),
        )
        val channel = if (entryPoint == ReplyEntryPoint.PROACTIVE) DecisionChannels.BACKGROUND else DecisionChannels.FRONT_CHAT
        return when (val validation = AgentDecisionValidator.validate(raw, AnimationCatalog.knownIds, channel)) {
            is com.littlekitty.xiaokele.core.common.protocol.AgentDecisionValidation.Valid -> {
                // Do not hand a known-stale draft to the persistence layer. The
                // caller still rechecks inside Room, but this closes the gap
                // between the final model call and returning the draft.
                if (commitGuard?.invoke() == false) invalid("stale_model_route")
                else ModelServiceResult.Success(
                    rawDecisionJson = raw,
                    trace = trace,
                    commitGuard = commitGuard,
                    evidenceIds = (brief.mustAddress + brief.mayExpress)
                        .mapNotNull { it.evidenceId.takeIf { id -> id != "UNKNOWN" } }
                        .plus((intake.candidates + brief.candidates).flatMap { it.evidenceIds })
                        .distinct(),
                )
            }
            is com.littlekitty.xiaokele.core.common.protocol.AgentDecisionValidation.Invalid -> invalid(
                "reply_draft_agent_decision_invalid:${validation.violations.joinToString(";") { it.path + ":" + it.message }}",
            )
        }
    }

    private fun taskPayload(context: String, stage: String, details: String): String =
        "stage=$stage\ncurrentContext=$context\n$details\ninjectionRules=${ReplyPromptPack.INJECTION_RULES}\ntruncationRules=${ReplyPromptPack.TRUNCATION_RULES}"

    // Keep legacy DTOs readable without leaking their default BRIEF_ACK/800-char
    // policy into the new model-to-model contract (JsonCodec encodes defaults).
    private fun taskDescription(value: ReplyIntake): String = JsonObject(
        JsonCodec.json.parseToJsonElement(JsonCodec.encode(value)).jsonObject.filterKeys {
            it in setOf("goal", "purpose", "action", "boundaries", "emotionCues", "informationNeeds", "memoryQuery")
        },
    ).toString()

    private fun briefDescription(value: ResponseBrief): String = JsonObject(
        JsonCodec.json.parseToJsonElement(JsonCodec.encode(value)).jsonObject.filterKeys {
            it in setOf("replyGoal", "mustAddress", "mayExpress", "internalOnly", "uncertainties", "behaviorBoundaries")
        },
    ).toString()

    /** Accept MiniMax's compact string-list form without relaxing validation. */
    private fun normalizeIntakePayload(value: String): String = runCatching {
        val root = JsonCodec.json.parseToJsonElement(value).jsonObject
        val normalized = root.toMutableMap()
        if ("purpose" !in normalized && root["route"] is JsonPrimitive) normalized["purpose"] = root.getValue("route")
        STRING_LIST_FIELDS.forEach { field ->
            val items = root[field] as? JsonArray ?: return@forEach
            normalized[field] = toPlainStringArray(items)
        }
        val brief = root["directResponseBrief"] as? JsonObject ?: return@runCatching JsonObject(normalized).toString()
        val normalizedBrief = brief.toMutableMap()
        BRIEF_ITEM_FIELDS.forEach { field ->
            val items = brief[field] as? JsonArray ?: return@forEach
            normalizedBrief[field] = JsonArray(items.map { item ->
                if (item is JsonPrimitive && item.isString) {
                    buildJsonObject {
                        put("text", item.content)
                        put("evidenceId", "UNKNOWN")
                        put("kind", "OBSERVED")
                    }
                } else item
            })
        }
        STRING_LIST_FIELDS.forEach { field ->
            val items = brief[field] as? JsonArray ?: return@forEach
            normalizedBrief[field] = toPlainStringArray(items)
        }
        normalized["directResponseBrief"] = JsonObject(normalizedBrief)
        JsonObject(normalized).toString()
    }.getOrDefault(value)

    private fun normalizeBriefPayload(value: String): String = runCatching {
        val root = JsonCodec.json.parseToJsonElement(value).jsonObject
        val normalized = root.toMutableMap()
        BRIEF_ITEM_FIELDS.forEach { field ->
            val items = root[field] as? JsonArray ?: return@forEach
            normalized[field] = JsonArray(items.map { item ->
                if (item is JsonPrimitive && item.isString) buildJsonObject {
                    put("text", item.content)
                    put("evidenceId", "UNKNOWN")
                    put("kind", "OBSERVED")
                } else item
            })
        }
        STRING_LIST_FIELDS.forEach { field ->
            val items = root[field] as? JsonArray ?: return@forEach
            normalized[field] = toPlainStringArray(items)
        }
        JsonObject(normalized).toString()
    }.getOrDefault(value)

    /**
     * The contract declares materials/uncertainties/boundaries/constructScope as
     * plain string arrays. Models still sometimes emit the richer object form
     * they see next door in mustAddress, which would otherwise fail the whole A
     * response on a shape mismatch (`$.directResponseBrief.uncertainties[0]`).
     * Collapse those elements to their text without relaxing any value rule.
     */
    private fun toPlainStringArray(items: JsonArray): JsonArray = JsonArray(items.map { item ->
        when {
            item is JsonPrimitive && item.isString -> item
            item is JsonObject -> {
                val text = item["text"]?.let { element ->
                    (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                } ?: item.values.firstOrNull { element ->
                    element is JsonPrimitive && element.isString
                }?.let { (it as JsonPrimitive).content }
                JsonPrimitive(text ?: item.toString())
            }
            else -> JsonPrimitive(item.toString())
        }
    })

    private fun jsonShape(value: String): String = runCatching {
        val root = JsonCodec.json.parseToJsonElement(value).jsonObject
        val topLevel = root.entries
            .sortedBy { it.key }
            .joinToString(",") { (key, element) -> "$key=${elementShape(element)}" }
        val directBrief = root["directResponseBrief"] as? JsonObject
        if (directBrief == null) topLevel else {
            val briefShape = directBrief.entries
                .sortedBy { it.key }
                .joinToString(",") { (key, element) -> "$key=${elementShape(element)}" }
            "$topLevel;directResponseBrief[$briefShape]"
        }
    }.getOrDefault("unparseable_json")

    private fun elementShape(element: JsonElement): String = when (element) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonPrimitive -> if (element.isString) "string" else "primitive"
        else -> "null"
    }

    private fun decodeFailurePath(error: Throwable): String =
        "path=" + Regex("\\$[A-Za-z0-9_.\\[\\]]*").find(error.message.orEmpty())?.value.orEmpty().ifBlank { "unknown" }

    /**
     * Keep raw wording visible as named B2 inputs. The complete ContextPack is
     * still sent for evidence and policy fields, but an adapter must not reduce
     * expression to an abstract intent label.
     */
    private fun currentUserOriginal(context: ContextPack): String =
        currentUserOriginalFromPayload(JsonCodec.encode(context))

    private fun recentConversationOriginal(context: ContextPack): String =
        recentConversationOriginalFromPayload(JsonCodec.encode(context))

    private fun currentUserOriginalFromPayload(contextPayload: String): String = runCatching {
        JsonCodec.json.parseToJsonElement(contextPayload)
            .jsonObject["event"]?.jsonObject
            ?.get("payload")?.jsonObject
            ?.get("user_message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
            .orEmpty()
    }.getOrDefault("").take(MAX_SOURCE_TEXT_LENGTH)

    private fun recentConversationOriginalFromPayload(contextPayload: String): String = runCatching {
        JsonCodec.json.parseToJsonElement(contextPayload)
            .jsonObject["event"]?.jsonObject
            ?.get("payload")?.jsonObject
            ?.get("recent_messages")?.toString()
            .orEmpty()
    }.getOrDefault("[]").take(MAX_RECENT_CONTEXT_LENGTH)

    /**
     * The expression ledger is deliberately reconstructed from the formal
     * recent-message window, never from A/B2 drafts or diagnostics.  Only
     * assistant rows are relevant to repetition/style checks; an empty window
     * is an explicit empty ledger rather than permission to infer history.
     */
    private fun submittedExpressionLedgerFromPayload(contextPayload: String): String = runCatching {
        val messages = JsonCodec.json.parseToJsonElement(contextPayload)
            .jsonObject["event"]?.jsonObject
            ?.get("payload")?.jsonObject
            ?.get("recent_messages")?.jsonArray
            .orEmpty()
        buildJsonArray {
            messages.filter { message ->
                message.jsonObject["role"]?.jsonPrimitive?.contentOrNull
                    ?.equals("ASSISTANT", ignoreCase = true) == true
            }.forEach { add(it) }
        }.toString()
    }.getOrDefault("[]").take(MAX_RECENT_CONTEXT_LENGTH)

    private fun isSafePlainText(value: String): Boolean {
        if (value.isBlank() || value.length > MAX_REPLY_CHARACTERS) return false
        val trimmed = value.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("```") || trimmed.contains("tool_calls", ignoreCase = true)) return false
        if (trimmed.contains("internalOnly", ignoreCase = true) || trimmed.contains("responseBrief", ignoreCase = true)) return false
        return true
    }

    private fun shouldReview(intake: ReplyIntake, brief: ResponseBrief, tools: ToolExecutionResult.Success): Boolean =
        intake.goal.isNotBlank() || intake.purpose == "PLAN" || intake.boundaries.isNotEmpty() || intake.candidates.any(::candidateNeedsReview) ||
            brief.candidates.any(::candidateNeedsReview) || tools.evidenceIds.isNotEmpty() ||
            (brief.mustAddress + brief.mayExpress).any { it.kind in setOf("INFERRED", "IMAGINED", "ROLEPLAY") }

    /** Conservative lexical gate only; C remains the semantic reviewer. */
    private fun hasExpressionRisk(candidate: String, brief: ResponseBrief): Boolean {
        val text = candidate.trim()
        val unsupportedCommitment = listOf(
            "我已经查", "我来查", "我帮你查", "我已经保存", "我帮你记住", "已记录", "已删除", "我保证",
        ).any(text::contains)
        if (unsupportedCommitment) return true
        val factualMarkers = listOf("天气", "排班", "定位", "位置", "记住", "保存", "删除")
        val supportedText = (brief.mustAddress + brief.mayExpress).joinToString(" ") { it.text }
        return factualMarkers.any { marker -> text.contains(marker) && !supportedText.contains(marker) }
    }

    private fun hasUnsupportedCompletionClaim(candidate: String): Boolean {
        val unquoted = candidate.replace(Regex("“[^”]*”|「[^」]*」|\\\"[^\\\"]*\\\""), "")
        return UNSUPPORTED_COMPLETION_CLAIMS.any { phrase ->
            var index = unquoted.indexOf(phrase)
            while (index >= 0) {
                val prefix = unquoted.substring((index - 8).coerceAtLeast(0), index)
                if (listOf("没有", "没", "不能", "无法", "不要", "不是", "不曾").none(prefix::contains)) return true
                index = unquoted.indexOf(phrase, index + phrase.length)
            }
            false
        }
    }

    private fun candidateNeedsReview(candidate: ReplyDraftCandidate): Boolean =
        candidate.kind != "ACTION" || candidate.action == AgentActionRegistry.UPDATE_PET_STATE

    private fun gatewayGuard(current: (() -> Boolean)?, snapshot: ModelRouteSnapshot): () -> Boolean = {
        (current?.invoke() != false) && gateway.isRouteCurrent(snapshot)
    }

    private fun validateIntake(value: ReplyIntake, evidence: Set<String>): String? {
        if (value.goal.length > MAX_TEXT_LENGTH || value.memoryQuery.length > MAX_TEXT_LENGTH ||
            value.emotionCues.size > MAX_LIST || value.informationNeeds.size > MAX_LIST ||
            (value.emotionCues + value.informationNeeds).any { it.length > MAX_TEXT_LENGTH }) return "reply_a_task_invalid"
        if (value.goal.isNotBlank() && (value.candidates.isNotEmpty() || value.directResponseBrief != null)) return "reply_a_v4_legacy_fields"
        if (value.task != "A_INTAKE" || value.purpose !in PURPOSES || value.supportLevel !in SUPPORT_LEVELS || value.action !in INTAKE_ACTIONS) return "reply_a_enum_invalid"
        if (value.materials.size > MAX_LIST || value.uncertainties.size > MAX_LIST || value.boundaries.size > MAX_LIST || value.toolRequests.size > FrontChatToolRegistry.MAX_CALLS_PER_SESSION || value.candidates.size > MAX_LIST) return "reply_a_count_invalid"
        if (value.materials.any { it.length > MAX_TEXT_LENGTH } || value.uncertainties.any { it.length > MAX_TEXT_LENGTH } || value.boundaries.any { it.length > MAX_TEXT_LENGTH }) return "reply_a_text_too_long"
        value.toolRequests.forEach { request ->
            if (request.arguments.toString().length > MAX_ARGUMENT_LENGTH) return "reply_a_tool_arguments_too_long"
            FrontChatToolRegistry.validateReadOnlyArguments(request.name, request.arguments)?.let {
                return "reply_a_tool_arguments_invalid:$it"
            }
            // A is allowed to request read-only evidence. Creative bookkeeping
            // tools mutate durable state and must only run from an explicit
            // program/UI flow, never from an uncommitted model intake.
            if (request.name in MUTATING_TOOL_NAMES) return "reply_a_tool_side_effect_not_allowed"
        }
        value.candidates.forEach { candidate -> validateCandidate(candidate, evidence)?.let { return it } }
        value.directResponseBrief?.let { validateBrief(it, evidence)?.let { detail -> return "reply_a_direct_brief_invalid:$detail" } }
        return null
    }

    private fun validateBrief(value: ResponseBrief, evidence: Set<String>): String? {
        if (value.replyGoal.isBlank() || value.replyGoal.length > MAX_TEXT_LENGTH || value.action !in ACTIONS || value.supportMode !in SUPPORT_LEVELS || value.communicativeAct !in ACTIONS || value.continuation !in CONTINUATIONS || value.lengthBudget !in 1..MAX_REPLY_CHARACTERS) return "reply_b1_enum_or_goal_invalid"
        if (value.mustAddress.size > MAX_LIST || value.mayExpress.size > MAX_LIST || value.internalOnly.size > MAX_LIST || value.uncertainties.size > MAX_LIST || value.candidates.size > MAX_LIST) return "reply_b1_count_invalid"
        if (value.behaviorBoundaries.size > MAX_LIST || value.constructScope.size > MAX_LIST) return "reply_b1_count_invalid"
        if (value.behaviorBoundaries.any { it.length > MAX_TEXT_LENGTH } || value.constructScope.any { it.length > MAX_TEXT_LENGTH }) return "reply_b1_text_too_long"
        (value.mustAddress + value.mayExpress + value.internalOnly).forEach { item ->
            if (item.text.isBlank() || item.text.length > MAX_TEXT_LENGTH || item.kind !in EVIDENCE_KINDS || (item.evidenceId != "UNKNOWN" && item.evidenceId !in evidence)) return "reply_b1_evidence_invalid"
        }
        value.candidates.forEach { candidate -> validateCandidate(candidate, evidence)?.let { return it } }
        return null
    }

    private fun validateReview(value: ReplyReview, evidence: Set<String>, expectedKind: ReviewKind): String? {
        if (value.reasonCode !in REVIEW_REASONS || (value.verdict == "PASS" && value.reasonCode != "NONE")) return "reply_c_reason_invalid"
        if (value.reviewKind != expectedKind.name || value.verdict !in REVIEW_VERDICTS || value.issues.size > MAX_LIST || value.evidenceIds.size > MAX_LIST) return "reply_c_enum_invalid"
        if (value.issues.any { it.location.length > 200 || it.minimumChange.length > MAX_TEXT_LENGTH || it.type !in REVIEW_TYPES }) return "reply_c_issue_invalid"
        if (value.issues.any { it.type !in REVIEW_TYPES_BY_KIND.getValue(expectedKind) }) return "reply_c_issue_recipe_invalid"
        if (value.evidenceIds.any { it !in evidence }) return "reply_c_evidence_invalid"
        if (value.verdict == "PASS" && value.issues.isNotEmpty()) return "reply_c_pass_with_issues"
        if (value.verdict == "REVISE" && value.issues.isEmpty()) return "reply_c_revise_without_issues"
        if (value.verdict == "PASS" && expectedKind in setOf(ReviewKind.RELATIONSHIP_CONFIRMATION, ReviewKind.STATE_UPDATE) && value.evidenceIds.isEmpty()) {
            return if (expectedKind == ReviewKind.RELATIONSHIP_CONFIRMATION) {
                "reply_c_confirmation_evidence_missing"
            } else {
                "reply_c_state_trigger_evidence_missing"
            }
        }
        return null
    }

    private fun validateCandidate(value: ReplyDraftCandidate, evidence: Set<String>): String? {
        if (value.kind !in CANDIDATE_KINDS || value.action.length > 120 || value.payload.toString().length > MAX_ARGUMENT_LENGTH || value.evidenceIds.size > MAX_LIST) return "reply_candidate_invalid"
        if (value.kind in setOf("ACTION", "STATE") && value.action !in AgentActionRegistry.all) return "reply_candidate_action_invalid"
        if (value.evidenceIds.any { it != "UNKNOWN" && it !in evidence }) return "reply_candidate_evidence_invalid"
        return null
    }

    private fun collectEvidenceIds(context: ContextPack): Set<String> {
        val result = mutableSetOf<String>()
        fun visit(element: JsonElement, key: String? = null) {
            when (element) {
                is kotlinx.serialization.json.JsonObject -> element.forEach { (childKey, child) -> visit(child, childKey) }
                is kotlinx.serialization.json.JsonArray -> element.forEach { visit(it, key) }
                is JsonPrimitive -> if (key != null && (key == "id" || key.endsWith("_id") || key == "evidenceId") && element.isString) {
                    element.contentOrNull?.takeIf { it.isNotBlank() }?.let(result::add)
                }
            }
        }
        visit(JsonCodec.json.parseToJsonElement(JsonCodec.encode(context)))
        return result
    }

    private suspend fun executeToolRequests(
        operation: String,
        correlationId: String,
        requests: List<ReplyToolRequest>,
        context: ContextPack,
        entryPoint: ReplyEntryPoint,
    ): ToolExecutionResult {
        if (requests.isEmpty()) return ToolExecutionResult.Success("[]", emptySet())
        val executor = localChatToolExecutor ?: return ToolExecutionResult.Failure("reply_tools_not_configured")
        val allowedTools = allowedToolNames(context, entryPoint)
        if (requests.any { request ->
                request.name !in allowedTools ||
                    FrontChatToolRegistry.validateReadOnlyArguments(request.name, request.arguments) != null
            }) {
            return ToolExecutionResult.Failure("reply_tool_not_allowlisted_or_arguments_invalid")
        }
        val calls = requests.mapIndexed { index, request ->
            FrontChatToolCall(
                id = "$correlationId-tool-$index",
                name = request.name,
                arguments = request.arguments,
                assistantMessage = buildJsonObject {},
                assistantToolCallIndex = index,
            )
        }
        val budget = ToolCallBudget(FrontChatToolRegistry.MAX_CALLS_PER_ROUND, FrontChatToolRegistry.MAX_CALLS_PER_SESSION)
        val selection = budget.select(calls)
        val evidenceIds = mutableSetOf<String>()
        val results = buildJsonArray {
            selection.executions.forEachIndexed { index, execution ->
                val started = System.currentTimeMillis()
                val rawResult = if (execution.allowed) executor.execute(execution.call) else execution.structuredRejectionResult()
                val result = boundToolResult(rawResult)
                val evidenceId = "tool_${index}_${execution.call.name}"
                evidenceIds += evidenceId
                diagnosticsLogger?.toolExecutionRecorded(
                    operation = operation,
                    correlationId = correlationId,
                    attempt = index + 1,
                    toolCallId = execution.call.id,
                    toolName = execution.call.name,
                    allowed = execution.allowed,
                    succeeded = execution.allowed && toolSucceeded(result),
                    arguments = execution.call.arguments,
                    result = result,
                    durationMillis = System.currentTimeMillis() - started,
                )
                add(buildJsonObject {
                    put("evidenceId", evidenceId)
                    put("toolName", execution.call.name)
                    put("succeeded", execution.allowed && toolSucceeded(result))
                    put("result", result)
                })
            }
        }
        return ToolExecutionResult.Success(results.toString(), evidenceIds)
    }

    /**
     * Background scenes may expose fewer read-only tools than front chat. The
     * old direct adapter enforced the same automation-rule allowlist while
     * constructing its tool definitions; keep the coordinator's A-planned
     * path equally fail-closed instead of letting a model bypass it by
     * writing toolRequests directly into its JSON intake.
     */
    private fun allowedToolNames(context: ContextPack, entryPoint: ReplyEntryPoint): Set<String> {
        if (entryPoint != ReplyEntryPoint.PROACTIVE) return FrontChatToolRegistry.readOnlyNames
        return context.scenePolicy
            ?.get("automation_rule")
            ?.jsonObject
            ?.get("allowed_tools")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.split(',')
            ?.map(String::trim)
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: setOf("query_weather")
    }

    private fun boundToolResult(result: JsonObject): JsonObject {
        val serialized = result.toString()
        if (serialized.length <= MAX_TOOL_RESULT_LENGTH) return result
        return buildJsonObject {
            put("type", "tool_result")
            put("ok", false)
            put("error_code", "tool_result_truncated")
            put("message", "工具结果过长，已安全截断；不得把截断文本当作完整事实。")
            put("raw_prefix", serialized.take(MAX_TOOL_RESULT_PREFIX_LENGTH))
            put("truncated", true)
        }
    }

    private fun toolSucceeded(result: JsonObject): Boolean = runCatching {
        val inner = result["result"]?.jsonObject ?: result
        (inner["ok"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: true
    }.getOrDefault(true)

    private fun invalid(detail: String): ModelServiceResult.Failure = ModelServiceResult.Failure(
        AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = detail),
    )

    private fun TaskModelResult.contentOrFailure(): String? = when (this) {
        is TaskModelResult.Success -> content
        is TaskModelResult.Failure -> null
    }

    private fun TaskModelResult.toModelFailure(): ModelServiceResult.Failure = when (this) {
        is TaskModelResult.Success -> invalid("unexpected_success_mapping")
        is TaskModelResult.Failure -> ModelServiceResult.Failure(error, trace)
    }

    private data class ExpressionResult(
        val content: String,
        val trace: ServiceCallTrace?,
    )

    private class CallCounter(
        var value: Int = 0,
        var repairs: Int = 0,
        var regenerations: Int = 0,
        var memoryLookups: Int = 0,
        val startedAtMillis: Long = System.currentTimeMillis(),
    )

    private sealed interface ToolExecutionResult {
        data class Success(val payload: String, val evidenceIds: Set<String>) : ToolExecutionResult
        data class Failure(val detail: String) : ToolExecutionResult
    }

    companion object {
        const val MAX_REVIEW_REVISIONS = 1
        const val MAX_REPLY_CHARACTERS = 8_000
        private const val MAX_TEXT_LENGTH = 1_000
        private const val MAX_ARGUMENT_LENGTH = 4_096
        private const val MAX_TOOL_RESULT_LENGTH = 8_000
        private const val MAX_TOOL_RESULT_PREFIX_LENGTH = 2_000
        private const val MAX_SOURCE_TEXT_LENGTH = 4_000
        private const val MAX_RECENT_CONTEXT_LENGTH = 12_000
        const val MAX_GENERATION_REQUESTS = 8
        private const val MAX_ATTEMPT_DURATION_MILLIS = 90_000L
        private const val MAX_LIST = 8
        /** Fields declared as arrays of {"text","evidenceId","kind"} objects. */
        private val BRIEF_ITEM_FIELDS = listOf("mustAddress", "mayExpress", "internalOnly")
        /** Fields declared as plain string arrays in both A and B1 schemas. */
        private val STRING_LIST_FIELDS = listOf("materials", "uncertainties", "boundaries", "behaviorBoundaries", "constructScope")
        private val PURPOSES = setOf("DIRECT", "PLAN")
        private val SUPPORT_LEVELS = setOf("IMPLICIT", "BRIEF_ACK", "FOCUSED_SUPPORT")
        private val INTAKE_ACTIONS = setOf("ANSWER", "ASK_MINIMUM", "ACKNOWLEDGE", "CLOSE", "STOP", "NO_ACTION")
        private val ACTIONS = setOf("ANSWER", "ASK_MINIMUM", "ACKNOWLEDGE", "CLOSE")
        private val CONTINUATIONS = setOf("NONE", "INVITE", "FOLLOW_UP")
        private val EVIDENCE_KINDS = setOf("OBSERVED", "REPORTED", "EVIDENCED", "INFERRED", "IMAGINED", "ROLEPLAY")
        private val CANDIDATE_KINDS = setOf("ACTION", "STATE", "MEMORY", "EVIDENCE")
        private val REVIEW_VERDICTS = setOf("PASS", "REVISE", "REJECT")
        private val REVIEW_REASONS = setOf("NONE", "UNSUPPORTED_FACT", "MISSING_INFORMATION", "CAPABILITY", "USER_BOUNDARY", "PRIVACY_OR_INJECTION", "TASK_MISMATCH")
        private val UNSUPPORTED_COMPLETION_CLAIMS = listOf(
            "已经设好", "已设好", "设好了", "已经保存", "已保存", "保存好了",
            "已经删除", "已删除", "删除好了", "已经创建", "已创建",
            "我会按时提醒你", "我一定提醒你", "到时候我会叫你", "我会自动提醒你",
        )
        private val REVIEW_TYPES = setOf("DECISION", "EXPRESSION", "FACT", "BOUNDARY", "INJECTION", "SOURCE", "CONFIRMATION", "STATE")
        private val REVIEW_TYPES_BY_KIND = mapOf(
            ReviewKind.FINAL_REPLY to setOf("DECISION", "EXPRESSION", "FACT", "BOUNDARY", "INJECTION", "SOURCE"),
            ReviewKind.MEMORY_CANDIDATE to setOf("FACT", "SOURCE", "CONFIRMATION", "BOUNDARY", "INJECTION"),
            ReviewKind.RELATIONSHIP_CONFIRMATION to setOf("CONFIRMATION", "FACT", "SOURCE", "INJECTION"),
            ReviewKind.STATE_UPDATE to setOf("STATE", "FACT", "SOURCE", "BOUNDARY", "INJECTION"),
        )
        private val MUTATING_TOOL_NAMES = setOf(
            "mark_topic_discussed",
            "save_creative_session_progress",
        )
    }
}
