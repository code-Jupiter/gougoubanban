package com.littlekitty.xiaokele.scene

import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.database.entity.ActionExecutionEntity
import com.littlekitty.xiaokele.core.database.entity.ActionPlanEntity
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.state.ActionExecutionStatuses
import com.littlekitty.xiaokele.core.database.state.ActionPlanStatuses
import com.littlekitty.xiaokele.core.database.state.DeliveryStatuses
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import com.littlekitty.xiaokele.handoff.InternalHandoffRequest
import com.littlekitty.xiaokele.handoff.InternalHandoffResult
import com.littlekitty.xiaokele.handoff.IntentHandoffManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class WidgetHandoffTest {

    private lateinit var ctx: Phase7TestContext
    private lateinit var handoff: IntentHandoffManager
    private val now = Instant.parse("2026-08-16T10:00:00Z")

    @Before
    fun setUp() {
        ctx = buildPhase7TestContext(now)
        handoff = IntentHandoffManager(ctx.db, ctx.time)
    }

    @After
    fun tearDown() {
        ctx.close()
    }

    @Test
    fun widgetClickUsesTodoSceneContentAndCancelsPendingReachOutputs() = runBlocking {
        val eventId = EntityId.generate("evt").value
        val todoId = EntityId.generate("todo").value
        val planId = EntityId.generate("plan").value
        val executionId = EntityId.generate("exec").value
        val deliveryId = EntityId.generate("dlv").value

        ctx.db.eventDao().upsert(
            com.littlekitty.xiaokele.core.database.entity.EventEntity(
                id = eventId,
                eventKey = "scene:handoff:event",
                type = "INTERACTION_GAP",
                source = "TEST_SCENE",
                payloadJson = """{"handoff_text":"这是事件通用文案"}""",
                occurredAt = now.toEpochMilli(),
                receivedAt = now.toEpochMilli(),
                expiresAt = now.toEpochMilli() + 3_600_000,
                priority = 80,
                status = EventStatuses.COMPLETED,
            ),
        )
        ctx.db.todoDao().upsert(
            TodoEntity(
                id = todoId,
                type = "DISCUSS_LATER",
                title = "旧标题不应作为承接话术",
                payloadJson = buildJsonObject {
                    put("handoff_text", "测试用户，可乐从桌面一路接你进聊天，这条是 Todo 场景承接内容。")
                }.toString(),
                sourceEventId = eventId,
                status = TodoStatuses.READY,
                priority = 80,
                createdAt = now.toEpochMilli(),
                updatedAt = now.toEpochMilli(),
            ),
        )
        ctx.db.actionPlanDao().insert(
            ActionPlanEntity(
                id = planId,
                eventId = eventId,
                agentOutputJson = "{}",
                decisionSummary = "pending notification",
                status = ActionPlanStatuses.RUNNING,
                activeEventKey = eventId,
                createdAt = now.toEpochMilli(),
            ),
        )
        ctx.db.actionExecutionDao().upsert(
            ActionExecutionEntity(
                id = executionId,
                actionPlanId = planId,
                actionType = "SEND_NOTIFICATION",
                payloadJson = """{"title":"待取消通知"}""",
                status = ActionExecutionStatuses.PENDING,
            ),
        )
        ctx.db.deliveryHistoryDao().insert(
            DeliveryHistoryEntity(
                id = deliveryId,
                eventId = eventId,
                todoId = todoId,
                channel = "NOTIFICATION",
                contentHash = "test-handoff-cancel",
                status = DeliveryStatuses.RESERVED,
                reservedAt = now.toEpochMilli(),
                executionId = executionId,
                cooldownGroup = "scene:interaction_gap",
                deliveredAt = 0,
            ),
        )

        val result = handoff.handle(
            InternalHandoffRequest(
                eventId = eventId,
                todoId = todoId,
                deliveryId = deliveryId,
                source = "WIDGET",
            ),
        )

        assertTrue(result is InternalHandoffResult.Opened)
        val opened = result as InternalHandoffResult.Opened
        assertTrue(opened.cancelledReservations >= 1)
        assertTrue(opened.cancelledPendingExecutions >= 1)
        assertEquals(
            DeliveryStatuses.CANCELLED,
            ctx.db.deliveryHistoryDao().byId(deliveryId)?.status,
        )
        assertEquals(
            ActionExecutionStatuses.CANCELLED,
            ctx.db.actionExecutionDao().byId(executionId)?.status,
        )
        assertEquals(TodoStatuses.CLAIMED, ctx.db.todoDao().byId(todoId)?.status)
        val message = ctx.db.messageDao().byId(opened.messageId)
        assertTrue(message?.content?.contains("Todo 场景承接内容") == true)
        assertTrue(message?.content?.contains("旧标题") != true)

        val repeat = handoff.handle(InternalHandoffRequest(eventId = eventId, todoId = todoId))
        assertTrue(repeat is InternalHandoffResult.Opened)
        assertEquals(message?.id, (repeat as InternalHandoffResult.Opened).messageId)
        assertEquals(1, ctx.db.messageDao().count())
    }
}
