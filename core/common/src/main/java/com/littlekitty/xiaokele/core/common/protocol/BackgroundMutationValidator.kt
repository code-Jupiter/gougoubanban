package com.littlekitty.xiaokele.core.common.protocol

import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.time.InstantJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Duration

data class MutationViolation(
    val path: String,
    val message: String,
)

sealed interface TodoMutationValidation {
    data class Valid(val commands: List<TodoMutationCommand>) : TodoMutationValidation
    data class Invalid(val violations: List<MutationViolation>) : TodoMutationValidation
}

sealed interface TriggerMutationValidation {
    data class Valid(val commands: List<TriggerMutationCommand>) : TriggerMutationValidation
    data class Invalid(val violations: List<MutationViolation>) : TriggerMutationValidation
}

/**
 * Deterministic structural validators for backend mutation payloads (C4).
 * They validate field presence/types, candidate enum names, ISO-8601 instants,
 * duration syntax, id syntax and time ordering before any row is touched.
 * Cross-row references (existing todo/trigger rows and event association) are
 * validated later inside the backend mutation transaction.
 */
object TodoMutationValidator {

    fun validate(
        mutations: List<TodoMutation>,
        currentEventId: String,
        nowEpochMillis: Long? = null,
    ): TodoMutationValidation {
        val violations = mutableListOf<MutationViolation>()
        val commands = mutableListOf<TodoMutationCommand>()

        mutations.forEachIndexed { index, mutation ->
            val path = "todo_mutations[$index]"
            if (TodoMutationOperations.all.contains(mutation.operation).not()) {
                violations += MutationViolation("$path.operation", "未知 todo mutation operation: ${mutation.operation}")
                return@forEachIndexed
            }
            val todo = mutation.todo
            when (mutation.operation) {
                MutationOperationNames.CREATE -> {
                    rejectForbidden(
                        todo = todo,
                        path = path,
                        forbidden = setOf("status", "claimed_by", "claimed_at", "created_at", "updated_at"),
                        violations = violations,
                    )

                    val type = requiredString(todo, "type", "$path.todo.type", violations) ?: ""
                    if (type.isNotBlank() && TodoTypeNames.candidateAll.contains(type).not()) {
                        violations += MutationViolation("$path.todo.type", "未知候选 Todo type: $type")
                    }
                    val requestedId = optionalString(todo, "id", "$path.todo.id", violations)
                    if (requestedId != null && EntityId.isValidForPrefix(requestedId, "todo").not()) {
                        violations += MutationViolation("$path.todo.id", "非法 Todo id: $requestedId")
                    }
                    val sourceEventId = optionalString(todo, "source_event_id", "$path.todo.source_event_id", violations)
                    if (sourceEventId != null && sourceEventId != currentEventId) {
                        violations += MutationViolation(
                            "$path.todo.source_event_id",
                            "CREATE source_event_id 必须等于当前 event_id: $currentEventId",
                        )
                    }
                    val title = optionalString(todo, "title", "$path.todo.title", violations)
                    val payload = optionalObject(todo, "payload", "$path.todo.payload", violations)
                    val priority = optionalInt(todo, "priority", "$path.todo.priority", violations) ?: 0
                    if (priority < NormalizedEventFields.PRIORITY_MIN || priority > NormalizedEventFields.PRIORITY_MAX) {
                        violations += MutationViolation("$path.todo.priority", "priority 必须在 0..100")
                    }
                    val availableAt = optionalInstant(todo, "available_at", "$path.todo.available_at", violations)
                    val expiresAt = optionalInstant(todo, "expires_at", "$path.todo.expires_at", violations)
                    if (nowEpochMillis != null && expiresAt != null && expiresAt <= nowEpochMillis) {
                        violations += MutationViolation("$path.todo.expires_at", "CREATE expires_at 必须晚于当前时间")
                    }
                    if (availableAt != null && expiresAt != null && expiresAt <= availableAt) {
                        violations += MutationViolation("$path.todo.expires_at", "expires_at 必须晚于 available_at")
                    }
                    if (hasAnyViolation(violations, path).not()) {
                        commands += TodoMutationCommand.Create(
                            requestedId = requestedId,
                            type = type,
                            title = title,
                            payload = payload,
                            sourceEventId = sourceEventId,
                            priority = priority,
                            availableAtEpochMillis = availableAt,
                            expiresAtEpochMillis = expiresAt,
                        )
                    }
                }

                MutationOperationNames.UPDATE -> {
                    val id = requiredString(todo, "id", "$path.todo.id", violations)
                    if (id != null && EntityId.isValidForPrefix(id, "todo").not()) {
                        violations += MutationViolation("$path.todo.id", "非法 Todo id: $id")
                    }
                    rejectForbidden(
                        todo = todo,
                        path = path,
                        forbidden = setOf(
                            "type", "status", "source_event_id", "claimed_by", "claimed_at",
                            "created_at", "updated_at",
                        ),
                        violations = violations,
                    )
                    val editableKeys = todo.keys.intersect(setOf("title", "payload", "priority", "available_at", "expires_at"))
                    if (editableKeys.isEmpty()) {
                        violations += MutationViolation("$path.todo", "UPDATE 至少需要提供一个可编辑字段")
                    }
                    val title = optionalString(todo, "title", "$path.todo.title", violations)
                    val payload = optionalObject(todo, "payload", "$path.todo.payload", violations)
                    val priority = optionalInt(todo, "priority", "$path.todo.priority", violations)
                    if (priority != null && (priority < NormalizedEventFields.PRIORITY_MIN || priority > NormalizedEventFields.PRIORITY_MAX)) {
                        violations += MutationViolation("$path.todo.priority", "priority 必须在 0..100")
                    }
                    val availableAt = optionalInstant(todo, "available_at", "$path.todo.available_at", violations)
                    val expiresAt = optionalInstant(todo, "expires_at", "$path.todo.expires_at", violations)
                    if (nowEpochMillis != null && expiresAt != null && expiresAt <= nowEpochMillis) {
                        violations += MutationViolation("$path.todo.expires_at", "UPDATE expires_at 必须晚于当前时间")
                    }
                    if (availableAt != null && expiresAt != null && expiresAt <= availableAt) {
                        violations += MutationViolation("$path.todo.expires_at", "expires_at 必须晚于 available_at")
                    }
                    if (id != null && hasAnyViolation(violations, path).not()) {
                        commands += TodoMutationCommand.Update(
                            id = id,
                            title = title,
                            payload = payload,
                            priority = priority,
                            availableAtEpochMillis = availableAt,
                            expiresAtEpochMillis = expiresAt,
                        )
                    }
                }

                MutationOperationNames.CANCEL -> {
                    val id = requiredString(todo, "id", "$path.todo.id", violations)
                    if (id != null && EntityId.isValidForPrefix(id, "todo").not()) {
                        violations += MutationViolation("$path.todo.id", "非法 Todo id: $id")
                    }
                    rejectForbidden(todo = todo, path = path, forbidden = todo.keys - setOf("id"), violations = violations)
                    if (id != null && hasAnyViolation(violations, path).not()) {
                        commands += TodoMutationCommand.Cancel(id)
                    }
                }
            }
        }

        return if (violations.isEmpty()) {
            TodoMutationValidation.Valid(commands)
        } else {
            TodoMutationValidation.Invalid(violations)
        }
    }

