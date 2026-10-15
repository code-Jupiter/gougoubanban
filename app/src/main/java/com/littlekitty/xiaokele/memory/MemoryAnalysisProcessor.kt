package com.littlekitty.xiaokele.memory

import androidx.room.withTransaction
import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.MemoryEvaluationValidation
import com.littlekitty.xiaokele.core.common.protocol.MemoryEvaluationValidator
import com.littlekitty.xiaokele.core.common.protocol.MemoryEvaluation
import com.littlekitty.xiaokele.core.common.protocol.MemoryTypeNames
import com.littlekitty.xiaokele.core.common.protocol.MemoryValidityNames
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogCategories
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogNames
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.core.database.entity.MemoryEmbeddingEntity
import com.littlekitty.xiaokele.core.database.memory.VectorBlobCodec
import com.littlekitty.xiaokele.core.database.repository.BehaviorPatchOutcome
import com.littlekitty.xiaokele.core.database.repository.BehaviorTrainer
import com.littlekitty.xiaokele.core.database.repository.MemoryRepository
import com.littlekitty.xiaokele.core.database.repository.MemoryWriteReport
import com.littlekitty.xiaokele.core.database.repository.UserTraitPatchOutcome
import com.littlekitty.xiaokele.core.database.repository.UserTraitRepository
import com.littlekitty.xiaokele.core.network.embedding.EmbeddingResult
import com.littlekitty.xiaokele.core.network.embedding.EmbeddingService
import com.littlekitty.xiaokele.core.network.memory.MemoryEvaluationMessage
import com.littlekitty.xiaokele.core.network.memory.MemoryEvaluationExistingMemory
import com.littlekitty.xiaokele.core.network.memory.MemoryEvaluationRequest
import com.littlekitty.xiaokele.core.network.memory.MemoryEvaluationResult
import com.littlekitty.xiaokele.core.network.memory.MemoryEvaluator
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import com.littlekitty.xiaokele.settings.RouteConfigurationBarrier
import com.littlekitty.xiaokele.provider.ReviewKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

data class MemoryAnalysisRequest(
    val userMessageId: String,
    val assistantMessageId: String,
    val eventId: String,
    val rawDecisionJson: String,
    val analysisMode: String = "EXPLICIT_TURN",
    /** Persistent session/consolidation scope; empty keeps the legacy recent window. */
    val sourceMessageIds: List<String> = emptyList(),
    val sourcePayloadJson: String? = null,
    /** Claim timestamp used to reject a stale settlement after lease recovery. */
    val settlementClaimUpdatedAt: Long? = null,
)

sealed interface MemoryAnalysisOutcome {
    data class Applied(
        val memoryWriteReport: MemoryWriteReport,
        val traitOutcomes: List<UserTraitPatchOutcome>,
        val behaviorOutcomes: List<BehaviorPatchOutcome>,
        val embeddingCount: Int,
        val embeddingDegraded: Boolean,
    ) : MemoryAnalysisOutcome

    data class Failure(
        val error: AppError,
    ) : MemoryAnalysisOutcome
}

/**
 * Post-reply async boundary (FR-CHAT-08, FR-MEM-01/09). Enqueue must return
 * immediately; the chat transaction is already committed and must never be
 * rolled back because analysis fails.
 */
interface MemoryAnalysisHook {
    fun enqueue(request: MemoryAnalysisRequest)
}

