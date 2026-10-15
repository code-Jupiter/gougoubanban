package com.littlekitty.xiaokele.handoff

import com.littlekitty.xiaokele.core.database.AppDatabase

interface DeliveryEngagement {
    suspend fun onUserReplied(nowEpochMillis: Long)
}

object NoOpDeliveryEngagement : DeliveryEngagement {
    override suspend fun onUserReplied(nowEpochMillis: Long) = Unit
}

/**
 * Associates a subsequent user chat reply with the most recent assistant
 * message that originated from an event/todo delivery. The delivered row's
 * user_replied_at is written once and only when it is still null.
 */
class DeliveryEngagementTracker(
    private val db: AppDatabase,
) : DeliveryEngagement {

    override suspend fun onUserReplied(nowEpochMillis: Long) {
        val latest = db.messageDao().latestAssistantWithSource() ?: return
        db.deliveryHistoryDao().markReplied(
            eventId = latest.sourceEventId,
            todoId = latest.sourceTodoId,
            now = nowEpochMillis,
        )
    }
}