    private fun rejectForbidden(
        todo: JsonObject,
        path: String,
        forbidden: Set<String>,
        violations: MutableList<MutationViolation>,
    ) {
        todo.keys.intersect(forbidden).forEach { key ->
            violations += MutationViolation("$path.todo.$key", "不允许设置保留字段 $key")
        }
    }

    private fun hasAnyViolation(violations: List<MutationViolation>, path: String): Boolean =
        violations.any { it.path.startsWith(path) }
}

object TriggerMutationValidator {

    fun validate(
        mutations: List<TriggerMutation>,
        nowEpochMillis: Long? = null,
    ): TriggerMutationValidation {
        val violations = mutableListOf<MutationViolation>()
        val commands = mutableListOf<TriggerMutationCommand>()

        mutations.forEachIndexed { index, mutation ->
            val path = "trigger_mutations[$index]"
            if (TriggerMutationOperations.all.contains(mutation.operation).not()) {
                violations += MutationViolation("$path.operation", "未知 trigger mutation operation: ${mutation.operation}")
                return@forEachIndexed
            }
            val trigger = mutation.trigger
            when (mutation.operation) {
                MutationOperationNames.CREATE -> {
                    rejectForbidden(
                        trigger = trigger,
                        path = path,
                        forbidden = setOf("status", "last_fire_at", "next_fire_at"),
                        violations = violations,
                    )
                    val requestedId = optionalString(trigger, "id", "$path.trigger.id", violations)
                    if (requestedId != null && EntityId.isValidForPrefix(requestedId, "trg").not()) {
                        violations += MutationViolation("$path.trigger.id", "非法 Trigger id: $requestedId")
                    }
                    val todoId = requiredString(trigger, "todo_id", "$path.trigger.todo_id", violations)
                    if (todoId != null && EntityId.isValidForPrefix(todoId, "todo").not()) {
                        violations += MutationViolation("$path.trigger.todo_id", "非法 todo_id: $todoId")
                    }
                    val triggerType = requiredString(trigger, "trigger_type", "$path.trigger.trigger_type", violations) ?: ""
                    if (triggerType.isNotBlank() && TriggerTypeNames.candidateAll.contains(triggerType).not()) {
                        violations += MutationViolation("$path.trigger.trigger_type", "未知候选 trigger_type: $triggerType")
                    }
                    val config = optionalObject(trigger, "trigger_config_json", "$path.trigger.trigger_config_json", violations)
                    val nextFireAt = parseNextFireAt(config, "$path.trigger.trigger_config_json", nowEpochMillis, violations)
                    if (nowEpochMillis != null && nextFireAt != null && nextFireAt <= nowEpochMillis) {
                        violations += MutationViolation(
                            "$path.trigger.trigger_config_json",
                            "Trigger 到期时间必须晚于当前时间",
                        )
                    }
                    if (todoId != null && hasAnyViolation(violations, path).not()) {
                        commands += TriggerMutationCommand.Create(
                            requestedId = requestedId,
                            todoId = todoId,
                            triggerType = triggerType,
                            nextFireAtEpochMillis = nextFireAt ?: Long.MIN_VALUE,
                            triggerConfigJson = config ?: JsonObject(emptyMap()),
                        )
                    }
                }

                MutationOperationNames.UPDATE -> {
                    val id = requiredString(trigger, "id", "$path.trigger.id", violations)
                    if (id != null && EntityId.isValidForPrefix(id, "trg").not()) {
                        violations += MutationViolation("$path.trigger.id", "非法 Trigger id: $id")
                    }
                    rejectForbidden(
                        trigger = trigger,
                        path = path,
                        forbidden = setOf("todo_id", "trigger_type", "status", "last_fire_at", "next_fire_at"),
                        violations = violations,
                    )
                    val config = optionalObject(trigger, "trigger_config_json", "$path.trigger.trigger_config_json", violations)
                    if (config == null && trigger.keys.contains("trigger_config_json").not()) {
                        violations += MutationViolation("$path.trigger.trigger_config_json", "UPDATE 必须提供 trigger_config_json")
                    }
                    val nextFireAt = if (config == null) {
                        null
                    } else {
                        parseNextFireAt(config, "$path.trigger.trigger_config_json", nowEpochMillis, violations)
                    }
                    if (nowEpochMillis != null && nextFireAt != null && nextFireAt <= nowEpochMillis) {
                        violations += MutationViolation(
                            "$path.trigger.trigger_config_json",
                            "Trigger 到期时间必须晚于当前时间",
                        )
                    }
                    if (id != null && hasAnyViolation(violations, path).not()) {
                        commands += TriggerMutationCommand.Update(
                            id = id,
                            nextFireAtEpochMillis = nextFireAt,
                            triggerConfigJson = config,
                        )
                    }
                }

                MutationOperationNames.CANCEL -> {
                    val id = requiredString(trigger, "id", "$path.trigger.id", violations)
                    if (id != null && EntityId.isValidForPrefix(id, "trg").not()) {
                        violations += MutationViolation("$path.trigger.id", "非法 Trigger id: $id")
                    }
                    rejectForbidden(
                        trigger = trigger,
                        path = path,
                        forbidden = trigger.keys - setOf("id"),
                        violations = violations,
                    )
                    if (id != null && hasAnyViolation(violations, path).not()) {
                        commands += TriggerMutationCommand.Cancel(id)
                    }
                }
            }
        }

        return if (violations.isEmpty()) {
            TriggerMutationValidation.Valid(commands)
        } else {
            TriggerMutationValidation.Invalid(violations)
        }
    }

