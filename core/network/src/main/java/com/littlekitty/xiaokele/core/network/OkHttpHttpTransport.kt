package com.littlekitty.xiaokele.core.network

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Phase 0 generic engine only. It is NOT wired to MiniMax, QWeather, ShiftService
 * or any other supplier, contains no URLs, no endpoints and no API keys.
 */
class OkHttpHttpTransport(
    private val client: OkHttpClient = defaultClient(HttpTransportConfig()),
) : HttpTransport {

    override suspend fun execute(request: HttpRequest): NetworkResult {
        return try {
            val okRequest = request.toOkHttpRequest()
            client.newCall(okRequest).awaitResponse().use { response ->
                NetworkResult.Success(
                    statusCode = response.code,
                    headers = response.headers.toMultimap()
                        .mapValues { (_, values) -> values.joinToString(",") },
                    body = response.body?.string().orEmpty(),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (timeout: SocketTimeoutException) {
            NetworkResult.Failure(
                AppError(ErrorCode.TIMEOUT, userMessage = "网络超时，稍后再试", technicalDetail = timeout.message),
            )
        } catch (io: IOException) {
            NetworkResult.Failure(
                AppError(
                    ErrorCode.NETWORK_UNAVAILABLE,
                    userMessage = "网络暂时不可用",
                    technicalDetail = io.message,
                ),
            )
        }
    }

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }
        })
    }

    private fun HttpRequest.toOkHttpRequest(): Request {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        when (method) {
            HttpMethod.GET -> builder.get()
            HttpMethod.POST -> builder.post(
                (body.orEmpty()).toRequestBody("application/json; charset=utf-8".toMediaType()),
            )
        }
        return builder.build()
    }

    companion object {
        fun defaultClient(config: HttpTransportConfig): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS)
                .callTimeout(config.callTimeoutMillis, TimeUnit.MILLISECONDS)
                .build()
    }
}

private fun Response.safeBody(): String = body?.string().orEmpty()
