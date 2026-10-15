package com.littlekitty.xiaokele.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.littlekitty.xiaokele.core.database.entity.ActionExecutionEntity
import com.littlekitty.xiaokele.core.database.entity.ActionPlanEntity
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity

@Dao
interface ActionPlanDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ActionPlanEntity)

    @Query("SELECT * FROM action_plans WHERE id = :id")
    suspend fun byId(id: String): ActionPlanEntity?

    @Query("SELECT * FROM action_plans WHERE event_id = :eventId")
    suspend fun forEvent(eventId: String): List<ActionPlanEntity>

    @Query("SELECT * FROM action_plans WHERE event_id = :eventId AND status IN ('PENDING', 'VALIDATED', 'RUNNING')")
    suspend fun activeForEvent(eventId: String): ActionPlanEntity?

    @Query("SELECT * FROM action_plans WHERE status IN ('PENDING', 'VALIDATED', 'RUNNING')")
    suspend fun activePlans(): List<ActionPlanEntity>

    @Query(
        """
        UPDATE action_plans
        SET status = :newStatus,
            active_event_key = CASE WHEN :newStatus IN ('PENDING', 'VALIDATED', 'RUNNING') THEN event_id ELSE NULL END
        WHERE id = :id
        """,
    )
    suspend fun updateStatusWithActiveKey(id: String, newStatus: String): Int

    /** Conditional transition used by concurrent channel/recovery owners. */
    @Query(
        """
        UPDATE action_plans
        SET status = :newStatus,
            active_event_key = CASE WHEN :newStatus IN ('PENDING', 'VALIDATED', 'RUNNING') THEN event_id ELSE NULL END
        WHERE id = :id AND status = :expectedStatus
        """,
    )
    suspend fun transitionStatusWithActiveKey(id: String, expectedStatus: String, newStatus: String): Int

    @Query(
        """
        UPDATE action_plans
        SET status = 'FAILED', active_event_key = NULL
        WHERE status IN ('PENDING', 'VALIDATED', 'RUNNING')
        """,
    )
    suspend fun failStaleActive(): Int

    /**
     * An event that is no longer executable must release its active-plan slot
     * before startup recovery or an outbox retry can observe it again.
     */
    @Query(
        """
        UPDATE action_plans
        SET status = 'CANCELLED', active_event_key = NULL
        WHERE id = :id
          AND status IN ('PENDING', 'VALIDATED', 'RUNNING')
        """,
    )
    suspend fun cancelActive(id: String): Int

    @Query("SELECT COUNT(*) FROM action_plans")
    suspend fun count(): Int

    @Query("SELECT * FROM action_plans ORDER BY created_at ASC, id ASC")
    suspend fun allAscending(): List<ActionPlanEntity>
}

