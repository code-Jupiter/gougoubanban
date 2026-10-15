package com.littlekitty.xiaokele.handoff

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.repository.TodoClaimants
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import com.littlekitty.xiaokele.core.database.state.TodoStatuses

data class InternalHandoffRequest(
    val eventId: String?,
    val todoId: String?,
    val executionId: String? = null,
    val deliveryId: String? = null,
    val source: String? = null,
)

sealed interface InternalHandoffResult {
    data class Opened(
        val messageId: String,
        val eventId: String?,
        val todoId: String?,
        val claimedTodoId: String?,
        val createdMessage: Boolean,
        val cancelledReservations: Int = 0,
        val cancelledPendingExecutions: Int = 0,
        val cancelledFailedRetryableReach: Int = 0,
    ) : InternalHandoffResult

    data class Invalid(val reason: String, val detail: String? = null) : InternalHandoffResult
}

/**
 * Trusted-explicit-extras handoff. No public URI scheme is parsed. Every id is
 * strictly validated against [EntityId] and the local Room fact source before
 * an existing assistant message is recovered or a deterministic handoff message
 * is created in the single existing chat session.
 *
 * Repeated clicks are idempotent: the same assistant message is returned, no
 * duplicate message is inserted, and user_opened_at is only written once.
 */
class IntentHandoffManager(
    private val db: AppDatabase,
    private val timeProvider: TimeProvider,
) {

    suspend fun handle(request: InternalHandoffRequest): InternalHandoffResult {
        if (request.eventId == null && request.todoId == null) {
            return InternalHandoffResult.Invalid("MISSING_TARGET", "event_id and todo_id are both missing")
        }
        request.executionId?.let { if (EntityId.tryParse(it) == null) return invalidEntity("execution_id") }
        request.deliveryId?.let { if (EntityId.tryParse(it) == null) return invalidEntity("delivery_id") }

        val event = request.eventId?.let { raw ->
            val id = EntityId.tryParse(raw) ?: return invalidEntity("event_id")
            db.eventDao().byId(id.value)
        }
        if (request.eventId != null && event == null) {
            return InternalHandoffResult.Invalid("UNKNOWN_EVENT")
        }
        event?.let { current ->
            if (current.status !in setOf(EventStatuses.RECEIVED, EventStatuses.PROCESSING, EventStatuses.COMPLETED)) {
                return InternalHandoffResult.Invalid("EVENT_NOT_CLAIMABLE", "status=${current.status}")
            }
            val expiresAt = current.expiresAt
            if (expiresAt != null && expiresAt <= timeProvider.now().toEpochMilli()) {
                return InternalHandoffResult.Invalid("EVENT_EXPIRED")
            }
        }

        val todo = request.todoId?.let { raw ->
            val id = EntityId.tryParse(raw) ?: return invalidEntity("todo_id")
            db.todoDao().byId(id.value)
        }
        if (request.todoId != null && todo == null) {
            return InternalHandoffResult.Invalid("UNKNOWN_TODO")
        }
        todo?.let { current ->
            if (current.status !in setOf(
                    TodoStatuses.WAITING,
                    TodoStatuses.READY,
                    TodoStatuses.CLAIMED,
                    TodoStatuses.EXECUTED,
                )
            ) {
                return InternalHandoffResult.Invalid("TODO_NOT_CLAIMABLE", "status=${current.status}")
            }
            val expiresAt = current.expiresAt
            if (expiresAt != null && expiresAt <= timeProvider.now().toEpochMilli()) {
                return InternalHandoffResult.Invalid("TODO_EXPIRED")
            }
        }

        val now = timeProvider.now().toEpochMilli()
        val eventId = event?.id
        val todoId = todo?.id
        return db.withTransaction {
            val cancelledReservations = db.deliveryHistoryDao().cancelReservedReachForEventOrTodo(
                eventId = eventId,
                todoId = todoId,
                errorCode = SUPERSEDED_BY_HANDOFF,
            )
            val cancelledExecutions = db.actionExecutionDao().cancelPendingReachForEventOrTodo(
                eventId = eventId,
                todoId = todoId,
                errorCode = SUPERSEDED_BY_HANDOFF,
                now = now,
            )
            // OS-invoked RESERVED rows are deliberately not cancelled above:
            // the click/OS commit race is frozen by only cancelling rows whose
            // attempt_started_at is still NULL. Failed-but-retryable reach is a
            // known-failure row and is safe to cancel deterministically.
            // Executions must be cancelled before their delivery rows: the
            // execution query keys off FAILED+next_retry_at delivery rows, and
            // the delivery cancellation below clears exactly that state.
            val cancelledFailedRetryableExecutions =
                db.actionExecutionDao().cancelFailedRetryableReachExecutionsForEventOrTodo(
                    eventId = eventId,
                    todoId = todoId,
                    errorCode = SUPERSEDED_BY_HANDOFF,
                    now = now,
                )
            val cancelledFailedRetryableReach =
                db.deliveryHistoryDao().cancelFailedRetryableReachForEventOrTodo(
                    eventId = eventId,
                    todoId = todoId,
                    errorCode = SUPERSEDED_BY_HANDOFF,
                )
            val existing = eventId?.let { db.messageDao().assistantForEvent(it) }
                ?: todoId?.let { db.messageDao().assistantForTodo(it) }
            if (existing != null) {
                markOpened(eventId, todoId, request.deliveryId, now)
                return@withTransaction InternalHandoffResult.Opened(
                    messageId = existing.id,
                    eventId = eventId,
                    todoId = todoId,
                    claimedTodoId = existing.sourceTodoId,
                    createdMessage = false,
                    cancelledReservations = cancelledReservations,
                    cancelledPendingExecutions = cancelledExecutions,
                    cancelledFailedRetryableReach = cancelledFailedRetryableReach + cancelledFailedRetryableExecutions,
                )
            }

            val handoffMessageId = EntityId.generate("msg").value
            val claimedTodoId = todoId?.let { id ->
                if (db.todoDao().byId(id)?.status in TodoStatuses.claimable) {
                    val claimedBy = "${TodoClaimants.CHAT}:$handoffMessageId"
                    if (db.todoDao().claim(id, claimedBy, now, now, now) == 1) id else null
                } else {
                    null
                }
            }
            val entity = MessageEntity(
                id = handoffMessageId,
                role = ROLE_ASSISTANT,
                content = handoffText(todo, event),
                createdAt = now,
                sourceEventId = eventId,
                sourceTodoId = claimedTodoId ?: todoId,
                animationId = com.littlekitty.xiaokele.animation.AnimationCatalog.ID_DEFAULT_IDLE,
                status = MessageStatuses.COMPLETED,
                // A handoff happens while the user is actively engaging, so it
                // starts read; only background proactive messages start unread.
                readAt = now,
            )
            val inserted = try {
                db.messageDao().insert(entity)
                true
            } catch (_: SQLiteConstraintException) {
                false
            }
            if (inserted.not()) {
                val winner = eventId?.let { db.messageDao().assistantForEvent(it) }
                    ?: todoId?.let { db.messageDao().assistantForTodo(it) }
                markOpened(eventId, todoId, request.deliveryId, now)
                if (winner != null) {
                    return@withTransaction InternalHandoffResult.Opened(
                        messageId = winner.id,
                        eventId = eventId,
                        todoId = todoId,
                        claimedTodoId = winner.sourceTodoId,
                        createdMessage = false,
                        cancelledReservations = cancelledReservations,
                        cancelledPendingExecutions = cancelledExecutions,
                        cancelledFailedRetryableReach = cancelledFailedRetryableReach + cancelledFailedRetryableExecutions,
                    )
                }
                error("handoff message unique slot disappeared")
            }

            markOpened(eventId, todoId, request.deliveryId, now)
            InternalHandoffResult.Opened(
                messageId = handoffMessageId,
                eventId = eventId,
                todoId = todoId,
                claimedTodoId = claimedTodoId,
                createdMessage = true,
                cancelledReservations = cancelledReservations,
                cancelledPendingExecutions = cancelledExecutions,
                cancelledFailedRetryableReach = cancelledFailedRetryableReach + cancelledFailedRetryableExecutions,
            )
        }
    }

    private suspend fun markOpened(eventId: String?, todoId: String?, deliveryId: String?, now: Long) {
        db.deliveryHistoryDao().markOpened(eventId, todoId, deliveryId, now)
    }

    private fun handoffText(todo: com.littlekitty.xiaokele.core.database.entity.TodoEntity?, event: com.littlekitty.xiaokele.core.database.entity.EventEntity?): String {
        val todoSceneContent = todo?.payloadJson?.let { sceneHandoffText(it) }
            ?: todo?.title?.takeIf { it.isNotBlank() }
        val eventSceneContent = event?.payloadJson?.let { sceneHandoffText(it) }
        return todoSceneContent ?: eventSceneContent ?: "把这件事接进对话里了，在这里继续聊。"
    }

    /**
     * Scene todos carry their continuation text in payload; handoff must use
     * that scene content instead of a generic "提醒已接住" placeholder.
     */
    private fun sceneHandoffText(payloadJson: String): String? {
        val obj = runCatching { JsonCodec.decode<kotlinx.serialization.json.JsonObject>(payloadJson) }
            .getOrNull() ?: return null
        return listOf("handoff_text", "scene_text", "content", "topic").firstNotNullOfOrNull { key ->
            (obj[key] as? kotlinx.serialization.json.JsonPrimitive)
                ?.takeIf { it.isString && it.content.isNotBlank() }
                ?.content
        }
    }

    private fun invalidEntity(field: String): InternalHandoffResult.Invalid =
        InternalHandoffResult.Invalid("INVALID_ENTITY_ID", detail = field)

    companion object {
        const val ROLE_ASSISTANT = "ASSISTANT"
        const val SUPERSEDED_BY_HANDOFF = "SUPERSEDED_BY_HANDOFF"
    }
}
