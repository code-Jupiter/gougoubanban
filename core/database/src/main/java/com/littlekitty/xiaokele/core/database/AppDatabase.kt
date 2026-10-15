package com.littlekitty.xiaokele.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.littlekitty.xiaokele.core.database.dao.ActionExecutionDao
import com.littlekitty.xiaokele.core.database.dao.ActionPlanDao
import com.littlekitty.xiaokele.core.database.dao.BehaviorTendencyDao
import com.littlekitty.xiaokele.core.database.dao.CharacterCoreDao
import com.littlekitty.xiaokele.core.database.dao.ConversationSummaryDao
import com.littlekitty.xiaokele.core.database.dao.ConversationSessionDao
import com.littlekitty.xiaokele.core.database.dao.CreativeCandidateDao
import com.littlekitty.xiaokele.core.database.dao.CreativeOfferEventDao
import com.littlekitty.xiaokele.core.database.dao.CreativePreferenceDao
import com.littlekitty.xiaokele.core.database.dao.CreativeSessionDao
import com.littlekitty.xiaokele.core.database.dao.CreativeSourceLinkDao
import com.littlekitty.xiaokele.core.database.dao.CreativeTaskRunDao
import com.littlekitty.xiaokele.core.database.dao.CreativeTopicDao
import com.littlekitty.xiaokele.core.database.dao.CreativeWorkDao
import com.littlekitty.xiaokele.core.database.dao.DeliveryHistoryDao
import com.littlekitty.xiaokele.core.database.dao.DiagnosticLogDao
import com.littlekitty.xiaokele.core.database.dao.EventDao
import com.littlekitty.xiaokele.core.database.dao.ExternalCacheDao
import com.littlekitty.xiaokele.core.database.dao.MemoryDao
import com.littlekitty.xiaokele.core.database.dao.MemoryEmbeddingDao
import com.littlekitty.xiaokele.core.database.dao.MessageDao
import com.littlekitty.xiaokele.core.database.dao.PetStateDao
import com.littlekitty.xiaokele.core.database.dao.ScenarioPolicyDao
import com.littlekitty.xiaokele.core.database.dao.TodoDao
import com.littlekitty.xiaokele.core.database.dao.TriggerDao
import com.littlekitty.xiaokele.core.database.dao.UserTraitDao
import com.littlekitty.xiaokele.core.database.dao.UserDialogueTurnDao
import com.littlekitty.xiaokele.core.database.entity.ActionExecutionEntity
import com.littlekitty.xiaokele.core.database.entity.ActionPlanEntity
import com.littlekitty.xiaokele.core.database.entity.BehaviorTendencyEntity
import com.littlekitty.xiaokele.core.database.entity.CharacterCoreEntity
import com.littlekitty.xiaokele.core.database.entity.CreativeCandidateEntity
import com.littlekitty.xiaokele.core.database.entity.CreativeOfferEventEntity
import com.littlekitty.xiaokele.core.database.entity.CreativePreferenceEntity
import com.littlekitty.xiaokele.core.database.entity.CreativeSessionEntity
import com.littlekitty.xiaokele.core.database.entity.CreativeSourceLinkEntity
import com.littlekitty.xiaokele.core.database.entity.CreativeTaskRunEntity
import com.littlekitty.xiaokele.core.database.entity.CreativeTopicEntity
import com.littlekitty.xiaokele.core.database.entity.CreativeWorkEntity
import com.littlekitty.xiaokele.core.database.entity.ConversationSummaryEntity
import com.littlekitty.xiaokele.core.database.entity.ConversationSessionEntity
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.ExternalCacheEntity
import com.littlekitty.xiaokele.core.database.entity.MemoryEmbeddingEntity
import com.littlekitty.xiaokele.core.database.entity.MemoryEntity
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.entity.PetStateEntity
import com.littlekitty.xiaokele.core.database.entity.ScenarioPolicyEntity
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.entity.TriggerEntity
import com.littlekitty.xiaokele.core.database.entity.UserTraitEntity

