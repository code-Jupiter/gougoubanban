package com.littlekitty.xiaokele.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 1 local fact source, part 4: action plans, executions and delivery.
 *
 * `action_plans.active_event_key` is an implementation column that backs the
 * "one active ActionPlan per Event" invariant with a real unique index:
 * active plans store the event_id there and terminal plans store NULL.
 */

@Entity(
    tableName = "action_plans",
    indices = [
        Index(value = ["event_id"]),
        Index(value = ["status"]),
        Index(value = ["active_event_key"], unique = true),
        Index(value = ["status", "event_id"]),
    ],
)
data class ActionPlanEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "agent_output_json") val agentOutputJson: String,
    @ColumnInfo(name = "decision_summary") val decisionSummary: String? = null,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "active_event_key") val activeEventKey: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "action_executions",
    indices = [
        Index(value = ["action_plan_id"]),
        Index(value = ["status"]),
        Index(value = ["executed_at"]),
    ],
)
data class ActionExecutionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "action_plan_id") val actionPlanId: String,
    @ColumnInfo(name = "action_type") val actionType: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String? = null,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "executed_at") val executedAt: Long? = null,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
)

@Entity(
    tableName = "delivery_history",
    indices = [
        Index(value = ["event_id"]),
        Index(value = ["todo_id"]),
        Index(value = ["channel"]),
        Index(value = ["content_hash"]),
        Index(value = ["delivered_at"]),
        Index(value = ["status"]),
        Index(value = ["execution_id"]),
        Index(value = ["next_retry_at"]),
        Index(value = ["channel", "cooldown_group"]),
        Index(value = ["event_id", "channel"], unique = true),
    ],
)
data class DeliveryHistoryEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "event_id") val eventId: String? = null,
    @ColumnInfo(name = "todo_id") val todoId: String? = null,
    @ColumnInfo(name = "channel") val channel: String,
    @ColumnInfo(name = "content_hash") val contentHash: String? = null,
    @ColumnInfo(name = "status", defaultValue = "DELIVERED")
    val status: String = com.littlekitty.xiaokele.core.database.state.DeliveryStatuses.DELIVERED,
    @ColumnInfo(name = "reserved_at") val reservedAt: Long? = null,
    @ColumnInfo(name = "attempt_started_at") val attemptStartedAt: Long? = null,
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Int = 0,
    @ColumnInfo(name = "next_retry_at") val nextRetryAt: Long? = null,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    @ColumnInfo(name = "execution_id") val executionId: String? = null,
    @ColumnInfo(name = "cooldown_group") val cooldownGroup: String? = null,
    @ColumnInfo(name = "delivered_at") val deliveredAt: Long = 0L,
    @ColumnInfo(name = "user_opened_at") val userOpenedAt: Long? = null,
    @ColumnInfo(name = "user_replied_at") val userRepliedAt: Long? = null,
)
