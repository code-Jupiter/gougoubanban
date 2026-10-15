package com.littlekitty.xiaokele.core.common.protocol

/**
 * I-02 Phase 4 freeze: one unified Action registry/enum for both channels.
 *
 * Output channel execution is intentionally split from schema legality:
 * [frontChatAllowed] and [backgroundAllowed] are independent allowlists.
 * The front-chat channel never executes a background-only action, and the
 * background channel never executes REPLY_CHAT.
 *
 * `todo_mutations` / `trigger_mutations` are NOT actions. They are safe
 * backend mutations carried beside AgentDecision and must only be applied by
 * the backend mutation transaction executor.
 */
object AgentActionRegistry {

    const val REPLY_CHAT = "REPLY_CHAT"
    const val SEND_PROACTIVE_MESSAGE = "SEND_PROACTIVE_MESSAGE"
    const val SEND_NOTIFICATION = "SEND_NOTIFICATION"
    const val SET_WIDGET_STATE = "SET_WIDGET_STATE"
    const val PLAY_ANIMATION = "PLAY_ANIMATION"
    const val UPDATE_PET_STATE = "UPDATE_PET_STATE"

    /** Architecture section 31 output action names, no todo/trigger pseudo-actions. */
    val all: Set<String> = setOf(
        REPLY_CHAT,
        SEND_PROACTIVE_MESSAGE,
        SEND_NOTIFICATION,
        SET_WIDGET_STATE,
        PLAY_ANIMATION,
        UPDATE_PET_STATE,
    )

    /** Front chat channel may execute reply and pet-state updates only. */
    val frontChatAllowed: Set<String> = setOf(REPLY_CHAT, UPDATE_PET_STATE)

    /** Background channel may select every non-chat-reply output. */
    val backgroundAllowed: Set<String> = setOf(
        SEND_PROACTIVE_MESSAGE,
        SEND_NOTIFICATION,
        SET_WIDGET_STATE,
        PLAY_ANIMATION,
        UPDATE_PET_STATE,
    )

    /** Phase 4 may really execute these; later-phase channels are deferred safely. */
    val phase4ExecutableBackground: Set<String> = setOf(UPDATE_PET_STATE)

    /** Deferred Phase 5 output channels. */
    val deferredToPhase5: Set<String> = backgroundAllowed - phase4ExecutableBackground
}
