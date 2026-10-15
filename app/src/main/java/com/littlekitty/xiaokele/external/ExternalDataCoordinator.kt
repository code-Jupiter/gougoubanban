package com.littlekitty.xiaokele.external

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.NormalizedEvent
import com.littlekitty.xiaokele.core.common.scene.ScenePriorities
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.repository.ExternalCacheRepository
import com.littlekitty.xiaokele.core.network.external.WeatherQueryProfile
import com.littlekitty.xiaokele.event.BackgroundProcessingResult
import com.littlekitty.xiaokele.event.EventDispatchResult
import com.littlekitty.xiaokele.event.LocalBackgroundDispatcher
import com.littlekitty.xiaokele.scene.SceneCheckScheduler
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Distinguishes a completed wake-candidate pass from a condition worth retrying
 * while the wake window remains open. A WeatherRefreshReport alone cannot make
 * that distinction because a fresh weather snapshot may still lack a usable
 * location or fail to enter the event pipeline.
 */
data class WakeWeatherBriefEmissionReport(
    val refresh: WeatherRefreshReport,
    val outcome: WakeWeatherBriefEmissionOutcome,
)

enum class WakeWeatherBriefEmissionOutcome {
    COMPLETED,
    RETRYABLE,
}

/**
 * Public-edition external data coordinator.
 *
 * It owns exactly two things: caching the user's current foreground location and
 * refreshing weather for it. Shift sync, shift windows and fixed-place
 * transitions were removed with the shift feature, so location here is a cached
 * coordinate with a freshness TTL and nothing more.
 */
