package com.littlekitty.xiaokele.core.network

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.Locale

/**
 * Process-local guard around a transport shared by all outbound service adapters.
 *
 * It intentionally rejects excess work instead of queueing it. A queue would let
 * a burst continue to spend network and provider quota long after its UI source
 * has gone away. Provider 429 responses and repeated transient failures create a
 * short host-scoped cooldown; permanent client errors never do.
 */
class GovernedHttpTransport(
    private val delegate: HttpTransport,
    private val policy: HttpRequestGovernancePolicy = HttpRequestGovernancePolicy(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : HttpTransport {

    private val mutex = Mutex()
    private val hosts = mutableMapOf<String, HostState>()
    private var activeRequests = 0

    override suspend fun execute(request: HttpRequest): NetworkResult {
        val host = request.hostKey()
        val rejected = mutex.withLock {
            val now = nowMillis()
            val state = hosts.getOrPut(host) { HostState() }
            when {
                state.cooldownUntilMillis > now -> rejection(
                    reason = "cooldown",
                    retryAfterMillis = state.cooldownUntilMillis - now,
                )

                state.activeRequests >= policy.maxConcurrentRequestsPerHost -> rejection(
                    reason = "host_concurrency",
                )

                activeRequests >= policy.maxConcurrentRequests -> rejection(
                    reason = "global_concurrency",
                )

                else -> {
                    state.activeRequests += 1
                    activeRequests += 1
                    null
                }
            }
        }
        if (rejected != null) return rejected

        var completedResult: NetworkResult? = null
        try {
            val result = delegate.execute(request)
            completedResult = result
            return result
        } finally {
            // A cancelled caller must not permanently consume a shared slot.
            withContext(NonCancellable) {
                mutex.withLock {
                    release(host, completedResult)
                }
            }
        }
    }

    private fun release(host: String, result: NetworkResult?) {
        val state = hosts[host] ?: return
        state.activeRequests = (state.activeRequests - 1).coerceAtLeast(0)
        activeRequests = (activeRequests - 1).coerceAtLeast(0)
        if (result == null) return

        val now = nowMillis()
        when (result) {
            is NetworkResult.Success -> when {
                result.statusCode == HTTP_TOO_MANY_REQUESTS -> {
                    state.consecutiveTransientFailures = 0
                    state.cooldownUntilMillis = maxOf(
                        state.cooldownUntilMillis,
                        now + retryAfterMillis(result.headers),
                    )
                }

                result.statusCode == HTTP_SERVICE_UNAVAILABLE && retryAfterHeaderMillis(result.headers) != null -> {
                    state.consecutiveTransientFailures = 0
                    state.cooldownUntilMillis = maxOf(
                        state.cooldownUntilMillis,
                        now + retryAfterMillis(result.headers),
                    )
                }

                result.statusCode == HTTP_REQUEST_TIMEOUT || result.statusCode in HTTP_SERVER_ERROR_RANGE -> {
                    state.consecutiveTransientFailures += 1
                    if (state.consecutiveTransientFailures >= policy.transientFailureThreshold) {
                        state.cooldownUntilMillis = maxOf(
                            state.cooldownUntilMillis,
                            now + policy.transientFailureCooldownMillis,
                        )
                        state.consecutiveTransientFailures = 0
                    }
                }

                else -> state.consecutiveTransientFailures = 0
            }

            is NetworkResult.Failure -> {
                if (result.error.code in TRANSIENT_ERROR_CODES) {
                    state.consecutiveTransientFailures += 1
                    if (state.consecutiveTransientFailures >= policy.transientFailureThreshold) {
                        state.cooldownUntilMillis = maxOf(
                            state.cooldownUntilMillis,
                            now + policy.transientFailureCooldownMillis,
                        )
                        state.consecutiveTransientFailures = 0
                    }
                } else {
                    // Authentication and configuration errors must not carry a stale
                    // transient-failure count into a later unrelated request.
                    state.consecutiveTransientFailures = 0
                }
            }
        }
    }

    private fun retryAfterMillis(headers: Map<String, String>): Long =
        (retryAfterHeaderMillis(headers) ?: policy.defaultRateLimitCooldownMillis)
            .coerceIn(policy.minimumCooldownMillis, policy.maximumCooldownMillis)

    private fun retryAfterHeaderMillis(headers: Map<String, String>): Long? =
        headers.entries
            .firstOrNull { (name, _) -> name.equals("Retry-After", ignoreCase = true) }
            ?.value
            ?.substringBefore(',')
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
            ?.times(1_000L)

    private fun rejection(reason: String, retryAfterMillis: Long? = null): NetworkResult.Failure =
        NetworkResult.Failure(
            AppError(
                code = ErrorCode.NETWORK_UNAVAILABLE,
                userMessage = "请求过于频繁，请稍后重试",
                technicalDetail = buildString {
                    append("request_governor_").append(reason)
                    retryAfterMillis?.let { append(":retry_after_ms=").append(it.coerceAtLeast(0L)) }
                },
            ),
        )

    private fun HttpRequest.hostKey(): String = runCatching {
        URI(url).host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
    }.getOrNull() ?: "invalid-host"

    private data class HostState(
        var activeRequests: Int = 0,
        var consecutiveTransientFailures: Int = 0,
        var cooldownUntilMillis: Long = 0L,
    )

    companion object {
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVICE_UNAVAILABLE = 503
        private val HTTP_SERVER_ERROR_RANGE = 500..599
        private val TRANSIENT_ERROR_CODES = setOf(
            ErrorCode.NETWORK_UNAVAILABLE,
            ErrorCode.TIMEOUT,
        )
    }
}

data class HttpRequestGovernancePolicy(
    val maxConcurrentRequests: Int = 8,
    val maxConcurrentRequestsPerHost: Int = 3,
    val transientFailureThreshold: Int = 3,
    val transientFailureCooldownMillis: Long = 30_000L,
    val defaultRateLimitCooldownMillis: Long = 60_000L,
    val minimumCooldownMillis: Long = 5_000L,
    val maximumCooldownMillis: Long = 5 * 60_000L,
) {
    init {
        require(maxConcurrentRequests > 0)
        require(maxConcurrentRequestsPerHost > 0)
        require(transientFailureThreshold > 0)
        require(transientFailureCooldownMillis > 0L)
        require(defaultRateLimitCooldownMillis > 0L)
        require(minimumCooldownMillis > 0L)
        require(maximumCooldownMillis >= minimumCooldownMillis)
    }
}
