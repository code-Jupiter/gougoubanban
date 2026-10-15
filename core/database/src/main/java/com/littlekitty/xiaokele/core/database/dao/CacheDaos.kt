package com.littlekitty.xiaokele.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.littlekitty.xiaokele.core.database.entity.ExternalCacheEntity

@Dao
interface ExternalCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ExternalCacheEntity)

    @Query("SELECT * FROM external_cache WHERE cache_key = :cacheKey AND source = :source")
    suspend fun byKeyAndSource(cacheKey: String, source: String): ExternalCacheEntity?

    @Query("SELECT * FROM external_cache WHERE expires_at IS NOT NULL AND expires_at <= :now")
    suspend fun expiredBefore(now: Long): List<ExternalCacheEntity>

    @Query(
        """
        INSERT OR REPLACE INTO external_cache (cache_key, source, payload_json, fetched_at, expires_at)
        SELECT :cacheKey, :source, :payloadJson, :fetchedAt, :expiresAt
        WHERE NOT EXISTS (
            SELECT 1 FROM external_cache
            WHERE cache_key = :cacheKey AND source = :source AND fetched_at > :fetchedAt
        )
        """,
    )
    suspend fun insertIfFresher(
        cacheKey: String,
        source: String,
        payloadJson: String,
        fetchedAt: Long,
        expiresAt: Long?,
    ): Long

    @Query("DELETE FROM external_cache WHERE expires_at IS NOT NULL AND expires_at <= :now")
    suspend fun deleteExpired(now: Long): Int

    @Query("SELECT COUNT(*) FROM external_cache")
    suspend fun count(): Int

    @Query("SELECT * FROM external_cache ORDER BY fetched_at ASC, cache_key ASC")
    suspend fun all(): List<ExternalCacheEntity>
}
