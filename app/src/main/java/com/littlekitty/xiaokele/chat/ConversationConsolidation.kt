package com.littlekitty.xiaokele.chat

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.MemoryEvaluation
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.ConversationSummaryEntity
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import com.littlekitty.xiaokele.core.network.HttpMethod
import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import com.littlekitty.xiaokele.core.network.ServiceCallTrace
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogCategories
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogNames
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import com.littlekitty.xiaokele.memory.MemoryAnalysisProcessor
import com.littlekitty.xiaokele.provider.MiniMaxDirectConfig
import com.littlekitty.xiaokele.provider.ModelProvider
import com.littlekitty.xiaokele.provider.ReplyPromptPack
import com.littlekitty.xiaokele.provider.TaskKind
import com.littlekitty.xiaokele.provider.TaskModelGateway
import com.littlekitty.xiaokele.provider.TaskModelRequest
import com.littlekitty.xiaokele.provider.TaskModelResult
import com.littlekitty.xiaokele.settings.ByokRuntimeConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val SUMMARY_FIELD_PATTERN = Regex("\\\"summary\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)")

/**
 * Models sometimes serialize the optional relationship fields as strings even
 * though the wire contract allows structured objects. Preserve that signal in
 * a small object instead of discarding the whole otherwise useful summary.
 */
internal fun normalizeConsolidationOutputJson(raw: String): String {
    val root = JsonCodec.json.parseToJsonElement(raw) as? JsonObject ?: return raw
    val memoryEvaluation = root["memory_evaluation"] as? JsonObject ?: return raw
    val candidates = memoryEvaluation["memory_candidates"] as? JsonArray ?: return raw
    val normalizedCandidates = JsonArray(candidates.map { element ->
        val candidate = element as? JsonObject ?: return@map element
        buildJsonObject {
            candidate.forEach { (key, value) ->
                if (key == "emotion_at_event" || key == "current_appraisal") {
                    put(key, value.toOptionalObject())
                } else {
                    put(key, value)
                }
            }
        }
    })
    return buildJsonObject {
        root.forEach { (key, value) ->
            if (key == "memory_evaluation") {
                put(
                    key,
                    buildJsonObject {
                        memoryEvaluation.forEach { (memoryKey, memoryValue) ->
                            put(
                                memoryKey,
                                if (memoryKey == "memory_candidates") normalizedCandidates else memoryValue,
                            )
                        }
                    },
                )
            } else {
                put(key, value)
            }
        }
    }.toString()
}

private fun JsonElement.toOptionalObject(): JsonElement = when (this) {
    is JsonObject,
    JsonNull,
    -> this
    else -> buildJsonObject { put("value", this@toOptionalObject) }
}

