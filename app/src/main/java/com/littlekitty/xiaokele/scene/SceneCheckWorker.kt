package com.littlekitty.xiaokele.scene

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.koin.java.KoinJavaComponent
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Local scene check worker: startup/foreground/after-sync scans share the
 * deterministic SceneCheckScheduler. This worker performs no network I/O and
 * does not register any weather periodic task.
 */
class SceneCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val scheduler = KoinJavaComponent.getOrNull<SceneCheckScheduler>(SceneCheckScheduler::class.java)
            ?: return Result.failure()
        val diagnostics = KoinJavaComponent.getOrNull<DiagnosticsLogger>(DiagnosticsLogger::class.java)
        diagnostics?.taskStarted("scene_check", UNIQUE_PERIODIC_WORK)
        return try {
            val report = scheduler.check()
            diagnostics?.taskFinished("scene_check", DiagnosticLogStatuses.SUCCESS, "processed=${report.backlogProcessed}")
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            diagnostics?.taskFinished("scene_check", DiagnosticLogStatuses.FAILED, error.message)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK = "scene_check_periodic_v1"
        const val UNIQUE_FOREGROUND_WORK = "scene_check_foreground_v1"
        const val PERIODIC_INTERVAL_HOURS = 1L

        fun enqueuePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SceneCheckWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(Constraints.NONE)
                    .build(),
            )
        }

        fun enqueueForegroundCatchUp(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_FOREGROUND_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SceneCheckWorker>()
                    .setConstraints(Constraints.NONE)
                    .build(),
            )
        }
    }
}
