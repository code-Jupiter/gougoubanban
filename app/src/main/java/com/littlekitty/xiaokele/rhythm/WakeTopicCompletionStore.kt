package com.littlekitty.xiaokele.rhythm

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Durable business-state guard for wake-topic work.
 *
 * WorkManager's KEEP policy only deduplicates unfinished work. Once a one-time
 * request is terminal, a later register call may enqueue it again. This store
 * records a completed candidate-handling pass for the local trigger day so
 * restarts and unlocks cannot turn that terminal state into another weather
 * request.
 */
class WakeTopicCompletionStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isCompleted(triggerDay: String): Boolean =
        prefs.contains(keyFor(triggerDay))

    /**
     * Uses commit so a worker's own re-registration observes the marker before
     * it can enqueue another same-day request.
     */
    fun markCompleted(triggerDay: String, completedAtEpochMillis: Long): Boolean =
        prefs.edit().putLong(keyFor(triggerDay), completedAtEpochMillis).commit()

    fun pruneBefore(earliestDayToKeep: LocalDate) {
        val editor = prefs.edit()
        var changed = false
        prefs.all.keys
            .asSequence()
            .filter { it.startsWith(KEY_PREFIX) }
            .forEach { key ->
                val day = runCatching {
                    LocalDate.parse(key.removePrefix(KEY_PREFIX), DAY_FORMAT)
                }.getOrNull()
                if (day == null || day.isBefore(earliestDayToKeep)) {
                    editor.remove(key)
                    changed = true
                }
            }
        if (changed) editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "little_kitty_wake_topic_completion"
        private const val KEY_PREFIX = "completed_"
        private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

        fun keyFor(triggerDay: String): String = "$KEY_PREFIX$triggerDay"
    }
}

/** Pure late-run guard; a late worker must never touch location or weather. */
object WakeTopicExecutionPolicy {
    fun windowState(
        nowEpochMillis: Long,
        windowStartEpochMillis: Long,
        windowEndEpochMillis: Long,
    ): WakeTopicWindowState = when {
        windowStartEpochMillis <= 0L || windowEndEpochMillis < windowStartEpochMillis -> WakeTopicWindowState.INVALID
        nowEpochMillis < windowStartEpochMillis -> WakeTopicWindowState.EARLY
        nowEpochMillis > windowEndEpochMillis -> WakeTopicWindowState.EXPIRED
        else -> WakeTopicWindowState.IN_WINDOW
    }
}

enum class WakeTopicWindowState {
    EARLY,
    IN_WINDOW,
    EXPIRED,
    INVALID,
}
