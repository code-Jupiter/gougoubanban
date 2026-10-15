package com.littlekitty.xiaokele.event

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.repository.TriggerManager
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.CancellationException
import org.koin.java.KoinJavaComponent
import java.util.concurrent.TimeUnit

/**
 * Phase 8 unique one-time trigger wake-up. A single REPLACE work carries the
 * delay until the earliest ACTIVE trigger; after the due pass it re-arms for
 * the next trigger, so a newly created earlier trigger reuses the same unique
 * work name instead of piling up duplicate workers.
 */
class TriggerWakeWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val scheduler = KoinJavaComponent.getOrNull<DueTriggerScheduler>(DueTriggerScheduler::class.java)
            ?: return Result.failure()
        val timeProvider = KoinJavaComponent.getOrNull<TimeProvider>(TimeProvider::class.java)
            ?: return Result.failure()
        val diagnostics = KoinJavaComponent.getOrNull<DiagnosticsLogger>(DiagnosticsLogger::class.java)
        diagnostics?.taskStarted("trigger_wake", UNIQUE_TRIGGER_WAKE_WORK)
        return try {
            val now = timeProvider.now()
            // FIRED rows can be left by process death after the atomic state
            // transition. Recover them through the same bounded late policy
            // before considering new ACTIVE triggers.
            scheduler.recoverFired(now)
            scheduler.dispatchDue(now)
            TriggerWakeScheduler.reconcile(applicationContext)
            Result.success()
                .also { diagnostics?.taskFinished("trigger_wake", DiagnosticLogStatuses.SUCCESS) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            diagnostics?.taskFinished("trigger_wake", DiagnosticLogStatuses.FAILED, error.message)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_TRIGGER_WAKE_WORK = "trigger_wake_v1"
    }
}

object TriggerWakeScheduler {

    suspend fun reconcile(context: Context) {
        val triggerManager = KoinJavaComponent.getOrNull<TriggerManager>(TriggerManager::class.java) ?: return
        val timeProvider = KoinJavaComponent.getOrNull<TimeProvider>(TimeProvider::class.java) ?: return
        val now = timeProvider.now().toEpochMilli()
        val nextFireAt = triggerManager.earliestActiveFireAt()
        enqueue(context, now, nextFireAt, triggerManager.hasFired())
    }

    fun enqueue(
        context: Context,
        nowEpochMillis: Long,
        nextFireAt: Long?,
        hasFiredRecoveryBacklog: Boolean = false,
    ) {
        val delayMillis = delayMillis(nowEpochMillis, nextFireAt, hasFiredRecoveryBacklog)
        if (delayMillis == null) {
            WorkManager.getInstance(context).cancelUniqueWork(TriggerWakeWorker.UNIQUE_TRIGGER_WAKE_WORK)
        } else {
            val request = OneTimeWorkRequestBuilder<TriggerWakeWorker>()
                .setInitialDelay(delayMillis.coerceAtMost(MAX_INITIAL_DELAY_MILLIS), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                TriggerWakeWorker.UNIQUE_TRIGGER_WAKE_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    /** Pure delay policy for deterministic unit tests. */
    fun delayMillis(
        nowEpochMillis: Long,
        nextFireAt: Long?,
        hasFiredRecoveryBacklog: Boolean = false,
    ): Long? {
        val activeDelay = nextFireAt?.let { fireAt ->
            if (fireAt <= nowEpochMillis) BATCH_REARM_DELAY_MILLIS else fireAt - nowEpochMillis
        }
        val firedDelay = BATCH_REARM_DELAY_MILLIS.takeIf { hasFiredRecoveryBacklog }
        return listOfNotNull(activeDelay, firedDelay).minOrNull()
    }

    const val MAX_INITIAL_DELAY_MILLIS = 365L * 24L * 60L * 60L * 1000L
    const val BATCH_REARM_DELAY_MILLIS = 5L * 60L * 1_000L
}
