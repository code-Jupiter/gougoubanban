package com.littlekitty.xiaokele.core.database.repository

import androidx.room.withTransaction
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.protocol.MutationViolation
import com.littlekitty.xiaokele.core.common.protocol.TodoMutationCommand
import com.littlekitty.xiaokele.core.common.protocol.TriggerMutationCommand
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.dao.TodoDao
import com.littlekitty.xiaokele.core.database.dao.TriggerDao
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.entity.TriggerEntity
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.TodoStateMachine
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import com.littlekitty.xiaokele.core.database.state.TriggerStateMachine
import com.littlekitty.xiaokele.core.database.state.TriggerStatuses

class MutationExecutionException(val violations: List<MutationViolation>) : IllegalStateException(
    violations.joinToString("; ") { "${it.path}: ${it.message}" },
)

data class MutationExecutionReport(
    val todosCreated: Int,
    val todosUpdated: Int,
    val todosCancelled: Int,
    val triggersCreated: Int,
    val triggersUpdated: Int,
    val triggersCancelled: Int,
    val touchedTodoIds: List<String>,
    val touchedTriggerIds: List<String>,
) {
    val isEmpty: Boolean
        get() = todosCreated == 0 && todosUpdated == 0 && todosCancelled == 0 &&
            triggersCreated == 0 && triggersUpdated == 0 && triggersCancelled == 0
}

/**
 * Transactional backend mutation executor. Structural validation has already
 * happened in core/common; this class performs reference/association checks and
 * row writes. It assumes it is called inside the caller transaction when using
 * [applyInTransaction]; the public [apply] wrapper exists for tests and direct
 * manager use.
 */
