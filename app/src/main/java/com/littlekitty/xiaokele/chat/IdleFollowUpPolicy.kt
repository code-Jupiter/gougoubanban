package com.littlekitty.xiaokele.chat

/**
 * Policy for the foreground "the user may not know how to continue" moment.
 * The timer starts after a completed assistant message is visible and the
 * user is at the bottom of the chat. Reading time is intentionally fixed to
 * roughly one screen so a long reply does not create an excessive delay.
 */
object IdleFollowUpPolicy {
    const val WAIT_MILLIS = 6_000L

    fun isEligible(reply: String): Boolean = reply.isNotBlank()

    fun delayMillis(@Suppress("UNUSED_PARAMETER") reply: String): Long = WAIT_MILLIS
}
