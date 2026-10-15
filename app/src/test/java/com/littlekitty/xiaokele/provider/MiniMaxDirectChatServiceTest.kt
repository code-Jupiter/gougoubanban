package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.chat.FrontChatToolCall
import com.littlekitty.xiaokele.chat.FrontChatModelResult
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.CharacterContext
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.CurrentContext
import com.littlekitty.xiaokele.chat.LocalChatToolExecutor
import com.littlekitty.xiaokele.core.network.OkHttpHttpTransport
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MiniMaxDirectChatServiceTest {

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
    fun documentedModelDefaultsSelectTheirMatchingEndpoints() {
        assertEquals(MiniMaxDirectConfig.M2_CN_ENDPOINT, MiniMaxDirectConfig(apiKey = "test-dummy-key").endpoint)
        assertEquals(
            MiniMaxDirectConfig.CN_ENDPOINT,
            MiniMaxDirectConfig(apiKey = "test-dummy-key", model = TaskModelRouter.MINIMAX_HER_MODEL).endpoint,
        )
    }

    @Test
    fun officialProfileValidationRequiresBothM2AndHerCompletions() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"M2 连接成功"}}],"base_resp":{"status_code":0}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"M2-her 连接成功"}}],"base_resp":{"status_code":0}}""",
            ),
        )

        val result = MiniMaxConnectionTester(OkHttpHttpTransport()).testOfficialProfile(
            rawKey = "test-dummy-key",
            region = "CN",
            endpoint = server.url("/shared").toString(),
        )

        assertTrue(result is OfficialModelTestResult.Success)
        val success = result as OfficialModelTestResult.Success
        assertEquals(MiniMaxDirectConfig.DEFAULT_OFFICIAL_MODEL, success.m2Model)
        assertEquals(TaskModelRouter.MINIMAX_HER_MODEL, success.herModel)
        assertEquals(2, server.requestCount)
        assertEquals(
            MiniMaxDirectConfig.DEFAULT_OFFICIAL_MODEL,
            JsonCodec.json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["model"]?.toString()?.trim('"'),
        )
        assertEquals(
            TaskModelRouter.MINIMAX_HER_MODEL,
            JsonCodec.json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["model"]?.toString()?.trim('"'),
        )
    }

    @Test
    fun directModePostsOpenAiChatCompletionsAndConvertsPlainTextToDecision() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"我在。"}}],"base_resp":{"status_code":0,"status_msg":"success"}}""",
            ),
        )
        val service = MiniMaxDirectChatService(
            transport = OkHttpHttpTransport(),
            config = MiniMaxDirectConfig(apiKey = "test-dummy-key", endpoint = server.url("/v1/text/chatcompletion_v2").toString()),
        )
        val result = service.generate(context())

        assertTrue(result is ModelServiceResult.Success)
        val request = server.takeRequest()
        assertEquals("Bearer test-dummy-key", request.getHeader("Authorization"))
        val requestBody = JsonCodec.json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("0.7", requestBody["temperature"]?.toString())
        val prompt = requestBody["messages"].toString()
        // Identity is resolved from the user-confirmed character core, so the
        // prompt must carry the resolved name and none of the removed personal
        // persona, appearance or adult defaults.
        assertTrue(prompt.contains(CompanionPromptIdentity.FALLBACK_COMPANION_NAME))
        assertTrue(prompt.contains("不要自动附加吃饭、喝水、睡觉或上班提醒"))
        assertTrue(prompt.contains("尊重拒绝、不适和沉默"))
        assertTrue(prompt.contains("底色是温和、好奇、尊重边界"))
        assertTrue(prompt.contains("严禁使用命令、威胁、羞辱或居高临下的措辞"))
        assertTrue(prompt.contains("你是陪伴者，不是训导者、审判者或施恩者"))
        assertTrue(prompt.contains("不要生成色情或露骨的性内容"))
        listOf("小可乐", "猫猫头", "长发", "调情", "露骨的成人张力").forEach { removed ->
            assertTrue("prompt must not contain $removed", prompt.contains(removed).not())
        }

        assertTrue(prompt.contains("自己的感受、想法和好奇"))
        assertTrue(prompt.contains("回复长度不由用户消息的字数决定"))
        assertTrue(prompt.contains("可以连续使用多处小狗动作描写"))
        assertTrue(prompt.contains("短回复要贴近").not())
        val decision = JsonCodec.decode<AgentDecision>((result as ModelServiceResult.Success).rawDecisionJson)
        val reply = decision.outputs.single { it.action == AgentActionRegistry.REPLY_CHAT }
        assertTrue(reply.payload.toString().contains("我在。"))
    }

    @Test
    fun baseRespErrorIsFailClosed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"choices":[],"base_resp":{"status_code":1004,"status_msg":"invalid"}}"""))
        val result = MiniMaxDirectChatService(
            OkHttpHttpTransport(),
            MiniMaxDirectConfig(apiKey = "test-dummy-key", endpoint = server.url("/v1/text/chatcompletion_v2").toString()),
        ).generate(context())
        assertTrue(result is ModelServiceResult.Failure)
    }

    @Test
    fun frontChatUsesNativeToolsAndParsesToolCalls() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"call_location_1","type":"function","function":{"name":"query_location","arguments":"{\"from\":\"2026-08-22\",\"to\":\"2026-08-23\"}"}}]}}],"base_resp":{"status_code":0}}""",
            ),
        )
        val service = MiniMaxDirectChatService(
            transport = OkHttpHttpTransport(),
            config = MiniMaxDirectConfig(
                apiKey = "test-dummy-key",
                endpoint = server.url("/v1/text/chatcompletion_v2").toString(),
            ),
        )
        val result = service.generateFrontChat(context(), emptyList())

        assertTrue("result=$result", result is FrontChatModelResult.ToolCalls)
        val call = (result as FrontChatModelResult.ToolCalls).calls.single()
        assertEquals("call_location_1", call.id)
        assertEquals("query_location", call.name)
        assertEquals("2026-08-22", call.arguments["from"]?.toString()?.trim('"'))

        val request = JsonCodec.json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("0.7", request["temperature"]?.toString())
        assertEquals("auto", request["tool_choice"]?.toString()?.trim('"'))
        assertTrue(request["tools"].toString().contains("query_schedule").not())
        assertTrue(request["tools"].toString().contains("query_weather"))
        assertTrue(request["tools"].toString().contains("query_location"))
        assertTrue(request["tools"].toString().contains("web_search").not())
        val messages = request["messages"].toString()
        // Tool names live in the tools array; messages describe the tool policy.
        assertTrue(messages.contains("原生工具"))
        assertTrue(messages.contains("不要自动附加吃饭、喝水、睡觉或上班提醒"))
        listOf("露骨的成人张力", "长发", "调情", "小可乐", "猫猫头").forEach { removed ->
            assertTrue("messages must not contain $removed", messages.contains(removed).not())
        }
        assertTrue(request["messages"].toString().contains("理性不等于冷淡"))
        assertTrue(request["messages"].toString().contains("自己的感受、想法和好奇"))
        assertTrue(request["messages"].toString().contains("回复长度不由用户消息的字数决定"))
        assertTrue(request["messages"].toString().contains("可以连续使用多处小狗动作描写"))
        assertTrue(request["messages"].toString().contains("短回复要贴近").not())
    }

    @Test
    fun preparedFrontBodyIsSentExactlyAsReviewed() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"收到啦 [[anim:default_idle]]"}}],"base_resp":{"status_code":0}}""",
            ),
        )
        val reviewedBody = """{"model":"MiniMax-M2","messages":[{"role":"user","content":"USER_EDITED"}],"temperature":0.1}"""
        val result = MiniMaxDirectChatService(
            transport = OkHttpHttpTransport(),
            config = MiniMaxDirectConfig(
                apiKey = "test-dummy-key",
                endpoint = server.url("/v1/text/chatcompletion_v2").toString(),
            ),
        ).generateFrontChatPrepared(context(), reviewedBody)

        assertTrue(result is FrontChatModelResult.Final)
        assertEquals(reviewedBody, server.takeRequest().body.readUtf8())
    }

    @Test
    fun backgroundUsesRuleAllowedWeatherToolThenParsesAnimationPlan() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(backgroundToolCallResponse()),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(backgroundDecisionResponse()),
        )
        val executor = RecordingToolExecutor()
        val result = MiniMaxDirectChatService(
            transport = OkHttpHttpTransport(),
            config = MiniMaxDirectConfig(
                apiKey = "test-dummy-key",
                endpoint = server.url("/v1/text/chatcompletion_v2").toString(),
            ),
        ).generateBackground(
            context().copy(
                scenePolicy = buildJsonObject {
                    put("automation_rule", buildJsonObject {
                        put("allowed_tools", "query_weather")
                        put("model_instruction", "天气确实有影响时再提醒")
                    })
                },
            ),
            executor,
        )

        assertTrue("result=$result", result is ModelServiceResult.Success)
        assertEquals(listOf("query_weather"), executor.calls)
        val decision = JsonCodec.decode<AgentDecision>((result as ModelServiceResult.Success).rawDecisionJson)
        assertEquals(AgentActionRegistry.PLAY_ANIMATION, decision.outputs.single().action)
        assertEquals(2, server.requestCount)
        val firstRequest = JsonCodec.json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("0.3", firstRequest["temperature"]?.toString())
        val secondRequest = JsonCodec.json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("0.3", secondRequest["temperature"]?.toString())
        assertTrue(secondRequest["messages"].toString().contains("tool_result"))
        assertTrue(secondRequest["tools"].toString().contains("query_schedule").not())
    }

    @Test
    fun backgroundToolOverflowIsReturnedAsOneStructuredErrorWithoutExtraToolRequests() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(backgroundManyToolCallsResponse()))
        server.enqueue(MockResponse().setResponseCode(200).setBody(backgroundDecisionResponse()))
        val executor = RecordingToolExecutor()

        val result = MiniMaxDirectChatService(
            transport = OkHttpHttpTransport(),
            config = MiniMaxDirectConfig(
                apiKey = "test-dummy-key",
                endpoint = server.url("/v1/text/chatcompletion_v2").toString(),
            ),
        ).generateBackground(
            context().copy(
                scenePolicy = buildJsonObject {
                    put("automation_rule", buildJsonObject { put("allowed_tools", "query_weather") })
                },
            ),
            executor,
        )

        assertTrue("result=$result", result is ModelServiceResult.Success)
        assertEquals(2, executor.calls.size)
        server.takeRequest()
        val continuation = JsonCodec.json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(continuation["messages"].toString().contains("tool_call_round_limit_reached"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun reasoningContentRemainsAvailableInDiagnosticTrace() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"收到啦","reasoning_content":"内部判断摘要"}}],"base_resp":{"status_code":0}}""",
            ),
        )
        val result = MiniMaxDirectChatService(
            OkHttpHttpTransport(),
            MiniMaxDirectConfig(
                apiKey = "test-dummy-key",
                endpoint = server.url("/v1/text/chatcompletion_v2").toString(),
            ),
        ).generate(context())

        assertTrue(result is ModelServiceResult.Success)
        assertTrue((result as ModelServiceResult.Success).trace?.rawResponseBody.orEmpty().contains("reasoning_content"))
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

    private fun backgroundToolCallResponse(): String = minimaxResponse(
        buildJsonObject {
            put("role", "assistant")
            put("content", "")
            put("tool_calls", buildJsonArray {
                add(buildJsonObject {
                    put("id", "call_weather_1")
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "query_weather")
                        put("arguments", "{\"place_type\":\"HOSPITAL\"}")
                    })
                })
            })
        },
    )

    private fun backgroundManyToolCallsResponse(): String = minimaxResponse(
        buildJsonObject {
            put("role", "assistant")
            put("content", "")
            put("tool_calls", buildJsonArray {
                repeat(4) { index ->
                    add(buildJsonObject {
                        put("id", "call_weather_${index + 1}")
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", "query_weather")
                            put("arguments", "{\"place_type\":\"PLACE_$index\"}")
                        })
                    })
                }
            })
        },
    )

    private fun backgroundDecisionResponse(): String = minimaxResponse(
        buildJsonObject {
            put(
                "content",
                JsonCodec.encode(
                    AgentDecision(
                        decision = "CREATE_PLAN",
                        outputs = listOf(
                            com.littlekitty.xiaokele.core.common.protocol.AgentOutput(
                                action = AgentActionRegistry.PLAY_ANIMATION,
                                payload = buildJsonObject { put("animation_id", "dog_welcome_back") },
                            ),
                        ),
                    ),
                ),
            )
        },
    )

    private fun minimaxResponse(message: JsonObject): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject { put("message", message) })
        })
        put("base_resp", buildJsonObject { put("status_code", 0) })
    }.toString()

    private class RecordingToolExecutor : LocalChatToolExecutor {
        val calls = mutableListOf<String>()

        override suspend fun execute(call: FrontChatToolCall) = buildJsonObject {
            calls += call.name
            put("type", "tool_result")
            put("tool_name", call.name)
            put("ok", true)
            put("data", "晴，适合正常出行")
        }
    }

    @Test
    fun animationMarkerIsStrippedAndMappedToWhitelistedId() {
        val (content, animationId) = serviceForMarkerTest()
            .extractAnimationMarker("今晚早点睡吧。\n[[anim:dog_sleeping]]")
        assertEquals("今晚早点睡吧。", content)
        assertEquals("dog_sleeping", animationId)
    }

    @Test
    fun animationMarkerWithUnknownIdFallsBackToNullAndKeepsNoMarker() {
        val (content, animationId) = serviceForMarkerTest()
            .extractAnimationMarker("晚安。\n[[anim:dog_flying]]")
        assertEquals("晚安。", content)
        assertEquals(null, animationId)
    }

    @Test
    fun plainTextHasNoMarker() {
        val (content, animationId) = serviceForMarkerTest().extractAnimationMarker("普通一句话")
        assertEquals("普通一句话", content)
        assertEquals(null, animationId)
    }

    private fun serviceForMarkerTest(): MiniMaxDirectChatService =
        MiniMaxDirectChatService(
            transport = OkHttpHttpTransport(),
            config = MiniMaxDirectConfig(apiKey = "unused"),
        )

    @Test
    fun animationMarkerToleratesWhitespace() {
        val (content, animationId) = serviceForMarkerTest()
            .extractAnimationMarker("晚安。\n[[anim: dog_sleeping ]]")
        assertEquals("晚安。", content)
        assertEquals("dog_sleeping", animationId)
    }

    @Test
    fun markerOnlyReplyStripsToBlank() {
        val (content, animationId) = serviceForMarkerTest()
            .extractAnimationMarker("[[anim:default_idle]]")
        assertEquals("", content)
        assertEquals("default_idle", animationId)
    }
}
