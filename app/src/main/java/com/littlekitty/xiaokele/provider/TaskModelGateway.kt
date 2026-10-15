package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.network.HttpMethod
import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import com.littlekitty.xiaokele.core.network.ServiceCallTrace
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.diagnostics.AcceptanceModelCall
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import com.littlekitty.xiaokele.settings.ByokRuntimeConfig
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException

data class TaskModelRequest(
    val task: TaskKind,
    val userPayload: String,
    val systemPrompt: String = ReplyPromptPack.systemPrompt(task),
    val complexity: RouteComplexity = RouteComplexity.DEFAULT,
    val structured: Boolean = task != TaskKind.B2_EXPRESSION,
    /** C must name the object-specific recipe; other tasks leave it null. */
    val reviewKind: ReviewKind? = null,
    /** Debug acceptance correlation; ignored by normal provider wire format. */
    val turnId: String = "",
    val scenarioId: String = "",
    val userInput: String = "",
    val evidenceIds: List<String> = emptyList(),
    val sideEffects: List<String> = emptyList(),
    /** Repair inputs duplicate model/user text and must not enter acceptance exports. */
    val redactDiagnostics: Boolean = false,
)

sealed interface TaskModelResult {
    data class Success(
        val content: String,
        val snapshot: ModelRouteSnapshot,
        val trace: ServiceCallTrace,
        val provenance: ModelCallProvenance,
        val commitGuard: () -> Boolean = { true },
    ) : TaskModelResult

    data class Failure(
        val error: AppError,
        val snapshot: ModelRouteSnapshot? = null,
        val trace: ServiceCallTrace? = null,
        val provenance: ModelCallProvenance? = null,
        /** Ephemeral model text for lossless syntax repair; never include in diagnostics. */
        val recoverableContent: String? = null,
    ) : TaskModelResult
}

interface TaskModelGateway {
    suspend fun generate(request: TaskModelRequest, budget: GenerationBudget = GenerationBudget()): TaskModelResult

    /** Final commit guard for a multi-request attempt. Legacy fakes remain valid. */
    fun isRouteCurrent(snapshot: ModelRouteSnapshot): Boolean = true

    /** Stable cache identity so model-generated summaries cannot cross route changes. */
    fun routeCacheIdentity(task: TaskKind = TaskKind.A_INTAKE): String = "unknown:${ReplyPromptPack.VERSION}:$task"
}

