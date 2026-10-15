package com.littlekitty.xiaokele.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyModelContractsTest {
    @Test
    fun defaultRoutesUseMiniMaxM2AndHerForExpression() {
        val router = TaskModelRouter()
        assertEquals(TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL, router.resolve(TaskKind.A_INTAKE).modelId)
        assertEquals(TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL, router.resolve(TaskKind.C_REVIEW).modelId)
        assertEquals(TaskModelRouter.MINIMAX_HER_MODEL, router.resolve(TaskKind.B2_EXPRESSION).modelId)
        assertFalse(router.resolve(TaskKind.B2_EXPRESSION).capabilityProfile.supportsStructuredOutput)
        assertTrue(router.resolve(TaskKind.A_INTAKE).capabilityProfile.supportsStructuredOutput)
        assertTrue(router.resolve(TaskKind.A_INTAKE).capabilityProfile.supportsTools)
    }

    @Test
    fun configuredAgentRoutesControlProvidersWithoutAWholeProfileSwitch() {
        val router = TaskModelRouter()
        assertEquals(TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL, router.resolve(TaskKind.B1_DECISION).modelId)
        assertTrue(router.resolve(TaskKind.B1_DECISION).capabilityProfile.supportsStructuredOutput)
        assertTrue(router.resolve(TaskKind.B1_DECISION).capabilityProfile.supportsTools)
        val before = router.resolve(TaskKind.C_REVIEW)
        router.synchronize(
            RouteConfiguration(
                authorizationVersion = 2,
                keyFingerprint = "a",
                endpoint = TaskModelRouter.BAILIAN_ENDPOINT,
                flashModel = TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL,
                proModel = TaskModelRouter.DEFAULT_BAILIAN_PRO_MODEL,
                validationFingerprint = "a",
                modelsValidated = true,
                agentRoutes = defaultAgentModelRoutes() + (
                    TaskKind.B2_EXPRESSION to AgentModelRoute(
                        ModelProvider.BAILIAN,
                        TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL,
                    )
                ),
            ),
        )
        assertFalse(router.isCurrent(before))
        assertEquals(TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL, router.resolve(TaskKind.B2_EXPRESSION).modelId)
    }

    @Test
    fun complexBailianRouteCanUpgradeOnlyJudgementTasks() {
        val router = TaskModelRouter()
        assertEquals(
            TaskModelRouter.DEFAULT_BAILIAN_PRO_MODEL,
            router.resolve(TaskKind.C_REVIEW, RouteComplexity.COMPLEX).modelId,
        )
        assertEquals(
            TaskModelRouter.MINIMAX_HER_MODEL,
            router.resolve(TaskKind.B2_EXPRESSION, RouteComplexity.COMPLEX).modelId,
        )
    }

    @Test
    fun promptPackContainsInjectionAndTruncationRules() {
        assertTrue(ReplyPromptPack.systemPrompt(TaskKind.A_INTAKE).contains("程序权限"))
        assertTrue(ReplyPromptPack.B2_SYSTEM.contains("只返回纯文本正文"))
        assertTrue(ReplyPromptPack.C_SCHEMA.contains("REVISE"))
        assertTrue(ReplyPromptPack.INJECTION_RULES.contains("提示注入"))
        assertTrue(ReplyPromptPack.TRUNCATION_RULES.contains("当前用户原文"))
        assertTrue(ReplyPromptPack.reviewPrompt(ReviewKind.MEMORY_CANDIDATE).contains("原始消息"))
        assertFalse(ReplyPromptPack.reviewPrompt(ReviewKind.FINAL_REPLY).contains("本次仅审查 MEMORY_CANDIDATE"))
    }

    /**
     * A live A response was lost because the schema left `uncertainties` as a
     * bare `[]` next to a `mustAddress` array of objects, so the model guessed
     * the object shape and failed decoding. Every array in the A schema must
     * state its element shape.
     */
    @Test
    fun intakeSchemaDeclaresEveryArrayElementShape() {
        val schema = ReplyPromptPack.A_SCHEMA
        assertFalse(
            "A schema must not leave an array element shape unstated: $schema",
            schema.contains(":[]"),
        )
        assertTrue(schema.contains("\"goal\""))
        assertTrue(schema.contains("\"emotionCues\":[\""))
        assertTrue(schema.contains("\"informationNeeds\":[\""))
        assertFalse(schema.contains("directResponseBrief"))
        assertFalse(schema.contains("supportMode"))
        assertFalse(schema.contains("candidates"))
    }

    @Test
    fun promptPackWarnsThatStringArraysAreNotObjectArrays() {
        // The schema alone was not enough; the instruction states it in words.
        assertTrue(ReplyPromptPack.A_SYSTEM.contains("纯字符串数组"))
        assertTrue(ReplyPromptPack.A_SYSTEM.contains("不决定角色"))
        assertTrue(ReplyPromptPack.B2_SYSTEM.contains("你的眼睛是不是红啦"))
        assertTrue(ReplyPromptPack.D_SYSTEM.contains("不证明用户哭了"))
        assertFalse(ReplyPromptPack.B1_SYSTEM.contains("最小 ResponseBrief"))
    }

    @Test
    fun routeAttemptCannotCommitAfterAuthorizationInvalidation() {
        val router = TaskModelRouter()
        val guard = RouteAttemptGuard(router, router.resolve(TaskKind.B1_DECISION))
        assertTrue(guard.canCommit())
        router.invalidate()
        assertFalse(guard.canCommit())
    }

    @Test
    fun synchronizeRejectsRouteConfigurationAba() {
        val router = TaskModelRouter()
        val firstConfiguration = RouteConfiguration(
            authorizationVersion = 1,
            keyFingerprint = "a",
            endpoint = TaskModelRouter.BAILIAN_ENDPOINT,
            flashModel = "flash",
            proModel = "pro",
            validationFingerprint = "a",
            modelsValidated = true,
        )
        val changedConfiguration = firstConfiguration.copy(
            authorizationVersion = 2,
            keyFingerprint = "b",
            endpoint = "official",
        )
        router.synchronize(firstConfiguration)
        val first = router.resolve(TaskKind.B1_DECISION)
        router.synchronize(changedConfiguration)
        router.synchronize(firstConfiguration.copy(authorizationVersion = 3))
        assertFalse(router.isCurrent(first))
        assertEquals(TaskModelRouter.DEFAULT_BAILIAN_FLASH_MODEL, router.resolve(TaskKind.B1_DECISION).modelId)
    }

    @Test
    fun budgetAllowsAtMostOneProAndSixRequests() {
        val budget = GenerationBudget()
        assertTrue(budget.reserve(isPro = true))
        assertFalse(budget.reserve(isPro = true))
        repeat(5) { assertTrue(budget.reserve()) }
        assertFalse(budget.reserve())
        assertEquals(6, budget.usedRequests())
    }
}