/**
 * Local-first Room database (C2): the single local fact source for all user
 * relation data. Version 2 adds the Phase 1 core tables; version 3 adds the
 * Phase 2 message delivery state columns; version 4 adds the Phase 5 delivery
 * outbox; version 5 adds Phase 8 background retry audit columns; version 6 adds
 * diagnostic logs for chat, AI, scenes, deliveries, and background tasks.
 * Version 7 adds memory appraisal metadata and core/dynamic user-profile layers.
 * Version 8 adds the proactive-message read marker used by the unread badge;
 * version 9 adds the isolated creative library, user dialogue turns, offers
 * and catch-up task checkpoints. Version 10 adds persistent time-bounded
 * conversation sessions for the memory lifecycle. Version 11 adds durable
 * per-background-event model request accounting.
 * Destructive fallback is intentionally NOT configured.
 */
@Database(
    entities = [
        AppMetadataEntity::class,
        MessageEntity::class,
        ConversationSummaryEntity::class,
        ConversationSessionEntity::class,
        EventEntity::class,
        ScenarioPolicyEntity::class,
        TodoEntity::class,
        TriggerEntity::class,
        MemoryEntity::class,
        MemoryEmbeddingEntity::class,
        UserTraitEntity::class,
        CharacterCoreEntity::class,
        BehaviorTendencyEntity::class,
        PetStateEntity::class,
        ActionPlanEntity::class,
        ActionExecutionEntity::class,
        DeliveryHistoryEntity::class,
        ExternalCacheEntity::class,
        DiagnosticLogEntity::class,
        CreativeCandidateEntity::class,
        CreativeWorkEntity::class,
        CreativeSourceLinkEntity::class,
        CreativeTopicEntity::class,
        CreativeSessionEntity::class,
        CreativePreferenceEntity::class,
        CreativeOfferEventEntity::class,
        com.littlekitty.xiaokele.core.database.entity.UserDialogueTurnEntity::class,
        CreativeTaskRunEntity::class,
    ],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun metadataDao(): AppMetadataDao
    abstract fun messageDao(): MessageDao
    abstract fun conversationSummaryDao(): ConversationSummaryDao
    abstract fun conversationSessionDao(): ConversationSessionDao
    abstract fun eventDao(): EventDao
    abstract fun scenarioPolicyDao(): ScenarioPolicyDao
    abstract fun todoDao(): TodoDao
    abstract fun triggerDao(): TriggerDao
    abstract fun memoryDao(): MemoryDao
    abstract fun memoryEmbeddingDao(): MemoryEmbeddingDao
    abstract fun userTraitDao(): UserTraitDao
    abstract fun characterCoreDao(): CharacterCoreDao
    abstract fun behaviorTendencyDao(): BehaviorTendencyDao
    abstract fun petStateDao(): PetStateDao
    abstract fun actionPlanDao(): ActionPlanDao
    abstract fun actionExecutionDao(): ActionExecutionDao
    abstract fun deliveryHistoryDao(): DeliveryHistoryDao
    abstract fun externalCacheDao(): ExternalCacheDao
    abstract fun diagnosticLogDao(): DiagnosticLogDao
    abstract fun creativeCandidateDao(): CreativeCandidateDao
    abstract fun creativeWorkDao(): CreativeWorkDao
    abstract fun creativeSourceLinkDao(): CreativeSourceLinkDao
    abstract fun creativeTopicDao(): CreativeTopicDao
    abstract fun creativeSessionDao(): CreativeSessionDao
    abstract fun creativePreferenceDao(): CreativePreferenceDao
    abstract fun creativeOfferEventDao(): CreativeOfferEventDao
    abstract fun userDialogueTurnDao(): UserDialogueTurnDao
    abstract fun creativeTaskRunDao(): CreativeTaskRunDao

    companion object {
        const val NAME = "little_kitty.db"
        const val VERSION = 12

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*AppMigrations.all())
                .build()
    }
}

/**
 * Explicit migration registry. Phase 1 introduces 1 -> 2, which creates every
 * local fact source table and index. Phase 2 introduces 2 -> 3, which adds
 * persisted chat-message delivery state so failed sends survive restarts.
 */
