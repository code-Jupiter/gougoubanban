package com.littlekitty.xiaokele.core.common.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Phase 4 NormalizedEvent wire shape, aligned with architecture section 29 and
 * FR-EVENT-01/02. Every external or local input becomes one of these before it
 * is allowed to enter the Event Store or any Agent decision.
 *
 * Times are ISO-8601 strings; the normalizer converts them to UTC epoch
 * milliseconds for Room. `id` is optional on the wire: when absent the
 * EventNormalizer assigns a unified `evt_...` id.
 */
@Serializable
data class NormalizedEvent(
    val id: String? = null,
    @SerialName("event_key")
    val eventKey: String,
    val type: String,
    val source: String,
    @SerialName("occurred_at")
    val occurredAt: String,
    @SerialName("received_at")
    val receivedAt: String? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null,
    val priority: Int = 0,
    val payload: JsonObject = JsonObject(emptyMap()),
)

object NormalizedEventFields {
    const val EVENT_KEY_MAX_LENGTH = 320
    const val TYPE_MAX_LENGTH = 80
    const val SOURCE_MAX_LENGTH = 80
    const val PRIORITY_MIN = 0
    const val PRIORITY_MAX = 100
}
