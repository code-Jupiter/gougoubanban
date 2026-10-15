package com.littlekitty.xiaokele.core.database.delivery

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.dao.DeliveryHistoryDao
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity
import com.littlekitty.xiaokele.core.database.state.DeliveryStateMachine
import com.littlekitty.xiaokele.core.database.state.DeliveryStatuses
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import java.security.MessageDigest
import java.time.Instant

/**
 * Phase 5 persistent delivery outbox.
 *
 * reserve -> (markInvoked) -> commitDelivered | fail is an explicit persisted
 * state machine. A reserved row means the de-duplication slot is owned, but
 * delivered_at stays 0 until the OS/system side effect really succeeded.
 * Adapters/system calls never run inside the reserve transaction.
 */
data class DeliveryReservationRequest(
    val eventId: String?,
    val todoId: String?,
    val channel: String,
    val content: String,
    val executionId: String,
    val cooldownGroup: String? = null,
    val nowEpochMillis: Long,
    val quotaExempt: Boolean = false,
)

sealed interface DeliveryReservationResult {
    data class Reserved(val delivery: DeliveryHistoryEntity) : DeliveryReservationResult

    data class Blocked(
        val reason: String,
        val detail: String? = null,
        val existingDeliveryId: String? = null,
    ) : DeliveryReservationResult
}

data class NotificationQuotaPolicy(
    val dailyLimit: Int = DeliveryOutbox.DEFAULT_NOTIFICATION_DAILY_LIMIT,
    val minIntervalMillis: Long = DeliveryOutbox.DEFAULT_NOTIFICATION_MIN_INTERVAL_MILLIS,
    val sameTypeCooldownMillis: Long = DeliveryOutbox.DEFAULT_NOTIFICATION_SAME_TYPE_COOLDOWN_MILLIS,
)

