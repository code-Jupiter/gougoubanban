package com.littlekitty.xiaokele.core.common.time

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * Canonical JSON representation for instants: ISO-8601 with explicit offset, e.g.
 * `2026-08-16T02:30:00Z` for UTC instants and `2026-08-16T10:30:00+08:00` for display.
 * Parsing accepts both `Z` and numeric offsets.
 */
object InstantJson {
    private val UTC_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendInstant()
        .toFormatter()

    private val PARSER: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .optionalEnd()
        .appendOffsetId()
        .toFormatter()

    fun formatUtc(instant: Instant): String = UTC_FORMATTER.format(instant)

    fun formatInZone(instant: Instant, zone: ZoneId = TimeProvider.DISPLAY_ZONE): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone).format(instant)

    fun parse(value: String): Instant = Instant.from(PARSER.parse(value))

    fun parseOrNull(value: String): Instant? = runCatching { parse(value) }.getOrNull()
}
