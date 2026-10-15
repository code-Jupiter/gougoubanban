package com.littlekitty.xiaokele.core.database.repository

import androidx.room.withTransaction
import com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.delivery.DeliveryOutbox
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.PetStateEntity
import com.littlekitty.xiaokele.core.database.state.ActionExecutionStatuses
import com.littlekitty.xiaokele.core.database.state.ActionPlanStatuses
import com.littlekitty.xiaokele.core.database.state.DeliveryStatuses
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.TodoStatuses

/**
 * Startup recovery for the local fact source (architecture section 10):
 * - expired events/todos/memories/pet states are moved to their terminal states;
 *   an expired event also cancels its still-replayable plans/executions/outbox
 *   rows and its unaccepted source-event Todo/Trigger mutations;
 * - stale background PROCESSING events return to RECEIVED; foreground chat
 *   USER_MESSAGE and IDLE_FOLLOW_UP events are terminal FAILED and never enter
 *   the background pipeline;
 * - stale non-chat CLAIMED todos return to READY, or to EXPIRED when already
 *   past expiry; chat-claimed todos are user-accepted and are never re-pooled;
 * - stale SENDING chat messages return to FAILED with retry enabled;
 * - RUNNING action executions are classified before recovery: no persisted
 *   outbox row = stale failure; reserved but never invoked = returned to
 *   PENDING for safe retry; attempted but not delivered = OUTCOME_UNKNOWN and
 *   is never blindly resent; delivered rows are completed idempotently.
 * - stale PENDING/VALIDATED/RUNNING action plans without any recoverable
 *   execution are marked FAILED and release the one-active-plan-per-event slot;
 * - expired external cache entries are removed.
 * No LLM/scheduler decision happens here (C4); this is deterministic code.
 */
class RecoveryManager(private val db: AppDatabase) {

    suspend fun run(
        now: Long,
        staleClaimLeaseMillis: Long = DEFAULT_STALE_CLAIM_LEASE_MILLIS,
    ): RecoveryReport = db.withTransaction {
        val eventDao = db.eventDao()
        val messageDao = db.messageDao()
        val todoDao = db.todoDao()
        val triggerDao = db.triggerDao()
        val memoryDao = db.memoryDao()
        val petStateDao = db.petStateDao()
        val actionExecutionDao = db.actionExecutionDao()
        val actionPlanDao = db.actionPlanDao()
        val externalCacheDao = db.externalCacheDao()

        val expiredEventWork = expireEventsAndCancelReplayableWork(now)
        val processingRecovered = eventDao.recoverStaleProcessing()
        val failedStaleChatEvents = eventDao.failStaleChatProcessing()

        val expiredTodos = todoDao.expireDueNonClaimed(now)
        val staleClaims = recoverStaleClaims(now, staleClaimLeaseMillis, todoDao)
        val cancelledTriggersForTerminalTodos = triggerDao.cancelActiveForTerminalTodos()
        val completedTriggersForExecutedTodos = triggerDao.completeActiveForExecutedTodos()

        val expiredMemories = memoryDao.expireDue(now)
        val expiredPetStates = petStateDao.resetExpired(PetStateEntity.SINGLETON_ID, now)
        val recoveredStaleMessages = messageDao.failStaleSending()
        val actionRecovery = recoverActionExecutions(now, actionExecutionDao)
        val failedActionPlans = recoverStaleActionPlans(actionPlanDao, actionExecutionDao)
        val expiredCaches = externalCacheDao.deleteExpired(now)

        RecoveryReport(
            expiredEvents = expiredEventWork.expiredEvents,
            cancelledExpiredEventPlans = expiredEventWork.cancelledPlans,
            cancelledExpiredEventExecutions = expiredEventWork.cancelledExecutions,
            cancelledExpiredEventDeliveries = expiredEventWork.cancelledDeliveries,
            quarantinedExpiredEventDeliveries = expiredEventWork.quarantinedDeliveries,
            cancelledExpiredSourceTodos = expiredEventWork.cancelledSourceTodos,
            cancelledExpiredSourceTriggers = expiredEventWork.cancelledSourceTriggers,
            recoveredProcessingEvents = processingRecovered,
            failedStaleChatEvents = failedStaleChatEvents,
            expiredTodos = expiredTodos,
            staleClaims = staleClaims.staleCount,
            staleClaimsReturnedToReady = staleClaims.returnedToReady,
            staleClaimsExpired = staleClaims.expired,
            triggersCancelledForTerminalTodos = cancelledTriggersForTerminalTodos,
            triggersCompletedForExecutedTodos = completedTriggersForExecutedTodos,
            expiredMemories = expiredMemories,
            expiredPetStates = expiredPetStates,
            recoveredStaleMessages = recoveredStaleMessages,
            failedStaleActionExecutions = actionRecovery.failed,
            failedStaleActionPlans = failedActionPlans,
            recoveredActionExecutionsNotInvoked = actionRecovery.notInvoked,
            actionExecutionsOutcomeUnknown = actionRecovery.outcomeUnknown,
            deletedExpiredExternalCaches = expiredCaches,
        )
    }