class DeliveryOutbox(
    private val db: AppDatabase,
    private val dao: DeliveryHistoryDao,
    private val notificationQuota: NotificationQuotaPolicy = NotificationQuotaPolicy(),
) {

    /**
     * Atomic eligibility check + slot reservation. The unique index on
     * (event_id, channel) is the database backstop; the check and insert happen
     * in one Room transaction so concurrent callers cannot both pass the check.
     */
    suspend fun reserve(request: DeliveryReservationRequest): DeliveryReservationResult = db.withTransaction {
        val contentHash = sha256(request.content)
        val existingForSlot = request.eventId?.let { eventId ->
            dao.forEventAndChannel(eventId, request.channel)
        }
        if (existingForSlot != null && existingForSlot.status in setOf(DeliveryStatuses.RESERVED, DeliveryStatuses.DELIVERED)) {
            return@withTransaction DeliveryReservationResult.Blocked(
                DeliveryBlockReasons.DUPLICATE_EVENT_CHANNEL,
                "slot=${request.channel}, status=${existingForSlot.status}",
                existingForSlot.id,
            )
        }

        val contentDuplicate = dao.activeOrDeliveredByContentHash(contentHash)
        if (contentDuplicate != null) {
            return@withTransaction DeliveryReservationResult.Blocked(
                DeliveryBlockReasons.DUPLICATE_CONTENT,
                "content_hash=$contentHash",
                contentDuplicate.id,
            )
        }

        val todoDuplicate = request.todoId?.let { todoId -> dao.activeOrDeliveredForTodo(todoId) }
        if (todoDuplicate != null) {
            return@withTransaction DeliveryReservationResult.Blocked(
                DeliveryBlockReasons.DUPLICATE_TODO,
                "todo_id=${request.todoId}, status=${todoDuplicate.status}",
                todoDuplicate.id,
            )
        }

        if (request.channel == CHANNEL_NOTIFICATION) {
            val cooldownBlock = notificationCooldownBlock(request)
            if (cooldownBlock != null) return@withTransaction cooldownBlock
            if (request.quotaExempt.not()) {
                val quotaBlock = notificationQuotaBlock(request)
                if (quotaBlock != null) return@withTransaction quotaBlock
            }
        }

        val existing = existingForSlot
        val delivery = if (existing != null) {
            DeliveryStateMachine.requireTransition(existing.status, DeliveryStatuses.RESERVED)
            check(dao.prepareRetry(existing.id, request.nowEpochMillis) == 1) {
                "delivery reservation reuse affected 0 rows"
            }
            dao.byId(existing.id) ?: error("delivery disappeared during reservation reuse")
        } else {
            val entity = DeliveryHistoryEntity(
                id = EntityId.generate("dlv").value,
                eventId = request.eventId,
                todoId = request.todoId,
                channel = request.channel,
                contentHash = contentHash,
                status = DeliveryStatuses.RESERVED,
                reservedAt = request.nowEpochMillis,
                attemptStartedAt = null,
                attemptCount = 0,
                nextRetryAt = null,
                errorCode = null,
                executionId = request.executionId,
                cooldownGroup = if (request.channel == CHANNEL_NOTIFICATION) {
                    request.cooldownGroup ?: DEFAULT_NOTIFICATION_COOLDOWN_GROUP
                } else {
                    request.cooldownGroup
                },
                deliveredAt = 0L,
            )
            try {
                dao.insert(entity)
            } catch (constraint: SQLiteConstraintException) {
                val winner = dao.forEventAndChannel(request.eventId.orEmpty(), request.channel)
                return@withTransaction DeliveryReservationResult.Blocked(
                    DeliveryBlockReasons.DUPLICATE_EVENT_CHANNEL,
                    "unique slot lost race",
                    winner?.id,
                )
            }
            entity
        }
        DeliveryReservationResult.Reserved(delivery)
    }

    suspend fun markInvoked(deliveryId: String, nowEpochMillis: Long): Boolean {
        if (dao.markInvoked(deliveryId, nowEpochMillis) != 1) {
            val current = dao.byId(deliveryId) ?: return false
            return current.status == DeliveryStatuses.RESERVED && current.attemptStartedAt != null
        }
        return true
    }

    suspend fun commitDelivered(deliveryId: String, nowEpochMillis: Long): DeliveryHistoryEntity? = db.withTransaction {
        val current = dao.byId(deliveryId) ?: return@withTransaction null
        DeliveryStateMachine.requireTransition(current.status, DeliveryStatuses.DELIVERED)
        check(dao.commitDelivered(deliveryId, nowEpochMillis) == 1) { "delivery commit affected 0 rows" }
        dao.byId(deliveryId)
    }

    suspend fun fail(
        deliveryId: String,
        errorCode: String,
        nowEpochMillis: Long,
        retryable: Boolean,
    ): DeliveryHistoryEntity? = db.withTransaction {
        val current = dao.byId(deliveryId) ?: return@withTransaction null
        if (current.status == DeliveryStatuses.DELIVERED) return@withTransaction current
        DeliveryStateMachine.requireTransition(current.status, DeliveryStatuses.FAILED)
        val nextRetryAt = if (retryable && current.attemptCount < MAX_ATTEMPTS) {
            nowEpochMillis + retryBackoffMillis(current.attemptCount)
        } else {
            null
        }
        check(dao.failReserved(deliveryId, errorCode, nextRetryAt) == 1) { "delivery failure affected 0 rows" }
        dao.byId(deliveryId)
    }

    suspend fun cancel(deliveryId: String, errorCode: String): DeliveryHistoryEntity? = db.withTransaction {
        val current = dao.byId(deliveryId) ?: return@withTransaction null
        if (current.status != DeliveryStatuses.RESERVED) return@withTransaction current
        DeliveryStateMachine.requireTransition(current.status, DeliveryStatuses.CANCELLED)
        check(dao.cancelReserved(deliveryId, errorCode) == 1) { "delivery cancel affected 0 rows" }
        dao.byId(deliveryId)
    }

    suspend fun markOutcomeUnknown(deliveryId: String, errorCode: String): DeliveryHistoryEntity? = db.withTransaction {
        val current = dao.byId(deliveryId) ?: return@withTransaction null
        if (current.status != DeliveryStatuses.RESERVED) return@withTransaction current
        DeliveryStateMachine.requireTransition(current.status, DeliveryStatuses.OUTCOME_UNKNOWN)
        check(dao.markOutcomeUnknown(deliveryId, errorCode) == 1) { "outcome-unknown mark affected 0 rows" }
        dao.byId(deliveryId)
    }

    suspend fun prepareRetry(deliveryId: String, nowEpochMillis: Long): DeliveryHistoryEntity? = db.withTransaction {
        val current = dao.byId(deliveryId) ?: return@withTransaction null
        if (current.status == DeliveryStatuses.RESERVED) return@withTransaction current
        DeliveryStateMachine.requireTransition(current.status, DeliveryStatuses.RESERVED)
        check(dao.prepareRetry(deliveryId, nowEpochMillis) == 1) { "delivery retry prepare affected 0 rows" }
        dao.byId(deliveryId)
    }

    suspend fun retryable(nowEpochMillis: Long, maxAttempts: Int = MAX_ATTEMPTS): List<DeliveryHistoryEntity> =
        dao.retryable(nowEpochMillis, maxAttempts)

    suspend fun nextRetryAt(maxAttempts: Int = MAX_ATTEMPTS): Long? =
        dao.earliestRetryAt(maxAttempts)

    private suspend fun notificationCooldownBlock(
        request: DeliveryReservationRequest,
    ): DeliveryReservationResult.Blocked? {
        val now = request.nowEpochMillis
        val cooldownGroup = request.cooldownGroup ?: DEFAULT_NOTIFICATION_COOLDOWN_GROUP
        val cooldownFloor = now - notificationQuota.sameTypeCooldownMillis
        val sameTypeRecent = dao.deliveredForChannelGroupSince(
            channel = CHANNEL_NOTIFICATION,
            cooldownGroup = cooldownGroup,
            after = cooldownFloor,
        )
        if (sameTypeRecent.isNotEmpty()) {
            return DeliveryReservationResult.Blocked(
                DeliveryBlockReasons.CHANNEL_COOLDOWN,
                "cooldown_group=$cooldownGroup, last_delivered_at=${sameTypeRecent.first().deliveredAt}",
            )
        }
        return null
    }

    private suspend fun notificationQuotaBlock(
        request: DeliveryReservationRequest,
    ): DeliveryReservationResult.Blocked? {
        val now = request.nowEpochMillis
        // Daily notification budget is a product-day boundary in
        // Asia/Shanghai, not a UTC calendar day.
        val startOfProductDay = Instant.ofEpochMilli(now)
            .atZone(TimeProvider.DISPLAY_ZONE)
            .toLocalDate()
            .atStartOfDay(TimeProvider.DISPLAY_ZONE)
            .toInstant()
            .toEpochMilli()
        val todayDeliveries = dao.deliveredForChannelBetween(
            channel = CHANNEL_NOTIFICATION,
            start = startOfProductDay,
            end = now,
        )
        if (todayDeliveries.size >= notificationQuota.dailyLimit) {
            return DeliveryReservationResult.Blocked(
                DeliveryBlockReasons.DAILY_LIMIT,
                "channel=NOTIFICATION, count=${todayDeliveries.size}, limit=${notificationQuota.dailyLimit}",
            )
        }

        val minIntervalFloor = now - notificationQuota.minIntervalMillis
        val recentInInterval = dao.recentDeliveredForChannel(CHANNEL_NOTIFICATION, minIntervalFloor)
        if (recentInInterval.isNotEmpty()) {
            return DeliveryReservationResult.Blocked(
                DeliveryBlockReasons.MIN_INTERVAL,
                "last_delivered_at=${recentInInterval.first().deliveredAt}",
            )
        }

        return null
    }

    private fun retryBackoffMillis(attemptCount: Int): Long = BASE_RETRY_BACKOFF_MILLIS shl attemptCount.coerceAtMost(4)

    companion object {
        const val CHANNEL_NOTIFICATION = "NOTIFICATION"
        const val CHANNEL_PROACTIVE_MESSAGE = "PROACTIVE_MESSAGE"
        const val CHANNEL_WIDGET = "WIDGET"
        const val CHANNEL_ANIMATION = "ANIMATION"
        const val CHANNEL_PET_STATE = "PET_STATE"
        const val CHANNEL_CHAT = "CHAT"

        const val DEFAULT_NOTIFICATION_DAILY_LIMIT = 3
        const val DEFAULT_NOTIFICATION_MIN_INTERVAL_MILLIS = 3 * 60 * 60 * 1_000L
        const val DEFAULT_NOTIFICATION_SAME_TYPE_COOLDOWN_MILLIS = 6 * 60 * 60 * 1_000L
        const val DEFAULT_NOTIFICATION_COOLDOWN_GROUP = "default"
        const val MAX_ATTEMPTS = 3
        const val BASE_RETRY_BACKOFF_MILLIS = 60 * 1_000L

        fun sha256(raw: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
