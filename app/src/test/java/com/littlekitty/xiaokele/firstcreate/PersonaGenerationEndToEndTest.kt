package com.littlekitty.xiaokele.firstcreate

import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.OkHttpHttpTransport
import com.littlekitty.xiaokele.provider.ModelProvider
import com.littlekitty.xiaokele.provider.ProfiledTaskModelGateway
import com.littlekitty.xiaokele.provider.RouteComplexity
import com.littlekitty.xiaokele.provider.AgentModelRoute
import com.littlekitty.xiaokele.provider.TaskKind
import com.littlekitty.xiaokele.settings.ByokRuntimeConfig
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end persona generation over the real task gateway and a mock provider.
 *
 * The unit tests cover the parser in isolation; this one proves the wiring: that
 * the brief actually travels to the provider, that the response is turned into a
 * confirmed persona, and that a provider that answers with prose instead of the
 * schema cannot produce a locked persona.
 */
class PersonaGenerationEndToEndTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config() = object : ByokRuntimeConfig {
        override fun loadKeyOrNull(): String = "fixture-key"
        override fun endpoint(): String = server.url("/v1/text/chatcompletion_v2").toString()
        override fun region(): String = "CUSTOM"
        override fun model(): String = "MiniMax-M3"
        override fun bailianKeyOrNull(): String = "fixture-key"
        override fun bailianEndpoint(): String = server.url("/compatible-mode/v1/chat/completions").toString()
        override fun bailianModelsValidated(): Boolean = true
        override fun officialModelsValidated(): Boolean = true
        override fun officialLegacyCompatibilityAllowed(): Boolean = true
        override fun agentModelRoute(task: TaskKind, complexity: RouteComplexity): AgentModelRoute =
            AgentModelRoute(ModelProvider.BAILIAN, "deepseek-v4-flash", "deepseek-v4-pro")

        override fun reserveGenerationRequest(isPro: Boolean, nowMillis: Long): Boolean = true
        override fun <T> withRouteRead(block: () -> T): T = block()
    }

    private fun gateway(): ProfiledTaskModelGateway {
        val transport: HttpTransport = object : HttpTransport {
            override suspend fun execute(request: com.littlekitty.xiaokele.core.network.HttpRequest) =
                OkHttpHttpTransport().execute(request.copy(url = server.url("/compatible-mode/v1/chat/completions").toString()))
        }
        return ProfiledTaskModelGateway(config(), transport)
    }

    private val input = PersonaInput(
        petName = "豆豆",
        userAddress = "小雨",
        relationship = "一起住的小狗",
        desiredPersonality = "安静、喜欢听人说话，不吵",
        desiredTowardUser = "把我当朋友，别管我，也不要叫我主人",
        desiredStyle = "短句，少用感叹号",
        avoid = "不要撒娇黏人",
    )

    private fun providerReply(content: String): String {
        val escaped = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        return """{"choices":[{"message":{"content":"$escaped"}}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}"""
    }

    @Test
    fun aWellFormedProviderReplyBecomesAConfirmablePersona() = runBlocking {
        // Providers in the wild wrap JSON in prose and fences; the pipeline must
        // still produce a usable persona from that.
        val persona = """
            {
              "persona_anchor": "一只安静的小狗，习惯先听完再开口，不抢话也不催促。对方说累的时候先陪着，不急着给建议。",
              "expression_dna": ["先说理解，再回应。", "不编造没发生的事。", "对方拒绝时立刻停下。", "不确定时直接说不确定。"],
              "how_it_sees_user": "把对方当成一个需要被认真听的独立的人，而不是需要被照顾或管理的对象。"
            }
        """.trimIndent()
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                providerReply("好的，这是整理后的设定：\n```json\n$persona\n```"),
            ),
        )

        val result = ModelPersonaGenerator(gateway()).generate(input)

        assertTrue("expected a persona, got $result", result is PersonaGenerationResult.Success)
        val generated = (result as PersonaGenerationResult.Success).persona
        assertEquals(4, generated.expressionDna.size)
        assertTrue(generated.personaAnchor.contains("安静"))

        // The request must actually carry what the user typed, and must ask for
        // structured output on a slot that supports it.
        val recorded = server.takeRequest()
        val body = recorded.body.readUtf8()
        assertTrue("brief must reach the provider", body.contains("安静、喜欢听人说话，不吵"))
        assertTrue(body.contains("别管我"))
        assertTrue(body.contains("不要撒娇黏人"))
        assertTrue("must request JSON output", body.contains("json_object"))

        // And it must be lockable, which is what confirmation requires.
        val text = PersonaPromptSpec.renderConfirmedText(input, generated)
        val draft = FirstCreateDraft(
            stage = FirstCreateStage.CONFIRM,
            petName = "豆豆",
            userAddress = "小雨",
            relationship = "一起住的小狗",
            declaredAge = 30,
            declaredAgeRecordedAt = 1L,
            personaGeneratorProvider = SelectedGenerationProvider.BAILIAN,
            personaGeneratorModel = "deepseek-v4-flash",
            personaDraft = text,
        )
        assertEquals(FirstCreateValidation.Valid, FirstCreateRules.canConfirm(draft))
    }

    @Test
    fun proseInsteadOfSchemaCannotBecomeALockedPersona() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                providerReply("我觉得可以把它写成一只温柔的小狗，你觉得呢？"),
            ),
        )

        val result = ModelPersonaGenerator(gateway()).generate(input)

        assertTrue(
            "unstructured output must not be accepted",
            result is PersonaGenerationResult.Failure,
        )
    }

    @Test
    fun aProviderErrorSurfacesInsteadOfAFabricatedPersona() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))

        val result = ModelPersonaGenerator(gateway()).generate(input)

        assertTrue(result is PersonaGenerationResult.Failure)
        // The flow must not present generated text when the call failed.
        assertTrue((result as PersonaGenerationResult.Failure).reason.isNotBlank())
    }
}
