package com.littlekitty.xiaokele.debug

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.littlekitty.xiaokele.automation.AutomationTimeRuleMaintenanceWorker
import com.littlekitty.xiaokele.automation.AutomationTimeRuleScheduler
import com.littlekitty.xiaokele.channel.OutboxRetryWorker
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogEntity
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.discovery.DiscoverySyncWorker
import com.littlekitty.xiaokele.event.TriggerWakeWorker
import com.littlekitty.xiaokele.external.UnlockSceneWorker
import com.littlekitty.xiaokele.external.WeatherRefreshWorker
import com.littlekitty.xiaokele.scene.SceneCheckWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class DebugTaskSpec(
    val id: String,
    val label: String,
    val schedule: String,
    val uniqueWorkNames: (LocalDate) -> List<String>,
)

data class DebugTaskStatus(
    val spec: DebugTaskSpec,
    val registration: String,
    val workState: String,
    val lastRun: String,
    val lastResult: String,
    val lastSummary: String?,
)

/**
 * Single source of truth for the Debug task list. It deliberately exposes
 * registration, current WorkManager state, and observed result separately.
 */
class BackgroundTaskInspector(
    context: Context,
    private val db: AppDatabase,
) {
    private val applicationContext = context.applicationContext
    private val workManager = WorkManager.getInstance(applicationContext)

    suspend fun inspect(nowEpochMillis: Long = System.currentTimeMillis()): List<DebugTaskStatus> =
        withContext(Dispatchers.IO) {
            val today = Instant.ofEpochMilli(nowEpochMillis)
                .atZone(java.time.ZoneId.of("Asia/Shanghai"))
                .toLocalDate()
            val logs = db.diagnosticLogDao().allAscending()
            taskSpecs().map { spec ->
                val names = spec.uniqueWorkNames(today)
                val infos = names.flatMap(::workInfos)
                val taskLogs = logs.filter { it.category == "TASK" && it.name == spec.id }
                val last = taskLogs.maxByOrNull { it.recordedAt }
                DebugTaskStatus(
                    spec = spec,
                    registration = if (infos.isEmpty()) "未注册/未观察" else "已注册",
                    workState = summarizeWorkState(infos),
                    lastRun = last?.let(::formatTime) ?: "尚未记录",
                    lastResult = last?.let { it.status.toDisplayStatus() } ?: "尚未记录",
                    lastSummary = last?.summary,
                )
            }
        }

    suspend fun runNow(taskId: String): String = withContext(Dispatchers.IO) {
        val suffix = UUID.randomUUID().toString().take(8)
        when (taskId) {
            TASK_WEATHER -> enqueue("debug_task_weather_$suffix", OneTimeWorkRequestBuilder<WeatherRefreshWorker>().build())
            TASK_SCENE -> enqueue("debug_task_scene_$suffix", OneTimeWorkRequestBuilder<SceneCheckWorker>().build())
            TASK_DISCOVERY -> enqueue("debug_task_discovery_$suffix", OneTimeWorkRequestBuilder<DiscoverySyncWorker>().build())
            TASK_AUTOMATION -> enqueue(
                "debug_task_automation_$suffix",
                OneTimeWorkRequestBuilder<AutomationTimeRuleMaintenanceWorker>().build(),
            )
            TASK_TRIGGER -> enqueue("debug_task_trigger_$suffix", OneTimeWorkRequestBuilder<TriggerWakeWorker>().build())
            TASK_OUTBOX -> enqueue("debug_task_outbox_$suffix", OneTimeWorkRequestBuilder<OutboxRetryWorker>().build())
            TASK_UNLOCK -> enqueue("debug_task_unlock_$suffix", OneTimeWorkRequestBuilder<UnlockSceneWorker>().build())
            else -> return@withContext "未找到任务：$taskId"
        }
        "已加入 WorkManager 队列；刷新本页查看实际结果。"
    }

    private fun enqueue(name: String, request: androidx.work.OneTimeWorkRequest) {
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    private fun workInfos(name: String): List<WorkInfo> = runCatching {
        workManager.getWorkInfosForUniqueWork(name).get()
    }.getOrDefault(emptyList())

    private fun summarizeWorkState(infos: List<WorkInfo>): String {
        if (infos.isEmpty()) return "未注册"
        return when {
            infos.any { it.state == WorkInfo.State.RUNNING } -> "运行中"
            infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> "等待执行"
            infos.any { it.state == WorkInfo.State.FAILED } -> "失败"
            infos.any { it.state == WorkInfo.State.CANCELLED } -> "已取消"
            infos.any { it.state == WorkInfo.State.SUCCEEDED } -> "最近成功"
            else -> infos.first().state.name
        }
    }

    private fun String?.toDisplayStatus(): String = when (this) {
        DiagnosticLogStatuses.SUCCESS -> "成功"
        DiagnosticLogStatuses.RETRY -> "等待重试"
        DiagnosticLogStatuses.FAILED -> "失败"
        DiagnosticLogStatuses.SKIPPED -> "跳过"
        DiagnosticLogStatuses.NO_ACTION -> "无动作"
        DiagnosticLogStatuses.STARTED -> "执行中"
        null -> "尚未记录"
        else -> this
    }

    private fun formatTime(log: DiagnosticLogEntity): String =
        java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
            .withZone(java.time.ZoneId.of("Asia/Shanghai"))
            .format(Instant.ofEpochMilli(log.recordedAt))

    companion object {
        const val TASK_WEATHER = "weather_refresh"
        const val TASK_SCENE = "scene_check"
        const val TASK_DISCOVERY = "discovery_sync"
        const val TASK_AUTOMATION = "automation_time_rules"
        const val TASK_TRIGGER = "trigger_wake"
        const val TASK_OUTBOX = "outbox_retry"
        const val TASK_UNLOCK = "screen_unlock"

        fun taskSpecs(): List<DebugTaskSpec> = listOf(
            DebugTaskSpec(TASK_WEATHER, "天气刷新", "每小时") { listOf(WeatherRefreshWorker.UNIQUE_PERIODIC_WORK, WeatherRefreshWorker.UNIQUE_FOREGROUND_WORK) },
            DebugTaskSpec(TASK_SCENE, "场景扫描", "每小时") { listOf(SceneCheckWorker.UNIQUE_PERIODIC_WORK, SceneCheckWorker.UNIQUE_FOREGROUND_WORK) },
            DebugTaskSpec(TASK_DISCOVERY, "本地生活更新", "每日") { listOf(DiscoverySyncWorker.UNIQUE_PERIODIC, DiscoverySyncWorker.UNIQUE_REFRESH) },
            DebugTaskSpec(TASK_AUTOMATION, "自动化时间规则", "规则时间 + 12 小时维护") { listOf(AutomationTimeRuleScheduler.UNIQUE_MAINTENANCE_WORK) },
            DebugTaskSpec(TASK_TRIGGER, "Todo 定时触发", "按最近到期时间") { listOf(TriggerWakeWorker.UNIQUE_TRIGGER_WAKE_WORK) },
            DebugTaskSpec(TASK_OUTBOX, "投递失败重试", "按下次重试时间") { listOf(OutboxRetryWorker.UNIQUE_OUTBOX_RETRY_WORK) },
            DebugTaskSpec(TASK_UNLOCK, "解锁触发", "每次屏幕解锁") { listOf(UnlockSceneWorker.UNIQUE_WORK) },
        )
    }
}