    private suspend fun recoverActionExecutions(
        now: Long,
        actionExecutionDao: com.littlekitty.xiaokele.core.database.dao.ActionExecutionDao,
    ): ActionExecutionRecovery {
        val deliveryDao = db.deliveryHistoryDao()
        var failed = 0
        var notInvoked = 0
        var outcomeUnknown = 0
        actionExecutionDao.running().forEach { execution ->
            val delivery = deliveryDao.byExecutionId(execution.id)
            when {
                delivery == null -> {
                    if (actionExecutionDao.updateStatusAndError(
                            execution.id,
                            ActionExecutionStatuses.FAILED,
                            STALE_AFTER_RESTART,
                            now,
                        ) == 1
                    ) failed++
                }

                delivery.deliveredAt > 0 && delivery.status == DeliveryStatuses.DELIVERED -> {
                    if (actionExecutionDao.updateStatus(execution.id, ActionExecutionStatuses.COMPLETED, now) == 1) {
                        // delivered already committed; idempotent completion only
                    }
                }

                delivery.status == DeliveryStatuses.RESERVED &&
                    delivery.attemptStartedAt != null &&
                    hasExternalSystemOutcome(execution, delivery) -> {
                    deliveryDao.markOutcomeUnknown(delivery.id, UNKNOWN_SYSTEM_OUTCOME)
                    if (actionExecutionDao.updateStatusAndError(
                            execution.id,
                            ActionExecutionStatuses.FAILED,
                            UNKNOWN_SYSTEM_OUTCOME,
                            now,
                        ) == 1
                    ) outcomeUnknown++
                }

                delivery.status == DeliveryStatuses.FAILED -> {
                    if (actionExecutionDao.updateStatusAndError(
                            execution.id,
                            ActionExecutionStatuses.FAILED,
                            delivery.errorCode ?: STALE_AFTER_RESTART,
                            now,
                        ) == 1
                    ) failed++
                }

                delivery.status == DeliveryStatuses.OUTCOME_UNKNOWN -> {
                    if (actionExecutionDao.updateStatusAndError(
                            execution.id,
                            ActionExecutionStatuses.FAILED,
                            delivery.errorCode ?: UNKNOWN_SYSTEM_OUTCOME,
                            now,
                        ) == 1
                    ) outcomeUnknown++
                }

                delivery.status == DeliveryStatuses.CANCELLED -> {
                    actionExecutionDao.cancelReplayable(
                        execution.id,
                        delivery.errorCode ?: STALE_AFTER_RESTART,
                        now,
                    )
                }

                else -> {
                    // Never invoked, or a local-only channel whose write can be
                    // safely re-applied. In the latter case the persisted
                    // invocation marker must be cleared first; otherwise its
                    // reservation would block its own replay.
                    if (delivery.attemptStartedAt != null) {
                        deliveryDao.recoverLocalAttempt(delivery.id, now)
                    }
                    if (actionExecutionDao.recoverRunningToPending(execution.id) == 1) notInvoked++
                }
            }
        }
        return ActionExecutionRecovery(failed, notInvoked, outcomeUnknown)
    }

