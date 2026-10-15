package com.littlekitty.xiaokele.chat

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.protocol.MemoryEvaluation
import com.littlekitty.xiaokele.core.common.protocol.UserProfileLayerNames
import com.littlekitty.xiaokele.core.common.protocol.UserTraitPatch
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.ConversationSummaryEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.repository.BehaviorTrainer
import com.littlekitty.xiaokele.core.database.repository.MemoryRepository
import com.littlekitty.xiaokele.core.database.repository.UserTraitRepository
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import com.littlekitty.xiaokele.core.network.embedding.UnconfiguredEmbeddingServiceAdapter
import com.littlekitty.xiaokele.core.network.memory.UnconfiguredMemoryEvaluator
import com.littlekitty.xiaokele.memory.MemoryAnalysisProcessor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConversationContextManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var timeProvider: FixedTimeProvider
    private lateinit var processor: MemoryAnalysisProcessor

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        timeProvider = FixedTimeProvider(Instant.parse("2026-08-20T10:00:00Z"))
        processor = MemoryAnalysisProcessor(
            db = db,
            memoryEvaluator = UnconfiguredMemoryEvaluator(),
            memoryRepository = MemoryRepository(db, db.memoryDao()),
            userTraitRepository = UserTraitRepository(db, db.userTraitDao()),
            behaviorTrainer = BehaviorTrainer(db, db.behaviorTendencyDao()),
            embeddingService = UnconfiguredEmbeddingServiceAdapter(),
            timeProvider = timeProvider,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun doesNotConsolidateWhileRawMessagesFitBudget() = runBlocking {
        insertMessages(count = 2, content = "短消息")
        val consolidator = RecordingConsolidator()

        val window = manager(consolidator, rawBudget = 1_000).prepare()

        assertEquals(0, consolidator.calls)
        assertEquals(0, db.conversationSummaryDao().count())
        assertEquals(2, window.rawMessages.size)
    }

    @Test
    fun rawWindowIsNotArtificiallyLimitedByMessageCount() = runBlocking {
        val messages = insertMessages(count = 20, content = "短消息")

        val window = manager(RecordingConsolidator(), rawBudget = 100_000).prepare()

        assertEquals(messages.map { it.id }, window.rawMessages.map { it.id })
    }

    @Test
    fun lowFrequencyOverflowDoesNotCreateConversationSummary() = runBlocking {
        val messages = insertMessages(
            count = 4,
            content = "聊".repeat(80),
            startAt = timeProvider.now().toEpochMilli() - 100,
        )
        val consolidator = RecordingConsolidator()

        val window = manager(consolidator, rawBudget = 120).prepare()

        assertEquals(0, consolidator.calls)
        assertEquals(0, db.conversationSummaryDao().count())
        assertEquals(listOf(messages.last().id), window.rawMessages.map { it.id })
        assertEquals(emptyList<String>(), window.recentSummaries.map { it.id })

        manager(consolidator, rawBudget = 120).prepare()
        assertEquals(0, consolidator.calls)
        assertEquals(0, db.conversationSummaryDao().count())
    }

    @Test
    fun consolidationFailureFallsBackToNewestRawMessagesWithoutBlockingContext() = runBlocking {
        val messages = insertMessages(
            count = 4,
            content = "聊".repeat(80),
            startAt = timeProvider.now().toEpochMilli() - 100,
        )
        val failing = ConversationConsolidator {
            ConversationConsolidationResult.Failure(
                com.littlekitty.xiaokele.core.common.error.AppError(
                    com.littlekitty.xiaokele.core.common.error.ErrorCode.MODEL_FAILURE,
                ),
            )
        }

        val window = manager(failing, rawBudget = 120).prepare()

        assertEquals(0, db.conversationSummaryDao().count())
        assertEquals(listOf(messages.last().id), window.rawMessages.map { it.id })
    }

    @Test
    fun productionModeDoesNotStartBackgroundConversationSummary() = runBlocking {
        val messages = insertMessages(
            count = 4,
            content = "聊".repeat(80),
            startAt = timeProvider.now().toEpochMilli() - 100,
        )
        var calls = 0
        val slowConsolidator = ConversationConsolidator { _ ->
            calls++
            ConversationConsolidationResult.Success(ConversationConsolidationOutput(summary = "不应调用"))
        }
        val manager = manager(slowConsolidator, rawBudget = 120, runInline = false)

        val firstWindow = manager.prepare()
        val secondWindow = manager.prepare()

        assertEquals(listOf(messages.last().id), firstWindow.rawMessages.map { it.id })
        assertEquals(listOf(messages.last().id), secondWindow.rawMessages.map { it.id })
        assertEquals(0, calls)
        assertEquals(0, db.conversationSummaryDao().count())
    }

    @Test
    fun explicitConsolidationRunsRealDToCPathEvenWhenHistoryFitsBudget() = runBlocking {
        val messages = insertMessages(count = 2, content = "需要归纳的历史")
        val consolidator = RecordingConsolidator()

        val covered = manager(consolidator, rawBudget = 1_000).consolidateAllNow()

        assertEquals(messages.size, covered)
        assertEquals(1, consolidator.calls)
        assertEquals(1, db.conversationSummaryDao().count())

        // The explicit action is idempotent: already-covered messages are not
        // sent to the model again on a later debug/acceptance retry.
        assertEquals(0, manager(consolidator, rawBudget = 1_000).consolidateAllNow())
        assertEquals(1, consolidator.calls)
        assertEquals(1, db.conversationSummaryDao().count())
    }

    @Test
    fun summaryOlderThanOneWeekNoLongerEntersReplyContext() = runBlocking {
        val oldAt = timeProvider.now().minusSeconds(8L * 24L * 60L * 60L).toEpochMilli()
        val oldMessages = insertMessages(count = 2, content = "旧对话", startAt = oldAt)
        db.conversationSummaryDao().upsert(
            ConversationSummaryEntity(
                id = EntityId.generate("summary").value,
                rangeStartMessageId = oldMessages.first().id,
                rangeEndMessageId = oldMessages.last().id,
                summary = "超过一周的旧摘要",
                createdAt = timeProvider.now().toEpochMilli(),
            ),
        )
        val current = insertMessages(count = 1, content = "现在的消息").single()

        val window = manager(RecordingConsolidator(), rawBudget = 1_000).prepare()

        assertEquals(oldMessages.map { it.id } + current.id, window.rawMessages.map { it.id })
        assertFalse(window.recentSummaries.any { it.summary.contains("旧摘要") })
    }

    @Test
    fun contextUsesRecentSummaryAndDoesNotDuplicateCurrentUserMessage() = runBlocking {
        val earlier = insertMessages(
            count = 2,
            content = "此前讨论",
            startAt = timeProvider.now().toEpochMilli() - 1_000,
        )
        db.conversationSummaryDao().upsert(
            ConversationSummaryEntity(
                id = EntityId.generate("summary").value,
                rangeStartMessageId = earlier.first().id,
                rangeEndMessageId = earlier.last().id,
                summary = "此前讨论了近期记忆设计。",
                createdAt = timeProvider.now().toEpochMilli(),
            ),
        )
        val current = insertMessages(count = 1, content = "继续讨论").single()
        val context = ChatContextBuilder(
            db = db,
            timeProvider = timeProvider,
            conversationContextManager = manager(RecordingConsolidator(), rawBudget = 1_000),
        ).buildFor(current)

        assertEquals(0, context.conversationSummaries.size)
        val payload = context.event!!["payload"]!!.jsonObject
        assertEquals(
            0,
            payload["recent_messages"]!!.jsonArray.count { it.jsonObject["id"].toString().contains(current.id) },
        )
        assertEquals(current.id, payload["user_message"]!!.jsonObject["id"].toString().trim('"'))
    }

    @Test
    fun foregroundContextCountsCurrentUserInsideFourteenMessageWindow() = runBlocking {
        insertMessages(count = 20, content = "历史消息")
        val current = insertMessages(count = 1, content = "当前消息").single()
        val context = ChatContextBuilder(
            db = db,
            timeProvider = timeProvider,
            conversationContextManager = manager(RecordingConsolidator(), rawBudget = 100_000),
        ).buildFor(current)

        val payload = context.event!!["payload"]!!.jsonObject
        assertEquals(13, payload["recent_messages"]!!.jsonArray.size)
    }

    @Test
    fun todoContextIncludesItsSourceUserWordingAndValidity() = runBlocking {
        val now = timeProvider.now().toEpochMilli()
        val eventId = EntityId.generate("evt").value
        val sourceMessage = MessageEntity(
            id = EntityId.generate("msg").value,
            role = "USER",
            content = "下午提醒我带伞",
            createdAt = now - 1_000,
            sourceEventId = eventId,
            status = MessageStatuses.COMPLETED,
        )
        db.eventDao().insert(
            EventEntity(
                id = eventId,
                eventKey = "test:todo-source",
                type = "USER_MESSAGE",
                source = "CHAT_INPUT",
                payloadJson = "{\"user_message_id\":\"${sourceMessage.id}\"}",
                occurredAt = sourceMessage.createdAt,
                receivedAt = sourceMessage.createdAt,
                status = EventStatuses.COMPLETED,
            ),
        )
        db.messageDao().insert(sourceMessage)
        db.todoDao().upsert(
            TodoEntity(
                id = EntityId.generate("todo").value,
                type = "REMINDER",
                title = "带伞",
                sourceEventId = eventId,
                status = TodoStatuses.READY,
                priority = 80,
                availableAt = now,
                expiresAt = now + 60_000,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val current = insertMessages(count = 1, content = "继续聊").single()

        val context = ChatContextBuilder(db, timeProvider).buildFor(current)
        val todo = context.relevantTodos.single().jsonObject

        assertEquals("下午提醒我带伞", todo["source"]!!.jsonObject["user_wording"].toString().trim('"'))
        assertEquals("USER_REQUEST", todo["source"]!!.jsonObject["kind"].toString().trim('"'))
        assertFalse(todo["expires_at"].toString().isBlank())
    }

    @Test
    fun oversizedCoreProfileIsSynthesizedOnceWithoutDeletingSourceFacts() = runBlocking {
        val repository = UserTraitRepository(db, db.userTraitDao())
        repeat(3) { index ->
            repository.applyPatch(
                patch = UserTraitPatch(
                    key = "stable_preference_$index",
                    value = buildJsonObject { put("description", "长期稳定偏好".repeat(20)) },
                    confidence = 0.9,
                    profileLayer = UserProfileLayerNames.CORE,
                ),
                derivedEvidenceMemoryIds = emptyList(),
                now = timeProvider.now().toEpochMilli(),
            )
        }
        val synthesizer = RecordingProfileSynthesizer()
        val manager = UserProfileContextManager(repository, synthesizer, timeProvider, tokenBudget = 20)

        val first = manager.prepare(repository.active())
        val second = manager.prepare(repository.active())

        assertEquals(1, synthesizer.calls)
        assertEquals(listOf(UserProfileContextManager.CORE_PROFILE_SNAPSHOT_KEY), first.map { it.key })
        assertEquals(listOf(UserProfileContextManager.CORE_PROFILE_SNAPSHOT_KEY), second.map { it.key })
        assertEquals(4, repository.active().size)
    }

    private fun manager(
        consolidator: ConversationConsolidator,
        rawBudget: Int,
        runInline: Boolean = true,
    ) = ConversationContextManager(
        db = db,
        consolidator = consolidator,
        memoryAnalysisProcessor = processor,
        timeProvider = timeProvider,
        policy = ConversationBudgetPolicy(
            rawMessageTokenBudget = rawBudget,
            summaryTokenBudget = 500,
        ),
        runConsolidationInline = runInline,
    )

    private suspend fun insertMessages(
        count: Int,
        content: String,
        startAt: Long = timeProvider.now().toEpochMilli(),
    ): List<MessageEntity> = List(count) { index ->
        MessageEntity(
            id = EntityId.generate("msg").value,
            role = if (index % 2 == 0) "USER" else "ASSISTANT",
            content = "$content-$index",
            createdAt = startAt + index,
            status = MessageStatuses.COMPLETED,
        ).also { db.messageDao().insert(it) }
    }

    private class RecordingConsolidator : ConversationConsolidator {
        var calls: Int = 0

        override suspend fun consolidate(
            messages: List<ConversationConsolidationMessage>,
        ): ConversationConsolidationResult {
            calls++
            return ConversationConsolidationResult.Success(
                ConversationConsolidationOutput(
                    summary = "共归纳 ${messages.size} 条消息，并由归纳模型自行划分话题。",
                    memoryEvaluation = MemoryEvaluation(),
                ),
            )
        }
    }

    private class RecordingProfileSynthesizer : CoreProfileSynthesizer {
        var calls: Int = 0

        override suspend fun synthesize(facts: List<CoreProfileFact>): CoreProfileSynthesisResult {
            calls++
            return CoreProfileSynthesisResult.Success(
                buildJsonObject { put("summary", "综合了 ${facts.size} 条稳定用户事实") },
            )
        }
    }

    private class FixedTimeProvider(private val instant: Instant) : TimeProvider {
        override fun now(): Instant = instant
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }
}
