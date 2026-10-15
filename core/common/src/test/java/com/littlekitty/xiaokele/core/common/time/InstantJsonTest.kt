package com.littlekitty.xiaokele.core.common.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class InstantJsonTest {

    @Test
    fun utcFormatUsesZ() {
        assertEquals(
            "2026-08-16T02:30:00Z",
            InstantJson.formatUtc(Instant.parse("2026-08-16T02:30:00Z")),
        )
    }

    @Test
    fun parsesUtc() {
        assertEquals(
            Instant.parse("2026-08-16T02:30:00Z"),
            InstantJson.parse("2026-08-16T02:30:00Z"),
        )
    }

    @Test
    fun parsesPositiveOffset() {
        assertEquals(
            Instant.parse("2026-08-15T18:30:00Z"),
            InstantJson.parse("2026-08-16T02:30:00+08:00"),
        )
    }

    @Test
    fun displayZoneIsShanghai() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        assertEquals(
            "2026-08-16T10:30:00+08:00",
            InstantJson.formatInZone(Instant.parse("2026-08-16T02:30:00Z"), shanghai),
        )
    }

    @Test
    fun badInputReturnsNull() {
        assertNull(InstantJson.parseOrNull("not-a-time"))
    }
}