private fun truncatedSummaryOrNull(raw: String): String? {
    val escaped = SUMMARY_FIELD_PATTERN.find(raw)?.groupValues?.getOrNull(1) ?: return null
    return runCatching { JsonCodec.decode<String>("\"$escaped\"") }
        .getOrNull()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

/** Approximate tokenizer used only for deterministic local context budgeting. */
object ContextTokenEstimator {
    fun estimate(raw: String): Int {
        if (raw.isEmpty()) return 0
        var weightedUnits = 0
        raw.codePoints().forEach { codePoint ->
            weightedUnits += when {
                Character.isWhitespace(codePoint) -> 1
                isCjk(codePoint) -> 4
                else -> 1
            }
        }
        return ((weightedUnits + 3) / 4).coerceAtLeast(1)
    }

    fun estimate(message: MessageEntity): Int =
        MESSAGE_JSON_OVERHEAD_TOKENS + estimate(message.role) + estimate(message.content)

    private fun isCjk(codePoint: Int): Boolean =
        codePoint in 0x3400..0x9FFF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0x20000..0x2FA1F

    private const val MESSAGE_JSON_OVERHEAD_TOKENS = 18
}

data class ConversationBudgetPolicy(
    /** Dialogue allowance; the provider applies the 8,000-token whole-request cap. */
    val rawMessageTokenBudget: Int = 8_000,
    val maxRawMessageCount: Int = Int.MAX_VALUE,
    /** Reserve room for persona, structured context and the current turn. */
    val highFrequencyCheckpointRawTokenThreshold: Int = 5_000,
    val summaryTokenBudget: Int = 600,
    val maxSummaryCount: Int = 1,
    val summaryRetentionMillis: Long = 7L * 24L * 60L * 60L * 1_000L,
    val maxSummaryCharacters: Int = 800,
)

@Serializable
data class ConversationConsolidationMessage(
    val id: String,
    val role: String,
    val content: String,
    @SerialName("created_at")
    val createdAt: String,
)

@Serializable
data class ConversationConsolidationOutput(
    val summary: String,
    @SerialName("memory_evaluation")
    val memoryEvaluation: MemoryEvaluation = MemoryEvaluation(),
)

sealed interface ConversationConsolidationResult {
    data class Success(
        val output: ConversationConsolidationOutput,
        val trace: ServiceCallTrace? = null,
        val commitGuard: () -> Boolean = { true },
    ) : ConversationConsolidationResult
    data class Failure(
        val error: AppError,
        val trace: ServiceCallTrace? = null,
    ) : ConversationConsolidationResult
}

fun interface ConversationConsolidator {
    suspend fun consolidate(messages: List<ConversationConsolidationMessage>): ConversationConsolidationResult
}

/** One BYOK request returns both the recent-conversation summary and optional durable learning. */
class MiniMaxConversationConsolidator(
    private val settings: ByokRuntimeConfig,
    private val transport: HttpTransport,
    private val taskGateway: TaskModelGateway? = null,
) : ConversationConsolidator {

    override suspend fun consolidate(
        messages: List<ConversationConsolidationMessage>,
    ): ConversationConsolidationResult {
        if (messages.isEmpty()) {
            return ConversationConsolidationResult.Failure(
                AppError(ErrorCode.INVALID_ARGUMENT, technicalDetail = "conversation_consolidation_empty"),
            )
        }
        taskGateway?.let { gateway ->
            return when (val result = gateway.generate(
                TaskModelRequest(
                    task = TaskKind.D_MEMORY,
                    userPayload = JsonCodec.encode(messages),
                    systemPrompt = ReplyPromptPack.D_SYSTEM + "\n\n只生成会话摘要和 MemoryEvaluation；不得补造事实。",
                ),
            )) {
                is TaskModelResult.Success -> parseContent(result.content, result.trace, result.commitGuard)
                is TaskModelResult.Failure -> ConversationConsolidationResult.Failure(result.error, result.trace)
            }
        }
        // Keep the old direct MiniMax adapter available to explicit legacy
        // callers, but scope the guard to D_MEMORY's configured provider.
        if (settings.agentModelRoute(TaskKind.D_MEMORY).provider != ModelProvider.MINIMAX_OFFICIAL) {
            return ConversationConsolidationResult.Failure(
                AppError(
                    ErrorCode.MODEL_NOT_CONFIGURED,
                    technicalDetail = "profiled_task_gateway_required_for_selected_profile",
                ),
            )
        }
        val key = settings.loadKeyOrNull()
        if (key.isNullOrBlank()) {
            return ConversationConsolidationResult.Failure(
                AppError(ErrorCode.MODEL_NOT_CONFIGURED, technicalDetail = "conversation_consolidation_key_missing"),
            )
        }
        val endpoint = settings.endpoint().ifBlank {
            if (settings.region() == "INTL") MiniMaxDirectConfig.M2_INTL_ENDPOINT else MiniMaxDirectConfig.M2_CN_ENDPOINT
        }
        val body = buildJsonObject {
            put("model", MiniMaxDirectConfig.DEFAULT_OFFICIAL_MODEL)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", JsonCodec.encode(messages))
                })
            })
            put("temperature", 0.1)
            // The legacy official adapter remains an explicit compatibility
            // seam, so keep its D_MEMORY output bounded like the profiled
            // route instead of relying on a provider default.
            put("max_completion_tokens", 2_048)
        }.toString()
        val startedAt = System.currentTimeMillis()

        return when (
            val result = transport.execute(
                HttpRequest(
                    method = HttpMethod.POST,
                    url = endpoint,
                    headers = mapOf(
                        "Authorization" to "Bearer $key",
                        "Content-Type" to "application/json",
                    ),
                    body = body,
                ),
            )
        ) {
            is NetworkResult.Failure -> ConversationConsolidationResult.Failure(
                result.error,
                trace = ServiceCallTrace(
                    serviceName = "minimax_byok_conversation_consolidator",
                    requestBody = body,
                    latencyMillis = System.currentTimeMillis() - startedAt,
                ),
            )
            is NetworkResult.Success -> parseResponse(
                result.statusCode,
                result.body,
                ServiceCallTrace(
                    serviceName = "minimax_byok_conversation_consolidator",
                    requestBody = body,
                    rawResponseBody = result.body,
                    responseStatusCode = result.statusCode,
                    latencyMillis = System.currentTimeMillis() - startedAt,
                ),
            )
        }
    }

    private fun parseResponse(statusCode: Int, body: String, trace: ServiceCallTrace): ConversationConsolidationResult {
        if (statusCode !in 200..299) {
            return ConversationConsolidationResult.Failure(
                AppError(ErrorCode.MODEL_FAILURE, technicalDetail = "conversation_consolidation_http_$statusCode"),
                trace = trace,
            )
        }
        return runCatching {
            val root = JsonCodec.json.parseToJsonElement(body).jsonObject
            val baseStatus = root["base_resp"]?.jsonObject
                ?.get("status_code")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            require(baseStatus == 0) { "provider_status_$baseStatus" }
            val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                .orEmpty()
            val normalized = content.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val output = runCatching {
                JsonCodec.decode<ConversationConsolidationOutput>(normalizeConsolidationOutputJson(normalized))
            }.getOrElse { decodeError ->
                // A provider response may be cut off after emitting a usable
                // summary. Keep context continuity and drop only the optional
                // learning payload in that case; never invent memory facts.
                val summary = truncatedSummaryOrNull(normalized) ?: throw decodeError
                ConversationConsolidationOutput(summary = summary)
            }
            require(output.summary.isNotBlank()) { "summary_blank" }
            ConversationConsolidationResult.Success(output, trace)
        }.getOrElse { throwable ->
            ConversationConsolidationResult.Failure(
                AppError(
                    ErrorCode.MODEL_OUTPUT_INVALID,
                    technicalDetail = "conversation_consolidation_invalid:${throwable.message}",
                ),
                trace = trace,
            )
        }
    }

    private fun parseContent(
        content: String,
        trace: ServiceCallTrace,
        commitGuard: () -> Boolean = { true },
    ): ConversationConsolidationResult = runCatching {
        val normalized = content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val output = runCatching {
            JsonCodec.decode<ConversationConsolidationOutput>(normalizeConsolidationOutputJson(normalized))
        }.getOrElse { decodeError ->
            val summary = truncatedSummaryOrNull(normalized) ?: throw decodeError
            ConversationConsolidationOutput(summary = summary)
        }
        require(output.summary.isNotBlank()) { "summary_blank" }
        ConversationConsolidationResult.Success(output, trace, commitGuard)
    }.getOrElse { throwable ->
        ConversationConsolidationResult.Failure(
            AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "conversation_consolidation_invalid:${throwable.message}"),
            trace = trace,
        )
    }

    companion object {
        private val SYSTEM_PROMPT = """
            你负责压缩一段即将离开原始上下文窗口的真实聊天记录。
            只能依据输入消息，不得补充推测。自行识别其中一个或多个话题，但不要判断整场对话是否结束。
            summary 应保留：讨论内容、已经形成的结论、仍待承接的问题、重要的当时感受；普通寒暄只需简要概括。
            同时只提取未来仍有帮助的长期信息。普通寒暄、单次情绪、知识问答和无未来用途的事实不得写入 memory_candidates。
            TEMPORARY 默认只用于 24 小时状态，SHORT_TERM 默认用于 14 天内状态，长期稳定偏好和重要关系事件使用 LONG_TERM。
            user_trait_patches 的 profile_layer 只能是 CORE 或 DYNAMIC：稳定身份、稳定偏好、禁忌和重要沟通方式用 CORE；近期状态和未稳定观察用 DYNAMIC。
            重要关系事件可填写 emotion_at_event 和 current_appraisal；普通事实不要填写。
            重复但尚不足以成为稳定画像的行为写入 pattern_observations，包含 key、observation、confidence、evidence_message_ids；单次行为不要写成稳定习惯。
            只能输出一个 JSON 对象，不能输出 Markdown。结构：
            {"summary":"中文摘要","memory_evaluation":{"memory_candidates":[{"type":"SHORT_TERM_MEMORY","subject":null,"content":"事实","summary":null,"importance":0.7,"confidence":0.8,"validity":"SHORT_TERM","evidence_message_ids":["msg_..."],"source_message_id":"msg_...","valid_until":null,"supersedes_memory_id":null,"emotion_at_event":null,"current_appraisal":null}],"user_trait_patches":[{"key":"preference_key","operation":"UPSERT","value":{"description":"偏好"},"confidence":0.8,"profile_layer":"CORE","evidence_memory_ids":[]}],"behavior_patches":[],"deactivate_memory_ids":[],"pattern_observations":[]}}
            没有对应结果时必须输出空数组。只有用户明确要求忘记已有记忆时，才可从输入提供的已有记忆中选择 deactivate_memory_ids；本次阶段输入没有已有记忆时保持空数组。
        """.trimIndent()
    }
}

