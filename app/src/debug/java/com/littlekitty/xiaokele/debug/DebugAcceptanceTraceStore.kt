package com.littlekitty.xiaokele.debug

import android.content.Context
import com.littlekitty.xiaokele.diagnostics.AcceptanceModelCall
import com.littlekitty.xiaokele.diagnostics.AcceptanceTraceRecorder
import com.littlekitty.xiaokele.settings.ByokSettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * Debug-only durable trace sink.  The external-files directory is app
 * private on Android; the user can explicitly pull the exported file with
 * adb for review.  No Room diagnostic row contains the request/response body.
 */
class DebugAcceptanceTraceStore(
    private val context: Context,
    private val settings: ByokSettingsStore,
) : AcceptanceTraceRecorder {
    private val mutex = Mutex()

    override suspend fun record(call: AcceptanceModelCall) {
        mutex.withLock {
            val runId = activeRunId().ifBlank { "unassigned" }
            val directory = runDirectory(runId).apply { mkdirs() }
            val file = traceFile(directory, runId)
            val entries = readEntries(file).toMutableList()
            entries += entryJson(call, runId)
            writeTrace(file, runId, entries)
            writeSummary(directory, runId, entries)
        }
    }

    suspend fun startRun(runId: String) {
        mutex.withLock {
            val directory = runDirectory(runId).apply { mkdirs() }
            val file = traceFile(directory, runId)
            if (!file.exists()) writeTrace(file, runId, emptyList())
            writeSummary(directory, runId, readEntries(file))
        }
    }

    suspend fun appendMarker(runId: String, scenarioId: String, status: String, detail: String) {
        mutex.withLock {
            val directory = runDirectory(runId).apply { mkdirs() }
            val file = markerFile(directory)
            file.appendText(
                "${Instant.now()}\t$scenarioId\t$status\t${detail.replace('\n', ' ')}\n",
                Charsets.UTF_8,
            )
            writeSummary(directory, runId, readEntries(traceFile(directory, runId)))
        }
    }

    /** Rewrite a run after an app upgrade so legacy HTTP_* statuses use the
     * public acceptance vocabulary without changing the recorded exchanges. */
    suspend fun normalizeCurrentTrace(runId: String) {
        mutex.withLock {
            val directory = runDirectory(runId).apply { mkdirs() }
            val file = traceFile(directory, runId)
            if (file.isFile) writeTrace(file, runId, readEntries(file))
            writeSummary(directory, runId, readEntries(file))
        }
    }

    fun currentTraceFile(runId: String): File = traceFile(runDirectory(runId), runId)

    fun currentSummaryFile(runId: String): File = File(runDirectory(runId), "provider_route_dialogue_trace_summary.md")

    private fun activeRunId(): String = RealAiAcceptanceStore(context).runId

    private fun runDirectory(runId: String): File = File(
        context.getExternalFilesDir(null),
        "acceptance/$runId",
    )

    private fun traceFile(directory: File, runId: String): File = File(
        directory,
        "provider_route_dialogue_trace_${runId.replace(Regex("[^A-Za-z0-9_.-]"), "_")}.json",
    )

    private fun markerFile(directory: File): File = File(directory, "scenario_markers.tsv")

    private fun readEntries(file: File): List<kotlinx.serialization.json.JsonElement> = runCatching {
        val root = com.littlekitty.xiaokele.core.common.json.JsonCodec.json
            .parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        root["entries"]?.jsonArray.orEmpty().map(::normalizeEntry)
    }.getOrDefault(emptyList())

    private fun normalizeEntry(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
        val objectValue = element.jsonObject
        val current = objectValue["status"]?.jsonPrimitive?.contentOrNull ?: return element
        val normalized = normalizeStatus(current).let { status ->
            if (status == "SUCCESS" && !hasUsableResponse(objectValue)) "FAILURE" else status
        }
        if (normalized == current) return element
        return buildJsonObject {
            objectValue.forEach { (key, value) ->
                if (key == "status") put(key, normalized) else put(key, value)
            }
        }
    }

    private fun hasUsableResponse(objectValue: kotlinx.serialization.json.JsonObject): Boolean {
        val raw = objectValue["response_body"]?.jsonPrimitive?.contentOrNull ?: return true
        return runCatching {
            val root = com.littlekitty.xiaokele.core.common.json.JsonCodec.json
                .parseToJsonElement(raw).jsonObject
            val message = root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject
            val content = message?.get("content") ?: return@runCatching false
            (content as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true || content !is JsonPrimitive
        }.getOrDefault(false)
    }

    private fun normalizeStatus(status: String): String = when {
        status.equals("HTTP_200", ignoreCase = true) -> "SUCCESS"
        status.startsWith("HTTP_", ignoreCase = true) -> "FAILURE"
        else -> status
    }

    private fun writeTrace(
        file: File,
        runId: String,
        entries: List<kotlinx.serialization.json.JsonElement>,
    ) {
        val root = buildJsonObject {
            put("schema_version", "littlekitty_provider_route_dialogue_trace_v1")
            put("run_id", runId)
            put("agent_routes", buildJsonObject {
                settings.agentModelRoutes().forEach { (task, route) ->
                    put(task.name, buildJsonObject {
                        put("provider", route.provider.name)
                        put("default_model", route.defaultModelId)
                        put("complex_model", route.complexModelId)
                    })
                }
            })
            put("generated_at", Instant.now().toString())
            put("device", "android_debug")
            put("apk_sha256", installedApkSha256())
            put("git_commit", com.littlekitty.xiaokele.BuildConfig.GIT_COMMIT)
            put("entries", buildJsonArray { entries.forEach(::add) })
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(com.littlekitty.xiaokele.core.common.json.JsonCodec.json.encodeToString(
            kotlinx.serialization.json.JsonElement.serializer(), root,
        ), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun writeSummary(directory: File, runId: String, entries: List<kotlinx.serialization.json.JsonElement>) {
        val grouped = entries.groupBy { (it.jsonObject["scenario_id"] as? JsonPrimitive)?.content.orEmpty() }
        val text = buildString {
            appendLine("# Agent 路由真实对话验收摘要")
            appendLine()
            appendLine("- Run ID：`$runId`")
            appendLine("- 记录条数：${entries.size}")
            appendLine("- 供应商：${entries.mapNotNull { it.jsonObject["provider"]?.jsonPrimitive?.content }.distinct().joinToString(", ").ifBlank { "无记录" }}")
            appendLine()
            appendLine("| 场景 | 调用数 | Agent | 状态 |")
            appendLine("|---|---:|---|---|")
            grouped.toSortedMap().forEach { (scenario, rows) ->
                val agents = rows.mapNotNull { it.jsonObject["agent"]?.jsonPrimitive?.content }.distinct().joinToString(", ")
                val statuses = rows.mapNotNull { it.jsonObject["status"]?.jsonPrimitive?.content }.distinct().joinToString(", ")
                appendLine("| ${scenario.ifBlank { "未标记" }} | ${rows.size} | $agents | $statuses |")
            }
            val markerRows = markerFile(directory).takeIf(File::isFile)?.readLines(Charsets.UTF_8).orEmpty()
                .mapNotNull { line ->
                    val fields = line.split('\t', limit = 4)
                    if (fields.size == 4) fields else null
                }
            if (markerRows.isNotEmpty()) {
                appendLine()
                appendLine("## 场景执行结果")
                appendLine()
                appendLine("| 场景 | 结果 | 详情 |")
                appendLine("|---|---|---|")
                markerRows.forEach { fields ->
                    appendLine("| ${fields[1]} | ${fields[2]} | ${fields[3].replace('|', '/')} |")
                }
            }
            appendLine()
            appendLine("请求/响应正文仅存在于 Debug acceptance 文件；未写入生产 diagnostics 或 Release APK。")
        }
        File(directory, "provider_route_dialogue_trace_summary.md").writeText(text, Charsets.UTF_8)
    }

    private fun entryJson(call: AcceptanceModelCall, runId: String) = buildJsonObject {
        put("turn_id", call.turnId)
        put("scenario_id", call.scenarioId)
        put("user_input", redact(call.userInput))
        put("agent", call.agent)
        call.reviewKind?.let { put("review_kind", it) }
        put("provider", call.provider)
        put("model_id", call.modelId)
        put("prompt_version", call.promptVersion)
        put("request_started_at", Instant.ofEpochMilli(call.requestStartedAt).toString())
        put("request_finished_at", Instant.ofEpochMilli(call.requestFinishedAt).toString())
        put("duration_ms", call.durationMillis)
        put("request_body", redact(call.requestBody))
        call.responseBody?.let { put("response_body", redact(it)) }
        call.inputTokens?.let { put("usage_input_tokens", it) }
        call.outputTokens?.let { put("usage_output_tokens", it) }
        call.totalTokens?.let { put("usage_total_tokens", it) }
        put("status", normalizeStatus(call.status))
        put("evidence_ids", buildJsonArray { call.evidenceIds.forEach { add(JsonPrimitive(it)) } })
        put("side_effects", buildJsonArray { call.sideEffects.forEach { add(JsonPrimitive(it)) } })
        call.error?.let { put("error", redact(it)) }
        put("run_id", runId)
    }

    private fun redact(raw: String): String {
        var value = raw
        listOfNotNull(settings.bailianKeyOrNull(), settings.loadKeyOrNull())
            .filter { it.isNotBlank() }
            .forEach { value = value.replace(it, "[REDACTED_API_KEY]") }
        // Bodies should not contain headers, but defend against a manually
        // edited Debug request or a provider echoing one.
        value = value.replace(
            Regex("(?i)(authorization|api[_-]?key|access[_-]?token)\\s*[:=]\\s*([\\\"']?)([^,\\\"'\\s}]+)"),
            "$1:$2[REDACTED]",
        )
        return value
    }

    private fun installedApkSha256(): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        File(context.applicationInfo.sourceDir).inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("unavailable")
}