@Dao
interface ActionExecutionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ActionExecutionEntity)

    @Query("SELECT * FROM action_executions WHERE id = :id")
    suspend fun byId(id: String): ActionExecutionEntity?

    @Query("SELECT * FROM action_executions WHERE action_plan_id = :actionPlanId ORDER BY rowid ASC, id ASC")
    suspend fun forActionPlan(actionPlanId: String): List<ActionExecutionEntity>

    @Query("SELECT * FROM action_executions WHERE status = 'RUNNING' ORDER BY executed_at ASC, id ASC")
    suspend fun running(): List<ActionExecutionEntity>

    @Query("UPDATE action_executions SET status = 'FAILED', error_code = 'STALE_AFTER_RESTART' WHERE status = 'RUNNING'")
    suspend fun failStaleRunning(): Int

    @Query(
        """
        UPDATE action_executions
        SET status = :newStatus, error_code = :errorCode, executed_at = :now
        WHERE id = :id
        """,
    )
    suspend fun updateStatusAndError(
        id: String,
        newStatus: String,
        errorCode: String,
        now: Long,
    ): Int

    @Query("UPDATE action_executions SET status = :newStatus, executed_at = :now WHERE id = :id")
    suspend fun updateStatus(id: String, newStatus: String, now: Long): Int

    @Query("UPDATE action_executions SET status = 'RUNNING', error_code = NULL, executed_at = :now WHERE id = :id AND status = 'FAILED'")
    suspend fun retryFailed(id: String, now: Long): Int

    @Query("UPDATE action_executions SET status = 'PENDING', error_code = NULL, executed_at = NULL WHERE id = :id AND status = 'RUNNING'")
    suspend fun recoverRunningToPending(id: String): Int

    /**
     * Claims a PENDING execution for one channel worker. The conditional update
     * is the ownership boundary that keeps concurrent startup/retry passes from
     * invoking the same system adapter twice.
     */
    @Query(
        """
        UPDATE action_executions
        SET status = 'RUNNING', error_code = NULL, executed_at = :now
        WHERE id = :id AND status = 'PENDING'
        """,
    )
    suspend fun claimPending(id: String, now: Long): Int

    /** Cancels work that has not reached a terminal, factual delivery state. */
    @Query(
        """
        UPDATE action_executions
        SET status = 'CANCELLED', error_code = :errorCode, executed_at = :now
        WHERE id = :id
          AND status IN ('PENDING', 'RUNNING', 'FAILED')
        """,
    )
    suspend fun cancelReplayable(id: String, errorCode: String, now: Long): Int

    @Query(
        """
        UPDATE action_executions
        SET status = 'CANCELLED', error_code = :errorCode, executed_at = :now
        WHERE status IN ('PENDING')
          AND action_type IN ('SEND_NOTIFICATION', 'SEND_PROACTIVE_MESSAGE')
          AND (
            action_plan_id IN (SELECT id FROM action_plans WHERE event_id = :eventId)
            OR id IN (SELECT execution_id FROM delivery_history WHERE todo_id = :todoId AND execution_id IS NOT NULL)
          )
        """,
    )
    suspend fun cancelPendingReachForEventOrTodo(
        eventId: String?,
        todoId: String?,
        errorCode: String,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE action_executions
        SET status = 'CANCELLED', error_code = :errorCode, executed_at = :now
        WHERE status = 'FAILED'
          AND action_type IN ('SEND_NOTIFICATION', 'SEND_PROACTIVE_MESSAGE')
          AND id IN (
            SELECT execution_id FROM delivery_history
            WHERE status = 'FAILED'
              AND next_retry_at IS NOT NULL
              AND channel IN ('NOTIFICATION', 'PROACTIVE_MESSAGE')
              AND (
                (:eventId IS NOT NULL AND event_id = :eventId)
                OR (:todoId IS NOT NULL AND todo_id = :todoId)
              )
          )
        """,
    )
    suspend fun cancelFailedRetryableReachExecutionsForEventOrTodo(
        eventId: String?,
        todoId: String?,
        errorCode: String,
        now: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM action_executions")
    suspend fun count(): Int

    @Query("SELECT * FROM action_executions ORDER BY COALESCE(executed_at, 0) ASC, id ASC")
    suspend fun allAscending(): List<ActionExecutionEntity>
}

@Dao
interface DeliveryHistoryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: DeliveryHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DeliveryHistoryEntity)

    @Query("SELECT * FROM delivery_history WHERE id = :id")
    suspend fun byId(id: String): DeliveryHistoryEntity?

    @Query("SELECT * FROM delivery_history WHERE execution_id = :executionId ORDER BY reserved_at DESC, id DESC LIMIT 1")
    suspend fun byExecutionId(executionId: String): DeliveryHistoryEntity?

    @Query("SELECT * FROM delivery_history WHERE event_id = :eventId ORDER BY delivered_at DESC, reserved_at DESC")
    suspend fun forEvent(eventId: String): List<DeliveryHistoryEntity>

    @Query("SELECT * FROM delivery_history WHERE content_hash = :contentHash AND status IN ('RESERVED', 'DELIVERED') ORDER BY delivered_at DESC, reserved_at DESC LIMIT 1")
    suspend fun activeOrDeliveredByContentHash(contentHash: String): DeliveryHistoryEntity?

    @Query("SELECT * FROM delivery_history WHERE todo_id = :todoId AND status IN ('RESERVED', 'DELIVERED') ORDER BY delivered_at DESC, reserved_at DESC LIMIT 1")
    suspend fun activeOrDeliveredForTodo(todoId: String): DeliveryHistoryEntity?

    @Query("SELECT * FROM delivery_history WHERE event_id = :eventId AND channel = :channel ORDER BY reserved_at DESC, delivered_at DESC LIMIT 1")
    suspend fun forEventAndChannel(eventId: String, channel: String): DeliveryHistoryEntity?

    @Query(
        """
        SELECT * FROM delivery_history
        WHERE channel = :channel AND status = 'DELIVERED' AND delivered_at >= :after
        ORDER BY delivered_at DESC
        """,
    )
    suspend fun recentDeliveredForChannel(channel: String, after: Long): List<DeliveryHistoryEntity>

    @Query(
        """
        SELECT * FROM delivery_history
        WHERE channel = :channel AND status = 'DELIVERED' AND delivered_at >= :start AND delivered_at <= :end
        ORDER BY delivered_at DESC
        """,
    )
    suspend fun deliveredForChannelBetween(channel: String, start: Long, end: Long): List<DeliveryHistoryEntity>

    @Query(
        """
        SELECT * FROM delivery_history
        WHERE channel = :channel AND cooldown_group = :cooldownGroup AND status = 'DELIVERED' AND delivered_at >= :after
        ORDER BY delivered_at DESC
        """,
    )
    suspend fun deliveredForChannelGroupSince(channel: String, cooldownGroup: String, after: Long): List<DeliveryHistoryEntity>

    @Query(
        """
        SELECT * FROM delivery_history
        WHERE cooldown_group = :cooldownGroup AND status = 'DELIVERED' AND delivered_at >= :after
        ORDER BY delivered_at DESC
        LIMIT 1
        """
    )
    suspend fun latestDeliveredForCooldownGroupSince(cooldownGroup: String, after: Long): DeliveryHistoryEntity?

    @Query("SELECT * FROM delivery_history ORDER BY COALESCE(delivered_at, reserved_at, 0) ASC, id ASC")
    suspend fun allAscending(): List<DeliveryHistoryEntity>

    @Query(
        """
        SELECT * FROM delivery_history
        ORDER BY CASE WHEN status = 'DELIVERED' AND delivered_at > 0 THEN delivered_at ELSE reserved_at END DESC
        LIMIT :limit
        """,
    )
    suspend fun recent(limit: Int): List<DeliveryHistoryEntity>

    @Query(
        """
        SELECT * FROM delivery_history
        WHERE status = 'FAILED'
          AND next_retry_at IS NOT NULL
          AND next_retry_at <= :now
          AND attempt_count < :maxAttempts
        ORDER BY next_retry_at ASC, id ASC
        """,
    )
    suspend fun retryable(now: Long, maxAttempts: Int): List<DeliveryHistoryEntity>

    @Query(
        """
        SELECT MIN(next_retry_at) FROM delivery_history
        WHERE status = 'FAILED'
          AND next_retry_at IS NOT NULL
          AND attempt_count < :maxAttempts
        """,
    )
    suspend fun earliestRetryAt(maxAttempts: Int): Long?

    @Query(
        """
        SELECT * FROM delivery_history
        WHERE status = 'RESERVED'
          AND id != :excludeId
          AND event_id = :eventId
          AND channel IN ('NOTIFICATION', 'PROACTIVE_MESSAGE')
        """,
    )
    suspend fun reservedCompetitorsForEvent(eventId: String, excludeId: String): List<DeliveryHistoryEntity>

    @Query(
        """
        SELECT * FROM delivery_history
        WHERE status = 'RESERVED'
          AND id != :excludeId
          AND todo_id = :todoId
          AND channel IN ('NOTIFICATION', 'PROACTIVE_MESSAGE')
        """,
    )
    suspend fun reservedCompetitorsForTodo(todoId: String, excludeId: String): List<DeliveryHistoryEntity>

    @Query("UPDATE delivery_history SET status = 'DELIVERED', delivered_at = :now, error_code = NULL, next_retry_at = NULL WHERE id = :id AND status = 'RESERVED'")
    suspend fun commitDelivered(id: String, now: Long): Int

    @Query(
        """
        UPDATE delivery_history
        SET status = 'FAILED', error_code = :errorCode, next_retry_at = :nextRetryAt
        WHERE id = :id AND status = 'RESERVED'
        """,
    )
    suspend fun failReserved(id: String, errorCode: String, nextRetryAt: Long?): Int

    @Query(
        """
        UPDATE delivery_history
        SET status = 'CANCELLED', error_code = :errorCode
        WHERE id = :id AND status = 'RESERVED'
        """,
    )
    suspend fun cancelReserved(id: String, errorCode: String): Int

    @Query(
        """
        UPDATE delivery_history
        SET status = 'OUTCOME_UNKNOWN', error_code = :errorCode, next_retry_at = NULL
        WHERE id = :id AND status = 'RESERVED'
        """,
    )
    suspend fun markOutcomeUnknown(id: String, errorCode: String): Int

    @Query("UPDATE delivery_history SET attempt_started_at = :now, attempt_count = attempt_count + 1 WHERE id = :id AND status = 'RESERVED' AND attempt_started_at IS NULL")
    suspend fun markInvoked(id: String, now: Long): Int

    /**
     * Local-only channels can be safely replayed after process death. Clear the
     * persisted invocation marker so the reservation remains owned by the same
     * execution but is eligible for exactly one fresh local attempt.
     */
    @Query(
        """
        UPDATE delivery_history
        SET attempt_started_at = NULL,
            error_code = NULL,
            next_retry_at = NULL,
            reserved_at = :now
        WHERE id = :id
          AND status = 'RESERVED'
          AND attempt_started_at IS NOT NULL
        """,
    )
    suspend fun recoverLocalAttempt(id: String, now: Long): Int

    @Query(
        """
        UPDATE delivery_history
        SET status = 'RESERVED', attempt_started_at = NULL, error_code = NULL, next_retry_at = NULL, reserved_at = :now
        WHERE id = :id AND status = 'FAILED'
        """,
    )
    suspend fun prepareRetry(id: String, now: Long): Int

    @Query(
        """
        UPDATE delivery_history
        SET user_opened_at = :now
        WHERE user_opened_at IS NULL
          AND (
            (:eventId IS NOT NULL AND event_id = :eventId)
            OR (:todoId IS NOT NULL AND todo_id = :todoId)
            OR (:deliveryId IS NOT NULL AND id = :deliveryId)
          )
        """,
    )
    suspend fun markOpened(eventId: String?, todoId: String?, deliveryId: String?, now: Long): Int

    @Query(
        """
        UPDATE delivery_history
        SET user_replied_at = :now
        WHERE user_replied_at IS NULL
          AND (
            (:eventId IS NOT NULL AND event_id = :eventId)
            OR (:todoId IS NOT NULL AND todo_id = :todoId)
          )
        """,
    )
    suspend fun markReplied(eventId: String?, todoId: String?, now: Long): Int

    @Query(
        """
        UPDATE delivery_history
        SET status = 'CANCELLED', error_code = :errorCode
        WHERE status = 'RESERVED'
          AND attempt_started_at IS NULL
          AND channel IN ('NOTIFICATION', 'PROACTIVE_MESSAGE')
          AND (
            (:eventId IS NOT NULL AND event_id = :eventId)
            OR (:todoId IS NOT NULL AND todo_id = :todoId)
          )
        """
    )
    suspend fun cancelReservedReachForEventOrTodo(eventId: String?, todoId: String?, errorCode: String): Int

    @Query(
        """
        UPDATE delivery_history
        SET status = 'CANCELLED', error_code = :errorCode, next_retry_at = NULL
        WHERE status = 'FAILED'
          AND next_retry_at IS NOT NULL
          AND channel IN ('NOTIFICATION', 'PROACTIVE_MESSAGE')
          AND (
            (:eventId IS NOT NULL AND event_id = :eventId)
            OR (:todoId IS NOT NULL AND todo_id = :todoId)
          )
        """
    )
    suspend fun cancelFailedRetryableReachForEventOrTodo(eventId: String?, todoId: String?, errorCode: String): Int

    /** Cancels a known failed delivery so it cannot remain eligible for retry. */
    @Query(
        """
        UPDATE delivery_history
        SET status = 'CANCELLED', error_code = :errorCode, next_retry_at = NULL
        WHERE id = :id AND status = 'FAILED'
        """,
    )
    suspend fun cancelFailed(id: String, errorCode: String): Int

    @Query("SELECT COUNT(*) FROM delivery_history")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM delivery_history WHERE status = 'DELIVERED' AND delivered_at > 0")
    suspend fun countDelivered(): Int
}
