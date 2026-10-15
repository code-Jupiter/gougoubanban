package com.littlekitty.xiaokele.external

import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.database.entity.ExternalCacheEntity
import com.littlekitty.xiaokele.core.database.repository.ExternalCacheRepository
import com.littlekitty.xiaokele.core.network.external.WeatherSnapshotDto
import com.littlekitty.xiaokele.core.network.external.WeatherQueryProfile
import java.time.Instant

/**
 * Public-edition external-context test seam.
 *
 * The geofence/shift helpers were removed with the shift feature, so tests that
 * exercise location/weather caching use this shared fixture instead of
 * re-implementing cache rows per file.
 */
object PublicExternalTestFixtures {

    const val DEFAULT_LATITUDE = 30.0
    const val DEFAULT_LONGITUDE = 120.0

    fun weatherSnapshot(
        placeType: String = LocationSnapshot.CURRENT_PLACE,
        observedAt: Instant = Instant.parse("2026-08-17T04:00:00Z"),
        condition: String = "cloudy",
        temperatureCelsius: Double? = 24.0,
        precipitationMm: Double? = 0.0,
        aqi: Int? = 52,
        alerts: List<com.littlekitty.xiaokele.core.network.external.WeatherAlertDto> = emptyList(),
        expiresAt: Instant = observedAt.plusSeconds(3_600L),
    ): WeatherSnapshotDto = WeatherSnapshotDto(
        locationKey = placeType.lowercase(),
        observedAt = InstantJson.formatUtc(observedAt),
        condition = condition,
        temperatureCelsius = temperatureCelsius,
        precipitationMm = precipitationMm,
        temperatureTrend = "steady",
        temperatureDeltaCelsius = 0.0,
        aqi = aqi,
        aqiCategory = "good",
        alerts = alerts,
        expiresAt = InstantJson.formatUtc(expiresAt),
    )

    fun weatherSnapshotJson(
        placeType: String = LocationSnapshot.CURRENT_PLACE,
        observedAt: Instant = Instant.parse("2026-08-17T04:00:00Z"),
        condition: String = "cloudy",
    ): String = com.littlekitty.xiaokele.core.common.json.JsonCodec.encode(
        weatherSnapshot(placeType = placeType, observedAt = observedAt, condition = condition),
    )

    /**
     * Writes a fresh location snapshot plus the weather cached for it, which is
     * exactly the state a successful foreground refresh leaves behind.
     */
    suspend fun seedLocationAndWeather(
        repository: ExternalCacheRepository,
        nowEpochMillis: Long,
        latitude: Double = DEFAULT_LATITUDE,
        longitude: Double = DEFAULT_LONGITUDE,
        condition: String = "cloudy",
        weatherTtlMillis: Long = 60L * 60L * 1000L,
    ): LocationSnapshot {
        val snapshot = LocationFix(latitude = latitude, longitude = longitude, accuracyMeters = 10.0)
            .toSnapshot(nowEpochMillis)
        repository.put(
            cacheKey = ExternalEventContract.LOCATION_CACHE_KEY,
            source = ExternalEventContract.LOCATION_CACHE_SOURCE,
            payloadJson = com.littlekitty.xiaokele.core.common.json.JsonCodec.encode(snapshot),
            fetchedAt = nowEpochMillis,
            expiresAt = nowEpochMillis + ExternalEventContract.LOCATION_CACHE_TTL_MILLIS,
        )
        repository.put(
            cacheKey = "weather:latest:${snapshot.placeType.lowercase()}",
            source = ExternalEventContract.WEATHER_CACHE_SOURCE,
            payloadJson = weatherSnapshotJson(placeType = snapshot.placeType, condition = condition),
            fetchedAt = nowEpochMillis,
            expiresAt = nowEpochMillis + weatherTtlMillis,
        )
        return snapshot
    }

    fun locationRow(cacheKey: String, payloadJson: String, fetchedAt: Long, expiresAt: Long?): ExternalCacheEntity =
        ExternalCacheEntity(
            cacheKey = cacheKey,
            source = ExternalEventContract.LOCATION_CACHE_SOURCE,
            payloadJson = payloadJson,
            fetchedAt = fetchedAt,
            expiresAt = expiresAt,
        )

    val profilesForLocation: List<WeatherQueryProfile> = listOf(
        WeatherQueryProfile.CORE,
        WeatherQueryProfile.DAILY_BRIEF,
        WeatherQueryProfile.FULL,
    )
}
