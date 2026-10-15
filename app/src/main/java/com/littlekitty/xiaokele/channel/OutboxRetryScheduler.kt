package com.littlekitty.xiaokele.channel

import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.delivery.DeliveryOutbox

data class OutboxRetryReport(
    val candidates: Int,
    val retried: Int,
    val skipped: Int,
    val recoveredPlans: Int,
)

interface ChannelRetryScheduler {
    suspend fun dispatchRetries(): OutboxRetryReport

    suspend fun nextRetryAtEpochMillis(): Long? = null
}

/**
 * Persisted-outbox retry scheduler. No WorkManager is introduced in Phase 5:
 * app startup and the debug panel drive this deterministic pass. A failed
 * notification row has next_retry_at written by [DeliveryOutbox.fail]; this
 * scheduler only retries due, known-failure rows and never OUTCOME_UNKNOWN.
 */
class OutboxRetryScheduler(
    private val outbox: DeliveryOutbox,
    private val coordinator: ChannelExecutionCoordinator,
    private val timeProvider: TimeProvider,
    private val maxAttempts: Int = DeliveryOutbox.MAX_ATTEMPTS,
) : ChannelRetryScheduler {

    override suspend fun nextRetryAtEpochMillis(): Long? =
        outbox.nextRetryAt(maxAttempts)

    override suspend fun dispatchRetries(): OutboxRetryReport {
        val now = timeProvider.now().toEpochMilli()
        val candidates = outbox.retryable(now, maxAttempts)
        var retried = 0
        var skipped = 0

        candidates.forEach { delivery ->
            val executionId = delivery.executionId
            if (executionId == null) {
                skipped++
                return@forEach
            }
            val result = coordinator.retryFailedExecution(executionId)
            if (result != null) retried++ else skipped++
        }

        val recoveredPlans = coordinator.recoverInterruptedPlans()

        return OutboxRetryReport(
            candidates = candidates.size,
            retried = retried,
            skipped = skipped,
            recoveredPlans = recoveredPlans,
        )
    }
}
