package com.littlekitty.xiaokele.channel

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.CancellationException
import org.koin.java.KoinJavaComponent
import java.util.concurrent.TimeUnit

/**
 * Phase 8 unique persisted-outbox retry worker. It only retries rows that are
 * still in known FAILED state with a due next_retry_at; OUTCOME_UNKNOWN rows
 * are never eligible. After each pass it re-arms one unique one-time work for
 * the next due timestamp instead of registering a periodic blind poll.
 */
class OutboxRetryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val scheduler = KoinJavaComponent.getOrNull<ChannelRetryScheduler>(ChannelRetryScheduler::class.java)
            ?: return Result.failure()
        val timeProvider = KoinJavaComponent.getOrNull<TimeProvider>(TimeProvider::class.java)
            ?: return Result.failure()
        val diagnostics = KoinJavaComponent.getOrNull<DiagnosticsLogger>(DiagnosticsLogger::class.java)
        diagnostics?.taskStarted("outbox_retry", UNIQUE_OUTBOX_RETRY_WORK)
        return try {
            scheduler.dispatchRetries()
            OutboxRetryWorkScheduler.reconcile(applicationContext, timeProvider.now().toEpochMilli())
            Result.success()
                .also { diagnostics?.taskFinished("outbox_retry", DiagnosticLogStatuses.SUCCESS) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            diagnostics?.taskFinished("outbox_retry", DiagnosticLogStatuses.FAILED, error.message)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_OUTBOX_RETRY_WORK = "outbox_retry_v1"
    }
}

object OutboxRetryWorkScheduler {

    suspend fun reconcile(context: Context, nowEpochMillis: Long) {
        val scheduler = KoinJavaComponent.getOrNull<ChannelRetryScheduler>(ChannelRetryScheduler::class.java)
            ?: return
        val nextRetryAt = scheduler.nextRetryAtEpochMillis()
        val delayMillis = when {
            nextRetryAt == null -> null
            nextRetryAt <= nowEpochMillis -> 0L
            else -> nextRetryAt - nowEpochMillis
        }
        if (delayMillis == null) {
            WorkManager.getInstance(context).cancelUniqueWork(OutboxRetryWorker.UNIQUE_OUTBOX_RETRY_WORK)
        } else {
            val request = OneTimeWorkRequestBuilder<OutboxRetryWorker>()
                .setInitialDelay(delayMillis.coerceAtMost(MAX_INITIAL_DELAY_MILLIS), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                OutboxRetryWorker.UNIQUE_OUTBOX_RETRY_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }

    /** Pure delay policy for deterministic unit tests. */
    fun delayMillis(nowEpochMillis: Long, nextRetryAt: Long?): Long? = when {
        nextRetryAt == null -> null
        nextRetryAt <= nowEpochMillis -> 0L
        else -> nextRetryAt - nowEpochMillis
    }

    const val MAX_INITIAL_DELAY_MILLIS = 365L * 24L * 60L * 60L * 1000L
}
