package com.littlekitty.xiaokele.core.common.protocol

import kotlinx.serialization.json.JsonObject

/**
 * Backend-only safe mutation protocol. These commands are produced by strict
 * validation of `AgentDecision.todo_mutations` / `trigger_mutations` and are
 * applied inside one local database transaction by the backend executor.
 * They are never output actions and are never executed by the front chat.
 */
sealed interface TodoMutationCommand {
    data class Create(
        val requestedId: String?,
        val type: String,
        val title: String?,
        val payload: JsonObject?,
        val sourceEventId: String?,
        val priority: Int,
        val availableAtEpochMillis: Long?,
        val expiresAtEpochMillis: Long?,
    ) : TodoMutationCommand

    data class Update(
        val id: String,
        val title: String?,
        val payload: JsonObject?,
        val priority: Int?,
        val availableAtEpochMillis: Long?,
        val expiresAtEpochMillis: Long?,
    ) : TodoMutationCommand

    data class Cancel(val id: String) : TodoMutationCommand
}

sealed interface TriggerMutationCommand {
    data class Create(
        val requestedId: String?,
        val todoId: String,
        val triggerType: String,
        val nextFireAtEpochMillis: Long,
        val triggerConfigJson: JsonObject,
    ) : TriggerMutationCommand

    data class Update(
        val id: String,
        val nextFireAtEpochMillis: Long?,
        val triggerConfigJson: JsonObject?,
    ) : TriggerMutationCommand

    data class Cancel(val id: String) : TriggerMutationCommand
}

object MutationOperationNames {
    const val CREATE = "CREATE"
    const val UPDATE = "UPDATE"
    const val CANCEL = "CANCEL"
}

object TodoMutationOperations {
    val all: Set<String> = setOf(
        MutationOperationNames.CREATE,
        MutationOperationNames.UPDATE,
        MutationOperationNames.CANCEL,
    )
}

object TriggerMutationOperations {
    val all: Set<String> = setOf(
        MutationOperationNames.CREATE,
        MutationOperationNames.UPDATE,
        MutationOperationNames.CANCEL,
    )
}

/**
 * Development-candidate Todo types only. They are intentionally named and
 * documented as candidates; the product Todo vocabulary is not frozen yet.
 * Four product categories plus the architecture-document example value.
 */
object TodoTypeNames {
    const val PROACTIVE_MESSAGE = "PROACTIVE_MESSAGE"
    const val IN_APP_ON_OPEN = "IN_APP_ON_OPEN"
    const val CHAT_OPPORTUNISTIC = "CHAT_OPPORTUNISTIC"
    const val REEVALUATE_LATER = "REEVALUATE_LATER"
    const val DISCUSS_LATER = "DISCUSS_LATER"

    /** DEV_CANDIDATE: allowed names for backend mutation validation. */
    val candidateAll: Set<String> = setOf(
        PROACTIVE_MESSAGE,
        IN_APP_ON_OPEN,
        CHAT_OPPORTUNISTIC,
        REEVALUATE_LATER,
        DISCUSS_LATER,
    )
}

object TriggerTypeNames {
    const val TIME = "TIME"

    /** DEV_CANDIDATE: only deterministic TIME triggers are implemented in Phase 4. */
    val candidateAll: Set<String> = setOf(TIME)
}
