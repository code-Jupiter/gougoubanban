package com.littlekitty.xiaokele.core.common.time

import java.time.Instant
import java.time.ZoneId

/**
 * Test/debug-only mutable clock. Production wiring uses the system clock; this
 * class lets unit tests and the debug manual dispatcher observe deterministic
 * expiry and trigger re-judgement without changing persisted time semantics.
 */
class MutableTimeProvider(
    initial: Instant,
    private val zone: ZoneId = ZoneId.of("UTC"),
) : TimeProvider {

    @Volatile
    var current: Instant = initial

    override fun now(): Instant = current

    override fun zone(): ZoneId = zone

    fun advanceTo(instant: Instant) {
        current = instant
    }

    fun advanceMillis(millis: Long): Instant {
        current = current.plusMillis(millis)
        return current
    }
}
