package com.littlekitty.xiaokele.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable, append-only operational facts used by the user-exportable report. */
@Entity(
    tableName = "diagnostic_logs",
    indices = [
        Index(value = ["recorded_at"]),
        Index(value = ["category", "name"]),
        Index(value = ["status"]),
    ],
)
data class DiagnosticLogEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long,
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "subject_id") val subjectId: String? = null,
    @ColumnInfo(name = "summary") val summary: String? = null,
    @ColumnInfo(name = "payload_json") val payloadJson: String? = null,
)

object DiagnosticLogCategories {
    const val CHAT = "CHAT"
    const val AI = "AI"
    const val SCENE = "SCENE"
    const val TASK = "TASK"
    const val DELIVERY = "DELIVERY"
    const val SYSTEM = "SYSTEM"
}

object DiagnosticLogStatuses {
    const val STARTED = "STARTED"
    const val SUCCESS = "SUCCESS"
    const val RETRY = "RETRY"
    const val FAILED = "FAILED"
    const val SKIPPED = "SKIPPED"
    const val NO_ACTION = "NO_ACTION"
    const val MISSED = "MISSED"
}

/** Stable markers used as the exported `name` field for trace queries. */
object DiagnosticLogNames {
    const val CHAT_TURN = "front_chat_turn"
    const val EVENT_INGESTED = "EVENT_INGESTED"
    const val EVENT_DEDUPLICATED = "EVENT_DEDUPLICATED"
    const val EVENT_REJECTED = "EVENT_REJECTED"
    const val SCENE_GATE = "SCENE_GATE"
    const val POLICY_GATE = "POLICY_GATE"
    const val CONTEXT_BUILT = "CONTEXT_BUILT"
    const val AI_REQUEST = "AI_REQUEST"
    const val AI_RESPONSE = "AI_RESPONSE"
    const val TOOL_EXECUTION = "TOOL_EXECUTION"
    const val DECISION_VALIDATION = "DECISION_VALIDATION"
    const val PLAN_PERSISTED = "PLAN_PERSISTED"
    const val CHANNEL_EXECUTION = "CHANNEL_EXECUTION"
    const val TURN_COMPLETED = "TURN_COMPLETED"
    const val MEMORY_ANALYSIS = "MEMORY_ANALYSIS"
    const val CONVERSATION_CONSOLIDATION = "CONVERSATION_CONSOLIDATION"
    const val PROFILE_SYNTHESIS = "PROFILE_SYNTHESIS"
}
