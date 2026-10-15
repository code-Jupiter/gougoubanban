package com.littlekitty.xiaokele.debug

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.ConversationSummaryEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.MemoryEntity
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.repository.BehaviorTrainer
import com.littlekitty.xiaokele.core.database.repository.MemoryRepository
import com.littlekitty.xiaokele.core.database.repository.UserTraitRepository
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.MemoryStatuses
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import com.littlekitty.xiaokele.core.network.embedding.FakeEmbeddingService
import com.littlekitty.xiaokele.core.network.memory.FakeMemoryEvaluator
import com.littlekitty.xiaokele.memory.MemoryAnalysisProcessor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DebugDataMaintenanceServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var service: DebugDataMaintenanceService
    private val now = 1_755_300_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val timeProvider = object : TimeProvider {
            override fun now(): Instant = Instant.ofEpochMilli(now)
            override fun zone(): ZoneId = ZoneId.of("Asia/Shanghai")
        }
        val processor = MemoryAnalysisProcessor(
            db = db,
            memoryEvaluator = FakeMemoryEvaluator(),
            memoryRepository = MemoryRepository(db, db.memoryDao()),
            userTraitRepository = UserTraitRepository(db, db.userTraitDao()),
            behaviorTrainer = BehaviorTrainer(db, db.behaviorTendencyDao()),
            embeddingService = FakeEmbeddingService(),
            timeProvider = timeProvider,
        )
        service = DebugDataMaintenanceService(db, processor, context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun clearChatRemovesMessagesAndSummariesButKeepsMemoryAndEndsChatEvent() = runBlocking {
        db.messageDao().insert(
            MessageEntity(
                id = "msg_debug_clear_0000000000000000000000000001",
                role = "USER",
                content = "需要清理的聊天",
                createdAt = now,
                status = MessageStatuses.COMPLETED,
            ),
        )
        db.conversationSummaryDao().upsert(
            ConversationSummaryEntity(
                id = "summary_debug_clear_0000000000000000000000001",
                rangeStartMessageId = "msg_debug_clear_0000000000000000000000000001",
                rangeEndMessageId = "msg_debug_clear_0000000000000000000000000001",
                summary = "旧摘要",
                createdAt = now,
            ),
        )
        db.eventDao().upsert(
            EventEntity(
                id = "event_debug_clear_00000000000000000000000001",
                eventKey = "debug:chat:clear:1",
                type = "USER_MESSAGE",
                source = "CHAT_INPUT",
                payloadJson = "{}",
                occurredAt = now,
                receivedAt = now,
                status = EventStatuses.RECEIVED,
            ),
        )
        db.memoryDao().upsert(
            MemoryEntity(
                id = "memory_debug_keep_0000000000000000000000001",
                type = "USER_PREFERENCE",
                content = "应保留的记忆",
                importance = 1.0,
                confidence = 1.0,
                status = MemoryStatuses.ACTIVE,
                createdAt = now,
                updatedAt = now,
            ),
        )

        val report = service.clearChat()

        assertEquals(1, report.messageCount)
        assertEquals(1, report.summaryCount)
        assertEquals(0, db.messageDao().count())
        assertEquals(0, db.conversationSummaryDao().count())
        assertEquals(1, db.memoryDao().count())
        assertEquals(EventStatuses.FAILED, db.eventDao().byId("event_debug_clear_00000000000000000000000001")?.status)
    }

    @Test
    fun naturalLanguageMemoryUsesChineseIntentWithoutJsonInput() = runBlocking {
        val result = service.addMemory("我不喜欢被连续追问", DebugMemoryIntent.COMPANIONSHIP_ADVICE)

        assertTrue(result.success)
        assertEquals(1, db.memoryDao().activeAt(now).size)
        assertEquals("我不喜欢被连续追问", db.memoryDao().activeAt(now).single().content)
    }

    @Test
    fun chatHistoryImportPreservesRolesContentAndOffsetDateTimes() = runBlocking {
        val result = service.importChatHistory(validChatHistoryJson())

        assertTrue(result is DebugChatImportResult.Success)
        result as DebugChatImportResult.Success
        assertEquals(2, result.insertedCount)
        assertEquals(0, result.skippedCount)
        val messages = db.messageDao().allAscending()
        assertEquals(listOf("USER", "ASSISTANT"), messages.map { it.role })
        assertEquals(listOf("以前的用户消息", "以前的 AI 回复"), messages.map { it.content })
        assertEquals(1_754_011_800_000L, messages.first().createdAt)
        assertEquals(1_754_011_805_000L, messages.last().createdAt)
        assertTrue(messages.all { it.status == MessageStatuses.COMPLETED })
        assertTrue(messages.all { it.sourceEventId == null && it.sourceTodoId == null })
        assertEquals(0, db.memoryDao().count())
    }

    @Test
    fun importingSameChatDocumentTwiceSkipsExistingRows() = runBlocking {
        val first = service.importChatHistory(validChatHistoryJson())
        val second = service.importChatHistory(validChatHistoryJson())

        assertTrue(first is DebugChatImportResult.Success)
        assertTrue(second is DebugChatImportResult.Success)
        second as DebugChatImportResult.Success
        assertEquals(0, second.insertedCount)
        assertEquals(2, second.skippedCount)
        assertEquals(2, db.messageDao().count())
    }

    @Test
    fun invalidRoleRejectsWholeChatDocumentWithoutPartialWrites() = runBlocking {
        val invalid = validChatHistoryJson().replace("ASSISTANT", "SYSTEM")

        val result = service.importChatHistory(invalid)

        assertTrue(result is DebugChatImportResult.Failure)
        assertTrue((result as DebugChatImportResult.Failure).description.contains("USER 或 ASSISTANT"))
        assertEquals(0, db.messageDao().count())
    }

    @Test
    fun timestampWithoutTimezoneIsRejectedWithoutPartialWrites() = runBlocking {
        val invalid = validChatHistoryJson().replace("2025-08-01T09:30:05+08:00", "2025-08-01T09:30:05")

        val result = service.importChatHistory(invalid)

        assertTrue(result is DebugChatImportResult.Failure)
        assertTrue((result as DebugChatImportResult.Failure).description.contains("带时区偏移"))
        assertEquals(0, db.messageDao().count())
    }

    @Test
    fun warningStageCannotBeReportedAsSuccessful() {
        val report = DebugRunReport(
            runId = "debug_run_test_0000000000000000000000000000",
            scenario = DebugAcceptanceScenario.INTERACTION_GAP,
            stages = listOf(
                DebugRunStage("后台任务", DebugRunStageStatus.PASS, "已返回"),
                DebugRunStage("消息投递", DebugRunStageStatus.WARNING, "未确认"),
            ),
            durationMillis = 1L,
        )

        assertFalse(report.isSuccessful)
    }

    private fun validChatHistoryJson(): String = """
        {
          "schema_version": 1,
          "messages": [
            {
              "role": "USER",
              "content": "以前的用户消息",
              "created_at": "2025-08-01T09:30:00+08:00"
            },
            {
              "role": "ASSISTANT",
              "content": "以前的 AI 回复",
              "created_at": "2025-08-01T09:30:05+08:00"
            }
          ]
        }
    """.trimIndent()
}
