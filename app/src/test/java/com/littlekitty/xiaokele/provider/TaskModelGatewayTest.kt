package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import com.littlekitty.xiaokele.settings.ByokRuntimeConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskModelGatewayTest {
    @Test
    fun truncatedPlainTextIsNotPublishedAsACompleteReply() = runBlocking {
        val gateway = ProfiledTaskModelGateway(settings = settings(validated = true), transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): NetworkResult = NetworkResult.Success(200, emptyMap(),
                """{"choices":[{"finish_reason":"length","message":{"content":"我可以帮你"}}]}""")
        })
        val result = gateway.generate(TaskModelRequest(TaskKind.B2_EXPRESSION, "{}"))
        assertTrue(result is TaskModelResult.Failure)
        assertTrue((result as TaskModelResult.Failure).error.technicalDetail.orEmpty().contains("model_output_truncated"))
        assertEquals(null, result.recoverableContent)
    }

    @Test
    fun cancellationIsNotConvertedToProviderFailureOrRetried() = runBlocking {
        val gateway = ProfiledTaskModelGateway(settings = settings(validated = true), transport = object : HttpTransport {
            override suspend fun execute(request: HttpRequest): NetworkResult = throw kotlinx.coroutines.CancellationException("cancel")
        })
        var cancelled = false
        try { gateway.generate(TaskModelRequest(TaskKind.A_INTAKE, "{}")) }
        catch (_: kotlinx.coroutines.CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
    @Test
    fun completeInvalidJsonIsAvailableOnlyForEphemeralRepair() = runBlocking {
        val gateway = ProfiledTaskModelGateway(
            settings = settings(validated = true),
            transport = object : HttpTransport {
                override suspend fun execute(value: HttpRequest): NetworkResult = NetworkResult.Success(
                    statusCode = 200, headers = emptyMap(),
                    body = """{"choices":[{"finish_reason":"stop","message":{"content":"{\"task\" \"A_INTAKE\"}"}}]}""",
                )
            },
        )
        val result = gateway.generate(TaskModelRequest(TaskKind.A_INTAKE, "{}"))
        assertTrue(result is TaskModelResult.Failure)
        assertEquals("""{"task" "A_INTAKE"}""", (result as TaskModelResult.Failure).recoverableContent)
        assertFalse(result.error.technicalDetail.orEmpty().contains("A_INTAKE"))
    }

    @Test
    fun truncatedJsonNeverBecomesRepairCandidate() = runBlocking {
        val gateway = ProfiledTaskModelGateway(
            settings = settings(validated = true),
            transport = object : HttpTransport {
                override suspend fun execute(value: HttpRequest): NetworkResult = NetworkResult.Success(
                    statusCode = 200, headers = emptyMap(),
                    body = """{"choices":[{"finish_reason":"length","message":{"content":"{\"task\":\"A_INTAKE\""}}]}""",
                )
            },
        )
        val result = gateway.generate(TaskModelRequest(TaskKind.A_INTAKE, "{}"))
        assertTrue(result is TaskModelResult.Failure)
        assertEquals(null, (result as TaskModelResult.Failure).recoverableContent)
    }
    @Test
    fun defaultStructuredTasksUseConfiguredBuiltInModel() = runBlocking {
        var request: HttpRequest? = null
        val gateway = ProfiledTaskModelGateway(
            settings = settings(validated = true),
            transport = object : HttpTransport {
                override suspend fun execute(value: HttpRequest): NetworkResult {
                    request = value
                    return NetworkResult.Success(statusCode = 200, headers = emptyMap(), body = """{"choices":[{"message":{"content":"{}"}}],"usage":{"total_tokens":3}}""")
                }
            },
        )
        val result = gateway.generate(TaskModelRequest(TaskKind.A_INTAKE, "{}"))
        assertTrue(result is TaskModelResult.Success)
        assertTrue(request!!.body.orEmpty().contains(TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL))
        assertFalse(request!!.body.orEmpty().contains("legacy-model"))
    }

    @Test
    fun structuredResponseMayBeWrappedInAMarkdownFence() = runBlocking {
        val gateway = ProfiledTaskModelGateway(
            settings = settings(validated = true),
            transport = object : HttpTransport {
                override suspend fun execute(value: HttpRequest): NetworkResult =
                    NetworkResult.Success(
                        statusCode = 200,
                        headers = emptyMap(),
                        body = """{"choices":[{"message":{"content":"```json\\n{}\\n```"}}]}""",
                    )
            },
        )

        val result = gateway.generate(TaskModelRequest(TaskKind.A_INTAKE, "{}"))

        assertTrue(result is TaskModelResult.Success)
        assertEquals("{}", (result as TaskModelResult.Success).content)
    }

    @Test
    fun officialM2AndHerUseTheirDocumentedEndpointsWhenNoCustomEndpointIsSaved() = runBlocking {
        val requests = mutableListOf<HttpRequest>()
        val gateway = ProfiledTaskModelGateway(
            settings = settings(
                routes = officialRoutes(),
            ).copyEndpoint(""),
            transport = object : HttpTransport {
                override suspend fun execute(value: HttpRequest): NetworkResult {
                    requests += value
                    val content = if (value.body.orEmpty().contains(TaskModelRouter.MINIMAX_HER_MODEL)) "纯文本" else "{}"
                    return NetworkResult.Success(statusCode = 200, headers = emptyMap(), body = """{"choices":[{"message":{"content":"$content"}}]}""")
                }
            },
        )
        assertTrue(gateway.generate(TaskModelRequest(TaskKind.A_INTAKE, "{}")) is TaskModelResult.Success)
        assertTrue(gateway.generate(TaskModelRequest(TaskKind.B2_EXPRESSION, "{}", structured = false)) is TaskModelResult.Success)
        assertEquals(MiniMaxDirectConfig.M2_CN_ENDPOINT, requests[0].url)
        assertEquals(MiniMaxDirectConfig.CN_ENDPOINT, requests[1].url)
    }

    @Test
    fun unvalidatedBailianCannotSendRequest() = runBlocking {
        var called = false
        val gateway = ProfiledTaskModelGateway(
            settings = settings(validated = false),
            transport = object : HttpTransport {
                override suspend fun execute(value: HttpRequest): NetworkResult {
                    called = true
                    return NetworkResult.Success(statusCode = 200, headers = emptyMap(), body = "{}")
                }
            },
        )
        val result = gateway.generate(TaskModelRequest(TaskKind.B1_DECISION, "{}"))
        assertTrue(result is TaskModelResult.Failure)
        assertFalse(called)
    }

    @Test
    fun changedOfficialConfigurationFailsClosedUntilDualProofIsRestored() = runBlocking {
        var called = false
        val gateway = ProfiledTaskModelGateway(
            settings = settings(
                officialValidated = false,
                officialLegacyAllowed = false,
                routes = officialRoutes(),
            ),
            transport = object : HttpTransport {
                override suspend fun execute(value: com.littlekitty.xiaokele.core.network.HttpRequest): NetworkResult {
                    called = true
                    error("official route must be blocked")
                }
            },
        )

        val result = gateway.generate(TaskModelRequest(TaskKind.A_INTAKE, "{}"))

        assertTrue(result is TaskModelResult.Failure)
        assertFalse(called)
        assertEquals(
            "official_profile_not_validated",
            (result as TaskModelResult.Failure).error.technicalDetail,
        )
    }

    @Test
    fun complexProUsesOnlyOneReservation() = runBlocking {
        val gateway = ProfiledTaskModelGateway(
            settings = settings(validated = true),
            transport = object : HttpTransport {
                override suspend fun execute(value: HttpRequest): NetworkResult =
                    NetworkResult.Success(statusCode = 200, headers = emptyMap(), body = """{"choices":[{"message":{"content":"{}"}}]}""")
            },
        )
        val budget = GenerationBudget()
        assertTrue(gateway.generate(TaskModelRequest(TaskKind.B1_DECISION, "{}", complexity = RouteComplexity.COMPLEX), budget) is TaskModelResult.Success)
        assertTrue(gateway.generate(TaskModelRequest(TaskKind.C_REVIEW, "{}", complexity = RouteComplexity.COMPLEX), budget) is TaskModelResult.Failure)
        assertEquals(1, budget.proRequestsUsed())
    }

    @Test
    fun flashRequestsDoNotConsumeProReservation() = runBlocking {
        val gateway = ProfiledTaskModelGateway(
            settings = settings(validated = true),
            transport = object : HttpTransport {
                override suspend fun execute(value: HttpRequest): NetworkResult =
                    NetworkResult.Success(statusCode = 200, headers = emptyMap(), body = """{"choices":[{"message":{"content":"{}"}}]}""")
            },
        )
        val budget = GenerationBudget()
        assertTrue(gateway.generate(TaskModelRequest(TaskKind.A_INTAKE, "{}"), budget) is TaskModelResult.Success)
        assertTrue(gateway.generate(TaskModelRequest(TaskKind.C_REVIEW, "{}"), budget) is TaskModelResult.Success)
        assertEquals(0, budget.proRequestsUsed())
    }

    @Test
    fun herRejectsStructuredRequestBeforeTransport() = runBlocking {
        var called = false
        val gateway = ProfiledTaskModelGateway(
            settings = settings(),
            transport = object : HttpTransport {
                override suspend fun execute(value: HttpRequest): NetworkResult {
                    called = true
                    return NetworkResult.Success(statusCode = 200, headers = emptyMap(), body = "{}")
                }
            },
        )
        val result = gateway.generate(TaskModelRequest(TaskKind.B2_EXPRESSION, "{}", structured = true))
        assertTrue(result is TaskModelResult.Failure)
        assertFalse(called)
    }

    /**
     * MiniMax reasoning models write their chain of thought into `content`
     * rather than a separate `reasoning_content` field, and expose no
     * `enable_thinking=false`. A live C_REVIEW response spent its whole 2048-token
     * budget on an inline `<think>` essay and was truncated before any JSON
     * appeared, so the turn failed as `structured_json_missing`. Structured
     * requests on a thinking model must therefore ask for reasoning headroom.
     */
    @Test
    fun structuredRequestsOnAThinkingModelReserveReasoningHeadroom() = runBlocking {
        val bodies = mutableListOf<String>()
        val gateway = ProfiledTaskModelGateway(
            settings = settings(routes = officialRoutes()).copyEndpoint(""),
            transport = object : HttpTransport {
                override suspend fun execute(value: HttpRequest): NetworkResult {
                    bodies += value.body.orEmpty()
                    val content = if (value.body.orEmpty().contains(TaskModelRouter.MINIMAX_HER_MODEL)) "纯文本" else "{}"
                    return NetworkResult.Success(
                        statusCode = 200,
                        headers = emptyMap(),
                        body = """{"choices":[{"message":{"content":"$content"}}]}""",
                    )
                }
            },
        )

        gateway.generate(TaskModelRequest(TaskKind.C_REVIEW, "{}", structured = true))
        gateway.generate(TaskModelRequest(TaskKind.B2_EXPRESSION, "{}", structured = false))

        val reviewBody = bodies.first { it.contains(TaskModelRouter.MINIMAX_M2_MODEL) }
        val expressionBody = bodies.first { it.contains(TaskModelRouter.MINIMAX_HER_MODEL) }
        // Reasoning headroom on top of the visible-answer budget.
        assertTrue(
            "a thinking model must be allowed room for inline reasoning, body=$reviewBody",
            "\"max_completion_tokens\":4096" in reviewBody,
        )
        // The plain-text expression model does not reason, so it keeps the base budget.
        assertTrue(
            "the expression model should keep the base budget, body=$expressionBody",
            "\"max_completion_tokens\":1024" in expressionBody,
        )
    }

    @Test
    fun bailianKeepsTheBaseBudgetBecauseItsThinkingIsDisabled() = runBlocking {
        var body = ""
        val gateway = ProfiledTaskModelGateway(
            settings = settings(validated = true),
            transport = object : HttpTransport {
                override suspend fun execute(value: HttpRequest): NetworkResult {
                    body = value.body.orEmpty()
                    return NetworkResult.Success(
                        statusCode = 200,
                        headers = emptyMap(),
                        body = """{"choices":[{"message":{"content":"{}"}}]}""",
                    )
                }
            },
        )

        gateway.generate(TaskModelRequest(TaskKind.A_INTAKE, "{}", structured = true))

        // Bailian can turn reasoning off explicitly, so it needs no headroom.
        assertTrue("Bailian must disable thinking, body=$body", "\"enable_thinking\":false" in body)
        assertTrue("Bailian must keep the base budget, body=$body", "\"max_tokens\":1024" in body)
    }

    private fun settings(
        validated: Boolean = false,
        officialValidated: Boolean = true,
        officialLegacyAllowed: Boolean = true,
        routes: Map<TaskKind, AgentModelRoute> = defaultAgentModelRoutes(),
    ) = object : ByokRuntimeConfig {
        override fun loadKeyOrNull(): String = "mini-key"
        override fun endpoint(): String = "https://api.minimaxi.com/v1/text/chatcompletion_v2"
        override fun region(): String = "CN"
        override fun model(): String = "legacy-model"
        override fun agentModelRoutes(): Map<TaskKind, AgentModelRoute> = routes
        override fun bailianKeyOrNull(): String? = "router-key"
        override fun bailianEndpoint(): String = TaskModelRouter.BAILIAN_ENDPOINT
        override fun bailianModelsValidated(): Boolean = validated
        override fun bailianValidationFingerprint(): String? = if (validated) com.littlekitty.xiaokele.settings.KeyFingerprint.stableOf("router-key") else null
        override fun officialModelsValidated(): Boolean = officialValidated
        override fun officialLegacyCompatibilityAllowed(): Boolean = officialLegacyAllowed
        override fun routeConfigurationVersion(): Long = 1L
    }

    private fun ByokRuntimeConfig.copyEndpoint(value: String): ByokRuntimeConfig = object : ByokRuntimeConfig {
        override fun loadKeyOrNull(): String? = this@copyEndpoint.loadKeyOrNull()
        override fun endpoint(): String = value
        override fun region(): String = this@copyEndpoint.region()
        override fun model(): String = this@copyEndpoint.model()
        override fun agentModelRoutes(): Map<TaskKind, AgentModelRoute> = this@copyEndpoint.agentModelRoutes()
        override fun bailianKeyOrNull(): String? = this@copyEndpoint.bailianKeyOrNull()
        override fun bailianEndpoint(): String = this@copyEndpoint.bailianEndpoint()
        override fun bailianFlashModel(): String = this@copyEndpoint.bailianFlashModel()
        override fun bailianProModel(): String = this@copyEndpoint.bailianProModel()
        override fun bailianModelsValidated(): Boolean = this@copyEndpoint.bailianModelsValidated()
        override fun bailianValidationFingerprint(): String? = this@copyEndpoint.bailianValidationFingerprint()
        override fun routeConfigurationVersion(): Long = this@copyEndpoint.routeConfigurationVersion()
        override fun reserveGenerationRequest(isPro: Boolean, nowMillis: Long): Boolean = this@copyEndpoint.reserveGenerationRequest(isPro, nowMillis)
    }

    private fun officialRoutes(): Map<TaskKind, AgentModelRoute> = TaskKind.values().associateWith { task ->
        val model = if (task == TaskKind.B2_EXPRESSION) TaskModelRouter.MINIMAX_HER_MODEL else TaskModelRouter.MINIMAX_M2_MODEL
        AgentModelRoute(ModelProvider.MINIMAX_OFFICIAL, model)
    }
}
