package com.littlekitty.xiaokele.core.network

import com.littlekitty.xiaokele.core.common.error.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpModelsTest {

    @Test
    fun requestCarriesNoSecretsByConstruction() {
        val request = HttpRequest(
            method = HttpMethod.GET,
            url = "https://example.invalid/health",
            headers = mapOf("Accept" to "application/json"),
        )
        assertEquals(HttpMethod.GET, request.method)
        assertNull(request.body)
        assertEquals(1, request.headers.size)
    }

    @Test
    fun failureKeepsTechnicalDetailSeparate() {
        val failure = NetworkResult.Failure(
            com.littlekitty.xiaokele.core.common.error.AppError(
                code = ErrorCode.TIMEOUT,
                userMessage = "网络超时，稍后再试",
                technicalDetail = "read timed out",
            ),
        )
        assertEquals("网络超时，稍后再试", (failure as NetworkResult.Failure).error.safeUserMessage())
    }

    @Test
    fun configHasSafeDefaults() {
        val config = HttpTransportConfig()
        assertEquals(15_000L, config.connectTimeoutMillis)
        assertEquals(60_000L, config.readTimeoutMillis)
        assertEquals(90_000L, config.callTimeoutMillis)
    }
}
