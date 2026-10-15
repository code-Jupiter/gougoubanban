package com.littlekitty.xiaokele.core.database.repository

import androidx.room.withTransaction
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.dao.TodoDao
import com.littlekitty.xiaokele.core.database.dao.TriggerDao
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.entity.TriggerEntity
import com.littlekitty.xiaokele.core.database.state.TodoStateMachine
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import com.littlekitty.xiaokele.core.database.state.TriggerStateMachine
import com.littlekitty.xiaokele.core.database.state.TriggerStatuses

/**
 * Phase 4 Todo manager. The atomic DAO claim remains the single concurrency
 * primitive for both chat and background claimants; every state change is
 * validated by [TodoStateMachine] before a write.
 */
class TodoManager(
    private val db: AppDatabase,
    private val dao: TodoDao,
    private val triggerDao: TriggerDao? = null,
) {

    suspend fun byId(id: String): TodoEntity? = dao.byId(id)

    suspend fun byStatuses(statuses: List<String>): List<TodoEntity> = dao.byStatuses(statuses)

    suspend fun create(
        type: String,
        now: Long,
        title: String? = null,
        payloadJson: String? = null,
        sourceEventId: String? = null,
        priority: Int = 0,
        availableAt: Long? = null,
        expiresAt: Long? = null,
        initialStatus: String? = null,
        id: String = EntityId.generate("todo").value,
    ): TodoEntity = db.withTransaction {
        require(type.isNotBlank()) { "Todo type must not be blank" }
        require(priority in 0..100) { "Todo priority must be in 0..100" }
        require(expiresAt == null || expiresAt > now) { "New todo must not be created already expired" }
        require(availableAt == null || expiresAt == null || expiresAt > availableAt) {
            "Todo expires_at must be later than available_at"
        }
        val status = initialStatus ?: if (availableAt != null && availableAt > now) {
            TodoStatuses.WAITING
        } else {
            TodoStatuses.READY
        }
        require(status in TodoStatuses.claimable + TodoStatuses.CANDIDATE) {
            "New todos must start in CANDIDATE, WAITING or READY"
        }
        val entity = TodoEntity(
            id = id,
            type = type,
            title = title,
            payloadJson = payloadJson,
            sourceEventId = sourceEventId,
            status = status,
            priority = priority,
            availableAt = availableAt,
            expiresAt = expiresAt,
            claimedBy = null,
            claimedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(entity)
        entity
    }

    /** Atomic single-use claim (FR-TODO-05). Returns null when already claimed/expired. */
    suspend fun claim(id: String, claimedBy: String, now: Long): TodoEntity? = db.withTransaction {
        if (dao.claim(id, claimedBy, now, now, now) == 1) dao.byId(id) else null
    }

    suspend fun releaseClaim(id: String, now: Long): TodoEntity = transition(id, TodoStatuses.READY, now)

    suspend fun markExecuted(id: String, now: Long): TodoEntity = transition(id, TodoStatuses.EXECUTED, now)

    suspend fun cancel(id: String, now: Long): TodoEntity = transition(id, TodoStatuses.CANCELLED, now)

    suspend fun fail(id: String, now: Long): TodoEntity = transition(id, TodoStatuses.FAILED, now)

    suspend fun expire(id: String, now: Long): TodoEntity = transition(id, TodoStatuses.EXPIRED, now)

    suspend fun updateEditable(
        id: String,
        now: Long,
        title: String? = null,
        payloadJson: String? = null,
        priority: Int? = null,
        availableAt: Long? = null,
        expiresAt: Long? = null,
        useCurrentAsDefault: Boolean = true,
    ): TodoEntity = db.withTransaction {
        val current = dao.byId(id) ?: error("Unknown todo: $id")
        require(current.status in setOf(TodoStatuses.CANDIDATE, TodoStatuses.WAITING, TodoStatuses.READY)) {
            "Only non-claimed, non-terminal todos are editable"
        }
        val finalTitle = if (useCurrentAsDefault) title ?: current.title else title
        val finalPayload = if (useCurrentAsDefault) payloadJson ?: current.payloadJson else payloadJson
        val finalPriority = priority ?: current.priority
        val finalAvailable = if (useCurrentAsDefault) availableAt ?: current.availableAt else availableAt
        val finalExpires = if (useCurrentAsDefault) expiresAt ?: current.expiresAt else expiresAt
        require(finalExpires == null || finalExpires > now) { "Updated todo must not be already expired" }
        require(finalAvailable == null || finalExpires == null || finalExpires > finalAvailable) {
            "Updated todo expires_at must be later than available_at"
        }
        check(
            dao.updateEditableFields(
                id = id,
                title = finalTitle,
                payloadJson = finalPayload,
                priority = finalPriority,
                availableAt = finalAvailable,
                expiresAt = finalExpires,
                now = now,
            ) == 1,
        ) { "Todo update affected 0 rows" }
        if (current.status == TodoStatuses.WAITING && (finalAvailable == null || finalAvailable <= now)) {
            dao.promoteToReadyIfAvailable(id, now)
        }
        dao.byId(id) ?: error("Todo disappeared during update")
    }

    suspend fun promoteDueWaiting(now: Long): Int = db.withTransaction {
        dao.waitingAvailable(now).count { dao.promoteToReadyIfAvailable(it.id, now) == 1 }
    }

    suspend fun expireDueNonClaimed(now: Long): Int = dao.expireDueNonClaimed(now)

    private suspend fun transition(id: String, newStatus: String, now: Long): TodoEntity = db.withTransaction {
        val current = dao.byId(id) ?: error("Unknown todo: $id")
        TodoStateMachine.requireTransition(current.status, newStatus)
        check(dao.transition(id, current.status, newStatus, now) == 1) {
            "Todo transition affected 0 rows: $id"
        }
        when (newStatus) {
            TodoStatuses.EXECUTED -> triggerDao?.completeActiveForTodo(id)
            TodoStatuses.CANCELLED, TodoStatuses.EXPIRED, TodoStatuses.FAILED -> triggerDao?.cancelActiveForTodo(id)
        }
        dao.byId(id) ?: error("Todo disappeared during transition: $id")
    }
}

/**
 * Phase 4 Trigger manager. Triggers only re-enter decision; firing never
 * directly sends anything to the user (FR-TODO-04).
 */
class TriggerManager(
    private val db: AppDatabase,
    private val dao: TriggerDao,
) {

    suspend fun byId(id: String): TriggerEntity? = dao.byId(id)

    suspend fun forTodo(todoId: String): List<TriggerEntity> = dao.forTodo(todoId)

    suspend fun due(now: Long, notBefore: Long = Long.MIN_VALUE, limit: Int = Int.MAX_VALUE): List<TriggerEntity> =
        dao.activeDue(now, notBefore, limit)

    suspend fun byFired(limit: Int = Int.MAX_VALUE): List<TriggerEntity> = dao.fired(limit)

    suspend fun hasFired(): Boolean = dao.firedCount() > 0

    suspend fun earliestActiveFireAt(): Long? = dao.earliestActiveFireAt()

    suspend fun create(
        todoId: String,
        triggerType: String,
        triggerConfigJson: String?,
        nextFireAt: Long,
        id: String = EntityId.generate("trg").value,
    ): TriggerEntity = db.withTransaction {
        require(todoId.isNotBlank()) { "Trigger todo_id must not be blank" }
        require(triggerType.isNotBlank()) { "Trigger type must not be blank" }
        require(nextFireAt > 0) { "Trigger next_fire_at must be positive" }
        val entity = TriggerEntity(
            id = id,
            todoId = todoId,
            triggerType = triggerType,
            triggerConfigJson = triggerConfigJson,
            nextFireAt = nextFireAt,
            lastFireAt = null,
            status = TriggerStatuses.ACTIVE,
        )
        dao.upsert(entity)
        entity
    }

    /**
     * Atomic due-fire: exactly one caller can transition ACTIVE -> FIRED for a
     * due trigger. Returns null when another caller won the race.
     */
    suspend fun fireDue(id: String, now: Long, notBefore: Long = Long.MIN_VALUE): TriggerEntity? = db.withTransaction {
        if (dao.fireDue(id, now, notBefore) == 1) dao.byId(id) else null
    }

    suspend fun expireActiveAtOrBefore(cutoff: Long): Int = dao.expireActiveAtOrBefore(cutoff)

    suspend fun expireFiredAtOrBefore(cutoff: Long): Int = dao.expireFiredAtOrBefore(cutoff)

    suspend fun completeFired(id: String): TriggerEntity = transition(id, TriggerStatuses.COMPLETED)

    suspend fun cancel(id: String): TriggerEntity = transition(id, TriggerStatuses.CANCELLED)

    suspend fun expire(id: String): TriggerEntity = transition(id, TriggerStatuses.EXPIRED)

    suspend fun updateSchedule(
        id: String,
        triggerConfigJson: String,
        nextFireAt: Long,
        now: Long,
    ): TriggerEntity = db.withTransaction {
        require(nextFireAt > now) { "Trigger next_fire_at must be in the future" }
        val current = dao.byId(id) ?: error("Unknown trigger: $id")
        require(current.status == TriggerStatuses.ACTIVE) { "Only ACTIVE triggers can be rescheduled" }
        check(dao.updateSchedule(id, triggerConfigJson, nextFireAt) == 1) {
            "Trigger schedule update affected 0 rows"
        }
        dao.byId(id) ?: error("Trigger disappeared during schedule update")
    }

    private suspend fun transition(id: String, newStatus: String): TriggerEntity = db.withTransaction {
        val current = dao.byId(id) ?: error("Unknown trigger: $id")
        TriggerStateMachine.requireTransition(current.status, newStatus)
        check(dao.transition(id, current.status, newStatus) == 1) {
            "Trigger transition affected 0 rows: $id"
        }
        dao.byId(id) ?: error("Trigger disappeared during transition: $id")
    }
}

/**
 * Semantic claimant names. The atomic claim in [TodoManager] is the only way to
 * obtain a todo, so chat insertion and background delivery can never both win.
 */
object TodoClaimants {
    const val CHAT = "chat"
    const val BACKGROUND = "background"
}

class TodoClaimCoordinator(
    private val todoManager: TodoManager,
) {
    suspend fun claimForChat(todoId: String, messageId: String, now: Long): TodoEntity? =
        todoManager.claim(todoId, "${TodoClaimants.CHAT}:$messageId", now)

    suspend fun claimForBackground(todoId: String, eventId: String, now: Long): TodoEntity? =
        todoManager.claim(todoId, "${TodoClaimants.BACKGROUND}:$eventId", now)
}
