package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import com.littlekitty.xiaokele.core.network.ServiceCallTrace
import com.littlekitty.xiaokele.discovery.DiscoveryItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceGroundedSummarizerTest {

    @Test
    fun cachesSuccessfulOverviewUntilTheInputCacheExpires() = runBlocking {
        var now = 1_000L
        val transport = RecordingTransport()
        val summarizer = SourceGroundedSummarizer(transport, nowMillis = { now })

        assertTrue(summarizer.summarize("test-key", ENDPOINT, "MiniMax-M2", items()) is SourceSummaryResult.Success)
        assertTrue(summarizer.summarize("test-key", ENDPOINT, "MiniMax-M2", items()) is SourceSummaryResult.Success)
        assertEquals(1, transport.calls)

        now += SourceGroundedSummarizer.SUMMARY_CACHE_TTL_MILLIS
        assertTrue(summarizer.summarize("test-key", ENDPOINT, "MiniMax-M2", items()) is SourceSummaryResult.Success)
        assertEquals(2, transport.calls)
    }

    @Test
    fun concurrentSameOverviewUsesOneOutboundRequest() = runBlocking {
        val transport = BlockingTransport()
        val summarizer = SourceGroundedSummarizer(transport)

        val first = async { summarizer.summarize("test-key", ENDPOINT, "MiniMax-M2", items()) }
        transport.started.await()
        val second = async { summarizer.summarize("test-key", ENDPOINT, "MiniMax-M2", items()) }
        transport.release.complete(success())

        assertTrue(first.await() is SourceSummaryResult.Success)
        assertTrue(second.await() is SourceSummaryResult.Success)
        assertEquals(1, transport.calls)
    }

    @Test
    fun profiledOverviewRejectsMissingOrOutOfRangeReferences() = runBlocking {
        val gateway = FakeOverviewGateway("没有编号的结论")
        val summarizer = SourceGroundedSummarizer(RecordingTransport(), taskGateway = gateway)
        assertTrue(summarizer.summarize(items()) is SourceSummaryResult.Failure)
        assertEquals(TaskKind.D_MEMORY, gateway.lastTask)

        gateway.content = "[2] 越界来源"
        assertTrue(summarizer.summarize(items()) is SourceSummaryResult.Failure)
    }

    @Test
    fun profiledOverviewCacheIdentityChangesAfterRouteChange() = runBlocking {
        val gateway = FakeOverviewGateway("[1] 周末市集")
        val summarizer = SourceGroundedSummarizer(RecordingTransport(), taskGateway = gateway)
        assertTrue(summarizer.summarize(items()) is SourceSummaryResult.Success)
        gateway.identity = "BAILIAN|flash|route-2"
        assertTrue(summarizer.summarize(items()) is SourceSummaryResult.Success)
        assertEquals(2, gateway.calls)
    }

    private fun items(): List<DiscoveryItem> = listOf(
        DiscoveryItem(
            id = "event-1",
            area = "liandu",
            category = "events",
            title = "周末市集",
            summary = "真实摘要",
            sourceName = "官方",
            sourceUrl = "https://example.test/event-1",
        ),
    )

    private class RecordingTransport : HttpTransport {
        var calls = 0

        override suspend fun execute(request: HttpRequest): NetworkResult {
            calls += 1
            return SourceGroundedSummarizerTest.success()
        }
    }

    private class BlockingTransport : HttpTransport {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<NetworkResult>()
        var calls = 0

        override suspend fun execute(request: HttpRequest): NetworkResult {
            calls += 1
            started.complete(Unit)
            return release.await()
        }
    }

    private class FakeOverviewGateway(var content: String) : TaskModelGateway {
        var identity = "MINIMAX_OFFICIAL|m2|route-1"
        var calls = 0
        var lastTask: TaskKind? = null

        override fun routeCacheIdentity(task: TaskKind): String = identity

        override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
            calls++
            lastTask = request.task
            val snapshot = TaskModelRouter().resolve(request.task)
            return TaskModelResult.Success(
                content = content,
                snapshot = snapshot,
                trace = ServiceCallTrace("fake", provider = ModelProvider.MINIMAX_OFFICIAL.name, modelId = snapshot.modelId),
                provenance = ModelCallProvenance(ModelProvider.MINIMAX_OFFICIAL, snapshot.modelId),
            )
        }
    }

    companion object {
        private const val ENDPOINT = "https://example.test/v1/chat"

        private fun success(): NetworkResult.Success = NetworkResult.Success(
            statusCode = 200,
            headers = emptyMap(),
            body = """{"choices":[{"message":{"content":"[1] 周末市集"}}],"base_resp":{"status_code":0}}""",
        )
    }
}