class ExternalDataCoordinator(
    private val externalCacheRepository: ExternalCacheRepository,
    private val contextResolver: ExternalContextResolver,
    private val weatherRefresh: WeatherRefresh,
    private val timeProvider: TimeProvider,
    private val dispatcher: LocalBackgroundDispatcher,
    private val sceneCheckScheduler: SceneCheckScheduler? = null,
    private val locationFixProvider: LocationFixProvider? = null,
) {

    private val locationMutex = Mutex()

    suspend fun refreshWeather(
        force: Boolean = false,
        queryProfile: WeatherQueryProfile = WeatherQueryProfile.CORE,
    ): WeatherRefreshReport {
        locationFixProvider?.let { provider ->
            val fix = provider.currentFix()
                ?: return WeatherRefreshReport(outcome = ExternalSyncOutcomes.SKIPPED_NO_LOCATION)
            val locationReport = onLocationFix(fix)
            if (locationReport.outcome == ExternalSyncOutcomes.FAILED) {
                return WeatherRefreshReport(
                    outcome = ExternalSyncOutcomes.FAILED,
                    error = locationReport.error,
                )
            }
            if (locationReport.outcome == ExternalSyncOutcomes.SKIPPED_LOCATION_UNUSABLE ||
                locationReport.outcome == ExternalSyncOutcomes.IGNORED_STALE_CACHE
            ) {
                return WeatherRefreshReport(outcome = ExternalSyncOutcomes.SKIPPED_NO_LOCATION)
            }
        }
        return weatherRefresh.refresh(force, queryProfile).also { sceneCheckScheduler?.check() }
    }

    /**
     * Entry point used by USER_PRESENT. Refreshes location/weather so the
     * cached scene context stays fresh; the wake weather brief is scheduled by
     * the rhythm estimator (WakeTopicScheduler), not by unlock time anymore.
     */
    suspend fun onScreenUnlock() {
        refreshWeather(force = false)
        sceneCheckScheduler?.check()
    }

    /**
     * WAKE_WEATHER_BRIEF producer for the wake-topic task. It reports whether
     * a candidate pass actually completed so the worker can persist its
     * same-day completion gate only after a usable result.
     */
    suspend fun emitWakeWeatherBrief(
        basis: String?,
        windowStartEpochMillis: Long,
        windowEndEpochMillis: Long,
    ): WakeWeatherBriefEmissionReport {
        val now = timeProvider.now()
        val nowEpoch = now.toEpochMilli()
        val zone = TimeProvider.DISPLAY_ZONE
        val localDate = now.atZone(zone).toLocalDate()
        val eventKey = ExternalEventContract.wakeWeatherEventKey(localDate.toString())
        // The event marker is an independent recovery guard. Checking it before
        // weather prevents a process-death recovery from making a redundant API
        // request if the scheduler completion marker was not committed.
        if (externalCacheRepository.get(eventKey, ExternalEventContract.WAKE_WEATHER_EVENT_SOURCE) != null) {
            return WakeWeatherBriefEmissionReport(
                refresh = WeatherRefreshReport(outcome = ExternalSyncOutcomes.SKIPPED_NOT_DUE),
                outcome = WakeWeatherBriefEmissionOutcome.COMPLETED,
            )
        }
        val refresh = refreshWeather(
            force = false,
            queryProfile = WeatherQueryProfile.DAILY_BRIEF,
        )
        // A weather failure cooldown is intentionally surfaced as
        // SKIPPED_NOT_DUE by WeatherRefresh to protect the upstream API. It
        // is nevertheless not a completed wake pass: retry with WorkManager
        // while this task's original wake window remains open.
        if (refresh.outcome in RETRYABLE_WEATHER_OUTCOMES || refresh.skippedFailureCooldown) {
            return WakeWeatherBriefEmissionReport(refresh, WakeWeatherBriefEmissionOutcome.RETRYABLE)
        }
        val location = contextResolver.resolveLocationSnapshot(nowEpoch)
            ?: return WakeWeatherBriefEmissionReport(refresh, WakeWeatherBriefEmissionOutcome.RETRYABLE)
        val weather = contextResolver.resolveWeatherSnapshot(nowEpoch)
            ?: return WakeWeatherBriefEmissionReport(refresh, WakeWeatherBriefEmissionOutcome.RETRYABLE)
        val expiresAt = nowEpoch + ExternalEventContract.WAKE_WEATHER_EVENT_TTL_MILLIS
        val event = NormalizedEvent(
            eventKey = eventKey,
            type = ExternalEventContract.EVENT_TYPE_WAKE_WEATHER_BRIEF,
            source = ExternalEventContract.SOURCE_WAKE_SCHEDULER,
            occurredAt = InstantJson.formatUtc(now),
            receivedAt = InstantJson.formatUtc(now),
            expiresAt = InstantJson.formatUtc(Instant.ofEpochMilli(expiresAt)),
            priority = ScenePriorities.LOCAL_CANDIDATE_DEFAULT,
            payload = buildJsonObject {
                put("eligible", true)
                put("basis", basis.orEmpty())
                put("window_start_epoch_millis", windowStartEpochMillis)
                put("window_end_epoch_millis", windowEndEpochMillis)
                put("local_date", localDate.toString())
                put("place_type", location.placeType)
                put("weather_observed_at", weather.observedAt)
                put("weather_condition", weather.condition)
            },
        )
        val result = dispatcher.dispatch(event)
        if (result.isWakeCandidateCompleted()) {
            externalCacheRepository.put(
                cacheKey = eventKey,
                source = ExternalEventContract.WAKE_WEATHER_EVENT_SOURCE,
                payloadJson = "{\"created\":true}",
                fetchedAt = nowEpoch,
                expiresAt = expiresAt,
            )
        }
        return WakeWeatherBriefEmissionReport(
            refresh = refresh,
            outcome = if (result.isWakeCandidateCompleted()) {
                WakeWeatherBriefEmissionOutcome.COMPLETED
            } else {
                WakeWeatherBriefEmissionOutcome.RETRYABLE
            },
        )
    }

    private fun EventDispatchResult.isWakeCandidateCompleted(): Boolean = when (this) {
        is EventDispatchResult.Processed -> processing !is BackgroundProcessingResult.RetryScheduled
        is EventDispatchResult.Duplicate -> true
        is EventDispatchResult.Expired, is EventDispatchResult.Invalid -> false
    }

    /** Nearby dining is intentionally disabled in this product version. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun searchNearby(force: Boolean = false): NearbyCandidateReport =
        NearbyCandidateReport(outcome = ExternalSyncOutcomes.SKIPPED_FEATURE_DISABLED)

    suspend fun resolveLocationSnapshot(nowEpochMillis: Long): LocationSnapshot? =
        contextResolver.resolveLocationSnapshot(nowEpochMillis)

    /**
     * Caches the current foreground fix. There is no place/geofence evaluation:
     * an unusable fix is skipped, and a fix older than the cached one never
     * overwrites it. No location event is dispatched, because the public
     * edition has no arrival/departure semantics.
     */
    suspend fun onLocationFix(fix: LocationFix): LocationUpdateReport = locationMutex.withLock {
        val observedAtEpochMillis = timeProvider.now().toEpochMilli()
        if (fix.latitude.isNaN() || fix.longitude.isNaN() ||
            fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0
        ) {
            return@withLock LocationUpdateReport(outcome = ExternalSyncOutcomes.SKIPPED_LOCATION_UNUSABLE)
        }
        val snapshot = fix.toSnapshot(observedAtEpochMillis)
        val write = cacheLocationSnapshot(snapshot)
        if (!write.written) {
            return@withLock LocationUpdateReport(
                outcome = ExternalSyncOutcomes.IGNORED_STALE_CACHE,
                skippedStaleCache = true,
            )
        }
        LocationUpdateReport(
            outcome = ExternalSyncOutcomes.SYNCED,
            cacheWritten = true,
        )
    }

    private suspend fun cacheLocationSnapshot(snapshot: LocationSnapshot) =
        externalCacheRepository.putIfFresher(
            cacheKey = ExternalEventContract.LOCATION_CACHE_KEY,
            source = ExternalEventContract.LOCATION_CACHE_SOURCE,
            payloadJson = JsonCodec.encode(snapshot),
            fetchedAt = snapshot.observedAtEpochMillis,
            expiresAt = snapshot.observedAtEpochMillis + ExternalEventContract.LOCATION_CACHE_TTL_MILLIS,
        )

    private companion object {
        val RETRYABLE_WEATHER_OUTCOMES = setOf(
            ExternalSyncOutcomes.FAILED,
            ExternalSyncOutcomes.EMPTY,
            ExternalSyncOutcomes.SKIPPED_NO_LOCATION,
            ExternalSyncOutcomes.SKIPPED_LOCATION_UNUSABLE,
        )
    }
}
