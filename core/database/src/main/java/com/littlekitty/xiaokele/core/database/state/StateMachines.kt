package com.littlekitty.xiaokele.core.database.state

/**
 * Deterministic local state machines (C4/C6). These objects validate every
 * status transition before a DAO write is committed. Statuses are persisted as
 * strings so new event sources and scene types can be registered without schema
 * migration (G7); invalid transitions are rejected by code, not by the model.
 */
object EventStateMachine {
    private val transitions: Map<String, Set<String>> = mapOf(
        EventStatuses.RECEIVED to setOf(
            EventStatuses.PROCESSING,
            EventStatuses.COMPLETED,
            EventStatuses.FILTERED,
            EventStatuses.EXPIRED,
            EventStatuses.FAILED,
        ),
        EventStatuses.PROCESSING to setOf(
            EventStatuses.RECEIVED, // stale processing recovery after process restart
            EventStatuses.COMPLETED,
            EventStatuses.FILTERED,
            EventStatuses.EXPIRED,
            EventStatuses.FAILED,
        ),
    )

    fun canTransition(from: String, to: String): Boolean {
        if (from == to) return true
        return transitions[from]?.contains(to) == true
    }

    fun requireTransition(from: String, to: String) {
        require(canTransition(from, to)) { "Illegal event transition: $from -> $to" }
    }
}

object TodoStateMachine {
    private val transitions: Map<String, Set<String>> = mapOf(
        TodoStatuses.CANDIDATE to setOf(
            TodoStatuses.WAITING,
            TodoStatuses.READY,
            TodoStatuses.CANCELLED,
            TodoStatuses.EXPIRED,
        ),
        TodoStatuses.WAITING to setOf(
            TodoStatuses.READY,
            TodoStatuses.CLAIMED,
            TodoStatuses.CANCELLED,
            TodoStatuses.EXPIRED,
            TodoStatuses.FAILED,
        ),
        TodoStatuses.READY to setOf(
            TodoStatuses.CLAIMED,
            TodoStatuses.EXECUTED,
            TodoStatuses.CANCELLED,
            TodoStatuses.EXPIRED,
            TodoStatuses.FAILED,
        ),
        TodoStatuses.CLAIMED to setOf(
            TodoStatuses.READY, // stale-claim recovery returns it to the pool
            TodoStatuses.EXECUTED,
            TodoStatuses.CANCELLED,
            TodoStatuses.EXPIRED,
            TodoStatuses.FAILED,
        ),
    )

    fun canTransition(from: String, to: String): Boolean {
        if (from == to) return true
        return transitions[from]?.contains(to) == true
    }

    fun requireTransition(from: String, to: String) {
        require(canTransition(from, to)) { "Illegal todo transition: $from -> $to" }
    }
}

object TriggerStateMachine {
    private val transitions: Map<String, Set<String>> = mapOf(
        TriggerStatuses.ACTIVE to setOf(
            TriggerStatuses.FIRED,
            TriggerStatuses.COMPLETED,
            TriggerStatuses.CANCELLED,
            TriggerStatuses.EXPIRED,
        ),
        TriggerStatuses.FIRED to setOf(
            TriggerStatuses.ACTIVE, // a trigger may be re-armed by deterministic code
            TriggerStatuses.COMPLETED,
            TriggerStatuses.CANCELLED,
            TriggerStatuses.EXPIRED,
        ),
    )

    fun canTransition(from: String, to: String): Boolean {
        if (from == to) return true
        return transitions[from]?.contains(to) == true
    }

    fun requireTransition(from: String, to: String) {
        require(canTransition(from, to)) { "Illegal trigger transition: $from -> $to" }
    }
}

object MemoryStateMachine {
    private val transitions: Map<String, Set<String>> = mapOf(
        MemoryStatuses.ACTIVE to setOf(
            MemoryStatuses.SUPERSEDED,
            MemoryStatuses.EXPIRED,
            MemoryStatuses.ARCHIVED,
        ),
    )

    fun canTransition(from: String, to: String): Boolean {
        if (from == to) return true
        return transitions[from]?.contains(to) == true
    }

    fun requireTransition(from: String, to: String) {
        require(canTransition(from, to)) { "Illegal memory transition: $from -> $to" }
    }
}

