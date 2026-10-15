package com.littlekitty.xiaokele.channel

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.animation.CatalogAnimationAdapter
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.delivery.DeliveryOutbox
import com.littlekitty.xiaokele.core.database.entity.ActionExecutionEntity
import com.littlekitty.xiaokele.core.database.entity.ActionPlanEntity
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.state.ActionExecutionStatuses
import com.littlekitty.xiaokele.core.database.state.ActionPlanStatuses
import com.littlekitty.xiaokele.core.database.state.DeliveryStatuses
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.notification.NotificationAdapter
import com.littlekitty.xiaokele.notification.NotificationSendRequest
import com.littlekitty.xiaokele.notification.NotificationSendResult
import com.littlekitty.xiaokele.widget.WidgetRefreshResult
import com.littlekitty.xiaokele.widget.WidgetStateAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class OutboxRetrySchedulerTest {

    private lateinit var db: AppDatabase
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
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun dueKnownFailureIsRetriedAndOutcomeUnknownIsNeverAutoRetried() = runBlocking {
        val outbox = DeliveryOutbox(db, db.deliveryHistoryDao())
        val coordinator = ChannelExecutionCoordinator(
            db = db,
            deliveryOutbox = outbox,
            notificationAdapter = SuccessNotificationAdapter,
            widgetStateAdapter = NoOpWidgetStateAdapter,
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
        )

        val eventId = EntityId.generate("evt").value
        db.eventDao().upsert(
            EventEntity(
                id = eventId,
                eventKey = "retry:$eventId",
                type = "TEST_RETRY",
                source = "TEST",
                payloadJson = "{}",
                occurredAt = now,
                receivedAt = now,
                priority = 50,
                status = EventStatuses.PROCESSING,
            ),
        )
        val planId = EntityId.generate("plan").value
        db.actionPlanDao().insert(
            ActionPlanEntity(
                id = planId,
                eventId = eventId,
                agentOutputJson = "{}",
                status = ActionPlanStatuses.RUNNING,
                activeEventKey = eventId,
                createdAt = now,
            ),
        )
        val executionId = EntityId.generate("exec").value
        db.actionExecutionDao().upsert(
            ActionExecutionEntity(
                id = executionId,
                actionPlanId = planId,
                actionType = "SEND_NOTIFICATION",
                payloadJson = buildJsonObject { put("title", "重试"); put("body", "retry") }.toString(),
                status = ActionExecutionStatuses.FAILED,
                errorCode = "NOTIFICATION_ADAPTER_FAILURE",
            ),
        )
        db.deliveryHistoryDao().insert(
            DeliveryHistoryEntity(
                id = EntityId.generate("dlv").value,
                eventId = eventId,
                channel = DeliveryOutbox.CHANNEL_NOTIFICATION,
                status = DeliveryStatuses.FAILED,
                reservedAt = now - 60_000,
                attemptStartedAt = now - 60_000,
                attemptCount = 1,
                nextRetryAt = now - 1,
                errorCode = "NOTIFICATION_ADAPTER_FAILURE",
                executionId = executionId,
                cooldownGroup = "default",
            ),
        )

        val scheduler = OutboxRetryScheduler(outbox, coordinator, timeProvider)
        val report = scheduler.dispatchRetries()

        assertEquals(1, report.candidates)
        assertEquals(1, report.retried)
        assertEquals(DeliveryStatuses.DELIVERED, db.deliveryHistoryDao().byExecutionId(executionId)?.status)
        assertEquals(ActionExecutionStatuses.COMPLETED, db.actionExecutionDao().byId(executionId)?.status)
        assertEquals(ActionPlanStatuses.COMPLETED, db.actionPlanDao().byId(planId)?.status)
        assertEquals(EventStatuses.COMPLETED, db.eventDao().byId(eventId)?.status)

        val unknownExecution = EntityId.generate("exec").value
        db.deliveryHistoryDao().insert(
            DeliveryHistoryEntity(
                id = EntityId.generate("dlv").value,
                eventId = EntityId.generate("evt").value,
                channel = DeliveryOutbox.CHANNEL_NOTIFICATION,
                status = DeliveryStatuses.OUTCOME_UNKNOWN,
                reservedAt = now - 60_000,
                attemptStartedAt = now - 60_000,
                attemptCount = 1,
                errorCode = "UNKNOWN_SYSTEM_OUTCOME_AFTER_RESTART",
                executionId = unknownExecution,
            ),
        )
        val secondReport = scheduler.dispatchRetries()
        assertEquals(0, secondReport.candidates)
        assertEquals(0, secondReport.retried)
    }

    private object SuccessNotificationAdapter : NotificationAdapter {
        override suspend fun send(request: NotificationSendRequest): NotificationSendResult =
            NotificationSendResult.Delivered
    }

    private object NoOpWidgetStateAdapter : WidgetStateAdapter {
        override suspend fun refreshCurrentPetState(): WidgetRefreshResult =
            WidgetRefreshResult.Refreshed(emptyList())
    }
}
