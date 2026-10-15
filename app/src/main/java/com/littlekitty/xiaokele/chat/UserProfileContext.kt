package com.littlekitty.xiaokele.chat

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.UserProfileLayerNames
import com.littlekitty.xiaokele.core.common.protocol.UserTraitPatch
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.entity.UserTraitEntity
import com.littlekitty.xiaokele.core.database.repository.UserTraitRepository
import com.littlekitty.xiaokele.core.network.HttpMethod
import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import com.littlekitty.xiaokele.core.network.ServiceCallTrace
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogCategories
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogNames
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import com.littlekitty.xiaokele.provider.MiniMaxDirectConfig
import com.littlekitty.xiaokele.provider.ModelProvider
import com.littlekitty.xiaokele.provider.ReplyPromptPack
import com.littlekitty.xiaokele.provider.ReviewKind
import com.littlekitty.xiaokele.provider.ReplyReview
import com.littlekitty.xiaokele.provider.TaskKind
import com.littlekitty.xiaokele.provider.TaskModelGateway
import com.littlekitty.xiaokele.provider.TaskModelRequest
import com.littlekitty.xiaokele.provider.TaskModelResult
import com.littlekitty.xiaokele.settings.ByokRuntimeConfig
import com.littlekitty.xiaokele.settings.RouteConfigurationBarrier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class CoreProfileFact(
    val key: String,
    val value: JsonObject,
    val confidence: Double,
)

sealed interface CoreProfileSynthesisResult {
    data class Success(
        val profile: JsonObject,
        val trace: ServiceCallTrace? = null,
        val commitGuard: () -> Boolean = { true },
    ) : CoreProfileSynthesisResult
    data class Failure(
        val error: AppError,
        val trace: ServiceCallTrace? = null,
    ) : CoreProfileSynthesisResult
}

fun interface CoreProfileSynthesizer {
    suspend fun synthesize(facts: List<CoreProfileFact>): CoreProfileSynthesisResult
}

class MiniMaxCoreProfileSynthesizer(
    private val settings: ByokRuntimeConfig,
    private val transport: HttpTransport,
    private val taskGateway: TaskModelGateway? = null,
) : CoreProfileSynthesizer {
    override suspend fun synthesize(facts: List<CoreProfileFact>): CoreProfileSynthesisResult {
        taskGateway?.let { gateway ->
            return when (val result = gateway.generate(
                TaskModelRequest(
                    task = TaskKind.D_MEMORY,
                    userPayload = JsonCodec.encode(facts),
                    systemPrompt = ReplyPromptPack.D_SYSTEM + "\n\n只输出紧凑的用户画像 JSON；不得引入请求之外的事实。",
                ),
            )) {
                is TaskModelResult.Success -> parseContent(result.content, result.trace, result.commitGuard)
                is TaskModelResult.Failure -> CoreProfileSynthesisResult.Failure(result.error, result.trace)
            }
        }
        // This fallback is retained only for explicit legacy/test callers;
        // production wiring supplies TaskModelGateway.  It is scoped to the
        // D_MEMORY route rather than a whole-generation profile.
        if (settings.agentModelRoute(TaskKind.D_MEMORY).provider != ModelProvider.MINIMAX_OFFICIAL) {
            return CoreProfileSynthesisResult.Failure(
                AppError(
                    ErrorCode.MODEL_NOT_CONFIGURED,
                    technicalDetail = "profiled_task_gateway_required_for_selected_profile",
                ),
            )
        }
        val key = settings.loadKeyOrNull()
        if (key.isNullOrBlank()) {
            return CoreProfileSynthesisResult.Failure(
                AppError(ErrorCode.MODEL_NOT_CONFIGURED, technicalDetail = "core_profile_key_missing"),
            )
        }
        val endpoint = settings.endpoint().ifBlank {
            if (settings.region() == "INTL") MiniMaxDirectConfig.M2_INTL_ENDPOINT else MiniMaxDirectConfig.M2_CN_ENDPOINT
        }
        val body = buildJsonObject {
            put("model", MiniMaxDirectConfig.DEFAULT_OFFICIAL_MODEL)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put(
                        "content",
                        "把输入的稳定用户事实综合成一个紧凑的中文用户画像 JSON。只能合并输入事实，不得推测；保留身份、稳定偏好、禁忌、沟通方式和关系互动偏好。冲突时保留更高 confidence 并明确不确定性。只输出 JSON 对象，不输出 Markdown。",
                    )
                })
                add(buildJsonObject { put("role", "user"); put("content", JsonCodec.encode(facts)) })
            })
            put("temperature", 0.1)
            // Profile synthesis is D_MEMORY work even on the explicit legacy
            // seam; keep the same bounded output contract as TaskGateway.
            put("max_completion_tokens", 2_048)
        }.toString()
        val startedAt = System.currentTimeMillis()
        return when (
            val result = transport.execute(
                HttpRequest(
                    method = HttpMethod.POST,
                    url = endpoint,
                    headers = mapOf(
                        "Authorization" to "Bearer $key",
                        "Content-Type" to "application/json",
                    ),
                    body = body,
                ),
            )
        ) {
            is NetworkResult.Failure -> CoreProfileSynthesisResult.Failure(
                result.error,
                trace = ServiceCallTrace(
                    serviceName = "minimax_byok_core_profile_synthesizer",
                    requestBody = body,
                    latencyMillis = System.currentTimeMillis() - startedAt,
                ),
            )
            is NetworkResult.Success -> parse(
                result.statusCode,
                result.body,
                ServiceCallTrace(
                    serviceName = "minimax_byok_core_profile_synthesizer",
                    requestBody = body,
                    rawResponseBody = result.body,
                    responseStatusCode = result.statusCode,
                    latencyMillis = System.currentTimeMillis() - startedAt,
                ),
            )
        }
    }

    private fun parse(statusCode: Int, body: String, trace: ServiceCallTrace): CoreProfileSynthesisResult {
        if (statusCode !in 200..299) {
            return CoreProfileSynthesisResult.Failure(
                AppError(ErrorCode.MODEL_FAILURE, technicalDetail = "core_profile_http_$statusCode"),
                trace = trace,
            )
        }
        return runCatching {
            val root = JsonCodec.json.parseToJsonElement(body).jsonObject
            val baseStatus = root["base_resp"]?.jsonObject
                ?.get("status_code")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            require(baseStatus == 0) { "provider_status_$baseStatus" }
            val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            val normalized = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val profile = JsonCodec.json.parseToJsonElement(normalized).jsonObject
            require(profile.isNotEmpty()) { "core_profile_blank" }
            CoreProfileSynthesisResult.Success(profile, trace)
        }.getOrElse { throwable ->
            CoreProfileSynthesisResult.Failure(
                AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "core_profile_invalid:${throwable.message}"),
                trace = trace,
            )
        }
    }

    private fun parseContent(
        content: String,
        trace: ServiceCallTrace,
        commitGuard: () -> Boolean = { true },
    ): CoreProfileSynthesisResult = runCatching {
        val normalized = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val profile = JsonCodec.json.parseToJsonElement(normalized).jsonObject
        require(profile.isNotEmpty()) { "core_profile_blank" }
        CoreProfileSynthesisResult.Success(profile, trace, commitGuard)
    }.getOrElse { throwable ->
        CoreProfileSynthesisResult.Failure(
            AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "core_profile_invalid:${throwable.message}"),
            trace = trace,
        )
    }
}

