package com.littlekitty.xiaokele.discovery

import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.OkHttpHttpTransport
import com.littlekitty.xiaokele.settings.ByokRuntimeConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiscoveryFeedServiceTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun localLifeUsesOnlyMiniMaxCodingPlanSearchAndMapsSafeSources() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"organic":[{"title":"莲都周末市集","link":"https://example.com/a","date":"2026-08-16","snippet":"真实摘要"},{"title":"坏来源","link":"file:///tmp/x","snippet":"不可点"}],"base_resp":{"status_code":0}}""",
        ))
        val service = ProfiledDiscoveryFeedService(OkHttpHttpTransport(), fakeSettings(server.url("/").toString()))
        val result = service.fetch(setOf("liandu", "hangzhou"), setOf("events"))
        assertTrue(result is DiscoveryFeedResult.Success)
        val item = (result as DiscoveryFeedResult.Success).items.single()
        assertEquals("liandu", item.area)
        assertEquals("https://example.com/a", item.sourceUrl)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/coding_plan/search", request.path)
        assertTrue(request.body.readUtf8().contains("丽水"))
    }

    @Test
    fun discoveryFailsClosedWhenMiniMaxCapabilityProofIsMissing() = runBlocking {
        var requestCount = 0
        val service = MiniMaxDiscoveryFeedService(object : HttpTransport {
            override suspend fun execute(request: com.littlekitty.xiaokele.core.network.HttpRequest) = error("no network")
        }, fakeSettings(server.url("/").toString(), officialValidated = false))
        val result = service.fetch(setOf("liandu"), setOf("events"))
        assertTrue(result is DiscoveryFeedResult.Failure)
        assertEquals("official_discovery_not_validated", (result as DiscoveryFeedResult.Failure).error.technicalDetail)
        assertEquals(0, requestCount)
    }

    @Test
    fun discoveryQuotaDenialPrecedesMiniMaxSearchIo() = runBlocking {
        var requestCount = 0
        val service = MiniMaxDiscoveryFeedService(object : HttpTransport {
            override suspend fun execute(request: com.littlekitty.xiaokele.core.network.HttpRequest): com.littlekitty.xiaokele.core.network.NetworkResult {
                requestCount++
                error("no network")
            }
        }, fakeSettings(server.url("/").toString(), quotaAllowed = false))
        val result = service.fetch(setOf("liandu"), setOf("events"))
        assertTrue(result is DiscoveryFeedResult.Failure)
        assertEquals("persistent_generation_quota_exhausted", (result as DiscoveryFeedResult.Failure).error.technicalDetail)
        assertEquals(0, requestCount)
    }

    @Test
    fun disabledWebSearchFailsClosedBeforeAnyNetworkIo() = runBlocking {
        var requestCount = 0
        val service = ProfiledDiscoveryFeedService(object : HttpTransport {
            override suspend fun execute(request: com.littlekitty.xiaokele.core.network.HttpRequest): com.littlekitty.xiaokele.core.network.NetworkResult {
                requestCount++
                error("network must not be called")
            }
        }, fakeSettings(server.url("/").toString(), webSearchEnabled = false))

        val result = service.fetch(setOf("liandu"), setOf("events"))

        assertTrue(result is DiscoveryFeedResult.Failure)
        assertEquals("web_search_disabled_by_user", (result as DiscoveryFeedResult.Failure).error.technicalDetail)
        assertEquals(0, requestCount)
    }

    private fun fakeSettings(
        endpoint: String,
        officialValidated: Boolean = true,
        quotaAllowed: Boolean = true,
        webSearchEnabled: Boolean = true,
    ) = object : ByokRuntimeConfig {
        override fun loadKeyOrNull(): String = "test-key"
        override fun endpoint(): String = endpoint
        override fun region(): String = "CN"
        override fun model(): String = "MiniMax-M2"
        override fun officialModelsValidated(): Boolean = officialValidated
        override fun officialLegacyCompatibilityAllowed(): Boolean = false
        override fun reserveGenerationRequest(isPro: Boolean, nowMillis: Long): Boolean = quotaAllowed
        override fun webSearchEnabled(): Boolean = webSearchEnabled
    }
}
