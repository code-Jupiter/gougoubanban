package com.littlekitty.xiaokele.chat

import com.littlekitty.xiaokele.core.network.OkHttpHttpTransport
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

class MiniMaxConversationConsolidatorTest {
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
    fun scalarOptionalRelationshipFieldsDoNotDiscardWholeConsolidation() = runBlocking {
        val content = """
            {"summary":"保留这段关系上下文","memory_evaluation":{"memory_candidates":[{"type":"RELATIONSHIP_EVENT","content":"用户表达了重要关系感受","importance":0.8,"confidence":0.9,"validity":"LONG_TERM","evidence_message_ids":[],"emotion_at_event":"害羞"}],"user_trait_patches":[],"behavior_patches":[],"deactivate_memory_ids":[],"pattern_observations":[]}}
        """.trimIndent()
        enqueueProviderContent(content)

        val result = consolidator().consolidate(sampleMessages())

        assertTrue(result is ConversationConsolidationResult.Success)
        val output = (result as ConversationConsolidationResult.Success).output
        assertEquals("保留这段关系上下文", output.summary)
        assertEquals("害羞", output.memoryEvaluation.memoryCandidates.single().emotionAtEvent!!["value"].toString().trim('"'))
    }

    @Test
    fun truncatedProviderOutputStillPersistsUsableSummaryWithoutInventingMemory() = runBlocking {
        enqueueProviderContent("{\"summary\":\"截断前已经生成的摘要\",\"memory_evaluation\":{")

        val result = consolidator().consolidate(sampleMessages())

        assertTrue(result is ConversationConsolidationResult.Success)
        val output = (result as ConversationConsolidationResult.Success).output
        assertEquals("截断前已经生成的摘要", output.summary)
        assertTrue(output.memoryEvaluation.memoryCandidates.isEmpty())
    }

    private fun consolidator() = MiniMaxConversationConsolidator(
        settings = TestSettings(server.url("/v1/text/chatcompletion_v2").toString()),
        transport = OkHttpHttpTransport(),
    )

    private fun enqueueProviderContent(content: String) {
        val escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"$escaped"}}],"base_resp":{"status_code":0}}""",
            ),
        )
    }

    private fun sampleMessages() = listOf(
        ConversationConsolidationMessage(
            id = "msg_00000000000000000000000000000001",
            role = "USER",
            content = "我很喜欢测试小狗",
            createdAt = "2026-08-20T10:00:00Z",
        ),
    )

    private class TestSettings(private val endpointValue: String) : ByokRuntimeConfig {
        override fun loadKeyOrNull(): String = "test-key"
        override fun endpoint(): String = endpointValue
        override fun region(): String = "CN"
        override fun model(): String = "MiniMax-M2"
        override fun agentModelRoute(task: TaskKind, complexity: RouteComplexity): AgentModelRoute =
            AgentModelRoute(ModelProvider.MINIMAX_OFFICIAL, TaskModelRouter.MINIMAX_M2_MODEL)
    }
}
