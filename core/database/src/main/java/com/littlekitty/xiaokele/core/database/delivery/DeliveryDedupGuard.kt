package com.littlekitty.xiaokele.core.database.delivery

import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.database.dao.DeliveryHistoryDao
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity
import com.littlekitty.xiaokele.core.database.state.DeliveryStatuses
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import java.security.MessageDigest
import java.time.Instant

data class DeliveryDedupRequest(
    val eventId: String?,
    val todoId: String?,
    val channel: String,
    val content: String,
    val nowEpochMillis: Long,
)

sealed interface DeliveryDedupDecision {
    data class Allowed(val contentHash: String) : DeliveryDedupDecision

    data class Blocked(val reason: String, val detail: String? = null) : DeliveryDedupDecision
}

object DeliveryBlockReasons {
    const val DUPLICATE_CONTENT = "DUPLICATE_CONTENT"
    const val DUPLICATE_EVENT_CHANNEL = "DUPLICATE_EVENT_CHANNEL"
    const val DUPLICATE_TODO = "DUPLICATE_TODO"
    const val DAILY_LIMIT = "DAILY_LIMIT"
    const val MIN_INTERVAL = "MIN_INTERVAL"
    const val CHANNEL_COOLDOWN = "CHANNEL_COOLDOWN"
}

/**
 * Deterministic delivery de-duplication and cooldown boundary (architecture
 * section 40/12.2). Phase 4 never sends a notification or any other real
 * delivery; this guard only computes and records deterministic decisions so
 * Phase 5 adapters cannot accidentally duplicate an already-touched event.
 */
class DeliveryDedupGuard(
    private val dao: DeliveryHistoryDao,
    private val dailyLimit: Int = DEFAULT_DAILY_LIMIT,
    private val minIntervalMillis: Long = DEFAULT_MIN_INTERVAL_MILLIS,
    private val channelCooldownMillis: Long = DEFAULT_CHANNEL_COOLDOWN_MILLIS,
) {

    suspend fun check(request: DeliveryDedupRequest): DeliveryDedupDecision {
        val contentHash = sha256(request.content)
        if (dao.activeOrDeliveredByContentHash(contentHash) != null) {
            return DeliveryDedupDecision.Blocked(DeliveryBlockReasons.DUPLICATE_CONTENT)
        }
        val slot = request.eventId?.let { eventId -> dao.forEventAndChannel(eventId, request.channel) }
        if (slot != null && slot.status in setOf(DeliveryStatuses.RESERVED, DeliveryStatuses.DELIVERED)) {
            return DeliveryDedupDecision.Blocked(DeliveryBlockReasons.DUPLICATE_EVENT_CHANNEL)
        }
        if (request.todoId != null && dao.activeOrDeliveredForTodo(request.todoId) != null) {
            return DeliveryDedupDecision.Blocked(DeliveryBlockReasons.DUPLICATE_TODO)
        }

        if (request.channel == CHANNEL_NOTIFICATION) {
            // Daily notification budget follows the product-day boundary in
            // Asia/Shanghai rather than a UTC calendar day.
            val startOfProductDay = Instant.ofEpochMilli(request.nowEpochMillis)
                .atZone(TimeProvider.DISPLAY_ZONE)
                .toLocalDate()
                .atStartOfDay(TimeProvider.DISPLAY_ZONE)
                .toInstant()
                .toEpochMilli()
            val todayDeliveries = dao.deliveredForChannelBetween(request.channel, startOfProductDay, request.nowEpochMillis)
            if (todayDeliveries.size >= dailyLimit) {
                return DeliveryDedupDecision.Blocked(
                    DeliveryBlockReasons.DAILY_LIMIT,
                    "channel=${request.channel}, count=${todayDeliveries.size}, limit=$dailyLimit",
                )
            }

            val minIntervalFloor = request.nowEpochMillis - minIntervalMillis
            val recentInInterval = dao.recentDeliveredForChannel(request.channel, minIntervalFloor)
            if (recentInInterval.isNotEmpty()) {
                return DeliveryDedupDecision.Blocked(
                    DeliveryBlockReasons.MIN_INTERVAL,
                    "last_delivered_at=${recentInInterval.first().deliveredAt}",
                )
            }

            val cooldownFloor = request.nowEpochMillis - channelCooldownMillis
            val recentInCooldown = dao.recentDeliveredForChannel(request.channel, cooldownFloor)
            if (recentInCooldown.isNotEmpty()) {
                return DeliveryDedupDecision.Blocked(
                    DeliveryBlockReasons.CHANNEL_COOLDOWN,
                    "last_delivered_at=${recentInCooldown.first().deliveredAt}",
                )
            }
        }

        return DeliveryDedupDecision.Allowed(contentHash)
    }

    suspend fun recordAllowed(
        request: DeliveryDedupRequest,
        contentHash: String,
    ): DeliveryHistoryEntity {
        val entity = DeliveryHistoryEntity(
            id = EntityId.generate("dlv").value,
            eventId = request.eventId,
            todoId = request.todoId,
            channel = request.channel,
            contentHash = contentHash,
            status = DeliveryStatuses.DELIVERED,
            deliveredAt = request.nowEpochMillis,
        )
        dao.upsert(entity)
        return entity
    }

    private fun sha256(raw: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    companion object {
        const val CHANNEL_NOTIFICATION = "NOTIFICATION"
        const val CHANNEL_PROACTIVE_MESSAGE = "PROACTIVE_MESSAGE"
        const val CHANNEL_CHAT = "CHAT"

        const val DEFAULT_DAILY_LIMIT = 3
        const val DEFAULT_MIN_INTERVAL_MILLIS = 3 * 60 * 60 * 1_000L
        const val DEFAULT_CHANNEL_COOLDOWN_MILLIS = 6 * 60 * 60 * 1_000L
    }
}
