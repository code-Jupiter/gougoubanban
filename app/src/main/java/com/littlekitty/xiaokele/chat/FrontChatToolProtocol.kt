package com.littlekitty.xiaokele.chat

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.network.ServiceCallTrace
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One model-visible tool invocation requested by the front-chat model. */
data class FrontChatToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    val assistantMessage: JsonObject,
    /** Index in the provider's raw tool_calls array, used to build a bounded continuation. */
    val assistantToolCallIndex: Int = 0,
)

/** A single continuation message used by the prompt-level tool protocol. */
sealed interface FrontChatToolTurn {
    data class AssistantToolCalls(val assistantMessage: JsonObject) : FrontChatToolTurn

    data class ToolResult(
        val call: FrontChatToolCall,
        val result: JsonObject,
    ) : FrontChatToolTurn
}

sealed interface FrontChatModelResult {
    data class Final(
        val rawDecisionJson: String,
        val trace: ServiceCallTrace? = null,
    ) : FrontChatModelResult

    data class ToolCalls(
        val calls: List<FrontChatToolCall>,
        val trace: ServiceCallTrace? = null,
    ) : FrontChatModelResult

    data class Failure(
        val error: AppError,
        val trace: ServiceCallTrace? = null,
    ) : FrontChatModelResult
}

interface FrontChatModelService {
    suspend fun generateFrontChat(
        context: com.littlekitty.xiaokele.core.common.protocol.ContextPack,
        turns: List<FrontChatToolTurn>,
    ): FrontChatModelResult
}

interface LocalChatToolExecutor {
    suspend fun execute(call: FrontChatToolCall): JsonObject
}

/**
 * Bounds external side effects requested by a model in one tool-loop session.
 *
 * A rejected request is preserved as one structured tool error so the model can
 * finish its answer using earlier data. Calls after that sentinel are omitted
 * from the continuation, avoiding an unbounded assistant/tool transcript.
 */
class ToolCallBudget(
    private val maxCallsPerRound: Int,
    private val maxCallsPerSession: Int,
) {
    private val executedFingerprints = mutableSetOf<String>()
    private var executedCalls = 0

    init {
        require(maxCallsPerRound > 0)
        require(maxCallsPerSession > 0)
    }

    fun select(calls: List<FrontChatToolCall>): ToolCallSelection {
        if (calls.isEmpty()) return ToolCallSelection(buildJsonObject {}, emptyList())

        val candidates = mutableListOf<FrontChatToolCall>()
        val seenThisRound = executedFingerprints.toMutableSet()
        var rejection: ToolCallRejection? = null
        calls.forEach { call ->
            val fingerprint = call.fingerprint()
            when {
                fingerprint in seenThisRound && rejection == null -> {
                    rejection = ToolCallRejection(call, DUPLICATE_TOOL_CALL)
                }

                fingerprint in seenThisRound -> Unit

                candidates.size >= maxCallsPerSession - executedCalls && rejection == null -> {
                    rejection = ToolCallRejection(call, SESSION_LIMIT_REACHED)
                }

                candidates.size >= maxCallsPerRound && rejection == null -> {
                    rejection = ToolCallRejection(call, ROUND_LIMIT_REACHED)
                }

                candidates.size >= maxCallsPerSession - executedCalls || candidates.size >= maxCallsPerRound -> Unit

                else -> {
                    seenThisRound += fingerprint
                    candidates += call
                }
            }
        }

        val executable = if (rejection == null) {
            candidates
        } else {
            // Reserve one continuation slot for the structured error itself.
            candidates.take((maxCallsPerRound - 1).coerceAtLeast(0))
        }
        executable.forEach { call ->
            executedFingerprints += call.fingerprint()
        }
        executedCalls += executable.size

        val selectedCalls = buildList {
            addAll(executable)
            rejection?.let { add(it.call) }
        }
        val assistantMessage = selectedCalls.firstOrNull()?.assistantMessage
            ?.boundedAssistantMessage(selectedCalls.map(FrontChatToolCall::assistantToolCallIndex).toSet())
            ?: calls.first().assistantMessage
        return ToolCallSelection(
            assistantMessage = assistantMessage,
            executions = buildList {
                executable.forEach { add(ToolCallExecution(it)) }
                rejection?.let { add(ToolCallExecution(it.call, rejectionCode = it.code)) }
            },
        )
    }

    private fun FrontChatToolCall.fingerprint(): String = "$name\u0000$arguments"

    private fun JsonObject.boundedAssistantMessage(allowedIndexes: Set<Int>): JsonObject {
        val rawCalls = this["tool_calls"]?.jsonArray ?: return this
        return buildJsonObject {
            this@boundedAssistantMessage.forEach { (key, value) ->
                if (key != "tool_calls") put(key, value)
            }
            put("tool_calls", buildJsonArray {
                rawCalls.forEachIndexed { index, value ->
                    if (index in allowedIndexes) add(value)
                }
            })
        }
    }

    companion object {
        const val DUPLICATE_TOOL_CALL = "duplicate_tool_call_suppressed"
        const val ROUND_LIMIT_REACHED = "tool_call_round_limit_reached"
        const val SESSION_LIMIT_REACHED = "tool_call_session_limit_reached"
    }
}

