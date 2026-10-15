package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.CharacterContext
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.CurrentContext
import com.littlekitty.xiaokele.core.network.OkHttpHttpTransport
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BailianChatServiceTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun structuredTaskUsesPromptSchemaAndBailianHeaders() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"{\"decision\":\"NO_ACTION\",\"outputs\":[],\"todo_mutations\":[],\"trigger_mutations\":[]}"}}],"usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}}""",
            ),
        )
        val service = BailianChatService(
            transport = object : com.littlekitty.xiaokele.core.network.HttpTransport {
                override suspend fun execute(request: com.littlekitty.xiaokele.core.network.HttpRequest) =
                    OkHttpHttpTransport().execute(request.copy(url = server.url("/v1/chat/completions").toString()))
            },
            config = BailianChatConfig(
                apiKey = "fixture",
                model = TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL,
            ),
            route = TaskModelRouter().resolve(TaskKind.B1_DECISION),
        )
        val result = service.generate(context())
        assertTrue("result=$result", result is ModelServiceResult.Success)
        assertEquals(3, (result as ModelServiceResult.Success).trace?.totalTokens)
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("response_format"))
        assertTrue(body.contains("\"max_tokens\":8192"))
        assertTrue(body.contains("\"enable_thinking\":false"))
        assertEquals("Bearer fixture", request.getHeader("Authorization"))
    }

    @Test
    fun flashExpressionRouteReturnsPlainTextWithoutResponseFormat() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"在，先陪你坐一会儿。"}}]}""",
            ),
        )
        // A small transport shim lets the unit test use MockWebServer while the
        // production config still enforces the fixed Bailian endpoint.
        val service = BailianChatService(
            transport = object : com.littlekitty.xiaokele.core.network.HttpTransport {
                override suspend fun execute(request: com.littlekitty.xiaokele.core.network.HttpRequest) =
                    OkHttpHttpTransport().execute(request.copy(url = server.url("/v1/chat/completions").toString()))
            },
            config = BailianChatConfig(
                apiKey = "fixture",
                model = TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL,
            ),
            route = TaskModelRouter(
                configuredRoutes = defaultAgentModelRoutes() + (
                    TaskKind.B2_EXPRESSION to AgentModelRoute(
                        ModelProvider.BAILIAN,
                        TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL,
                    )
                ),
            ).resolve(TaskKind.B2_EXPRESSION),
        )
        val result = service.generateFrontChat(context(), emptyList())
        assertTrue("result=$result", result is com.littlekitty.xiaokele.chat.FrontChatModelResult.Final)
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("response_format"))
        assertFalse(body.contains("reasoning_effort"))
        assertTrue(body.contains("\"max_tokens\":8192"))
        val decision = JsonCodec.json.parseToJsonElement(
            (result as com.littlekitty.xiaokele.chat.FrontChatModelResult.Final).rawDecisionJson,
        ).jsonObject
        assertEquals("REPLY_CHAT", decision["outputs"]!!.jsonArray.single().jsonObject["action"]!!.jsonPrimitive.content)
    }

    @Test
    fun structuredRouteUsesTheRequestedTaskPrompt() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"{\"verdict\":\"PASS\",\"issues\":[],\"evidenceIds\":[]}"}}]}""",
            ),
        )
        val service = BailianChatService(
            transport = object : com.littlekitty.xiaokele.core.network.HttpTransport {
                override suspend fun execute(request: com.littlekitty.xiaokele.core.network.HttpRequest) =
                    OkHttpHttpTransport().execute(request.copy(url = server.url("/v1/chat/completions").toString()))
            },
            config = BailianChatConfig(
                apiKey = "test-provider_route-key",
                model = TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL,
            ),
            route = TaskModelRouter().resolve(TaskKind.C_REVIEW),
        )
        assertTrue(service.generateStructured(context(), TaskKind.C_REVIEW) is ModelServiceResult.Success)
        val body = server.takeRequest().body.readUtf8()
        val prompt = com.littlekitty.xiaokele.core.common.json.JsonCodec.json.parseToJsonElement(body)
            .jsonObject["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(prompt.contains(ReplyPromptPack.C_SYSTEM))
        assertFalse(prompt.contains(ReplyPromptPack.B1_SYSTEM))
    }

    private fun context() = ContextPack(
        currentContext = CurrentContext(localTime = "2026-09-19T18:00:00+08:00"),
        character = CharacterContext(immutableCore = buildJsonObject {}),
    )
}
