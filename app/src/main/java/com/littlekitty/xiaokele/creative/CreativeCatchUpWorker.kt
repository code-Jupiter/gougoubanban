package com.littlekitty.xiaokele.creative

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.littlekitty.xiaokele.core.database.state.CreativeTaskStatuses
import kotlinx.coroutines.CancellationException
import org.koin.java.KoinJavaComponent
import java.util.concurrent.TimeUnit

/**
 * OS-opportunity runner for creative catch-up. The database coordinator owns
 * idempotency, stale-task recovery and the one-model-call decision; this
 * worker only provides periodic and foreground wake-ups.
 */
class CreativeCatchUpWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val coordinator = KoinJavaComponent.getOrNull<CreativeCatchUpCoordinator>(CreativeCatchUpCoordinator::class.java)
            ?: return Result.failure()
        val report = try {
            coordinator.catchUp()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return Result.retry()
        }
        return when (report.status) {
            CreativeTaskStatuses.RETRYABLE -> Result.retry()
            CreativeTaskStatuses.FAILED -> Result.failure()
            else -> Result.success()
        }
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK = "creative_catch_up_periodic_v1"
        const val UNIQUE_FOREGROUND_WORK = "creative_catch_up_foreground_v1"
        const val PERIODIC_INTERVAL_HOURS = 24L

        fun enqueuePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CreativeCatchUpWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(Constraints.NONE)
                    .build(),
            )
        }

        fun enqueueForegroundCatchUp(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_FOREGROUND_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CreativeCatchUpWorker>()
                    .setConstraints(Constraints.NONE)
                    .build(),
            )
        }
    }
}
