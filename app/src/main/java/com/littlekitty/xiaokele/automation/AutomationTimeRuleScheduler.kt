package com.littlekitty.xiaokele.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.littlekitty.xiaokele.core.common.protocol.NormalizedEvent
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import com.littlekitty.xiaokele.event.EventDispatchResult
import com.littlekitty.xiaokele.event.LocalBackgroundDispatcher
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.java.KoinJavaComponent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Explicit bounded catch-up contract for WorkManager-delayed TIME_POINT jobs. */
object AutomationTimePointPolicy {
    const val MAX_LATENESS_MILLIS = 30L * 60L * 1_000L

    fun state(scheduledAtEpochMillis: Long, nowEpochMillis: Long): AutomationTimePointState = when {
        scheduledAtEpochMillis <= 0L -> AutomationTimePointState.INVALID
        nowEpochMillis < scheduledAtEpochMillis -> AutomationTimePointState.EARLY
        nowEpochMillis >= scheduledAtEpochMillis + MAX_LATENESS_MILLIS -> AutomationTimePointState.MISSED
        else -> AutomationTimePointState.ELIGIBLE
    }

    fun shouldRegister(targetEpochMillis: Long, nowEpochMillis: Long): Boolean =
        state(targetEpochMillis, nowEpochMillis) in setOf(
            AutomationTimePointState.EARLY,
            AutomationTimePointState.ELIGIBLE,
        )

    fun expiresAtEpochMillis(scheduledAtEpochMillis: Long): Long =
        scheduledAtEpochMillis + MAX_LATENESS_MILLIS
}

enum class AutomationTimePointState {
    EARLY,
    ELIGIBLE,
    MISSED,
    INVALID,
}

sealed interface AutomationTimeDispatchResult {
    data class Dispatched(val result: EventDispatchResult) : AutomationTimeDispatchResult
    data object RuleUnavailable : AutomationTimeDispatchResult
    data object NotYetDue : AutomationTimeDispatchResult
    data object Missed : AutomationTimeDispatchResult
}

