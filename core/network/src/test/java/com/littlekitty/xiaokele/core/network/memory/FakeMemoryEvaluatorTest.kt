package com.littlekitty.xiaokele.core.network.memory

import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.BehaviorPatchOperationNames
import com.littlekitty.xiaokele.core.common.protocol.MemoryEvaluation
import com.littlekitty.xiaokele.core.common.protocol.MemoryEvaluationValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeMemoryEvaluatorTest {

    private val request = MemoryEvaluationRequest(
        eventId = "evt_00000000000000000000000000000001",
        userMessage = MemoryEvaluationMessage(
            id = "msg_00000000000000000000000000000001",
            role = "USER",
            content = "不要一直催我啦",
        ),
        assistantReply = MemoryEvaluationMessage(
            id = "msg_00000000000000000000000000000002",
            role = "ASSISTANT",
            content = "好，测试小狗记住了",
        ),
    )

    @Test
    fun successCorrectionReturnsDecodableValidEvaluation() = runBlocking {
        val result = FakeMemoryEvaluator(FakeMemoryEvaluatorMode.SUCCESS).evaluate(request)

        assertTrue(result is MemoryEvaluationResult.Success)
        val evaluation = JsonCodec.decode<MemoryEvaluation>((result as MemoryEvaluationResult.Success).rawMemoryEvaluationJson)
        assertEquals(1, evaluation.memoryCandidates.size)
        assertEquals(1, evaluation.behaviorPatches.size)
        assertEquals(BehaviorPatchOperationNames.DECREASE, evaluation.behaviorPatches.single().operation)
        assertTrue(evaluation.behaviorPatches.single().keepAsLegacy)
        assertTrue(
            MemoryEvaluationValidator.validate((result as MemoryEvaluationResult.Success).rawMemoryEvaluationJson)
                is com.littlekitty.xiaokele.core.common.protocol.MemoryEvaluationValidation.Valid,
        )
    }

    @Test
    fun failureModeReturnsSafeFailure() = runBlocking {
        val result = FakeMemoryEvaluator(FakeMemoryEvaluatorMode.FAILURE).evaluate(request)

        assertTrue(result is MemoryEvaluationResult.Failure)
        assertEquals(ErrorCode.MODEL_FAILURE, (result as MemoryEvaluationResult.Failure).error.code)
    }

    @Test
    fun invalidPatchModeTargetsImmutableCoreAndIsValidatorRejected() = runBlocking {
        val evaluator = FakeMemoryEvaluator(FakeMemoryEvaluatorMode.INVALID_PATCH)
        val result = evaluator.evaluate(request) as MemoryEvaluationResult.Success

        val validation = MemoryEvaluationValidator.validate(result.rawMemoryEvaluationJson)

        assertTrue(validation is com.littlekitty.xiaokele.core.common.protocol.MemoryEvaluationValidation.Invalid)
    }

    @Test
    fun unconfiguredEvaluatorFailsClosed() = runBlocking {
        val result = UnconfiguredMemoryEvaluator().evaluate(request)

        assertTrue(result is MemoryEvaluationResult.Failure)
        assertEquals(
            ErrorCode.MEMORY_EVALUATOR_NOT_CONFIGURED,
            (result as MemoryEvaluationResult.Failure).error.code,
        )
    }
}
