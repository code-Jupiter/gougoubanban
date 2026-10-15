package com.littlekitty.xiaokele.rhythm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import com.littlekitty.xiaokele.external.ExternalDataCoordinator
import org.koin.java.KoinJavaComponent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Registers one one-time wake-topic task per day at the estimated wake window
 * (or the 05:00 fallback). Task execution emits the WAKE_WEATHER_BRIEF
 * candidate; re-registration happens on app start, every unlock and after
 * each run so newly observed wake rhythm reshapes the same-day plan.
 */
class WakeTopicWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val diagnostics = KoinJavaComponent.getOrNull<DiagnosticsLogger>(DiagnosticsLogger::class.java)
        diagnostics?.taskStarted("wake_topic", KEY_ACTION_WEATHER)
        if (inputData.getString(KEY_ACTION) != KEY_ACTION_WEATHER) {
            diagnostics?.taskFinished("wake_topic", DiagnosticLogStatuses.SKIPPED, "unknown_action")
            return Result.failure()
        }

        val timeProvider = KoinJavaComponent.getOrNull<TimeProvider>(TimeProvider::class.java)
        if (timeProvider == null) {
            diagnostics?.taskFinished("wake_topic", DiagnosticLogStatuses.FAILED, "missing_time_provider")
            return Result.retry()
        }
        val now = timeProvider.now()
        val nowEpoch = now.toEpochMilli()
        val windowStart = inputData.getLong(KEY_WINDOW_START, 0L)
        val windowEnd = inputData.getLong(KEY_WINDOW_END, 0L)
        when (WakeTopicExecutionPolicy.windowState(nowEpoch, windowStart, windowEnd)) {
            WakeTopicWindowState.INVALID -> {
                diagnostics?.taskFinished("wake_topic", DiagnosticLogStatuses.SKIPPED, "invalid_window")
                return Result.failure()
            }

            WakeTopicWindowState.EARLY -> {
                diagnostics?.taskFinished("wake_topic", DiagnosticLogStatuses.RETRY, "before_wake_window")
                return Result.retry()
            }

            WakeTopicWindowState.EXPIRED -> {
                // This must happen before any location or weather operation. A
                // replacement allows the estimator to schedule a still-valid
                // fallback window before 15:00, or tomorrow otherwise.
                diagnostics?.taskFinished("wake_topic", DiagnosticLogStatuses.MISSED, "wake_window_expired")
                runCatching {
                    WakeTopicScheduler.register(applicationContext, replaceRunningWork = true)
                }
                return Result.success()
            }

            WakeTopicWindowState.IN_WINDOW -> Unit
        }

        val triggerDay = WakeTopicScheduler.triggerDayFor(windowStart, TimeProvider.DISPLAY_ZONE)
        val completionStore = WakeTopicCompletionStore(applicationContext)
        if (completionStore.isCompleted(triggerDay)) {
            diagnostics?.taskFinished("wake_topic", DiagnosticLogStatuses.SKIPPED, "already_completed:$triggerDay")
            runCatching { WakeTopicScheduler.register(applicationContext) }
            return Result.success()
        }

        val coordinator = KoinJavaComponent.getOrNull<ExternalDataCoordinator>(ExternalDataCoordinator::class.java)
        if (coordinator == null) {
            diagnostics?.taskFinished("wake_topic", DiagnosticLogStatuses.FAILED, "missing_external_coordinator")
            return Result.retry()
        }

        val result = try {
            val emission = coordinator.emitWakeWeatherBrief(
                basis = inputData.getString(KEY_BASIS),
                windowStartEpochMillis = windowStart,
                windowEndEpochMillis = windowEnd,
            )
            if (emission.outcome == com.littlekitty.xiaokele.external.WakeWeatherBriefEmissionOutcome.COMPLETED) {
                if (completionStore.markCompleted(triggerDay, nowEpoch)) {
                    diagnostics?.taskFinished("wake_topic", DiagnosticLogStatuses.SUCCESS)
                    Result.success()
                } else {
                    diagnostics?.taskFinished("wake_topic", DiagnosticLogStatuses.FAILED, "completion_marker_write_failed")
                    Result.retry()
                }
            } else {
                diagnostics?.taskFinished(
                    "wake_topic",
                    DiagnosticLogStatuses.RETRY,
                    "wake_candidate_retryable:${emission.refresh.outcome}",
                )
                Result.retry()
            }
        } catch (error: Throwable) {
            com.littlekitty.xiaokele.observability.PrivacyLog.warn(
                "wake_topic emission failed: ${error.javaClass.simpleName}: ${error.message ?: ""}",
            )
            diagnostics?.taskFinished("wake_topic", DiagnosticLogStatuses.FAILED, error.message)
            Result.retry()
        }
        // The completion marker is committed before this registration; failed
        // attempts remain retryable only while their original window is open.
        runCatching { WakeTopicScheduler.register(applicationContext) }
        return result
    }

    companion object {
        const val KEY_ACTION = "wake_topic_action"
        const val KEY_ACTION_WEATHER = "WAKE_WEATHER"
        const val KEY_BASIS = "wake_basis"
        const val KEY_WINDOW_START = "wake_window_start"
        const val KEY_WINDOW_END = "wake_window_end"
    }
}

class WakeTopicMaintenanceWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        WakeTopicScheduler.register(applicationContext)
        return Result.success()
    }

    companion object {
        const val UNIQUE_MAINTENANCE_WORK = "wake_topic_maintenance_v1"
    }
}

object WakeTopicScheduler {

    private val DAY_KEY = DateTimeFormatter.BASIC_ISO_DATE

    /**
     * Recomputes today's wake plan and (re)arms the one-time task. Cheap and
     * idempotent: a same-day plan with an unchanged trigger is a no-op.
     */
    suspend fun register(context: Context, replaceRunningWork: Boolean = false) {
        try {
            val db = KoinJavaComponent.getOrNull<AppDatabase>(AppDatabase::class.java) ?: return
            val timeProvider = KoinJavaComponent.getOrNull<TimeProvider>(TimeProvider::class.java) ?: return
            val now = timeProvider.now()
            // Day boundaries (04:00 wake floor, 05:00 fallback, weekday classes)
            // are product-day concepts in the display zone, not UTC days.
            val zone = TimeProvider.DISPLAY_ZONE
            val estimator = WakeWindowEstimator { startInclusive, endExclusive ->
                db.messageDao().firstUserMessageAtBetween(startInclusive, endExclusive)
            }
            // The public edition has no schedule: the wake window comes from the
            // user's own observed unlock/first-message rhythm plus the daily floor.
            val plan = estimator.estimate(
                now = now,
                zone = zone,
                unlockObservations = RhythmObservationStore(context).allDays(),
            )
            com.littlekitty.xiaokele.observability.PrivacyLog.debug(
                "wake_topic plan basis=${plan.basis} trigger=${plan.triggerAtEpochMillis} " +
                    "confident=${plan.confident} note=${plan.note}",
            )
            enqueue(context, now, zone, plan, replaceRunningWork)
        } catch (error: Throwable) {
            com.littlekitty.xiaokele.observability.PrivacyLog.warn(
                "wake_topic register failed: ${error.javaClass.simpleName}: ${error.message ?: ""}",
            )
            throw error
        }
    }

    fun enqueue(
        context: Context,
        now: Instant,
        zone: ZoneId,
        plan: WakeWindowPlan,
        replaceRunningWork: Boolean = false,
    ) {
        val completionStore = WakeTopicCompletionStore(context)
        val triggerDay = triggerDayFor(plan.triggerAtEpochMillis, zone)
        completionStore.pruneBefore(now.atZone(zone).toLocalDate().minusDays(COMPLETION_HISTORY_DAYS))
        if (!completionStore.isCompleted(triggerDay)) {
            val uniqueName = uniqueWorkName(triggerDay)
            val delay = (plan.triggerAtEpochMillis - now.toEpochMilli()).coerceAtLeast(0L)
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName,
                // KEEP prevents concurrent/pending duplicate work only. The
                // durable completion store above is the business-state guard
                // once a one-time request becomes terminal.
                if (replaceRunningWork) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WakeTopicWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setInputData(
                        workDataOf(
                            WakeTopicWorker.KEY_ACTION to WakeTopicWorker.KEY_ACTION_WEATHER,
                            WakeTopicWorker.KEY_BASIS to plan.basis.name,
                            WakeTopicWorker.KEY_WINDOW_START to plan.windowStartEpochMillis,
                            WakeTopicWorker.KEY_WINDOW_END to plan.windowEndEpochMillis,
                        ),
                    )
                    .build(),
            )
        }
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WakeTopicMaintenanceWorker.UNIQUE_MAINTENANCE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WakeTopicMaintenanceWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(12, TimeUnit.HOURS)
                .build(),
        )
    }

    /** Day-stable for in-flight WorkManager dedup; completion is tracked separately. */
    fun uniqueWorkName(triggerDay: String): String = "wake_topic_$triggerDay"

    fun triggerDayFor(triggerEpochMillis: Long, zone: ZoneId): String =
        DAY_KEY.format(Instant.ofEpochMilli(triggerEpochMillis).atZone(zone))

    private const val COMPLETION_HISTORY_DAYS = 2L
}
