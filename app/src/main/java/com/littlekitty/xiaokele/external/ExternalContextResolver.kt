package com.littlekitty.xiaokele.external

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.ExternalCacheEntity
import com.littlekitty.xiaokele.core.network.external.WeatherSnapshotDto

/**
 * Public-edition external context resolver.
 *
 * It resolves only what the product actually has: the user's current location
 * snapshot and the weather cached for that snapshot. Shift/schedule/place
 * resolution was removed with the shift feature, so there is no path here that
 * can fabricate a shift phase, a schedule state or a home/hospital place.
 */
class ExternalContextResolver(
    private val db: AppDatabase,
    private val timeProvider: TimeProvider,
) {

    suspend fun resolveLocationSnapshot(nowEpochMillis: Long): LocationSnapshot? {
        val row = db.externalCacheDao().byKeyAndSource(
            ExternalEventContract.LOCATION_CACHE_KEY,
            ExternalEventContract.LOCATION_CACHE_SOURCE,
        ) ?: return null
        val expiresAt = row.expiresAt
        if (expiresAt != null && expiresAt <= nowEpochMillis) return null
        return decodeLocationSnapshot(row)
    }

    suspend fun resolveWeatherSnapshot(nowEpochMillis: Long): WeatherSnapshotDto? {
        val snapshot = resolveLocationSnapshot(nowEpochMillis) ?: return null
        val row = db.externalCacheDao().byKeyAndSource(
            WeatherRefresh.weatherCacheKey(snapshot.placeType),
            ExternalEventContract.WEATHER_CACHE_SOURCE,
        ) ?: return null
        val expiresAt = row.expiresAt
        if (expiresAt != null && expiresAt <= nowEpochMillis) return null
        return runCatching { JsonCodec.decode<WeatherSnapshotDto>(row.payloadJson) }.getOrNull()
    }

    /** Kept for callers that need the current zone/clock without a fix. */
    fun zone() = timeProvider.zone()

    private fun decodeLocationSnapshot(row: ExternalCacheEntity): LocationSnapshot? =
        runCatching { JsonCodec.decode<LocationSnapshot>(row.payloadJson) }.getOrNull()
}
