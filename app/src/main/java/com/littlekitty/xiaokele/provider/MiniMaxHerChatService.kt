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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * MiniMax M2-her has a deliberately smaller contract than M2: visible plain
 * text only, no tools and no response_format. The coordinator remains
 * responsible for deciding whether a tool-capable judgement task is needed.
 */
class MiniMaxHerChatService(
    private val transport: HttpTransport,
    private val config: MiniMaxDirectConfig,
) : FrontChatModelService {
    override suspend fun generateFrontChat(
        context: ContextPack,
        turns: List<FrontChatToolTurn>,
    ): FrontChatModelResult {
        if (!config.isConfigured) return FrontChatModelResult.Failure(
            AppError(ErrorCode.MODEL_NOT_CONFIGURED, userMessage = "请先在设置中填写可用的 MiniMax Key"),
        )
        if (turns.isNotEmpty()) return FrontChatModelResult.Failure(
            AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "minimax_her_tools_not_supported"),
        )
        return executeBody(buildRequestBody(context))
    }

    /** Sends a reviewed body without rebuilding or silently enabling tools. */
    suspend fun generatePreparedRequest(body: String): FrontChatModelResult = executeBody(body)

    private suspend fun executeBody(body: String): FrontChatModelResult {
        validateHerRequest(body)?.let { return FrontChatModelResult.Failure(it) }
        val started = System.currentTimeMillis()
        return when (val result = transport.execute(
            HttpRequest(
                method = HttpMethod.POST,
                url = config.endpoint,
                headers = mapOf("Authorization" to "Bearer ${config.apiKey}", "Content-Type" to "application/json"),
                body = body,
            ),
        )) {
            is NetworkResult.Failure -> FrontChatModelResult.Failure(
                AppError(result.error.code, userMessage = "网络暂时不可用，请稍后再试", technicalDetail = result.error.technicalDetail),
                trace = trace(body, null, null, started),
            )
            is NetworkResult.Success -> {
                val trace = trace(body, result.body, result.statusCode, started)
                if (result.statusCode !in 200..299) {
                    FrontChatModelResult.Failure(AppError(ErrorCode.MODEL_FAILURE, technicalDetail = "minimax_her_http_${result.statusCode}"), trace)
                } else {
                    val root = runCatching { JsonCodec.json.parseToJsonElement(result.body).jsonObject }.getOrNull()
                    val providerStatus = root?.get("base_resp")?.jsonObject?.get("status_code")?.jsonPrimitive?.contentOrNull
                    val content = root?.get("choices")?.let {
                        runCatching { it.jsonArray.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull }.getOrNull()
                    }
                    if (providerStatus != null && providerStatus != "0") {
                        FrontChatModelResult.Failure(AppError(ErrorCode.MODEL_FAILURE, technicalDetail = "minimax_her_base_resp_$providerStatus"), trace)
                    } else if (content.isNullOrBlank()) {
                        FrontChatModelResult.Failure(AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "minimax_her_content_missing"), trace)
                    } else if (!isPlainText(content)) {
                        FrontChatModelResult.Failure(AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "minimax_her_non_plain_text"), trace)
                    } else {
                        FrontChatModelResult.Final(wrapPlainText(content), trace)
                    }
                }
            }
        }
    }

    fun buildRequestBodyForDebug(context: ContextPack): String = buildRequestBody(context)

    private fun buildRequestBody(context: ContextPack): String = buildJsonObject {
        put("model", config.model)
        put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", ReplyPromptPack.B2_SYSTEM) })
            add(buildJsonObject { put("role", "user"); put("content", JsonCodec.encode(context)) })
        })
        put("temperature", 0.7)
        // M2-her's documented hard generation ceiling is 2048 tokens; keep
        // the visible expression call bounded and provider-compatible.
        put("max_completion_tokens", CapabilityProfile.HER.maxOutputTokens)
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
            "minimax_her_request_contains_forbidden_controls"
        }
        null
    }.getOrElse { throwable ->
        AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = throwable.message ?: "minimax_her_request_invalid")
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
            serviceName = "minimax_m2_her",
            requestBody = requestBody,
            rawResponseBody = responseBody,
            responseStatusCode = responseStatusCode,
            latencyMillis = System.currentTimeMillis() - startedAt,
            provider = ModelProvider.MINIMAX_OFFICIAL.name,
            modelId = TaskModelRouter.MINIMAX_HER_MODEL,
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
}
