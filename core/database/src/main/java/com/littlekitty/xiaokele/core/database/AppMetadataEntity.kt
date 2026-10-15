package com.littlekitty.xiaokele.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Phase 0 infrastructure table only. It stores app-level metadata (for example the
 * local database schema marker) and intentionally contains NO user relation data.
 *
 * Product core tables (messages, events, todos, triggers, memories, pet_state, ...)
 * are introduced in Phase 1 and must not be added during Phase 0.
 */
@Entity(tableName = "app_metadata")
data class AppMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,
    @ColumnInfo(name = "value")
    val value: String,
    @ColumnInfo(name = "updated_at_epoch_millis")
    val updatedAtEpochMillis: Long,
)
