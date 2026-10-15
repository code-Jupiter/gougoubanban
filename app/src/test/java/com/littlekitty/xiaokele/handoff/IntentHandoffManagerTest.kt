package com.littlekitty.xiaokele.handoff

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.state.DeliveryStatuses
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class IntentHandoffManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var manager: IntentHandoffManager
    private val now = 1_756_400_000_000L
    private val timeProvider = object : TimeProvider {
        override fun now(): Instant = Instant.ofEpochMilli(now)
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        manager = IntentHandoffManager(db, timeProvider)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun invalidAndUnknownAndExpiredTargetsAreRejected() = runBlocking {
        assertTrue(manager.handle(InternalHandoffRequest(eventId = "not-an-id", todoId = null)) is InternalHandoffResult.Invalid)
        assertTrue(manager.handle(InternalHandoffRequest(eventId = null, todoId = null)) is InternalHandoffResult.Invalid)
        assertTrue(
            manager.handle(
                InternalHandoffRequest(eventId = EntityId.generate("evt").value, todoId = null),
            ) is InternalHandoffResult.Invalid,
        )

        val expiredEvent = insertEvent(expired = true)
        assertTrue(
            manager.handle(InternalHandoffRequest(eventId = expiredEvent.id, todoId = null)) is InternalHandoffResult.Invalid,
        )
        val expiredTodo = insertTodo(eventId = null, expired = true)
        assertTrue(
            manager.handle(InternalHandoffRequest(eventId = null, todoId = expiredTodo.id)) is InternalHandoffResult.Invalid,
        )
    }

    @Test
    fun validClickCreatesOneMessageClaimsTodoAtomicallyAndWritesOpenedOnce() = runBlocking {
        val event = insertEvent(expired = false)
        val todo = insertTodo(eventId = event.id, expired = false)
        db.deliveryHistoryDao().insert(
            DeliveryHistoryEntity(
                id = EntityId.generate("dlv").value,
                eventId = event.id,
                todoId = todo.id,
                channel = "NOTIFICATION",
                status = DeliveryStatuses.DELIVERED,
                deliveredAt = now - 1_000,
                executionId = EntityId.generate("exec").value,
            ),
        )

        val first = manager.handle(InternalHandoffRequest(eventId = event.id, todoId = todo.id))
        val second = manager.handle(InternalHandoffRequest(eventId = event.id, todoId = todo.id))

        assertTrue(first is InternalHandoffResult.Opened)
        assertTrue(second is InternalHandoffResult.Opened)
        val firstOpened = first as InternalHandoffResult.Opened
        val secondOpened = second as InternalHandoffResult.Opened
        assertEquals(true, firstOpened.createdMessage)
        assertEquals(false, secondOpened.createdMessage)
        assertEquals(firstOpened.messageId, secondOpened.messageId)
        assertEquals(1, db.messageDao().count())
        assertEquals(MessageStatuses.COMPLETED, db.messageDao().byId(firstOpened.messageId)?.status)
        assertEquals(TodoStatuses.CLAIMED, db.todoDao().byId(todo.id)?.status)
        assertEquals("chat:${firstOpened.messageId}", db.todoDao().byId(todo.id)?.claimedBy)
        assertEquals(1, db.deliveryHistoryDao().count())
        assertEquals(now, db.deliveryHistoryDao().forEvent(event.id).single().userOpenedAt)
    }

    @Test
    fun twentyConcurrentRepeatedClicksKeepOneMessageAndOneAtomicClaim() = runBlocking {
        val event = insertEvent(expired = false)
        val todo = insertTodo(eventId = event.id, expired = false)
        val request = InternalHandoffRequest(eventId = event.id, todoId = todo.id)

        val results = kotlinx.coroutines.coroutineScope {
            (1..20).map {
                async(Dispatchers.IO) { manager.handle(request) }
            }.awaitAll()
        }

        assertEquals(20, results.size)
        assertTrue(results.all { it is InternalHandoffResult.Opened })
        assertEquals(1, results.filterIsInstance<InternalHandoffResult.Opened>().count { it.createdMessage })
        assertEquals(1, db.messageDao().count())
        assertEquals(TodoStatuses.CLAIMED, db.todoDao().byId(todo.id)?.status)
    }

    @Test
    fun subsequentUserReplyWritesUserRepliedAtForLatestDeliveredSource() = runBlocking {
        val event = insertEvent(expired = false)
        db.deliveryHistoryDao().insert(
            DeliveryHistoryEntity(
                id = EntityId.generate("dlv").value,
                eventId = event.id,
                channel = "PROACTIVE_MESSAGE",
                status = DeliveryStatuses.DELIVERED,
                deliveredAt = now - 1_000,
            ),
        )
        db.messageDao().upsert(
            MessageEntity(
                id = EntityId.generate("msg").value,
                role = "ASSISTANT",
                content = "主动消息",
                createdAt = now - 1_000,
                sourceEventId = event.id,
                status = MessageStatuses.COMPLETED,
            ),
        )

        DeliveryEngagementTracker(db).onUserReplied(now)

        val delivery = db.deliveryHistoryDao().forEvent(event.id).single()
        assertEquals(now, delivery.userRepliedAt)
    }

    private suspend fun insertEvent(expired: Boolean): EventEntity {
        val id = EntityId.generate("evt").value
        val entity = EventEntity(
            id = id,
            eventKey = "test:handoff:$id",
            type = "TEST_HANDOFF",
            source = "TEST",
            payloadJson = "{}",
            occurredAt = now,
            receivedAt = now,
            expiresAt = if (expired) now - 1 else now + 600_000,
            priority = 50,
            status = EventStatuses.COMPLETED,
        )
        db.eventDao().upsert(entity)
        return entity
    }

    private suspend fun insertTodo(eventId: String?, expired: Boolean): TodoEntity {
        val entity = TodoEntity(
            id = EntityId.generate("todo").value,
            type = "DISCUSS_LATER",
            title = "承接测试",
            sourceEventId = eventId,
            status = TodoStatuses.READY,
            expiresAt = if (expired) now - 1 else null,
            createdAt = now,
            updatedAt = now,
        )
        db.todoDao().upsert(entity)
        return entity
    }
}
