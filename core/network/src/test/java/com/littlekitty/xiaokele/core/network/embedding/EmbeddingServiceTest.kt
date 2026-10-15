package com.littlekitty.xiaokele.core.network.embedding

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingServiceTest {

    @Test
    fun unconfiguredAdapterFailsClosedWithoutTransport() = runBlocking {
        var transportCalled = false
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): NetworkResult {
                transportCalled = true
                return NetworkResult.Success(200, emptyMap(), "{}")
            }
        }
        val adapter = RemoteEmbeddingServiceAdapter(
            transport = transport,
            config = EmbeddingConfig.UNCONFIGURED,
            requestBodyBuilder = { _, _, _ -> "{}" },
            responseParser = { _, _ ->
                EmbeddingBatch(modelId = "m", dimension = 1, vectors = listOf(EmbeddingVector(listOf(1f))))
            },
        )

        val result = adapter.embed(listOf("hello"))

        assertTrue(result is EmbeddingResult.Failure)
        assertEquals(ErrorCode.EMBEDDING_NOT_CONFIGURED, (result as EmbeddingResult.Failure).error.code)
        assertTrue(!transportCalled)
    }

    @Test
    fun configuredRemoteAdapterUsesInjectedBuilderParserAndBearerHeader() = runBlocking {
        var request: HttpRequest? = null
        val transport = object : HttpTransport {
            override suspend fun execute(req: HttpRequest): NetworkResult {
                request = req
                return NetworkResult.Success(200, emptyMap(), "ok")
            }
        }
        val adapter = RemoteEmbeddingServiceAdapter(
            transport = transport,
            config = EmbeddingConfig(endpoint = "https://test.invalid/embed", apiKey = "test-key", model = "test-model"),
            requestBodyBuilder = { texts, config, _ -> """{"texts":["${texts.joinToString("\",\"")}"],"model":"${config.model}"}""" },
            responseParser = { _, _ ->
                EmbeddingBatch(
                    modelId = "test-model",
                    dimension = 2,
                    vectors = listOf(EmbeddingVector(listOf(1f, 0f))),
                )
            },
        )

        val result = adapter.embed(listOf("hello"))

        assertTrue(result is EmbeddingResult.Success)
        assertEquals("test-model", (result as EmbeddingResult.Success).batch.modelId)
        assertEquals("Bearer test-key", request?.headers?.get("Authorization"))
        assertTrue(request?.body.orEmpty().contains("hello"))
    }

    @Test
    fun remoteHttpErrorIsMappedToEmbeddingFailure() = runBlocking {
        val adapter = RemoteEmbeddingServiceAdapter(
            transport = object : HttpTransport {
                override suspend fun execute(request: HttpRequest): NetworkResult =
                    NetworkResult.Success(500, emptyMap(), "{}")
            },
            config = EmbeddingConfig(endpoint = "https://test.invalid/embed", apiKey = "k", model = "m"),
            requestBodyBuilder = { _, _, _ -> "{}" },
            responseParser = { _, _ -> EmbeddingBatch(modelId = "m", dimension = 1, vectors = emptyList()) },
        )

        val result = adapter.embed(listOf("hello")) as EmbeddingResult.Failure

        assertEquals(ErrorCode.EMBEDDING_FAILURE, result.error.code)
    }

    @Test
    fun fakeEmbeddingIsRepeatable() = runBlocking {
        val fake = FakeEmbeddingService()
        val first = (fake.embed(listOf("夜班前不要催")) as EmbeddingResult.Success).batch
        val second = (fake.embed(listOf("夜班前不要催")) as EmbeddingResult.Success).batch

        assertEquals(first.modelId, second.modelId)
        assertEquals(first.dimension, second.dimension)
        assertEquals(first.vectors.single().values, second.vectors.single().values)
    }

    @Test
    fun fakeEmbeddingFailureIsExplicit() = runBlocking {
        val result = FakeEmbeddingService(FakeEmbeddingMode.FAILURE).embed(listOf("hello"))

        assertTrue(result is EmbeddingResult.Failure)
        assertEquals(ErrorCode.EMBEDDING_FAILURE, (result as EmbeddingResult.Failure).error.code)
    }

    @Test
    fun dashScopeAdapterBuildsQueryRequestAndParsesDimension() = runBlocking {
        var request: HttpRequest? = null
        val transport = object : HttpTransport {
            override suspend fun execute(req: HttpRequest): NetworkResult {
                request = req
                return NetworkResult.Success(
                    200,
                    emptyMap(),
                    """{"output":{"embeddings":[{"embedding":[0.1,0.2,0.3]}]}}""",
                )
            }
        }
        val adapter = DashScopeEmbeddingServiceAdapter(
            transport = transport,
            configProvider = {
                EmbeddingConfig(
                    endpoint = "https://workspace.example/embed",
                    apiKey = "test-key",
                    model = "qwen3.7-text-embedding",
                )
            },
        )

        val result = adapter.embedQuery(listOf("hello")) as EmbeddingResult.Success

        assertEquals("qwen3.7-text-embedding", result.batch.modelId)
        assertEquals(3, result.batch.dimension)
        assertEquals("Bearer test-key", request?.headers?.get("Authorization"))
        assertTrue(request?.body.orEmpty().contains("\"text_type\":\"query\""))
    }

    @Test
    fun dashScopeHostIsExpandedToNativeEmbeddingEndpoint() {
        assertEquals(
            "https://ws-example.cn-beijing.maas.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding",
            dashScopeEmbeddingEndpoint("ws-example.cn-beijing.maas.aliyuncs.com"),
        )
    }

    @Test
    fun blankHostFallsBackToThePublicDashScopeDomain() {
        assertEquals(
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding",
            dashScopeEmbeddingEndpoint(""),
        )
        assertTrue(
            EmbeddingConfig(
                endpoint = dashScopeEmbeddingEndpoint(""),
                apiKey = "key",
                model = "model",
            ).isConfigured,
        )
    }
}
