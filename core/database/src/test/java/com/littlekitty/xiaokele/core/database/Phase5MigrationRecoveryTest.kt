package com.littlekitty.xiaokele.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames
import com.littlekitty.xiaokele.core.database.entity.ActionExecutionEntity
import com.littlekitty.xiaokele.core.database.entity.ActionPlanEntity
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.entity.TriggerEntity
import com.littlekitty.xiaokele.core.database.repository.RecoveryManager
import com.littlekitty.xiaokele.core.database.state.ActionExecutionStatuses
import com.littlekitty.xiaokele.core.database.state.ActionPlanStatuses
import com.littlekitty.xiaokele.core.database.state.DeliveryStatuses
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import com.littlekitty.xiaokele.core.database.state.TriggerStatuses
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Phase5MigrationRecoveryTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private var db: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "phase5-migration-${UUID.randomUUID().toString().replace("-", "")}"
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun backgroundLifecycleRequestReservationIsDurableAndBounded() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val room = db ?: error("db not created")
        room.eventDao().insert(
            EventEntity(
                id = "evt_lifecycle_budget",
                eventKey = "evt_lifecycle_budget",
                type = "IDLE_FOLLOW_UP",
                source = "CHAT_IDLE_FOLLOW_UP",
                payloadJson = "{}",
                occurredAt = 1L,
                receivedAt = 1L,
                status = EventStatuses.PROCESSING,
            ),
        )

        assertEquals(1, room.eventDao().reserveGenerationRequests("evt_lifecycle_budget", 11, 12))
        assertEquals(0, room.eventDao().reserveGenerationRequests("evt_lifecycle_budget", 2, 12))
        assertEquals(11, room.eventDao().byId("evt_lifecycle_budget")?.generationRequestCount)
    }

    @Test
    fun version3ToLatestMigrationKeepsEveryRowAndAddsOutboxColumnsAndUniqueIndexes() = runBlocking {
        createVersion3Database()
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppMigrations.MIGRATION_3_4,
                AppMigrations.MIGRATION_4_5,
                AppMigrations.MIGRATION_5_6,
                AppMigrations.MIGRATION_6_7,
                AppMigrations.MIGRATION_7_8,
                AppMigrations.MIGRATION_8_9,
                AppMigrations.MIGRATION_9_10,
                AppMigrations.MIGRATION_10_11,
                AppMigrations.MIGRATION_11_12,
            )
            .build()
        val room = db ?: error("db not created")

        assertEquals(AppDatabase.VERSION, room.openHelper.readableDatabase.version)

        val oldMessage = room.messageDao().byId("msg_v3_user")
        val oldDelivery = room.deliveryHistoryDao().byId("dlv_v3")
        assertNotNull(oldMessage)
        assertEquals(MessageStatuses.COMPLETED, oldMessage?.status)
        assertNotNull(oldDelivery)
        assertEquals(DeliveryStatuses.DELIVERED, oldDelivery?.status)
        assertEquals(1_700_000_000_000L, oldDelivery?.deliveredAt)
        assertEquals(0, oldDelivery?.attemptCount)
        assertNull(oldDelivery?.reservedAt)
        assertNull(oldDelivery?.executionId)

        val indexes = room.openHelper.readableDatabase
            .query("PRAGMA index_list(`delivery_history`)")
            .use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        assertNotNull("unique event/channel index missing") {
            indexes.contains("index_delivery_history_event_id_channel")
        }
        assertNotNull("outbox status index missing") {
            indexes.contains("index_delivery_history_status")
        }

        val messageIndexes = room.openHelper.readableDatabase
            .query("PRAGMA index_list(`messages`)")
            .use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        assertNotNull("unique message source_event/role index missing") {
            messageIndexes.contains("index_messages_source_event_id_role")
        }
    }

    @Test
    fun runningExecutionRecoveryClassifiesNotInvokedUnknownAndCompleted() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val room = db ?: error("db not created")
        val now = 1_756_400_000_000L

        room.actionPlanDao().insert(
            ActionPlanEntity(
                id = "plan_recoverable",
                eventId = "evt_00000000000000000000000000000001",
                agentOutputJson = "{}",
                status = "RUNNING",
                activeEventKey = "evt_00000000000000000000000000000001",
                createdAt = now,
            ),
        )
        room.actionExecutionDao().upsert(
            ActionExecutionEntity(
                id = "exec_not_invoked",
                actionPlanId = "plan_recoverable",
                actionType = "SEND_NOTIFICATION",
                status = ActionExecutionStatuses.RUNNING,
            ),
        )
        room.deliveryHistoryDao().insert(
            DeliveryHistoryEntity(
                id = "dlv_not_invoked",
                eventId = "evt_00000000000000000000000000000001",
                channel = "NOTIFICATION",
                status = DeliveryStatuses.RESERVED,
                reservedAt = now,
                executionId = "exec_not_invoked",
            ),
        )
        room.actionExecutionDao().upsert(
            ActionExecutionEntity(
                id = "exec_unknown",
                actionPlanId = "plan_recoverable",
                actionType = "SEND_NOTIFICATION",
                status = ActionExecutionStatuses.RUNNING,
            ),
        )
        room.deliveryHistoryDao().insert(
            DeliveryHistoryEntity(
                id = "dlv_unknown",
                eventId = "evt_00000000000000000000000000000002",
                channel = "NOTIFICATION",
                status = DeliveryStatuses.RESERVED,
                reservedAt = now,
                attemptStartedAt = now,
                executionId = "exec_unknown",
            ),
        )
        room.actionExecutionDao().upsert(
            ActionExecutionEntity(
                id = "exec_delivered",
                actionPlanId = "plan_recoverable",
                actionType = "SEND_PROACTIVE_MESSAGE",
                status = ActionExecutionStatuses.RUNNING,
            ),
        )
        room.deliveryHistoryDao().insert(
            DeliveryHistoryEntity(
                id = "dlv_delivered",
                eventId = "evt_00000000000000000000000000000003",
                channel = "PROACTIVE_MESSAGE",
                status = DeliveryStatuses.DELIVERED,
                reservedAt = now,
                deliveredAt = now - 1,
                executionId = "exec_delivered",
            ),
        )

        val report = RecoveryManager(room).run(now)

        assertEquals(1, report.recoveredActionExecutionsNotInvoked)
        assertEquals(1, report.actionExecutionsOutcomeUnknown)
        assertEquals(ActionExecutionStatuses.PENDING, room.actionExecutionDao().byId("exec_not_invoked")?.status)
        assertEquals(ActionExecutionStatuses.FAILED, room.actionExecutionDao().byId("exec_unknown")?.status)
        assertEquals(DeliveryStatuses.OUTCOME_UNKNOWN, room.deliveryHistoryDao().byId("dlv_unknown")?.status)
        assertEquals(ActionExecutionStatuses.COMPLETED, room.actionExecutionDao().byId("exec_delivered")?.status)
    }

    @Test
    fun expiredEventCancelsReplayableOutputAndUnacceptedMutationsButPreservesAcceptedTodos() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val room = db ?: error("db not created")
        val now = 1_756_400_000_000L
        val eventId = "evt_expired_source"
        room.eventDao().upsert(
            EventEntity(
                id = eventId,
                eventKey = "expired:source",
                type = ExternalEventTypeNames.WAKE_WEATHER_BRIEF,
                source = "TEST",
                payloadJson = "{}",
                occurredAt = now - 60_000,
                receivedAt = now - 60_000,
                expiresAt = now - 1,
                priority = 50,
                status = EventStatuses.PROCESSING,
            ),
        )
        room.actionPlanDao().insert(
            ActionPlanEntity(
                id = "plan_expired_source",
                eventId = eventId,
                agentOutputJson = "{}",
                status = ActionPlanStatuses.RUNNING,
                activeEventKey = eventId,
                createdAt = now - 60_000,
            ),
        )
        room.actionExecutionDao().upsert(
            ActionExecutionEntity(
                id = "exec_expired_pending",
                actionPlanId = "plan_expired_source",
                actionType = "SEND_NOTIFICATION",
                status = ActionExecutionStatuses.PENDING,
            ),
        )
        room.deliveryHistoryDao().insert(
            DeliveryHistoryEntity(
                id = "dlv_expired_pending",
                eventId = eventId,
                channel = "NOTIFICATION",
                status = DeliveryStatuses.RESERVED,
                reservedAt = now - 60_000,
                executionId = "exec_expired_pending",
            ),
        )
        room.actionExecutionDao().upsert(
            ActionExecutionEntity(
                id = "exec_expired_wake",
                actionPlanId = "plan_expired_source",
                actionType = "SEND_PROACTIVE_MESSAGE",
                status = ActionExecutionStatuses.RUNNING,
            ),
        )
        room.deliveryHistoryDao().insert(
            DeliveryHistoryEntity(
                id = "dlv_expired_wake",
                eventId = eventId,
                channel = "PROACTIVE_MESSAGE",
                status = DeliveryStatuses.RESERVED,
                reservedAt = now - 60_000,
                attemptStartedAt = now - 30_000,
                attemptCount = 1,
                executionId = "exec_expired_wake",
            ),
        )
        room.todoDao().upsert(
            TodoEntity(
                id = "todo_unaccepted",
                type = "DISCUSS_LATER",
                sourceEventId = eventId,
                status = TodoStatuses.READY,
                createdAt = now - 60_000,
                updatedAt = now - 60_000,
            ),
        )
        room.todoDao().upsert(
            TodoEntity(
                id = "todo_chat_claimed",
                type = "DISCUSS_LATER",
                sourceEventId = eventId,
                status = TodoStatuses.CLAIMED,
                claimedBy = "chat:msg_user_accepted",
                claimedAt = now - 60_000,
                createdAt = now - 60_000,
                updatedAt = now - 60_000,
            ),
        )
        room.todoDao().upsert(
            TodoEntity(
                id = "todo_executed",
                type = "DISCUSS_LATER",
                sourceEventId = eventId,
                status = TodoStatuses.EXECUTED,
                createdAt = now - 60_000,
                updatedAt = now - 60_000,
            ),
        )
        room.triggerDao().upsert(
            TriggerEntity(
                id = "trg_unaccepted_active",
                todoId = "todo_unaccepted",
                triggerType = "TIME",
                nextFireAt = now + 60_000,
                status = TriggerStatuses.ACTIVE,
            ),
        )
        room.triggerDao().upsert(
            TriggerEntity(
                id = "trg_unaccepted_fired",
                todoId = "todo_unaccepted",
                triggerType = "TIME",
                lastFireAt = now - 1,
                status = TriggerStatuses.FIRED,
            ),
        )
        room.triggerDao().upsert(
            TriggerEntity(
                id = "trg_chat_claimed",
                todoId = "todo_chat_claimed",
                triggerType = "TIME",
                nextFireAt = now + 60_000,
                status = TriggerStatuses.ACTIVE,
            ),
        )
        room.triggerDao().upsert(
            TriggerEntity(
                id = "trg_executed",
                todoId = "todo_executed",
                triggerType = "TIME",
                nextFireAt = now + 60_000,
                status = TriggerStatuses.ACTIVE,
            ),
        )

        val report = RecoveryManager(room).run(now)

        assertEquals(EventStatuses.EXPIRED, room.eventDao().byId(eventId)?.status)
        assertEquals(ActionPlanStatuses.CANCELLED, room.actionPlanDao().byId("plan_expired_source")?.status)
        assertEquals(ActionExecutionStatuses.CANCELLED, room.actionExecutionDao().byId("exec_expired_pending")?.status)
        assertEquals(DeliveryStatuses.CANCELLED, room.deliveryHistoryDao().byId("dlv_expired_pending")?.status)
        assertEquals(ActionExecutionStatuses.FAILED, room.actionExecutionDao().byId("exec_expired_wake")?.status)
        assertEquals(DeliveryStatuses.OUTCOME_UNKNOWN, room.deliveryHistoryDao().byId("dlv_expired_wake")?.status)

        assertEquals(TodoStatuses.CANCELLED, room.todoDao().byId("todo_unaccepted")?.status)
        assertEquals(TriggerStatuses.CANCELLED, room.triggerDao().byId("trg_unaccepted_active")?.status)
        assertEquals(TriggerStatuses.CANCELLED, room.triggerDao().byId("trg_unaccepted_fired")?.status)
        assertEquals(TodoStatuses.CLAIMED, room.todoDao().byId("todo_chat_claimed")?.status)
        assertEquals(TriggerStatuses.ACTIVE, room.triggerDao().byId("trg_chat_claimed")?.status)
        assertEquals(TodoStatuses.EXECUTED, room.todoDao().byId("todo_executed")?.status)
        assertEquals(TriggerStatuses.COMPLETED, room.triggerDao().byId("trg_executed")?.status)
        assertEquals(1, report.cancelledExpiredSourceTodos)
        assertEquals(2, report.cancelledExpiredSourceTriggers)
        assertEquals(1, report.quarantinedExpiredEventDeliveries)
    }

    @Test
    fun validLocalProactiveAttemptReplaysButWakeAttemptIsFrozenAfterRestart() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val room = db ?: error("db not created")
        val now = 1_756_400_000_000L
        val localEventId = "evt_local_proactive"
        val wakeEventId = "evt_wake_proactive"
        for (event in listOf(
            EventEntity(
                id = localEventId,
                eventKey = "local:proactive",
                type = "TEST_LOCAL_PROACTIVE",
                source = "TEST",
                payloadJson = "{}",
                occurredAt = now,
                receivedAt = now,
                expiresAt = now + 60_000,
                priority = 50,
                status = EventStatuses.PROCESSING,
            ),
            EventEntity(
                id = wakeEventId,
                eventKey = "wake:proactive",
                type = ExternalEventTypeNames.WAKE_WEATHER_BRIEF,
                source = "TEST",
                payloadJson = "{}",
                occurredAt = now,
                receivedAt = now,
                expiresAt = now + 60_000,
                priority = 50,
                status = EventStatuses.PROCESSING,
            ),
        )) {
            room.eventDao().upsert(event)
        }
        for (plan in listOf(
            ActionPlanEntity(
                id = "plan_local_proactive",
                eventId = localEventId,
                agentOutputJson = "{}",
                status = ActionPlanStatuses.RUNNING,
                activeEventKey = localEventId,
                createdAt = now,
            ),
            ActionPlanEntity(
                id = "plan_wake_proactive",
                eventId = wakeEventId,
                agentOutputJson = "{}",
                status = ActionPlanStatuses.RUNNING,
                activeEventKey = wakeEventId,
                createdAt = now,
            ),
        )) {
            room.actionPlanDao().insert(plan)
        }
        for (execution in listOf(
            ActionExecutionEntity(
                id = "exec_local_proactive",
                actionPlanId = "plan_local_proactive",
                actionType = "SEND_PROACTIVE_MESSAGE",
                status = ActionExecutionStatuses.RUNNING,
            ),
            ActionExecutionEntity(
                id = "exec_wake_proactive",
                actionPlanId = "plan_wake_proactive",
                actionType = "SEND_PROACTIVE_MESSAGE",
                status = ActionExecutionStatuses.RUNNING,
            ),
        )) {
            room.actionExecutionDao().upsert(execution)
        }
        for (delivery in listOf(
            DeliveryHistoryEntity(
                id = "dlv_local_proactive",
                eventId = localEventId,
                channel = "PROACTIVE_MESSAGE",
                status = DeliveryStatuses.RESERVED,
                reservedAt = now - 1,
                attemptStartedAt = now - 1,
                attemptCount = 1,
                executionId = "exec_local_proactive",
            ),
            DeliveryHistoryEntity(
                id = "dlv_wake_proactive",
                eventId = wakeEventId,
                channel = "PROACTIVE_MESSAGE",
                status = DeliveryStatuses.RESERVED,
                reservedAt = now - 1,
                attemptStartedAt = now - 1,
                attemptCount = 1,
                executionId = "exec_wake_proactive",
            ),
        )) {
            room.deliveryHistoryDao().insert(delivery)
        }

        val report = RecoveryManager(room).run(now)

        assertEquals(EventStatuses.PROCESSING, room.eventDao().byId(localEventId)?.status)
        assertEquals(ActionExecutionStatuses.PENDING, room.actionExecutionDao().byId("exec_local_proactive")?.status)
        assertEquals(DeliveryStatuses.RESERVED, room.deliveryHistoryDao().byId("dlv_local_proactive")?.status)
        assertEquals(null, room.deliveryHistoryDao().byId("dlv_local_proactive")?.attemptStartedAt)
        assertEquals(ActionExecutionStatuses.FAILED, room.actionExecutionDao().byId("exec_wake_proactive")?.status)
        assertEquals(DeliveryStatuses.OUTCOME_UNKNOWN, room.deliveryHistoryDao().byId("dlv_wake_proactive")?.status)
        assertEquals(1, report.recoveredActionExecutionsNotInvoked)
        assertEquals(1, report.actionExecutionsOutcomeUnknown)
    }

    @Test
    fun conditionalPlanStartCannotReviveAPlanCancelledByExpiryRecovery() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val room = db ?: error("db not created")
        val now = 1_756_400_000_000L
        val eventId = "evt_plan_transition_race"
        room.actionPlanDao().insert(
            ActionPlanEntity(
                id = "plan_transition_race",
                eventId = eventId,
                agentOutputJson = "{}",
                status = ActionPlanStatuses.PENDING,
                activeEventKey = eventId,
                createdAt = now,
            ),
        )

        assertEquals(1, room.actionPlanDao().cancelActive("plan_transition_race"))
        assertEquals(
            0,
            room.actionPlanDao().transitionStatusWithActiveKey(
                "plan_transition_race",
                ActionPlanStatuses.PENDING,
                ActionPlanStatuses.RUNNING,
            ),
        )
        val plan = room.actionPlanDao().byId("plan_transition_race")
        assertEquals(ActionPlanStatuses.CANCELLED, plan?.status)
        assertEquals(null, plan?.activeEventKey)
    }

    private fun createVersion3Database() {
        val framework = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        Phase1MigrationSql.statements.forEach { statement -> framework.execSQL(statement) }
        framework.execSQL("ALTER TABLE `messages` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'COMPLETED'")
        framework.execSQL("ALTER TABLE `messages` ADD COLUMN `error_code` TEXT")
        framework.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_status` ON `messages` (`status`)")
        framework.execSQL(
            """
            INSERT INTO `messages`
            (`id`, `role`, `content`, `created_at`, `source_event_id`, `source_todo_id`, `animation_id`, `status`, `error_code`)
            VALUES ('msg_v3_user', 'USER', 'v3 历史消息', 1700000000000, 'evt_v3', NULL, NULL, 'COMPLETED', NULL)
            """.trimIndent(),
        )
        framework.execSQL(
            """
            INSERT INTO `delivery_history`
            (`id`, `event_id`, `todo_id`, `channel`, `content_hash`, `delivered_at`, `user_opened_at`, `user_replied_at`)
            VALUES ('dlv_v3', 'evt_v3', NULL, 'DEBUG', 'hash-v3', 1700000000000, NULL, NULL)
            """.trimIndent(),
        )
        framework.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        framework.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'ce203d6712b85ea992f9292bfe32ff9b')",
        )
        framework.version = 3
        framework.close()
    }
}
