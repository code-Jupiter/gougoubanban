package com.littlekitty.xiaokele.external

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.network.external.WeatherQueryProfile
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import kotlinx.coroutines.CancellationException
import org.koin.java.KoinJavaComponent
import java.util.concurrent.TimeUnit

/** Refreshes cached weather; the existing scene gate decides whether to speak. */
class WeatherRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val coordinator = KoinJavaComponent.getOrNull<ExternalDataCoordinator>(ExternalDataCoordinator::class.java)
            ?: return Result.failure()
        val diagnostics = KoinJavaComponent.getOrNull<DiagnosticsLogger>(DiagnosticsLogger::class.java)
        diagnostics?.taskStarted("weather_refresh", UNIQUE_PERIODIC_WORK)
        return try {
            val report = coordinator.refreshWeather(force = false, queryProfile = WeatherQueryProfile.CORE)
            val failed = report.outcome == ExternalSyncOutcomes.FAILED
            val retry = failed && report.error?.code in RETRYABLE_CODES
            diagnostics?.taskFinished(
                "weather_refresh",
                when {
                    retry -> DiagnosticLogStatuses.RETRY
                    failed -> DiagnosticLogStatuses.FAILED
                    else -> DiagnosticLogStatuses.SUCCESS
                },
                weatherDiagnosticSummary(report),
            )
            if (retry) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            diagnostics?.taskFinished(
                "weather_refresh",
                DiagnosticLogStatuses.FAILED,
                "EXCEPTION type=${error::class.simpleName ?: "unknown"}",
            )
            Result.retry()
        }
    }

    private fun weatherDiagnosticSummary(report: WeatherRefreshReport): String = buildString {
        append(report.outcome)
        report.error?.let { error ->
            append(" error_code=").append(error.code.name)
            safeTechnicalDetail(error.technicalDetail)?.let { detail ->
                append(" detail=").append(detail)
            }
        }
        if (report.cacheWritten) append(" cache_written=true")
        if (report.skippedStaleCache) append(" skipped_stale_cache=true")
        if (report.skippedFailureCooldown) append(" skipped_failure_cooldown=true")
    }

    /** Only allow known provider classifications; never copy provider text into diagnostics. */
    private fun safeTechnicalDetail(detail: String?): String? {
        val value = detail?.trim().orEmpty()
        if (value.isBlank()) return null
        return when {
            value in SAFE_WEATHER_DETAILS -> value
            value.matches(Regex("qweather_http_[0-9]{3}")) -> value
            else -> null
        }
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK = "weather_refresh_periodic_v1"
        const val UNIQUE_FOREGROUND_WORK = "weather_refresh_foreground_v1"
        const val PERIODIC_INTERVAL_HOURS = 1L

        private val RETRYABLE_CODES = setOf(
            ErrorCode.TIMEOUT,
            ErrorCode.NETWORK_UNAVAILABLE,
            ErrorCode.WEATHER_FAILURE,
        )

        private val SAFE_WEATHER_DETAILS = setOf(
            "qweather_config_missing",
            "qweather_config_incomplete",
            "qweather_coordinates_missing",
            "qweather_hourly_unavailable",
            "qweather_json_invalid",
            "weather_location_key_blank",
            "weather_condition_blank",
            "weather_observed_at_invalid",
            "weather_expires_at_invalid",
            "weather_expires_before_observed",
        )

        private fun connectedConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueuePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WeatherRefreshWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(connectedConstraints())
                    .build(),
            )
        }

        fun enqueueForegroundCatchUp(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_FOREGROUND_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WeatherRefreshWorker>()
                    .setConstraints(connectedConstraints())
                    .build(),
            )
        }
    }
}
