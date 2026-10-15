package com.littlekitty.xiaokele.core.database.state

/**
 * Persisted status strings for Phase 1 local fact source.
 * The wire-facing statuses stay plain strings so new event/todo types do not
 * require schema changes (G7); state transitions are enforced by the
 * deterministic state machines in this package, never by the LLM (C4).
 */
object MessageStatuses {
    const val SENDING = "SENDING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"

    val terminal = setOf(COMPLETED, FAILED)
}

object EventStatuses {
    const val RECEIVED = "RECEIVED"
    const val PROCESSING = "PROCESSING"
    const val COMPLETED = "COMPLETED"
    const val FILTERED = "FILTERED"
    const val EXPIRED = "EXPIRED"
    const val FAILED = "FAILED"

    val terminal = setOf(COMPLETED, FILTERED, EXPIRED, FAILED)
}

object TodoStatuses {
    const val CANDIDATE = "CANDIDATE"
    const val WAITING = "WAITING"
    const val READY = "READY"
    const val CLAIMED = "CLAIMED"
    const val EXECUTED = "EXECUTED"
    const val CANCELLED = "CANCELLED"
    const val EXPIRED = "EXPIRED"
    const val FAILED = "FAILED"

    val claimable = setOf(WAITING, READY)
    val terminal = setOf(EXECUTED, CANCELLED, EXPIRED, FAILED)
}

object TriggerStatuses {
    const val ACTIVE = "ACTIVE"
    const val FIRED = "FIRED"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
    const val EXPIRED = "EXPIRED"

    val terminal = setOf(COMPLETED, CANCELLED, EXPIRED)
}

object MemoryStatuses {
    const val ACTIVE = "ACTIVE"
    const val SUPERSEDED = "SUPERSEDED"
    const val EXPIRED = "EXPIRED"
    const val ARCHIVED = "ARCHIVED"
}

object UserTraitStatuses {
    const val ACTIVE = "ACTIVE"
    const val INACTIVE = "INACTIVE"
}

object BehaviorTendencyStatuses {
    const val ACTIVE = "ACTIVE"
    const val SUPPRESSED_LEGACY = "SUPPRESSED_LEGACY"
    const val ARCHIVED = "ARCHIVED"

    val terminal = setOf(SUPPRESSED_LEGACY, ARCHIVED)
}

object ActionPlanStatuses {
    const val PENDING = "PENDING"
    const val VALIDATED = "VALIDATED"
    const val RUNNING = "RUNNING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"

    /** Statuses that occupy the one-active-plan-per-event unique slot. */
    val active = setOf(PENDING, VALIDATED, RUNNING)
    val terminal = setOf(COMPLETED, FAILED, CANCELLED)
}

object ActionExecutionStatuses {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

object DeliveryStatuses {
    /**
     * De-duplication slot has been reserved but the OS/system side effect has
     * not been confirmed. [delivered_at] must remain 0 in this state.
     */
    const val RESERVED = "RESERVED"

    /** OS/system side effect succeeded; this is the only state with delivered_at > 0. */
    const val DELIVERED = "DELIVERED"

    /** Known failure; the same row may be retried through a deterministic scheduler. */
    const val FAILED = "FAILED"

    /** Superseded by another successful channel for the same event/todo. */
    const val CANCELLED = "CANCELLED"

    /** The process died after the OS call was attempted; automatic retry is forbidden. */
    const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"

    val active = setOf(RESERVED)
    val delivered = setOf(DELIVERED)
    val terminal = setOf(DELIVERED, CANCELLED, OUTCOME_UNKNOWN)
}

object PetStateIds {
    const val IDLE = "IDLE"
    const val WAITING = "WAITING"
    const val SLEEPING = "SLEEPING"
    const val CURLED_UP = "CURLED_UP"
    const val EXPECTING = "EXPECTING"
    const val CARING = "CARING"
    const val WORK_GUARD = "WORK_GUARD"
    const val WELCOME_BACK = "WELCOME_BACK"

    val all = setOf(IDLE, WAITING, SLEEPING, CURLED_UP, EXPECTING, CARING, WORK_GUARD, WELCOME_BACK)
}

object CreativeWorkStatuses {
    const val ACTIVE = "ACTIVE"
    const val INVALIDATED = "INVALIDATED"
    const val ARCHIVED = "ARCHIVED"
}

object CreativeCandidateStatuses {
    const val AVAILABLE = "AVAILABLE"
    const val CONVERTED = "CONVERTED"
    const val DISMISSED = "DISMISSED"
    const val EXPIRED = "EXPIRED"
    const val INVALIDATED = "INVALIDATED"
}

object CreativeTopicStatuses {
    const val AVAILABLE = "AVAILABLE"
    const val RESERVED = "RESERVED"
    const val DISCUSSED = "DISCUSSED"
    const val DISMISSED = "DISMISSED"
    const val EXPIRED = "EXPIRED"
    const val INVALIDATED = "INVALIDATED"
}

object CreativeSessionStatuses {
    const val AWAITING_CONSENT = "AWAITING_CONSENT"
    const val ACTIVE = "ACTIVE"
    const val PAUSED = "PAUSED"
    const val COMPLETED = "COMPLETED"
    const val ABANDONED = "ABANDONED"
}

object CreativeOfferStatuses {
    const val RESERVED = "RESERVED"
    const val SENT = "SENT"
    const val RELEASED = "RELEASED"
    const val CHECKED = "CHECKED"
    const val DISMISSED = "DISMISSED"
}

object CreativeTaskStatuses {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val COMPLETED = "COMPLETED"
    const val NO_ACTION = "NO_ACTION"
    const val RETRYABLE = "RETRYABLE"
    const val FAILED = "FAILED"
    const val SKIPPED_STALE = "SKIPPED_STALE"
    const val CANCELLED_EXPIRED = "CANCELLED_EXPIRED"
    const val COALESCED = "COALESCED"
}