    /**
     * Expiring the event alone is not enough once its ActionPlan was already
     * committed. Clean up all still-replayable descendants in the same startup
     * transaction before an outbox retry or fired-trigger pass can see them.
     */
    private suspend fun expireEventsAndCancelReplayableWork(now: Long): ExpiredEventWorkRecovery {
        val eventDao = db.eventDao()
        val expiredEvents = eventDao.dueBefore(
            now,
            listOf(EventStatuses.RECEIVED, EventStatuses.PROCESSING),
        )
        var expired = 0
        var cancelledPlans = 0
        var cancelledExecutions = 0
        var cancelledDeliveries = 0
        var quarantinedDeliveries = 0
        var cancelledSourceTodos = 0
        var cancelledSourceTriggers = 0

        expiredEvents.forEach { event ->
            if (eventDao.updateStatus(event.id, EventStatuses.EXPIRED) != 1) return@forEach
            expired++

            // A source event may have persisted Todo/Trigger mutations in the
            // same transaction as the ActionPlan. Preserve chat-claimed or
            // already-executed user work, but stop unaccepted ACTIVE/FIRED
            // triggers before startup's recoverFired pass can dispatch them.
            cancelledSourceTriggers += db.triggerDao()
                .cancelActiveOrFiredForUnacceptedSourceEvent(event.id)
            cancelledSourceTodos += db.todoDao().cancelUnacceptedForSourceEvent(event.id, now)

            db.actionPlanDao().forEvent(event.id)
                .filter { it.status in ActionPlanStatuses.active }
                .forEach { plan ->
                    db.actionExecutionDao().forActionPlan(plan.id).forEach { execution ->
                        when (quarantineExecutionForExpiredEvent(event, execution, now)) {
                            ExpiredExecutionDisposition.CANCELLED -> {
                                cancelledExecutions++
                                cancelledDeliveries++
                            }

                            ExpiredExecutionDisposition.QUARANTINED -> quarantinedDeliveries++
                            ExpiredExecutionDisposition.ALREADY_DELIVERED -> Unit
                            ExpiredExecutionDisposition.NOOP -> Unit
                        }
                    }
                    if (db.actionPlanDao().cancelActive(plan.id) == 1) cancelledPlans++
                }
        }

        return ExpiredEventWorkRecovery(
            expiredEvents = expired,
            cancelledPlans = cancelledPlans,
            cancelledExecutions = cancelledExecutions,
            cancelledDeliveries = cancelledDeliveries,
            quarantinedDeliveries = quarantinedDeliveries,
            cancelledSourceTodos = cancelledSourceTodos,
            cancelledSourceTriggers = cancelledSourceTriggers,
        )
    }

    /**
     * Returns the factual recovery disposition for one execution. An invoked
     * OS delivery is never converted to a retryable cancellation: its outcome
     * is unknown. WAKE uses the PROACTIVE_MESSAGE channel for its chat row, but
     * also performs a real silent OS notification, so it belongs in the same
     * conservative branch as SEND_NOTIFICATION.
     */
    private suspend fun quarantineExecutionForExpiredEvent(
        event: EventEntity,
        execution: com.littlekitty.xiaokele.core.database.entity.ActionExecutionEntity,
        now: Long,
    ): ExpiredExecutionDisposition {
        val deliveryDao = db.deliveryHistoryDao()
        val actionExecutionDao = db.actionExecutionDao()
        val delivery = deliveryDao.byExecutionId(execution.id)
        when {
            delivery == null -> {
                return if (actionExecutionDao.cancelReplayable(
                        execution.id,
                        EVENT_EXPIRED_BEFORE_DELIVERY,
                        now,
                    ) == 1
                ) {
                    ExpiredExecutionDisposition.CANCELLED
                } else {
                    ExpiredExecutionDisposition.NOOP
                }
            }

            delivery.status == DeliveryStatuses.DELIVERED && delivery.deliveredAt > 0 -> {
                if (execution.status == ActionExecutionStatuses.RUNNING) {
                    actionExecutionDao.updateStatus(
                        execution.id,
                        ActionExecutionStatuses.COMPLETED,
                        now,
                    )
                }
                return ExpiredExecutionDisposition.ALREADY_DELIVERED
            }

            delivery.status == DeliveryStatuses.RESERVED &&
                delivery.attemptStartedAt != null &&
                hasExternalSystemOutcome(event, delivery) -> {
                deliveryDao.markOutcomeUnknown(delivery.id, UNKNOWN_SYSTEM_OUTCOME)
                actionExecutionDao.updateStatusAndError(
                    execution.id,
                    ActionExecutionStatuses.FAILED,
                    UNKNOWN_SYSTEM_OUTCOME,
                    now,
                )
                return ExpiredExecutionDisposition.QUARANTINED
            }

            delivery.status == DeliveryStatuses.RESERVED -> {
                deliveryDao.cancelReserved(delivery.id, EVENT_EXPIRED_BEFORE_DELIVERY)
            }

            delivery.status == DeliveryStatuses.FAILED -> {
                deliveryDao.cancelFailed(delivery.id, EVENT_EXPIRED_BEFORE_DELIVERY)
            }
        }

        return if (actionExecutionDao.cancelReplayable(
                execution.id,
                EVENT_EXPIRED_BEFORE_DELIVERY,
                now,
            ) == 1
        ) {
            ExpiredExecutionDisposition.CANCELLED
        } else {
            ExpiredExecutionDisposition.NOOP
        }
    }

    private suspend fun hasExternalSystemOutcome(
        execution: com.littlekitty.xiaokele.core.database.entity.ActionExecutionEntity,
        delivery: DeliveryHistoryEntity,
    ): Boolean {
        if (delivery.channel == DeliveryOutbox.CHANNEL_NOTIFICATION) return true
        if (delivery.channel != DeliveryOutbox.CHANNEL_PROACTIVE_MESSAGE) return false
        val plan = db.actionPlanDao().byId(execution.actionPlanId) ?: return false
        return db.eventDao().byId(plan.eventId)?.type == ExternalEventTypeNames.WAKE_WEATHER_BRIEF
    }

