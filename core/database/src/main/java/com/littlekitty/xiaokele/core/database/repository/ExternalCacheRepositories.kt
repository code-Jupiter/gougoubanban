package com.littlekitty.xiaokele.core.database.repository

import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.dao.ExternalCacheDao
import com.littlekitty.xiaokele.core.database.entity.ExternalCacheEntity

data class CacheWriteResult(
    val written: Boolean,
    val skippedStale: Boolean,
    val entity: ExternalCacheEntity? = null,
)

/**
 * Atomic external-cache emission guard. A single SQL
 * `INSERT OR REPLACE ... SELECT ... WHERE NOT EXISTS(fetched_at > candidate)`
 * guarantees that a late/stale response can neither overwrite a fresher cached
 * payload nor claim the right to emit an event for it.
 */
class ExternalCacheEmissionGuard(
    private val dao: ExternalCacheDao,
) {
    suspend fun emitIfFresher(
        cacheKey: String,
        source: String,
        payloadJson: String,
        fetchedAt: Long,
        expiresAt: Long?,
    ): CacheWriteResult {
        val changed = dao.insertIfFresher(cacheKey, source, payloadJson, fetchedAt, expiresAt)
        val current = dao.byKeyAndSource(cacheKey, source)
        return if (changed > 0 || current?.fetchedAt == fetchedAt) {
            CacheWriteResult(written = true, skippedStale = false, entity = current)
        } else {
            CacheWriteResult(written = false, skippedStale = true, entity = current)
        }
    }
}

class ExternalCacheRepository(
    private val db: AppDatabase,
    private val dao: ExternalCacheDao,
    private val guard: ExternalCacheEmissionGuard,
) {
    suspend fun get(cacheKey: String, source: String): ExternalCacheEntity? =
        dao.byKeyAndSource(cacheKey, source)

    suspend fun put(
        cacheKey: String,
        source: String,
        payloadJson: String,
        fetchedAt: Long,
        expiresAt: Long?,
    ): ExternalCacheEntity {
        val entity = ExternalCacheEntity(
            cacheKey = cacheKey,
            source = source,
            payloadJson = payloadJson,
            fetchedAt = fetchedAt,
            expiresAt = expiresAt,
        )
        dao.upsert(entity)
        return entity
    }

    suspend fun putIfFresher(
        cacheKey: String,
        source: String,
        payloadJson: String,
        fetchedAt: Long,
        expiresAt: Long?,
    ): CacheWriteResult = guard.emitIfFresher(cacheKey, source, payloadJson, fetchedAt, expiresAt)

    suspend fun deleteExpired(now: Long): Int = dao.deleteExpired(now)

    suspend fun count(): Int = dao.count()
}
