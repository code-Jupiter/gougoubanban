package com.littlekitty.xiaokele.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.entity.TriggerEntity

@Dao
interface TodoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TodoEntity)

    /** CREATE paths must never replace an existing Todo supplied by a model. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: TodoEntity): Long

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun byId(id: String): TodoEntity?

    @Query("SELECT * FROM todos WHERE source_event_id = :eventId")
    suspend fun bySourceEventId(eventId: String): List<TodoEntity>

    @Query("SELECT * FROM todos WHERE status IN (:statuses) ORDER BY priority DESC, available_at ASC")
    suspend fun byStatuses(statuses: List<String>): List<TodoEntity>

    @Query("SELECT * FROM todos WHERE status = 'WAITING' AND (available_at IS NULL OR available_at <= :now) ORDER BY priority DESC, available_at ASC")
    suspend fun waitingAvailable(now: Long): List<TodoEntity>

    @Query(
        """
        UPDATE todos
        SET title = :title,
            payload_json = :payloadJson,
            priority = :priority,
            available_at = :availableAt,
            expires_at = :expiresAt,
            updated_at = :now
        WHERE id = :id AND status IN ('CANDIDATE', 'WAITING', 'READY')
        """,
    )
    suspend fun updateEditableFields(
        id: String,
        title: String?,
        payloadJson: String?,
        priority: Int,
        availableAt: Long?,
        expiresAt: Long?,
        now: Long,
    ): Int

    @Query(
        """
        UPDATE todos
        SET status = 'READY', updated_at = :now
        WHERE id = :id AND status = 'WAITING' AND (available_at IS NULL OR available_at <= :now)
        """,
    )
    suspend fun promoteToReadyIfAvailable(id: String, now: Long): Int

    /**
     * Atomic claim: the row update is conditional and returns 1 only for the
     * single writer that actually matched a WAITING/READY, non-expired todo.
     * Callers must run this inside a transaction where needed (FR-TODO-05).
     */
    @Query(
        """
        UPDATE todos
        SET status = 'CLAIMED', claimed_by = :claimedBy, claimed_at = :claimedAt, updated_at = :updatedAt
        WHERE id = :id
          AND status IN ('WAITING', 'READY')
          AND (available_at IS NULL OR available_at <= :now)
          AND (expires_at IS NULL OR expires_at > :now)
        """,
    )
    suspend fun claim(id: String, claimedBy: String, claimedAt: Long, updatedAt: Long, now: Long): Int

    @Query("UPDATE todos SET status = 'READY', claimed_by = NULL, claimed_at = NULL, updated_at = :now WHERE id = :id AND status = 'CLAIMED'")
    suspend fun releaseClaim(id: String, now: Long): Int

    @Query("UPDATE todos SET status = :newStatus, updated_at = :now WHERE id = :id AND status = :expectedStatus")
    suspend fun transition(id: String, expectedStatus: String, newStatus: String, now: Long): Int

    @Query(
        """
        UPDATE todos
        SET status = 'EXPIRED', updated_at = :now
        WHERE expires_at IS NOT NULL AND expires_at <= :now AND status IN ('CANDIDATE', 'WAITING', 'READY')
        """,
    )
    suspend fun expireDueNonClaimed(now: Long): Int

    /**
     * Cancels source-event mutations that were never accepted by the user.
     * Chat-claimed and executed Todos remain factual user work and are left
     * untouched by source-event expiry recovery.
     */
    @Query(
        """
        UPDATE todos
        SET status = 'CANCELLED', updated_at = :now
        WHERE source_event_id = :eventId
          AND (
            status IN ('CANDIDATE', 'WAITING', 'READY')
            OR (status = 'CLAIMED' AND (claimed_by IS NULL OR claimed_by NOT LIKE 'chat:%'))
          )
        """,
    )
    suspend fun cancelUnacceptedForSourceEvent(eventId: String, now: Long): Int

    @Query(
        """
        SELECT * FROM todos
        WHERE status = 'CLAIMED'
          AND claimed_at IS NOT NULL
          AND claimed_at <= :claimedBefore
          AND (claimed_by IS NULL OR claimed_by NOT LIKE 'chat:%')
        """,
    )
    suspend fun staleClaims(claimedBefore: Long): List<TodoEntity>

    @Query("SELECT COUNT(*) FROM todos")
    suspend fun count(): Int

    @Query("SELECT * FROM todos ORDER BY created_at ASC, id ASC")
    suspend fun allAscending(): List<TodoEntity>

    @Query("SELECT COUNT(*) FROM todos WHERE status = :status")
    suspend fun countByStatus(status: String): Int
}

