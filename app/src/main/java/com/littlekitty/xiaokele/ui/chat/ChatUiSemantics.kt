package com.littlekitty.xiaokele.ui.chat

import com.littlekitty.xiaokele.chat.FrontChatPipeline
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Frozen product-facing role semantics for the chat UI.
 *
 * USER = the real user; COMPANION = the virtual dog character living in
 * the app. USER rows always belong to the user and ASSISTANT rows always belong
 * to the companion. The persisted role strings and protocol constants stay unchanged;
 * only UI presentation uses these mappings.
 */
enum class ChatMessageAuthor {
    USER,
    COMPANION,
    UNKNOWN,
}

object ChatUiSemantics {

    /** WeChat/QQ-style grouping: only show a timestamp after a five-minute gap. */
    const val TIMESTAMP_GAP_MILLIS = 5 * 60 * 1000L

    fun authorForRole(role: String): ChatMessageAuthor = when (role) {
        FrontChatPipeline.ROLE_USER -> ChatMessageAuthor.USER
        FrontChatPipeline.ROLE_ASSISTANT -> ChatMessageAuthor.COMPANION
        else -> ChatMessageAuthor.UNKNOWN
    }

    fun isUserMessage(role: String): Boolean =
        role == FrontChatPipeline.ROLE_USER

    /** A new user message is allowed while an earlier attempt is generating. */
    fun canSend(input: String, isSending: Boolean): Boolean = input.isNotBlank()

    /**
     * SENDING is a persisted delivery/reply lifecycle state. It is presented
     * as the companion preparing a reply, never as an unresolved user bubble.
     */
    fun shouldShowCompanionReplyingIndicator(isSending: Boolean): Boolean = isSending

    fun shouldShowTimestamp(previousCreatedAt: Long?, currentCreatedAt: Long): Boolean =
        previousCreatedAt == null ||
            abs(currentCreatedAt - previousCreatedAt) >= TIMESTAMP_GAP_MILLIS

    /** Formats timestamps relative to today while keeping older history clear. */
    fun formatTimestamp(
        timestamp: Long,
        nowEpochMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val dateTime = Instant.ofEpochMilli(timestamp).atZone(zoneId)
        val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
        val time = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
        return when {
            dateTime.toLocalDate() == today -> dateTime.format(time)
            dateTime.toLocalDate() == today.minusDays(1) -> "昨天 ${dateTime.format(time)}"
            dateTime.year == today.year -> dateTime.format(
                DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA),
            )
            else -> dateTime.format(
                DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA),
            )
        }
    }
}