data class ConversationWindow(
    val rawMessages: List<MessageEntity>,
    val recentSummaries: List<ConversationSummaryEntity>,
    val temporarySessionState: String? = null,
)

/**
 * Summarizes only when the unsummarized raw-message budget would overflow.
 * Time gaps and topic-boundary guesses intentionally do not trigger work.
 */
class ConversationContextManager(
    private val db: AppDatabase,
    private val consolidator: ConversationConsolidator,
    private val memoryAnalysisProcessor: MemoryAnalysisProcessor,
    private val timeProvider: TimeProvider,
    private val policy: ConversationBudgetPolicy = ConversationBudgetPolicy(),
    private val diagnosticsLogger: DiagnosticsLogger? = null,
    private val runConsolidationInline: Boolean = false,
) {
    private val consolidationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightRanges = ConcurrentHashMap.newKeySet<String>()

    suspend fun prepare(): ConversationWindow {
        // Normal context never gets a conversation summary. A completed session
        // has already been sent through memory settlement; raw history remains
        // in SQLite but leaves future prompts. Low-frequency turns from the
        // current operational day are deliberately retained as raw text.
        val messages = db.messageDao().allAscending().filter { it.status != MessageStatuses.FAILED &&
            it.errorCode != com.littlekitty.xiaokele.provider.CompanionFallback.MESSAGE_MARKER }
        var active = db.conversationSessionDao().active()
        active = active?.let { maybeCreateHighFrequencyCheckpoint(it, messages) }
        val sessions = db.conversationSessionDao().settledDescending()
        val index = messages.mapIndexed { position, message -> message.id to position }.toMap()
        val settledIndexes = buildSet {
            sessions.forEach { session ->
                val start = index[session.firstMessageId] ?: return@forEach
                val end = index[session.lastMessageId] ?: return@forEach
                if (start <= end) addAll(start..end)
            }
        }
        val checkpointIndex = active?.checkpointEndMessageId?.let(index::get)
        val retained = messages.filterIndexed { position, message ->
            position !in settledIndexes ||
                (active?.mode == com.littlekitty.xiaokele.core.database.entity.ConversationSessionModes.LOW_FREQUENCY &&
                    active.operationalDayKey == operationalDayKey(message.createdAt))
        }.filterIndexed { _, message ->
            checkpointIndex == null || index.getValue(message.id) > checkpointIndex
        }
        return ConversationWindow(
            rawMessages = takeNewestWithinBudget(
                values = retained,
                budget = policy.rawMessageTokenBudget,
                cost = ContextTokenEstimator::estimate,
                maxItems = policy.maxRawMessageCount,
            ),
            recentSummaries = emptyList(),
            temporarySessionState = active?.checkpointSummary,
        )

    }

    private fun operationalDayKey(epochMillis: Long): String {
        val local = java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), timeProvider.zone())
        val boundary = local.withHour(4).withMinute(0).withSecond(0).withNano(0)
        val day = if (local.isBefore(boundary)) local.toLocalDate().minusDays(1) else local.toLocalDate()
        return "$day@4"
    }

    /** The only summary path: a temporary rolling state for an active high-frequency session. */
    private suspend fun maybeCreateHighFrequencyCheckpoint(
        session: com.littlekitty.xiaokele.core.database.entity.ConversationSessionEntity,
        allMessages: List<MessageEntity>,
    ): com.littlekitty.xiaokele.core.database.entity.ConversationSessionEntity {
        if (session.mode != com.littlekitty.xiaokele.core.database.entity.ConversationSessionModes.HIGH_FREQUENCY ||
            session.checkpointSummary != null
        ) return session
        val start = allMessages.indexOfFirst { it.id == session.firstMessageId }
        val end = allMessages.indexOfLast { it.id == session.lastMessageId }
        if (start < 0 || end < start) return session
        val sessionMessages = allMessages.subList(start, end + 1)
        if (sessionMessages.sumOf(ContextTokenEstimator::estimate) <= policy.highFrequencyCheckpointRawTokenThreshold) return session
        val checkpointInput = takeNewestWithinBudget(
            values = sessionMessages.dropLast(1),
            budget = HIGH_FREQUENCY_CHECKPOINT_INPUT_TOKENS,
            cost = ContextTokenEstimator::estimate,
            maxItems = Int.MAX_VALUE,
        )
        if (checkpointInput.isEmpty()) return session
        val checkpointKey = "${session.id}:${checkpointInput.last().id}"
        if (!inFlightRanges.add(checkpointKey)) return session
        return try {
            // A second caller may have entered with a stale session snapshot
            // while the first checkpoint was finishing.
            val current = db.conversationSessionDao().byId(session.id) ?: return session
            if (current.checkpointSummary != null) return current
            val consolidation = consolidator.consolidate(checkpointInput.map { it.toConsolidationMessage() })
            val output = (consolidation as? ConversationConsolidationResult.Success)?.output ?: return session
            val summary = output.summary.trim().take(policy.maxSummaryCharacters).takeIf(String::isNotBlank) ?: return session
            val updatedAt = timeProvider.now().toEpochMilli()
            // The checkpoint is a durable active-view replacement. Review the
            // complete D payload (including an empty evaluation) before publish.
            val reviewOutcome = memoryAnalysisProcessor.reviewAndApplyRawEvaluation(
                rawMemoryEvaluationJson = JsonCodec.encode(output.memoryEvaluation),
                sourcePayloadJson = JsonCodec.encode(checkpointInput.map { it.toConsolidationMessage() }),
                allowedMessageIds = checkpointInput.map { it.id }.toSet(),
                now = timeProvider.now().toEpochMilli(),
                correlationId = "checkpoint:${session.id}:${checkpointInput.last().id}",
                upstreamCommitGuard = consolidation.commitGuard,
                commitCallback = {
                    check(consolidation.commitGuard()) { "stale_model_route_before_checkpoint_commit" }
                    check(
                        db.conversationSessionDao().setCheckpointIfAbsent(
                            id = session.id,
                            summary = summary,
                            checkpointEndMessageId = checkpointInput.last().id,
                            now = updatedAt,
                        ) == 1,
                    ) { "checkpoint_claim_lost" }
                },
            )
            if (reviewOutcome is com.littlekitty.xiaokele.memory.MemoryAnalysisOutcome.Failure) return session
            session.copy(
                checkpointSummary = summary,
                checkpointEndMessageId = checkpointInput.last().id,
                updatedAt = updatedAt,
            )
        } finally {
            inFlightRanges.remove(checkpointKey)
        }
    }

    /**
     * Explicit debug/acceptance hook for summarizing all currently uncovered
     * history. Normal context preparation deliberately never calls this path;
     * the explicit action must nevertheless perform real D->C-reviewed
     * consolidation rather than report a successful no-op.
     *
     * Each round re-reads coverage so a partially completed run is safe to
     * retry after a timeout or process death. A failed/no-progress round stops
     * immediately, leaving the remaining raw messages available for the next
     * invocation. The returned count is the number of messages whose summary
     * was actually committed, not merely sent to the model.
     */
    suspend fun consolidateAllNow(): Int {
        var coveredCount = 0
        for (round in 0 until MAX_FORCED_CONSOLIDATION_ROUNDS) {
            val state = readState()
            if (state.uncoveredMessages.isEmpty()) break

            // Keep each request within the raw-message budget where possible.
            // When the remaining history already fits, the explicit action is
            // still expected to summarize it (unlike normal prepare()).
            val range = oldestPrefixRequiredToFit(state.uncoveredMessages)
                .ifEmpty { state.uncoveredMessages }
            if (range.isEmpty()) break

            val startId = range.first().id
            val endId = range.last().id
            val rangeKey = "forced-summary:$startId:$endId"
            if (!inFlightRanges.add(rangeKey)) break
            try {
                val hadSummary = db.conversationSummaryDao().byRange(startId, endId) != null
                consolidateRange(range)
                val hasSummary = db.conversationSummaryDao().byRange(startId, endId) != null
                if (!hadSummary && hasSummary) {
                    coveredCount += range.size
                } else {
                    // Provider failure, invalid D/C output, stale route, or a
                    // competing writer made no progress. Do not spin or
                    // consume another model request in this invocation.
                    break
                }
            } finally {
                inFlightRanges.remove(rangeKey)
            }
        }
        return coveredCount
    }

    private suspend fun consolidateRange(range: List<MessageEntity>) {
        val startId = range.first().id
        val endId = range.last().id
        if (db.conversationSummaryDao().byRange(startId, endId) != null) return

        val requestMessages = range.map { it.toConsolidationMessage() }
        val correlationId = "summary:$startId:$endId"
        val requestBody = JsonCodec.encode(requestMessages)
        diagnosticsLogger?.record(
            category = DiagnosticLogCategories.AI,
            name = DiagnosticLogNames.CONVERSATION_CONSOLIDATION,
            status = DiagnosticLogStatuses.STARTED,
            subjectId = endId,
            summary = "未压缩对话超出预算，后台触发会话压缩",
            payloadJson = buildJsonObject {
                put("marker", "CONVERSATION_CONSOLIDATION_STARTED")
                put("correlation_id", correlationId)
                put("range_start_message_id", startId)
                put("range_end_message_id", endId)
                put("raw_input", requestBody)
            }.toString(),
        )
        diagnosticsLogger?.aiRequestStarted(
            operation = "conversation_consolidation",
            correlationId = correlationId,
            subjectId = endId,
            serviceName = consolidator.javaClass.simpleName,
            requestBody = requestBody,
            requestKind = "conversation_messages",
        )
        when (val result = consolidator.consolidate(requestMessages)) {
            is ConversationConsolidationResult.Failure -> {
                diagnosticsLogger?.aiResponseRecorded(
                    operation = "conversation_consolidation",
                    correlationId = correlationId,
                    subjectId = endId,
                    status = DiagnosticLogStatuses.FAILED,
                    trace = result.trace,
                    fallbackRequestBody = requestBody,
                    errorCode = result.error.code.name,
                    technicalDetail = result.error.technicalDetail,
                )
            }
            is ConversationConsolidationResult.Success -> {
                diagnosticsLogger?.aiResponseRecorded(
                    operation = "conversation_consolidation",
                    correlationId = correlationId,
                    subjectId = endId,
                    status = DiagnosticLogStatuses.SUCCESS,
                    trace = result.trace,
                    fallbackRequestBody = requestBody,
                    normalizedOutput = JsonCodec.encode(result.output),
                )
                val summary = result.output.summary.trim().take(policy.maxSummaryCharacters)
                if (summary.isNotBlank()) {
                    // A consolidation result is D output. Run its memory
                    // review before the summary replaces the active raw
                    // range; a rejected/stale batch must publish nothing.
                    val reviewOutcome = memoryAnalysisProcessor.reviewAndApplyRawEvaluation(
                        rawMemoryEvaluationJson = JsonCodec.encode(result.output.memoryEvaluation),
                        sourcePayloadJson = requestBody,
                        allowedMessageIds = range.map { it.id }.toSet(),
                        now = timeProvider.now().toEpochMilli(),
                        correlationId = correlationId,
                        upstreamCommitGuard = result.commitGuard,
                        commitCallback = {
                            check(result.commitGuard()) { "stale_model_route_before_summary_commit" }
                            db.conversationSummaryDao().upsert(
                                ConversationSummaryEntity(
                                    id = EntityId.generate("summary").value,
                                    rangeStartMessageId = startId,
                                    rangeEndMessageId = endId,
                                    summary = summary,
                                    createdAt = timeProvider.now().toEpochMilli(),
                                ),
                            )
                        },
                    )
                    if (reviewOutcome is com.littlekitty.xiaokele.memory.MemoryAnalysisOutcome.Failure) return
                }
            }
        }
    }

    private suspend fun readState(): WindowState {
        val messages = db.messageDao().allAscending()
            .filter { it.status != MessageStatuses.FAILED &&
                it.errorCode != com.littlekitty.xiaokele.provider.CompanionFallback.MESSAGE_MARKER }
        val summaries = db.conversationSummaryDao().allAscending()
        val indexById = messages.mapIndexed { index, message -> message.id to index }.toMap()
        val covered = mutableSetOf<Int>()
        summaries.forEach { summary ->
            val start = indexById[summary.rangeStartMessageId] ?: return@forEach
            val end = indexById[summary.rangeEndMessageId] ?: return@forEach
            if (start <= end) (start..end).forEach(covered::add)
        }
        return WindowState(
            messages = messages,
            summaries = summaries,
            indexById = indexById,
            uncoveredMessages = messages.filterIndexed { index, _ -> index !in covered },
        )
    }

    private fun oldestPrefixRequiredToFit(messages: List<MessageEntity>): List<MessageEntity> {
        if (messages.size <= 1) return emptyList()
        var remaining = messages.sumOf(ContextTokenEstimator::estimate)
        val prefix = mutableListOf<MessageEntity>()
        for (message in messages.dropLast(1)) {
            if (remaining <= policy.rawMessageTokenBudget) break
            prefix += message
            remaining -= ContextTokenEstimator.estimate(message)
        }
        return prefix
    }

    private fun WindowState.toWindow(): ConversationWindow {
        val now = timeProvider.now().toEpochMilli()
        val recent = summaries.filter { summary ->
            val endIndex = indexById[summary.rangeEndMessageId] ?: return@filter false
            val endTime = messages[endIndex].createdAt
            endTime <= now && now - endTime <= policy.summaryRetentionMillis
        }
        val selectedSummaries = takeNewestWithinBudget(
            values = recent,
            budget = policy.summaryTokenBudget,
            cost = { ContextTokenEstimator.estimate(it.summary) + SUMMARY_JSON_OVERHEAD_TOKENS },
            maxItems = policy.maxSummaryCount,
        )
        val selectedMessages = takeNewestWithinBudget(
            values = uncoveredMessages,
            budget = policy.rawMessageTokenBudget,
            cost = ContextTokenEstimator::estimate,
            maxItems = policy.maxRawMessageCount,
        )
        return ConversationWindow(selectedMessages, selectedSummaries)
    }

    private fun <T> takeNewestWithinBudget(
        values: List<T>,
        budget: Int,
        cost: (T) -> Int,
        maxItems: Int,
    ): List<T> {
        if (values.isEmpty()) return emptyList()
        var used = 0
        val selected = mutableListOf<T>()
        values.asReversed().forEach { value ->
            if (selected.size >= maxItems) return@forEach
            val itemCost = cost(value)
            if (selected.isEmpty() || used + itemCost <= budget) {
                selected += value
                used += itemCost
            }
        }
        return selected.asReversed()
    }

    private fun MessageEntity.toConsolidationMessage() = ConversationConsolidationMessage(
        id = id,
        role = role,
        content = content,
        createdAt = InstantJson.formatUtc(Instant.ofEpochMilli(createdAt)),
    )

    private data class WindowState(
        val messages: List<MessageEntity>,
        val summaries: List<ConversationSummaryEntity>,
        val indexById: Map<String, Int>,
        val uncoveredMessages: List<MessageEntity>,
    )

    companion object {
        private const val SUMMARY_JSON_OVERHEAD_TOKENS = 24
        private const val MAX_FORCED_CONSOLIDATION_ROUNDS = 64
        private const val HIGH_FREQUENCY_CHECKPOINT_INPUT_TOKENS = 6_000
    }
}

internal fun ConversationSummaryEntity.toContextJson(endMessage: MessageEntity?): JsonObject = buildJsonObject {
    put("id", id)
    put("range_start_message_id", rangeStartMessageId)
    put("range_end_message_id", rangeEndMessageId)
    put("summary", summary)
    endMessage?.let {
        put("conversation_ended_at", InstantJson.formatUtc(Instant.ofEpochMilli(it.createdAt)))
    }
}
