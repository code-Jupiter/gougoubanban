package com.littlekitty.xiaokele.external

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.littlekitty.xiaokele.floating.FloatingPetService
import com.littlekitty.xiaokele.rhythm.RhythmUnlockRecorder
import org.koin.java.KoinJavaComponent
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger

/** Resolves unlock-time weather/location scenes, then shows one pending bubble. */
class UnlockSceneWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val coordinator = KoinJavaComponent.getOrNull<ExternalDataCoordinator>(ExternalDataCoordinator::class.java)
            ?: return Result.failure()
        val diagnostics = KoinJavaComponent.getOrNull<DiagnosticsLogger>(DiagnosticsLogger::class.java)
        diagnostics?.taskStarted("screen_unlock", UNIQUE_WORK)
        return try {
            // Recorded before anything else so the wake estimator sees this
            // unlock even if later stages fail.
            runCatching {
                RhythmUnlockRecorder.record(applicationContext)
            }
            coordinator.onScreenUnlock()
            val unreadCount = KoinJavaComponent.getOrNull<com.littlekitty.xiaokele.core.database.AppDatabase>(
                com.littlekitty.xiaokele.core.database.AppDatabase::class.java,
            )?.messageDao()?.countUnreadProactive() ?: 0
            FloatingPetService.showPending(applicationContext, unreadCount = unreadCount)
            // Schedule changes and fresh rhythm observations reshape the
            // same-day wake plan; register() is idempotent.
            runCatching {
                com.littlekitty.xiaokele.rhythm.WakeTopicScheduler.register(applicationContext)
            }
            Result.success()
                .also { diagnostics?.taskFinished("screen_unlock", DiagnosticLogStatuses.SUCCESS) }
        } catch (error: Throwable) {
            diagnostics?.taskFinished("screen_unlock", DiagnosticLogStatuses.FAILED, error.message)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK = "screen_unlock_scene_v1"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<UnlockSceneWorker>().build(),
            )
        }
    }
}