/** One provider-neutral structured-task boundary for chat, memory and profile pipelines. */
class ProfiledTaskModelGateway(
    private val settings: ByokRuntimeConfig,
    private val transport: HttpTransport,
    private val router: TaskModelRouter = TaskModelRouter(),
    private val diagnosticsLogger: DiagnosticsLogger? = null,
) : TaskModelGateway {
    override fun routeCacheIdentity(task: TaskKind): String = settings.withRouteRead {
        synchronizeRoute()
        val snapshot = router.resolve(task = task)
        "${snapshot.provider}|${snapshot.modelId}|${snapshot.promptVersion}|${snapshot.routeVersion}"
    }

    override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
        val captured = settings.withRouteRead {
            synchronizeRoute()
            val snapshot = router.resolve(
                task = request.task,
                complexity = request.complexity,
            )
            val key = if (snapshot.provider == ModelProvider.BAILIAN) settings.bailianKeyOrNull() else settings.loadKeyOrNull()
            val endpoint = if (snapshot.provider == ModelProvider.BAILIAN) {
                settings.bailianEndpoint()
            } else {
                settings.endpoint().ifBlank {
                    if (snapshot.modelId == TaskModelRouter.MINIMAX_HER_MODEL) {
                        if (settings.region() == "INTL") MiniMaxDirectConfig.INTL_ENDPOINT else MiniMaxDirectConfig.CN_ENDPOINT
                    } else {
                        if (settings.region() == "INTL") MiniMaxDirectConfig.M2_INTL_ENDPOINT else MiniMaxDirectConfig.M2_CN_ENDPOINT
                    }
                }
            }
            CapturedRoute(
                snapshot = snapshot,
                key = key,
                endpoint = endpoint,
                bailianReady = snapshot.provider != ModelProvider.BAILIAN || (
                    settings.bailianEndpoint().isNotBlank() &&
                        !settings.bailianKeyOrNull().isNullOrBlank() && settings.bailianModelsValidated()
                    ),
            )
        }
        val snapshot = captured.snapshot
        if (snapshot.capabilityProfile.plainTextOnly && request.structured) {
            return TaskModelResult.Failure(
                AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "her_structured_request_forbidden"),
                snapshot = snapshot,
            )
        }
        if (snapshot.provider == ModelProvider.BAILIAN && !captured.bailianReady) {
            return TaskModelResult.Failure(
                AppError(ErrorCode.MODEL_NOT_CONFIGURED, userMessage = "请先完成 Bailian Flash/Pro 能力测试", technicalDetail = "bailian_profile_not_validated"),
                snapshot = snapshot,
            )
        }
        if (snapshot.provider == ModelProvider.MINIMAX_OFFICIAL &&
            !settings.officialModelsValidated() && !settings.officialLegacyCompatibilityAllowed()
        ) {
            return TaskModelResult.Failure(
                AppError(
                    ErrorCode.MODEL_NOT_CONFIGURED,
                    userMessage = "请先完成 MiniMax M2 与 M2-her 双模型连接能力测试",
                    technicalDetail = "official_profile_not_validated",
                ),
                snapshot = snapshot,
            )
        }
        val isPro = snapshot.provider == ModelProvider.BAILIAN && snapshot.capabilityProfile.isPro
        val key = captured.key
        if (key.isNullOrBlank()) {
            return TaskModelResult.Failure(
                AppError(ErrorCode.MODEL_NOT_CONFIGURED, userMessage = "请先保存当前方案的 API Key", technicalDetail = "generation_key_missing"),
                snapshot = snapshot,
            )
        }
        if (!budget.reserve(isPro)) {
            return TaskModelResult.Failure(
                AppError(ErrorCode.MODEL_FAILURE, userMessage = "本轮模型请求预算已用尽，请稍后重试", technicalDetail = "generation_request_budget_exhausted"),
                snapshot = snapshot,
                provenance = ModelCallProvenance(snapshot.provider, snapshot.modelId, costStatus = CostStatus.UNKNOWN, errorCode = "BUDGET_EXHAUSTED"),
            )
        }
        // Reserve the persistent rolling/day quota immediately before network
        // I/O.  A timeout, cancellation or provider error is intentionally not
        // refunded; only a request that never reached this point is free.
        if (!settings.reserveGenerationRequest(isPro = isPro)) {
            return TaskModelResult.Failure(
                AppError(
                    ErrorCode.MODEL_FAILURE,
                    userMessage = "本轮模型请求达到滚动或每日额度，请稍后重试",
                    technicalDetail = "persistent_generation_quota_exhausted",
                ),
                snapshot = snapshot,
                provenance = ModelCallProvenance(snapshot.provider, snapshot.modelId, costStatus = CostStatus.UNKNOWN, errorCode = "QUOTA_EXHAUSTED"),
            )
        }
        val body = buildRequestBody(request, snapshot)
        val started = System.currentTimeMillis()
        val result = try {
            transport.execute(
                HttpRequest(
                    method = HttpMethod.POST,
                    url = captured.endpoint,
                    headers = mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
                    body = body,
                ),
            )
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            val finishedAt = System.currentTimeMillis()
            diagnosticsLogger?.acceptanceModelCallRecorded(
                AcceptanceModelCall(
                    runId = "",
                    turnId = request.turnId.ifBlank { "task_$finishedAt" },
                    scenarioId = request.scenarioId.ifBlank { request.task.name },
                    userInput = if (request.redactDiagnostics) "" else request.userInput,
                    agent = request.task.name,
                    reviewKind = request.reviewKind?.name,
                    provider = snapshot.provider.name,
                    modelId = snapshot.modelId,
                    promptVersion = snapshot.promptVersion,
                    requestStartedAt = started,
                    requestFinishedAt = finishedAt,
                    durationMillis = finishedAt - started,
                    requestBody = if (request.redactDiagnostics) "[structured_repair_redacted]" else body,
                    status = if (throwable is kotlinx.coroutines.CancellationException) "CANCELLED" else "FAILURE",
                    evidenceIds = request.evidenceIds,
                    sideEffects = request.sideEffects,
                    error = if (request.redactDiagnostics) "repair_transport_failure" else
                        (throwable.message ?: throwable::class.java.simpleName),
                ),
            )
            throw throwable
        }
        val trace = when (result) {
            is NetworkResult.Failure -> ServiceCallTrace(
                serviceName = "${snapshot.provider.name.lowercase()}_${snapshot.modelId}",
                requestBody = if (request.redactDiagnostics) "[structured_repair_redacted]" else body,
                latencyMillis = System.currentTimeMillis() - started,
                provider = snapshot.provider.name,
                modelId = snapshot.modelId,
                task = request.task.name,
                promptVersion = snapshot.promptVersion,
                routeVersion = snapshot.routeVersion,
                costStatus = CostStatus.UNKNOWN.name,
            )
            is NetworkResult.Success -> ServiceCallTrace(
                serviceName = "${snapshot.provider.name.lowercase()}_${snapshot.modelId}",
                requestBody = if (request.redactDiagnostics) "[structured_repair_redacted]" else body,
                rawResponseBody = if (request.redactDiagnostics) null else result.body,
                responseStatusCode = result.statusCode,
                latencyMillis = System.currentTimeMillis() - started,
                provider = snapshot.provider.name,
                modelId = snapshot.modelId,
                task = request.task.name,
                promptVersion = snapshot.promptVersion,
                routeVersion = snapshot.routeVersion,
                inputTokens = usageOf(result.body).inputTokens,
                outputTokens = usageOf(result.body).outputTokens,
                totalTokens = usageOf(result.body).totalTokens,
                costStatus = CostStatus.UNKNOWN.name,
            )
        }
        val finishedAt = System.currentTimeMillis()
        // Do not mark a 2xx response as a successful task until the provider
        // payload has passed the same parsing/shape checks used by callers.
        // This keeps the Debug acceptance status honest when a model spends
        // its output budget on reasoning and returns no usable content.
        val parsedResult = if (!routeStillCurrent(snapshot)) {
            TaskModelResult.Failure(
                AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "stale_model_route"),
                snapshot = snapshot,
                trace = trace,
                provenance = ModelCallProvenance(snapshot.provider, snapshot.modelId, costStatus = CostStatus.UNKNOWN, errorCode = "STALE_ROUTE"),
            )
        } else {
            when (result) {
                is NetworkResult.Failure -> TaskModelResult.Failure(
                    result.error,
                    snapshot,
                    trace,
                    ModelCallProvenance(snapshot.provider, snapshot.modelId, costStatus = CostStatus.UNKNOWN, errorCode = result.error.code.name),
                )
                is NetworkResult.Success -> parseResult(result.statusCode, result.body, request, snapshot, trace)
            }
        }
        val taskStatus = when (parsedResult) {
            is TaskModelResult.Success -> "SUCCESS"
            is TaskModelResult.Failure -> "FAILURE"
        }
        val taskError = (parsedResult as? TaskModelResult.Failure)?.error?.technicalDetail
            ?: when (result) {
                is NetworkResult.Failure -> result.error.technicalDetail
                is NetworkResult.Success -> result.statusCode.takeIf { it !in 200..299 }?.let { "http_$it" }
            }
        diagnosticsLogger?.acceptanceModelCallRecorded(
            AcceptanceModelCall(
                runId = "",
                turnId = request.turnId.ifBlank { "task_${finishedAt}" },
                scenarioId = request.scenarioId.ifBlank { request.task.name },
                userInput = if (request.redactDiagnostics) "" else request.userInput,
                agent = request.task.name,
                reviewKind = request.reviewKind?.name,
                provider = snapshot.provider.name,
                modelId = snapshot.modelId,
                promptVersion = snapshot.promptVersion,
                requestStartedAt = started,
                requestFinishedAt = finishedAt,
                durationMillis = trace.latencyMillis ?: (finishedAt - started),
                requestBody = if (request.redactDiagnostics) "[structured_repair_redacted]" else body,
                responseBody = if (request.redactDiagnostics) null else (result as? NetworkResult.Success)?.body,
                inputTokens = trace.inputTokens,
                outputTokens = trace.outputTokens,
                totalTokens = trace.totalTokens,
                status = taskStatus,
                evidenceIds = request.evidenceIds,
                sideEffects = request.sideEffects,
                error = taskError,
            ),
        )
        return parsedResult
    }

    private fun parseResult(
        statusCode: Int,
        rawBody: String,
        request: TaskModelRequest,
        snapshot: ModelRouteSnapshot,
        trace: ServiceCallTrace,
    ): TaskModelResult {
        val provenance = ModelCallProvenance(snapshot.provider, snapshot.modelId, usage = usageOf(rawBody), costStatus = CostStatus.UNKNOWN)
        if (statusCode !in 200..299) {
            return TaskModelResult.Failure(
                AppError(ErrorCode.MODEL_FAILURE, technicalDetail = "${snapshot.provider.name.lowercase()}_http_$statusCode"),
                snapshot,
                trace,
                provenance.copy(errorCode = "HTTP_$statusCode"),
            )
        }
        var repairCandidate: String? = null
        return runCatching {
            val root = runCatching { JsonCodec.json.parseToJsonElement(rawBody).jsonObject }
                .getOrElse { throw IllegalArgumentException("provider_envelope_invalid") }
            val baseStatus = root["base_resp"]?.jsonObject?.get("status_code")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            require(baseStatus == 0) { "provider_status_$baseStatus" }
            val rawContent = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            require(rawContent.isNotBlank()) { "model_content_missing" }
            require(finishReasonOf(rawBody) != "length") { "model_output_truncated" }
            if (request.structured && finishReasonOf(rawBody) != "length") repairCandidate = rawContent
            // MiniMax's JSON response mode is normally exact, but the model
            // can still wrap a valid object in a Markdown fence.  Treat the
            // fence as transport presentation rather than schema data, while
            // retaining fail-closed parsing when no complete object exists.
            val content = if (request.structured) {
                extractJsonObject(rawContent) ?: throw IllegalArgumentException(
                    // No complete JSON object: either the model answered in prose
                    // or the completion was cut off mid-object. Record which, so a
                    // `structured_json_missing` failure says whether to fix the
                    // prompt or the token budget.
                    "structured_json_missing(chars=${rawContent.length},finish=${finishReasonOf(rawBody)},usage=${usageOf(rawBody)})",
                )
            } else rawContent
            if (request.structured) require(StructuredJsonRepair.validObject(content)) { "structured_duplicate_or_invalid_keys" }
            if (!request.structured && snapshot.capabilityProfile.plainTextOnly) {
                require(!content.contains("tool_calls", ignoreCase = true)) { "her_tool_call_output" }
                require(!content.trim().startsWith("{") && !content.trim().startsWith("[")) { "her_structured_output" }
            }
            TaskModelResult.Success(content, snapshot, trace, provenance) { isRouteCurrent(snapshot) }
        }.getOrElse { throwable ->
            val safeDetail = throwable.message?.takeIf { it.startsWith("structured_json_missing(") || it in setOf(
                "provider_envelope_invalid", "model_content_missing", "model_output_truncated", "structured_duplicate_or_invalid_keys",
            ) }
                ?: "structured_parse_failure"
            TaskModelResult.Failure(
                AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "model_output_invalid:$safeDetail"),
                snapshot,
                trace,
                provenance.copy(errorCode = "OUTPUT_INVALID"),
                recoverableContent = repairCandidate?.takeIf { throwable.message?.startsWith("structured_json_missing") == true },
            )
        }
    }

    private fun buildRequestBody(request: TaskModelRequest, snapshot: ModelRouteSnapshot): String = buildJsonObject {
        put("model", snapshot.modelId)
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", request.systemPrompt) })
            add(buildJsonObject { put("role", "user"); put("content", request.userPayload) })
        })
        put("temperature", if (request.structured) 0.2 else 0.7)
        val outputLimit = structuredOutputBudget(request, snapshot)
        // The two direct providers use different names for the same bounded
        // completion budget; never let a task silently inherit an unlimited
        // provider default.
        if (snapshot.provider == ModelProvider.BAILIAN) {
            put("max_tokens", outputLimit)
            // Bailian documents that reasoning tokens share max_tokens and
            // may consume the entire budget, yielding HTTP 200 with empty
            // content.  Structured A/B1/C/D contracts need the visible JSON;
            // disable hidden reasoning for this bounded protocol call rather
            // than charging a retry for a truncated response.
            if (thinkingCanBeDisabled(request, snapshot) && snapshot.capabilityProfile.supportsThinking) {
                put("enable_thinking", false)
            }
        } else {
            put("max_completion_tokens", outputLimit)
        }
        if (request.structured && snapshot.capabilityProfile.supportsStructuredOutput) {
            put("response_format", buildJsonObject { put("type", "json_object") })
        }
    }.toString()

    private fun extractJsonObject(content: String): String? {
        val trimmed = content.trim().trimStart('\uFEFF')
        if (runCatching { JsonCodec.json.parseToJsonElement(trimmed).jsonObject }.isSuccess) return trimmed
        var start = -1
        var depth = 0
        var quoted = false
        var escaped = false
        trimmed.forEachIndexed { index, character ->
            if (quoted) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                }
                return@forEachIndexed
            }
            when (character) {
                '"' -> quoted = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        val candidate = trimmed.substring(start, index + 1)
                        if (runCatching { JsonCodec.json.parseToJsonElement(candidate).jsonObject }.isSuccess) return candidate
                        start = -1
                    }
                }
            }
        }
        return null
    }

    /**
     * Provider stop reason, recorded only inside failure details.
     *
     * `length` is the signature of a truncated completion, which is the
     * actionable difference between "the model ignored the schema" and "the
     * budget ran out before the JSON closed".
     */
    /**
     * Completion budget for one request.
     *
     * MiniMax reasoning models (M2/M3) do not use a separate `reasoning_content`
     * field: they write the whole chain of thought into `content`, ahead of the
     * answer. Measured live, a C_REVIEW response spent its entire 2048-token
     * budget on an inline `<think>` essay and was cut off before any JSON
     * appeared, so the turn failed as `structured_json_missing` even though the
     * model was doing the right thing. Unlike Bailian there is no
     * `enable_thinking=false` escape for these models, so the budget must cover
     * reasoning *plus* a complete object.
     *
     * A larger ceiling costs nothing when the model stops early: it is an upper
     * bound, not a target.
     */
    private fun structuredOutputBudget(request: TaskModelRequest, snapshot: ModelRouteSnapshot): Int {
        // Every task's visible answer fits the original 1024-token ceiling; the
        // overflow is inline reasoning, which only a model that is actually
        // reasoning will produce.
        val base = BASE_OUTPUT_TOKENS
        val headroom = if (reasonsInsideContent(request, snapshot)) REASONING_HEADROOM_TOKENS else 0
        return (base + headroom).coerceAtMost(snapshot.capabilityProfile.maxOutputTokens)
    }

    /**
     * Whether this exact request will spend completion budget on reasoning.
     *
     * Bailian can be told to stop thinking, and structured Bailian calls do, so
     * it needs no headroom. MiniMax reasoning models have no such switch and put
     * the reasoning into `content`, so they need room for it. This predicate is
     * shared with the request builder so the budget and the flags cannot
     * disagree.
     */
    private fun reasonsInsideContent(request: TaskModelRequest, snapshot: ModelRouteSnapshot): Boolean =
        request.structured &&
            snapshot.capabilityProfile.supportsThinking &&
            !thinkingCanBeDisabled(request, snapshot)

    private fun thinkingCanBeDisabled(request: TaskModelRequest, snapshot: ModelRouteSnapshot): Boolean =
        snapshot.provider == ModelProvider.BAILIAN && request.structured

    private fun finishReasonOf(rawBody: String): String = runCatching {
        JsonCodec.json.parseToJsonElement(rawBody).jsonObject["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("finish_reason")?.jsonPrimitive?.contentOrNull
    }.getOrNull().orEmpty().ifBlank { "unknown" }

    private fun usageOf(rawBody: String): ModelUsage = runCatching {
        val usage = JsonCodec.json.parseToJsonElement(rawBody).jsonObject["usage"]?.jsonObject ?: return ModelUsage()
        ModelUsage(
            inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            outputTokens = usage["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            totalTokens = usage["total_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        )
    }.getOrDefault(ModelUsage())

    private fun synchronizeRoute() {
        router.synchronize(
            RouteConfiguration(
                authorizationVersion = settings.routeConfigurationVersion(),
                keyFingerprint = listOfNotNull(
                    settings.loadKeyOrNull()?.let(com.littlekitty.xiaokele.settings.KeyFingerprint::stableOf),
                    settings.bailianKeyOrNull()?.let(com.littlekitty.xiaokele.settings.KeyFingerprint::stableOf),
                ).joinToString("|").ifBlank { null },
                endpoint = settings.bailianEndpoint(),
                flashModel = TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL,
                proModel = TaskModelRouter.DEFAULT_BAILIAN_PRO_MODEL,
                validationFingerprint = listOfNotNull(
                    settings.officialValidationFingerprint(),
                    settings.bailianValidationFingerprint(),
                ).joinToString("|").ifBlank { null },
                modelsValidated = settings.officialModelsValidated() && settings.bailianModelsValidated(),
                agentRoutes = settings.agentModelRoutes(),
            ),
        )
    }

    private fun routeStillCurrent(snapshot: ModelRouteSnapshot): Boolean {
        synchronizeRoute()
        return router.isCurrent(snapshot)
    }

    private fun bailianReady(): Boolean =
        settings.bailianEndpoint().isNotBlank() &&
            !settings.bailianKeyOrNull().isNullOrBlank() && settings.bailianModelsValidated()

    override fun isRouteCurrent(snapshot: ModelRouteSnapshot): Boolean {
        synchronizeRoute()
        return router.isCurrent(snapshot)
    }

    private data class CapturedRoute(
        val snapshot: ModelRouteSnapshot,
        val key: String?,
        val endpoint: String,
        val bailianReady: Boolean,
    )

    private companion object {
        /** Visible-answer budget for one protocol call, before reasoning. */
        const val BASE_OUTPUT_TOKENS = 1_024

        /** Extra room for models that reason inside `content` instead of a side channel. */
        const val REASONING_HEADROOM_TOKENS = 3_072
    }
}
