package com.littlekitty.xiaokele.core.network.model

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.network.HttpMethod
import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import com.littlekitty.xiaokele.core.network.ServiceCallTrace
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * MiniMax direct-connection skeleton (C13).
 *
 * The adapter is fully replaceable behind [ModelService]. The exact MiniMax
 * request/response contract is NOT frozen yet: model name, endpoint and API
 * key come from local build config (never source), and the default envelope
 * builders below are explicit extension points. Without a complete config the
 * adapter fails closed and never performs network I/O.
 */
class MiniMaxChatServiceAdapter(
    private val transport: HttpTransport,
    private val config: MiniMaxChatConfig,
    private val json: Json = JsonCodec.json,
    private val requestBodyBuilder: (ContextPack, MiniMaxChatConfig, Json) -> String =
        ::buildDefaultMiniMaxRequestBody,
    private val decisionExtractor: (String, Json) -> String? =
        ::extractDefaultMiniMaxDecision,
) : ModelService {

    override val serviceName: String = "minimax_chat"

    override suspend fun generate(context: ContextPack): ModelServiceResult {
        if (!config.isConfigured) {
            return ModelServiceResult.Failure(
                AppError(
                    code = ErrorCode.MODEL_NOT_CONFIGURED,
                    technicalDetail = "minimax_config_incomplete",
                ),
            )
        }

        val body = try {
            requestBodyBuilder(context, config, json)
        } catch (t: Throwable) {
            return ModelServiceResult.Failure(
                AppError(ErrorCode.PARSE_FAILURE, technicalDetail = "request_build_failed:${t.message}"),
                trace = ServiceCallTrace(serviceName = serviceName),
            )
        }

        val startedAt = System.currentTimeMillis()
        return when (
            val result = transport.execute(
                HttpRequest(
                    method = HttpMethod.POST,
                    url = config.endpoint,
                    headers = mapOf(
                        "Authorization" to "Bearer ${config.apiKey}",
                        "Content-Type" to "application/json",
                    ),
                    body = body,
                ),
            )
        ) {
            is NetworkResult.Success -> {
                if (result.statusCode !in 200..299) {
                    ModelServiceResult.Failure(
                        AppError(
                            code = ErrorCode.MODEL_FAILURE,
                            technicalDetail = "minimax_http_${result.statusCode}",
                        ),
                        trace = ServiceCallTrace(
                            serviceName = serviceName,
                            requestBody = body,
                            rawResponseBody = result.body,
                            responseStatusCode = result.statusCode,
                            latencyMillis = System.currentTimeMillis() - startedAt,
                        ),
                    )
                } else {
                    val supplierError = extractMiniMaxBaseResponseError(result.body, json)
                    if (supplierError != null) {
                        return supplierError.copy(
                            trace = ServiceCallTrace(
                                serviceName = serviceName,
                                requestBody = body,
                                rawResponseBody = result.body,
                                responseStatusCode = result.statusCode,
                                latencyMillis = System.currentTimeMillis() - startedAt,
                            ),
                        )
                    }
                    val decisionJson = try {
                        decisionExtractor(result.body, json)
                    } catch (_: Throwable) {
                        null
                    }
                    if (decisionJson.isNullOrBlank()) {
                        ModelServiceResult.Failure(
                            AppError(
                                code = ErrorCode.MODEL_OUTPUT_INVALID,
                                technicalDetail = "minimax_decision_extraction_failed",
                            ),
                            trace = ServiceCallTrace(
                                serviceName = serviceName,
                                requestBody = body,
                                rawResponseBody = result.body,
                                responseStatusCode = result.statusCode,
                                latencyMillis = System.currentTimeMillis() - startedAt,
                            ),
                        )
                    } else {
                        ModelServiceResult.Success(
                            rawDecisionJson = decisionJson,
                            trace = ServiceCallTrace(
                                serviceName = serviceName,
                                requestBody = body,
                                rawResponseBody = result.body,
                                responseStatusCode = result.statusCode,
                                latencyMillis = System.currentTimeMillis() - startedAt,
                            ),
                        )
                    }
                }
            }

            is NetworkResult.Failure -> ModelServiceResult.Failure(
                AppError(
                    code = if (result.error.code == ErrorCode.TIMEOUT) {
                        ErrorCode.TIMEOUT
                    } else {
                        ErrorCode.NETWORK_UNAVAILABLE
                    },
                    userMessage = "网络暂时不可用，请稍后再试",
                    technicalDetail = result.error.technicalDetail,
                ),
                trace = ServiceCallTrace(
                    serviceName = serviceName,
                    requestBody = body,
                    latencyMillis = System.currentTimeMillis() - startedAt,
                ),
            )
        }
    }
}

