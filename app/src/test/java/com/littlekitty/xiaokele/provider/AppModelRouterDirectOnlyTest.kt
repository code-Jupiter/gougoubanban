package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.AgentOutput
import com.littlekitty.xiaokele.core.common.protocol.CharacterContext
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.CurrentContext
import com.littlekitty.xiaokele.core.common.protocol.DecisionNames
import com.littlekitty.xiaokele.core.network.OkHttpHttpTransport
import com.littlekitty.xiaokele.settings.ByokRuntimeConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppModelRouterDirectOnlyTest {

    private lateinit var miniMaxServer: MockWebServer
    private lateinit var otherServer: MockWebServer

    @Before
    fun setUp() {
        miniMaxServer = MockWebServer()
        miniMaxServer.start()
        otherServer = MockWebServer()
        otherServer.start()
    }

    @After
    fun tearDown() {
        miniMaxServer.shutdown()
        otherServer.shutdown()
    }

    @Test
    fun chatGoesOnlyToConfiguredMiniMaxHost() = runBlocking {
        miniMaxServer.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"choices":[{"message":{"content":"OK"}}],"base_resp":{"status_code":0}}""",
        ))
        val config = object : ByokRuntimeConfig {
            override fun loadKeyOrNull(): String = "dummy-key"
            override fun endpoint(): String = miniMaxServer.url("/v1/text/chatcompletion_v2").toString()
            override fun region(): String = "CUSTOM"
            override fun model(): String = "MiniMax-M2"
            override fun agentModelRoute(task: TaskKind, complexity: RouteComplexity): AgentModelRoute =
                AgentModelRoute(ModelProvider.MINIMAX_OFFICIAL, TaskModelRouter.MINIMAX_M2_MODEL)
        }
        val router = AppModelRouter(config, OkHttpHttpTransport())
        val result = router.generate(context())

        assertTrue(result is com.littlekitty.xiaokele.core.network.model.ModelServiceResult.Success)
        val request = miniMaxServer.takeRequest()
        assertEquals("/v1/text/chatcompletion_v2", request.path)
        assertEquals(0, otherServer.requestCount)
    }

    @Test
    fun backgroundEventUsesStructuredDecisionAdapterAndNeverProducesReplyChat() = runBlocking {
        val structuredDecision = JsonCodec.encode(
            AgentDecision(
                decision = DecisionNames.CREATE_PLAN,
                outputs = listOf(
                    AgentOutput(
                        action = AgentActionRegistry.SEND_PROACTIVE_MESSAGE,
                        payload = buildJsonObject { put("content", "今天也慢慢来。") },
                    ),
                ),
            ),
        )
        miniMaxServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                JsonCodec.encode(
                    buildJsonObject {
                        put("choices", buildJsonArray {
                            add(buildJsonObject {
                                put("message", buildJsonObject { put("content", structuredDecision) })
                            })
                        })
                    },
                ),
            ),
        )
        val config = object : ByokRuntimeConfig {
            override fun loadKeyOrNull(): String = "dummy-key"
            override fun endpoint(): String = miniMaxServer.url("/v1/text/chatcompletion_v2").toString()
            override fun region(): String = "CUSTOM"
            override fun model(): String = "MiniMax-M2"
            override fun agentModelRoute(task: TaskKind, complexity: RouteComplexity): AgentModelRoute =
                AgentModelRoute(ModelProvider.MINIMAX_OFFICIAL, TaskModelRouter.MINIMAX_M2_MODEL)
        }

        val result = AppModelRouter(config, OkHttpHttpTransport()).generate(backgroundContext())

        assertTrue("result=$result", result is com.littlekitty.xiaokele.core.network.model.ModelServiceResult.Success)
        val decision = JsonCodec.decode<AgentDecision>(
            (result as com.littlekitty.xiaokele.core.network.model.ModelServiceResult.Success).rawDecisionJson,
        )
        assertEquals(AgentActionRegistry.SEND_PROACTIVE_MESSAGE, decision.outputs.single().action)
        val requestBody = miniMaxServer.takeRequest().body.readUtf8()
        assertTrue(requestBody.contains("后台场景决策 Agent"))
        assertTrue(requestBody.contains("content_excerpt"))
        assertTrue(requestBody.contains("不得自动附加吃饭、喝水、睡觉或上班提醒"))
        // The background prompt resolves identity from context.character, so it
        // carries the user-confirmed name and none of the removed personal
        // persona, appearance or adult defaults.
        assertTrue(requestBody.contains("豆豆"))
        listOf("小可乐", "猫猫头", "长发", "调情", "露骨的成人张力", "专属小狗").forEach { removed ->
            assertTrue("prompt must not contain $removed", requestBody.contains(removed).not())
        }
        assertTrue(requestBody.contains("尊重拒绝、不适和沉默"))
        assertTrue(requestBody.contains("你是陪伴者，不是训导者、审判者或施恩者"))
        assertTrue(requestBody.contains("不要生成色情或露骨的性内容"))
        assertTrue(requestBody.contains("自己的感受、想法和好奇"))
        // The old personal address copy must be gone from the background prompt too.
        assertTrue(!requestBody.contains("请直接输出对测试用户的中文回复"))
    }

    @Test
    fun idleFollowUpUsesFrontChatAndKeepsReplyChat() = runBlocking {
        miniMaxServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"还想再陪你聊一会儿。"}}],"base_resp":{"status_code":0}}""",
            ),
        )
        val config = object : ByokRuntimeConfig {
            override fun loadKeyOrNull(): String = "dummy-key"
            override fun endpoint(): String = miniMaxServer.url("/v1/text/chatcompletion_v2").toString()
            override fun region(): String = "CUSTOM"
            override fun model(): String = "MiniMax-M2"
        }

        val result = AppModelRouter(config, OkHttpHttpTransport()).generate(idleFollowUpContext())

        assertTrue("result=$result", result is com.littlekitty.xiaokele.core.network.model.ModelServiceResult.Success)
        val decision = JsonCodec.decode<AgentDecision>(
            (result as com.littlekitty.xiaokele.core.network.model.ModelServiceResult.Success).rawDecisionJson,
        )
        assertEquals(AgentActionRegistry.REPLY_CHAT, decision.outputs.single().action)
        val requestBody = miniMaxServer.takeRequest().body.readUtf8()
        assertTrue(requestBody.contains("IDLE_FOLLOW_UP"))
        assertTrue(!requestBody.contains("后台场景决策 Agent"))
    }

    @Test
    fun durableQuotaDenialStopsLegacyDirectEntryBeforeTransport() = runBlocking {
        val config = object : ByokRuntimeConfig {
            override fun loadKeyOrNull(): String = "dummy-key"
            override fun endpoint(): String = miniMaxServer.url("/v1/chat/completions").toString()
            override fun region(): String = "CUSTOM"
            override fun model(): String = "MiniMax-M2"
            override fun agentModelRoute(task: TaskKind, complexity: RouteComplexity): AgentModelRoute =
                AgentModelRoute(ModelProvider.MINIMAX_OFFICIAL, TaskModelRouter.MINIMAX_M2_MODEL)
            override fun reserveGenerationRequest(isPro: Boolean, nowMillis: Long): Boolean = false
        }

        val result = AppModelRouter(config, OkHttpHttpTransport()).generate(backgroundContext())

        assertTrue(result is com.littlekitty.xiaokele.core.network.model.ModelServiceResult.Failure)
        assertEquals(0, miniMaxServer.requestCount)
        assertTrue(
            (result as com.littlekitty.xiaokele.core.network.model.ModelServiceResult.Failure)
                .error.technicalDetail == "persistent_generation_quota_exhausted",
        )
    }

    @Test
    fun bailianCreativeReviewUsesTheCReviewRouteWhenB2RemainsMiniMax() = runBlocking {
        otherServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"{}"}}],"usage":{"total_tokens":3}}""",
            ),
        )
        val config = object : ByokRuntimeConfig {
            override fun loadKeyOrNull(): String = "mini-key"
            override fun endpoint(): String = ""
            override fun region(): String = "CN"
            override fun model(): String = TaskModelRouter.MINIMAX_M2_MODEL
            override fun bailianKeyOrNull(): String = "bailian-key"
            override fun bailianEndpoint(): String = otherServer.url("/compatible-mode/v1/chat/completions").toString()
            override fun bailianModelsValidated(): Boolean = true
            override fun agentModelRoute(task: TaskKind, complexity: RouteComplexity): AgentModelRoute {
                return if (task == TaskKind.C_REVIEW) {
                    AgentModelRoute(
                        provider = ModelProvider.BAILIAN,
                        defaultModelId = TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL,
                        complexModelId = TaskModelRouter.DEFAULT_BAILIAN_PRO_MODEL,
                    )
                } else {
                    super.agentModelRoute(task, complexity)
                }
            }
        }

        val result = AppModelRouter(config, OkHttpHttpTransport()).generateCreativeEvaluation(context())

        assertTrue("result=$result", result is com.littlekitty.xiaokele.core.network.model.ModelServiceResult.Success)
        assertEquals("/compatible-mode/v1/chat/completions", otherServer.takeRequest().path)
        assertEquals(0, miniMaxServer.requestCount)
    }

    private fun context(): ContextPack = ContextPack(
        currentContext = CurrentContext(localTime = "2026-08-16T18:00:00+08:00"),
        character = CharacterContext(
            immutableCore = buildJsonObject {
                put("display_name", "豆豆")
                put("user_name", "小雨")
            },
        ),
    )

    private fun backgroundContext(): ContextPack = context().copy(
        event = buildJsonObject {
            put("source", "SHIFT_SYNC")
            put("type", "SHIFT_UPCOMING")
        },
    )

    private fun idleFollowUpContext(): ContextPack = context().copy(
        event = buildJsonObject {
            put("source", "CHAT_IDLE_FOLLOW_UP")
            put("type", "IDLE_FOLLOW_UP")
        },
    )
}