    private fun rejectForbidden(
        trigger: JsonObject,
        path: String,
        forbidden: Set<String>,
        violations: MutableList<MutationViolation>,
    ) {
        trigger.keys.intersect(forbidden).forEach { key ->
            violations += MutationViolation("$path.trigger.$key", "不允许设置保留字段 $key")
        }
    }

    private fun parseNextFireAt(
        config: JsonObject?,
        path: String,
        nowEpochMillis: Long?,
        violations: MutableList<MutationViolation>,
    ): Long? {
        if (config == null) {
            violations += MutationViolation(path, "trigger_config_json 不能为空")
            return null
        }
        val hasFireAt = config.containsKey("fire_at")
        val hasDelay = config.containsKey("delay")
        if (hasFireAt == hasDelay) {
            violations += MutationViolation(path, "trigger_config_json 必须且只能包含 fire_at 或 delay 之一")
            return null
        }
        val extra = config.keys - setOf("fire_at", "delay")
        extra.forEach { key ->
            violations += MutationViolation("$path.$key", "trigger_config_json 不允许字段 $key")
        }
        if (hasFireAt) {
            return requiredInstant(config, "fire_at", "$path.fire_at", violations)
        }
        val delay = optionalString(config, "delay", "$path.delay", violations) ?: return null
        return try {
            val parsed = Duration.parse(delay)
            if (parsed.isZero || parsed.isNegative) {
                violations += MutationViolation("$path.delay", "delay 必须为正时长")
                null
            } else {
                (nowEpochMillis ?: 0L) + parsed.toMillis()
            }
        } catch (t: Throwable) {
            violations += MutationViolation("$path.delay", "delay 不是合法 ISO-8601 duration")
            null
        }
    }

