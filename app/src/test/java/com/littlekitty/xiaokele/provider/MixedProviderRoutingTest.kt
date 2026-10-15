package com.littlekitty.xiaokele.provider

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

/**
 * Mixed-provider routing contract.
 *
 * The product supports two shapes and both must keep working:
 *  - mixed: A/B1/C/D run on Bailian DeepSeek while B2 uses MiniMax, which is the
 *    default routing of the original product;
 *  - single-provider: every Agent runs on the one configured service.
 *
 * In both shapes a request must carry the credential of *its own* provider. A
 * route must never fall back to the other provider's key.
 */
class MixedProviderRoutingTest {

    private lateinit var miniMaxServer: MockWebServer
    private lateinit var bailianServer: MockWebServer

    private val minimaxKey = "minimax-test-key-0001"
    private val bailianKey = "bailian-test-key-0002"

    @Before
    fun setUp() {
        miniMaxServer = MockWebServer().also { it.start() }
        bailianServer = MockWebServer().also { it.start() }
        installDefaultResponses()
    }

    @After
    fun tearDown() {
        miniMaxServer.shutdown()
        bailianServer.shutdown()
    }

    private fun miniMaxResponse(): MockResponse = MockResponse().setResponseCode(200).setBody(
        """{"choices":[{"message":{"content":"好的。"}}],"base_resp":{"status_code":0,"status_msg":"success"}}""",
    )

    private fun bailianResponse(): MockResponse = MockResponse().setResponseCode(200).setBody(
        """{"choices":[{"message":{"content":"好的。"}}]}""",
    )

