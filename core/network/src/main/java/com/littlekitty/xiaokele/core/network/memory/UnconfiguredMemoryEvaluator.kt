package com.littlekitty.xiaokele.core.network.memory

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode

/**
 * Release-safe memory evaluator while D-01/D-02/D-03 remain unconfirmed.
 * It performs no network I/O and fails closed; the asynchronous analysis layer
 * treats that failure as non-blocking and keeps the completed chat turn intact.
 */
class UnconfiguredMemoryEvaluator : MemoryEvaluator {
    override val serviceName: String = "memory_evaluator_unconfigured"

    override suspend fun evaluate(request: MemoryEvaluationRequest): MemoryEvaluationResult =
        MemoryEvaluationResult.Failure(
            AppError(
                code = ErrorCode.MEMORY_EVALUATOR_NOT_CONFIGURED,
                technicalDetail = "real_memory_evaluator_pending_d01_d02_d03",
            ),
        )
}
