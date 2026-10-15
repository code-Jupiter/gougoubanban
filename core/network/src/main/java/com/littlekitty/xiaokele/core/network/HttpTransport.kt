package com.littlekitty.xiaokele.core.network

/**
 * Generic HTTP transport abstraction. Future ServiceAdapters call this interface;
 * business code never sees OkHttp types.
 */
interface HttpTransport {
    suspend fun execute(request: HttpRequest): NetworkResult
}
