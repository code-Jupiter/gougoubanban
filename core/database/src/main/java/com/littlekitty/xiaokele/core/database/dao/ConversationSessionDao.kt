package com.littlekitty.xiaokele.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.littlekitty.xiaokele.core.database.entity.ConversationSessionEntity

@Dao
interface ConversationSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationSessionEntity)

    @Query("SELECT * FROM conversation_sessions WHERE id = :id")
    suspend fun byId(id: String): ConversationSessionEntity?

    @Query("SELECT * FROM conversation_sessions WHERE status = 'ACTIVE' ORDER BY last_message_at DESC, id DESC LIMIT 1")
    suspend fun active(): ConversationSessionEntity?

    @Query("SELECT * FROM conversation_sessions WHERE status IN ('CLOSED', 'RETRYABLE') ORDER BY last_message_at ASC, id ASC LIMIT :limit")
    suspend fun awaitingSettlement(limit: Int): List<ConversationSessionEntity>

    /**
     * A process can die after claiming SETTLING and before the model/commit
     * finishes. Recover only an old claim; a fresh SETTLING row must stay
     * invisible to another worker so two workers cannot run D_MEMORY/C_REVIEW
     * for the same session concurrently.
     */
    @Query(
        "UPDATE conversation_sessions SET status = 'RETRYABLE', updated_at = :now " +
            "WHERE status = 'SETTLING' AND updated_at <= :staleBefore",
    )
    suspend fun recoverStaleSettling(staleBefore: Long, now: Long): Int

    @Query("SELECT * FROM conversation_sessions WHERE status = 'SETTLED' ORDER BY last_message_at DESC, id DESC")
    suspend fun settledDescending(): List<ConversationSessionEntity>

    @Query("UPDATE conversation_sessions SET status = :status, updated_at = :now WHERE id = :id AND status = :expectedStatus")
    suspend fun transition(id: String, expectedStatus: String, status: String, now: Long): Int

    /** Completes only the worker claim that supplied this exact lease timestamp. */
    @Query(
        "UPDATE conversation_sessions SET status = 'SETTLED', settled_at = :settledAt, updated_at = :now " +
            "WHERE id = :id AND status = 'SETTLING' AND updated_at = :expectedClaimUpdatedAt",
    )
    suspend fun completeSettlement(id: String, expectedClaimUpdatedAt: Long, settledAt: Long, now: Long): Int

    /** Publishes a retry/settled outcome without overwriting a newer claim. */
    @Query(
        "UPDATE conversation_sessions SET status = :status, settled_at = :settledAt, updated_at = :now " +
            "WHERE id = :id AND status = 'SETTLING' AND updated_at = :expectedClaimUpdatedAt",
    )
    suspend fun finishSettlement(
        id: String,
        expectedClaimUpdatedAt: Long,
        status: String,
        settledAt: Long?,
        now: Long,
    ): Int

    @Query(
        "UPDATE conversation_sessions SET checkpoint_summary = :summary, " +
            "checkpoint_end_message_id = :checkpointEndMessageId, updated_at = :now " +
            "WHERE id = :id AND checkpoint_summary IS NULL",
    )
    suspend fun setCheckpointIfAbsent(
        id: String,
        summary: String,
        checkpointEndMessageId: String,
        now: Long,
    ): Int
}
