package com.littlekitty.xiaokele.core.database.state

import com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * PetState override matrix.
 *
 * - WORK_GUARD is a high-context state: ordinary gap, ordinary weather,
 *   meals and nearby candidates must never overwrite it.
 * - Only HIGH_WEATHER_ALERT may temporarily raise WORK_GUARD to CARING.
 * - Expired states restore to IDLE via PetStateDao.resetExpiredTo.
 *
 * The after-shift and home-arrival continuation exceptions were removed with
 * the shift/geofence feature, so weather is the only override that remains.
 */
object PetStatePriorityMatrix {

    const val CONTEXT_DEFAULT_IDLE = PetStateIds.IDLE

    private val ranks: Map<String, Int> = mapOf(
        PetStateIds.IDLE to 0,
        PetStateIds.SLEEPING to 10,
        PetStateIds.WAITING to 20,
        PetStateIds.CURLED_UP to 25,
        PetStateIds.EXPECTING to 30,
        PetStateIds.CARING to 40,
        PetStateIds.WELCOME_BACK to 50,
        PetStateIds.WORK_GUARD to 60,
    )

    fun canOverride(currentStateId: String?, requestedStateId: String, event: EventEntity?): Boolean {
        val current = currentStateId ?: PetStateIds.IDLE
        if (current == requestedStateId) return true
        if (requestedStateId == PetStateIds.IDLE) return true

        // WORK_GUARD is protected from lower-context states.
        if (current == PetStateIds.WORK_GUARD && requestedStateId != PetStateIds.WORK_GUARD) {
            val eventType = event?.type
            val highAlertCaring = eventType == ExternalEventTypeNames.WEATHER_ALERT &&
                requestedStateId == PetStateIds.CARING &&
                eventPayload(event)?.get("severity")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.contentOrNull == "HIGH"
            return highAlertCaring
        }

        return requestedStateId == PetStateIds.WORK_GUARD ||
            ranks[requestedStateId] ?: 0 > (ranks[current] ?: 0)
    }

    /** Expired states always restore to IDLE: there is no shift to guard. */
    fun contextualDefault(): String = CONTEXT_DEFAULT_IDLE

    private fun eventPayload(event: EventEntity): JsonObject? =
        runCatching {
            com.littlekitty.xiaokele.core.common.json.JsonCodec.json.parseToJsonElement(event.payloadJson) as? JsonObject
        }.getOrNull()
}
