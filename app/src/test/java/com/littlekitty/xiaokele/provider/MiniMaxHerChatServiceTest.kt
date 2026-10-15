package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.chat.FrontChatModelResult
import com.littlekitty.xiaokele.core.common.protocol.CharacterContext
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.CurrentContext
import com.littlekitty.xiaokele.core.network.OkHttpHttpTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MiniMaxHerChatServiceTest {
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
    fun herRequestIsPlainTextWithoutToolsOrResponseFormat() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"测试小狗在。"}}],"base_resp":{"status_code":0}}""",
            ),
        )
        val result = MiniMaxHerChatService(
            transport = OkHttpHttpTransport(),
            config = MiniMaxDirectConfig("test-key", TaskModelRouter.MINIMAX_HER_MODEL, server.url("/v1/text/chatcompletion_v2").toString()),
        ).generateFrontChat(context(), emptyList())
        assertTrue(result is FrontChatModelResult.Final)
        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("tools"))
        assertFalse(body.contains("response_format"))
        assertTrue(body.contains(TaskModelRouter.MINIMAX_HER_MODEL))
    }

    @Test
    fun preparedHerRequestRejectsToolsAndResponseFormatBeforeNetwork() = runBlocking {
        val result = MiniMaxHerChatService(
            transport = OkHttpHttpTransport(),
            config = MiniMaxDirectConfig("test-key", TaskModelRouter.MINIMAX_HER_MODEL, server.url("/v1/text/chatcompletion_v2").toString()),
        ).generatePreparedRequest(
            """{"model":"${TaskModelRouter.MINIMAX_HER_MODEL}","tools":[],"tool_choice":"none","response_format":{"type":"json_object"}}""",
        )
        assertTrue(result is FrontChatModelResult.Failure)
        assertTrue(server.requestCount == 0)
    }

    private fun context() = ContextPack(
        currentContext = CurrentContext(localTime = "2026-09-19T18:00:00+08:00"),
        character = CharacterContext(immutableCore = buildJsonObject {}),
    )
}
