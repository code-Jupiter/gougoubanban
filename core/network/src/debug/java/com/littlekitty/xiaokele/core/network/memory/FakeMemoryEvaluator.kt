package com.littlekitty.xiaokele.core.network.memory

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.BehaviorPatch
import com.littlekitty.xiaokele.core.common.protocol.BehaviorPatchOperationNames
import com.littlekitty.xiaokele.core.common.protocol.BehaviorTendencyKeyNames
import com.littlekitty.xiaokele.core.common.protocol.MemoryCandidate
import com.littlekitty.xiaokele.core.common.protocol.MemoryEvaluation
import com.littlekitty.xiaokele.core.common.protocol.MemoryTypeNames
import com.littlekitty.xiaokele.core.common.protocol.MemoryValidityNames

enum class FakeMemoryEvaluatorMode {
    SUCCESS,
    FAILURE,
    INVALID_JSON,
    INVALID_PATCH,
}

/**
 * Deterministic Phase 3 test double. It is wired only in debug builds and is
 * not a product fallback for release model outages.
 *
 * SUCCESS produces a reproducible correction scenario when the user asks the
 * dog to stop urging before a night shift; otherwise it produces a low-key
 * short-term memory. INVALID_PATCH returns a behavior patch that targets the
 * immutable character core so validation rejects it deterministically.
 */
class FakeMemoryEvaluator(
    initialMode: FakeMemoryEvaluatorMode = FakeMemoryEvaluatorMode.SUCCESS,
) : MemoryEvaluator {

    @Volatile
    var mode: FakeMemoryEvaluatorMode = initialMode

    override val serviceName: String = "fake_memory_evaluator"

    override suspend fun evaluate(request: MemoryEvaluationRequest): MemoryEvaluationResult =
        when (mode) {
            FakeMemoryEvaluatorMode.SUCCESS -> MemoryEvaluationResult.Success(
                successEvaluationJson(request),
            )

            FakeMemoryEvaluatorMode.FAILURE -> MemoryEvaluationResult.Failure(
                AppError(ErrorCode.MODEL_FAILURE, technicalDetail = "fake_memory_evaluator_failure"),
            )

            FakeMemoryEvaluatorMode.INVALID_JSON -> MemoryEvaluationResult.Success(
                "{not-valid-memory-evaluation",
            )

            FakeMemoryEvaluatorMode.INVALID_PATCH -> MemoryEvaluationResult.Success(
                JsonCodec.encode(
                    MemoryEvaluation(
                        behaviorPatches = listOf(
                            BehaviorPatch(
                                key = "character_core",
                                operation = BehaviorPatchOperationNames.SET,
                                amount = 1.0,
                            ),
                        ),
                    ),
                ),
            )
        }

    private fun successEvaluationJson(request: MemoryEvaluationRequest): String {
        val userText = request.userMessage.content
        val correction = CORRECTION_KEYWORDS.any { userText.contains(it) }
        val evaluation = if (correction) {
            MemoryEvaluation(
                memoryCandidates = listOf(
                    MemoryCandidate(
                        type = MemoryTypeNames.USER_PREFERENCE,
                        subject = "夜班前提醒偏好",
                        content = "测试用户不喜欢在夜班前被反复催促",
                        summary = "夜班前应降低催促强度",
                        importance = 0.85,
                        confidence = 0.9,
                        validity = MemoryValidityNames.LONG_TERM,
                        evidenceMessageIds = listOf(request.userMessage.id),
                        sourceMessageId = request.userMessage.id,
                    ),
                ),
                behaviorPatches = listOf(
                    BehaviorPatch(
                        key = BehaviorTendencyKeyNames.PRE_SHIFT_REMINDER_INTENSITY,
                        operation = BehaviorPatchOperationNames.DECREASE,
                        amount = 0.2,
                        keepAsLegacy = true,
                        evidenceMemoryIds = listOf(request.userMessage.id),
                    ),
                ),
            )
        } else {
            MemoryEvaluation(
                memoryCandidates = listOf(
                    MemoryCandidate(
                        type = MemoryTypeNames.SHORT_TERM_MEMORY,
                        content = "测试用户说：$userText",
                        summary = "最近一次聊天提到：$userText",
                        importance = 0.4,
                        confidence = 0.7,
                        validity = MemoryValidityNames.SHORT_TERM,
                        evidenceMessageIds = listOf(request.userMessage.id),
                        sourceMessageId = request.userMessage.id,
                    ),
                ),
            )
        }
        return JsonCodec.encode(evaluation)
    }

    companion object {
        val CORRECTION_KEYWORDS = listOf("不要催", "别催", "少催", "别再催", "不喜欢被催", "不要一直催")
    }
}