class BackgroundMutationExecutor(
    private val db: AppDatabase,
    private val todoDao: TodoDao,
    private val triggerDao: TriggerDao,
) {

    suspend fun apply(
        event: EventEntity,
        todoCommands: List<TodoMutationCommand>,
        triggerCommands: List<TriggerMutationCommand>,
        now: Long,
    ): MutationExecutionReport = db.withTransaction {
        applyInTransaction(event, todoCommands, triggerCommands, now)
    }

    suspend fun applyInTransaction(
        event: EventEntity,
        todoCommands: List<TodoMutationCommand>,
        triggerCommands: List<TriggerMutationCommand>,
        now: Long,
    ): MutationExecutionReport {
        require(event.status == EventStatuses.PROCESSING) {
            "Mutations may only be applied while the event is PROCESSING"
        }
        val violations = mutableListOf<MutationViolation>()

        var todosCreated = 0
        var todosUpdated = 0
        var todosCancelled = 0
        var triggersCreated = 0
        var triggersUpdated = 0
        var triggersCancelled = 0
        val touchedTodos = mutableListOf<String>()
        val touchedTriggers = mutableListOf<String>()

        todoCommands.forEachIndexed { index, command ->
            when (command) {
                is TodoMutationCommand.Create -> {
                    validateTodoCreate(command, event, now, index, violations)
                    if (violations.isEmpty()) {
                        val id = command.requestedId ?: EntityId.generate("todo").value
                        val availableAt = command.availableAtEpochMillis
                        val status = if (availableAt != null && availableAt > now) {
                            TodoStatuses.WAITING
                        } else {
                            TodoStatuses.READY
                        }
                        val entity = TodoEntity(
                            id = id,
                            type = command.type,
                            title = command.title,
                            payloadJson = command.payload?.toString(),
                            sourceEventId = command.sourceEventId ?: event.id,
                            status = status,
                            priority = command.priority,
                            availableAt = availableAt,
                            expiresAt = command.expiresAtEpochMillis,
                            claimedBy = null,
                            claimedAt = null,
                            createdAt = now,
                            updatedAt = now,
                        )
                        if (todoDao.insertIfAbsent(entity) == -1L) {
                            violations += MutationViolation(
                                "todo_mutations[$index].todo.id",
                                "CREATE Todo id 已存在: $id",
                            )
                        } else {
                            todosCreated++
                            touchedTodos += id
                        }
                    }
                }

                is TodoMutationCommand.Update -> {
                    val current = todoDao.byId(command.id)
                    if (current == null) {
                        violations += MutationViolation("todo_mutations[$index].todo.id", "Todo 不存在: ${command.id}")
                    } else if (current.sourceEventId != null && current.sourceEventId != event.id) {
                        violations += MutationViolation(
                            "todo_mutations[$index].todo.id",
                            "不允许跨事件修改 Todo ${command.id}（source_event_id=${current.sourceEventId}）",
                        )
                    } else if (current.status !in setOf(TodoStatuses.CANDIDATE, TodoStatuses.WAITING, TodoStatuses.READY)) {
                        violations += MutationViolation(
                            "todo_mutations[$index].todo.id",
                            "Todo ${command.id} 当前状态 ${current.status} 不允许 UPDATE",
                        )
                    } else {
                        val finalTitle = command.title ?: current.title
                        val finalPayload = command.payload?.toString() ?: current.payloadJson
                        val finalPriority = command.priority ?: current.priority
                        val finalAvailable = command.availableAtEpochMillis ?: current.availableAt
                        val finalExpires = command.expiresAtEpochMillis ?: current.expiresAt
                        val timeViolation = validateTodoTimes(finalAvailable, finalExpires, now)
                        if (timeViolation != null) {
                            violations += MutationViolation("todo_mutations[$index].todo", timeViolation)
                        } else {
                            check(
                                todoDao.updateEditableFields(
                                    id = command.id,
                                    title = finalTitle,
                                    payloadJson = finalPayload,
                                    priority = finalPriority,
                                    availableAt = finalAvailable,
                                    expiresAt = finalExpires,
                                    now = now,
                                ) == 1,
                            ) { "Todo update affected 0 rows: ${command.id}" }
                            if (current.status == TodoStatuses.WAITING && (finalAvailable == null || finalAvailable <= now)) {
                                todoDao.promoteToReadyIfAvailable(command.id, now)
                            }
                            todosUpdated++
                            touchedTodos += command.id
                        }
                    }
                }

                is TodoMutationCommand.Cancel -> {
                    val current = todoDao.byId(command.id)
                    if (current == null) {
                        violations += MutationViolation("todo_mutations[$index].todo.id", "Todo 不存在: ${command.id}")
                    } else if (current.sourceEventId != null && current.sourceEventId != event.id) {
                        violations += MutationViolation(
                            "todo_mutations[$index].todo.id",
                            "不允许跨事件取消 Todo ${command.id}",
                        )
                    } else if (runCatching { TodoStateMachine.requireTransition(current.status, TodoStatuses.CANCELLED) }.isFailure) {
                        violations += MutationViolation(
                            "todo_mutations[$index].todo.id",
                            "Todo ${command.id} 当前状态 ${current.status} 不允许 CANCELLED",
                        )
                    } else {
                        check(todoDao.transition(command.id, current.status, TodoStatuses.CANCELLED, now) == 1) {
                            "Todo cancel affected 0 rows: ${command.id}"
                        }
                        triggerDao.cancelActiveForTodo(command.id)
                        todosCancelled++
                        touchedTodos += command.id
                    }
                }
            }
        }

        if (violations.isNotEmpty()) throw MutationExecutionException(violations)

        triggerCommands.forEachIndexed { index, command ->
            when (command) {
                is TriggerMutationCommand.Create -> {
                    val todo = todoDao.byId(command.todoId)
                    if (todo == null) {
                        violations += MutationViolation(
                            "trigger_mutations[$index].trigger.todo_id",
                            "Todo 不存在: ${command.todoId}",
                        )
                    } else if (todo.sourceEventId != null && todo.sourceEventId != event.id) {
                        violations += MutationViolation(
                            "trigger_mutations[$index].trigger.todo_id",
                            "不允许为跨事件 Todo ${command.todoId} 创建 Trigger",
                        )
                    } else if (todo.status !in setOf(TodoStatuses.CANDIDATE, TodoStatuses.WAITING, TodoStatuses.READY)) {
                        violations += MutationViolation(
                            "trigger_mutations[$index].trigger.todo_id",
                            "Todo ${command.todoId} 当前状态 ${todo.status} 不允许创建 Trigger",
                        )
                    } else {
                        val id = command.requestedId ?: EntityId.generate("trg").value
                        val entity = TriggerEntity(
                            id = id,
                            todoId = command.todoId,
                            triggerType = command.triggerType,
                            triggerConfigJson = command.triggerConfigJson.toString(),
                            nextFireAt = command.nextFireAtEpochMillis,
                            lastFireAt = null,
                            status = TriggerStatuses.ACTIVE,
                        )
                        if (triggerDao.insertIfAbsent(entity) == -1L) {
                            violations += MutationViolation(
                                "trigger_mutations[$index].trigger.id",
                                "CREATE Trigger id 已存在: $id",
                            )
                        } else {
                            triggersCreated++
                            touchedTriggers += id
                        }
                    }
                }

                is TriggerMutationCommand.Update -> {
                    val current = triggerDao.byId(command.id)
                    if (current == null) {
                        violations += MutationViolation("trigger_mutations[$index].trigger.id", "Trigger 不存在: ${command.id}")
                    } else if (current.status != TriggerStatuses.ACTIVE) {
                        violations += MutationViolation(
                            "trigger_mutations[$index].trigger.id",
                            "Trigger ${command.id} 当前状态 ${current.status} 不允许 UPDATE",
                        )
                    } else {
                        val nextFireAt = command.nextFireAtEpochMillis
                        val configJson = command.triggerConfigJson
                        if (nextFireAt == null || configJson == null) {
                            violations += MutationViolation(
                                "trigger_mutations[$index].trigger.trigger_config_json",
                                "UPDATE 必须提供 trigger_config_json",
                            )
                        } else {
                            check(
                                triggerDao.updateSchedule(
                                    id = command.id,
                                    triggerConfigJson = configJson.toString(),
                                    nextFireAt = nextFireAt,
                                ) == 1,
                            ) { "Trigger schedule update affected 0 rows: ${command.id}" }
                            triggersUpdated++
                            touchedTriggers += command.id
                        }
                    }
                }

                is TriggerMutationCommand.Cancel -> {
                    val current = triggerDao.byId(command.id)
                    if (current == null) {
                        violations += MutationViolation("trigger_mutations[$index].trigger.id", "Trigger 不存在: ${command.id}")
                    } else if (runCatching { TriggerStateMachine.requireTransition(current.status, TriggerStatuses.CANCELLED) }.isFailure) {
                        violations += MutationViolation(
                            "trigger_mutations[$index].trigger.id",
                            "Trigger ${command.id} 当前状态 ${current.status} 不允许 CANCELLED",
                        )
                    } else {
                        check(triggerDao.transition(command.id, current.status, TriggerStatuses.CANCELLED) == 1) {
                            "Trigger cancel affected 0 rows: ${command.id}"
                        }
                        triggersCancelled++
                        touchedTriggers += command.id
                    }
                }
            }
        }

        if (violations.isNotEmpty()) throw MutationExecutionException(violations)

        return MutationExecutionReport(
            todosCreated = todosCreated,
            todosUpdated = todosUpdated,
            todosCancelled = todosCancelled,
            triggersCreated = triggersCreated,
            triggersUpdated = triggersUpdated,
            triggersCancelled = triggersCancelled,
            touchedTodoIds = touchedTodos,
            touchedTriggerIds = touchedTriggers,
        )
    }

    private fun validateTodoCreate(
        command: TodoMutationCommand.Create,
        event: EventEntity,
        now: Long,
        index: Int,
        violations: MutableList<MutationViolation>,
    ) {
        val timeViolation = validateTodoTimes(command.availableAtEpochMillis, command.expiresAtEpochMillis, now)
        if (timeViolation != null) {
            violations += MutationViolation("todo_mutations[$index].todo", timeViolation)
        }
        if (command.sourceEventId != null && command.sourceEventId != event.id) {
            violations += MutationViolation(
                "todo_mutations[$index].todo.source_event_id",
                "CREATE source_event_id 必须等于当前 event_id: ${event.id}",
            )
        }
    }

    private fun validateTodoTimes(availableAt: Long?, expiresAt: Long?, now: Long): String? = when {
        expiresAt != null && expiresAt <= now -> "expires_at 必须晚于当前时间"
        availableAt != null && expiresAt != null && expiresAt <= availableAt -> "expires_at 必须晚于 available_at"
        else -> null
    }
}
