package com.littlekitty.xiaokele.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A time-bounded chat session used by the memory lifecycle.  This is not the
 * UI history: messages remain in `messages`; the row only records which range
 * has been settled into durable memory and which active high-frequency session
 * owns a temporary checkpoint.
 */
@Entity(
    tableName = "conversation_sessions",
    indices = [
        Index(value = ["status"]),
        Index(value = ["last_message_at"]),
        Index(value = ["operational_day_key"]),
    ],
)
data class ConversationSessionEntity(
    @PrimaryKey val id: String,
    val mode: String,
    val status: String,
    @ColumnInfo(name = "operational_day_key") val operationalDayKey: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "last_message_at") val lastMessageAt: Long,
    @ColumnInfo(name = "first_message_id") val firstMessageId: String,
    @ColumnInfo(name = "last_message_id") val lastMessageId: String,
    @ColumnInfo(name = "user_message_count") val userMessageCount: Int,
    @ColumnInfo(name = "checkpoint_summary") val checkpointSummary: String? = null,
    @ColumnInfo(name = "checkpoint_end_message_id") val checkpointEndMessageId: String? = null,
    @ColumnInfo(name = "settled_at") val settledAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

object ConversationSessionModes {
    const val LOW_FREQUENCY = "LOW_FREQUENCY"
    const val HIGH_FREQUENCY = "HIGH_FREQUENCY"
}

object ConversationSessionStatuses {
    const val ACTIVE = "ACTIVE"
    const val CLOSED = "CLOSED"
    const val SETTLING = "SETTLING"
    const val SETTLED = "SETTLED"
    const val RETRYABLE = "RETRYABLE"
}
