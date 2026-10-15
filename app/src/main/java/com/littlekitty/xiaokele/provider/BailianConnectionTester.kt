package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.network.HttpMethod
import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import com.littlekitty.xiaokele.settings.ByokRuntimeConfig
import com.littlekitty.xiaokele.settings.KeyFingerprint
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed interface BailianModelTestResult {
    data class Success(val keyFingerprint: String, val modelIds: Set<String>, val latencyMillis: Long) : BailianModelTestResult
    data class Failure(val userMessage: String) : BailianModelTestResult
}

sealed interface BailianModelCatalogResult {
    data class Success(val modelIds: List<String>) : BailianModelCatalogResult
    data class Failure(val userMessage: String) : BailianModelCatalogResult
}

/** Direct completion probes. Bailian's compatible endpoint does not expose a model directory. */
class BailianConnectionTester(
    private val transport: HttpTransport,
    private val quota: ByokRuntimeConfig? = null,
) {
    /**
     * Reads the workspace catalog after a user has supplied a Host and Key.
     * Only DeepSeek IDs are returned: the route UI deliberately starts with
     * one reviewed provider family instead of exposing an unbounded catalog.
     */
    suspend fun listDeepSeekModels(rawKey: String, endpoint: String): BailianModelCatalogResult {
        val key = rawKey.trim().trim('"')
        if (key.isBlank()) return BailianModelCatalogResult.Failure("请先填写百炼 API Key")
        if (endpoint.isBlank()) return BailianModelCatalogResult.Failure("请先配置百炼 Host")
        val modelsUrl = endpoint.substringBeforeLast("/chat/completions") + "/models"
        return when (val result = transport.execute(
            HttpRequest(
                method = HttpMethod.GET,
                url = modelsUrl,
                headers = mapOf("Authorization" to "Bearer $key"),
            ),
        )) {
            is NetworkResult.Failure -> BailianModelCatalogResult.Failure("百炼模型目录网络失败")
            is NetworkResult.Success -> when {
                result.statusCode == 401 || result.statusCode == 403 ->
                    BailianModelCatalogResult.Failure("百炼 API Key 无效或没有权限")
                result.statusCode !in 200..299 ->
                    BailianModelCatalogResult.Failure("百炼模型目录返回 HTTP ${result.statusCode}")
                else -> runCatching {
                    JsonCodec.json.parseToJsonElement(result.body).jsonObject["data"]!!.jsonArray
                        .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
                        .filter(::isDeepSeekModel)
                        .distinct()
                        .sorted()
                }.fold(
                    onSuccess = { models ->
                        if (models.isEmpty()) BailianModelCatalogResult.Failure("当前业务空间没有可用的 DeepSeek 模型")
                        else BailianModelCatalogResult.Success(models)
                    },
                    onFailure = { BailianModelCatalogResult.Failure("百炼模型目录格式无法识别") },
                )
            }
        }
    }
    /** Compatibility overload for old callers; the shared Embedding host is
     * now supplied by the settings screen through the endpoint overload. */
    suspend fun test(
        rawKey: String,
        requiredModelIds: Set<String>,
        flashModelId: String,
        proModelId: String,
        herModelId: String,
    ): BailianModelTestResult = test(
        rawKey = rawKey,
        endpoint = TaskModelRouter.BAILIAN_ENDPOINT,
        modelIds = requiredModelIds.filter { it != herModelId }.toSet().ifEmpty {
            setOf(flashModelId, proModelId)
        },
    )

    suspend fun test(
        rawKey: String,
        endpoint: String,
        modelIds: Set<String> = setOf(
            TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL,
            TaskModelRouter.DEFAULT_BAILIAN_PRO_MODEL,
        ),
    ): BailianModelTestResult {
        val key = rawKey.trim().trim('"')
        if (key.isBlank()) return BailianModelTestResult.Failure("请先填写百炼 API Key")
        if (endpoint.isBlank()) return BailianModelTestResult.Failure("请先配置百炼 Host")
        if (modelIds.isEmpty() || modelIds.any { !isDeepSeekModel(it) }) {
            return BailianModelTestResult.Failure("百炼模型不在当前版本支持列表中")
        }
        val started = System.currentTimeMillis()
        val tested = mutableSetOf<String>()
        for (model in modelIds) {
            if (quota != null && !quota.reserveGenerationRequest(isPro = model == TaskModelRouter.DEFAULT_BAILIAN_PRO_MODEL)) {
                return BailianModelTestResult.Failure("本轮模型请求达到滚动或每日额度，请稍后重试")
            }
            probe(key, endpoint, model)?.let { return BailianModelTestResult.Failure(it) }
            tested += model
        }
        return BailianModelTestResult.Success(KeyFingerprint.stableOf(key), tested, System.currentTimeMillis() - started)
    }

    private suspend fun probe(key: String, endpoint: String, model: String): String? {
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", "只输出 JSON：{\"ok\":true}") })
                add(buildJsonObject { put("role", "user"); put("content", "连接能力测试") })
            })
            put("temperature", 0)
            put("max_tokens", 128)
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("enable_thinking", false)
        }.toString()
        return when (val result = transport.execute(
            HttpRequest(
                method = HttpMethod.POST,
                url = endpoint,
                headers = mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
                body = body,
            ),
        )) {
            is NetworkResult.Failure -> "百炼 $model 能力测试网络失败"
            is NetworkResult.Success -> when {
                result.statusCode == 401 || result.statusCode == 403 -> "百炼 API Key 无效或没有权限"
                result.statusCode !in 200..299 -> "百炼 $model 能力测试返回 HTTP ${result.statusCode}"
                else -> runCatching {
                    val content = JsonCodec.json.parseToJsonElement(result.body).jsonObject["choices"]!!.jsonArray
                        .first().jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
                    JsonCodec.json.parseToJsonElement(content).jsonObject
                }.fold(onSuccess = { null }, onFailure = { "百炼 $model 未返回可解析 JSON" })
            }
        }
    }

    companion object {
        fun isDeepSeekModel(modelId: String): Boolean =
            modelId.startsWith("deepseek-") ||
                modelId.startsWith("vanchin/deepseek-") ||
                modelId.startsWith("siliconflow/deepseek-")
    }
}
