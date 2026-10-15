package com.littlekitty.xiaokele.chat

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.animation.CatalogAnimationAdapter
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.network.model.FakeModelService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ChatControllerFallbackTest {
    @Test fun storageWriteFailureStillDisplaysPersonaSpeechAsCompanionBubble() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val time = object : TimeProvider {
                override fun now(): Instant = Instant.parse("2026-09-26T12:00:00Z")
                override fun zone(): ZoneId = ZoneId.of("UTC")
            }
            CharacterCoreBootstrap.ensureSeeded(db, time.now().toEpochMilli())
            val core = db.characterCoreDao().latest()!!
            db.characterCoreDao().upsert(core.copy(contentJson = """{"name":"测试犬丁","persona_anchor":"热情活泼"}"""))
            val pipeline = FrontChatPipeline(db, FakeModelService(), ChatContextBuilder(db, time), CatalogAnimationAdapter(), time)
            val controller = ChatController(db, pipeline)
            controller.refreshCompanionName()
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_message_insert BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT, 'test_storage_failure'); END",
            )
            controller.send("你好")
            val state = withTimeout(2_000) { controller.state.first { it.messages.isNotEmpty() } }
            val reply = state.messages.single()
            assertEquals("ASSISTANT", reply.role)
            assertTrue(reply.content.contains("测试犬丁"))
            assertNull(state.sendError)
            assertEquals(0, db.messageDao().count())
            assertFalse(reply.retryable)
        } finally { db.close() }
    }
}
