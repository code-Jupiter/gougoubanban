package com.littlekitty.xiaokele.external

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.NormalizedEvent
import com.littlekitty.xiaokele.core.common.scene.ScenePriorities
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.repository.ExternalCacheRepository
import com.littlekitty.xiaokele.core.network.external.WeatherFetchResult
import com.littlekitty.xiaokele.core.network.external.WeatherLocation
import com.littlekitty.xiaokele.core.network.external.WeatherService
import com.littlekitty.xiaokele.core.network.external.WeatherSnapshotDto
import com.littlekitty.xiaokele.core.network.external.WeatherQueryProfile
import com.littlekitty.xiaokele.event.EventDispatchResult
import com.littlekitty.xiaokele.event.LocalBackgroundDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Weather refresh consumes the location snapshot prepared by
 * ExternalDataCoordinator and only then calls the weather provider.
 */
class WeatherRefresh(
    private val service: WeatherService,
    private val externalCacheRepository: ExternalCacheRepository,
    private val contextResolver: ExternalContextResolver,
    private val timeProvider: TimeProvider,
    private val dispatcher: LocalBackgroundDispatcher,
) {

    private val mutex = Mutex()

    suspend fun refresh(
        force: Boolean = false,
        queryProfile: WeatherQueryProfile = WeatherQueryProfile.CORE,
    ): WeatherRefreshReport = mutex.withLock {
        val now = timeProvider.now()
        val nowEpoch = now.toEpochMilli()
        val snapshot = contextResolver.resolveLocationSnapshot(nowEpoch) ?: run {
            return@withLock WeatherRefreshReport(outcome = ExternalSyncOutcomes.SKIPPED_NO_LOCATION)
        }
        val place = snapshot.placeType
        val cacheKey = weatherCacheKey(place)
        val cached = externalCacheRepository.get(cacheKey, ExternalEventContract.WEATHER_CACHE_SOURCE)
        val cachedSnapshot = cached?.let { runCatching { JsonCodec.decode<WeatherSnapshotDto>(it.payloadJson) }.getOrNull() }
        if (!force && cached != null && nowEpoch - cached.fetchedAt < ExternalEventContract.WEATHER_CACHE_TTL_MILLIS &&
            cachedSnapshot.satisfies(queryProfile)
        ) {
            return@withLock WeatherRefreshReport(outcome = ExternalSyncOutcomes.SKIPPED_NOT_DUE)
        }
        val failureCooldown = externalCacheRepository.get(
            weatherFailureCooldownKey(place),
            ExternalEventContract.WEATHER_FAILURE_COOLDOWN_SOURCE,
        )
        if (failureCooldown?.expiresAt?.let { it > nowEpoch } == true) {
            return@withLock WeatherRefreshReport(
                outcome = ExternalSyncOutcomes.SKIPPED_NOT_DUE,
                skippedFailureCooldown = true,
            )
        }

        val location = WeatherLocation(
            placeType = place,
            latitude = snapshot.latitude,
            longitude = snapshot.longitude,
        )
        return@withLock when (val result = service.fetch(location, queryProfile)) {
            is WeatherFetchResult.Failure -> {
                recordFailureCooldown(place, nowEpoch, result.error)
                WeatherRefreshReport(
                    outcome = ExternalSyncOutcomes.FAILED,
                    error = result.error,
                )
            }

            is WeatherFetchResult.Empty -> WeatherRefreshReport(outcome = ExternalSyncOutcomes.EMPTY)

            is WeatherFetchResult.Success -> {
                val weather = result.snapshot
                val previousWeather = cached?.let { runCatching { JsonCodec.decode<WeatherSnapshotDto>(it.payloadJson) }.getOrNull() }
                val currentTemperature = weather.temperatureCelsius
                val previousTemperature = previousWeather?.temperatureCelsius
                val weatherWithDelta = weather.copy(
                    temperatureDeltaCelsius = if (currentTemperature != null && previousTemperature != null) {
                        currentTemperature - previousTemperature
                    } else {
                        weather.temperatureDeltaCelsius
                    },
                )
                val validation = validate(weatherWithDelta)
                if (validation != null) {
                    return@withLock WeatherRefreshReport(
                        outcome = ExternalSyncOutcomes.FAILED,
                        error = AppError(
                            code = ErrorCode.WEATHER_INVALID_RESPONSE,
                            userMessage = null,
                            technicalDetail = validation,
                        ),
                    )
                }
                val observedAt = InstantJson.parseOrNull(weatherWithDelta.observedAt).toEpochOrThrow()
                val snapshotExpiresAt = InstantJson.parseOrNull(weatherWithDelta.expiresAt).toEpochOrThrow()
                val cacheExpiresAt = minOf(
                    nowEpoch + ExternalEventContract.WEATHER_CACHE_TTL_MILLIS,
                    snapshotExpiresAt,
                )
                val payloadWeather = weatherWithDelta.copy(provenance = weatherWithDelta.provenance ?: service.provenance)
                val write = externalCacheRepository.putIfFresher(
                    cacheKey = cacheKey,
                    source = ExternalEventContract.WEATHER_CACHE_SOURCE,
                    payloadJson = JsonCodec.encode(payloadWeather),
                    fetchedAt = observedAt,
                    expiresAt = cacheExpiresAt,
                )
                if (!write.written) {
                    return@withLock WeatherRefreshReport(
                        outcome = ExternalSyncOutcomes.IGNORED_STALE_CACHE,
                        skippedStaleCache = true,
                    )
                }

                val dispatchResults = mutableListOf<EventDispatchResult>()
                val keys = mutableListOf<String>()
                val changeKey = ExternalEventContract.weatherChangeEventKey(place, observedAt)
                dispatchResults += dispatcher.dispatch(
                    NormalizedEvent(
                        eventKey = changeKey,
                        type = ExternalEventContract.EVENT_TYPE_WEATHER_CHANGE,
                        source = ExternalEventContract.SOURCE_WEATHER_PROVIDER,
                        occurredAt = weather.observedAt,
                        receivedAt = InstantJson.formatUtc(now),
                        expiresAt = InstantJson.formatUtc(Instant.ofEpochMilli(cacheExpiresAt)),
                        priority = ScenePriorities.WEATHER_CHANGE,
                        payload = buildJsonObject {
                            put("place_type", place)
                            put("condition", weather.condition)
                            weather.temperatureCelsius?.let { put("temperature_celsius", it) }
                            weather.precipitationMm?.let { put("precipitation_mm", it) }
                            weather.temperatureTrend?.let { put("temperature_trend", it) }
                            weather.temperatureDeltaCelsius?.let { put("temperature_delta_celsius", it) }
                            weather.aqi?.let { put("aqi", it) }
                            weather.aqiCategory?.let { put("aqi_category", it) }
                            put("observed_at", weather.observedAt)
                            put("expires_at", weather.expiresAt)
                            put("provenance", provenanceJson(ExternalEventContract.SOURCE_WEATHER_PROVIDER, service.provenance, observedAt, cacheKey, service.isDebugFake))
                        },
                    ),
                )
                keys += changeKey

                weather.alerts.forEach { alert ->
                    val alertStarts = InstantJson.parseOrNull(alert.startsAt)?.toEpochMilli()
                    val alertEnds = InstantJson.parseOrNull(alert.endsAt)?.toEpochMilli()
                    if (alertStarts == null || alertEnds == null || alertEnds <= nowEpoch) return@forEach
                    val alertExpires = minOf(alertEnds, nowEpoch + ExternalEventContract.WEATHER_ALERT_TTL_MILLIS)
                    val alertKey = ExternalEventContract.weatherAlertEventKey(place, alert.id)
                    dispatchResults += dispatcher.dispatch(
                        NormalizedEvent(
                            eventKey = alertKey,
                            type = ExternalEventContract.EVENT_TYPE_WEATHER_ALERT,
                            source = ExternalEventContract.SOURCE_WEATHER_PROVIDER,
                            occurredAt = alert.startsAt,
                            receivedAt = InstantJson.formatUtc(now),
                            expiresAt = InstantJson.formatUtc(Instant.ofEpochMilli(alertExpires)),
                            priority = ScenePriorities.HIGH_WEATHER_ALERT,
                            payload = buildJsonObject {
                                put("place_type", place)
                                put("alert_id", alert.id)
                                put("severity", alert.severity)
                                put("title", alert.title)
                                put("source", alert.source)
                                put("starts_at", alert.startsAt)
                                put("ends_at", alert.endsAt)
                                alert.instruction?.let { put("instruction", it) }
                                put("provenance", provenanceJson(ExternalEventContract.SOURCE_WEATHER_PROVIDER, service.provenance, observedAt, cacheKey, service.isDebugFake))
                            },
                        ),
                    )
                    keys += alertKey
                }

                WeatherRefreshReport(
                    outcome = ExternalSyncOutcomes.SYNCED,
                    cacheWritten = true,
                    dispatchResults = dispatchResults,
                    generatedEventKeys = keys,
                )
            }
        }
    }

    private fun validate(weather: WeatherSnapshotDto): String? {
        if (weather.locationKey.isBlank()) return "weather_location_key_blank"
        if (weather.condition.isBlank()) return "weather_condition_blank"
        if (InstantJson.parseOrNull(weather.observedAt) == null) return "weather_observed_at_invalid"
        if (InstantJson.parseOrNull(weather.expiresAt) == null) return "weather_expires_at_invalid"
        val observed = InstantJson.parseOrNull(weather.observedAt)!!.toEpochMilli()
        val expires = InstantJson.parseOrNull(weather.expiresAt)!!.toEpochMilli()
        if (expires <= observed) return "weather_expires_before_observed"
        return null
    }

    private fun WeatherSnapshotDto?.satisfies(profile: WeatherQueryProfile): Boolean = when (profile) {
        WeatherQueryProfile.CORE -> this != null
        WeatherQueryProfile.SHIFT_COMMUTE -> this != null &&
            forecastHourly.isNotEmpty() && minutelyPrecipitation.isNotEmpty()
        WeatherQueryProfile.DAILY_BRIEF -> this != null &&
            forecastDaily.isNotEmpty() && forecastHourly.isNotEmpty()
        WeatherQueryProfile.FULL -> this != null &&
            forecastDaily.isNotEmpty() && forecastHourly.isNotEmpty() && indices.isNotEmpty()
    }

    private fun Instant?.toEpochOrThrow(): Long = checkNotNull(this).toEpochMilli()

    private suspend fun recordFailureCooldown(
        place: String,
        nowEpochMillis: Long,
        error: AppError,
    ) {
        val cooldownMillis = failureCooldownMillis(error) ?: return
        externalCacheRepository.put(
            cacheKey = weatherFailureCooldownKey(place),
            source = ExternalEventContract.WEATHER_FAILURE_COOLDOWN_SOURCE,
            payloadJson = "{\"cooldown\":true}",
            fetchedAt = nowEpochMillis,
            expiresAt = nowEpochMillis + cooldownMillis,
        )
    }

    private fun failureCooldownMillis(error: AppError): Long? = when {
        weatherHttpStatus(error.technicalDetail) == HTTP_TOO_MANY_REQUESTS ->
            ExternalEventContract.WEATHER_RATE_LIMIT_COOLDOWN_MILLIS

        error.code in RETRYABLE_FAILURE_CODES ||
            (error.code == ErrorCode.WEATHER_FAILURE && isTransientWeatherHttpFailure(error.technicalDetail)) ->
            ExternalEventContract.WEATHER_FAILURE_COOLDOWN_MILLIS

        else -> null
    }

    /** Only normalize the adapter's own HTTP classification; do not infer from provider text. */
    private fun weatherHttpStatus(detail: String?): Int? = detail
        ?.takeIf { it.startsWith(WEATHER_HTTP_DETAIL_PREFIX) }
        ?.removePrefix(WEATHER_HTTP_DETAIL_PREFIX)
        ?.toIntOrNull()

    private fun isTransientWeatherHttpFailure(detail: String?): Boolean = weatherHttpStatus(detail)
        ?.let { status -> status == HTTP_REQUEST_TIMEOUT || status in HTTP_SERVER_ERROR_RANGE }
        ?: false

    companion object {
        fun weatherCacheKey(place: String): String = "weather:latest:${place.lowercase()}"

        fun weatherFailureCooldownKey(place: String): String =
            "weather:failure_cooldown:${place.lowercase()}"

        private val RETRYABLE_FAILURE_CODES = setOf(
            ErrorCode.TIMEOUT,
            ErrorCode.NETWORK_UNAVAILABLE,
        )
        private const val WEATHER_HTTP_DETAIL_PREFIX = "qweather_http_"
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val HTTP_SERVER_ERROR_RANGE = 500..599
    }
}
