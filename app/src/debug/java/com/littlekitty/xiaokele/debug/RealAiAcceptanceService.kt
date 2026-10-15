package com.littlekitty.xiaokele.debug

import androidx.room.withTransaction
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.littlekitty.xiaokele.BuildConfig
import androidx.work.WorkManager
import com.littlekitty.xiaokele.chat.CharacterCoreBootstrap
import com.littlekitty.xiaokele.chat.ChatSendOutcome
import com.littlekitty.xiaokele.chat.ConversationContextManager
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.policy.DevelopmentCandidatePolicies
import com.littlekitty.xiaokele.core.database.policy.ProductScenePolicyCatalog
import com.littlekitty.xiaokele.core.database.repository.BehaviorTrainer
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogCategories
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogNames
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import com.littlekitty.xiaokele.floating.FloatingWindowSettingsStore
import com.littlekitty.xiaokele.settings.ByokSettingsStore
import com.littlekitty.xiaokele.settings.QWeatherSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Debug-only persisted state for the staged real-AI acceptance runs. */
class RealAiAcceptanceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var realChainMode: Boolean
        get() = prefs.getBoolean(KEY_REAL_CHAIN, false)
        set(value) = prefs.edit().putBoolean(KEY_REAL_CHAIN, value).apply()

    var stage: String
        get() = prefs.getString(KEY_STAGE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_STAGE, value).apply()

    var runId: String
        get() = prefs.getString(KEY_RUN_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_RUN_ID, value).apply()

    var importedInputs: String
        get() = prefs.getString(KEY_IMPORTED, "[]").orEmpty()
        set(value) = prefs.edit().putString(KEY_IMPORTED, value).apply()

    /** Full run reset (baseline): clears run id and the imported-input registry. */
    fun clearRunState() {
        prefs.edit().remove(KEY_RUN_ID).remove(KEY_IMPORTED).apply()
    }

    /** New run within the same stage: keeps run id fresh, clears imported registry. */
    fun newRunId(timestampMillis: Long): String {
        prefs.edit().remove(KEY_IMPORTED).apply()
        val runId = "run_$timestampMillis"
        prefs.edit().putString(KEY_RUN_ID, runId).apply()
        return runId
    }

    fun traceFields() = runId.takeIf { it.isNotBlank() }?.let { activeRunId ->
        buildJsonObject {
            put("acceptance_run_id", activeRunId)
            stage.takeIf { it.isNotBlank() }?.let { put("acceptance_stage", it) }
        }
    }

    companion object {
        const val PREFS_NAME = "little_kitty_real_ai_acceptance"
        const val KEY_REAL_CHAIN = "real_chain_mode"
        const val KEY_STAGE = "stage"
        const val KEY_RUN_ID = "run_id"
        const val KEY_IMPORTED = "imported_inputs"
    }
}

data class RealAiImportedInput(
    val label: String,
    val sha256: String,
    val insertedCount: Int,
    val skippedCount: Int,
    val firstCreatedAt: String,
    val lastCreatedAt: String,
    val importedAt: String,
)

data class RealAiEnvironment(
    val gitCommit: String,
    val apkSha256: String,
    val buildType: String,
    val appVersion: String,
    val deviceModel: String,
    val androidVersion: String,
    val nowText: String,
    val timezone: String,
    val minimaxHost: String,
    val minimaxRegion: String,
    val minimaxModel: String,
    val keyConfigured: Boolean,
    val keyFingerprint: String?,
    val locationFineGranted: Boolean,
    val backgroundLocationGranted: Boolean,
    val notificationGranted: Boolean,
    val overlayGranted: Boolean,
    val floatingWindowEnabled: Boolean,
    val weatherConfigured: Boolean,
    val sceneMode: String,
    val stage: String,
    val runId: String,
    val importedInputs: List<RealAiImportedInput>,
    val sourceSummary: String,
)

data class RealAiLogLine(
    val recordedAt: Long,
    val name: String,
    val status: String,
    val subjectId: String?,
    val summary: String?,
)

/**
 * Single entry point for the staged real-AI acceptance page. Real provider
 * calls still go through the production pipeline (FrontChatPipeline /
 * ConversationContextManager); this service only orchestrates inputs,
 * baseline isolation and evidence export.
 */
