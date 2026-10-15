package com.littlekitty.xiaokele.rhythm

import android.content.Context
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Durable record of screen-unlock times per local day. This is the raw
 * observation feed for the wake-window estimator; nothing here is user
 * visible and only day-granular timestamps are retained.
 */
@Serializable
private data class RhythmObservationDocument(
    val days: Map<String, List<Long>> = emptyMap(),
)

class RhythmObservationStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun recordUnlock(atEpochMillis: Long, zone: ZoneId) {
        val dayKey = DateTimeFormatter.BASIC_ISO_DATE.format(
            Instant.ofEpochMilli(atEpochMillis).atZone(zone),
        )
        val days = read().toMutableMap()
        val updated = (days[dayKey].orEmpty() + atEpochMillis).take(MAX_UNLOCKS_PER_DAY)
        days[dayKey] = updated
        write(prune(days, atEpochMillis, zone))
    }

    @Synchronized
    fun unlockTimesOfDay(dayKey: String): List<Long> = read()[dayKey].orEmpty()

    @Synchronized
    fun allDays(): Map<String, List<Long>> = read()

    private fun prune(
        days: Map<String, List<Long>>,
        nowEpochMillis: Long,
        zone: ZoneId,
    ): Map<String, List<Long>> {
        val cutoffDay = DateTimeFormatter.BASIC_ISO_DATE.format(
            Instant.ofEpochMilli(nowEpochMillis).atZone(zone).minusDays(RETENTION_DAYS),
        )
        return days.filterKeys { it >= cutoffDay }
    }

    private fun read(): Map<String, List<Long>> {
        val raw = prefs.getString(KEY_DOCUMENT, null) ?: return emptyMap()
        return runCatching { JsonCodec.decode<RhythmObservationDocument>(raw) }
            .getOrNull()?.days ?: emptyMap()
    }

    private fun write(days: Map<String, List<Long>>) {
        prefs.edit()
            .putString(KEY_DOCUMENT, JsonCodec.encode(RhythmObservationDocument(days)))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "little_kitty_rhythm_observations"
        private const val KEY_DOCUMENT = "document"
        private const val MAX_UNLOCKS_PER_DAY = 16
        private const val RETENTION_DAYS = 21L
    }
}
