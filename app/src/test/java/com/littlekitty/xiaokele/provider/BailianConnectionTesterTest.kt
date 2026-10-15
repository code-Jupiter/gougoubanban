package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BailianConnectionTesterTest {
    @Test
    fun probesBothBuiltInChatModelsWithStructuredJson() = runBlocking {
        val bodies = mutableListOf<String>()
        val transport = object : HttpTransport {
            override suspend fun execute(request: com.littlekitty.xiaokele.core.network.HttpRequest): NetworkResult {
                bodies += request.body.orEmpty()
                return NetworkResult.Success(200, emptyMap(), """{"choices":[{"message":{"content":"{\"ok\":true}"}}]}""")
            }
        }
        val result = BailianConnectionTester(transport).test(
            rawKey = "test-key",
            endpoint = "https://dashscope.example/compatible-mode/v1/chat/completions",
        )
        assertTrue(result is BailianModelTestResult.Success)
        assertTrue(bodies.all { it.contains("response_format") && it.contains("enable_thinking") })
        assertTrue(bodies.any { it.contains("deepseek-v4-flash") })
        assertTrue(bodies.any { it.contains("deepseek-v4-pro") })
    }

    @Test
    fun rejectsUnknownBuiltInModelBeforeNetworkIo() = runBlocking {
        var called = false
        val result = BailianConnectionTester(object : HttpTransport {
            override suspend fun execute(request: com.littlekitty.xiaokele.core.network.HttpRequest): NetworkResult {
                called = true
                error("must not call network")
            }
        }).test("test-key", "https://dashscope.example/chat", setOf("unknown-model"))
        assertTrue(result is BailianModelTestResult.Failure)
        assertTrue(!called)
    }

    @Test
    fun catalogKeepsOnlyDeepSeekModelsFromTheWorkspaceResponse() = runBlocking {
        val result = BailianConnectionTester(object : HttpTransport {
            override suspend fun execute(request: com.littlekitty.xiaokele.core.network.HttpRequest): NetworkResult {
                assertEquals("GET", request.method.name)
                assertTrue(request.url.endsWith("/compatible-mode/v1/models"))
                return NetworkResult.Success(
                    200,
                    emptyMap(),
                    """{"data":[{"id":"qwen-plus"},{"id":"deepseek-v4-flash-0731"},{"id":"vanchin/deepseek-v4-pro"}]}""",
                )
            }
        }).listDeepSeekModels("test-key", "https://workspace.example/compatible-mode/v1/chat/completions")

        assertTrue(result is BailianModelCatalogResult.Success)
        assertEquals(
            listOf("deepseek-v4-flash-0731", "vanchin/deepseek-v4-pro"),
            (result as BailianModelCatalogResult.Success).modelIds,
        )
    }
}
