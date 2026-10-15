package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.chat.FrontChatModelResult
import com.littlekitty.xiaokele.chat.FrontChatModelService
import com.littlekitty.xiaokele.chat.FrontChatToolTurn
import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.network.HttpMethod
import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import com.littlekitty.xiaokele.core.network.ServiceCallTrace
import com.littlekitty.xiaokele.core.network.model.ModelService
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class BailianChatConfig(
    val apiKey: String,
    val model: String,
    val endpoint: String = TaskModelRouter.BAILIAN_ENDPOINT,
    val httpReferer: String? = null,
    val appTitle: String? = "LittleKitty",
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && model.isNotBlank() && endpoint.isNotBlank()
}

/** Bailian's OpenAI-compatible adapter. It intentionally never sends tools to HER. */
class BailianChatService(
    private val transport: HttpTransport,
    private val config: BailianChatConfig,
    private val route: ModelRouteSnapshot,
) : ModelService, FrontChatModelService {
    override val serviceName: String = "bailian_byok_direct"

    override suspend fun generate(context: ContextPack): ModelServiceResult {
        return generateStructured(context, TaskKind.B1_DECISION)
    }

    /** Structured route entry point; the task controls the system contract. */
    suspend fun generateStructured(
        context: ContextPack,
        task: TaskKind,
    ): ModelServiceResult {
        if (!config.isConfigured) return ModelServiceResult.Failure(
            AppError(ErrorCode.MODEL_NOT_CONFIGURED, userMessage = "请先在设置中填写可用的 Bailian Key"),
        )
        if (route.capabilityProfile.plainTextOnly) {
            return ModelServiceResult.Failure(
                AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "her_not_allowed_for_structured_task"),
            )
        }
        val body = requestBody(
            system = ReplyPromptPack.systemPrompt(task),
            user = JsonCodec.encode(context),
            structured = true,
        )
        return execute(body, structured = true)
    }

    /** Compatibility bridge for the existing background AgentDecision channel. */
    suspend fun generateWithSystemPrompt(context: ContextPack, systemPrompt: String): ModelServiceResult {
        if (!config.isConfigured) return ModelServiceResult.Failure(
            AppError(ErrorCode.MODEL_NOT_CONFIGURED, userMessage = "请先在设置中填写可用的 Bailian Key"),
        )
        if (route.capabilityProfile.plainTextOnly) return ModelServiceResult.Failure(
            AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "her_not_allowed_for_structured_task"),
        )
        return execute(
            requestBody(systemPrompt, JsonCodec.encode(context), structured = true),
            structured = true,
        )
    }

    /** Debug-only path: the reviewed JSON body is sent without re-serializing it. */
    suspend fun generatePreparedRequest(body: String): ModelServiceResult {
        if (!config.isConfigured) return ModelServiceResult.Failure(
            AppError(ErrorCode.MODEL_NOT_CONFIGURED, userMessage = "请先在设置中填写可用的 Bailian Key"),
        )
        if (route.capabilityProfile.plainTextOnly) {
            validateHerRequest(body)?.let { return ModelServiceResult.Failure(it) }
        }
        return execute(body, structured = !route.capabilityProfile.plainTextOnly)
    }

    override suspend fun generateFrontChat(
        context: ContextPack,
        turns: List<FrontChatToolTurn>,
    ): FrontChatModelResult {
        if (!config.isConfigured) return FrontChatModelResult.Failure(
            AppError(ErrorCode.MODEL_NOT_CONFIGURED, userMessage = "请先在设置中填写可用的 Bailian Key"),
        )
        // Bailian routes deliberately expose no tools; a caller must not silently retry with a tool-capable model.
        if (turns.isNotEmpty()) return FrontChatModelResult.Failure(
            AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "bailian_tools_not_supported"),
        )
        val body = requestBody(
            system = ReplyPromptPack.B2_SYSTEM,
            user = JsonCodec.encode(context),
            structured = false,
        )
        return when (val result = executeRaw(body)) {
            is RawResult.Failure -> FrontChatModelResult.Failure(result.error, result.trace)
            is RawResult.Success -> FrontChatModelResult.Final(
                rawDecisionJson = wrapPlainText(result.content),
                trace = result.trace,
            ).takeIf { isPlainText(result.content) } ?: FrontChatModelResult.Failure(
                AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "bailian_her_non_plain_text"),
                result.trace,
            )
        }
    }

    private suspend fun execute(body: String, structured: Boolean): ModelServiceResult {
        return when (val result = executeRaw(body)) {
            is RawResult.Failure -> ModelServiceResult.Failure(result.error, result.trace)
            is RawResult.Success -> {
                val candidate = if (structured) extractJsonObject(result.content)
                else result.content.takeIf(::isPlainText)?.let(::wrapPlainText)
                if (candidate == null) {
                    ModelServiceResult.Failure(
                        AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "bailian_json_extraction_failed"),
                        result.trace,
                    )
                } else {
                    ModelServiceResult.Success(candidate, result.trace)
                }
            }
        }
    }

    private suspend fun executeRaw(body: String): RawResult {
        val started = System.currentTimeMillis()
        val headers = buildMap {
            put("Authorization", "Bearer ${config.apiKey}")
            put("Content-Type", "application/json")
            config.httpReferer?.takeIf { it.isNotBlank() }?.let { put("HTTP-Referer", it) }
            config.appTitle?.takeIf { it.isNotBlank() }?.let { put("X-Title", it) }
        }
        return when (val network = transport.execute(HttpRequest(HttpMethod.POST, config.endpoint, headers, body))) {
            is NetworkResult.Failure -> RawResult.Failure(
                AppError(network.error.code, userMessage = "Bailian 网络暂时不可用，请稍后再试", technicalDetail = network.error.technicalDetail),
                trace(body, null, null, started),
            )
            is NetworkResult.Success -> {
                val trace = trace(body, network.body, network.statusCode, started)
                if (network.statusCode == 401 || network.statusCode == 403) {
                    RawResult.Failure(AppError(ErrorCode.MODEL_FAILURE, userMessage = "Bailian Key 无效或没有权限，请到设置中更新", technicalDetail = "bailian_auth_${network.statusCode}"), trace)
                } else if (network.statusCode == 429) {
                    RawResult.Failure(AppError(ErrorCode.MODEL_FAILURE, userMessage = "Bailian 请求太频繁，请稍后重试", technicalDetail = "bailian_rate_limited"), trace)
                } else if (network.statusCode !in 200..299) {
                    RawResult.Failure(AppError(ErrorCode.MODEL_FAILURE, userMessage = "Bailian 暂时不可用，请稍后重试", technicalDetail = "bailian_http_${network.statusCode}"), trace)
                } else {
                    val root = runCatching { JsonCodec.json.parseToJsonElement(network.body).jsonObject }.getOrNull()
                    val providerError = root?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    if (!providerError.isNullOrBlank()) {
                        RawResult.Failure(AppError(ErrorCode.MODEL_FAILURE, technicalDetail = "bailian_provider_error"), trace)
                    } else {
                        val content = root?.get("choices")?.let { choices ->
                            runCatching {
                                choices.jsonArray.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                            }.getOrNull()
                        }
                        if (content.isNullOrBlank()) RawResult.Failure(
                            AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "bailian_content_missing"),
                            trace,
                        ) else RawResult.Success(content, trace)
                    }
                }
            }
        }
    }

    private fun requestBody(system: String, user: String, structured: Boolean): String = buildJsonObject {
        put("model", config.model)
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", system) })
            add(buildJsonObject { put("role", "user"); put("content", user) })
        })
        put("temperature", if (structured) 0.2 else 0.7)
        // Keep every Bailian completion bounded by the route contract,
        // including the plain-text HER path.  Relying on the provider
        // default would make the six-call budget insufficiently meaningful
        // and can produce an unbounded paid completion.
        put("max_tokens", route.capabilityProfile.maxOutputTokens)
        if (structured && route.capabilityProfile.supportsStructuredOutput) {
            put("response_format", buildJsonObject { put("type", "json_object") })
        }
        if (structured && route.capabilityProfile.supportsThinking) {
            put("enable_thinking", false)
        }
    }.toString()

    private fun wrapPlainText(content: String): String = buildJsonObject {
        put("decision", "CREATE_PLAN")
        put("decision_summary", "front_chat")
        put("outputs", buildJsonArray {
            add(buildJsonObject {
                put("action", AgentActionRegistry.REPLY_CHAT)
                put("payload", buildJsonObject { put("content", content.trim()) })
            })
        })
        put("todo_mutations", buildJsonArray {})
        put("trigger_mutations", buildJsonArray {})
    }.toString()

    private fun validateHerRequest(body: String): AppError? = runCatching {
        val root = JsonCodec.json.parseToJsonElement(body).jsonObject
        require("tools" !in root && "tool_choice" !in root && "response_format" !in root) {
            "bailian_her_request_contains_forbidden_controls"
        }
        null
    }.getOrElse { throwable ->
        AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = throwable.message ?: "bailian_her_request_invalid")
    }

    private fun isPlainText(content: String): Boolean {
        val trimmed = content.trimStart()
        return trimmed.isNotBlank() && !trimmed.startsWith("{") && !trimmed.startsWith("[") &&
            !trimmed.startsWith("```") && !trimmed.contains("tool_calls", ignoreCase = true)
    }

    private fun trace(
        requestBody: String,
        responseBody: String?,
        responseStatusCode: Int?,
        startedAt: Long,
    ): ServiceCallTrace {
        val usage = responseBody?.let(::usageOf) ?: Usage()
        return ServiceCallTrace(
            serviceName = serviceName,
            requestBody = requestBody,
            rawResponseBody = responseBody,
            responseStatusCode = responseStatusCode,
            latencyMillis = System.currentTimeMillis() - startedAt,
            provider = route.provider.name,
            modelId = route.modelId,
            promptVersion = route.promptVersion,
            routeVersion = route.routeVersion,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            totalTokens = usage.totalTokens,
            costStatus = CostStatus.UNKNOWN.name,
        )
    }

    private fun usageOf(rawBody: String): Usage = runCatching {
        val usage = JsonCodec.json.parseToJsonElement(rawBody).jsonObject["usage"]?.jsonObject ?: return Usage()
        Usage(
            inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            outputTokens = usage["completion_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            totalTokens = usage["total_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        )
    }.getOrDefault(Usage())

    private data class Usage(
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val totalTokens: Int? = null,
    )

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

    private sealed interface RawResult {
        data class Success(val content: String, val trace: ServiceCallTrace) : RawResult
        data class Failure(val error: AppError, val trace: ServiceCallTrace) : RawResult
    }
}
