package com.littlekitty.xiaokele.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 1 local fact source, part 2: Memory and user model tables.
 * Memory stores what happened and who the user is (C6); it is never merged
 * with Todo, Trigger or Pet State.
 */

@Entity(
    tableName = "memories",
    indices = [
        Index(value = ["type"]),
        Index(value = ["status"]),
        Index(value = ["valid_until"]),
        Index(value = ["source_event_id"]),
        Index(value = ["source_message_id"]),
        Index(value = ["supersedes_memory_id"]),
        Index(value = ["status", "valid_until"]),
    ],
)
data class MemoryEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "subject") val subject: String? = null,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "summary") val summary: String? = null,
    @ColumnInfo(name = "importance") val importance: Double = 0.0,
    @ColumnInfo(name = "confidence") val confidence: Double = 0.0,
    @ColumnInfo(name = "valid_from") val validFrom: Long? = null,
    @ColumnInfo(name = "valid_until") val validUntil: Long? = null,
    @ColumnInfo(name = "source_message_id") val sourceMessageId: String? = null,
    @ColumnInfo(name = "source_event_id") val sourceEventId: String? = null,
    @ColumnInfo(name = "supersedes_memory_id") val supersedesMemoryId: String? = null,
    @ColumnInfo(name = "emotion_at_event_json") val emotionAtEventJson: String? = null,
    @ColumnInfo(name = "current_appraisal_json") val currentAppraisalJson: String? = null,
    @ColumnInfo(name = "last_reassessed_at") val lastReassessedAt: Long? = null,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "memory_embeddings",
    primaryKeys = ["memory_id", "model_id"],
    indices = [
        Index(value = ["model_id"]),
        Index(value = ["created_at"]),
    ],
)
data class MemoryEmbeddingEntity(
    @ColumnInfo(name = "memory_id") val memoryId: String,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "dimension") val dimension: Int,
    @ColumnInfo(name = "vector_blob") val vectorBlob: ByteArray,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "user_traits",
    indices = [
        Index(value = ["key"], unique = true),
        Index(value = ["status"]),
    ],
)
data class UserTraitEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value_json") val valueJson: String,
    @ColumnInfo(name = "confidence") val confidence: Double = 0.0,
    @ColumnInfo(name = "evidence_memory_ids_json") val evidenceMemoryIdsJson: String? = null,
    @ColumnInfo(name = "profile_layer") val profileLayer: String = "DYNAMIC",
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