/** Keeps all source facts locally and replaces them in the prompt only with a fresh synthesized snapshot. */
class UserProfileContextManager(
    private val repository: UserTraitRepository,
    private val synthesizer: CoreProfileSynthesizer,
    private val timeProvider: TimeProvider,
    private val tokenBudget: Int = CORE_PROFILE_TOKEN_BUDGET,
    private val diagnosticsLogger: DiagnosticsLogger? = null,
    private val taskGateway: TaskModelGateway? = null,
) {
    suspend fun prepare(coreTraits: List<UserTraitEntity>): List<UserTraitEntity> {
        val snapshot = coreTraits.firstOrNull { it.key == CORE_PROFILE_SNAPSHOT_KEY }
        val sourceTraits = coreTraits.filterNot { it.key == CORE_PROFILE_SNAPSHOT_KEY }
        if (sourceTraits.isEmpty()) return listOfNotNull(snapshot)
        val sourceCost = sourceTraits.sumOf {
            ContextTokenEstimator.estimate(it.key) + ContextTokenEstimator.estimate(it.valueJson) + 20
        }
        if (sourceCost <= tokenBudget) return sourceTraits

        val latestSourceUpdate = sourceTraits.maxOf { it.updatedAt }
        if (snapshot != null && snapshot.updatedAt >= latestSourceUpdate) return listOf(snapshot)

        val facts = sourceTraits.map { trait ->
            CoreProfileFact(
                key = trait.key,
                value = runCatching { JsonCodec.json.parseToJsonElement(trait.valueJson).jsonObject }
                    .getOrElse { JsonObject(emptyMap()) },
                confidence = trait.confidence,
            )
        }
        val correlationId = "profile:${latestSourceUpdate}"
        val requestBody = JsonCodec.encode(facts)
        diagnosticsLogger?.aiRequestStarted(
            operation = "core_profile_synthesis",
            correlationId = correlationId,
            subjectId = CORE_PROFILE_SNAPSHOT_KEY,
            serviceName = synthesizer.javaClass.simpleName,
            requestBody = requestBody,
            requestKind = "core_profile_facts",
        )
        val result = synthesizer.synthesize(facts)
        when (result) {
            is CoreProfileSynthesisResult.Success -> diagnosticsLogger?.aiResponseRecorded(
                operation = "core_profile_synthesis",
                correlationId = correlationId,
                subjectId = CORE_PROFILE_SNAPSHOT_KEY,
                status = DiagnosticLogStatuses.SUCCESS,
                trace = result.trace,
                fallbackRequestBody = requestBody,
                normalizedOutput = result.profile.toString(),
            )
            is CoreProfileSynthesisResult.Failure -> diagnosticsLogger?.aiResponseRecorded(
                operation = "core_profile_synthesis",
                correlationId = correlationId,
                subjectId = CORE_PROFILE_SNAPSHOT_KEY,
                status = DiagnosticLogStatuses.FAILED,
                trace = result.trace,
                fallbackRequestBody = requestBody,
                errorCode = result.error.code.name,
                technicalDetail = result.error.technicalDetail,
            )
        }
        if (result !is CoreProfileSynthesisResult.Success) return sourceTraits
        if (!result.commitGuard()) return sourceTraits
        var reviewCommitGuard: () -> Boolean = { true }
        taskGateway?.let { gateway ->
            val allowedProfileEvidenceIds = facts.map { it.key }.toSet()
            val reviewPayload = buildJsonObject {
                put("review_kind", ReviewKind.STATE_UPDATE.name)
                put("source_facts", requestBody)
                put("candidate_profile", result.profile)
                put("allowed_evidence_ids", JsonCodec.encode(allowedProfileEvidenceIds.toList().sorted()))
                put("schema", "{reviewKind:STATE_UPDATE,verdict:PASS|REVISE|REJECT,issues:[],evidenceIds:[source fact keys]}")
            }.toString()
            val reviewResult = gateway.generate(
                TaskModelRequest(
                    task = TaskKind.C_REVIEW,
                    userPayload = reviewPayload,
                    systemPrompt = ReplyPromptPack.reviewPrompt(ReviewKind.STATE_UPDATE) +
                        "\n\n这是 D_MEMORY 生成的用户画像候选，reviewKind=STATE_UPDATE。只允许核对触发证据、行为影响和退出条件；只有 PASS 才能进入画像快照。",
                    reviewKind = ReviewKind.STATE_UPDATE,
                ),
            )
            val review = (reviewResult as? TaskModelResult.Success)?.let {
                runCatching { JsonCodec.decode<ReplyReview>(it.content) }.getOrNull()
            }
            reviewCommitGuard = (reviewResult as? TaskModelResult.Success)?.commitGuard ?: { false }
            if (review == null || review.reviewKind != ReviewKind.STATE_UPDATE.name || review.verdict != "PASS" ||
                review.issues.size > MAX_PROFILE_REVIEW_ISSUES ||
                review.issues.any { it.type !in PROFILE_REVIEW_ISSUE_TYPES || it.minimumChange.length > MAX_REVIEW_TEXT_LENGTH } ||
                review.evidenceIds.isEmpty() || review.evidenceIds.any { it !in allowedProfileEvidenceIds } ||
                !reviewCommitGuard()
            ) {
                diagnosticsLogger?.record(
                    category = DiagnosticLogCategories.AI,
                    name = DiagnosticLogNames.PROFILE_SYNTHESIS,
                    status = DiagnosticLogStatuses.FAILED,
                    subjectId = CORE_PROFILE_SNAPSHOT_KEY,
                    summary = "用户画像候选未通过独立 C_REVIEW，保持原画像",
                    payloadJson = buildJsonObject {
                        put("marker", "PROFILE_REVIEW_BLOCKED")
                        put("correlation_id", correlationId)
                        put("verdict", review?.verdict ?: "INVALID")
                    }.toString(),
                )
                return sourceTraits
            }
        }
        var applied = false
        RouteConfigurationBarrier.withReadSuspend {
            // Keep both model guards valid through the actual Room write; a
            // profile switch between validation and persistence must not
            // publish a stale synthesized snapshot.
            if (!result.commitGuard() || !reviewCommitGuard()) return@withReadSuspend
            repository.applyPatch(
                patch = UserTraitPatch(
                    key = CORE_PROFILE_SNAPSHOT_KEY,
                    value = result.profile,
                    confidence = sourceTraits.minOf { it.confidence },
                    profileLayer = UserProfileLayerNames.CORE,
                ),
                derivedEvidenceMemoryIds = emptyList(),
                now = timeProvider.now().toEpochMilli(),
            )
            applied = true
        }
        if (!applied) return sourceTraits
        diagnosticsLogger?.record(
            category = DiagnosticLogCategories.AI,
            name = DiagnosticLogNames.PROFILE_SYNTHESIS,
            status = DiagnosticLogStatuses.SUCCESS,
            subjectId = CORE_PROFILE_SNAPSHOT_KEY,
            summary = "核心用户画像综合结果已通过并写入",
            payloadJson = buildJsonObject {
                put("marker", "PROFILE_SYNTHESIS_APPLIED")
                put("correlation_id", correlationId)
                put("profile", result.profile)
            }.toString(),
        )
        return repository.active().filter { it.key == CORE_PROFILE_SNAPSHOT_KEY }.ifEmpty { sourceTraits }
    }

    companion object {
        const val CORE_PROFILE_SNAPSHOT_KEY = "core_profile_summary"
        const val CORE_PROFILE_TOKEN_BUDGET = 1_600
        private const val MAX_PROFILE_REVIEW_ISSUES = 8
        private const val MAX_REVIEW_TEXT_LENGTH = 1_000
        private val PROFILE_REVIEW_ISSUE_TYPES = setOf("STATE", "FACT", "SOURCE", "BOUNDARY", "INJECTION")
    }
}
