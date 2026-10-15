package com.littlekitty.xiaokele.core.network

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GovernedHttpTransportTest {

    @Test
    fun rejectsConcurrentSameHostRequestAndReleasesSlotWhenFirstCompletes() = runBlocking {
        val delegate = BlockingTransport()
        val governed = GovernedHttpTransport(
            delegate = delegate,
            policy = HttpRequestGovernancePolicy(
                maxConcurrentRequests = 1,
                maxConcurrentRequestsPerHost = 1,
            ),
        )

        val first = async { governed.execute(request()) }
        delegate.firstStarted.await()

        val rejected = governed.execute(request()) as NetworkResult.Failure
        assertEquals(ErrorCode.NETWORK_UNAVAILABLE, rejected.error.code)
        assertTrue(rejected.error.technicalDetail.orEmpty().startsWith("request_governor_host_concurrency"))
        assertEquals(1, delegate.calls)

        delegate.releaseFirst.complete(success())
        assertTrue(first.await() is NetworkResult.Success)
        assertTrue(governed.execute(request()) is NetworkResult.Success)
        assertEquals(2, delegate.calls)
    }

    @Test
    fun releasesSlotAfterTransportFailureAndCancelledRequest() = runBlocking {
        val failureDelegate = object : HttpTransport {
            var calls = 0
            override suspend fun execute(request: HttpRequest): NetworkResult {
                calls += 1
                return NetworkResult.Failure(AppError(ErrorCode.NETWORK_UNAVAILABLE))
            }
        }
        val afterFailure = GovernedHttpTransport(
            delegate = failureDelegate,
            policy = HttpRequestGovernancePolicy(
                maxConcurrentRequests = 1,
                maxConcurrentRequestsPerHost = 1,
                transientFailureThreshold = 3,
            ),
        )
        afterFailure.execute(request())
        afterFailure.execute(request())
        assertEquals(2, failureDelegate.calls)

        val cancellationDelegate = BlockingThenSuccessTransport()
        val afterCancellation = GovernedHttpTransport(
            delegate = cancellationDelegate,
            policy = HttpRequestGovernancePolicy(
                maxConcurrentRequests = 1,
                maxConcurrentRequestsPerHost = 1,
            ),
        )
        val first = async { afterCancellation.execute(request()) }
        cancellationDelegate.firstStarted.await()
        first.cancelAndJoin()

        assertTrue(afterCancellation.execute(request()) is NetworkResult.Success)
        assertEquals(2, cancellationDelegate.calls)
    }

    @Test
    fun honorsRetryAfterFor429WithoutSendingAnotherRequest() = runBlocking {
        var now = 1_000L
        val delegate = QueueTransport(
            mutableListOf(
                NetworkResult.Success(429, headers = mapOf("Retry-After" to "20"), body = ""),
                success(),
            ),
        )
        val governed = GovernedHttpTransport(
            delegate = delegate,
            nowMillis = { now },
        )

        assertTrue(governed.execute(request()) is NetworkResult.Success)
        val blocked = governed.execute(request()) as NetworkResult.Failure
        assertTrue(blocked.error.technicalDetail.orEmpty().contains("request_governor_cooldown"))
        assertTrue(blocked.error.technicalDetail.orEmpty().contains("retry_after_ms=20000"))
        assertEquals(1, delegate.calls)

        now += 20_000L
        assertTrue(governed.execute(request()) is NetworkResult.Success)
        assertEquals(2, delegate.calls)
    }

    @Test
    fun permanentFailureResetsTheTransientFailureStreak() = runBlocking {
        val delegate = QueueTransport(
            mutableListOf(
                failure(ErrorCode.NETWORK_UNAVAILABLE),
                failure(ErrorCode.NETWORK_UNAVAILABLE),
                failure(ErrorCode.MODEL_NOT_CONFIGURED),
                failure(ErrorCode.NETWORK_UNAVAILABLE),
                success(),
            ),
        )
        val governed = GovernedHttpTransport(
            delegate = delegate,
            policy = HttpRequestGovernancePolicy(transientFailureThreshold = 3),
        )

        repeat(4) { assertTrue(governed.execute(request()) is NetworkResult.Failure) }
        assertTrue(governed.execute(request()) is NetworkResult.Success)
        assertEquals(5, delegate.calls)
    }

    private fun request(): HttpRequest = HttpRequest(HttpMethod.POST, "https://example.test/v1/request")

    private fun success(): NetworkResult.Success = NetworkResult.Success(200, emptyMap(), "{}")

    private fun failure(code: ErrorCode): NetworkResult.Failure =
        NetworkResult.Failure(AppError(code))

    private class BlockingTransport : HttpTransport {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<NetworkResult>()
        var calls = 0

        override suspend fun execute(request: HttpRequest): NetworkResult {
            calls += 1
            return if (calls == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            } else {
                NetworkResult.Success(200, emptyMap(), "{}")
            }
        }
    }

    private class BlockingThenSuccessTransport : HttpTransport {
        val firstStarted = CompletableDeferred<Unit>()
        var calls = 0

        override suspend fun execute(request: HttpRequest): NetworkResult {
            calls += 1
            return if (calls == 1) {
                firstStarted.complete(Unit)
                CompletableDeferred<NetworkResult>().await()
            } else {
                NetworkResult.Success(200, emptyMap(), "{}")
            }
        }
    }

    private class QueueTransport(
        private val results: MutableList<NetworkResult>,
    ) : HttpTransport {
        var calls = 0

        override suspend fun execute(request: HttpRequest): NetworkResult {
            calls += 1
            check(results.isNotEmpty()) { "missing queued result" }
            return results.removeAt(0)
        }
    }
}
