package com.littlekitty.xiaokele.core.common.time

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Product timezone is fixed to Asia/Shanghai per shift data contract.
 * All persisted instants stay UTC-based epoch values; display formatting uses [DISPLAY_ZONE].
 */
interface TimeProvider {
    fun now(): Instant
    fun zone(): ZoneId

    companion object {
        val DISPLAY_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        fun systemUtc(): TimeProvider = object : TimeProvider {
            private val clock: Clock = Clock.systemUTC()

            override fun now(): Instant = Instant.now(clock)
            override fun zone(): ZoneId = ZoneId.of("UTC")
        }
    }
}
