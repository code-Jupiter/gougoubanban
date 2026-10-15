package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.network.HttpMethod
import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.ServiceCallTrace
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.core.network.NetworkResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

sealed interface SourceSummaryResult {
    data class Success(val summary: String) : SourceSummaryResult
    data class Failure(val error: AppError) : SourceSummaryResult
}

/**
 * Direct-Mode source-grounded overview. The prompt contains ONLY the supplied
 * search items and requires every statement to reference one of their numbers.
 * MiniMax M2 never sees an instruction that allows built-in knowledge or invented
 * sources. The returned plain text is shown in addition to the source list.
 */
class SourceGroundedSummarizer(
    private val transport: HttpTransport,
    private val json: Json = com.littlekitty.xiaokele.core.common.json.JsonCodec.json,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val taskGateway: TaskModelGateway? = null,
    private val diagnosticsLogger: DiagnosticsLogger? = null,
) {
    private val mutex = Mutex()
    private val cachedResults = mutableMapOf<String, CachedResult>()

    suspend fun summarize(
        apiKey: String,
        endpoint: String,
        model: String,
        items: List<com.littlekitty.xiaokele.discovery.DiscoveryItem>,
    ): SourceSummaryResult {
        if (apiKey.isBlank() || items.isEmpty()) {
            return SourceSummaryResult.Failure(
                AppError(ErrorCode.INVALID_ARGUMENT, userMessage = "没有可总结的真实来源"),
            )
        }
        val cacheKey = cacheKey(endpoint, model, items)
        return mutex.withLock {
            val now = nowMillis()
            cachedResults[cacheKey]
                ?.takeIf { it.expiresAtMillis > now }
                ?.let { return@withLock it.result }

            // The mutex intentionally covers I/O. It is a singleton service,
            // so duplicate presses and duplicate composable instances join the
            // same computation instead of launching concurrent paid requests.
            val result = summarizeUncached(apiKey, endpoint, model, items)
            cachedResults[cacheKey] = CachedResult(
                result = result,
                expiresAtMillis = now + if (result is SourceSummaryResult.Success) {
                    SUMMARY_CACHE_TTL_MILLIS
                } else {
                    FAILURE_COOLDOWN_MILLIS
                },
            )
            trimExpired(now)
            result
        }
    }

    /** Production overview path: route through the active whole generation profile. */
    suspend fun summarize(items: List<com.littlekitty.xiaokele.discovery.DiscoveryItem>): SourceSummaryResult {
        val gateway = taskGateway ?: return SourceSummaryResult.Failure(
            AppError(ErrorCode.MODEL_NOT_CONFIGURED, userMessage = "当前生成模型方案未配置"),
        )
        if (items.isEmpty()) return SourceSummaryResult.Failure(
            AppError(ErrorCode.INVALID_ARGUMENT, userMessage = "没有可总结的真实来源"),
        )
        val cacheKey = cacheKey(
            "profiled:${gateway.routeCacheIdentity(TaskKind.D_MEMORY)}",
            "discovery-overview:${ReplyPromptPack.VERSION}",
            items,
        )
        return mutex.withLock {
            val now = nowMillis()
            cachedResults[cacheKey]?.takeIf { it.expiresAtMillis > now }?.let { return@withLock it.result }
            val request = TaskModelRequest(
                task = TaskKind.D_MEMORY,
                structured = false,
                systemPrompt = "你是来源核对概览任务。只依据用户提供的真实检索条目生成150字以内中文概览。每个关键结论必须标注 [编号]；不得补充来源之外的事实、网址或行动。只返回纯文本。",
                userPayload = buildPrompt(items),
            )
            diagnosticsLogger?.aiRequestStarted(
                operation = "discovery_overview",
                correlationId = cacheKey,
                subjectId = cacheKey,
                serviceName = "profiled_task_model_gateway",
                requestBody = request.userPayload,
                requestKind = "discovery_overview",
            )
            var generatedTrace: ServiceCallTrace? = null
            val result = when (val generated = gateway.generate(request)) {
                is TaskModelResult.Success -> {
                    generatedTrace = generated.trace
                    val summary = generated.content.trim().takeIf {
                        it.isNotBlank() &&
                            it.length <= MAX_SUMMARY_CHARACTERS &&
                            hasValidSourceReferences(it, items.take(MAX_PROMPT_ITEMS).size)
                    }
                    if (summary == null) SourceSummaryResult.Failure(AppError(ErrorCode.MODEL_OUTPUT_INVALID, userMessage = "概览格式错误，原始来源仍可查看"))
                    else SourceSummaryResult.Success(summary)
                }
                is TaskModelResult.Failure -> {
                    generatedTrace = generated.trace
                    SourceSummaryResult.Failure(generated.error)
                }
            }
            diagnosticsLogger?.aiResponseRecorded(
                operation = "discovery_overview",
                correlationId = cacheKey,
                subjectId = cacheKey,
                status = if (result is SourceSummaryResult.Success) DiagnosticLogStatuses.SUCCESS else DiagnosticLogStatuses.FAILED,
                trace = generatedTrace,
                fallbackRequestBody = request.userPayload,
                normalizedOutput = (result as? SourceSummaryResult.Success)?.summary,
                errorCode = (result as? SourceSummaryResult.Failure)?.error?.code?.name,
            )
            cachedResults[cacheKey] = CachedResult(
                result,
                now + if (result is SourceSummaryResult.Success) SUMMARY_CACHE_TTL_MILLIS else FAILURE_COOLDOWN_MILLIS,
            )
            trimExpired(now)
            result
        }
    }

    private suspend fun summarizeUncached(
        apiKey: String,
        endpoint: String,
        model: String,
        items: List<com.littlekitty.xiaokele.discovery.DiscoveryItem>,
    ): SourceSummaryResult {
        val prompt = buildPrompt(items)
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", "你只依据用户给出的编号来源生成中文概览。每个结论必须标注来源编号。不得使用模型内部知识，不得补充未给出的网址。")
                })
                add(buildJsonObject { put("role", "user"); put("content", prompt) })
            })
            put("temperature", 0.2)
            // Legacy direct overview callers still need a bounded paid
            // completion. Production uses the profiled gateway overload.
            put("max_completion_tokens", 1_024)
        }.toString()
        return when (val result = transport.execute(
            HttpRequest(
                method = HttpMethod.POST,
                url = endpoint,
                headers = mapOf(
                    "Authorization" to "Bearer $apiKey",
                    "Content-Type" to "application/json",
                ),
                body = body,
            ),
        )) {
            is NetworkResult.Failure -> SourceSummaryResult.Failure(result.error)
            is NetworkResult.Success -> {
                if (result.statusCode !in 200..299) {
                    SourceSummaryResult.Failure(
                        AppError(ErrorCode.MODEL_FAILURE, userMessage = "概览生成失败，原始来源仍可查看"),
                    )
                } else {
                    extractText(result.body)
                }
            }
        }
    }

    private fun cacheKey(
        endpoint: String,
        model: String,
        items: List<com.littlekitty.xiaokele.discovery.DiscoveryItem>,
    ): String {
        val inputs = buildString {
            append(endpoint).append('\n').append(model)
            items.take(MAX_PROMPT_ITEMS).forEach { item ->
                append('\n').append(item.id)
                append('\u0000').append(item.sourceUrl)
                append('\u0000').append(item.title)
                append('\u0000').append(item.summary)
                append('\u0000').append(item.publishedAt.orEmpty())
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(inputs.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun trimExpired(now: Long) {
        cachedResults.entries.removeAll { (_, cached) -> cached.expiresAtMillis <= now }
    }

    private fun extractText(body: String): SourceSummaryResult =
        runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val base = root["base_resp"]?.jsonObject
            val status = base?.get("status_code")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            if (status != 0) {
                SourceSummaryResult.Failure(
                    AppError(ErrorCode.MODEL_FAILURE, userMessage = "概览生成失败，原始来源仍可查看"),
                )
            } else {
                val content = root["choices"]
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("message")
                    ?.jsonObject
                    ?.get("content")
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
                    .trim()
                if (content.isBlank()) {
                    SourceSummaryResult.Failure(
                        AppError(ErrorCode.MODEL_OUTPUT_INVALID, userMessage = "概览为空，原始来源仍可查看"),
                    )
                } else {
                    SourceSummaryResult.Success(content)
                }
            }
        }.getOrElse {
            SourceSummaryResult.Failure(
                AppError(ErrorCode.MODEL_OUTPUT_INVALID, userMessage = "概览格式错误，原始来源仍可查看"),
            )
        }

    private fun buildPrompt(items: List<com.littlekitty.xiaokele.discovery.DiscoveryItem>): String = buildString {
        appendLine("以下是真实检索结果，请生成 150 字以内的中文概览：")
        items.take(MAX_PROMPT_ITEMS).forEachIndexed { index, item ->
            appendLine("[${index + 1}] ${item.title}")
            appendLine("来源：${item.sourceName} ${item.sourceUrl}")
            appendLine(item.summary)
        }
        appendLine("规则：只依据这些来源；每个关键结论标注 [编号]；没有把握就写来源不足。")
    }

    private fun hasValidSourceReferences(summary: String, sourceCount: Int): Boolean {
        val references = Regex("\\[(\\d+)]").findAll(summary).map { it.groupValues[1].toInt() }.toList()
        return references.isNotEmpty() && references.all { it in 1..sourceCount }
    }

    private data class CachedResult(
        val result: SourceSummaryResult,
        val expiresAtMillis: Long,
    )

    companion object {
        const val MAX_PROMPT_ITEMS = 10
        const val SUMMARY_CACHE_TTL_MILLIS = 10L * 60L * 1_000L
        const val FAILURE_COOLDOWN_MILLIS = 30L * 1_000L
        const val MAX_SUMMARY_CHARACTERS = 1_000
    }
}
