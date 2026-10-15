package com.littlekitty.xiaokele.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * External data cache table. It stores normalized external data only.
 *
 * The public edition has no shift table: `shift_cache` was dropped in
 * migration 11 -> 12 so no schedule/shift row can be restored at runtime.
 */

@Entity(
    tableName = "external_cache",
    primaryKeys = ["cache_key", "source"],
    indices = [
        Index(value = ["source"]),
        Index(value = ["expires_at"]),
    ],
)
data class ExternalCacheEntity(
    @ColumnInfo(name = "cache_key") val cacheKey: String,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long? = null,
)
