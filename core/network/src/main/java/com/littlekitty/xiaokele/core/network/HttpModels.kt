package com.littlekitty.xiaokele.core.network

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode

data class HttpRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

enum class HttpMethod {
    GET,
    POST,
}

sealed interface NetworkResult {
    data class Success(
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: String,
    ) : NetworkResult

    data class Failure(
        val error: AppError,
    ) : NetworkResult
}

data class HttpTransportConfig(
    val connectTimeoutMillis: Long = 15_000L,
    // Model-Studio and MiniMax can take longer than the short health/check
    // requests, especially for a structured A/B1/C turn with a real chat
    // context. Keep the transport bounded, but do not discard a valid reply
    // at the 30-second mark.
    val readTimeoutMillis: Long = 60_000L,
    val callTimeoutMillis: Long = 90_000L,
)
