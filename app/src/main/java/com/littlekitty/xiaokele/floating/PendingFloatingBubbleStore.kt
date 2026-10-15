package com.littlekitty.xiaokele.floating

import android.content.Context

/**
 * Small durable handoff between background scene processing and the next
 * screen-unlock event. Only the highest-priority pending bubble is retained so
 * several background workers cannot create a burst of messages.
 */
data class PendingFloatingBubble(
    val text: String,
    val eventId: String?,
    val priority: Int,
    val expiresAt: Long,
)

class PendingFloatingBubbleStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enqueue(candidate: PendingFloatingBubble, now: Long = System.currentTimeMillis()) {
        synchronized(PROCESS_LOCK) {
            if (candidate.text.isBlank() || candidate.expiresAt <= now) return
            val current = read()
            if (current != null && current.expiresAt > now && current.priority > candidate.priority) return
            prefs.edit()
                .putString(KEY_TEXT, candidate.text.take(MAX_TEXT_LENGTH))
                .putString(KEY_EVENT_ID, candidate.eventId)
                .putInt(KEY_PRIORITY, candidate.priority)
                .putLong(KEY_EXPIRES_AT, candidate.expiresAt)
                .commit()
        }
    }

    fun consumeIfValid(now: Long = System.currentTimeMillis()): PendingFloatingBubble? = synchronized(PROCESS_LOCK) {
        val current = read()
        clearLocked()
        current?.takeIf { it.text.isNotBlank() && it.expiresAt > now }
    }

    fun hasValidPending(now: Long = System.currentTimeMillis()): Boolean = synchronized(PROCESS_LOCK) {
        read()?.let { it.text.isNotBlank() && it.expiresAt > now } == true
    }

    fun clear() {
        synchronized(PROCESS_LOCK) { clearLocked() }
    }

    private fun clearLocked() {
        prefs.edit().clear().commit()
    }

    private fun read(): PendingFloatingBubble? {
        val text = prefs.getString(KEY_TEXT, null) ?: return null
        return PendingFloatingBubble(
            text = text,
            eventId = prefs.getString(KEY_EVENT_ID, null),
            priority = prefs.getInt(KEY_PRIORITY, 0),
            expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L),
        )
    }

    companion object {
        /** Multiple production call sites create stores; the queue lock must be process-wide. */
        private val PROCESS_LOCK = Any()
        private const val PREFS_NAME = "little_kitty_pending_floating_bubble"
        private const val KEY_TEXT = "text"
        private const val KEY_EVENT_ID = "event_id"
        private const val KEY_PRIORITY = "priority"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val MAX_TEXT_LENGTH = 96
    }
}
