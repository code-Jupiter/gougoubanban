package com.littlekitty.xiaokele.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.database.creative.CreativeBuckets
import com.littlekitty.xiaokele.core.database.creative.CreativeOfferFrequencyPolicy
import com.littlekitty.xiaokele.core.database.creative.CreativeOfferLedger
import com.littlekitty.xiaokele.core.database.creative.CreativeTurnTracker
import com.littlekitty.xiaokele.core.database.entity.CreativeOfferEventEntity
import com.littlekitty.xiaokele.core.database.entity.UserDialogueTurnEntity
import com.littlekitty.xiaokele.core.database.state.CreativeOfferStatuses
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CreativeFrequencyTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun messagesWithinThirtyMinutesShareTurnButAtThirtyMinutesStartNewTurn() = runBlocking {
        val tracker = CreativeTurnTracker(db)
        val first = tracker.record(messageId(1), 1_000_000L)
        val same = tracker.record(messageId(2), 1_000_000L + 29 * 60_000L + 59_999L)
        val next = tracker.record(messageId(3), same.lastUserMessageAt + 30 * 60_000L)

        assertEquals(first.id, same.id)
        assertEquals(2, same.validMessageCount)
        assertTrue(next.id != first.id)
        assertEquals(1, next.validMessageCount)
    }

    @Test
    fun firstOfferIsAllowedAndSeventhTurnLeavesSixTurnWindow() = runBlocking {
        val turns = (0 until 7).map { index ->
            UserDialogueTurnEntity(
                id = turnId(index),
                startedAt = index * 31L * 60_000L,
                lastUserMessageAt = index * 31L * 60_000L,
                firstMessageId = messageId(index * 2),
                lastMessageId = messageId(index * 2),
                validMessageCount = 1,
                createdAt = index * 31L * 60_000L,
                updatedAt = index * 31L * 60_000L,
            )
        }
        turns.take(6).forEach { db.userDialogueTurnDao().upsert(it) }

        val policy = CreativeOfferFrequencyPolicy(db)
        assertTrue(policy.check(CreativeBuckets.MICRO_INLINE, turns.first().id, 1_000L).allowed)
        db.creativeOfferEventDao().insert(sentOffer(turns.first().id, "one", 1_000L))
        assertFalse(policy.check(CreativeBuckets.MICRO_INLINE, turns[5].id, 1_000L).allowed)
        db.userDialogueTurnDao().upsert(turns[6])
        assertTrue(policy.check(CreativeBuckets.MICRO_INLINE, turns[6].id, 1_000L).allowed)
    }

    @Test
    fun rollingSeventyTwoHoursIsAnIndependentMaximum() = runBlocking {
        val turn = UserDialogueTurnEntity(
            id = turnId(20), startedAt = 1_000L, lastUserMessageAt = 1_000L,
            firstMessageId = messageId(20), lastMessageId = messageId(20), validMessageCount = 1,
            createdAt = 1_000L, updatedAt = 1_000L,
        )
        db.userDialogueTurnDao().upsert(turn)
        db.creativeOfferEventDao().insert(sentOffer(turn.id, "one", 100_000L))
        db.creativeOfferEventDao().insert(sentOffer(turn.id, "two", 200_000L))

        assertFalse(
            CreativeOfferFrequencyPolicy(db)
                .check(CreativeBuckets.MICRO_INLINE, turn.id, 200_000L).allowed,
        )
        assertTrue(
            CreativeOfferFrequencyPolicy(db)
                .check(CreativeBuckets.LIBRARY_TOPIC, turn.id, 200_000L).allowed,
        )
    }

    @Test
    fun userInitiatedReservationDoesNotConsumeProactiveQuotaAndReleaseIsRetrySafe() = runBlocking {
        val turn = UserDialogueTurnEntity(
            id = turnId(30), startedAt = 1_000L, lastUserMessageAt = 1_000L,
            firstMessageId = messageId(30), lastMessageId = messageId(30), validMessageCount = 1,
            createdAt = 1_000L, updatedAt = 1_000L,
        )
        db.userDialogueTurnDao().upsert(turn)
        repeat(2) { index -> db.creativeOfferEventDao().insert(sentOffer(turn.id, "proactive$index", 100_000L + index)) }

        val ledger = CreativeOfferLedger(db)
        val reservation = ledger.reserve(
            bucket = CreativeBuckets.MICRO_INLINE,
            offerType = "USER_REQUEST",
            dedupKey = "user-request-1",
            turnId = turn.id,
            now = 200_000L,
            userInitiated = true,
        )
        assertNotNull(reservation)
        assertTrue(ledger.release(reservation!!.id))
        assertFalse(ledger.release(reservation.id))
    }

    private fun sentOffer(turnId: String, key: String, at: Long) = CreativeOfferEventEntity(
        id = "offer_${key.padEnd(32, '0').take(32)}",
        bucket = CreativeBuckets.MICRO_INLINE,
        userDialogueTurnId = turnId,
        offerType = "TEST",
        dedupKey = key,
        reservedAt = at,
        sentAt = at,
        status = CreativeOfferStatuses.SENT,
        createdAt = at,
    )

    private fun messageId(index: Int) = "msg_${index.toString(16).padStart(32, '0')}"
    private fun turnId(index: Int) = "turn_${index.toString(16).padStart(32, '0')}"
}