    /**
     * A dispatcher keeps every request answered, so a routing assertion failure
     * surfaces as a failing assertion instead of a blocking dequeue on a server
     * that never receives its response.
     */
    private fun installDefaultResponses() {
        miniMaxServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                miniMaxResponse()
        }
        bailianServer.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                bailianResponse()
        }
    }

    private fun takeFrom(server: MockWebServer): okhttp3.mockwebserver.RecordedRequest =
        requireNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) {
            "expected a request on ${server.hostName}:${server.port} but none arrived"
        }

    private fun config(routes: Map<TaskKind, AgentModelRoute>) = object : ByokRuntimeConfig {
        override fun loadKeyOrNull(): String = minimaxKey
        override fun endpoint(): String = miniMaxServer.url("/v1/text/chatcompletion_v2").toString()
        override fun region(): String = "CUSTOM"
        override fun model(): String = TaskModelRouter.MINIMAX_M2_MODEL
        override fun bailianKeyOrNull(): String = bailianKey
        override fun bailianEndpoint(): String =
            bailianServer.url("/compatible-mode/v1/chat/completions").toString()

        override fun bailianModelsValidated(): Boolean = true
        override fun officialModelsValidated(): Boolean = true
        override fun officialLegacyCompatibilityAllowed(): Boolean = true
        override fun agentModelRoute(task: TaskKind, complexity: RouteComplexity): AgentModelRoute =
            routes.getValue(task)

        override fun reserveGenerationRequest(isPro: Boolean, nowMillis: Long): Boolean = true
        override fun <T> withRouteRead(block: () -> T): T = block()
    }

    private val mixedRoutes = mapOf(
        TaskKind.A_INTAKE to AgentModelRoute(ModelProvider.BAILIAN, TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL),
        TaskKind.B1_DECISION to AgentModelRoute(ModelProvider.BAILIAN, TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL),
        TaskKind.B2_EXPRESSION to AgentModelRoute(ModelProvider.MINIMAX_OFFICIAL, TaskModelRouter.MINIMAX_HER_MODEL),
        TaskKind.C_REVIEW to AgentModelRoute(ModelProvider.BAILIAN, TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL),
        TaskKind.D_MEMORY to AgentModelRoute(ModelProvider.BAILIAN, TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL),
    )

    /**
     * Single-provider MiniMax configuration.
     *
     * `M2-her` is plain-text-only by contract ("HER is never used for A/C/D and
     * must not be asked for JSON or tools"), so a MiniMax-only setup must route
     * the structured Agents to `MiniMax-M2` and keep only expression on `M2-her`.
     * A configuration that points every Agent at `M2-her` is rejected by the
     * gateway, which is its own (valuable) fail-closed behaviour.
     */
    private val singleProviderRoutes = TaskKind.values().associateWith { task ->
        if (task == TaskKind.B2_EXPRESSION) {
            AgentModelRoute(ModelProvider.MINIMAX_OFFICIAL, TaskModelRouter.MINIMAX_HER_MODEL)
        } else {
            AgentModelRoute(ModelProvider.MINIMAX_OFFICIAL, TaskModelRouter.MINIMAX_M2_MODEL)
        }
    }

    private suspend fun generate(gateway: ProfiledTaskModelGateway, task: TaskKind) {
        gateway.generate(
            TaskModelRequest(
                task = task,
                userPayload = """{"probe":"mixed-provider-routing"}""",
                systemPrompt = "你是陪聊助手。",
            ),
        )
    }

    /** Drains every recorded request so assertions never block on an empty queue. */
    private fun drain(server: MockWebServer): List<okhttp3.mockwebserver.RecordedRequest> {
        val drained = mutableListOf<okhttp3.mockwebserver.RecordedRequest>()
        while (true) {
            val recorded = server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                ?: break
            drained += recorded
        }
        return drained
    }

    private fun resetCounts() {
        runCatching { drain(miniMaxServer) }
        runCatching { drain(bailianServer) }
    }

    @Test
    fun mixedRoutingSendsEachRoleToItsOwnProvider() = runBlocking {
        val gateway = ProfiledTaskModelGateway(config(mixedRoutes), OkHttpHttpTransport())

        listOf(TaskKind.A_INTAKE, TaskKind.B1_DECISION, TaskKind.C_REVIEW, TaskKind.D_MEMORY).forEach { task ->
            resetCounts()
            generate(gateway, task)

            val bailianCalls = drain(bailianServer)
            val miniMaxCalls = drain(miniMaxServer)

            assertTrue("$task must reach Bailian", bailianCalls.isNotEmpty())
            assertTrue("MiniMax must not serve $task", miniMaxCalls.isEmpty())
            bailianCalls.forEach { recorded ->
                assertEquals(
                    "$task must carry the Bailian credential",
                    "Bearer $bailianKey",
                    recorded.getHeader("Authorization"),
                )
            }
        }

        resetCounts()
        generate(gateway, TaskKind.B2_EXPRESSION)

        val miniMaxCalls = drain(miniMaxServer)
        assertTrue("B2 must reach MiniMax", miniMaxCalls.isNotEmpty())
        assertTrue("Bailian must not serve B2", drain(bailianServer).isEmpty())
        miniMaxCalls.forEach { recorded ->
            assertEquals(
                "B2 must carry the MiniMax credential",
                "Bearer $minimaxKey",
                recorded.getHeader("Authorization"),
            )
        }
    }

    @Test
    fun singleProviderRoutingKeepsEveryRoleOnThatProvider() = runBlocking {
        val gateway = ProfiledTaskModelGateway(config(singleProviderRoutes), OkHttpHttpTransport())

        TaskKind.values().forEach { task ->
            resetCounts()
            generate(gateway, task)

            val miniMaxCalls = drain(miniMaxServer)
            assertTrue("single-provider $task must reach MiniMax", miniMaxCalls.isNotEmpty())
            assertTrue("Bailian must not be called for $task", drain(bailianServer).isEmpty())
            miniMaxCalls.forEach { recorded ->
                assertEquals(
                    "single-provider $task must carry the MiniMax credential",
                    "Bearer $minimaxKey",
                    recorded.getHeader("Authorization"),
                )
            }
        }
    }

    @Test
    fun bailianRouteNeverCarriesTheMiniMaxCredential() = runBlocking {
        val gateway = ProfiledTaskModelGateway(config(mixedRoutes), OkHttpHttpTransport())

        generate(gateway, TaskKind.B1_DECISION)

        val bailianCalls = drain(bailianServer)
        assertTrue(bailianCalls.isNotEmpty())
        bailianCalls.forEach { recorded ->
            val authorization = recorded.getHeader("Authorization").orEmpty()
            assertEquals("Bearer $bailianKey", authorization)
            assertTrue(
                "the MiniMax key must not leak onto a Bailian request",
                authorization.contains(minimaxKey).not(),
            )
        }
        assertTrue("MiniMax must not be called for a Bailian route", drain(miniMaxServer).isEmpty())
    }

    @Test
    fun expressionOnlyModelIsRefusedForStructuredAgents() = runBlocking {
        val herEverywhere = TaskKind.values().associateWith {
            AgentModelRoute(ModelProvider.MINIMAX_OFFICIAL, TaskModelRouter.MINIMAX_HER_MODEL)
        }
        val gateway = ProfiledTaskModelGateway(config(herEverywhere), OkHttpHttpTransport())

        val outcome = gateway.generate(
            TaskModelRequest(
                task = TaskKind.B1_DECISION,
                userPayload = """{"probe":"structured-on-plain-text-model"}""",
                systemPrompt = "只输出 JSON。",
                structured = true,
            ),
        )

        // Fail-closed: no plain-text model may be asked to produce our decision
        // JSON, and no request is sent.
        assertTrue("structured output must be refused on M2-her", outcome is TaskModelResult.Failure)
        assertTrue("no request may be sent for a refused route", drain(miniMaxServer).isEmpty())
        assertTrue(drain(bailianServer).isEmpty())
    }

    @Test
    fun defaultRoutingIsMixedLikeTheOriginalProduct() {
        val defaults = defaultAgentModelRoutes()

        assertEquals(ModelProvider.BAILIAN, defaults.getValue(TaskKind.A_INTAKE).provider)
        assertEquals(ModelProvider.BAILIAN, defaults.getValue(TaskKind.B1_DECISION).provider)
        assertEquals(ModelProvider.MINIMAX_OFFICIAL, defaults.getValue(TaskKind.B2_EXPRESSION).provider)
        assertEquals(ModelProvider.BAILIAN, defaults.getValue(TaskKind.C_REVIEW).provider)
        assertEquals(ModelProvider.BAILIAN, defaults.getValue(TaskKind.D_MEMORY).provider)
    }
}
