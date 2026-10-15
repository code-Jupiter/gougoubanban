package com.littlekitty.xiaokele.rhythm

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** How today's wake plan was derived; surfaced in event payloads for diagnostics. */
enum class WakeWindowBasis {
    BEHAVIOR_WEEKDAY,
    BEHAVIOR_WEEKEND,
    FALLBACK_FIXED_TIME,
}

/**
 * One deterministic wake plan for the companion-initiated weather brief.
 * `confident=false` plans intentionally fall back to the 05:00 fixed task:
 * content is generated before the user wakes and delivered as a silent
 * notification / pending bubble at the next unlock.
 */
data class WakeWindowPlan(
    val basis: WakeWindowBasis,
    val triggerAtEpochMillis: Long,
    val windowStartEpochMillis: Long,
    val windowEndEpochMillis: Long,
    val confident: Boolean,
    val note: String,
)

/**
 * Estimates when the user likely wakes today from their own observed
 * unlock/first-message rhythm, with a fixed daily fallback.
 *
 * The public edition has no schedule or shift, so there is no
 * schedule-derived branch: the estimate is only a local contact-timing prior.
 */
class WakeWindowEstimator(
    private val firstUserMessageAt: suspend (startInclusive: Long, endExclusive: Long) -> Long?,
) {

    suspend fun estimate(
        now: Instant,
        zone: ZoneId,
        unlockObservations: Map<String, List<Long>>,
    ): WakeWindowPlan {
        val today = now.atZone(zone).toLocalDate()
        return planFromBehavior(
            now = now,
            zone = zone,
            today = today,
            todayIsWeekend = today.dayOfWeek.value >= 6,
            unlockObservations = unlockObservations,
        )
    }

    private suspend fun planFromBehavior(
        now: Instant,
        zone: ZoneId,
        today: LocalDate,
        todayIsWeekend: Boolean,
        unlockObservations: Map<String, List<Long>>,
    ): WakeWindowPlan {
        val nowMillis = now.toEpochMilli()
        val dayTypeLabel = if (todayIsWeekend) "weekend" else "weekday"
        // Historical wake proxies are minutes-after-04:00 offsets, not absolute
        // epochs: medians of raw past-day timestamps would always look like a
        // missed window and silently degrade every behavior plan to 05:00.
        val samples = mutableListOf<Long>()
        for (offset in 1..BEHAVIOR_SAMPLE_DAYS) {
            val date = today.minusDays(offset.toLong())
            val proxyMinutes = wakeProxyMinutesFor(date, zone, unlockObservations)?.toLong() ?: continue
            val isWeekendSample = date.dayOfWeek.value >= 6
            if (isWeekendSample == todayIsWeekend) samples += proxyMinutes
        }
        if (samples.size >= BEHAVIOR_CONFIDENT_SAMPLES) {
            val medianMinutes = median(samples)
            val projected = today.atStartOfDay(zone).toInstant().toEpochMilli() +
                WAKE_DAY_FLOOR_MILLIS + medianMinutes * 60_000L
            return clampedPlan(
                basis = if (todayIsWeekend) WakeWindowBasis.BEHAVIOR_WEEKEND else WakeWindowBasis.BEHAVIOR_WEEKDAY,
                start = projected - BEHAVIOR_WINDOW_HALF_SPAN_MILLIS,
                end = projected + BEHAVIOR_WINDOW_HALF_SPAN_MILLIS,
                confident = true,
                note = "$dayTypeLabel rhythm from ${samples.size} samples (median +${medianMinutes}m after 04:00)",
                nowMillis = nowMillis,
                zone = zone,
                today = today,
            )
        }
        return fallbackPlan(nowMillis, zone, today, "$dayTypeLabel rhythm has ${samples.size} samples")
    }

    /** Earliest wake signal (unlock or first user message) of a past day as minutes after the 04:00 floor. */
    private suspend fun wakeProxyMinutesFor(
        date: LocalDate,
        zone: ZoneId,
        unlockObservations: Map<String, List<Long>>,
    ): Int? {
        val dayStart = date.atTime(4, 0).atZone(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atTime(4, 0).atZone(zone).toInstant().toEpochMilli()
        val dayKey = date.format(DAY_KEY_FORMATTER)
        val firstUnlock = unlockObservations[dayKey]
            ?.filter { it in dayStart until dayEnd }
            ?.minOrNull()
        val firstMessage = firstUserMessageAt(dayStart, dayEnd)
        // Earliest of the two signals is the wake proxy: both mark "already
        // awake", so whichever comes first is the tighter bound.
        val wakeEpoch = listOfNotNull(firstUnlock, firstMessage?.takeIf { it in dayStart until dayEnd })
            .minOrNull() ?: return null
        return java.time.Duration.ofMillis(wakeEpoch - dayStart).toMinutes().toInt().coerceAtLeast(0)
    }

    private fun fallbackPlan(nowMillis: Long, zone: ZoneId, today: LocalDate, note: String): WakeWindowPlan {
        val fixedAt = today.atTime(FALLBACK_LOCAL_TIME).atZone(zone).toInstant().toEpochMilli()
        val fallbackCutoff = today.atTime(FALLBACK_MISSED_CUTOFF).atZone(zone).toInstant().toEpochMilli()
        return when {
            nowMillis <= fixedAt -> plan(
                basis = WakeWindowBasis.FALLBACK_FIXED_TIME,
                trigger = fixedAt,
                note = "$note; fixed 05:00 task",
            )
            nowMillis < fallbackCutoff -> plan(
                basis = WakeWindowBasis.FALLBACK_FIXED_TIME,
                trigger = nowMillis,
                note = "$note; fixed task late, run now",
            )
            else -> plan(
                basis = WakeWindowBasis.FALLBACK_FIXED_TIME,
                trigger = today.plusDays(1).atTime(FALLBACK_LOCAL_TIME).atZone(zone).toInstant().toEpochMilli(),
                note = "$note; window already over, plan for tomorrow",
            )
        }
    }

    private fun clampedPlan(
        basis: WakeWindowBasis,
        start: Long,
        end: Long,
        confident: Boolean,
        note: String,
        nowMillis: Long,
        zone: ZoneId,
        today: LocalDate,
    ): WakeWindowPlan {
        val plan = plan(basis, start, note, confident)
        return when {
            nowMillis < plan.triggerAtEpochMillis -> plan
            nowMillis <= end -> plan.copy(
                triggerAtEpochMillis = nowMillis,
                note = "$note; inside window, run now",
            )
            else -> fallbackPlan(nowMillis, zone, today, "$note; window missed")
        }
    }

    private fun plan(
        basis: WakeWindowBasis,
        trigger: Long,
        note: String,
        confident: Boolean = true,
    ): WakeWindowPlan = WakeWindowPlan(
        basis = basis,
        triggerAtEpochMillis = trigger,
        windowStartEpochMillis = trigger,
        windowEndEpochMillis = trigger + WAKE_WINDOW_SPAN_MILLIS,
        confident = confident,
        note = note,
    )

    private fun median(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }

    companion object {
        /** Day floor used for wake-proxy offsets (04:00 local). */
        const val WAKE_DAY_FLOOR_MILLIS = 4L * 60L * 60L * 1000L

        /** Length of the estimated wake window. */
        const val WAKE_WINDOW_SPAN_MILLIS = 60L * 60L * 1000L

        /** Half span for behavior-based windows (median +/- 30 min). */
        const val BEHAVIOR_WINDOW_HALF_SPAN_MILLIS = 30L * 60L * 1000L

        /** Samples required before the behavior rhythm is considered confident. */
        const val BEHAVIOR_CONFIDENT_SAMPLES = 4

        const val BEHAVIOR_SAMPLE_DAYS = 14

        /** Fallback daily task time (05:00 local) when prediction is not confident. */
        val FALLBACK_LOCAL_TIME: LocalTime = LocalTime.of(5, 0)

        /** After this local time a missed window is no longer worth a late push. */
        val FALLBACK_MISSED_CUTOFF: LocalTime = LocalTime.of(15, 0)

        private val DAY_KEY_FORMATTER = java.time.format.DateTimeFormatter.BASIC_ISO_DATE
    }
}
