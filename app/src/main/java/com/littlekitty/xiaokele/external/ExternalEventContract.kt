package com.littlekitty.xiaokele.external

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * External-data contract for the public edition (see docs implementation gates).
 * Event keys, cache keys, sources, types, TTLs and due windows live here only;
 * ad-hoc event keys are forbidden.
 *
 * Shift, meal-window, fixed-place and location-transition members were removed
 * together with the shift feature: the companion has no shift or geofence, so no
 * such event may be produced or keyed.
 */
object ExternalEventContract {

    const val SOURCE_WEATHER_PROVIDER = "WEATHER_PROVIDER"
    const val SOURCE_NEARBY_PROVIDER = "NEARBY_PROVIDER"
    const val SOURCE_LOCATION_PROVIDER = "LOCATION_PROVIDER"

    const val EVENT_TYPE_WEATHER_CHANGE = "WEATHER_CHANGE"
    const val EVENT_TYPE_WEATHER_ALERT = "WEATHER_ALERT"
    const val EVENT_TYPE_DAILY_WEATHER_BRIEF = "DAILY_WEATHER_BRIEF"
    const val EVENT_TYPE_WAKE_WEATHER_BRIEF = "WAKE_WEATHER_BRIEF"
    const val EVENT_TYPE_DISCOVERY_TOPIC_NUDGE = "DISCOVERY_TOPIC_NUDGE"
    const val SOURCE_WAKE_SCHEDULER = "WAKE_TOPIC_SCHEDULER"
    const val WAKE_WEATHER_EVENT_SOURCE = "WAKE_WEATHER_SCENE"
    const val WAKE_WEATHER_EVENT_TTL_MILLIS = 4L * 60L * 60L * 1000L
    const val DISCOVERY_NUDGE_EVENT_SOURCE = "DISCOVERY_NUDGE"
    const val DISCOVERY_NUDGE_MAX_ITEMS = 5
    const val EVENT_TYPE_NEARBY_CANDIDATE = "NEARBY_CANDIDATE"

    const val WEATHER_CACHE_SOURCE = "WEATHER_SYNC"
    const val WEATHER_FAILURE_COOLDOWN_SOURCE = "WEATHER_FAILURE_COOLDOWN"
    const val NEARBY_CACHE_SOURCE = "NEARBY_SYNC"
    const val LOCATION_CACHE_SOURCE = "LOCATION_SYNC"
    const val LOCATION_CACHE_KEY = "location:latest_snapshot"

    const val WEATHER_CACHE_TTL_MILLIS = 1L * 60L * 60L * 1000L
    const val WEATHER_FAILURE_COOLDOWN_MILLIS = 2L * 60L * 1000L
    const val WEATHER_RATE_LIMIT_COOLDOWN_MILLIS = 5L * 60L * 1000L
    const val WEATHER_CHANGE_BUCKET_MILLIS = 3L * 60L * 60L * 1000L
    const val WEATHER_ALERT_TTL_MILLIS = 6L * 60L * 60L * 1000L

    const val NEARBY_CACHE_TTL_MILLIS = 6L * 60L * 60L * 1000L
    const val NEARBY_BUCKET_MILLIS = 6L * 60L * 60L * 1000L

    const val LOCATION_CACHE_TTL_MILLIS = 6L * 60L * 60L * 1000L
    const val LOCATION_BUCKET_MILLIS = 6L * 60L * 60L * 1000L

    fun bucket(epochMillis: Long, bucketMillis: Long): Long =
        Math.floorDiv(epochMillis, bucketMillis) * bucketMillis

    fun weatherChangeEventKey(place: String, nowEpochMillis: Long): String =
        "weather:change:${slug(place)}:${bucket(nowEpochMillis, WEATHER_CHANGE_BUCKET_MILLIS)}"

    fun weatherAlertEventKey(place: String, alertId: String): String =
        "weather:alert:${slug(place)}:${slug(alertId)}"

    fun dailyWeatherEventKey(localDate: String): String =
        "weather:daily:${slug(localDate)}"

    fun wakeWeatherEventKey(localDate: String): String =
        "weather:wake:${slug(localDate)}"

    fun discoveryNudgeEventKey(localDate: String): String =
        "discovery:nudge:${slug(localDate)}"

    fun nearbyEventKey(place: String, merchantId: String, nowEpochMillis: Long): String =
        "nearby:candidate:${slug(place)}:${bucket(nowEpochMillis, NEARBY_BUCKET_MILLIS)}:${slug(merchantId)}"

    private fun slug(value: String): String {
        val normalized = value.lowercase()
            .map { ch -> if (ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_') ch else '_' }
            .joinToString("")
            .trim('_')
        if (normalized.isBlank()) return "unknown"
        return normalized.take(64)
    }
}

object ExternalSyncOutcomes {
    const val SYNCED = "SYNCED"
    const val NOT_MODIFIED = "NOT_MODIFIED"
    const val SKIPPED_NOT_DUE = "SKIPPED_NOT_DUE"
    const val SKIPPED_NO_LOCATION = "SKIPPED_NO_LOCATION"
    const val SKIPPED_FEATURE_DISABLED = "SKIPPED_FEATURE_DISABLED"
    const val EMPTY = "EMPTY"
    const val FAILED = "FAILED"
    const val IGNORED_STALE_CACHE = "IGNORED_STALE_CACHE"
    const val SKIPPED_LOCATION_UNUSABLE = "SKIPPED_LOCATION_UNUSABLE"
}

@Serializable
data class ExternalEventProvenance(
    val provider: String,
    @SerialName("service_provenance")
    val serviceProvenance: String,
    @SerialName("fetched_at")
    val fetchedAt: String,
    @SerialName("cache_key")
    val cacheKey: String,
    @SerialName("is_debug_fake")
    val isDebugFake: Boolean = false,
)
