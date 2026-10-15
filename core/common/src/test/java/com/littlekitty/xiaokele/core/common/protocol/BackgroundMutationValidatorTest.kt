package com.littlekitty.xiaokele.core.common.protocol

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BackgroundMutationValidatorTest {

    private val now = Instant.parse("2026-08-16T10:00:00Z").toEpochMilli()
    private val eventId = "evt_00000000000000000000000000000001"

    @Test
    fun todoCreateWithValidFieldsProducesCommand() {
        val mutation = TodoMutation(
            operation = MutationOperationNames.CREATE,
            todo = buildJsonObject {
                put("type", TodoTypeNames.DISCUSS_LATER)
                put("priority", 40)
                put("available_at", "2026-08-16T10:05:00Z")
                put("expires_at", "2026-08-16T11:00:00Z")
            },
        )

        val result = TodoMutationValidator.validate(listOf(mutation), eventId, now)

        assertTrue(result is TodoMutationValidation.Valid)
        val command = (result as TodoMutationValidation.Valid).commands.single() as TodoMutationCommand.Create
        assertEquals(TodoTypeNames.DISCUSS_LATER, command.type)
        assertEquals(40, command.priority)
    }

    @Test
    fun todoCreateRejectsUnknownOperationReservedStatusAndBadTime() {
        val mutations = listOf(
            TodoMutation(operation = "CLAIM", todo = buildJsonObject { put("type", "X") }),
            TodoMutation(
                operation = MutationOperationNames.CREATE,
                todo = buildJsonObject {
                    put("type", TodoTypeNames.DISCUSS_LATER)
                    put("status", "READY")
                },
            ),
            TodoMutation(
                operation = MutationOperationNames.CREATE,
                todo = buildJsonObject {
                    put("type", TodoTypeNames.DISCUSS_LATER)
                    put("expires_at", "not-a-time")
                },
            ),
            TodoMutation(
                operation = MutationOperationNames.CREATE,
                todo = buildJsonObject {
                    put("type", TodoTypeNames.DISCUSS_LATER)
                    put("expires_at", "2026-08-16T09:00:00Z")
                },
            ),
        )

        val result = TodoMutationValidator.validate(mutations, eventId, now)

        assertTrue(result is TodoMutationValidation.Invalid)
        val violations = (result as TodoMutationValidation.Invalid).violations
        assertTrue(violations.any { it.path == "todo_mutations[0].operation" })
        assertTrue(violations.any { it.path == "todo_mutations[1].todo.status" })
        assertTrue(violations.any { it.path == "todo_mutations[2].todo.expires_at" })
        assertTrue(violations.any { it.path == "todo_mutations[3].todo.expires_at" })
    }

    @Test
    fun todoCreateSourceEventMustMatchCurrentEvent() {
        val mutation = TodoMutation(
            operation = MutationOperationNames.CREATE,
            todo = buildJsonObject {
                put("type", TodoTypeNames.DISCUSS_LATER)
                put("source_event_id", "evt_00000000000000000000000000000002")
            },
        )

        val result = TodoMutationValidator.validate(listOf(mutation), eventId, now)

        assertTrue(result is TodoMutationValidation.Invalid)
        assertTrue((result as TodoMutationValidation.Invalid).violations.any { it.path.contains("source_event_id") })
    }

    @Test
    fun createAndReferencesRequireTheirDomainSpecificIdPrefixes() {
        val todo = TodoMutation(
            operation = MutationOperationNames.CREATE,
            todo = buildJsonObject {
                put("id", "trg_00000000000000000000000000000001")
                put("type", TodoTypeNames.DISCUSS_LATER)
            },
        )
        val trigger = TriggerMutation(
            operation = MutationOperationNames.CREATE,
            trigger = buildJsonObject {
                put("id", "todo_00000000000000000000000000000001")
                put("todo_id", "trg_00000000000000000000000000000002")
                put("trigger_type", TriggerTypeNames.TIME)
                put("trigger_config_json", buildJsonObject { put("delay", "PT5M") })
            },
        )

        val todoResult = TodoMutationValidator.validate(listOf(todo), eventId, now)
        val triggerResult = TriggerMutationValidator.validate(listOf(trigger), now)

        assertTrue(todoResult is TodoMutationValidation.Invalid)
        assertTrue(triggerResult is TriggerMutationValidation.Invalid)
    }

    @Test
    fun triggerCreateSupportsFireAtAndDelay() {
        val byFireAt = TriggerMutation(
            operation = MutationOperationNames.CREATE,
            trigger = buildJsonObject {
                put("todo_id", "todo_00000000000000000000000000000001")
                put("trigger_type", TriggerTypeNames.TIME)
                put(
                    "trigger_config_json",
                    buildJsonObject { put("fire_at", "2026-08-16T10:10:00Z") },
                )
            },
        )
        val byDelay = TriggerMutation(
            operation = MutationOperationNames.CREATE,
            trigger = buildJsonObject {
                put("todo_id", "todo_00000000000000000000000000000002")
                put("trigger_type", TriggerTypeNames.TIME)
                put(
                    "trigger_config_json",
                    buildJsonObject { put("delay", "PT5M") },
                )
            },
        )

        val result = TriggerMutationValidator.validate(listOf(byFireAt, byDelay), now)

        assertTrue(result is TriggerMutationValidation.Valid)
        val commands = (result as TriggerMutationValidation.Valid).commands
        assertEquals(10L * 60_000L + now, (commands[0] as TriggerMutationCommand.Create).nextFireAtEpochMillis)
        assertEquals(5L * 60_000L + now, (commands[1] as TriggerMutationCommand.Create).nextFireAtEpochMillis)
    }

    @Test
    fun triggerCreateRejectsBothFireAtAndDelayOrBadDuration() {
        val mutations = listOf(
            TriggerMutation(
                operation = MutationOperationNames.CREATE,
                trigger = buildJsonObject {
                    put("todo_id", "todo_00000000000000000000000000000001")
                    put("trigger_type", TriggerTypeNames.TIME)
                    put(
                        "trigger_config_json",
                        buildJsonObject {
                            put("fire_at", "2026-08-16T10:10:00Z")
                            put("delay", "PT5M")
                        },
                    )
                },
            ),
            TriggerMutation(
                operation = MutationOperationNames.CREATE,
                trigger = buildJsonObject {
                    put("todo_id", "todo_00000000000000000000000000000002")
                    put("trigger_type", TriggerTypeNames.TIME)
                    put(
                        "trigger_config_json",
                        buildJsonObject { put("delay", "not-duration") },
                    )
                },
            ),
        )

        val result = TriggerMutationValidator.validate(mutations, now)

        assertTrue(result is TriggerMutationValidation.Invalid)
        val violations = (result as TriggerMutationValidation.Invalid).violations
        assertTrue(violations.any { it.message.contains("只能包含") })
        assertTrue(violations.any { it.message.contains("duration") })
    }

    @Test
    fun backgroundDecisionAllowsNoActionAndMutationsAndRejectsReplyChat() {
        val todoId = "todo_00000000000000000000000000000001"
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            todoMutations = listOf(
                TodoMutation(
                    operation = MutationOperationNames.CREATE,
                    todo = buildJsonObject { put("type", TodoTypeNames.DISCUSS_LATER) },
                ),
            ),
            triggerMutations = listOf(
                TriggerMutation(
                    operation = MutationOperationNames.CREATE,
                    trigger = buildJsonObject {
                        put("todo_id", todoId)
                        put("trigger_type", TriggerTypeNames.TIME)
                        put("trigger_config_json", buildJsonObject { put("delay", "PT5M") })
                    },
                ),
            ),
        )

        val valid = AgentDecisionValidator.validate(
            rawDecisionJson = JsonCodec.encode(decision),
            allowedAnimationIds = setOf("default_idle"),
            channel = DecisionChannels.BACKGROUND,
            nowEpochMillis = now,
            currentEventId = eventId,
        )
        assertTrue(valid is AgentDecisionValidation.Valid)

        val noAction = AgentDecisionValidator.validate(
            rawDecisionJson = JsonCodec.encode(AgentDecision(decision = DecisionNames.NO_ACTION)),
            allowedAnimationIds = setOf("default_idle"),
            channel = DecisionChannels.BACKGROUND,
        )
        assertTrue(noAction is AgentDecisionValidation.Valid)

        val reply = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.REPLY_CHAT,
                    payload = buildJsonObject { put("content", "不应出现在后台") },
                ),
            ),
        )
        val rejected = AgentDecisionValidator.validate(
            rawDecisionJson = JsonCodec.encode(reply),
            allowedAnimationIds = setOf("default_idle"),
            channel = DecisionChannels.BACKGROUND,
        )
        assertTrue(rejected is AgentDecisionValidation.Invalid)
    }

    @Test
    fun frontChatRejectsMutationsEvenWhenDecisionLooksValid() {
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.REPLY_CHAT,
                    payload = buildJsonObject { put("content", "前台回复") },
                ),
            ),
            todoMutations = listOf(
                TodoMutation(
                    operation = MutationOperationNames.CREATE,
                    todo = buildJsonObject { put("type", TodoTypeNames.DISCUSS_LATER) },
                ),
            ),
        )

        val result = AgentDecisionValidator.validate(
            rawDecisionJson = JsonCodec.encode(decision),
            allowedAnimationIds = setOf("default_idle"),
            channel = DecisionChannels.FRONT_CHAT,
        )

        assertTrue(result is AgentDecisionValidation.Invalid)
        assertTrue((result as AgentDecisionValidation.Invalid).violations.any { it.path == "todo_mutations" })
    }
}
