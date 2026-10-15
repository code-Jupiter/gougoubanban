package com.littlekitty.xiaokele.core.network.model

import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.CurrentContext
import com.littlekitty.xiaokele.core.common.protocol.CharacterContext
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniMaxChatServiceAdapterTest {

    private val context = ContextPack(
        currentContext = CurrentContext(localTime = "2026-08-16T10:00:00+08:00"),
        character = CharacterContext(immutableCore = JsonObject(emptyMap())),
    )

    @Test
    fun unconfiguredAdapterFailsClosedWithoutNetwork() = runBlocking {
        var called = false
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): NetworkResult {
                called = true
                return NetworkResult.Success(200, emptyMap(), "{}")
            }
        }

        val result = MiniMaxChatServiceAdapter(
            transport = transport,
            config = MiniMaxChatConfig.UNCONFIGURED,
        ).generate(context)

        assertTrue(result is ModelServiceResult.Failure)
        assertEquals(ErrorCode.MODEL_NOT_CONFIGURED, (result as ModelServiceResult.Failure).error.code)
        assertTrue(!called)
    }

    @Test
    fun configuredAdapterSendsBearerAuthAndBuildsJsonBody() = runBlocking {
        var captured: HttpRequest? = null
        val transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): NetworkResult {
                captured = request
                return NetworkResult.Success(
                    200,
                    emptyMap(),
                    JsonCodec.encode(AgentDecision(decision = "NO_ACTION")),
                )
            }
        }
        val adapter = MiniMaxChatServiceAdapter(
            transport = transport,
            config = MiniMaxChatConfig(
                endpoint = "https://example.test/minimax",
                apiKey = "test-key",
                model = "test-model",
            ),
        )

        val result = adapter.generate(context)

        assertTrue("result=$result", result is ModelServiceResult.Success)
        assertEquals("Bearer test-key", captured?.headers?.get("Authorization"))
        assertTrue(captured?.body.orEmpty().contains("\"model\":\"test-model\""))
        assertTrue(!captured?.body.orEmpty().contains("test-key"))
    }

    @Test
    fun choicesContentDecisionIsExtracted() = runBlocking {
        val decision = AgentDecision(decision = "NO_ACTION")
        val encoded = JsonCodec.encode(decision)
        val body = """{"choices":[{"message":{"content":"${escapeJson(encoded)}"}}]}"""
        val directExtraction = extractDefaultMiniMaxDecision(body, JsonCodec.json)
        org.junit.Assert.assertNotNull("body=$body encoded=$encoded extracted=$directExtraction", directExtraction)
        val transport = FakeStringTransport(body)

        val result = MiniMaxChatServiceAdapter(
            transport = transport,
            config = MiniMaxChatConfig("https://example.test", "key", "model"),
        ).generate(context)

        assertTrue("result=$result", result is ModelServiceResult.Success)
        assertEquals("NO_ACTION", JsonCodec.decode<AgentDecision>((result as ModelServiceResult.Success).rawDecisionJson).decision)
    }

    @Test
    fun fencedDecisionWithExplanationIsExtracted() = runBlocking {
        val encoded = JsonCodec.encode(AgentDecision(decision = "NO_ACTION"))
        val content = "我会严格按格式返回：\n```json\n$encoded\n```"
        val body = """{"choices":[{"message":{"content":"${escapeJson(content)}"}}],"base_resp":{"status_code":0}}"""

        val result = MiniMaxChatServiceAdapter(
            transport = FakeStringTransport(body),
            config = MiniMaxChatConfig("https://example.test", "key", "model"),
        ).generate(context)

        assertTrue("result=$result", result is ModelServiceResult.Success)
        assertEquals(
            "NO_ACTION",
            JsonCodec.decode<AgentDecision>((result as ModelServiceResult.Success).rawDecisionJson).decision,
        )
    }

    @Test
    fun bracesInsideDecisionStringsDoNotBreakExtraction() {
        val content = "prefix {\"decision\":\"NO_ACTION\",\"decision_summary\":\"保留 {占位} 文本\",\"outputs\":[]} suffix"
        val extracted = extractJsonObjectCandidate(content, JsonCodec.json)

        assertEquals("NO_ACTION", JsonCodec.decode<AgentDecision>(extracted.orEmpty()).decision)
    }

    @Test
    fun supplierBaseResponseTimeoutIsNotMisreportedAsInvalidOutput() = runBlocking {
        val result = MiniMaxChatServiceAdapter(
            transport = FakeStringTransport("""{"choices":[],"base_resp":{"status_code":1001}}"""),
            config = MiniMaxChatConfig("https://example.test", "key", "model"),
        ).generate(context)

        assertTrue(result is ModelServiceResult.Failure)
        assertEquals(ErrorCode.TIMEOUT, (result as ModelServiceResult.Failure).error.code)
        assertEquals("minimax_base_resp_1001", result.error.technicalDetail)
    }

    @Test
    fun nonJsonSupplierBodyIsModelOutputInvalid() = runBlocking {
        val transport = FakeStringTransport("ok")
        val result = MiniMaxChatServiceAdapter(
            transport = transport,
            config = MiniMaxChatConfig("https://example.test", "key", "model"),
        ).generate(context)

        assertTrue(result is ModelServiceResult.Failure)
        assertEquals(ErrorCode.MODEL_OUTPUT_INVALID, (result as ModelServiceResult.Failure).error.code)
    }

    private fun escapeJson(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"")

    private class FakeStringTransport(private val body: String) : HttpTransport {
        override suspend fun execute(request: HttpRequest): NetworkResult =
            NetworkResult.Success(200, emptyMap(), body)
    }
}
