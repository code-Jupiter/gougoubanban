package com.littlekitty.xiaokele.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Phase 1 local fact source, part 3: character and current pet state.
 * Pet State is a separate singleton table and is only changed through the
 * Pet State Reducer (FR-PET-01/02).
 */

@Entity(
    tableName = "character_core",
    indices = [
        Index(value = ["version"]),
        Index(value = ["created_at"]),
    ],
)
data class CharacterCoreEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "version") val version: Int,
    @ColumnInfo(name = "content_json") val contentJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "behavior_tendencies",
    indices = [
        Index(value = ["key"]),
        Index(value = ["status"]),
    ],
)
data class BehaviorTendencyEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "original_value") val originalValue: String? = null,
    @ColumnInfo(name = "current_value") val currentValue: String? = null,
    @ColumnInfo(name = "suppression_level") val suppressionLevel: Double? = null,
    @ColumnInfo(name = "legacy_expression_rule_json") val legacyExpressionRuleJson: String? = null,
    @ColumnInfo(name = "source_memory_ids_json") val sourceMemoryIdsJson: String? = null,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "pet_state",
    indices = [
        Index(value = ["state_id"]),
        Index(value = ["expires_at"]),
        Index(value = ["reason_event_id"]),
    ],
)
data class PetStateEntity(
    @PrimaryKey @ColumnInfo(name = "singleton_id") val singletonId: Int = 1,
    @ColumnInfo(name = "state_id") val stateId: String,
    @ColumnInfo(name = "reason_event_id") val reasonEventId: String? = null,
    @ColumnInfo(name = "animation_id") val animationId: String? = null,
    @ColumnInfo(name = "short_text") val shortText: String? = null,
    @ColumnInfo(name = "starts_at") val startsAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,
    @ColumnInfo(name = "click_target_json") val clickTargetJson: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val SINGLETON_ID: Int = 1
    }
}
