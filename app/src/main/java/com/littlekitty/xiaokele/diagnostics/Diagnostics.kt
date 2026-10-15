package com.littlekitty.xiaokele.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.littlekitty.xiaokele.BuildConfig
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.ActionExecutionEntity
import com.littlekitty.xiaokele.core.database.entity.ActionPlanEntity
import com.littlekitty.xiaokele.core.database.entity.BehaviorTendencyEntity
import com.littlekitty.xiaokele.core.database.entity.CharacterCoreEntity
import com.littlekitty.xiaokele.core.database.entity.ConversationSummaryEntity
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogCategories
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogEntity
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogNames
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.ExternalCacheEntity
import com.littlekitty.xiaokele.core.database.entity.MemoryEntity
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.entity.PetStateEntity
import com.littlekitty.xiaokele.core.database.entity.ScenarioPolicyEntity
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.entity.TriggerEntity
import com.littlekitty.xiaokele.core.database.entity.UserTraitEntity
import com.littlekitty.xiaokele.core.network.ServiceCallTrace
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DiagnosticTaskSpec(
    val id: String,
    val label: String,
    val intervalMillis: Long,
    val graceMillis: Long = intervalMillis * 2,
)

/**
 * A model exchange captured only by a Debug acceptance sink.
 *
 * The production diagnostics database deliberately stores hashes and safe
 * provider facts instead of user text.  Debug builds may opt in to this
 * record so the acceptance page can export the exact request/response body
 * that the user reviewed.  The sink is null in release wiring.
 */
data class AcceptanceModelCall(
    val runId: String,
    val turnId: String,
    val scenarioId: String,
    val userInput: String,
    val agent: String,
    val reviewKind: String? = null,
    val provider: String,
    val modelId: String,
    val promptVersion: String,
    val requestStartedAt: Long,
    val requestFinishedAt: Long,
    val durationMillis: Long,
    val requestBody: String,
    val responseBody: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val status: String,
    val evidenceIds: List<String> = emptyList(),
    val sideEffects: List<String> = emptyList(),
    val error: String? = null,
)

fun interface AcceptanceTraceRecorder {
    suspend fun record(call: AcceptanceModelCall)
}

/** The expected cadence used to detect a likely OS/background miss in exports. */
object DiagnosticTaskCatalog {
    val specs = listOf(
        DiagnosticTaskSpec("weather_refresh", "天气刷新", 60L * 60 * 1000),
        DiagnosticTaskSpec("location_window", "下班定位窗口", 30L * 60 * 1000),
        DiagnosticTaskSpec("scene_check", "场景扫描", 60L * 60 * 1000),
        DiagnosticTaskSpec("discovery_sync", "本地生活同步", 24L * 60 * 60 * 1000),
        DiagnosticTaskSpec("automation_time_rules", "自动化时间规则", 12L * 60 * 60 * 1000),
        DiagnosticTaskSpec("trigger_wake", "Todo 定时触发", 60L * 60 * 1000),
        DiagnosticTaskSpec("outbox_retry", "投递重试", 60L * 60 * 1000),
    )
}

