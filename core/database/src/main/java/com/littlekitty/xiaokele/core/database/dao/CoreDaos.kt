package com.littlekitty.xiaokele.core.database.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.littlekitty.xiaokele.core.database.entity.ConversationSummaryEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.entity.ScenarioPolicyEntity

data class IdleFollowUpAssistantRecord(
    @Embedded val assistant: MessageEntity,
    @ColumnInfo(name = "idle_follow_up_payload_json") val eventPayloadJson: String,
)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: MessageEntity)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE source_event_id = :eventId AND role = 'ASSISTANT' ORDER BY created_at ASC, id ASC LIMIT 1")
    suspend fun assistantForEvent(eventId: String): MessageEntity?

    @Query(
        """
        SELECT m.*, e.payload_json AS idle_follow_up_payload_json FROM messages m
        INNER JOIN events e ON e.id = m.source_event_id
        WHERE m.role = 'ASSISTANT'
          AND e.type = 'IDLE_FOLLOW_UP'
        ORDER BY m.created_at ASC, m.id ASC
        """,
    )
    suspend fun idleFollowUpAssistants(): List<IdleFollowUpAssistantRecord>

    @Query("SELECT * FROM messages WHERE source_todo_id = :todoId AND role = 'ASSISTANT' ORDER BY created_at ASC, id ASC LIMIT 1")
    suspend fun assistantForTodo(todoId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE role = 'ASSISTANT' AND (source_event_id IS NOT NULL OR source_todo_id IS NOT NULL) ORDER BY created_at DESC, id DESC LIMIT 1")
    suspend fun latestAssistantWithSource(): MessageEntity?

    @Query("SELECT * FROM messages ORDER BY created_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MessageEntity>

    @Query(
        """
        SELECT * FROM (
            SELECT * FROM messages
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
        )
        ORDER BY created_at ASC, id ASC
        """,
    )
    suspend fun recentAsc(limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY created_at ASC, id ASC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY created_at ASC, id ASC")
    suspend fun allAscending(): List<MessageEntity>

    /** Half-open time window used by periodic creative evaluation. */
    @Query(
        "SELECT * FROM messages WHERE created_at >= :startInclusive AND created_at < :endExclusive ORDER BY created_at ASC, id ASC",
    )
    suspend fun between(startInclusive: Long, endExclusive: Long): List<MessageEntity>

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAll(): Int

    @Query(
        """
        UPDATE messages
        SET status = :status, error_code = :errorCode
        WHERE id = :id
        """,
    )
    suspend fun updateStatus(id: String, status: String, errorCode: String?): Int

    /** Completes only a still-running user send; cancellation must not be overwritten. */
    @Query(
        """
        UPDATE messages
        SET status = :newStatus, error_code = :errorCode
        WHERE id = :id AND role = 'USER' AND status = :expectedStatus
        """,
    )
    suspend fun updateStatusIfCurrent(
        id: String,
        expectedStatus: String,
        newStatus: String,
        errorCode: String?,
    ): Int

    @Query(
        """
        UPDATE messages SET status = :status, error_code = :errorCode
        WHERE status = 'SENDING' AND role = 'USER'
        """,
    )
    suspend fun updateStatusWhereSendingUser(status: String, errorCode: String): Int

    @Query(
        """
        UPDATE messages
        SET status = :status, source_event_id = :sourceEventId, error_code = NULL
        WHERE id = :id
          AND source_event_id = :expectedSourceEventId
          AND status = :expectedStatus
        """,
    )
    suspend fun prepareRetry(
        id: String,
        status: String,
        sourceEventId: String,
        expectedSourceEventId: String,
        expectedStatus: String,
    ): Int

    @Query(
        """
        UPDATE messages
        SET status = 'FAILED', error_code = 'STALE_AFTER_RESTART'
        WHERE status = 'SENDING'
        """,
    )
    suspend fun failStaleSending(): Int

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM messages WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    /**
     * Last effective user interaction across chat messages and delivery
     * engagement (opened/replied). Used by the interaction-gap scene check.
     */
    @Query(
        """
        SELECT COALESCE(MAX(ts), 0) FROM (
            SELECT created_at AS ts FROM messages WHERE role = 'USER'
            UNION ALL
            SELECT user_opened_at AS ts FROM delivery_history WHERE user_opened_at IS NOT NULL
            UNION ALL
            SELECT user_replied_at AS ts FROM delivery_history WHERE user_replied_at IS NOT NULL
        )
        """,
    )
    suspend fun latestInteractionAtEpochMillis(): Long

    /** Proactive ASSISTANT messages the user has not seen yet. */
    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE role = 'ASSISTANT'
          AND (source_event_id IS NOT NULL OR source_todo_id IS NOT NULL)
          AND read_at IS NULL
        """,
    )
    suspend fun countUnreadProactive(): Int

    /**
     * Companion-initiated chat messages written during the current product
     * day. A message counts once it exists in chat, regardless of whether the
     * optional system notification later succeeds.
     */
    @Query(
        """
        SELECT COUNT(*) FROM messages m
        WHERE m.role = 'ASSISTANT'
          AND m.created_at >= :startInclusive
          AND m.created_at <= :endInclusive
          AND EXISTS (
              SELECT 1 FROM delivery_history d
              WHERE d.channel = 'PROACTIVE_MESSAGE'
                AND (
                    d.event_id = m.source_event_id
                    OR (m.source_todo_id IS NOT NULL AND d.todo_id = m.source_todo_id)
                )
          )
        """,
    )
    suspend fun countProactiveAssistantBetween(startInclusive: Long, endInclusive: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM messages
        WHERE role = 'ASSISTANT'
          AND (source_event_id IS NOT NULL OR source_todo_id IS NOT NULL)
          AND read_at IS NULL
        """,
    )
    fun observeUnreadProactiveCount(): kotlinx.coroutines.flow.Flow<Int>

    @Query(
        """
        UPDATE messages SET read_at = :now
        WHERE role = 'ASSISTANT'
          AND (source_event_id IS NOT NULL OR source_todo_id IS NOT NULL)
          AND read_at IS NULL
        """,
    )
    suspend fun markProactiveRead(now: Long): Int

    /** Earliest user message in a window; used by the wake-rhythm estimator. */
    @Query(
        """
        SELECT MIN(created_at) FROM messages
        WHERE role = 'USER' AND created_at >= :startInclusive AND created_at < :endExclusive
        """,
    )
    suspend fun firstUserMessageAtBetween(startInclusive: Long, endExclusive: Long): Long?
}

@Dao
interface ConversationSummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationSummaryEntity)

    @Query("SELECT * FROM conversation_summaries WHERE id = :id")
    suspend fun byId(id: String): ConversationSummaryEntity?

    @Query("SELECT * FROM conversation_summaries ORDER BY created_at DESC LIMIT 1")
    suspend fun latest(): ConversationSummaryEntity?

    @Query("SELECT * FROM conversation_summaries WHERE range_start_message_id = :startMessageId AND range_end_message_id = :endMessageId LIMIT 1")
    suspend fun byRange(startMessageId: String, endMessageId: String): ConversationSummaryEntity?

    @Query("SELECT * FROM conversation_summaries ORDER BY created_at ASC, id ASC")
    suspend fun allAscending(): List<ConversationSummaryEntity>

    @Query("SELECT COUNT(*) FROM conversation_summaries")
    suspend fun count(): Int

    @Query("DELETE FROM conversation_summaries")
    suspend fun deleteAll(): Int
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: EventEntity)

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun byId(id: String): EventEntity?

    @Query("SELECT * FROM events WHERE event_key = :eventKey")
    suspend fun byEventKey(eventKey: String): EventEntity?

    @Query("SELECT * FROM events WHERE status IN (:statuses)")
    suspend fun byStatuses(statuses: List<String>): List<EventEntity>

    @Query("SELECT * FROM events WHERE expires_at IS NOT NULL AND expires_at <= :now AND status IN (:nonTerminal)")
    suspend fun dueBefore(now: Long, nonTerminal: List<String>): List<EventEntity>

    @Query("SELECT * FROM events WHERE type = :type AND status IN (:statuses) ORDER BY received_at DESC, id DESC LIMIT :limit")
    suspend fun recentByTypeAndStatuses(type: String, statuses: List<String>, limit: Int): List<EventEntity>

    @Query("UPDATE events SET status = :newStatus WHERE id = :id")
    suspend fun updateStatus(id: String, newStatus: String): Int

    /** Reserve provider requests before I/O; process death cannot reset this cap. */
    @Query(
        """
        UPDATE events
        SET generation_request_count = generation_request_count + :amount
        WHERE id = :id
          AND status NOT IN ('FILTERED', 'EXPIRED', 'FAILED', 'COMPLETED')
          AND generation_request_count + :amount <= :maxRequests
        """,
    )
    suspend fun reserveGenerationRequests(id: String, amount: Int = 1, maxRequests: Int): Int

    @Query(
        """
        UPDATE events SET status = 'RECEIVED'
        WHERE status = 'PROCESSING'
          AND id NOT IN (
            SELECT event_id FROM action_plans
            WHERE status IN ('PENDING', 'VALIDATED', 'RUNNING')
          )
          AND NOT (
            (type = 'USER_MESSAGE' AND source = 'CHAT_INPUT')
            OR (type = 'IDLE_FOLLOW_UP' AND source = 'CHAT_IDLE_FOLLOW_UP')
          )
        """,
    )
    suspend fun recoverStaleProcessing(): Int

    @Query(
        """
        UPDATE events
        SET status = 'FAILED',
            attempt_count = attempt_count + 1,
            last_error_code = 'STALE_AFTER_RESTART',
            last_error_detail = 'foreground_chat_request_interrupted',
            next_retry_at = NULL
        WHERE (
            status = 'PROCESSING'
            AND type = 'USER_MESSAGE' AND source = 'CHAT_INPUT'
          ) OR (
            status IN ('RECEIVED', 'PROCESSING')
            AND type = 'IDLE_FOLLOW_UP' AND source = 'CHAT_IDLE_FOLLOW_UP'
          )
        """,
    )
    suspend fun failStaleChatProcessing(): Int

    @Query(
        """
        UPDATE events
        SET status = 'RECEIVED',
            attempt_count = attempt_count + 1,
            last_error_code = :errorCode,
            last_error_detail = :errorDetail,
            next_retry_at = :nextRetryAt
        WHERE id = :id
          AND status IN ('RECEIVED', 'PROCESSING')
          AND attempt_count < :maxAttempts
        """,
    )
    suspend fun scheduleRetryableFailure(
        id: String,
        errorCode: String,
        errorDetail: String?,
        nextRetryAt: Long,
        maxAttempts: Int,
    ): Int

    @Query(
        """
        UPDATE events
        SET status = 'FAILED',
            attempt_count = attempt_count + 1,
            last_error_code = :errorCode,
            last_error_detail = :errorDetail,
            next_retry_at = NULL
        WHERE id = :id
          AND status IN ('RECEIVED', 'PROCESSING')
        """,
    )
    suspend fun markTerminalFailure(id: String, errorCode: String, errorDetail: String?): Int

    @Query(
        """
        SELECT * FROM events
        WHERE status = 'RECEIVED'
          AND (next_retry_at IS NULL OR next_retry_at <= :now)
          AND NOT (
            (type = 'USER_MESSAGE' AND source = 'CHAT_INPUT')
            OR (type = 'IDLE_FOLLOW_UP' AND source = 'CHAT_IDLE_FOLLOW_UP')
          )
        ORDER BY priority DESC, received_at ASC, id ASC
        LIMIT :limit
        """,
    )
    suspend fun backgroundBacklog(now: Long, limit: Int): List<EventEntity>

    @Query(
        """
        SELECT COUNT(*) FROM events
        WHERE status = 'RECEIVED'
          AND (next_retry_at IS NULL OR next_retry_at <= :now)
          AND NOT (
            (type = 'USER_MESSAGE' AND source = 'CHAT_INPUT')
            OR (type = 'IDLE_FOLLOW_UP' AND source = 'CHAT_IDLE_FOLLOW_UP')
          )
        """,
    )
    suspend fun backgroundBacklogCount(now: Long): Int

    @Query("SELECT * FROM events ORDER BY received_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<EventEntity>

    @Query("SELECT * FROM events ORDER BY received_at ASC, id ASC")
    suspend fun allAscending(): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM events WHERE status = :status")
    suspend fun countByStatus(status: String): Int
}

@Dao
interface ScenarioPolicyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScenarioPolicyEntity)

    @Query("SELECT * FROM scenario_policies WHERE id = :id")
    suspend fun byId(id: String): ScenarioPolicyEntity?

    @Query("SELECT * FROM scenario_policies WHERE event_type = :eventType ORDER BY version DESC")
    suspend fun forEventType(eventType: String): List<ScenarioPolicyEntity>

    @Query("SELECT * FROM scenario_policies ORDER BY event_type ASC, version ASC, id ASC")
    suspend fun all(): List<ScenarioPolicyEntity>

    @Query("SELECT COUNT(*) FROM scenario_policies")
    suspend fun count(): Int
}
