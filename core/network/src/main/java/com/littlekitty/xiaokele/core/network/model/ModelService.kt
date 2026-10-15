package com.littlekitty.xiaokele.core.network.model

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.network.ServiceCallTrace
import com.littlekitty.xiaokele.core.network.ServiceAdapter

/**
 * Replaceable model-inference boundary (FR-SERVICE-04, C13).
 *
 * Adapters return raw standardized decision JSON. Decoding and deterministic
 * validation happen in the Agent layer so vendor response shapes never leak
 * into business logic.
 */
interface ModelService : ServiceAdapter {
    suspend fun generate(context: ContextPack): ModelServiceResult
}

/**
 * A model service that persists one diagnostic pair for each provider round.
 * Callers must not add a second aggregate AI request/response pair, because
 * that makes audit exports over-count real provider calls.
 */
interface RoundDiagnosticsOwner {
    val recordsOwnModelRounds: Boolean
}

sealed interface ModelServiceResult {
    data class Success(
        val rawDecisionJson: String,
        val trace: ServiceCallTrace? = null,
        /** Checked again immediately before the caller commits side effects. */
        val commitGuard: (() -> Boolean)? = null,
        /** Evidence IDs used by the final visible reply, persisted by the caller's diagnostics. */
        val evidenceIds: List<String> = emptyList(),
    ) : ModelServiceResult

    /**
     * A deliberate background/idle terminal state. It is not a provider
     * failure and must not be retried or sent through the expression model.
     * Foreground user-input callers must reject this state because they owe
     * the user a visible reply.
     */
    data class NoAction(
        val reason: String,
        val trace: ServiceCallTrace? = null,
        val commitGuard: (() -> Boolean)? = null,
        val evidenceIds: List<String> = emptyList(),
    ) : ModelServiceResult

    data class Failure(
        val error: AppError,
        val trace: ServiceCallTrace? = null,
    ) : ModelServiceResult
}