object ActionPlanStateMachine {
    private val transitions: Map<String, Set<String>> = mapOf(
        ActionPlanStatuses.PENDING to setOf(
            ActionPlanStatuses.VALIDATED,
            ActionPlanStatuses.RUNNING,
            ActionPlanStatuses.FAILED,
            ActionPlanStatuses.CANCELLED,
        ),
        ActionPlanStatuses.VALIDATED to setOf(
            ActionPlanStatuses.RUNNING,
            ActionPlanStatuses.FAILED,
            ActionPlanStatuses.CANCELLED,
        ),
        ActionPlanStatuses.RUNNING to setOf(
            ActionPlanStatuses.COMPLETED,
            ActionPlanStatuses.FAILED,
            ActionPlanStatuses.CANCELLED,
        ),
    )

    fun canTransition(from: String, to: String): Boolean {
        if (from == to) return true
        return transitions[from]?.contains(to) == true
    }

    fun requireTransition(from: String, to: String) {
        require(canTransition(from, to)) { "Illegal action plan transition: $from -> $to" }
    }

    fun isActive(status: String): Boolean = status in ActionPlanStatuses.active
}

object ActionExecutionStateMachine {
    private val transitions: Map<String, Set<String>> = mapOf(
        ActionExecutionStatuses.PENDING to setOf(
            ActionExecutionStatuses.RUNNING,
            ActionExecutionStatuses.FAILED,
            ActionExecutionStatuses.CANCELLED,
        ),
        ActionExecutionStatuses.RUNNING to setOf(
            ActionExecutionStatuses.COMPLETED,
            ActionExecutionStatuses.FAILED,
            ActionExecutionStatuses.CANCELLED,
            // Crash recovery after transaction commit but before the OS call.
            ActionExecutionStatuses.PENDING,
        ),
        // Deterministic retry path; only the persisted outbox scheduler may use
        // RUNNING. User handoff may cancel a known failed-retryable reach row.
        ActionExecutionStatuses.FAILED to setOf(
            ActionExecutionStatuses.RUNNING,
            ActionExecutionStatuses.CANCELLED,
        ),
    )

    fun canTransition(from: String, to: String): Boolean {
        if (from == to) return true
        return transitions[from]?.contains(to) == true
    }

    fun requireTransition(from: String, to: String) {
        require(canTransition(from, to)) { "Illegal action execution transition: $from -> $to" }
    }
}

object DeliveryStateMachine {
    private val transitions: Map<String, Set<String>> = mapOf(
        DeliveryStatuses.RESERVED to setOf(
            DeliveryStatuses.DELIVERED,
            DeliveryStatuses.FAILED,
            DeliveryStatuses.CANCELLED,
            DeliveryStatuses.OUTCOME_UNKNOWN,
        ),
        DeliveryStatuses.FAILED to setOf(
            DeliveryStatuses.RESERVED, // explicit persisted-outbox retry only
        ),
    )

    fun canTransition(from: String, to: String): Boolean {
        if (from == to) return true
        return transitions[from]?.contains(to) == true
    }

    fun requireTransition(from: String, to: String) {
        require(canTransition(from, to)) { "Illegal delivery transition: $from -> $to" }
    }
}

object PetStateMachine {
    private val transitions: Map<String, Set<String>> = mapOf(
        PetStateIds.IDLE to PetStateIds.all,
        PetStateIds.WAITING to setOf(PetStateIds.IDLE, PetStateIds.EXPECTING, PetStateIds.CURLED_UP, PetStateIds.CARING, PetStateIds.WELCOME_BACK),
        PetStateIds.SLEEPING to setOf(PetStateIds.IDLE),
        PetStateIds.CURLED_UP to setOf(PetStateIds.IDLE, PetStateIds.WAITING),
        PetStateIds.EXPECTING to setOf(PetStateIds.IDLE, PetStateIds.CARING, PetStateIds.WELCOME_BACK),
        PetStateIds.CARING to setOf(PetStateIds.IDLE, PetStateIds.WORK_GUARD),
        PetStateIds.WORK_GUARD to setOf(PetStateIds.IDLE, PetStateIds.WELCOME_BACK, PetStateIds.CARING),
        PetStateIds.WELCOME_BACK to setOf(PetStateIds.IDLE, PetStateIds.CARING, PetStateIds.EXPECTING),
    )

    fun canTransition(from: String, to: String): Boolean {
        if (from == to) return true
        return transitions[from]?.contains(to) == true
    }

    fun requireTransition(from: String, to: String) {
        require(canTransition(from, to)) { "Illegal pet state transition: $from -> $to" }
    }
}