data class ToolCallSelection(
    val assistantMessage: JsonObject,
    val executions: List<ToolCallExecution>,
)

data class ToolCallExecution(
    val call: FrontChatToolCall,
    val rejectionCode: String? = null,
) {
    val allowed: Boolean
        get() = rejectionCode == null

    fun structuredRejectionResult(): JsonObject = buildJsonObject {
        put("type", "tool_result")
        put("tool_name", call.name)
        put("arguments", call.arguments)
        put("result", buildJsonObject {
            put("ok", false)
            put("error_code", rejectionCode ?: "tool_call_rejected")
            put("message", "本轮查询预算已用尽，请基于已获得的信息继续回答。")
        })
    }
}

private data class ToolCallRejection(
    val call: FrontChatToolCall,
    val code: String,
)

/** Serializable record of the tool-loop turns already exchanged with the model. */
internal fun buildToolTurnsPayload(turns: List<FrontChatToolTurn>): String = buildJsonObject {
    put("kind", "model_tool_turns")
    put("turn_count", turns.size)
    put("turns", buildJsonArray {
        turns.forEach { turn ->
            when (turn) {
                is FrontChatToolTurn.AssistantToolCalls -> add(buildJsonObject {
                    put("kind", "assistant_tool_calls")
                    put("assistant_message", turn.assistantMessage)
                })
                is FrontChatToolTurn.ToolResult -> add(buildJsonObject {
                    put("kind", "tool_result")
                    put("tool_call_id", turn.call.id)
                    put("tool_name", turn.call.name)
                    put("arguments", turn.call.arguments)
                    put("result", turn.result)
                })
            }
        }
    })
}.toString()

/** Shared allowlist and limits for both the compatibility tool loop and A-planned tools. */
object FrontChatToolRegistry {
    const val MAX_CALLS_PER_ROUND = 3
    const val MAX_CALLS_PER_SESSION = 6
    val allowedNames: Set<String> = setOf(
        DefaultLocalToolNames.QUERY_WEATHER,
        DefaultLocalToolNames.QUERY_LOCATION,
        DefaultLocalToolNames.SEARCH_SONG_LYRICS,
        DefaultLocalToolNames.LIST_CREATIVE_TOPICS,
        DefaultLocalToolNames.READ_CREATIVE_CANDIDATE,
        DefaultLocalToolNames.READ_CREATIVE_WORK,
        DefaultLocalToolNames.READ_CREATIVE_SESSION,
        DefaultLocalToolNames.MARK_CREATIVE_TOPIC_DISCUSSED,
        DefaultLocalToolNames.SAVE_CREATIVE_SESSION_PROGRESS,
    )
    /** A-planned tools are evidence reads only; creative writes remain program-gated. */
    val readOnlyNames: Set<String> = allowedNames - setOf(
        DefaultLocalToolNames.MARK_CREATIVE_TOPIC_DISCUSSED,
        DefaultLocalToolNames.SAVE_CREATIVE_SESSION_PROGRESS,
    )

    fun validateReadOnlyArguments(name: String, arguments: JsonObject): String? =
        ToolArgumentSchemas.validate(name, arguments, readOnlyNames)
}

private object ToolArgumentSchemas {
    private enum class Kind { STRING, INTEGER }
    private data class Schema(
        val required: Map<String, Kind> = emptyMap(),
        val optional: Map<String, Kind> = emptyMap(),
        val enumValues: Map<String, Set<String>> = emptyMap(),
        val maxLength: Map<String, Int> = emptyMap(),
    )

