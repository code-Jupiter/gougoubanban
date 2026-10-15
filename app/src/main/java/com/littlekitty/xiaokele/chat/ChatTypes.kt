package com.littlekitty.xiaokele.chat

import com.littlekitty.xiaokele.core.common.error.AppError

sealed interface ChatSendOutcome {
    data class Success(
        val userMessageId: String,
        val assistantMessageId: String,
        val animationId: String?,
    ) : ChatSendOutcome

    /** Idle/proactive generation intentionally produced no visible message. */
    data class NoAction(
        val anchorMessageId: String? = null,
        val reason: String? = null,
    ) : ChatSendOutcome

    data class Failure(
        val error: AppError,
        val userMessageId: String? = null,
        /** Display-only dog speech when even the local reply could not be persisted. */
        val companionReply: String? = null,
    ) : ChatSendOutcome
}

data class ChatMessageUi(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val status: String,
    val animationId: String?,
    val retryable: Boolean,
    val isForegroundReply: Boolean = false,
    val isIdleFollowUp: Boolean = false,
    val foregroundReplyGeneration: Long = 0L,
)

data class ChatUiState(
    val messages: List<ChatMessageUi> = emptyList(),
    val animationId: String? = null,
    val animationKey: String? = null,
    val isSending: Boolean = false,
    val sendError: String? = null,
    val commandNotice: String? = null,
)
