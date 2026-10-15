package com.littlekitty.xiaokele.core.network.memory

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.network.ServiceCallTrace
import com.littlekitty.xiaokele.core.network.ServiceAdapter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Replaceable memory-evaluation inference boundary.
 *
 * A real MiniMax-backed implementation is intentionally not selected until
 * D-01/D-02/D-03 are confirmed. Adapters return raw standardized
 * MemoryEvaluation JSON; decoding and deterministic validation happen in the
 * local learning layer so vendor response shapes never leak into repositories.
 */
interface MemoryEvaluator : ServiceAdapter {
    suspend fun evaluate(request: MemoryEvaluationRequest): MemoryEvaluationResult
}

@Serializable
data class MemoryEvaluationMessage(
    val id: String,
    val role: String,
    val content: String,
    @SerialName("created_at")
    val createdAt: String? = null,
)

@Serializable
data class MemoryEvaluationRequest(
    @SerialName("event_id")
    val eventId: String,
    @SerialName("event_type")
    val eventType: String = "USER_MESSAGE",
    @SerialName("user_message")
    val userMessage: MemoryEvaluationMessage,
    @SerialName("assistant_reply")
    val assistantReply: MemoryEvaluationMessage,
    @SerialName("recent_messages")
    val recentMessages: List<MemoryEvaluationMessage> = emptyList(),
    @SerialName("active_memory_count")
    val activeMemoryCount: Int = 0,
    @SerialName("active_memories")
    val activeMemories: List<MemoryEvaluationExistingMemory> = emptyList(),
    /** Set only for one format-repair retry; ordinary memory evaluation leaves it absent. */
    @SerialName("format_repair_instruction")
    val formatRepairInstruction: String? = null,
)

@Serializable
data class MemoryEvaluationExistingMemory(
    val id: String,
    val type: String,
    val subject: String? = null,
    val content: String,
    val summary: String? = null,
)

sealed interface MemoryEvaluationResult {
    data class Success(
        val rawMemoryEvaluationJson: String,
        val trace: ServiceCallTrace? = null,
        val commitGuard: () -> Boolean = { true },
    ) : MemoryEvaluationResult

    data class Failure(
        val error: AppError,
        val trace: ServiceCallTrace? = null,
    ) : MemoryEvaluationResult
}