    private val schemas = mapOf(
        DefaultLocalToolNames.QUERY_WEATHER to Schema(
            optional = mapOf("profile" to Kind.STRING),
            enumValues = mapOf("profile" to setOf("CORE", "SHIFT_COMMUTE", "DAILY_BRIEF", "FULL")),
        ),
        DefaultLocalToolNames.QUERY_LOCATION to Schema(),
        DefaultLocalToolNames.SEARCH_SONG_LYRICS to Schema(
            required = mapOf("query" to Kind.STRING),
            optional = mapOf("artist_hint" to Kind.STRING),
            maxLength = mapOf("query" to 160, "artist_hint" to 160),
        ),
        DefaultLocalToolNames.LIST_CREATIVE_TOPICS to Schema(
            optional = mapOf("limit" to Kind.INTEGER),
            maxLength = mapOf("limit" to 2),
        ),
        DefaultLocalToolNames.READ_CREATIVE_CANDIDATE to Schema(
            required = mapOf("candidate_id" to Kind.STRING),
            maxLength = mapOf("candidate_id" to 160),
        ),
        DefaultLocalToolNames.READ_CREATIVE_WORK to Schema(
            required = mapOf("work_id" to Kind.STRING),
            maxLength = mapOf("work_id" to 160),
        ),
        DefaultLocalToolNames.READ_CREATIVE_SESSION to Schema(
            required = mapOf("session_id" to Kind.STRING),
            maxLength = mapOf("session_id" to 160),
        ),
    )

    fun validate(name: String, arguments: JsonObject, allowedNames: Set<String>): String? {
        if (name !in allowedNames) return "tool_not_read_only_or_allowlisted"
        val schema = schemas[name] ?: return "tool_has_no_argument_schema"
        val known = schema.required.keys + schema.optional.keys
        val unknown = arguments.keys - known
        if (unknown.isNotEmpty()) return "tool_unknown_argument:${unknown.sorted().joinToString(",")}"
        if (arguments.size > known.size) return "tool_argument_count_exceeded"
        schema.required.forEach { (key, kind) ->
            val value = arguments[key] ?: return "tool_missing_argument:$key"
            validateValue(key, value, kind, schema)?.let { return it }
        }
        schema.optional.forEach { (key, kind) ->
            arguments[key]?.let { value -> validateValue(key, value, kind, schema)?.let { return it } }
        }
        return null
    }

    private fun validateValue(key: String, value: JsonElement, kind: Kind, schema: Schema): String? {
        val primitive = value as? JsonPrimitive ?: return "tool_argument_not_primitive:$key"
        val content = primitive.contentOrNull ?: return "tool_argument_not_scalar:$key"
        when (kind) {
            Kind.STRING -> if (!primitive.isString) return "tool_argument_type:$key"
            Kind.INTEGER -> if (content.toIntOrNull() == null) return "tool_argument_type:$key"
        }
        if (content.length > (schema.maxLength[key] ?: MAX_ARGUMENT_FIELD_LENGTH)) {
            return "tool_argument_too_long:$key"
        }
        schema.enumValues[key]?.let { allowed ->
            if (content !in allowed) return "tool_argument_enum:$key"
        }
        if (key == "limit" && content.toIntOrNull() !in 1..3) return "tool_argument_range:$key"
        if ((key == "from" || key == "to") && !DATE_PATTERN.matches(content)) return "tool_argument_date:$key"
        return null
    }

    private val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
    private const val MAX_ARGUMENT_FIELD_LENGTH = 160
}

private object DefaultLocalToolNames {
    const val QUERY_WEATHER = "query_weather"
    const val QUERY_LOCATION = "query_location"
    const val SEARCH_SONG_LYRICS = "search_song_lyrics"
    const val LIST_CREATIVE_TOPICS = "list_available_creative_topics"
    const val READ_CREATIVE_CANDIDATE = "read_creative_candidate"
    const val READ_CREATIVE_WORK = "read_creative_work"
    const val READ_CREATIVE_SESSION = "read_creative_session"
    const val MARK_CREATIVE_TOPIC_DISCUSSED = "mark_topic_discussed"
    const val SAVE_CREATIVE_SESSION_PROGRESS = "save_creative_session_progress"
}
