package com.littlekitty.xiaokele.core.common.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * AgentDecision wire shape, aligned with architecture section 31.
 *
 * I-02 Phase 4 freeze:
 * - [AgentActionRegistry] is the single action-name source for both channels.
 * - Todo and Trigger changes travel in the separate [todoMutations] /
 *   [triggerMutations] arrays and are backend safe mutations, never actions.
 * - `NO_ACTION` is a decision, not an output action.
 */
@Serializable
data class AgentDecision(
    val decision: String,
    @SerialName("decision_summary")
    val decisionSummary: String? = null,
    val outputs: List<AgentOutput> = emptyList(),
    @SerialName("todo_mutations")
    val todoMutations: List<TodoMutation> = emptyList(),
    @SerialName("trigger_mutations")
    val triggerMutations: List<TriggerMutation> = emptyList(),
    @SerialName("animation_id")
    val animationId: String? = null,
)

@Serializable
data class AgentOutput(
    val action: String,
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class TodoMutation(
    val operation: String,
    val todo: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class TriggerMutation(
    val operation: String,
    val trigger: JsonObject = JsonObject(emptyMap()),
)

object DecisionNames {
    const val NO_ACTION = "NO_ACTION"
    const val CREATE_PLAN = "CREATE_PLAN"

    val all = setOf(NO_ACTION, CREATE_PLAN)
}

/** Kept as compatibility aliases; the frozen source of truth is [AgentActionRegistry]. */
object AgentActionNames {
    const val REPLY_CHAT = AgentActionRegistry.REPLY_CHAT
    const val SEND_PROACTIVE_MESSAGE = AgentActionRegistry.SEND_PROACTIVE_MESSAGE
    const val SEND_NOTIFICATION = AgentActionRegistry.SEND_NOTIFICATION
    const val SET_WIDGET_STATE = AgentActionRegistry.SET_WIDGET_STATE
    const val PLAY_ANIMATION = AgentActionRegistry.PLAY_ANIMATION
    const val UPDATE_PET_STATE = AgentActionRegistry.UPDATE_PET_STATE

    val allowed: Set<String> = AgentActionRegistry.all
    val executedInPhase2: Set<String> = AgentActionRegistry.frontChatAllowed
}
