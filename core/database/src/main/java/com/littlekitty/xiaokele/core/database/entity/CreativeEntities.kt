package com.littlekitty.xiaokele.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "creative_candidates",
    indices = [
        Index(value = ["status"]),
        Index(value = ["source_period_start", "source_period_end"]),
        Index(value = ["expires_at"]),
    ],
)
data class CreativeCandidateEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "candidate_type") val candidateType: String,
    @ColumnInfo(name = "source_period_start") val sourcePeriodStart: Long,
    @ColumnInfo(name = "source_period_end") val sourcePeriodEnd: Long,
    @ColumnInfo(name = "source_watermark_start") val sourceWatermarkStart: Long,
    @ColumnInfo(name = "source_watermark_end") val sourceWatermarkEnd: Long,
    val theme: String,
    @ColumnInfo(name = "idea_seed") val ideaSeed: String,
    @ColumnInfo(name = "dog_feeling_seed") val dogFeelingSeed: String,
    @ColumnInfo(name = "risk_level") val riskLevel: String,
    @ColumnInfo(name = "novelty_score") val noveltyScore: Double,
    @ColumnInfo(name = "repeat_risk") val repeatRisk: Double,
    val status: String,
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "creative_works",
    indices = [
        Index(value = ["work_type"]),
        Index(value = ["period_start", "period_end"]),
        Index(value = ["status"]),
        Index(value = ["input_fingerprint"]),
        Index(value = ["work_type", "period_start", "period_end", "timezone"], unique = true),
    ],
)
data class CreativeWorkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "work_type") val workType: String,
    val title: String,
    val content: String,
    val summary: String,
    @ColumnInfo(name = "dog_emotion_summary") val dogEmotionSummary: String,
    @ColumnInfo(name = "period_start") val periodStart: Long,
    @ColumnInfo(name = "period_end") val periodEnd: Long,
    val timezone: String,
    @ColumnInfo(name = "input_fingerprint") val inputFingerprint: String,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "invalidated_at") val invalidatedAt: Long? = null,
)

@Entity(
    tableName = "creative_source_links",
    indices = [
        Index(value = ["work_id"]),
        Index(value = ["source_type", "source_id", "coverage_role"], unique = true),
        Index(value = ["source_type", "source_id"]),
    ],
)
data class CreativeSourceLinkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "work_id") val workId: String,
    @ColumnInfo(name = "source_type") val sourceType: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "source_timestamp") val sourceTimestamp: Long,
    @ColumnInfo(name = "coverage_role") val coverageRole: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "creative_topics",
    indices = [
        Index(value = ["work_id"]),
        Index(value = ["candidate_id"]),
        Index(value = ["status", "available_after", "expires_at"]),
    ],
)
data class CreativeTopicEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "work_id") val workId: String? = null,
    @ColumnInfo(name = "candidate_id") val candidateId: String? = null,
    @ColumnInfo(name = "topic_summary") val topicSummary: String,
    @ColumnInfo(name = "opening_hint") val openingHint: String,
    val sensitivity: String,
    val priority: Int,
    val novelty: Double,
    val status: String,
    @ColumnInfo(name = "available_after") val availableAfter: Long? = null,
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,
    @ColumnInfo(name = "last_offered_at") val lastOfferedAt: Long? = null,
    @ColumnInfo(name = "offer_count") val offerCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "creative_sessions",
    indices = [Index(value = ["candidate_id"]), Index(value = ["work_id"]), Index(value = ["status"])],
)
data class CreativeSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "candidate_id") val candidateId: String? = null,
    @ColumnInfo(name = "work_id") val workId: String? = null,
    @ColumnInfo(name = "session_type") val sessionType: String,
    @ColumnInfo(name = "current_outline") val currentOutline: String? = null,
    @ColumnInfo(name = "current_stage") val currentStage: String,
    @ColumnInfo(name = "user_direction") val userDirection: String? = null,
    @ColumnInfo(name = "last_message_id") val lastMessageId: String? = null,
    val status: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "creative_preferences")
data class CreativePreferenceEntity(
    @PrimaryKey @ColumnInfo(name = "feature_key") val featureKey: String,
    @ColumnInfo(name = "proactive_enabled") val proactiveEnabled: Boolean = true,
    @ColumnInfo(name = "style_preferences") val stylePreferences: String = "{}",
    @ColumnInfo(name = "reject_count_window") val rejectCountWindow: Int = 0,
    @ColumnInfo(name = "reject_window_started_at") val rejectWindowStartedAt: Long? = null,
    @ColumnInfo(name = "disabled_reason") val disabledReason: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "creative_offer_events",
    indices = [
        Index(value = ["bucket"]),
        Index(value = ["user_dialogue_turn_id"]),
        Index(value = ["message_id"]),
        Index(value = ["bucket", "dedup_key"], unique = true),
        Index(value = ["bucket", "status", "sent_at"]),
    ],
)
data class CreativeOfferEventEntity(
    @PrimaryKey val id: String,
    val bucket: String,
    @ColumnInfo(name = "user_dialogue_turn_id") val userDialogueTurnId: String? = null,
    @ColumnInfo(name = "message_id") val messageId: String? = null,
    @ColumnInfo(name = "offer_type") val offerType: String,
    @ColumnInfo(name = "dedup_key") val dedupKey: String,
    @ColumnInfo(name = "reserved_at") val reservedAt: Long? = null,
    @ColumnInfo(name = "sent_at") val sentAt: Long? = null,
    val status: String,
    @ColumnInfo(name = "is_user_initiated") val isUserInitiated: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "user_dialogue_turns",
    indices = [
        Index(value = ["started_at"]),
        Index(value = ["last_user_message_at"]),
        Index(value = ["first_message_id"], unique = true),
        Index(value = ["last_message_id"]),
    ],
)
data class UserDialogueTurnEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "last_user_message_at") val lastUserMessageAt: Long,
    @ColumnInfo(name = "first_message_id") val firstMessageId: String,
    @ColumnInfo(name = "last_message_id") val lastMessageId: String,
    @ColumnInfo(name = "valid_message_count") val validMessageCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "creative_task_runs",
    indices = [
        Index(value = ["task_kind"]),
        Index(value = ["period_key"]),
        Index(value = ["status"]),
        Index(value = ["task_kind", "period_key", "timezone"], unique = true),
    ],
)
data class CreativeTaskRunEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_kind") val taskKind: String,
    @ColumnInfo(name = "period_key") val periodKey: String,
    val timezone: String,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long,
    @ColumnInfo(name = "due_at") val dueAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long? = null,
    @ColumnInfo(name = "completed_at") val completedAt: Long? = null,
    @ColumnInfo(name = "source_watermark") val sourceWatermark: Long? = null,
    @ColumnInfo(name = "input_fingerprint") val inputFingerprint: String? = null,
    val status: String,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "result_work_id") val resultWorkId: String? = null,
    @ColumnInfo(name = "terminal_reason") val terminalReason: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