class AsyncMemoryAnalysisHook(
    private val processor: MemoryAnalysisProcessor,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : MemoryAnalysisHook {

    override fun enqueue(request: MemoryAnalysisRequest) {
        scope.launch {
            runCatching { processor.analyze(request) }
        }
    }
}

object NoOpMemoryAnalysisHook : MemoryAnalysisHook {
    override fun enqueue(request: MemoryAnalysisRequest) = Unit
}

/**
 * Local learning pipeline:
 * MemoryEvaluator -> deterministic Validator -> one Room transaction for
 * memories/user traits/behavior tendencies -> best-effort remote embedding.
 * Any failure here is non-blocking and does not touch the already persisted
 * chat turn.
 */
class MemoryAnalysisProcessor(
    private val db: AppDatabase,
    private val memoryEvaluator: MemoryEvaluator,
    private val memoryRepository: MemoryRepository,
    private val userTraitRepository: UserTraitRepository,
    private val behaviorTrainer: BehaviorTrainer,
    private val embeddingService: EmbeddingService,
    private val timeProvider: TimeProvider,
    private val diagnosticsLogger: DiagnosticsLogger? = null,
    private val memoryReviewGateway: MemoryReviewGateway? = null,
) {

    suspend fun analyze(request: MemoryAnalysisRequest): MemoryAnalysisOutcome {
        val now = timeProvider.now().toEpochMilli()
        // A deleted source is never allowed to remain retrievable merely
        // because its old embedding survived. This is deterministic and does
        // not spend a model request.
        reconcileDeletedSources(now)
        val userMessage = db.messageDao().byId(request.userMessageId)
        val assistantMessage = db.messageDao().byId(request.assistantMessageId)
        if (userMessage == null || assistantMessage == null) {
            return MemoryAnalysisOutcome.Failure(
                AppError(ErrorCode.INVALID_ARGUMENT, technicalDetail = "memory_analysis_turn_missing"),
            )
        }
        if (assistantMessage.errorCode == com.littlekitty.xiaokele.provider.CompanionFallback.MESSAGE_MARKER) {
            return MemoryAnalysisOutcome.Failure(AppError(ErrorCode.INVALID_ARGUMENT, technicalDetail = "fallback_not_memory_evidence"))
        }
        val activeMemories = db.memoryDao().activeAt(now)
        val activeMemoryCount = activeMemories.size

        val evaluatorInput = MemoryEvaluationRequest(
            eventId = request.eventId,
            eventType = request.analysisMode,
            userMessage = userMessage.toEvaluationMessage(),
            assistantReply = assistantMessage.toEvaluationMessage(),
            recentMessages = (if (request.sourceMessageIds.isEmpty()) {
                db.messageDao().recentAsc(RECENT_MESSAGE_LIMIT)
            } else {
                request.sourceMessageIds.mapNotNull { db.messageDao().byId(it) }
            })
                .filter { it.id != request.assistantMessageId && it.id != request.userMessageId &&
                    it.errorCode != com.littlekitty.xiaokele.provider.CompanionFallback.MESSAGE_MARKER }
                .let { if (request.sourceMessageIds.isEmpty()) it.takeLast(RECENT_MESSAGE_LIMIT) else it }
                .map { it.toEvaluationMessage() },
            activeMemoryCount = activeMemoryCount,
            activeMemories = activeMemories.take(MAX_ACTIVE_MEMORIES_FOR_EXPLICIT_ANALYSIS).map { memory ->
                MemoryEvaluationExistingMemory(
                    id = memory.id,
                    type = memory.type,
                    subject = memory.subject,
                    content = memory.content,
                    summary = memory.summary,
                )
            },
        )

        val evaluatorRequestBody = JsonCodec.encode(evaluatorInput)
        diagnosticsLogger?.record(
            category = DiagnosticLogCategories.AI,
            name = DiagnosticLogNames.MEMORY_ANALYSIS,
            status = DiagnosticLogStatuses.STARTED,
            subjectId = request.eventId,
            summary = "开始记忆评估：用户明确记住/忘记/纠正信号",
            payloadJson = stagePayload(request.eventId, "MEMORY_ANALYSIS_STARTED") {
                put("user_message_id", request.userMessageId)
                put("assistant_message_id", request.assistantMessageId)
                put("raw_evaluator_input", evaluatorRequestBody)
            },
        )
        diagnosticsLogger?.aiRequestStarted(
            operation = "memory_evaluation",
            correlationId = request.eventId,
            subjectId = request.eventId,
            serviceName = memoryEvaluator.serviceName,
            requestBody = evaluatorRequestBody,
            requestKind = "memory_evaluation_request",
            attempt = 1,
        )

        val evaluatorResult = runCatching {
            memoryEvaluator.evaluate(evaluatorInput)
        }.getOrElse { throwable ->
            diagnosticsLogger?.aiResponseRecorded(
                operation = "memory_evaluation",
                correlationId = request.eventId,
                subjectId = request.eventId,
                status = DiagnosticLogStatuses.FAILED,
                trace = null,
                fallbackRequestBody = evaluatorRequestBody,
                errorCode = ErrorCode.MEMORY_EVALUATION_INVALID.name,
                technicalDetail = "memory_evaluator_threw:${throwable.message}",
                attempt = 1,
            )
            return MemoryAnalysisOutcome.Failure(
                AppError(
                    code = ErrorCode.MEMORY_EVALUATION_INVALID,
                    technicalDetail = "memory_evaluator_threw:${throwable.message}",
                ),
            )
        }

        return when (val result = evaluatorResult) {
            is MemoryEvaluationResult.Failure -> {
                diagnosticsLogger?.aiResponseRecorded(
                    operation = "memory_evaluation",
                    correlationId = request.eventId,
                    subjectId = request.eventId,
                    status = DiagnosticLogStatuses.FAILED,
                    trace = result.trace,
                    fallbackRequestBody = evaluatorRequestBody,
                    errorCode = result.error.code.name,
                    technicalDetail = result.error.technicalDetail,
                    attempt = 1,
                )
                MemoryAnalysisOutcome.Failure(result.error)
            }
            is MemoryEvaluationResult.Success -> {
                diagnosticsLogger?.aiResponseRecorded(
                    operation = "memory_evaluation",
                    correlationId = request.eventId,
                    subjectId = request.eventId,
                    status = DiagnosticLogStatuses.SUCCESS,
                    trace = result.trace,
                    fallbackRequestBody = evaluatorRequestBody,
                    normalizedOutput = result.rawMemoryEvaluationJson,
                    attempt = 1,
                )
                val normalizedOutput = normalizeMemoryEvaluationJson(result.rawMemoryEvaluationJson)
                when (val validation = MemoryEvaluationValidator.validate(normalizedOutput)) {
                    is MemoryEvaluationValidation.Valid ->
                        reviewAndApply(
                            rawMemoryEvaluationJson = normalizedOutput,
                            validation = validation,
                            now = now,
                            correlationId = request.eventId,
                            sourcePayloadJson = request.sourcePayloadJson ?: evaluatorRequestBody,
                            allowedMessageIds = buildSet {
                                add(request.userMessageId)
                                add(request.assistantMessageId)
                                addAll(evaluatorInput.recentMessages.map { it.id })
                            },
                            allowedMemoryIds = evaluatorInput.activeMemories.map { it.id }.toSet(),
                            // Explicit turns still send the user/assistant pair
                            // and recent context as evidence. Re-check every
                            // such source before commit; an empty request list
                            // must not disable deletion-race protection.
                            requiredSourceMessageIds = request.sourceMessageIds.toSet().ifEmpty {
                                buildSet {
                                    add(request.userMessageId)
                                    add(request.assistantMessageId)
                                    addAll(evaluatorInput.recentMessages.map { it.id })
                                }
                            },
                            settlementClaimUpdatedAt = request.settlementClaimUpdatedAt,
                            upstreamCommitGuard = result.commitGuard,
                        )

                    is MemoryEvaluationValidation.Invalid ->
                        retryAfterInvalidOutput(
                            baseRequest = evaluatorInput,
                            firstValidation = validation,
                            now = now,
                            correlationId = request.eventId,
                            settlementClaimUpdatedAt = request.settlementClaimUpdatedAt,
                        )
                }
            }
        }
    }

    private suspend fun retryAfterInvalidOutput(
        baseRequest: MemoryEvaluationRequest,
        firstValidation: MemoryEvaluationValidation.Invalid,
        now: Long,
        correlationId: String,
        settlementClaimUpdatedAt: Long? = null,
    ): MemoryAnalysisOutcome {
        val violations = firstValidation.violations.joinToString("; ") { "${it.path}:${it.message}" }
        diagnosticsLogger?.record(
            category = DiagnosticLogCategories.AI,
            name = DiagnosticLogNames.MEMORY_ANALYSIS,
            status = DiagnosticLogStatuses.RETRY,
            subjectId = correlationId,
            summary = "MemoryEvaluation 本地校验失败，发起一次格式修复重试",
            payloadJson = stagePayload(correlationId, "MEMORY_EVALUATION_RETRY_STARTED") {
                put("attempt", 1)
                put("violations", violations)
                put("retry_policy", "FORMAT_ONLY_ONCE")
            },
        )

        val retryRequest = baseRequest.copy(formatRepairInstruction = FORMAT_REPAIR_INSTRUCTION)
        val retryRequestBody = JsonCodec.encode(retryRequest)
        diagnosticsLogger?.aiRequestStarted(
            operation = "memory_evaluation",
            correlationId = correlationId,
            subjectId = correlationId,
            serviceName = memoryEvaluator.serviceName,
            requestBody = retryRequestBody,
            requestKind = "memory_evaluation_format_repair",
            attempt = 2,
        )

        val retryResult = runCatching {
            memoryEvaluator.evaluate(retryRequest)
        }.getOrElse { throwable ->
            diagnosticsLogger?.aiResponseRecorded(
                operation = "memory_evaluation",
                correlationId = correlationId,
                subjectId = correlationId,
                status = DiagnosticLogStatuses.FAILED,
                trace = null,
                fallbackRequestBody = retryRequestBody,
                errorCode = ErrorCode.MEMORY_EVALUATION_INVALID.name,
                technicalDetail = "memory_evaluator_retry_threw:${throwable.message}",
                attempt = 2,
            )
            return MemoryAnalysisOutcome.Failure(
                AppError(ErrorCode.MEMORY_EVALUATION_INVALID, technicalDetail = "memory_evaluator_retry_threw:${throwable.message}"),
            )
        }

        return when (retryResult) {
            is MemoryEvaluationResult.Failure -> {
                diagnosticsLogger?.aiResponseRecorded(
                    operation = "memory_evaluation",
                    correlationId = correlationId,
                    subjectId = correlationId,
                    status = DiagnosticLogStatuses.FAILED,
                    trace = retryResult.trace,
                    fallbackRequestBody = retryRequestBody,
                    errorCode = retryResult.error.code.name,
                    technicalDetail = retryResult.error.technicalDetail,
                    attempt = 2,
                )
                MemoryAnalysisOutcome.Failure(retryResult.error)
            }

            is MemoryEvaluationResult.Success -> {
                diagnosticsLogger?.aiResponseRecorded(
                    operation = "memory_evaluation",
                    correlationId = correlationId,
                    subjectId = correlationId,
                    status = DiagnosticLogStatuses.SUCCESS,
                    trace = retryResult.trace,
                    fallbackRequestBody = retryRequestBody,
                    normalizedOutput = retryResult.rawMemoryEvaluationJson,
                    attempt = 2,
                )
                val normalizedRetryOutput = normalizeMemoryEvaluationJson(retryResult.rawMemoryEvaluationJson)
                val retryValidation = MemoryEvaluationValidator.validate(normalizedRetryOutput)
                when (retryValidation) {
                    is MemoryEvaluationValidation.Valid ->
                        reviewAndApply(
                            rawMemoryEvaluationJson = normalizedRetryOutput,
                            validation = retryValidation,
                            now = now,
                            correlationId = correlationId,
                            sourcePayloadJson = JsonCodec.encode(baseRequest),
                            allowedMessageIds = buildSet {
                                add(baseRequest.userMessage.id)
                                add(baseRequest.assistantReply.id)
                                addAll(baseRequest.recentMessages.map { it.id })
                            },
                            allowedMemoryIds = baseRequest.activeMemories.map { it.id }.toSet(),
                            requiredSourceMessageIds = baseRequest.recentMessages.map { it.id }.toSet() +
                                setOf(baseRequest.userMessage.id, baseRequest.assistantReply.id),
                            settlementClaimUpdatedAt = settlementClaimUpdatedAt,
                            upstreamCommitGuard = retryResult.commitGuard,
                        )

                    is MemoryEvaluationValidation.Invalid -> {
                        diagnosticsLogger?.record(
                            category = DiagnosticLogCategories.AI,
                            name = DiagnosticLogNames.MEMORY_ANALYSIS,
                            status = DiagnosticLogStatuses.FAILED,
                            subjectId = correlationId,
                            summary = "MemoryEvaluation 格式修复重试后仍未通过本地校验",
                            payloadJson = stagePayload(correlationId, "MEMORY_EVALUATION_RETRY_EXHAUSTED") {
                                put("attempt", 2)
                                put("violations", retryValidation.violations.joinToString("; ") { "${it.path}:${it.message}" })
                            },
                        )
                        MemoryAnalysisOutcome.Failure(
                            AppError(
                                ErrorCode.MEMORY_EVALUATION_INVALID,
                                technicalDetail = retryValidation.violations.joinToString("; ") { "${it.path}:${it.message}" },
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * C review boundary for consolidation/session D output. The legacy
     * applyRawEvaluation API remains a local test/import seam, but every
     * production D result goes through this method before it can mutate Room.
     */
    suspend fun reviewAndApplyRawEvaluation(
        rawMemoryEvaluationJson: String,
        sourcePayloadJson: String,
        allowedMessageIds: Set<String>,
        now: Long = timeProvider.now().toEpochMilli(),
        correlationId: String = "memory_review",
        upstreamCommitGuard: () -> Boolean = { true },
        commitCallback: suspend () -> Unit = {},
    ): MemoryAnalysisOutcome {
        val normalized = normalizeMemoryEvaluationJson(rawMemoryEvaluationJson)
        return when (val validation = validateWithPartialSalvage(normalized)) {
            is MemoryEvaluationValidation.Invalid -> MemoryAnalysisOutcome.Failure(
                AppError(
                    ErrorCode.MEMORY_EVALUATION_INVALID,
                    technicalDetail = validation.violations.joinToString("; ") { "${it.path}:${it.message}" },
                ),
            )
            is MemoryEvaluationValidation.Valid -> reviewAndApply(
                rawMemoryEvaluationJson = normalized,
                validation = validation,
                now = now,
                correlationId = correlationId,
                sourcePayloadJson = sourcePayloadJson,
                allowedMessageIds = allowedMessageIds,
                allowedMemoryIds = db.memoryDao().activeAt(now).map { it.id }.toSet(),
                requiredSourceMessageIds = allowedMessageIds,
                upstreamCommitGuard = upstreamCommitGuard,
                commitCallback = commitCallback,
            )
        }
    }

    /** Archives source-bound memories whose original message was deleted. */
    suspend fun reconcileDeletedSources(now: Long = timeProvider.now().toEpochMilli()): Int = db.withTransaction {
        var archived = 0
        db.memoryDao().all()
            .filter { it.status == com.littlekitty.xiaokele.core.database.state.MemoryStatuses.ACTIVE }
            .forEach { memory ->
                val sourceId = memory.sourceMessageId ?: return@forEach
                if (db.messageDao().byId(sourceId) == null &&
                    db.memoryDao().transition(
                        memory.id,
                        com.littlekitty.xiaokele.core.database.state.MemoryStatuses.ACTIVE,
                        com.littlekitty.xiaokele.core.database.state.MemoryStatuses.ARCHIVED,
                        now,
                    ) == 1
                ) {
                    db.memoryEmbeddingDao().deleteForMemory(memory.id)
                    archived++
                }
            }
        archived
    }

    private suspend fun reviewAndApply(
        rawMemoryEvaluationJson: String,
        validation: MemoryEvaluationValidation.Valid,
        now: Long,
        correlationId: String,
        sourcePayloadJson: String,
        allowedMessageIds: Set<String>,
        allowedMemoryIds: Set<String>,
        requiredSourceMessageIds: Set<String> = allowedMessageIds,
        settlementClaimUpdatedAt: Long? = null,
        upstreamCommitGuard: () -> Boolean,
        commitCallback: suspend () -> Unit = {},
    ): MemoryAnalysisOutcome {
        if (!upstreamCommitGuard()) {
            recordReviewFailure(correlationId, "MEMORY_REVIEW_STALE_D", "stale_model_route")
            return MemoryAnalysisOutcome.Failure(
                AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "stale_model_route"),
            )
        }
        val evidenceViolation = validateEvidenceReferences(
            validation.evaluation,
            allowedMessageIds,
            allowedMemoryIds,
        )
        if (evidenceViolation != null) {
            recordReviewFailure(correlationId, "MEMORY_EVIDENCE_INVALID", evidenceViolation)
            return MemoryAnalysisOutcome.Failure(
                AppError(ErrorCode.MEMORY_EVALUATION_INVALID, technicalDetail = evidenceViolation),
            )
        }
        val review = memoryReviewGateway?.review(
            MemoryReviewRequest(
                correlationId = correlationId,
                rawEvaluationJson = rawMemoryEvaluationJson,
                sourcePayloadJson = reviewSourcePayload(
                    sourcePayloadJson = sourcePayloadJson,
                    evaluation = validation.evaluation,
                    allowedMemoryIds = allowedMemoryIds,
                    now = now,
                ),
                allowedEvidenceIds = allowedMessageIds + allowedMemoryIds,
                reviewKind = if (validation.evaluation.memoryCandidates.any { it.type == MemoryTypeNames.RELATIONSHIP_EVENT }) {
                    ReviewKind.RELATIONSHIP_CONFIRMATION
                } else {
                    ReviewKind.MEMORY_CANDIDATE
                },
            ),
        ) ?: MemoryReviewResult.Passed()
        when (review) {
            is MemoryReviewResult.Passed -> {
                if (!review.commitGuard()) {
                    recordReviewFailure(correlationId, "MEMORY_REVIEW_STALE_ROUTE", "stale_model_route")
                    return MemoryAnalysisOutcome.Failure(
                        AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "stale_model_route"),
                    )
                }
                diagnosticsLogger?.record(
                    category = DiagnosticLogCategories.AI,
                    name = DiagnosticLogNames.MEMORY_ANALYSIS,
                    status = DiagnosticLogStatuses.SUCCESS,
                    subjectId = correlationId,
                    summary = "独立 C_REVIEW 通过，提交 D_MEMORY 候选",
                    payloadJson = stagePayload(correlationId, "MEMORY_REVIEW_PASS"),
                )
                return applyRawEvaluation(
                    rawMemoryEvaluationJson = rawMemoryEvaluationJson,
                    now = now,
                    correlationId = correlationId,
                    commitGuard = { upstreamCommitGuard() && review.commitGuard() },
                    sourceMessageIds = requiredSourceMessageIds,
                    settlementClaimUpdatedAt = settlementClaimUpdatedAt,
                    commitCallback = commitCallback,
                )
            }

            is MemoryReviewResult.Blocked -> {
                val detail = "memory_review_${review.verdict.lowercase()}:" +
                    review.issues.joinToString(";") { "${it.location}:${it.minimumChange}" }
                recordReviewFailure(correlationId, "MEMORY_REVIEW_${review.verdict}", detail)
                return MemoryAnalysisOutcome.Failure(
                    AppError(ErrorCode.MEMORY_EVALUATION_INVALID, technicalDetail = detail),
                )
            }

            is MemoryReviewResult.Failed -> {
                recordReviewFailure(correlationId, "MEMORY_REVIEW_FAILED", review.error.technicalDetail)
                return MemoryAnalysisOutcome.Failure(review.error)
            }
        }
    }

    /**
     * Consolidation/session callers provide the original message range, while
     * D may also cite an existing memory. Give C the referenced memory's
     * content too; an allowlisted ID alone is not enough for independent
     * factual/conflict review.
     */
    private suspend fun reviewSourcePayload(
        sourcePayloadJson: String,
        evaluation: MemoryEvaluation,
        allowedMemoryIds: Set<String>,
        now: Long,
    ): String {
        val referencedIds = buildSet {
            addAll(evaluation.userTraitPatches.flatMap { it.evidenceMemoryIds })
            addAll(evaluation.behaviorPatches.flatMap { it.evidenceMemoryIds })
            addAll(evaluation.deactivateMemoryIds)
            evaluation.memoryCandidates.mapNotNullTo(this) { it.supersedesMemoryId }
        }.filter { it in allowedMemoryIds }
        if (referencedIds.isEmpty()) return sourcePayloadJson
        val memories = db.memoryDao().activeAt(now)
            .filter { it.id in referencedIds }
            .map { memory ->
                MemoryEvaluationExistingMemory(
                    id = memory.id,
                    type = memory.type,
                    subject = memory.subject,
                    content = memory.content,
                    summary = memory.summary,
                )
            }
        return buildString {
            append(sourcePayloadJson)
            append("\n\nactive_memory_evidence=")
            append(JsonCodec.encode(memories))
        }
    }

    private suspend fun validateEvidenceReferences(
        evaluation: MemoryEvaluation,
        allowedMessageIds: Set<String>,
        allowedMemoryIds: Set<String>,
    ): String? {
        // Assistant rows are context, not independent user evidence.  In
        // particular, an HER promise or a model's own recap must never be
        // sufficient to create a memory/relationship fact or an AGREED state.
        // Keep mixed evidence usable (the user row remains the authority), but
        // reject assistant-only candidates deterministically before C or Room.
        val userEvidenceIds = mutableSetOf<String>()
        for (id in allowedMessageIds) {
            if (db.messageDao().byId(id)?.role?.equals("USER", ignoreCase = true) == true) {
                userEvidenceIds += id
            }
        }
        evaluation.memoryCandidates.forEachIndexed { index, candidate ->
            if (candidate.evidenceMessageIds.isEmpty() && candidate.sourceMessageId == null) {
                return "memory_candidates[$index].evidence_message_ids:missing_source"
            }
            candidate.evidenceMessageIds.forEach { id ->
                if (id !in allowedMessageIds) return "memory_candidates[$index].evidence_message_ids:$id"
            }
            candidate.sourceMessageId?.let { id ->
                if (id !in allowedMessageIds) return "memory_candidates[$index].source_message_id:$id"
                if (id !in userEvidenceIds) {
                    return "memory_candidates[$index].source_message_id:must_be_user_message"
                }
            }
            val candidateEvidenceIds = candidate.evidenceMessageIds + listOfNotNull(candidate.sourceMessageId)
            if (candidate.sourceMessageId == null && candidateEvidenceIds.none { it in userEvidenceIds }) {
                return "memory_candidates[$index].evidence_message_ids:requires_user_message_evidence"
            }
            candidate.supersedesMemoryId?.let { id ->
                if (id !in allowedMemoryIds) return "memory_candidates[$index].supersedes_memory_id:$id"
            }
        }
        evaluation.patternObservations.forEachIndexed { index, observation ->
            observation.evidenceMessageIds.forEach { id ->
                if (id !in allowedMessageIds) return "pattern_observations[$index].evidence_message_ids:$id"
            }
            if (observation.evidenceMessageIds.none { it in userEvidenceIds }) {
                return "pattern_observations[$index].evidence_message_ids:requires_user_message_evidence"
            }
        }
        evaluation.userTraitPatches.forEachIndexed { index, patch ->
            patch.evidenceMemoryIds.forEach { id ->
                if (id !in allowedMemoryIds && id !in allowedMessageIds) return "user_trait_patches[$index].evidence_memory_ids:$id"
            }
        }
        evaluation.behaviorPatches.forEachIndexed { index, patch ->
            patch.evidenceMemoryIds.forEach { id ->
                if (id !in allowedMemoryIds && id !in allowedMessageIds) return "behavior_patches[$index].evidence_memory_ids:$id"
            }
        }
        evaluation.deactivateMemoryIds.forEach { id ->
            if (id !in allowedMemoryIds) return "deactivate_memory_ids:$id"
        }
        return null
    }

    private suspend fun recordReviewFailure(correlationId: String, marker: String, detail: String?) {
        diagnosticsLogger?.record(
            category = DiagnosticLogCategories.AI,
            name = DiagnosticLogNames.MEMORY_ANALYSIS,
            status = DiagnosticLogStatuses.FAILED,
            subjectId = correlationId,
            summary = "D_MEMORY 未通过独立 C_REVIEW，未提交候选",
            payloadJson = stagePayload(correlationId, marker) {
                detail?.let { put("detail", it) }
            },
        )
    }

    /** Applies the optional learning portion returned by conversation consolidation. */
    suspend fun applyRawEvaluation(
        rawMemoryEvaluationJson: String,
        now: Long = timeProvider.now().toEpochMilli(),
        correlationId: String = "memory_evaluation_manual",
        commitGuard: (() -> Boolean)? = null,
        sourceMessageIds: Set<String>? = null,
        settlementClaimUpdatedAt: Long? = null,
        commitCallback: suspend () -> Unit = {},
    ): MemoryAnalysisOutcome {
        val normalizedInput = normalizeMemoryEvaluationJson(rawMemoryEvaluationJson)
        return when (val validation = validateWithPartialSalvage(normalizedInput)) {
        is MemoryEvaluationValidation.Invalid -> {
            val detail = validation.violations.joinToString("; ") { "${it.path}:${it.message}" }
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.AI,
                name = DiagnosticLogNames.MEMORY_ANALYSIS,
                status = DiagnosticLogStatuses.FAILED,
                subjectId = correlationId,
                summary = "MemoryEvaluation 本地校验失败",
                payloadJson = stagePayload(correlationId, "MEMORY_EVALUATION_INVALID") {
                    put("raw_output", rawMemoryEvaluationJson)
                    if (normalizedInput != rawMemoryEvaluationJson) {
                        put("locally_normalized_output", normalizedInput)
                    }
                    put("violations", detail)
                },
            )
            MemoryAnalysisOutcome.Failure(
                AppError(
                    code = ErrorCode.MEMORY_EVALUATION_INVALID,
                    technicalDetail = detail,
                ),
            )
        }

        is MemoryEvaluationValidation.Valid -> {
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.AI,
                name = DiagnosticLogNames.MEMORY_ANALYSIS,
                status = DiagnosticLogStatuses.SUCCESS,
                subjectId = correlationId,
                summary = "MemoryEvaluation 本地校验通过，开始应用记忆变更",
                payloadJson = stagePayload(correlationId, "MEMORY_EVALUATION_VALID") {
                    put("normalized_evaluation", normalizedInput)
                    if (validation.warnings.isNotEmpty()) {
                        put("warnings", validation.warnings.joinToString("; "))
                    }
                },
            )
            val applied = runCatching {
                applyValidatedEvaluation(
                    validation = validation,
                    now = now,
                    commitGuard = commitGuard,
                    settlingSessionId = correlationId
                        .takeIf { it.startsWith("session:") }
                        ?.removePrefix("session:")
                        ?.takeIf(String::isNotBlank),
                    settlementClaimUpdatedAt = settlementClaimUpdatedAt,
                    sourceMessageIds = sourceMessageIds,
                    commitCallback = commitCallback,
                )
            }.getOrElse { throwable ->
                diagnosticsLogger?.record(
                    category = DiagnosticLogCategories.AI,
                    name = DiagnosticLogNames.MEMORY_ANALYSIS,
                    status = DiagnosticLogStatuses.FAILED,
                    subjectId = correlationId,
                    summary = "记忆评估通过但本地变更应用失败",
                    payloadJson = stagePayload(correlationId, "MEMORY_APPLY_FAILED") {
                        put("error_code", ErrorCode.LOCAL_STORAGE_FAILURE.name)
                        put("technical_detail", throwable.message ?: "memory apply failed")
                    },
                )
                return MemoryAnalysisOutcome.Failure(
                    AppError(
                        code = ErrorCode.LOCAL_STORAGE_FAILURE,
                        technicalDetail = throwable.message,
                    ),
                )
            }
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.AI,
                name = DiagnosticLogNames.MEMORY_ANALYSIS,
                status = DiagnosticLogStatuses.SUCCESS,
                subjectId = correlationId,
                summary = "记忆、画像和行为倾向变更已应用",
                payloadJson = stagePayload(correlationId, "MEMORY_APPLIED") {
                    put("memory_ids", applied.memoryReport.memoryIds.joinToString(","))
                    put("embedding_count", applied.embeddingCount)
                    put("embedding_degraded", applied.embeddingDegraded)
                },
            )
            MemoryAnalysisOutcome.Applied(
                memoryWriteReport = applied.memoryReport,
                traitOutcomes = applied.traitOutcomes,
                behaviorOutcomes = applied.behaviorOutcomes,
                embeddingCount = applied.embeddingCount,
                embeddingDegraded = applied.embeddingDegraded,
            )
        }
        }
    }

    /**
     * A malformed optional item must not discard unrelated valid learning.
     * Every top-level array item is revalidated in isolation; only records
     * that satisfy the same deterministic validator are retained.
     */
    private fun validateWithPartialSalvage(raw: String): MemoryEvaluationValidation {
        val original = MemoryEvaluationValidator.validate(raw)
        if (original is MemoryEvaluationValidation.Valid) return original
        val root = runCatching { JsonCodec.json.parseToJsonElement(raw) as? JsonObject }
            .getOrNull() ?: return original

        var salvaged = MemoryEvaluation()
        var accepted = 0
        var rejected = 0
        EVALUATION_ARRAY_FIELDS.forEach { field ->
            val values = root[field] as? JsonArray
            if (values == null) {
                if (root.containsKey(field)) rejected++
                return@forEach
            }
            values.forEach { value ->
                val isolated = buildJsonObject {
                    EVALUATION_ARRAY_FIELDS.forEach { knownField ->
                        put(knownField, buildJsonArray {
                            if (knownField == field) add(value)
                        })
                    }
                }.toString()
                when (val itemValidation = MemoryEvaluationValidator.validate(isolated)) {
                    is MemoryEvaluationValidation.Invalid -> rejected++
                    is MemoryEvaluationValidation.Valid -> {
                        accepted++
                        salvaged = salvaged.merge(itemValidation.evaluation)
                    }
                }
            }
        }
        if (accepted == 0) return original
        return MemoryEvaluationValidation.Valid(
            evaluation = salvaged,
            warnings = listOf("PARTIAL_SALVAGE accepted=$accepted rejected=$rejected"),
        )
    }

    /**
     * Translates the operation-shaped answer this evaluator actually receives
     * into the shape the contract expects.
     *
     * The prompt now leads with the canonical schema, and the model still
     * replies with `{"memoryEvaluation":{"operations":[{"operation":"new",...}]}}`
     * — a shape it produces reliably. Rather than keep asking the model to
     * change dialect, map the reliable dialect onto the canonical one. Nothing
     * is invented: `content`, `evidenceId` and `validity` all come from the
     * reply; the two required scores are derived from the model's own validity
     * grading rather than guessed.
     */
    private fun translateOperationsShape(raw: String): String? = runCatching {
        val root = JsonCodec.json.parseToJsonElement(raw) as? JsonObject ?: return null
        if (root.containsKey("memory_candidates")) return null
        val inner = root["memoryEvaluation"] as? JsonObject ?: return null
        val operations = inner["operations"] as? JsonArray ?: return null

        val candidates = buildJsonArray {
            operations.forEach { operation ->
                val entry = operation as? JsonObject ?: return@forEach
                val evidenceId = entry.stringField("evidenceId") ?: return@forEach
                val content = entry.stringField("content") ?: return@forEach
                add(buildJsonObject {
                    put("type", memoryTypeFor(entry.stringField("contentType"), entry.stringField("validity")))
                    entry.stringField("subject")?.uppercase()?.let { put("subject", it) }
                    put("content", content)
                    entry.stringField("notes")?.let { put("summary", it.take(500)) }
                    put("importance", importanceFor(entry.stringField("validity")))
                    put("confidence", OPERATIONS_SHAPE_CONFIDENCE)
                    put("validity", validityFor(entry.stringField("validity")))
                    put("evidence_message_ids", buildJsonArray { add(JsonPrimitive(evidenceId)) })
                    put("source_message_id", evidenceId)
                })
            }
        }
        buildJsonObject {
            put("memory_candidates", candidates)
            put("user_trait_patches", JsonArray(emptyList()))
            put("behavior_patches", JsonArray(emptyList()))
            put("deactivate_memory_ids", JsonArray(emptyList()))
            put("pattern_observations", JsonArray(emptyList()))
        }.toString()
    }.getOrNull()

    private fun JsonObject.stringField(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** Maps the model's free-text content type onto the four declared types. */
    private fun memoryTypeFor(contentType: String?, validity: String?): String {
        val text = listOfNotNull(contentType, validity).joinToString(" ").lowercase()
        return when {
            "relationship" in text || "关系" in text -> MemoryTypeNames.RELATIONSHIP_EVENT
            "preference" in text || "偏好" in text || "喜好" in text -> MemoryTypeNames.USER_PREFERENCE
            "ephemeral" in text || "transient" in text || "temporary" in text || "临时" in text ||
                "situational" in text || "状态" in text -> MemoryTypeNames.TEMPORARY_STATE
            else -> MemoryTypeNames.SHORT_TERM_MEMORY
        }
    }

    private fun validityFor(validity: String?): String {
        val text = validity.orEmpty().lowercase()
        return when {
            "long" in text || "permanent" in text || "长期" in text -> MemoryValidityNames.LONG_TERM
            "short" in text || "短期" in text -> MemoryValidityNames.SHORT_TERM
            else -> MemoryValidityNames.TEMPORARY
        }
    }

    /** Derived, not guessed: durable facts matter more than transient states. */
    private fun importanceFor(validity: String?): Double = when (validityFor(validity)) {
        MemoryValidityNames.LONG_TERM -> 0.7
        MemoryValidityNames.SHORT_TERM -> 0.5
        else -> 0.3
    }

    /**
     * Keeps the persisted contract object-shaped while tolerating a common
     * provider mistake: relationship fields returned as scalar strings.
     * This is local normalization only; the raw provider output remains
     * represented by the AI response hash in diagnostics.
     */
    private fun normalizeMemoryEvaluationJson(raw: String): String = runCatching {
        translateOperationsShape(raw)?.let { return it }
        val root = JsonCodec.json.parseToJsonElement(raw) as? JsonObject ?: return raw
        val candidates = root["memory_candidates"] as? JsonArray ?: return raw
        buildJsonObject {
            root.forEach { (key, value) ->
                if (key != "memory_candidates") {
                    put(key, value)
                    return@forEach
                }
                put(key, buildJsonArray {
                    candidates.forEach { candidate ->
                        val candidateObject = candidate as? JsonObject
                        if (candidateObject == null) {
                            add(candidate)
                        } else {
                            add(buildJsonObject {
                                candidateObject.forEach { (field, fieldValue) ->
                                    if (field in RELATIONSHIP_JSON_OBJECT_FIELDS &&
                                        fieldValue !is JsonObject && fieldValue !is JsonNull
                                    ) {
                                        put(field, buildJsonObject { put("value", fieldValue) })
                                    } else {
                                        put(field, fieldValue)
                                    }
                                }
                            })
                        }
                    }
                })
            }
        }.toString()
    }.getOrDefault(raw)

    private fun MemoryEvaluation.merge(other: MemoryEvaluation): MemoryEvaluation = copy(
        memoryCandidates = memoryCandidates + other.memoryCandidates,
        userTraitPatches = userTraitPatches + other.userTraitPatches,
        behaviorPatches = behaviorPatches + other.behaviorPatches,
        deactivateMemoryIds = deactivateMemoryIds + other.deactivateMemoryIds,
        patternObservations = patternObservations + other.patternObservations,
    )

    private fun stagePayload(
        correlationId: String,
        marker: String,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): String = buildJsonObject {
        put("marker", marker)
        put("correlation_id", correlationId)
        fields()
    }.toString()

    private suspend fun applyValidatedEvaluation(
        validation: MemoryEvaluationValidation.Valid,
        now: Long,
        commitGuard: (() -> Boolean)? = null,
        settlingSessionId: String? = null,
        settlementClaimUpdatedAt: Long? = null,
        sourceMessageIds: Set<String>? = null,
        commitCallback: suspend () -> Unit = {},
    ): AppliedParts = RouteConfigurationBarrier.withReadSuspend {
        db.withTransaction {
        check(commitGuard?.invoke() != false) { "stale_model_route_during_memory_persist" }
        sourceMessageIds?.let { ids ->
            val currentMessageIds = db.messageDao().allAscending().mapTo(mutableSetOf<String>()) { it.id }
            check(ids.all { it in currentMessageIds }) { "memory_source_deleted_before_commit" }
        }
        validation.evaluation.deactivateMemoryIds.forEach { memoryId ->
            val memory = db.memoryDao().byId(memoryId) ?: return@forEach
            if (memory.status == com.littlekitty.xiaokele.core.database.state.MemoryStatuses.ACTIVE) {
                com.littlekitty.xiaokele.core.database.state.MemoryStateMachine.requireTransition(
                    memory.status,
                    com.littlekitty.xiaokele.core.database.state.MemoryStatuses.ARCHIVED,
                )
                db.memoryDao().transition(
                    memory.id,
                    memory.status,
                    com.littlekitty.xiaokele.core.database.state.MemoryStatuses.ARCHIVED,
                    now,
                )
            }
        }
        val memoryReport = memoryRepository.writeCandidates(validation.evaluation.memoryCandidates, now)
        val traitOutcomes = validation.evaluation.userTraitPatches.map { patch ->
            userTraitRepository.applyPatch(patch, memoryReport.memoryIds, now)
        } + validation.evaluation.patternObservations.map { observation ->
            userTraitRepository.applyPatternObservation(observation, now)
        }
        val behaviorOutcomes = behaviorTrainer.applyPatches(
            validation.evaluation.behaviorPatches,
            memoryReport.memoryIds,
            now,
        )
        settlingSessionId?.let { sessionId ->
            val claimUpdatedAt = settlementClaimUpdatedAt
                ?: error("session_settlement_claim_missing")
            check(
                db.conversationSessionDao().completeSettlement(
                    id = sessionId,
                    expectedClaimUpdatedAt = claimUpdatedAt,
                    settledAt = maxOf(now, claimUpdatedAt),
                    now = maxOf(now, claimUpdatedAt),
                ) == 1,
            ) { "session_settlement_claim_lost" }
        }
        // Consolidation/checkpoint callers use this callback to publish their
        // durable active-view row in the same Room transaction as the reviewed
        // memory changes. A failed callback rolls back the whole commit.
        commitCallback()
        // Keep the route read lock through the final write check so a route
        // change rolls back this whole Room transaction instead of publishing
        // a stale D/C result.
        check(commitGuard?.invoke() != false) { "stale_model_route_during_memory_persist" }
        AppliedParts(memoryReport, traitOutcomes, behaviorOutcomes, 0, true)
        }
    }.let { inTransaction ->
        val embeddingResult = runCatching {
            persistEmbeddings(inTransaction.memoryReport.memoryIds, now)
        }.getOrElse { EmbeddingPersistResult(0, degraded = true) }
        inTransaction.copy(
            embeddingCount = embeddingResult.count,
            embeddingDegraded = embeddingResult.degraded,
        )
    }

    private suspend fun persistEmbeddings(
        memoryIds: List<String>,
        now: Long,
    ): EmbeddingPersistResult {
        val eligible = memoryIds
            .mapNotNull { db.memoryDao().byId(it) }
            .filter { it.type in MemoryTypeNames.embeddingEligible }
        if (eligible.isEmpty()) return EmbeddingPersistResult(0, degraded = false)

        return when (val result = embeddingService.embedDocuments(eligible.map { it.content })) {
            is EmbeddingResult.Failure -> EmbeddingPersistResult(0, degraded = true)

            is EmbeddingResult.Success -> {
                val batch = result.batch
                if (batch.vectors.size != eligible.size || batch.dimension <= 0) {
                    return EmbeddingPersistResult(0, degraded = true)
                }
                var persisted = 0
                eligible.forEachIndexed { index, memory ->
                    val vector = batch.vectors[index]
                    if (vector.values.size != batch.dimension) return@forEachIndexed
                    db.memoryEmbeddingDao().upsert(
                        MemoryEmbeddingEntity(
                            memoryId = memory.id,
                            modelId = batch.modelId,
                            dimension = batch.dimension,
                            vectorBlob = VectorBlobCodec.encode(vector.values),
                            createdAt = now,
                        ),
                    )
                    persisted++
                }
                EmbeddingPersistResult(persisted, degraded = persisted != eligible.size)
            }
        }
    }

    private fun com.littlekitty.xiaokele.core.database.entity.MessageEntity.toEvaluationMessage(): MemoryEvaluationMessage =
        MemoryEvaluationMessage(
            id = id,
            role = role,
            content = content,
            createdAt = InstantJson.formatUtc(Instant.ofEpochMilli(createdAt)),
        )

    private data class AppliedParts(
        val memoryReport: MemoryWriteReport,
        val traitOutcomes: List<UserTraitPatchOutcome>,
        val behaviorOutcomes: List<BehaviorPatchOutcome>,
        val embeddingCount: Int,
        val embeddingDegraded: Boolean,
    )

    private data class EmbeddingPersistResult(
        val count: Int,
        val degraded: Boolean,
    )

    companion object {
        const val RECENT_MESSAGE_LIMIT = 20
        const val MAX_ACTIVE_MEMORIES_FOR_EXPLICIT_ANALYSIS = 20
        private const val FORMAT_REPAIR_INSTRUCTION =
            "只修复输出格式和引用 ID，不改变你对消息事实的判断。只输出一个 JSON 对象，且仅含五个顶层数组字段：" +
                "memory_candidates、user_trait_patches、behavior_patches、deactivate_memory_ids、pattern_observations。" +
                "emotion_at_event 和 current_appraisal 必须是对象（例如 {\"value\":\"略烦躁\"}），不能是字符串；" +
                "evidence_memory_ids 只能引用输入中真实存在的 msg_ 或 mem_ ID，无法确认时输出 []，绝不使用 mem_pending 或其他占位符。"
        private val RELATIONSHIP_JSON_OBJECT_FIELDS = setOf("emotion_at_event", "current_appraisal")

        /**
         * Confidence assigned when translating the operation dialect: the model
         * asserted the fact but supplied no score, so this stays moderate rather
         * than pretending to a certainty it never expressed.
         */
        private const val OPERATIONS_SHAPE_CONFIDENCE = 0.6
        private val EVALUATION_ARRAY_FIELDS = listOf(
            "memory_candidates",
            "user_trait_patches",
            "behavior_patches",
            "deactivate_memory_ids",
            "pattern_observations",
        )
    }
}