/** Best-effort logger: diagnostics must never break chat or background work. */
class DiagnosticsLogger(
    private val db: AppDatabase,
    private val timeProvider: TimeProvider,
    private val traceContextProvider: (() -> JsonObject?)? = null,
    private val acceptanceTraceRecorder: AcceptanceTraceRecorder? = null,
) {
    suspend fun record(
        category: String,
        name: String,
        status: String,
        subjectId: String? = null,
        summary: String? = null,
        payloadJson: String? = null,
        recordedAt: Long = timeProvider.now().toEpochMilli(),
    ) {
        runCatching {
            db.diagnosticLogDao().insert(
                DiagnosticLogEntity(
                    id = EntityId.generate("diag").value,
                    recordedAt = recordedAt,
                    category = category,
                    name = name,
                    status = status,
                    subjectId = subjectId,
                    summary = summary?.take(MAX_SUMMARY_LENGTH),
                    payloadJson = enrichPayload(payloadJson)?.take(MAX_PAYLOAD_LENGTH),
                ),
            )
        }
    }

    suspend fun taskStarted(taskId: String, subjectId: String? = null) = record(
        category = DiagnosticLogCategories.TASK,
        name = taskId,
        status = DiagnosticLogStatuses.STARTED,
        subjectId = subjectId,
    )

    suspend fun taskFinished(
        taskId: String,
        status: String,
        summary: String? = null,
        subjectId: String? = null,
    ) = record(
        category = DiagnosticLogCategories.TASK,
        name = taskId,
        status = status,
        subjectId = subjectId,
        summary = summary,
    )

    suspend fun aiRequestStarted(
        operation: String,
        correlationId: String,
        subjectId: String?,
        serviceName: String,
        requestBody: String,
        requestKind: String = "wire_or_context",
        attempt: Int? = null,
    ) = record(
        category = DiagnosticLogCategories.AI,
        name = DiagnosticLogNames.AI_REQUEST,
        status = DiagnosticLogStatuses.STARTED,
        subjectId = subjectId,
        summary = "$operation 请求已发起 service=$serviceName",
        payloadJson = buildJsonObject {
            put("marker", DiagnosticLogNames.AI_REQUEST)
            put("operation", operation)
            put("correlation_id", correlationId)
            put("service_name", serviceName)
            put("request_kind", requestKind)
            attempt?.let { put("attempt", it) }
            put("request_sha256", sha256(requestBody))
            put("request_length", requestBody.length)
        }.toString(),
    )

    suspend fun aiResponseRecorded(
        operation: String,
        correlationId: String,
        subjectId: String?,
        status: String,
        trace: ServiceCallTrace?,
        fallbackRequestBody: String,
        normalizedOutput: String? = null,
        errorCode: String? = null,
        technicalDetail: String? = null,
        attempt: Int? = null,
    ) = record(
        category = DiagnosticLogCategories.AI,
        name = DiagnosticLogNames.AI_RESPONSE,
        status = status,
        subjectId = subjectId,
        summary = "$operation 响应 $status service=${trace?.serviceName ?: "unknown"}",
        payloadJson = buildJsonObject {
            put("marker", DiagnosticLogNames.AI_RESPONSE)
            put("operation", operation)
            put("correlation_id", correlationId)
            put("service_name", trace?.serviceName ?: "unknown")
            trace?.provider?.let { put("provider", it) }
            trace?.modelId?.let { put("model_id", it) }
            trace?.task?.let { put("task", it) }
            trace?.promptVersion?.let { put("prompt_version", it) }
            trace?.routeVersion?.let { put("route_version", it) }
            trace?.inputTokens?.let { put("input_tokens", it) }
            trace?.outputTokens?.let { put("output_tokens", it) }
            trace?.totalTokens?.let { put("total_tokens", it) }
            put("cost_status", trace?.costStatus ?: "UNKNOWN")
            put("application_status", status)
            val requestBody = trace?.requestBody ?: fallbackRequestBody
            put("request_sha256", sha256(requestBody))
            put("request_length", requestBody.length)
            trace?.rawResponseBody?.let {
                put("response_sha256", sha256(it))
                put("response_length", it.length)
            }
            trace?.responseStatusCode?.let { put("response_status_code", it) }
            trace?.latencyMillis?.let { put("latency_millis", it) }
            trace?.rawResponseBody?.let { providerResponseFacts(it)?.let { facts -> put("provider_response_facts", facts) } }
            normalizedOutput?.let {
                put("normalized_output_sha256", sha256(it))
                put("normalized_output_length", it.length)
            }
            errorCode?.let { put("error_code", it) }
            technicalDetail?.let { put("technical_detail", it) }
            attempt?.let { put("attempt", it) }
        }.toString(),
    )

    /**
     * Forward a full exchange to the Debug-only acceptance sink.  Nothing is
     * written to Room here, so release builds retain the existing privacy
     * boundary even when normal AI diagnostics are enabled.
     */
    suspend fun acceptanceModelCallRecorded(call: AcceptanceModelCall) {
        runCatching { acceptanceTraceRecorder?.record(call) }
    }

    /**
     * Keeps tool-loop evidence linkable without persisting coordinates, tool
     * payloads, or user-derived provider data in the diagnostic log.
     */
    suspend fun toolExecutionRecorded(
        operation: String,
        correlationId: String,
        attempt: Int,
        toolCallId: String,
        toolName: String,
        allowed: Boolean,
        succeeded: Boolean,
        arguments: JsonObject,
        result: JsonObject,
        durationMillis: Long,
    ) = record(
        category = DiagnosticLogCategories.AI,
        name = DiagnosticLogNames.TOOL_EXECUTION,
        status = if (succeeded) DiagnosticLogStatuses.SUCCESS else DiagnosticLogStatuses.FAILED,
        subjectId = correlationId,
        summary = "$operation 工具 $toolName 执行完成",
        payloadJson = buildJsonObject {
            put("marker", DiagnosticLogNames.TOOL_EXECUTION)
            put("operation", operation)
            put("correlation_id", correlationId)
            put("attempt", attempt)
            put("tool_call_id", toolCallId)
            put("tool_name", toolName)
            put("allowed", allowed)
            put("succeeded", succeeded)
            put("arguments_sha256", sha256(arguments.toString()))
            put("arguments_length", arguments.toString().length)
            put("result_sha256", sha256(result.toString()))
            put("result_length", result.toString().length)
            safeToolFacts(result)?.let { put("safe_tool_facts", it) }
            put("duration_millis", durationMillis)
        }.toString(),
    )

    /** Keeps operational tool facts visible without exporting coordinates or provider payloads. */
    private fun safeToolFacts(result: JsonObject): JsonObject? {
        val payload = (result["result"] as? JsonObject) ?: result
        val toolName = (result["tool_name"] as? JsonPrimitive)?.contentOrNull
        val allowedKeys = if (toolName == "query_weather" || toolName == "query_location") {
            setOf("ok", "error_code", "source", "location_refresh_outcome")
        } else {
            setOf("ok", "error_code")
        }
        val facts = buildJsonObject {
            payload.forEach { (key, value) ->
                if (key in allowedKeys) put(key, value)
            }
        }
        return facts.takeIf { it.isNotEmpty() }
    }

    private fun providerResponseFacts(rawResponse: String): JsonObject? = runCatching {
        val root = JsonCodec.json.parseToJsonElement(rawResponse).jsonObject
        val baseResponse = root["base_resp"]?.jsonObject
        val firstMessage = root["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject
        val reasoning = firstMessage?.get("reasoning_content")
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
        buildJsonObject {
            baseResponse?.get("status_code")?.let { it as? JsonPrimitive }?.contentOrNull
                ?.toIntOrNull()?.let { put("provider_status_code", it) }
            put("choices_count", root["choices"]?.jsonArray?.size ?: 0)
            put("reasoning_content_present", !reasoning.isNullOrBlank())
            put("reasoning_content_length", reasoning?.length ?: 0)
            put("reasoning_details_present", firstMessage?.containsKey("reasoning_details") == true)
        }
    }.getOrNull()

    private fun enrichPayload(payloadJson: String?): String? {
        val context = runCatching { traceContextProvider?.invoke() }.getOrNull() ?: return payloadJson
        if (context.isEmpty()) return payloadJson
        return buildJsonObject {
            payloadJson?.let { raw ->
                val objectPayload = runCatching { JsonCodec.json.parseToJsonElement(raw).jsonObject }.getOrNull()
                if (objectPayload != null) {
                    objectPayload.forEach { (key, value) -> put(key, value) }
                } else {
                    put("payload_sha256", sha256(raw))
                    put("payload_length", raw.length)
                }
            }
            context.forEach { (key, value) -> put(key, value) }
        }.toString()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    suspend fun recordAiResult(
        operation: String,
        correlationId: String,
        subjectId: String?,
        context: ContextPack,
        result: ModelServiceResult,
        attempt: Int? = null,
    ) {
        val fallbackRequest = JsonCodec.encode(context)
        aiResponseRecorded(
            operation = operation,
            correlationId = correlationId,
            subjectId = subjectId,
            status = when (result) {
                is ModelServiceResult.Success -> DiagnosticLogStatuses.SUCCESS
                is ModelServiceResult.NoAction -> DiagnosticLogStatuses.NO_ACTION
                is ModelServiceResult.Failure -> DiagnosticLogStatuses.FAILED
            },
            trace = when (result) {
                is ModelServiceResult.Success -> result.trace
                is ModelServiceResult.NoAction -> result.trace
                is ModelServiceResult.Failure -> result.trace
            },
            fallbackRequestBody = fallbackRequest,
            normalizedOutput = (result as? ModelServiceResult.Success)?.rawDecisionJson,
            errorCode = (result as? ModelServiceResult.Failure)?.error?.code?.name,
            technicalDetail = (result as? ModelServiceResult.Failure)?.error?.technicalDetail,
            attempt = attempt,
        )
    }

    companion object {
        private const val MAX_SUMMARY_LENGTH = 500
        /** Keeps full normal model exchanges while bounding pathological provider bodies. */
        private const val MAX_PAYLOAD_LENGTH = 500_000
    }
}

class DiagnosticsExportService(
    private val db: AppDatabase,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val timeProvider: TimeProvider,
) {

    suspend fun export(
        context: Context,
        sensitiveWordInput: String = "",
    ): android.net.Uri = withContext(Dispatchers.IO) {
        diagnosticsLogger.record(
            category = DiagnosticLogCategories.SYSTEM,
            name = "diagnostics_export",
            status = DiagnosticLogStatuses.STARTED,
            summary = "用户请求导出诊断包",
        )
        val now = timeProvider.now().toEpochMilli()
        val logs = db.diagnosticLogDao().allAscending()
        val root = buildJsonObject {
            put("schema_version", "littlekitty_diagnostics_v2")
            put("generated_at_epoch_millis", now)
            put("generated_at", Instant.ofEpochMilli(now).toString())
            put("app_version", BuildConfig.VERSION_NAME)
            put("app_build_type", if (BuildConfig.DEBUG) "debug" else "release")
            put("timezone", ZoneId.systemDefault().id)
            put(
                "privacy_note",
                "包含聊天、AI 原始请求/响应、AI 决策、场景阶段、任务状态、投递结果、记忆和天气/位置缓存；不包含 AI Key、天气 API Key 或排班认证 Token。",
            )
            put(
                "trace_contract",
                buildJsonObject {
                    put("correlation_id_field", "diagnostic_logs.payload.correlation_id")
                    put(
                        "markers",
                        JsonArray(
                            listOf(
                                DiagnosticLogNames.EVENT_INGESTED,
                                DiagnosticLogNames.SCENE_GATE,
                                DiagnosticLogNames.POLICY_GATE,
                                DiagnosticLogNames.CONTEXT_BUILT,
                                DiagnosticLogNames.AI_REQUEST,
                                DiagnosticLogNames.AI_RESPONSE,
                                DiagnosticLogNames.TOOL_EXECUTION,
                                DiagnosticLogNames.DECISION_VALIDATION,
                                DiagnosticLogNames.PLAN_PERSISTED,
                                DiagnosticLogNames.CHANNEL_EXECUTION,
                                DiagnosticLogNames.MEMORY_ANALYSIS,
                                DiagnosticLogNames.CONVERSATION_CONSOLIDATION,
                                DiagnosticLogNames.PROFILE_SYNTHESIS,
                            ).map { JsonPrimitive(it) },
                        ),
                    )
                    put(
                        "ai_audit_fields",
                        "payload.request_sha256, payload.response_sha256, payload.normalized_output_sha256",
                    )
                },
            )
            put("task_health", JsonArray(DiagnosticTaskCatalog.specs.map { taskHealth(it, logs, now) }))
            put("diagnostic_logs", JsonArray(logs.map { it.toJson() }))
            put("messages", JsonArray(db.messageDao().allAscending().map { it.toJson() }))
            put("conversation_summaries", JsonArray(db.conversationSummaryDao().allAscending().map { it.toJson() }))
            put("events", JsonArray(db.eventDao().allAscending().map { it.toJson() }))
            put("scenario_policies", JsonArray(db.scenarioPolicyDao().all().map { it.toJson() }))
            put("memories", JsonArray(db.memoryDao().all().map { it.toJson() }))
            put("user_traits", JsonArray(db.userTraitDao().all().map { it.toJson() }))
            put("behavior_tendencies", JsonArray(db.behaviorTendencyDao().all().map { it.toJson() }))
            put("character_core", JsonArray(db.characterCoreDao().all().map { it.toJson() }))
            db.petStateDao().get()?.let { put("pet_state", it.toJson()) }
            put(
                "memory_embedding_stats",
                buildJsonObject {
                    put("count", db.memoryEmbeddingDao().count())
                    put("vectors_exported", false)
                    put("note", "向量原始二进制不进入诊断包；保留数量用于判断检索降级")
                },
            )
            put("ai_action_plans", JsonArray(db.actionPlanDao().allAscending().map { it.toJson() }))
            put("action_executions", JsonArray(db.actionExecutionDao().allAscending().map { it.toJson() }))
            put("delivery_history", JsonArray(db.deliveryHistoryDao().allAscending().map { it.toJson() }))
            put("todos", JsonArray(db.todoDao().allAscending().map { it.toJson() }))
            put("triggers", JsonArray(db.triggerDao().allAscending().map { it.toJson() }))
            put("external_cache", JsonArray(db.externalCacheDao().all().map { it.toJson() }))
        }
        val redactor = SensitiveWordRedactor.fromInput(sensitiveWordInput)
        val sanitizedRoot = redactor.redact(root)
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(now))
        val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val file = File(directory, "littlekitty-diagnostics-$stamp.json")
        file.writeText(
            JsonCodec.json.encodeToString(JsonElement.serializer(), sanitizedRoot),
            Charsets.UTF_8,
        )
        diagnosticsLogger.record(
            category = DiagnosticLogCategories.SYSTEM,
            name = "diagnostics_export",
            status = DiagnosticLogStatuses.SUCCESS,
            summary = "诊断包已生成，敏感词规则 ${redactor.wordCount} 条",
        )
        FileProvider.getUriForFile(context, "${context.packageName}.diagnostics.fileprovider", file)
    }

    fun shareIntent(context: Context, uri: android.net.Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_SUBJECT, "狗狗伴伴诊断日志")
        putExtra(Intent.EXTRA_TEXT, "狗狗伴伴诊断日志，请用于分析场景触发与后台任务执行情况。")
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun taskHealth(
        spec: DiagnosticTaskSpec,
        logs: List<DiagnosticLogEntity>,
        now: Long,
    ): JsonObject {
        val taskLogs = logs.filter { it.category == DiagnosticLogCategories.TASK && it.name == spec.id }
        val lastSuccess = taskLogs
            // Older workers recorded a non-retryable FAILED outcome as a
            // SUCCESS status with summary=FAILED. Keep historical exports
            // honest even after the worker-side status fix lands.
            .filter {
                (it.status == DiagnosticLogStatuses.SUCCESS && it.summary != "FAILED") ||
                    it.status == DiagnosticLogStatuses.SKIPPED
            }
            .maxOfOrNull { it.recordedAt }
        val lastStarted = taskLogs.maxOfOrNull { it.recordedAt }
        val last = taskLogs.maxByOrNull { it.recordedAt }
        val status = when {
            lastSuccess == null -> "NOT_OBSERVED"
            now - lastSuccess > spec.graceMillis -> "POSSIBLE_MISSED"
            else -> "OK"
        }
        return buildJsonObject {
            put("task_id", spec.id)
            put("label", spec.label)
            put("expected_interval_millis", spec.intervalMillis)
            put("status", status)
            put("last_success_at", lastSuccess ?: 0L)
            put("last_started_at", lastStarted ?: 0L)
            put("last_record_status", last?.status ?: "NEVER")
            put("gap_millis", if (lastSuccess == null) -1L else now - lastSuccess)
            put(
                "diagnosis",
                when (status) {
                    "OK" -> "最近一次执行在预期窗口内"
                    "POSSIBLE_MISSED" -> "超过预期执行窗口，可能被系统后台限制、进程被杀或网络约束延迟"
                    else -> "尚未观察到成功执行记录，可能尚未启动或日志功能刚开启"
                },
            )
        }
    }

    private fun MessageEntity.toJson() = buildJsonObject {
        put("id", id); put("role", role); put("content", content); put("created_at", createdAt)
        putNullable("source_event_id", sourceEventId); putNullable("source_todo_id", sourceTodoId)
        putNullable("animation_id", animationId); put("status", status); putNullable("error_code", errorCode)
    }

    private fun ConversationSummaryEntity.toJson() = buildJsonObject {
        put("id", id); put("range_start_message_id", rangeStartMessageId); put("range_end_message_id", rangeEndMessageId)
        put("summary", summary); put("created_at", createdAt)
    }

    private fun MemoryEntity.toJson() = buildJsonObject {
        put("id", id); put("type", type); putNullable("subject", subject); put("content", content)
        putNullable("summary", summary); put("importance", importance); put("confidence", confidence)
        putNullable("valid_from", validFrom); putNullable("valid_until", validUntil)
        putNullable("source_message_id", sourceMessageId); putNullable("source_event_id", sourceEventId)
        putNullable("supersedes_memory_id", supersedesMemoryId); putRaw("emotion_at_event", emotionAtEventJson)
        putRaw("current_appraisal", currentAppraisalJson); putNullable("last_reassessed_at", lastReassessedAt)
        put("status", status); put("created_at", createdAt); put("updated_at", updatedAt)
    }

    private fun UserTraitEntity.toJson() = buildJsonObject {
        put("id", id); put("key", key); putRaw("value", valueJson); put("confidence", confidence)
        putNullable("evidence_memory_ids", evidenceMemoryIdsJson); put("profile_layer", profileLayer)
        put("status", status); put("updated_at", updatedAt)
    }

    private fun BehaviorTendencyEntity.toJson() = buildJsonObject {
        put("id", id); put("key", key); putNullable("original_value", originalValue); putNullable("current_value", currentValue)
        suppressionLevel?.let { put("suppression_level", it) }; putRaw("legacy_expression_rule", legacyExpressionRuleJson)
        putNullable("source_memory_ids", sourceMemoryIdsJson); put("status", status); put("updated_at", updatedAt)
    }

    private fun CharacterCoreEntity.toJson() = buildJsonObject {
        put("id", id); put("version", version); putRaw("content", contentJson); put("created_at", createdAt)
    }

    private fun PetStateEntity.toJson() = buildJsonObject {
        put("singleton_id", singletonId); put("state_id", stateId); putNullable("reason_event_id", reasonEventId)
        putNullable("animation_id", animationId); putNullable("short_text", shortText); put("starts_at", startsAt)
        putNullable("expires_at", expiresAt); putRaw("click_target", clickTargetJson); put("updated_at", updatedAt)
    }

    private fun ScenarioPolicyEntity.toJson() = buildJsonObject {
        put("id", id); put("event_type", eventType); put("mode", mode); putRaw("matcher", matcherJson)
        putNullable("prompt_template", promptTemplate); putRaw("examples", examplesJson); putRaw("allowed_actions", allowedActionsJson)
        putRaw("constraints", constraintsJson); put("version", version)
    }

    private fun EventEntity.toJson() = buildJsonObject {
        put("id", id); put("event_key", eventKey); put("type", type); put("source", source)
        put("occurred_at", occurredAt); put("received_at", receivedAt); putNullable("expires_at", expiresAt)
        put("priority", priority); put("status", status); put("attempt_count", attemptCount)
        putNullable("last_error_code", lastErrorCode); putNullable("last_error_detail", lastErrorDetail)
        putNullable("next_retry_at", nextRetryAt); putRaw("payload", payloadJson)
    }

    private fun ActionPlanEntity.toJson() = buildJsonObject {
        put("id", id); put("event_id", eventId); put("created_at", createdAt); put("status", status)
        putNullable("decision_summary", decisionSummary); putNullable("active_event_key", activeEventKey)
        putRaw("agent_output", agentOutputJson)
    }

    private fun ActionExecutionEntity.toJson() = buildJsonObject {
        put("id", id); put("action_plan_id", actionPlanId); put("action_type", actionType)
        put("status", status); putNullable("executed_at", executedAt); putNullable("error_code", errorCode)
        putRaw("payload", payloadJson)
    }

    private fun DeliveryHistoryEntity.toJson() = buildJsonObject {
        put("id", id); putNullable("event_id", eventId); putNullable("todo_id", todoId); put("channel", channel)
        put("status", status); putNullable("reserved_at", reservedAt); putNullable("attempt_started_at", attemptStartedAt)
        put("attempt_count", attemptCount); putNullable("next_retry_at", nextRetryAt); putNullable("error_code", errorCode)
        putNullable("execution_id", executionId); putNullable("cooldown_group", cooldownGroup); put("delivered_at", deliveredAt)
        putNullable("user_opened_at", userOpenedAt); putNullable("user_replied_at", userRepliedAt)
    }

    private fun TodoEntity.toJson() = buildJsonObject {
        put("id", id); put("type", type); putNullable("title", title); put("status", status); put("priority", priority)
        putNullable("source_event_id", sourceEventId); putNullable("available_at", availableAt); putNullable("expires_at", expiresAt)
        putNullable("claimed_by", claimedBy); putNullable("claimed_at", claimedAt); put("created_at", createdAt); put("updated_at", updatedAt)
        putRaw("payload", payloadJson)
    }

    private fun TriggerEntity.toJson() = buildJsonObject {
        put("id", id); putNullable("todo_id", todoId); put("trigger_type", triggerType); put("status", status)
        putNullable("next_fire_at", nextFireAt); putNullable("last_fire_at", lastFireAt); putRaw("trigger_config", triggerConfigJson)
    }

    private fun ExternalCacheEntity.toJson() = buildJsonObject {
        put("cache_key", cacheKey); put("source", source); put("fetched_at", fetchedAt); putNullable("expires_at", expiresAt)
        putRaw("payload", payloadJson)
    }

    private fun DiagnosticLogEntity.toJson() = buildJsonObject {
        put("id", id); put("recorded_at", recordedAt); put("category", category); put("name", name); put("status", status)
        putNullable("subject_id", subjectId); putNullable("summary", summary); putRaw("payload", payloadJson)
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: String?) { value?.let { put(key, it) } }
    private fun JsonObjectBuilder.putNullable(key: String, value: Long?) { value?.let { put(key, it) } }
    private fun JsonObjectBuilder.putRaw(key: String, value: String?) {
        if (value == null) return
        put(key, runCatching { JsonCodec.json.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) })
    }
}