object AppMigrations {

    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            Phase1MigrationSql.statements.forEach { statement -> db.execSQL(statement) }
        }
    }

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'COMPLETED'")
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `error_code` TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_status` ON `messages` (`status`)")
        }
    }

    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Deterministic de-duplication BEFORE unique-index creation. Dirty v3
            // databases may contain historical duplicate messages/delivery rows;
            // keep the earliest row by (created_at/delivered_at, id) and never drop
            // unrelated data.
            db.execSQL(
                """
                DELETE FROM `messages`
                WHERE `source_event_id` IS NOT NULL
                  AND EXISTS (
                    SELECT 1 FROM `messages` AS `keep`
                    WHERE `keep`.`source_event_id` = `messages`.`source_event_id`
                      AND `keep`.`role` = `messages`.`role`
                      AND `keep`.`source_event_id` IS NOT NULL
                      AND (
                        `keep`.`created_at` < `messages`.`created_at`
                        OR (`keep`.`created_at` = `messages`.`created_at` AND `keep`.`id` < `messages`.`id`)
                      )
                  )
                """.trimIndent(),
            )
            db.execSQL(
                """
                DELETE FROM `messages`
                WHERE `source_todo_id` IS NOT NULL
                  AND EXISTS (
                    SELECT 1 FROM `messages` AS `keep`
                    WHERE `keep`.`source_todo_id` = `messages`.`source_todo_id`
                      AND `keep`.`role` = `messages`.`role`
                      AND `keep`.`source_todo_id` IS NOT NULL
                      AND (
                        `keep`.`created_at` < `messages`.`created_at`
                        OR (`keep`.`created_at` = `messages`.`created_at` AND `keep`.`id` < `messages`.`id`)
                      )
                  )
                """.trimIndent(),
            )
            db.execSQL(
                """
                DELETE FROM `delivery_history`
                WHERE `event_id` IS NOT NULL
                  AND EXISTS (
                    SELECT 1 FROM `delivery_history` AS `keep`
                    WHERE `keep`.`event_id` = `delivery_history`.`event_id`
                      AND `keep`.`channel` = `delivery_history`.`channel`
                      AND `keep`.`event_id` IS NOT NULL
                      AND (
                        `keep`.`delivered_at` < `delivery_history`.`delivered_at`
                        OR (`keep`.`delivered_at` = `delivery_history`.`delivered_at` AND `keep`.`id` < `delivery_history`.`id`)
                      )
                  )
                """.trimIndent(),
            )
            db.execSQL("ALTER TABLE `delivery_history` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'DELIVERED'")
            db.execSQL("ALTER TABLE `delivery_history` ADD COLUMN `reserved_at` INTEGER")
            db.execSQL("ALTER TABLE `delivery_history` ADD COLUMN `attempt_started_at` INTEGER")
            db.execSQL("ALTER TABLE `delivery_history` ADD COLUMN `attempt_count` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `delivery_history` ADD COLUMN `next_retry_at` INTEGER")
            db.execSQL("ALTER TABLE `delivery_history` ADD COLUMN `error_code` TEXT")
            db.execSQL("ALTER TABLE `delivery_history` ADD COLUMN `execution_id` TEXT")
            db.execSQL("ALTER TABLE `delivery_history` ADD COLUMN `cooldown_group` TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_delivery_history_status` ON `delivery_history` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_delivery_history_execution_id` ON `delivery_history` (`execution_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_delivery_history_next_retry_at` ON `delivery_history` (`next_retry_at`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_delivery_history_channel_cooldown_group` ON `delivery_history` (`channel`, `cooldown_group`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_delivery_history_event_id_channel` ON `delivery_history` (`event_id`, `channel`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_source_event_id_role` ON `messages` (`source_event_id`, `role`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_source_todo_id_role` ON `messages` (`source_todo_id`, `role`)")
        }
    }

    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `events` ADD COLUMN `attempt_count` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `events` ADD COLUMN `last_error_code` TEXT")
            db.execSQL("ALTER TABLE `events` ADD COLUMN `last_error_detail` TEXT")
            db.execSQL("ALTER TABLE `events` ADD COLUMN `next_retry_at` INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_next_retry_at` ON `events` (`next_retry_at`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_events_status_next_retry_at` ON `events` (`status`, `next_retry_at`)")
        }
    }

    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `diagnostic_logs` (
                    `id` TEXT NOT NULL,
                    `recorded_at` INTEGER NOT NULL,
                    `category` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `subject_id` TEXT,
                    `summary` TEXT,
                    `payload_json` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_diagnostic_logs_recorded_at` ON `diagnostic_logs` (`recorded_at`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_diagnostic_logs_category_name` ON `diagnostic_logs` (`category`, `name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_diagnostic_logs_status` ON `diagnostic_logs` (`status`)")
        }
    }

    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `memories` ADD COLUMN `emotion_at_event_json` TEXT")
            db.execSQL("ALTER TABLE `memories` ADD COLUMN `current_appraisal_json` TEXT")
            db.execSQL("ALTER TABLE `memories` ADD COLUMN `last_reassessed_at` INTEGER")
            db.execSQL("ALTER TABLE `user_traits` ADD COLUMN `profile_layer` TEXT NOT NULL DEFAULT 'DYNAMIC'")
        }
    }

    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `messages` ADD COLUMN `read_at` INTEGER")
            // Historical rows predate unread tracking; only proactive messages
            // written after this migration may start unread.
            db.execSQL("UPDATE `messages` SET `read_at` = `created_at` WHERE `read_at` IS NULL")
        }
    }

    val MIGRATION_8_9: Migration = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `creative_candidates` (
                    `id` TEXT NOT NULL, `candidate_type` TEXT NOT NULL,
                    `source_period_start` INTEGER NOT NULL, `source_period_end` INTEGER NOT NULL,
                    `source_watermark_start` INTEGER NOT NULL, `source_watermark_end` INTEGER NOT NULL,
                    `theme` TEXT NOT NULL, `idea_seed` TEXT NOT NULL, `dog_feeling_seed` TEXT NOT NULL,
                    `risk_level` TEXT NOT NULL, `novelty_score` REAL NOT NULL, `repeat_risk` REAL NOT NULL,
                    `status` TEXT NOT NULL, `expires_at` INTEGER, `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_candidates_status` ON `creative_candidates` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_candidates_source_period_start_source_period_end` ON `creative_candidates` (`source_period_start`, `source_period_end`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_candidates_expires_at` ON `creative_candidates` (`expires_at`)")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `creative_works` (
                    `id` TEXT NOT NULL, `work_type` TEXT NOT NULL, `title` TEXT NOT NULL,
                    `content` TEXT NOT NULL, `summary` TEXT NOT NULL, `dog_emotion_summary` TEXT NOT NULL,
                    `period_start` INTEGER NOT NULL, `period_end` INTEGER NOT NULL, `timezone` TEXT NOT NULL,
                    `input_fingerprint` TEXT NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL, `invalidated_at` INTEGER, PRIMARY KEY(`id`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_works_work_type` ON `creative_works` (`work_type`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_works_period_start_period_end` ON `creative_works` (`period_start`, `period_end`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_works_status` ON `creative_works` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_works_input_fingerprint` ON `creative_works` (`input_fingerprint`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_creative_works_work_type_period_start_period_end_timezone` ON `creative_works` (`work_type`, `period_start`, `period_end`, `timezone`)")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `creative_source_links` (
                    `id` TEXT NOT NULL, `work_id` TEXT NOT NULL, `source_type` TEXT NOT NULL,
                    `source_id` TEXT NOT NULL, `source_timestamp` INTEGER NOT NULL,
                    `coverage_role` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_source_links_work_id` ON `creative_source_links` (`work_id`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_creative_source_links_source_type_source_id_coverage_role` ON `creative_source_links` (`source_type`, `source_id`, `coverage_role`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_source_links_source_type_source_id` ON `creative_source_links` (`source_type`, `source_id`)")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `creative_topics` (
                    `id` TEXT NOT NULL, `work_id` TEXT, `candidate_id` TEXT,
                    `topic_summary` TEXT NOT NULL, `opening_hint` TEXT NOT NULL,
                    `sensitivity` TEXT NOT NULL, `priority` INTEGER NOT NULL, `novelty` REAL NOT NULL,
                    `status` TEXT NOT NULL, `available_after` INTEGER, `expires_at` INTEGER,
                    `last_offered_at` INTEGER, `offer_count` INTEGER NOT NULL, `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_topics_work_id` ON `creative_topics` (`work_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_topics_candidate_id` ON `creative_topics` (`candidate_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_topics_status_available_after_expires_at` ON `creative_topics` (`status`, `available_after`, `expires_at`)")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `creative_sessions` (
                    `id` TEXT NOT NULL, `candidate_id` TEXT, `work_id` TEXT, `session_type` TEXT NOT NULL,
                    `current_outline` TEXT, `current_stage` TEXT NOT NULL, `user_direction` TEXT,
                    `last_message_id` TEXT, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_sessions_candidate_id` ON `creative_sessions` (`candidate_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_sessions_work_id` ON `creative_sessions` (`work_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_sessions_status` ON `creative_sessions` (`status`)")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `creative_preferences` (
                    `feature_key` TEXT NOT NULL, `proactive_enabled` INTEGER NOT NULL,
                    `style_preferences` TEXT NOT NULL, `reject_count_window` INTEGER NOT NULL,
                    `reject_window_started_at` INTEGER, `disabled_reason` TEXT, `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`feature_key`)
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `creative_offer_events` (
                    `id` TEXT NOT NULL, `bucket` TEXT NOT NULL, `user_dialogue_turn_id` TEXT,
                    `message_id` TEXT, `offer_type` TEXT NOT NULL, `dedup_key` TEXT NOT NULL,
                    `reserved_at` INTEGER, `sent_at` INTEGER, `status` TEXT NOT NULL,
                    `is_user_initiated` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_offer_events_bucket` ON `creative_offer_events` (`bucket`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_offer_events_user_dialogue_turn_id` ON `creative_offer_events` (`user_dialogue_turn_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_offer_events_message_id` ON `creative_offer_events` (`message_id`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_creative_offer_events_bucket_dedup_key` ON `creative_offer_events` (`bucket`, `dedup_key`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_offer_events_bucket_status_sent_at` ON `creative_offer_events` (`bucket`, `status`, `sent_at`)")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `user_dialogue_turns` (
                    `id` TEXT NOT NULL, `started_at` INTEGER NOT NULL, `last_user_message_at` INTEGER NOT NULL,
                    `first_message_id` TEXT NOT NULL, `last_message_id` TEXT NOT NULL,
                    `valid_message_count` INTEGER NOT NULL, `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_dialogue_turns_started_at` ON `user_dialogue_turns` (`started_at`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_dialogue_turns_last_user_message_at` ON `user_dialogue_turns` (`last_user_message_at`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_user_dialogue_turns_first_message_id` ON `user_dialogue_turns` (`first_message_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_dialogue_turns_last_message_id` ON `user_dialogue_turns` (`last_message_id`)")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `creative_task_runs` (
                    `id` TEXT NOT NULL, `task_kind` TEXT NOT NULL, `period_key` TEXT NOT NULL,
                    `timezone` TEXT NOT NULL, `scheduled_at` INTEGER NOT NULL, `due_at` INTEGER NOT NULL,
                    `started_at` INTEGER, `completed_at` INTEGER, `source_watermark` INTEGER,
                    `input_fingerprint` TEXT, `status` TEXT NOT NULL, `attempt_count` INTEGER NOT NULL,
                    `result_work_id` TEXT, `terminal_reason` TEXT, `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_task_runs_task_kind` ON `creative_task_runs` (`task_kind`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_task_runs_period_key` ON `creative_task_runs` (`period_key`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_creative_task_runs_status` ON `creative_task_runs` (`status`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_creative_task_runs_task_kind_period_key_timezone` ON `creative_task_runs` (`task_kind`, `period_key`, `timezone`)")
        }
    }

    val MIGRATION_9_10: Migration = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `conversation_sessions` (
                    `id` TEXT NOT NULL, `mode` TEXT NOT NULL, `status` TEXT NOT NULL,
                    `operational_day_key` TEXT NOT NULL, `started_at` INTEGER NOT NULL,
                    `last_message_at` INTEGER NOT NULL, `first_message_id` TEXT NOT NULL,
                    `last_message_id` TEXT NOT NULL, `user_message_count` INTEGER NOT NULL,
                    `checkpoint_summary` TEXT, `checkpoint_end_message_id` TEXT,
                    `settled_at` INTEGER, `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_sessions_status` ON `conversation_sessions` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_sessions_last_message_at` ON `conversation_sessions` (`last_message_at`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversation_sessions_operational_day_key` ON `conversation_sessions` (`operational_day_key`)")
        }
    }

    val MIGRATION_10_11: Migration = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `events` ADD COLUMN `generation_request_count` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /**
     * Public edition: the companion has no shift concept. Dropping the table
     * (rather than only removing its production readers) guarantees that no
     * previously cached shift/schedule row can be restored or read at runtime.
     */
    val MIGRATION_11_12: Migration = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `shift_cache`")
        }
    }

    fun all(): Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
    )
}
