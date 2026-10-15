package com.littlekitty.xiaokele.channel

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.animation.CatalogAnimationAdapter
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.delivery.DeliveryOutbox
import com.littlekitty.xiaokele.core.database.entity.ActionExecutionEntity
import com.littlekitty.xiaokele.core.database.entity.ActionPlanEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.state.ActionExecutionStatuses
import com.littlekitty.xiaokele.core.database.state.ActionPlanStatuses
import com.littlekitty.xiaokele.core.database.state.DeliveryStatuses
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import com.littlekitty.xiaokele.notification.NotificationAdapter
import com.littlekitty.xiaokele.notification.NotificationId
import com.littlekitty.xiaokele.notification.NotificationSendRequest
import com.littlekitty.xiaokele.notification.NotificationSendResult
import com.littlekitty.xiaokele.widget.WidgetRefreshResult
import com.littlekitty.xiaokele.widget.WidgetStateAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ChannelExecutionCoordinatorTest {

    private lateinit var db: AppDatabase
    private lateinit var coordinator: ChannelExecutionCoordinator
    private lateinit var fakeNotification: ConfigurableNotificationAdapter
    private lateinit var fakeWidget: RecordingWidgetStateAdapter
    private val now = 1_756_400_000_000L
    private val timeProvider = object : TimeProvider {
        override fun now(): Instant = Instant.ofEpochMilli(now)
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeNotification = ConfigurableNotificationAdapter()
        fakeWidget = RecordingWidgetStateAdapter()
        coordinator = ChannelExecutionCoordinator(
            db = db,
            deliveryOutbox = DeliveryOutbox(db, db.deliveryHistoryDao()),
            notificationAdapter = fakeNotification,
            widgetStateAdapter = fakeWidget,
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun fourChannelsAreIsolatedAndOneFailureDoesNotRollBackOthers() = runBlocking {
        fakeNotification.result = NotificationSendResult.AdapterFailure("fake system failure")
        val event = insertEvent()
        val plan = insertPlan(event.id)
        val executions = listOf(
            insertExecution(plan.id, "SEND_NOTIFICATION", buildJsonObject {
                put("title", "提醒")
                put("body", "通知失败也要隔离")
            }),
            insertExecution(plan.id, "SEND_PROACTIVE_MESSAGE", buildJsonObject {
                put("content", "主动消息成功")
            }),
            insertExecution(plan.id, "SET_WIDGET_STATE", buildJsonObject {
                put("state_id", "WAITING")
                put("short_text", "气泡")
                put("animation_id", "dog_not_imported")
            }),
            insertExecution(plan.id, "PLAY_ANIMATION", buildJsonObject {
                put("animation_id", "dog_not_imported")
            }),
        )

        val report = coordinator.executePlan(plan.id)

        assertEquals(1, report.failed)
        assertEquals(3, report.completed)
        assertEquals(
            ActionExecutionStatuses.FAILED,
            db.actionExecutionDao().byId(executions[0].id)?.status,
        )
        assertEquals(
            ActionExecutionStatuses.COMPLETED,
            db.actionExecutionDao().byId(executions[1].id)?.status,
        )
        assertEquals(
            ActionExecutionStatuses.COMPLETED,
            db.actionExecutionDao().byId(executions[2].id)?.status,
        )
        assertEquals(
            ActionExecutionStatuses.COMPLETED,
            db.actionExecutionDao().byId(executions[3].id)?.status,
        )
        assertEquals(ActionPlanStatuses.COMPLETED, db.actionPlanDao().byId(plan.id)?.status)
        assertEquals(EventStatuses.COMPLETED, db.eventDao().byId(event.id)?.status)
        assertEquals(1, db.messageDao().count())
        assertEquals("WAITING", db.petStateDao().get()?.stateId)
        assertTrue(fakeWidget.refreshCount > 0)

        val deliveries = db.deliveryHistoryDao().forEvent(event.id)
        assertEquals(4, deliveries.size)
        assertEquals(3, db.deliveryHistoryDao().countDelivered())
        assertEquals(
            DeliveryStatuses.FAILED,
            deliveries.first { it.channel == DeliveryOutbox.CHANNEL_NOTIFICATION }.status,
        )
    }

    @Test
    fun notificationPermissionDeniedWritesNoDeliveredAtAndDoesNotConsumeTodo() = runBlocking {
        fakeNotification.result = NotificationSendResult.PermissionDenied("test denied")
        val event = insertEvent()
        val todo = insertTodo(event.id)
        val plan = insertPlan(event.id)
        val execution = insertExecution(plan.id, "SEND_NOTIFICATION", buildJsonObject {
            put("title", "提醒")
            put("body", "权限拒绝")
            put("todo_id", todo.id)
        })

        coordinator.executePlan(plan.id)

        assertEquals(ActionExecutionStatuses.FAILED, db.actionExecutionDao().byId(execution.id)?.status)
        val delivery = db.deliveryHistoryDao().byExecutionId(execution.id)
        assertEquals(DeliveryStatuses.FAILED, delivery?.status)
        assertEquals(0L, delivery?.deliveredAt)
        assertEquals(0, db.deliveryHistoryDao().countDelivered())
        assertEquals(TodoStatuses.READY, db.todoDao().byId(todo.id)?.status)
        assertEquals(null, db.todoDao().byId(todo.id)?.claimedBy)
    }

    @Test
    fun notificationIdIsStableForSameExecutionAndDifferentForOthers() {
        val first = NotificationId.forExecutionId("exec_00000000000000000000000000000001")
        val second = NotificationId.forExecutionId("exec_00000000000000000000000000000001")
        val other = NotificationId.forExecutionId("exec_00000000000000000000000000000002")
        assertEquals(first, second)
        assertNotEquals(first, other)
        assertTrue(first > 0)
    }

    @Test
    fun proactiveMessageIsSingleSessionAndRepeatedPlanExecutionDoesNotDoubleWrite() = runBlocking {
        val event = insertEvent()
        val plan = insertPlan(event.id)
        insertExecution(plan.id, "SEND_PROACTIVE_MESSAGE", buildJsonObject {
            put("content", "只写一条主动消息")
        })

        coordinator.executePlan(plan.id)
        coordinator.executePlan(plan.id)

        assertEquals(1, db.messageDao().count())
        val delivery = db.deliveryHistoryDao().forEventAndChannel(event.id, DeliveryOutbox.CHANNEL_PROACTIVE_MESSAGE)
        assertEquals(DeliveryStatuses.DELIVERED, delivery?.status)
        assertEquals(1, db.deliveryHistoryDao().countDelivered())
    }

    @Test
    fun expiredEventIsQuarantinedBeforePlanExecutionCanReachTheAdapter() = runBlocking {
        val event = insertEvent(expiresAt = now - 1)
        val plan = insertPlan(event.id)
        val execution = insertExecution(plan.id, "SEND_NOTIFICATION", buildJsonObject {
            put("title", "过期事件")
            put("body", "不应投递")
        })

        val report = coordinator.executePlan(plan.id)

        assertTrue(report.results.isEmpty())
        assertEquals(0, fakeNotification.sendCount)
        assertEquals(EventStatuses.EXPIRED, db.eventDao().byId(event.id)?.status)
        assertEquals(ActionPlanStatuses.CANCELLED, db.actionPlanDao().byId(plan.id)?.status)
        assertEquals(ActionExecutionStatuses.CANCELLED, db.actionExecutionDao().byId(execution.id)?.status)
    }

    @Test
    fun expiredEventCannotBeResurrectedByFailedOutboxRetry() = runBlocking {
        val event = insertEvent(expiresAt = now - 1)
        val plan = insertPlan(event.id)
        val execution = insertExecution(plan.id, "SEND_NOTIFICATION", buildJsonObject {
            put("title", "重试")
            put("body", "不应重试")
        })
        db.actionExecutionDao().updateStatusAndError(
            execution.id,
            ActionExecutionStatuses.FAILED,
            "NOTIFICATION_ADAPTER_FAILURE",
            now,
        )
        db.deliveryHistoryDao().insert(
            com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity(
                id = EntityId.generate("dlv").value,
                eventId = event.id,
                channel = DeliveryOutbox.CHANNEL_NOTIFICATION,
                status = DeliveryStatuses.FAILED,
                reservedAt = now - 60_000,
                attemptStartedAt = now - 60_000,
                attemptCount = 1,
                nextRetryAt = now - 1,
                executionId = execution.id,
            ),
        )

        val result = coordinator.retryFailedExecution(execution.id)

        assertEquals(null, result)
        assertEquals(0, fakeNotification.sendCount)
        assertEquals(EventStatuses.EXPIRED, db.eventDao().byId(event.id)?.status)
        assertEquals(ActionPlanStatuses.CANCELLED, db.actionPlanDao().byId(plan.id)?.status)
        assertEquals(ActionExecutionStatuses.CANCELLED, db.actionExecutionDao().byId(execution.id)?.status)
        assertEquals(DeliveryStatuses.CANCELLED, db.deliveryHistoryDao().byExecutionId(execution.id)?.status)
    }

    @Test
    fun knownFailedOutboxCanRetryWhileCompletedSourceEventIsStillFresh() = runBlocking {
        val event = insertEvent()
        val plan = insertPlan(event.id)
        val execution = insertExecution(plan.id, "SEND_NOTIFICATION", buildJsonObject {
            put("title", "已完成事件的重试")
            put("body", "仍在 TTL 内")
        })
        db.actionPlanDao().updateStatusWithActiveKey(plan.id, ActionPlanStatuses.COMPLETED)
        db.eventDao().updateStatus(event.id, EventStatuses.COMPLETED)
        db.actionExecutionDao().updateStatusAndError(
            execution.id,
            ActionExecutionStatuses.FAILED,
            "NOTIFICATION_ADAPTER_FAILURE",
            now,
        )
        db.deliveryHistoryDao().insert(
            com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity(
                id = EntityId.generate("dlv").value,
                eventId = event.id,
                channel = DeliveryOutbox.CHANNEL_NOTIFICATION,
                status = DeliveryStatuses.FAILED,
                reservedAt = now - 60_000,
                attemptStartedAt = now - 60_000,
                attemptCount = 1,
                nextRetryAt = now - 1,
                executionId = execution.id,
            ),
        )

        val result = coordinator.retryFailedExecution(execution.id)

        assertEquals(ActionExecutionStatuses.COMPLETED, result?.status)
        assertEquals(1, fakeNotification.sendCount)
        assertEquals(EventStatuses.COMPLETED, db.eventDao().byId(event.id)?.status)
        assertEquals(ActionPlanStatuses.COMPLETED, db.actionPlanDao().byId(plan.id)?.status)
        assertEquals(DeliveryStatuses.DELIVERED, db.deliveryHistoryDao().byExecutionId(execution.id)?.status)
    }

    @Test
    fun concurrentPlanExecutionClaimsOneAdapterInvocation() = runBlocking {
        val event = insertEvent()
        val plan = insertPlan(event.id)
        val execution = insertExecution(plan.id, "SEND_NOTIFICATION", buildJsonObject {
            put("title", "并发")
            put("body", "只能发送一次")
        })

        coroutineScope {
            List(8) { async { coordinator.executePlan(plan.id) } }.awaitAll()
        }

        assertEquals(1, fakeNotification.sendCount)
        assertEquals(ActionExecutionStatuses.COMPLETED, db.actionExecutionDao().byId(execution.id)?.status)
        assertEquals(DeliveryStatuses.DELIVERED, db.deliveryHistoryDao().byExecutionId(execution.id)?.status)
    }

    @Test
    fun proactiveBudgetCountsWrittenChatWhenNotificationIsDeniedAndStaysHardUnderConcurrency() = runBlocking {
        fakeNotification.result = NotificationSendResult.PermissionDenied("test denied")
        val wakeEvent = insertEvent(type = ExternalEventTypeNames.WAKE_WEATHER_BRIEF)
        val wakePlan = insertPlan(wakeEvent.id)
        val wakeExecution = insertExecution(wakePlan.id, "SEND_PROACTIVE_MESSAGE", buildJsonObject {
            put("content", "通知权限关闭，但聊天里仍然有这条天气")
        })

        coordinator.executePlan(wakePlan.id)

        assertEquals(ActionExecutionStatuses.FAILED, db.actionExecutionDao().byId(wakeExecution.id)?.status)
        assertEquals(1, db.messageDao().count())
        assertEquals(1, fakeWidget.refreshCount)

        fakeNotification.result = NotificationSendResult.Delivered
        val remainingPlans = (1..3).map { index ->
            val event = insertEvent()
            insertPlan(event.id).also { plan ->
                insertExecution(plan.id, "SEND_PROACTIVE_MESSAGE", buildJsonObject {
                    put("content", "并发主动消息 $index")
                })
            }
        }

        val reports = coroutineScope {
            remainingPlans.map { plan -> async { coordinator.executePlan(plan.id) } }.awaitAll()
        }

        assertEquals(3, db.messageDao().count())
        assertEquals(3, fakeWidget.refreshCount)
        assertEquals(1, reports.flatMap { it.results }.count {
            it.status == ActionExecutionStatuses.CANCELLED &&
                it.errorCode == ChannelExecutionCoordinator.DAILY_PROACTIVE_BUDGET_BLOCKED
        })
    }

    @Test
    fun successfulTodoChannelCancelsOtherUnexecutedDuplicateOutputs() = runBlocking {
        val event = insertEvent()
        val todo = insertTodo(event.id)
        val plan = insertPlan(event.id)
        val proactive = insertExecution(plan.id, "SEND_PROACTIVE_MESSAGE", buildJsonObject {
            put("content", "同一 Todo 先走主动消息")
            put("todo_id", todo.id)
        })
        val notification = insertExecution(plan.id, "SEND_NOTIFICATION", buildJsonObject {
            put("title", "重复输出")
            put("body", "应被取消")
            put("todo_id", todo.id)
        })

        val report = coordinator.executePlan(plan.id)

        assertEquals(ActionExecutionStatuses.COMPLETED, db.actionExecutionDao().byId(proactive.id)?.status)
        assertEquals(ActionExecutionStatuses.CANCELLED, db.actionExecutionDao().byId(notification.id)?.status)
        assertEquals(TodoStatuses.EXECUTED, db.todoDao().byId(todo.id)?.status)
        assertEquals(1, db.deliveryHistoryDao().countDelivered())
        assertEquals(
            DeliveryOutbox.CHANNEL_PROACTIVE_MESSAGE,
            db.deliveryHistoryDao().forEvent(event.id).first { it.status == DeliveryStatuses.DELIVERED }.channel,
        )
        val cancelled = report.results.first { it.executionId == notification.id }
        assertTrue(
            cancelled.errorCode == "SUPERSEDED_BY_DELIVERY" ||
                cancelled.errorCode.orEmpty().startsWith("DELIVERY_BLOCKED_"),
        )
    }

    private suspend fun insertEvent(
        type: String = "TEST_CHANNEL",
        expiresAt: Long? = now + 600_000,
    ): EventEntity {
        val id = EntityId.generate("evt").value
        val entity = EventEntity(
            id = id,
            eventKey = "test:channel:$id",
            type = type,
            source = "TEST",
            payloadJson = "{}",
            occurredAt = now,
            receivedAt = now,
            expiresAt = expiresAt,
            priority = 50,
            status = EventStatuses.PROCESSING,
        )
        db.eventDao().upsert(entity)
        return entity
    }

    private suspend fun insertPlan(eventId: String): ActionPlanEntity {
        val entity = ActionPlanEntity(
            id = EntityId.generate("plan").value,
            eventId = eventId,
            agentOutputJson = "{}",
            status = ActionPlanStatuses.RUNNING,
            activeEventKey = eventId,
            createdAt = now,
        )
        db.actionPlanDao().insert(entity)
        return entity
    }

    private suspend fun insertTodo(eventId: String): TodoEntity {
        val entity = TodoEntity(
            id = EntityId.generate("todo").value,
            type = "DISCUSS_LATER",
            title = "测试 Todo",
            sourceEventId = eventId,
            status = TodoStatuses.READY,
            createdAt = now,
            updatedAt = now,
        )
        db.todoDao().upsert(entity)
        return entity
    }

    private suspend fun insertExecution(
        planId: String,
        actionType: String,
        payload: kotlinx.serialization.json.JsonObject,
    ): ActionExecutionEntity {
        val entity = ActionExecutionEntity(
            id = EntityId.generate("exec").value,
            actionPlanId = planId,
            actionType = actionType,
            payloadJson = payload.toString(),
            status = ActionExecutionStatuses.PENDING,
        )
        db.actionExecutionDao().upsert(entity)
        return entity
    }

    private class ConfigurableNotificationAdapter : NotificationAdapter {
        @Volatile
        var result: NotificationSendResult = NotificationSendResult.Delivered

        private val sends = AtomicInteger(0)

        val sendCount: Int
            get() = sends.get()

        override suspend fun send(request: NotificationSendRequest): NotificationSendResult {
            sends.incrementAndGet()
            return result
        }
    }

    private class RecordingWidgetStateAdapter : WidgetStateAdapter {
        @Volatile
        var refreshCount: Int = 0

        override suspend fun refreshCurrentPetState(): WidgetRefreshResult {
            refreshCount++
            return WidgetRefreshResult.Refreshed(emptyList())
        }
    }
}
