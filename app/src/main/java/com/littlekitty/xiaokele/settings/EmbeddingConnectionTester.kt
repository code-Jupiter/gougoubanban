package com.littlekitty.xiaokele.settings

import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.embedding.DashScopeEmbeddingServiceAdapter
import com.littlekitty.xiaokele.core.network.embedding.EmbeddingConfig
import com.littlekitty.xiaokele.core.network.embedding.EmbeddingResult
import com.littlekitty.xiaokele.core.network.embedding.dashScopeEmbeddingEndpoint
import kotlin.system.measureTimeMillis

sealed interface EmbeddingTestResult {
    data class Success(
        val latencyMillis: Long,
        val model: String,
        val dimension: Int,
    ) : EmbeddingTestResult

    data class Failure(val message: String) : EmbeddingTestResult
}

class EmbeddingConnectionTester(
    private val transport: HttpTransport,
) {
    suspend fun test(
        apiHost: String,
        apiKey: String,
        model: String,
    ): EmbeddingTestResult {
        // A blank host is valid: the endpoint builder resolves it to the
        // public DashScope domain, just like the runtime adapter does.
        if (apiKey.isBlank()) return EmbeddingTestResult.Failure("请填写阿里百炼 API Key")
        if (model.isBlank()) return EmbeddingTestResult.Failure("请填写 Embedding 模型")
        var result: EmbeddingResult? = null
        val elapsed = measureTimeMillis {
            result = DashScopeEmbeddingServiceAdapter(
                transport = transport,
                configProvider = {
                    EmbeddingConfig(
                        endpoint = dashScopeEmbeddingEndpoint(apiHost),
                        apiKey = apiKey,
                        model = model,
                    )
                },
            ).embed(listOf("Embedding 连接测试"))
        }
        return when (val value = result) {
            is EmbeddingResult.Success -> EmbeddingTestResult.Success(
                latencyMillis = elapsed,
                model = value.batch.modelId,
                dimension = value.batch.dimension,
            )
            is EmbeddingResult.Failure -> EmbeddingTestResult.Failure(
                "Embedding 连接失败：${value.error.userMessage ?: "请检查 Host、Key、模型或网络"}",
            )
            null -> EmbeddingTestResult.Failure("Embedding 没有返回结果")
        }
    }
}
