package com.littlekitty.xiaokele.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 1 local fact source, part 1: conversation and event intake tables.
 * Memory, Todo, Trigger and Pet State are intentionally separate tables (C6).
 */

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["created_at"]),
        Index(value = ["source_event_id"]),
        Index(value = ["source_todo_id"]),
        Index(value = ["status"]),
        Index(value = ["source_event_id", "role"], unique = true),
        Index(value = ["source_todo_id", "role"], unique = true),
    ],
)
data class MessageEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "source_event_id") val sourceEventId: String? = null,
    @ColumnInfo(name = "source_todo_id") val sourceTodoId: String? = null,
    @ColumnInfo(name = "animation_id") val animationId: String? = null,
    @ColumnInfo(name = "status", defaultValue = "COMPLETED")
    val status: String = com.littlekitty.xiaokele.core.database.state.MessageStatuses.COMPLETED,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    /**
     * Null only for proactive ASSISTANT messages that the user has not seen
     * yet; everything else (user messages, historical rows) is stamped read.
     */
    @ColumnInfo(name = "read_at") val readAt: Long? = null,
)

@Entity(
    tableName = "conversation_summaries",
    indices = [
        Index(value = ["range_start_message_id"]),
        Index(value = ["range_end_message_id"]),
        Index(value = ["created_at"]),
    ],
)
data class ConversationSummaryEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "range_start_message_id") val rangeStartMessageId: String,
    @ColumnInfo(name = "range_end_message_id") val rangeEndMessageId: String,
    @ColumnInfo(name = "summary") val summary: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["event_key"], unique = true),
        Index(value = ["type"]),
        Index(value = ["source"]),
        Index(value = ["status"]),
        Index(value = ["expires_at"]),
        Index(value = ["status", "expires_at"]),
        Index(value = ["next_retry_at"]),
        Index(value = ["status", "next_retry_at"]),
    ],
)
data class EventEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "event_key") val eventKey: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,
    @ColumnInfo(name = "priority") val priority: Int = 0,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Int = 0,
    /** Durable total model-request count for this background event lifecycle. */
    @ColumnInfo(name = "generation_request_count", defaultValue = "0") val generationRequestCount: Int = 0,
    @ColumnInfo(name = "last_error_code") val lastErrorCode: String? = null,
    @ColumnInfo(name = "last_error_detail") val lastErrorDetail: String? = null,
    @ColumnInfo(name = "next_retry_at") val nextRetryAt: Long? = null,
)

@Entity(
    tableName = "scenario_policies",
    indices = [
        Index(value = ["event_type"]),
        Index(value = ["mode"]),
    ],
)
data class ScenarioPolicyEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "mode") val mode: String,
    @ColumnInfo(name = "matcher_json") val matcherJson: String? = null,
    @ColumnInfo(name = "prompt_template") val promptTemplate: String? = null,
    @ColumnInfo(name = "examples_json") val examplesJson: String? = null,
    @ColumnInfo(name = "allowed_actions_json") val allowedActionsJson: String? = null,
    @ColumnInfo(name = "constraints_json") val constraintsJson: String? = null,
    @ColumnInfo(name = "version") val version: Int = 1,
)

@Entity(
    tableName = "todos",
    indices = [
        Index(value = ["status"]),
        Index(value = ["available_at"]),
        Index(value = ["expires_at"]),
        Index(value = ["source_event_id"]),
        Index(value = ["claimed_by"]),
        Index(value = ["status", "expires_at"]),
        Index(value = ["status", "available_at"]),
    ],
)
data class TodoEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "title") val title: String? = null,
    @ColumnInfo(name = "payload_json") val payloadJson: String? = null,
    @ColumnInfo(name = "source_event_id") val sourceEventId: String? = null,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "priority") val priority: Int = 0,
    @ColumnInfo(name = "available_at") val availableAt: Long? = null,
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,
    @ColumnInfo(name = "claimed_by") val claimedBy: String? = null,
    @ColumnInfo(name = "claimed_at") val claimedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "triggers",
    indices = [
        Index(value = ["todo_id"]),
        Index(value = ["status"]),
        Index(value = ["next_fire_at"]),
        Index(value = ["status", "next_fire_at"]),
    ],
)
data class TriggerEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "todo_id") val todoId: String? = null,
    @ColumnInfo(name = "trigger_type") val triggerType: String,
    @ColumnInfo(name = "trigger_config_json") val triggerConfigJson: String? = null,
    @ColumnInfo(name = "next_fire_at") val nextFireAt: Long? = null,
    @ColumnInfo(name = "last_fire_at") val lastFireAt: Long? = null,
    @ColumnInfo(name = "status") val status: String,
)