    private fun hasExternalSystemOutcome(event: EventEntity, delivery: DeliveryHistoryEntity): Boolean =
        delivery.channel == DeliveryOutbox.CHANNEL_NOTIFICATION ||
            (delivery.channel == DeliveryOutbox.CHANNEL_PROACTIVE_MESSAGE &&
                event.type == ExternalEventTypeNames.WAKE_WEATHER_BRIEF)

    private suspend fun recoverStaleActionPlans(
        actionPlanDao: com.littlekitty.xiaokele.core.database.dao.ActionPlanDao,
        actionExecutionDao: com.littlekitty.xiaokele.core.database.dao.ActionExecutionDao,
    ): Int {
        var failed = 0
        actionPlanDao.activePlans().forEach { plan ->
            val executions = actionExecutionDao.forActionPlan(plan.id)
            val hasRecoverableExecution = executions.any {
                it.status in setOf(ActionExecutionStatuses.PENDING, ActionExecutionStatuses.RUNNING)
            }
            if (hasRecoverableExecution.not()) {
                if (actionPlanDao.updateStatusWithActiveKey(plan.id, "FAILED") == 1) failed++
            }
        }
        return failed
    }

    private suspend fun recoverStaleClaims(
        now: Long,
        staleClaimLeaseMillis: Long,
        todoDao: com.littlekitty.xiaokele.core.database.dao.TodoDao,
    ): StaleClaimRecovery {
        val stale = todoDao.staleClaims(now - staleClaimLeaseMillis)
        var returnedToReady = 0
        var expired = 0
        stale.forEach { todo ->
            val alreadyExpired = todo.expiresAt != null && todo.expiresAt <= now
            if (alreadyExpired) {
                if (todoDao.transition(todo.id, TodoStatuses.CLAIMED, TodoStatuses.EXPIRED, now) == 1) expired++
            } else if (todoDao.releaseClaim(todo.id, now) == 1) {
                returnedToReady++
            }
        }
        return StaleClaimRecovery(staleCount = stale.size, returnedToReady = returnedToReady, expired = expired)
    }

    private data class ActionExecutionRecovery(
        val failed: Int,
        val notInvoked: Int,
        val outcomeUnknown: Int,
    )

    private data class ExpiredEventWorkRecovery(
        val expiredEvents: Int,
        val cancelledPlans: Int,
        val cancelledExecutions: Int,
        val cancelledDeliveries: Int,
        val quarantinedDeliveries: Int,
        val cancelledSourceTodos: Int,
        val cancelledSourceTriggers: Int,
    )

    private enum class ExpiredExecutionDisposition {
        CANCELLED,
        QUARANTINED,
        ALREADY_DELIVERED,
        NOOP,
    }

    companion object {
        const val DEFAULT_STALE_CLAIM_LEASE_MILLIS: Long = 15 * 60 * 1_000L
        const val STALE_AFTER_RESTART = "STALE_AFTER_RESTART"
        const val UNKNOWN_SYSTEM_OUTCOME = "UNKNOWN_SYSTEM_OUTCOME_AFTER_RESTART"
        const val EVENT_EXPIRED_BEFORE_DELIVERY = "EVENT_EXPIRED_BEFORE_DELIVERY"
    }
}

data class RecoveryReport(
    val expiredEvents: Int,
    val cancelledExpiredEventPlans: Int = 0,
    val cancelledExpiredEventExecutions: Int = 0,
    val cancelledExpiredEventDeliveries: Int = 0,
    val quarantinedExpiredEventDeliveries: Int = 0,
    val cancelledExpiredSourceTodos: Int = 0,
    val cancelledExpiredSourceTriggers: Int = 0,
    val recoveredProcessingEvents: Int,
    val failedStaleChatEvents: Int = 0,
    val expiredTodos: Int,
    val staleClaims: Int,
    val staleClaimsReturnedToReady: Int,
    val staleClaimsExpired: Int,
    val triggersCancelledForTerminalTodos: Int = 0,
    val triggersCompletedForExecutedTodos: Int = 0,
    val expiredMemories: Int,
    val expiredPetStates: Int,
    val recoveredStaleMessages: Int = 0,
    val failedStaleActionExecutions: Int,
    val failedStaleActionPlans: Int = 0,
    val recoveredActionExecutionsNotInvoked: Int = 0,
    val actionExecutionsOutcomeUnknown: Int = 0,
    val deletedExpiredExternalCaches: Int,
)
