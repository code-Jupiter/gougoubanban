package com.littlekitty.xiaokele.core.database.creative

import androidx.room.withTransaction
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.CreativeOfferEventEntity
import com.littlekitty.xiaokele.core.database.entity.UserDialogueTurnEntity
import com.littlekitty.xiaokele.core.database.state.CreativeOfferStatuses

object CreativeBuckets {
    const val MICRO_INLINE = "MICRO_INLINE"
    const val LIBRARY_TOPIC = "LIBRARY_TOPIC"
    const val BEDTIME_STORY = "BEDTIME_STORY"
}

object CreativeWorkTypes {
    const val DAILY_DIARY = "DAILY_DIARY"
    const val WEEKLY_DIARY = "WEEKLY_DIARY"
    const val MONTHLY_DIARY = "MONTHLY_DIARY"
    const val DOG_LIFE_ESSAY = "DOG_LIFE_ESSAY"
    const val FABLE = "FABLE"
    const val MICRO_STORY = "MICRO_STORY"
    const val INSPIRED_SHORT_STORY = "INSPIRED_SHORT_STORY"
    const val BEDTIME_STORY = "BEDTIME_STORY"
    const val USER_SAVED_POEM = "USER_SAVED_POEM"
}

object CreativeCandidateTypes {
    const val INSPIRED_SHORT_STORY_CANDIDATE = "INSPIRED_SHORT_STORY_CANDIDATE"
}

object CreativeSourceTypes {
    const val USER_MESSAGE = "USER_MESSAGE"
    const val ASSISTANT_MESSAGE = "ASSISTANT_MESSAGE"
    const val CREATIVE_CANDIDATE = "CREATIVE_CANDIDATE"
}

object CreativeCoverageRoles {
    const val PRIMARY = "PRIMARY"
    const val REFERENCE_ONLY = "REFERENCE_ONLY"
}

object CreativeTaskKinds {
    const val DAILY_EVALUATION = "DAILY_EVALUATION"
    const val DAILY_CATCH_UP = "DAILY_CATCH_UP"
    const val WEEKLY_EVALUATION = "WEEKLY_EVALUATION"
    const val MONTHLY_EVALUATION = "MONTHLY_EVALUATION"
}

/** Deterministic 30-minute segmentation of persisted, valid user messages. */
class CreativeTurnTracker(private val db: AppDatabase) {
    suspend fun recordInTransaction(messageId: String, createdAt: Long): UserDialogueTurnEntity {
        val current = db.userDialogueTurnDao().recent(1).firstOrNull()
        val sameTurn = current != null &&
            createdAt >= current.lastUserMessageAt &&
            createdAt - current.lastUserMessageAt < TURN_GAP_MILLIS
        val next = if (sameTurn) {
            current!!.copy(
                lastUserMessageAt = createdAt,
                lastMessageId = messageId,
                validMessageCount = current.validMessageCount + 1,
                updatedAt = createdAt,
            )
        } else {
            UserDialogueTurnEntity(
                id = EntityId.generate("turn").value,
                startedAt = createdAt,
                lastUserMessageAt = createdAt,
                firstMessageId = messageId,
                lastMessageId = messageId,
                validMessageCount = 1,
                createdAt = createdAt,
                updatedAt = createdAt,
            )
        }
        db.userDialogueTurnDao().upsert(next)
        return next
    }

    suspend fun record(messageId: String, createdAt: Long): UserDialogueTurnEntity = db.withTransaction {
        recordInTransaction(messageId, createdAt)
    }

    companion object {
        const val TURN_GAP_MILLIS = 30 * 60 * 1_000L
    }
}

data class CreativeOfferEligibility(
    val allowed: Boolean,
    val reason: String,
)

/**
 * Pure local frequency policy. It only counts SENT proactive offer events;
 * user-initiated creation is deliberately handled as a separate path.
 */
class CreativeOfferFrequencyPolicy(private val db: AppDatabase) {
    suspend fun check(bucket: String, turnId: String, now: Long): CreativeOfferEligibility {
        val turns = db.userDialogueTurnDao().recent(MAX_TURNS_WINDOW)
            .map { it.id }
            .let { ids -> if (turnId in ids) ids else listOf(turnId) + ids }
            .distinct()
            .take(MAX_TURNS_WINDOW)
        if (db.creativeOfferEventDao().countSentForTurns(bucket, turns) >= MAX_OFFERS_PER_TURNS) {
            return CreativeOfferEligibility(false, "SIX_TURN_LIMIT")
        }
        if (db.creativeOfferEventDao().countSentSince(bucket, now - ROLLING_WINDOW_MILLIS) >= MAX_OFFERS_PER_ROLLING_WINDOW) {
            return CreativeOfferEligibility(false, "ROLLING_72_HOUR_LIMIT")
        }
        return CreativeOfferEligibility(true, "ALLOWED")
    }

    companion object {
        const val MAX_TURNS_WINDOW = 6
        const val MAX_OFFERS_PER_TURNS = 1
        const val ROLLING_WINDOW_MILLIS = 72 * 60 * 60 * 1_000L
        const val MAX_OFFERS_PER_ROLLING_WINDOW = 2
    }
}

/** Reservation/send/confirm ledger used by front-chat creative markers. */
class CreativeOfferLedger(private val db: AppDatabase) {
    suspend fun reserve(
        bucket: String,
        offerType: String,
        dedupKey: String,
        turnId: String?,
        now: Long,
        userInitiated: Boolean,
    ): CreativeOfferEventEntity? = db.withTransaction {
        if (!userInitiated && (turnId == null || !CreativeOfferFrequencyPolicy(db).check(bucket, turnId, now).allowed)) {
            return@withTransaction null
        }
        val entity = CreativeOfferEventEntity(
            id = EntityId.generate("offer").value,
            bucket = bucket,
            userDialogueTurnId = turnId,
            messageId = null,
            offerType = offerType,
            dedupKey = dedupKey,
            reservedAt = now,
            sentAt = null,
            status = CreativeOfferStatuses.RESERVED,
            isUserInitiated = userInitiated,
            createdAt = now,
        )
        db.creativeOfferEventDao().insert(entity)
        db.creativeOfferEventDao().byDedupKey(bucket, dedupKey)
    }

    suspend fun markSent(reservationId: String, messageId: String, now: Long): Boolean =
        db.creativeOfferEventDao().markSent(reservationId, messageId, now) == 1

    suspend fun release(reservationId: String): Boolean =
        db.creativeOfferEventDao().release(reservationId) == 1
}
