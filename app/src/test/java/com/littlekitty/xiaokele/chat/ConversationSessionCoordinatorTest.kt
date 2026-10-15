package com.littlekitty.xiaokele.chat

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.ConversationSessionModes
import com.littlekitty.xiaokele.core.database.entity.ConversationSessionEntity
import com.littlekitty.xiaokele.core.database.entity.ConversationSessionStatuses
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.repository.BehaviorTrainer
import com.littlekitty.xiaokele.core.database.repository.MemoryRepository
import com.littlekitty.xiaokele.core.database.repository.UserTraitRepository
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import com.littlekitty.xiaokele.core.network.embedding.UnconfiguredEmbeddingServiceAdapter
import com.littlekitty.xiaokele.core.network.memory.UnconfiguredMemoryEvaluator
import com.littlekitty.xiaokele.memory.MemoryAnalysisProcessor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.work.testing.WorkManagerTestInitHelper
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConversationSessionCoordinatorTest {
    private lateinit var db: AppDatabase
    private val time = FixedTimeProvider(Instant.parse("2026-09-08T12:00:00Z"))
    private lateinit var coordinator: ConversationSessionCoordinator

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The coordinator reconciles WorkManager work on every message; a
        // Robolectric test must provide a WorkManager instance explicitly.
        runCatching {
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        coordinator = ConversationSessionCoordinator(
            context, db, time,
            MemoryAnalysisProcessor(
                db, UnconfiguredMemoryEvaluator(), MemoryRepository(db, db.memoryDao()),
                UserTraitRepository(db, db.userTraitDao()), BehaviorTrainer(db, db.behaviorTendencyDao()),
                UnconfiguredEmbeddingServiceAdapter(), time,
            ),
        )
    }

    @After fun tearDown() = db.close()

    @Test fun fiveUserMessagesInsideFifteenMinutesEnterHighFrequencyMode() = runBlocking {
        repeat(ConversationSessionCoordinator.HIGH_FREQUENCY_USER_MESSAGE_COUNT) { index ->
            val message = MessageEntity(
                id = EntityId.generate("msg").value, role = "USER", content = "第$index 条",
                createdAt = time.now().toEpochMilli() + index * 60_000L, status = MessageStatuses.COMPLETED,
            )
            db.messageDao().insert(message)
            coordinator.onUserMessage(message)
        }
        assertEquals(ConversationSessionModes.HIGH_FREQUENCY, coordinator.activeSession()!!.mode)
    }

    @Test fun aFortyFiveMinuteGapStartsAnotherSession() = runBlocking {
        val first = MessageEntity(EntityId.generate("msg").value, "USER", "先聊", time.now().toEpochMilli(), status = MessageStatuses.COMPLETED)
        db.messageDao().insert(first); coordinator.onUserMessage(first)
        val later = MessageEntity(EntityId.generate("msg").value, "USER", "再聊", time.now().toEpochMilli() + ConversationSessionCoordinator.SESSION_END_GAP_MILLIS, status = MessageStatuses.COMPLETED)
        db.messageDao().insert(later); coordinator.onUserMessage(later)
        assertEquals(later.id, coordinator.activeSession()!!.firstMessageId)
    }

    @Test fun interruptedSettlingSessionIsRecoveredAsRetryable() = runBlocking {
        val user = MessageEntity(
            EntityId.generate("msg").value,
            "USER",
            "需要结算",
            time.now().toEpochMilli() - ConversationSessionCoordinator.SESSION_END_GAP_MILLIS,
            status = MessageStatuses.COMPLETED,
        )
        val assistant = MessageEntity(
            EntityId.generate("msg").value,
            "ASSISTANT",
            "收到",
            time.now().toEpochMilli() - ConversationSessionCoordinator.SESSION_END_GAP_MILLIS + 1,
            status = MessageStatuses.COMPLETED,
        )
        db.messageDao().insert(user)
        db.messageDao().insert(assistant)
        val session = ConversationSessionEntity(
            id = EntityId.generate("conv_session").value,
            mode = ConversationSessionModes.LOW_FREQUENCY,
            status = ConversationSessionStatuses.SETTLING,
            operationalDayKey = "2026-09-08@4",
            startedAt = user.createdAt,
            lastMessageAt = assistant.createdAt,
            firstMessageId = user.id,
            lastMessageId = assistant.id,
            userMessageCount = 1,
            createdAt = user.createdAt,
            updatedAt = user.createdAt,
        )
        db.conversationSessionDao().upsert(session)

        coordinator.settleInactiveSessions(time.now().toEpochMilli())

        assertEquals(ConversationSessionStatuses.RETRYABLE, db.conversationSessionDao().byId(session.id)?.status)
        coordinator.settleInactiveSessions(time.now().toEpochMilli())
        coordinator.settleInactiveSessions(time.now().toEpochMilli())
        assertEquals(ConversationSessionCoordinator.SETTLEMENT_EXHAUSTED, db.conversationSessionDao().byId(session.id)?.status)
        repeat(5) { coordinator.settleInactiveSessions(time.now().toEpochMilli()) }
        assertEquals("3", db.metadataDao().get("memory_settlement_attempts:${session.id}"))
        assertEquals(0, db.conversationSessionDao().awaitingSettlement(8).size)
    }

    private class FixedTimeProvider(private val instant: Instant) : TimeProvider {
        override fun now(): Instant = instant
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }
}
