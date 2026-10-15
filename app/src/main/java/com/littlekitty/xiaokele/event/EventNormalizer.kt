package com.littlekitty.xiaokele.event

import android.database.sqlite.SQLiteConstraintException
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.protocol.NormalizedEvent
import com.littlekitty.xiaokele.core.common.protocol.NormalizedEventFields
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.dao.EventDao
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.state.EventStatuses

data class EventNormalizationViolation(
    val path: String,
    val message: String,
)

sealed interface EventIngestion {
    data class Stored(
        val event: EventEntity,
        val normalizedEvent: NormalizedEvent,
    ) : EventIngestion

    data class RejectedDuplicate(
        val existingEvent: EventEntity,
        val attemptedEvent: NormalizedEvent,
    ) : EventIngestion

    data class RejectedExpired(
        val event: EventEntity,
        val normalizedEvent: NormalizedEvent,
    ) : EventIngestion

    data class RejectedInvalid(
        val violations: List<EventNormalizationViolation>,
        val attemptedEvent: NormalizedEvent,
    ) : EventIngestion
}

/**
 * Phase 4 Event Normalizer (architecture section 15, FR-EVENT-01/02/03).
 *
 * Normalization always precedes Agent decision. Valid events are persisted to
 * the local Event Store before any processing. Duplicate event_key attempts
 * return the existing row; expired events are persisted with status EXPIRED
 * and filtered out; invalid events are rejected without a row and remain
 * auditable through the returned structured result.
 */
class EventNormalizer(
    private val dao: EventDao,
    private val timeProvider: TimeProvider,
) {

    suspend fun ingest(raw: NormalizedEvent): EventIngestion {
        val now = timeProvider.now()
        val receivedAtEpoch = raw.receivedAt
            ?.let { InstantJson.parseOrNull(it)?.toEpochMilli() }
            ?: now.toEpochMilli()
        val violations = validate(raw)

        if (violations.isNotEmpty()) {
            return EventIngestion.RejectedInvalid(violations, raw)
        }

        val occurredAtEpoch = InstantJson.parseOrNull(raw.occurredAt)!!.toEpochMilli()
        val expiresAtEpoch = raw.expiresAt?.let { InstantJson.parseOrNull(it)?.toEpochMilli() }
        val alreadyExpired = expiresAtEpoch != null && expiresAtEpoch <= receivedAtEpoch
        val id = raw.id?.takeIf { EntityId.isValid(it) } ?: EntityId.generate("evt").value

        val entity = EventEntity(
            id = id,
            eventKey = raw.eventKey.trim(),
            type = raw.type.trim(),
            source = raw.source.trim(),
            payloadJson = raw.payload.toString(),
            occurredAt = occurredAtEpoch,
            receivedAt = receivedAtEpoch,
            expiresAt = expiresAtEpoch,
            priority = raw.priority,
            status = if (alreadyExpired) EventStatuses.EXPIRED else EventStatuses.RECEIVED,
        )

        dao.byEventKey(entity.eventKey)?.let { existing ->
            return EventIngestion.RejectedDuplicate(existing, raw)
        }

        try {
            dao.insert(entity)
        } catch (constraint: SQLiteConstraintException) {
            val existing = dao.byEventKey(entity.eventKey)
            if (existing != null) {
                return EventIngestion.RejectedDuplicate(existing, raw)
            }
            throw constraint
        }

        return if (alreadyExpired) {
            EventIngestion.RejectedExpired(entity, raw)
        } else {
            EventIngestion.Stored(entity, raw)
        }
    }

    private fun validate(raw: NormalizedEvent): List<EventNormalizationViolation> {
        val violations = mutableListOf<EventNormalizationViolation>()
        if (raw.eventKey.isBlank()) {
            violations += EventNormalizationViolation("event_key", "event_key 不能为空")
        } else if (raw.eventKey.length > NormalizedEventFields.EVENT_KEY_MAX_LENGTH) {
            violations += EventNormalizationViolation(
                "event_key",
                "event_key 超过 ${NormalizedEventFields.EVENT_KEY_MAX_LENGTH} 字符",
            )
        }
        if (raw.type.isBlank()) {
            violations += EventNormalizationViolation("type", "type 不能为空")
        } else if (raw.type.length > NormalizedEventFields.TYPE_MAX_LENGTH) {
            violations += EventNormalizationViolation("type", "type 超过 ${NormalizedEventFields.TYPE_MAX_LENGTH} 字符")
        }
        if (raw.source.isBlank()) {
            violations += EventNormalizationViolation("source", "source 不能为空")
        } else if (raw.source.length > NormalizedEventFields.SOURCE_MAX_LENGTH) {
            violations += EventNormalizationViolation("source", "source 超过 ${NormalizedEventFields.SOURCE_MAX_LENGTH} 字符")
        }
        if (raw.priority < NormalizedEventFields.PRIORITY_MIN || raw.priority > NormalizedEventFields.PRIORITY_MAX) {
            violations += EventNormalizationViolation("priority", "priority 必须在 0..100")
        }
        if (InstantJson.parseOrNull(raw.occurredAt) == null) {
            violations += EventNormalizationViolation("occurred_at", "occurred_at 不是合法 ISO-8601 时间")
        }
        val receivedAtRaw = raw.receivedAt
        if (receivedAtRaw != null && InstantJson.parseOrNull(receivedAtRaw) == null) {
            violations += EventNormalizationViolation("received_at", "received_at 不是合法 ISO-8601 时间")
        }
        val expiresAtRaw = raw.expiresAt
        if (expiresAtRaw != null && InstantJson.parseOrNull(expiresAtRaw) == null) {
            violations += EventNormalizationViolation("expires_at", "expires_at 不是合法 ISO-8601 时间")
        }
        val rawId = raw.id
        if (rawId != null && EntityId.isValid(rawId).not()) {
            violations += EventNormalizationViolation("id", "id 不是统一 EntityId 格式")
        }
        return violations
    }
}
