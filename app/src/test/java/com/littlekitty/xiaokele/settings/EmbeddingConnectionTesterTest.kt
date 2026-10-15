package com.littlekitty.xiaokele.settings

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingConnectionTesterTest {
    @Test
    fun blankHostProbesThePublicDashScopeEndpointInsteadOfFailingEarly() = runBlocking {
        var requestedUrl: String? = null
        val tester = EmbeddingConnectionTester(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): NetworkResult {
                requestedUrl = request.url
                return NetworkResult.Failure(AppError(ErrorCode.NETWORK_UNAVAILABLE))
            }
        })

        val result = tester.test("", "test-key", "qwen3.7-text-embedding")

        assertEquals(
            "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding",
            requestedUrl,
        )
        assertTrue(result is EmbeddingTestResult.Failure)
        assertTrue((result as EmbeddingTestResult.Failure).message.contains("Embedding 连接失败"))
    }

    @Test
    fun missingKeyOrModelFailsBeforeNetwork() = runBlocking {
        var called = false
        val tester = EmbeddingConnectionTester(object : HttpTransport {
            override suspend fun execute(request: HttpRequest): NetworkResult {
                called = true
                error("network must not be called")
            }
        })

        val noKey = tester.test("", "", "model")
        val noModel = tester.test("", "test-key", "")

        assertEquals("请填写阿里百炼 API Key", (noKey as EmbeddingTestResult.Failure).message)
        assertEquals("请填写 Embedding 模型", (noModel as EmbeddingTestResult.Failure).message)
        assertFalse(called)
    }
}