data class MiniMaxChatConfig(
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    val isConfigured: Boolean
        get() = endpoint.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    companion object {
        val UNCONFIGURED = MiniMaxChatConfig()
    }
}

/**
 * Unconfirmed default envelope. It is intentionally small and easy to replace
 * when the MiniMax contract for model name/endpoint is provided (D-01).
 */
internal fun buildDefaultMiniMaxRequestBody(
    context: ContextPack,
    config: MiniMaxChatConfig,
    json: Json,
): String {
    val contextJson = json.encodeToString(context)
    val systemPrompt = context.character.immutableCore.toString()
    return buildJsonObject {
        put("model", config.model)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", contextJson)
            })
        })
    }.toString()
}

/**
 * Unconfirmed default response extraction:
 * 1. A body that already matches AgentDecision is returned as-is.
 * 2. A chat-completion body exposes choices[0].message.content; if that content
 *    is an AgentDecision JSON object it is returned.
 */
internal fun extractDefaultMiniMaxDecision(
    body: String,
    json: Json,
): String? {
    val root = runCatching { json.parseToJsonElement(body.trimStart('\uFEFF')) }.getOrNull()
    if (root is JsonObject) {
        val looksLikeDecision = root["decision"] is JsonPrimitive && root["outputs"] is JsonArray
        if (looksLikeDecision) return body.trim().trimStart('\uFEFF')
        val content = runCatching {
            root["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.takeIf { it.isString }
                ?.contentOrNull
        }.getOrNull()
        if (!content.isNullOrBlank()) {
            return extractJsonObjectCandidate(content, json)
        }
    }
    return null
}

/**
 * Prompt-following output can still be wrapped in a Markdown fence or a short
 * explanation. Extract only one syntactically valid JSON object; the protocol
 * validator remains responsible for rejecting unsafe decisions.
 */
internal fun extractJsonObjectCandidate(content: String, json: Json): String? {
    val trimmed = content.trim().trimStart('\uFEFF')
    if (runCatching { json.parseToJsonElement(trimmed) }.getOrNull() is JsonObject) {
        return trimmed
    }

    var objectStart = -1
    var depth = 0
    var inString = false
    var escaped = false
    trimmed.forEachIndexed { index, character ->
        if (inString) {
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '"' -> inString = false
            }
            return@forEachIndexed
        }
        when (character) {
            '"' -> inString = true
            '{' -> {
                if (depth == 0) objectStart = index
                depth += 1
            }
            '}' -> if (depth > 0) {
                depth -= 1
                if (depth == 0 && objectStart >= 0) {
                    val candidate = trimmed.substring(objectStart, index + 1)
                    if (runCatching { json.parseToJsonElement(candidate) }.getOrNull() is JsonObject) {
                        return candidate
                    }
                    objectStart = -1
                }
            }
        }
    }
    return null
}

private fun extractMiniMaxBaseResponseError(body: String, json: Json): ModelServiceResult.Failure? {
    val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    val statusCode = runCatching {
        root["base_resp"]?.jsonObject?.get("status_code")?.jsonPrimitive?.intOrNull
    }.getOrNull() ?: return null
    if (statusCode == 0) return null
    val code = if (statusCode == MINIMAX_TIMEOUT_STATUS) ErrorCode.TIMEOUT else ErrorCode.MODEL_FAILURE
    return ModelServiceResult.Failure(
        AppError(
            code = code,
            technicalDetail = "minimax_base_resp_$statusCode",
        ),
    )
}

private const val MINIMAX_TIMEOUT_STATUS = 1001