class RealAiAcceptanceService(
    private val context: Context,
    private val db: AppDatabase,
    private val timeProvider: TimeProvider,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val dataMaintenance: DebugDataMaintenanceService,
    private val behaviorTrainer: BehaviorTrainer,
    private val conversationContextManager: ConversationContextManager,
    private val byokSettingsStore: ByokSettingsStore,
    private val debugRealAi: DebugRealAiAcceptanceService,
) {
    private val appContext = context.applicationContext
    private val store = RealAiAcceptanceStore(appContext)

    // ---------------------------------------------------------------- env --

    suspend fun environment(): RealAiEnvironment = withContext(Dispatchers.IO) {
        val routes = byokSettingsStore.agentModelRoutes()
        val endpoint = byokSettingsStore.endpoint().ifBlank { byokSettingsStore.bailianEndpoint() }
        val minimaxHost = runCatching { URI(endpoint).host }.getOrDefault(endpoint)
        val qweather = QWeatherSettingsStore(appContext)
        val weatherHost = qweather.apiHost()
        val weatherKeyReady = qweather.hasSavedKey()
        val floating = FloatingWindowSettingsStore(appContext)
        // Debug acceptance is permanently wired to production adapters. The
        // old persisted switch is intentionally ignored for trustworthiness.
        val fakeInChain = false
        val now = timeProvider.now()
        RealAiEnvironment(
            gitCommit = BuildConfig.GIT_COMMIT,
            apkSha256 = installedApkSha256(),
            buildType = if (BuildConfig.DEBUG) "debug" else "release",
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            nowText = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault()).format(now),
            timezone = ZoneId.systemDefault().id,
            minimaxHost = minimaxHost,
            minimaxRegion = byokSettingsStore.region(),
            minimaxModel = routes.entries.joinToString("; ") { "${it.key.name}=${it.value.provider.name}/${it.value.defaultModelId}/${it.value.complexModelId}" },
            keyConfigured = routes.values.any { route ->
                if (route.provider.name == "BAILIAN") !byokSettingsStore.bailianKeyOrNull().isNullOrBlank()
                else !byokSettingsStore.loadKeyOrNull().isNullOrBlank()
            },
            keyFingerprint = listOfNotNull(byokSettingsStore.fingerprint(), byokSettingsStore.bailianFingerprint())
                .distinct().joinToString("|").ifBlank { null },
            locationFineGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            backgroundLocationGranted = if (Build.VERSION.SDK_INT >= 29) {
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                true
            },
            notificationGranted = if (Build.VERSION.SDK_INT >= 33) {
                granted(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                true
            },
            overlayGranted = Settings.canDrawOverlays(appContext),
            floatingWindowEnabled = floating.isEnabled(),
            weatherConfigured = weatherHost.isNotBlank() && weatherKeyReady,
            sceneMode = "REAL_AI_ONLY",
            stage = store.stage,
            runId = store.runId,
            importedInputs = decodeInputs(store.importedInputs),
            sourceSummary = buildString {
                append("模型路由：${routes.size} 个 Agent；前台/后台=当前配置的真实直连；")
                append(if (fakeInChain) "通知=Fake 捕获适配器（不产生真实系统通知）" else "通知=真实系统通知")
                append("；")
                append(if (fakeInChain) "记忆评估/向量=Fake（仅限实验室内使用）" else "记忆评估=当前生成 Profile；向量=已配置的 Embedding 服务")
                append("；场景触发条件可模拟，真实链路不会注入固定定位")
            },
        )
    }

    private fun installedApkSha256(): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        File(appContext.applicationInfo.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("unavailable")

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------- run id --

    fun setStage(stage: String) {
        store.stage = stage
    }

    fun newRunId(): String = store.newRunId(timeProvider.now().toEpochMilli())

    /** Kept as a source-compatible no-op; Debug acceptance is always real. */
    fun setRealChainMode(enabled: Boolean) { store.realChainMode = true }

    fun realChainMode(): Boolean = true

    suspend fun prepareScenario(scenario: DebugAcceptanceScenario): Result<DebugPreparedRequest> =
        debugRealAi.prepareScenario(scenario)

    suspend fun submitPreparedRequest(prepared: DebugPreparedRequest, editedBody: String): DebugRealAiResult =
        debugRealAi.submitPreparedRequest(prepared, editedBody)

    fun expectedModel(frontChat: Boolean): String = debugRealAi.expectedModel(frontChat)

    /** Non-destructive baseline: captures current raw chat before appending any test input. */
    suspend fun captureBaselineSnapshot(): String = withContext(Dispatchers.IO) {
        val runId = store.runId.ifBlank { store.newRunId(timeProvider.now().toEpochMilli()) }
        val snapshot = dataMaintenance.snapshot()
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault()).format(timeProvider.now())
        val directory = File(appContext.getExternalFilesDir(null), "acceptance/$runId").apply { mkdirs() }
        val payload = buildJsonObject {
            put("schema_version", "littlekitty_acceptance_baseline_v1")
            put("run_id", runId)
            put("captured_at", Instant.now().toString())
            put("message_count", snapshot.messageCount)
            put("conversation_summary_count", snapshot.conversationSummaryCount)
            put("memory_count", snapshot.memoryCount)
            put("active_memory_count", snapshot.activeMemoryCount)
            put("pending_chat_event_count", snapshot.pendingChatEventCount)
            put("messages", buildJsonArray {
                snapshot.messages.forEach { message ->
                    add(buildJsonObject {
                        put("id", message.id)
                        put("role", message.role)
                        put("content", message.content)
                        put("created_at", message.createdAt)
                        put("status", message.status)
                    })
                }
            })
        }
        val file = File(directory, "baseline_snapshot_$stamp.json")
        file.writeText(JsonCodec.json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), payload), Charsets.UTF_8)
        "已保留当前数据基线：${file.absolutePath}（${snapshot.messageCount} 条消息、${snapshot.memoryCount} 条记忆；未清空任何数据）"
    }

    // ------------------------------------------------------------ actions --

    /**
     * Isolated baseline for one acceptance stage: wipes all conversational,
     * memory, proactive and cache state while deliberately keeping the user
     * configured BYOK key, region/model and weather key.
     */
    suspend fun resetBaseline(): String = withContext(Dispatchers.IO) {
        WorkManager.getInstance(appContext).cancelAllWorkByTag(DebugSceneTestCommandHandler.DEBUG_SCENE_WORK_TAG)
        val clearReport = dataMaintenance.clearChat()
        val clearedTables = mutableListOf<String>()
        db.withTransaction {
            listOf(
                "events", "todos", "triggers", "action_plans", "action_executions",
                "delivery_history", "memories", "memory_embeddings", "user_traits",
                "behavior_tendencies", "pet_state", "external_cache", "diagnostic_logs",
                "scenario_policies",
            ).forEach { table ->
                db.openHelper.writableDatabase.execSQL("DELETE FROM $table")
                clearedTables += table
            }
        }
        listOf(
            "little_kitty_discovery_cache",
            "little_kitty_discovery_mentions",
            "little_kitty_discovery_refresh_rate",
            "little_kitty_rhythm_observations",
            "little_kitty_pending_floating_bubble",
        ).forEach { name ->
            appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        store.clearRunState()
        val now = timeProvider.now().toEpochMilli()
        CharacterCoreBootstrap.ensureSeeded(db, now)
        behaviorTrainer.ensureDefaults(now)
        ProductScenePolicyCatalog.ensureSeeded(db)
        DevelopmentCandidatePolicies.ensureSeeded(db)
        diagnosticsLogger.record(
            category = DiagnosticLogCategories.SYSTEM,
            name = "real_ai_acceptance",
            status = DiagnosticLogStatuses.SUCCESS,
            summary = "验收基线已重置（保留 Key 与设置）",
            payloadJson = buildJsonObject {
                put("cleared_tables", buildJsonArray { clearedTables.forEach { add(JsonPrimitive(it)) } })
                put("chat_report", clearReport.description)
                put("debug_scene_work_cancelled", true)
                put("policies_reseeded", true)
            }.toString(),
        )
            "基线已重置：${clearReport.description} 清空表=${clearedTables.size} 张；" +
            "已保留当前生成方案的 Key、区域/模型、排班 URL、天气 Key 与悬浮窗开关。运行 ID 已清空。"
    }

    suspend fun importStage(rawJson: String, label: String): String = withContext(Dispatchers.IO) {
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(rawJson.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val result = dataMaintenance.importChatHistory(rawJson)
        when (result) {
            is DebugChatImportResult.Success -> {
                val input = RealAiImportedInput(
                    label = label,
                    sha256 = sha256,
                    insertedCount = result.insertedCount,
                    skippedCount = result.skippedCount,
                    firstCreatedAt = result.firstCreatedAt,
                    lastCreatedAt = result.lastCreatedAt,
                    importedAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        .withZone(ZoneId.systemDefault())
                        .format(timeProvider.now()),
                )
                val updated = decodeInputs(store.importedInputs) + input
                store.importedInputs = JsonCodec.encode(updated.map { inputJson(it) })
                "${result.description} 文件 [$label] sha256=${sha256.take(12)}…（完整值见导出的快照）"
            }
            is DebugChatImportResult.Failure -> result.description
        }
    }

    suspend fun sendChatMessage(text: String): String =
        "Debug 验收不允许写入正式聊天；请在真实 AI 验收页使用“生成报文 → 提交真实模型”。"

    suspend fun runConsolidationNow(): String = withContext(Dispatchers.IO) {
        val beforeSummaries = db.conversationSummaryDao().count()
        val covered = conversationContextManager.consolidateAllNow()
        val afterSummaries = db.conversationSummaryDao().count()
        "真实归纳完成：本次覆盖 $covered 条消息，摘要 $beforeSummaries → $afterSummaries 条；" +
            "记忆提取随归纳输出一并落库（详见诊断日志 CONVERSATION_CONSOLIDATION / MEMORY_ANALYSIS）。"
    }

    suspend fun exportAcceptanceSnapshot(): String = withContext(Dispatchers.IO) {
        val runId = store.runId.ifBlank { return@withContext "请先创建 Run ID，再导出验收证据。" }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault()).format(timeProvider.now())
        val outDir = File(appContext.getExternalFilesDir(null), "acceptance/$runId/$stamp").apply { mkdirs() }

        // Export only the run-tagged, safe audit fields. Database files and raw
        // diagnostic payloads can contain the user's entire conversation or
        // location and must stay in app-private storage.
        val aiLogs = db.diagnosticLogDao().allAscending()
            .filter { it.category == DiagnosticLogCategories.AI }
            .filter { log -> log.payloadJson?.let { payloadHasRunId(it, runId) } == true }
        val trace = buildJsonObject {
            put("schema_version", "littlekitty_real_ai_trace_v2")
            put("run_id", runId)
            put("stage", store.stage)
            put("generated_at", stamp)
            put("git_commit", BuildConfig.GIT_COMMIT)
            put("imported_inputs", runCatching {
                JsonCodec.json.parseToJsonElement(store.importedInputs)
            }.getOrDefault(buildJsonArray { }))
            put("ai_logs", buildJsonArray {
                aiLogs.forEach { log ->
                    add(buildJsonObject {
                        put("recorded_at", log.recordedAt)
                        put("recorded_at_iso", Instant.ofEpochMilli(log.recordedAt).toString())
                        put("name", log.name)
                        put("status", log.status)
                        put("subject_id", log.subjectId)
                        log.summary?.let { put("summary", it) }
                        log.payloadJson?.let { payload -> put("payload", safeTracePayload(payload)) }
                    })
                }
            })
        }
        File(outDir, "acceptance_trace_$stamp.json")
            .writeText(JsonCodec.json.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(), trace,
            ), Charsets.UTF_8)

        // The acceptance recorder keeps full request/response bodies outside
        // Room.  Copy that Debug-only artifact to the timestamped export so
        // adb pull yields the exact Bailian exchanges as well as the safe
        // production diagnostic summary above.
        val fullTraceStore = DebugAcceptanceTraceStore(appContext, byokSettingsStore)
        fullTraceStore.normalizeCurrentTrace(runId)
        val fullTrace = fullTraceStore.currentTraceFile(runId)
        if (fullTrace.isFile) {
            File(outDir, "provider_route_dialogue_trace_${stamp}.json")
                .writeText(fullTrace.readText(Charsets.UTF_8), Charsets.UTF_8)
        }
        val summary = fullTraceStore.currentSummaryFile(runId)
        if (summary.isFile) {
            File(outDir, "provider_route_dialogue_trace_summary.md")
                .writeText(summary.readText(Charsets.UTF_8), Charsets.UTF_8)
        }

        "脱敏验收 trace 已导出到应用外部目录 acceptance/$runId/$stamp/；完整请求/响应正文仅存在于 Debug 文件，不进入 Room、生产日志或 Release。"
    }

    private fun payloadHasRunId(payload: String, runId: String): Boolean = runCatching {
        JsonCodec.json.parseToJsonElement(payload).jsonObject["acceptance_run_id"]
            ?.jsonPrimitive?.content == runId
    }.getOrDefault(false)

    private fun safeTracePayload(payload: String) = buildJsonObject {
        val objectPayload = runCatching { JsonCodec.json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return@buildJsonObject
        SAFE_TRACE_KEYS.forEach { key -> objectPayload[key]?.let { put(key, it) } }
    }

    suspend fun recentAiLogs(limit: Int = 24): List<RealAiLogLine> = withContext(Dispatchers.IO) {
        db.diagnosticLogDao().allAscending()
            .filter { it.category == DiagnosticLogCategories.AI }
            .filter { it.name in TRACKED_AI_LOG_NAMES }
            .takeLast(limit)
            .map { RealAiLogLine(it.recordedAt, it.name, it.status, it.subjectId, it.summary) }
            .reversed()
    }

    private fun inputJson(input: RealAiImportedInput) = buildJsonObject {
        put("label", input.label)
        put("sha256", input.sha256)
        put("inserted_count", input.insertedCount)
        put("skipped_count", input.skippedCount)
        put("first_created_at", input.firstCreatedAt)
        put("last_created_at", input.lastCreatedAt)
        put("imported_at", input.importedAt)
    }

    private fun decodeInputs(raw: String): List<RealAiImportedInput> = runCatching {
        JsonCodec.json.parseToJsonElement(raw).jsonArray.map { element ->
            val obj = element.jsonObject
            RealAiImportedInput(
                label = (obj["label"] as? JsonPrimitive)?.content.orEmpty(),
                sha256 = (obj["sha256"] as? JsonPrimitive)?.content.orEmpty(),
                insertedCount = (obj["inserted_count"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                skippedCount = (obj["skipped_count"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                firstCreatedAt = (obj["first_created_at"] as? JsonPrimitive)?.content.orEmpty(),
                lastCreatedAt = (obj["last_created_at"] as? JsonPrimitive)?.content.orEmpty(),
                importedAt = (obj["imported_at"] as? JsonPrimitive)?.content.orEmpty(),
            )
        }
    }.getOrDefault(emptyList())

    private fun safeUserMessageOrCode(error: com.littlekitty.xiaokele.core.common.error.AppError): String =
        error.userMessage ?: error.code.name

    companion object {
        private val SAFE_TRACE_KEYS = setOf(
            "marker", "operation", "correlation_id", "service_name", "request_kind", "attempt",
            "request_sha256", "request_length", "response_sha256", "response_length",
            "response_status_code", "latency_millis", "provider_response_facts", "error_code",
            "tool_call_id", "tool_name", "allowed", "succeeded", "arguments_sha256",
            "arguments_length", "result_sha256", "result_length", "duration_millis",
            "acceptance_run_id", "acceptance_stage",
        )
        private val TRACKED_AI_LOG_NAMES = setOf(
            DiagnosticLogNames.AI_REQUEST,
            DiagnosticLogNames.AI_RESPONSE,
            DiagnosticLogNames.TOOL_EXECUTION,
            DiagnosticLogNames.CONVERSATION_CONSOLIDATION,
            DiagnosticLogNames.MEMORY_ANALYSIS,
            DiagnosticLogNames.PROFILE_SYNTHESIS,
            DiagnosticLogNames.DECISION_VALIDATION,
            DiagnosticLogNames.PLAN_PERSISTED,
        )
    }
}
