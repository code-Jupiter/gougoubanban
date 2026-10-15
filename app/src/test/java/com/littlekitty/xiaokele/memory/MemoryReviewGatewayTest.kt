package com.littlekitty.xiaokele.memory

import com.littlekitty.xiaokele.core.network.ServiceCallTrace
import com.littlekitty.xiaokele.provider.GenerationBudget
import com.littlekitty.xiaokele.provider.ModelCallProvenance
import com.littlekitty.xiaokele.provider.ModelProvider
import com.littlekitty.xiaokele.provider.ReviewKind
import com.littlekitty.xiaokele.provider.TaskModelGateway
import com.littlekitty.xiaokele.provider.TaskModelRequest
import com.littlekitty.xiaokele.provider.TaskModelResult
import com.littlekitty.xiaokele.provider.TaskModelRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryReviewGatewayTest {
    @Test
    fun memoryReviewUsesStrictMemoryRecipeNotChatRecipe() = runBlocking {
        var prompt = ""
        val gateway = ProfiledMemoryReviewGateway(object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                prompt = request.systemPrompt
                return TaskModelResult.Success(
                    """{"reviewKind":"MEMORY_CANDIDATE","verdict":"PASS","issues":[],"evidenceIds":["u1"]}""",
                    TaskModelRouter().resolve(request.task), ServiceCallTrace("fake"),
                    ModelCallProvenance(ModelProvider.BAILIAN, "fake"),
                )
            }
        })
        val result = gateway.review(MemoryReviewRequest("turn", """{"memory_candidates":[{}]}""", "{}", setOf("u1")))
        assertTrue(result is MemoryReviewResult.Passed)
        assertTrue(prompt.contains("本次仅审查 MEMORY_CANDIDATE"))
        assertTrue(!prompt.contains("本次仅审查 FINAL_REPLY"))
    }

    @Test
    fun reviewPassWithIssuesCannotCommitMemory() = runBlocking {
        val gateway = ProfiledMemoryReviewGateway(object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult =
                TaskModelResult.Success(
                    """{"reviewKind":"MEMORY_CANDIDATE","verdict":"PASS","issues":[{"location":"candidate","type":"FACT","minimumChange":"缺少证据"}],"evidenceIds":["u1"]}""",
                    TaskModelRouter().resolve(request.task), ServiceCallTrace("fake"),
                    ModelCallProvenance(ModelProvider.BAILIAN, "fake"),
                )
        })
        val result = gateway.review(MemoryReviewRequest("turn", """{"memory_candidates":[{}]}""", "{}", setOf("u1"), ReviewKind.MEMORY_CANDIDATE))
        assertTrue(result is MemoryReviewResult.Failed)
    }

    @Test
    fun relationshipReviewReceivesItsOwnSchemaAndPrompt() = runBlocking {
        var payload = ""
        var prompt = ""
        val gateway = ProfiledMemoryReviewGateway(object : TaskModelGateway {
            override suspend fun generate(request: TaskModelRequest, budget: GenerationBudget): TaskModelResult {
                payload = request.userPayload
                prompt = request.systemPrompt
                return TaskModelResult.Success(
                    """{"reviewKind":"RELATIONSHIP_CONFIRMATION","verdict":"REJECT","issues":[],"evidenceIds":[]}""",
                    TaskModelRouter().resolve(request.task), ServiceCallTrace("fake"),
                    ModelCallProvenance(ModelProvider.BAILIAN, "fake"),
                )
            }
        })
        assertTrue(gateway.review(MemoryReviewRequest("turn", "{}", "{}", emptySet(),
            ReviewKind.RELATIONSHIP_CONFIRMATION)) is MemoryReviewResult.Blocked)
        assertTrue(payload.contains("reviewKind:RELATIONSHIP_CONFIRMATION"))
        assertTrue(prompt.contains("本次仅审查 RELATIONSHIP_CONFIRMATION"))
    }
}