/** Registers user-configured time rules without requiring an exact alarm permission. */
class AutomationTimeRuleScheduler(
    private val store: AutomationRuleStore,
    private val dispatcher: LocalBackgroundDispatcher,
) {
    suspend fun dispatch(
        ruleId: String,
        scheduledAtEpochMillis: Long,
        now: Instant = Instant.now(),
    ): AutomationTimeDispatchResult {
        when (AutomationTimePointPolicy.state(scheduledAtEpochMillis, now.toEpochMilli())) {
            AutomationTimePointState.INVALID, AutomationTimePointState.MISSED -> return AutomationTimeDispatchResult.Missed
            AutomationTimePointState.EARLY -> return AutomationTimeDispatchResult.NotYetDue
            AutomationTimePointState.ELIGIBLE -> Unit
        }
        val rule = store.load().rules.firstOrNull { it.id == ruleId && it.enabled && it.trigger.eventType == AutomationEventTypes.TIME_POINT }
            ?: return AutomationTimeDispatchResult.RuleUnavailable
        val scheduled = Instant.ofEpochMilli(scheduledAtEpochMillis).atZone(ZONE)
        val event = NormalizedEvent(
            eventKey = "automation:time:${rule.id}:${scheduled.toLocalDate()}:${scheduled.toLocalTime().hour}:${scheduled.toLocalTime().minute}",
            type = AutomationEventTypes.TIME_POINT,
            source = "AUTOMATION_RULE",
            occurredAt = InstantJson.formatUtc(scheduled.toInstant()),
            receivedAt = InstantJson.formatUtc(now),
            // A delayed task must retain the deadline of its intended schedule,
            // not receive a fresh 30-minute window at app restart time.
            expiresAt = InstantJson.formatUtc(
                Instant.ofEpochMilli(AutomationTimePointPolicy.expiresAtEpochMillis(scheduledAtEpochMillis)),
            ),
            priority = rule.priority.coerceIn(0, 100),
            payload = buildJsonObject {
                put("automation_rule_id", rule.id)
                put("scheduled_local_date", scheduled.toLocalDate().toString())
                put("scheduled_local_hour", scheduled.hour)
                put("scheduled_local_minute", scheduled.minute)
                put("scheduled_local_day_of_week", scheduled.dayOfWeek.value)
                put("scheduled_local_time", scheduled.toLocalTime().format(LOCAL_TIME_FORMAT))
            },
        )
        return AutomationTimeDispatchResult.Dispatched(dispatcher.dispatch(event))
    }

    companion object {
        private val ZONE = ZoneId.of("Asia/Shanghai")
        internal val LOCAL_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        const val UNIQUE_MAINTENANCE_WORK = "automation_time_rule_maintenance_v1"

        fun register(context: Context, now: Instant = Instant.now()) {
            val store = AutomationRuleStore(context)
            val manager = WorkManager.getInstance(context)
            val today = now.atZone(ZONE).toLocalDate()
            store.load().rules
                .filter { it.enabled && it.trigger.eventType == AutomationEventTypes.TIME_POINT }
                .forEach { rule ->
                    (0..1).forEach dateOffset@{ offset ->
                        val date = today.plusDays(offset.toLong())
                        val target = targetFor(rule, date) ?: return@dateOffset
                        if (!AutomationTimePointPolicy.shouldRegister(target.toInstant().toEpochMilli(), now.toEpochMilli())) {
                            return@dateOffset
                        }
                        val request = OneTimeWorkRequestBuilder<AutomationTimeRuleWorker>()
                            .setInitialDelay((target.toInstant().toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                            .setInputData(workDataOf(
                                AutomationTimeRuleWorker.KEY_RULE_ID to rule.id,
                                AutomationTimeRuleWorker.KEY_SCHEDULED_AT to target.toInstant().toEpochMilli(),
                            ))
                            .build()
                        manager.enqueueUniqueWork(
                            "automation_time_${rule.id}_${date}",
                            ExistingWorkPolicy.KEEP,
                            request,
                        )
                    }
                }
            manager.enqueueUniquePeriodicWork(
                UNIQUE_MAINTENANCE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AutomationTimeRuleMaintenanceWorker>(12, TimeUnit.HOURS)
                    .setInitialDelay(12, TimeUnit.HOURS)
                    .build(),
            )
        }

        private fun targetFor(rule: AutomationRule, date: LocalDate): ZonedDateTime? {
            val localTime = rule.trigger.conditions
                .firstOrNull { it.field == "local_time" && it.operator == "equals" }
                ?.value?.jsonPrimitive?.contentOrNull
                ?.let { value -> runCatching { LocalTime.parse(value, LOCAL_TIME_FORMAT) }.getOrNull() }
            val hour = conditionNumber(rule, "local_hour")
            val minute = conditionNumber(rule, "local_minute")
                ?: localTime?.minute
            val resolvedHour = hour
                ?: localTime?.hour
            if (resolvedHour == null || minute == null || resolvedHour !in 0..23 || minute !in 0..59) return null
            val day = conditionNumber(rule, "local_day_of_week")
            if (day != null && day != date.dayOfWeek.value) return null
            return ZonedDateTime.of(date, LocalTime.of(resolvedHour, minute), ZONE)
        }

        private fun conditionNumber(rule: AutomationRule, field: String): Int? =
            rule.trigger.conditions.firstOrNull { it.field == field && it.operator == "equals" }
                ?.value?.let { value -> (value as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
    }
}

class AutomationTimeRuleWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val scheduler = KoinJavaComponent.getOrNull<AutomationTimeRuleScheduler>(AutomationTimeRuleScheduler::class.java)
            ?: return Result.failure()
        val ruleId = inputData.getString(KEY_RULE_ID) ?: return Result.failure()
        val scheduledAt = inputData.getLong(KEY_SCHEDULED_AT, 0L)
        if (scheduledAt <= 0L) return Result.failure()
        val timeProvider = KoinJavaComponent.getOrNull<TimeProvider>(TimeProvider::class.java)
            ?: return Result.retry()
        val diagnostics = KoinJavaComponent.getOrNull<DiagnosticsLogger>(DiagnosticsLogger::class.java)
        diagnostics?.taskStarted("automation_time_rules", ruleId)
        return try {
            when (scheduler.dispatch(ruleId, scheduledAt, timeProvider.now())) {
                is AutomationTimeDispatchResult.Dispatched -> {
                    AutomationTimeRuleScheduler.register(applicationContext)
                    Result.success().also {
                        diagnostics?.taskFinished("automation_time_rules", DiagnosticLogStatuses.SUCCESS, "rule=$ruleId")
                    }
                }

                AutomationTimeDispatchResult.RuleUnavailable -> {
                    AutomationTimeRuleScheduler.register(applicationContext)
                    Result.success().also {
                        diagnostics?.taskFinished("automation_time_rules", DiagnosticLogStatuses.SKIPPED, "rule_unavailable:$ruleId")
                    }
                }

                AutomationTimeDispatchResult.Missed -> {
                    AutomationTimeRuleScheduler.register(applicationContext)
                    Result.success().also {
                        diagnostics?.taskFinished("automation_time_rules", DiagnosticLogStatuses.MISSED, "late_time_point:$ruleId")
                    }
                }

                AutomationTimeDispatchResult.NotYetDue -> Result.retry().also {
                    diagnostics?.taskFinished("automation_time_rules", DiagnosticLogStatuses.RETRY, "before_time_point:$ruleId")
                }
            }
        } catch (error: Throwable) {
            diagnostics?.taskFinished("automation_time_rules", DiagnosticLogStatuses.FAILED, error.message, ruleId)
            Result.retry()
        }
    }

    companion object {
        const val KEY_RULE_ID = "automation_rule_id"
        const val KEY_SCHEDULED_AT = "automation_scheduled_at"
    }
}

class AutomationTimeRuleMaintenanceWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val diagnostics = KoinJavaComponent.getOrNull<DiagnosticsLogger>(DiagnosticsLogger::class.java)
        diagnostics?.taskStarted("automation_time_rules", AutomationTimeRuleScheduler.UNIQUE_MAINTENANCE_WORK)
        return try {
            AutomationTimeRuleScheduler.register(applicationContext)
            Result.success().also {
                diagnostics?.taskFinished("automation_time_rules", DiagnosticLogStatuses.SUCCESS, "maintenance")
            }
        } catch (error: Throwable) {
            diagnostics?.taskFinished(
                "automation_time_rules",
                DiagnosticLogStatuses.FAILED,
                error.message,
                AutomationTimeRuleScheduler.UNIQUE_MAINTENANCE_WORK,
            )
            Result.retry()
        }
    }
}