@Dao
interface TriggerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TriggerEntity)

    /** CREATE paths must never replace an existing Trigger supplied by a model. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: TriggerEntity): Long

    @Query("SELECT * FROM triggers WHERE id = :id")
    suspend fun byId(id: String): TriggerEntity?

    @Query("SELECT * FROM triggers WHERE todo_id = :todoId")
    suspend fun forTodo(todoId: String): List<TriggerEntity>

    @Query(
        """
        SELECT * FROM triggers
        WHERE status = 'ACTIVE'
          AND next_fire_at IS NOT NULL
          AND next_fire_at > :notBefore
          AND next_fire_at <= :now
        ORDER BY next_fire_at ASC
        LIMIT :limit
        """,
    )
    suspend fun activeDue(now: Long, notBefore: Long, limit: Int): List<TriggerEntity>

    @Query("SELECT * FROM triggers WHERE status = 'FIRED' ORDER BY COALESCE(next_fire_at, last_fire_at) ASC, id ASC LIMIT :limit")
    suspend fun fired(limit: Int): List<TriggerEntity>

    @Query("SELECT COUNT(*) FROM triggers WHERE status = 'FIRED'")
    suspend fun firedCount(): Int

    @Query("SELECT MIN(next_fire_at) FROM triggers WHERE status = 'ACTIVE' AND next_fire_at IS NOT NULL")
    suspend fun earliestActiveFireAt(): Long?

    @Query("SELECT * FROM triggers ORDER BY COALESCE(next_fire_at, last_fire_at, 0) ASC, id ASC")
    suspend fun allAscending(): List<TriggerEntity>

    @Query("UPDATE triggers SET status = :newStatus WHERE id = :id")
    suspend fun updateStatus(id: String, newStatus: String): Int

    @Query(
        """
        UPDATE triggers
        SET status = :newStatus
        WHERE id = :id AND status = :expectedStatus
        """,
    )
    suspend fun transition(id: String, expectedStatus: String, newStatus: String): Int

    @Query(
        """
        UPDATE triggers
        SET status = 'FIRED', last_fire_at = :now
        WHERE id = :id
          AND status = 'ACTIVE'
          AND next_fire_at IS NOT NULL
          AND next_fire_at > :notBefore
          AND next_fire_at <= :now
        """,
    )
    suspend fun fireDue(id: String, now: Long, notBefore: Long): Int

    @Query(
        """
        UPDATE triggers
        SET status = 'EXPIRED'
        WHERE status = 'ACTIVE'
          AND next_fire_at IS NOT NULL
          AND next_fire_at <= :cutoff
        """,
    )
    suspend fun expireActiveAtOrBefore(cutoff: Long): Int

    @Query(
        """
        UPDATE triggers
        SET status = 'EXPIRED'
        WHERE status = 'FIRED'
          AND COALESCE(next_fire_at, last_fire_at) IS NOT NULL
          AND COALESCE(next_fire_at, last_fire_at) <= :cutoff
        """,
    )
    suspend fun expireFiredAtOrBefore(cutoff: Long): Int

    @Query(
        """
        UPDATE triggers
        SET trigger_config_json = :triggerConfigJson,
            next_fire_at = :nextFireAt
        WHERE id = :id AND status = 'ACTIVE'
        """,
    )
    suspend fun updateSchedule(id: String, triggerConfigJson: String, nextFireAt: Long): Int

    @Query("UPDATE triggers SET status = 'CANCELLED' WHERE todo_id = :todoId AND status = 'ACTIVE'")
    suspend fun cancelActiveForTodo(todoId: String): Int

    @Query("UPDATE triggers SET status = 'COMPLETED' WHERE todo_id = :todoId AND status = 'ACTIVE'")
    suspend fun completeActiveForTodo(todoId: String): Int

    /**
     * Covers both ACTIVE and FIRED rows. FIRED is included so a crash between
     * fire and completion cannot resurrect an unaccepted mutation during
     * startup's recoverFired pass.
     */
    @Query(
        """
        UPDATE triggers
        SET status = 'CANCELLED'
        WHERE status IN ('ACTIVE', 'FIRED')
          AND todo_id IN (
            SELECT id FROM todos
            WHERE source_event_id = :eventId
              AND (
                status IN ('CANDIDATE', 'WAITING', 'READY')
                OR (status = 'CLAIMED' AND (claimed_by IS NULL OR claimed_by NOT LIKE 'chat:%'))
              )
          )
        """,
    )
    suspend fun cancelActiveOrFiredForUnacceptedSourceEvent(eventId: String): Int

    @Query(
        """
        UPDATE triggers
        SET status = 'CANCELLED'
        WHERE status = 'ACTIVE'
          AND todo_id IN (SELECT id FROM todos WHERE status IN ('CANCELLED', 'EXPIRED', 'FAILED'))
        """,
    )
    suspend fun cancelActiveForTerminalTodos(): Int

    @Query(
        """
        UPDATE triggers
        SET status = 'COMPLETED'
        WHERE status = 'ACTIVE'
          AND todo_id IN (SELECT id FROM todos WHERE status = 'EXECUTED')
        """,
    )
    suspend fun completeActiveForExecutedTodos(): Int

    @Query("SELECT COUNT(*) FROM triggers")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM triggers WHERE status = :status")
    suspend fun countByStatus(status: String): Int
}