    private fun hasAnyViolation(violations: List<MutationViolation>, path: String): Boolean =
        violations.any { it.path.startsWith(path) }
}

private fun requiredString(
    obj: JsonObject,
    key: String,
    path: String,
    violations: MutableList<MutationViolation>,
): String? {
    val element = obj[key] ?: run {
        violations += MutationViolation(path, "缺少必填字段 $key")
        return null
    }
    return if (element is JsonPrimitive && element.isString && element.content.isNotBlank()) {
        element.content
    } else {
        violations += MutationViolation(path, "$key 必须是非空字符串")
        null
    }
}

private fun optionalString(
    obj: JsonObject,
    key: String,
    path: String,
    violations: MutableList<MutationViolation>,
): String? {
    val element = obj[key] ?: return null
    return if (element is JsonPrimitive && element.isString) {
        element.content
    } else {
        violations += MutationViolation(path, "$key 必须是字符串")
        null
    }
}

private fun requiredInstant(
    obj: JsonObject,
    key: String,
    path: String,
    violations: MutableList<MutationViolation>,
): Long? {
    val raw = requiredString(obj, key, path, violations) ?: return null
    val parsed = InstantJson.parseOrNull(raw)
    if (parsed == null) {
        violations += MutationViolation(path, "$key 不是合法 ISO-8601 时间")
        return null
    }
    return parsed.toEpochMilli()
}

private fun optionalInstant(
    obj: JsonObject,
    key: String,
    path: String,
    violations: MutableList<MutationViolation>,
): Long? {
    val element = obj[key] ?: return null
    if (element !is JsonPrimitive || !element.isString) {
        violations += MutationViolation(path, "$key 必须是 ISO-8601 字符串")
        return null
    }
    val parsed = InstantJson.parseOrNull(element.content)
    if (parsed == null) {
        violations += MutationViolation(path, "$key 不是合法 ISO-8601 时间")
        return null
    }
    return parsed.toEpochMilli()
}

private fun optionalInt(
    obj: JsonObject,
    key: String,
    path: String,
    violations: MutableList<MutationViolation>,
): Int? {
    val element = obj[key] ?: return null
    return when {
        element is JsonPrimitive && element.isString -> {
            val parsed = element.content.toIntOrNull()
            if (parsed == null) {
                violations += MutationViolation(path, "$key 必须是整数")
                null
            } else {
                parsed
            }
        }
        element is JsonPrimitive -> {
            val parsed = element.content.toIntOrNull()
            if (parsed == null) {
                violations += MutationViolation(path, "$key 必须是整数")
                null
            } else {
                parsed
            }
        }
        else -> {
            violations += MutationViolation(path, "$key 必须是整数")
            null
        }
    }
}

private fun optionalObject(
    obj: JsonObject,
    key: String,
    path: String,
    violations: MutableList<MutationViolation>,
): JsonObject? {
    val element = obj[key] ?: return null
    return if (element is JsonObject) {
        element
    } else {
        violations += MutationViolation(path, "$key 必须是 JSON object")
        null
    }
}
