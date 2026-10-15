package com.littlekitty.xiaokele.memory

import com.littlekitty.xiaokele.core.network.OkHttpHttpTransport
import com.littlekitty.xiaokele.core.network.memory.MemoryEvaluationMessage
import com.littlekitty.xiaokele.core.network.memory.MemoryEvaluationRequest
import com.littlekitty.xiaokele.core.network.memory.MemoryEvaluationResult
import com.littlekitty.xiaokele.provider.AgentModelRoute
import com.littlekitty.xiaokele.provider.ModelProvider
import com.littlekitty.xiaokele.provider.RouteComplexity
import com.littlekitty.xiaokele.provider.TaskKind
import com.littlekitty.xiaokele.provider.TaskModelRouter
import com.littlekitty.xiaokele.settings.ByokRuntimeConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MiniMaxDirectMemoryEvaluatorTest {
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
    fun explicitMemoryEvaluationUsesByokAndReturnsRawValidatedShape() = runBlocking {
        val evaluation = """{"memory_candidates":[],"user_trait_patches":[],"behavior_patches":[],"deactivate_memory_ids":[]}"""
        val escaped = evaluation.replace("\"", "\\\"")
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"$escaped"}}],"base_resp":{"status_code":0}}""",
            ),
        )
        val evaluator = MiniMaxDirectMemoryEvaluator(
            settings = TestSettings(server.url("/v1/text/chatcompletion_v2").toString()),
            transport = OkHttpHttpTransport(),
        )

        val result = evaluator.evaluate(
            MemoryEvaluationRequest(
                eventId = "evt_00000000000000000000000000000001",
                userMessage = MemoryEvaluationMessage(
                    id = "msg_00000000000000000000000000000001",
                    role = "USER",
                    content = "请记住我不喜欢被催",
                ),
                assistantReply = MemoryEvaluationMessage(
                    id = "msg_00000000000000000000000000000002",
                    role = "ASSISTANT",
                    content = "记住了",
                ),
            ),
        )

        assertTrue(result is MemoryEvaluationResult.Success)
        assertEquals(evaluation, (result as MemoryEvaluationResult.Success).rawMemoryEvaluationJson)
        val request = server.takeRequest()
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue(request.body.readUtf8().contains("deactivate_memory_ids"))
    }

    private class TestSettings(private val endpointValue: String) : ByokRuntimeConfig {
        override fun loadKeyOrNull(): String = "test-key"
        override fun endpoint(): String = endpointValue
        override fun region(): String = "CN"
        override fun model(): String = "MiniMax-M2"
        override fun agentModelRoute(task: TaskKind, complexity: RouteComplexity): AgentModelRoute =
            AgentModelRoute(ModelProvider.MINIMAX_OFFICIAL, TaskModelRouter.MINIMAX_M2_MODEL)
    }
}
