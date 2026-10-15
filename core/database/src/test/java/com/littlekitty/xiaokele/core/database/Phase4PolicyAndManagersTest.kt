package com.littlekitty.xiaokele.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.protocol.MutationOperationNames
import com.littlekitty.xiaokele.core.common.protocol.TodoMutationCommand
import com.littlekitty.xiaokele.core.common.protocol.TodoTypeNames
import com.littlekitty.xiaokele.core.common.protocol.TriggerMutationCommand
import com.littlekitty.xiaokele.core.common.protocol.TriggerTypeNames
import com.littlekitty.xiaokele.core.database.delivery.DeliveryBlockReasons
import com.littlekitty.xiaokele.core.database.delivery.DeliveryDedupDecision
import com.littlekitty.xiaokele.core.database.delivery.DeliveryDedupGuard
import com.littlekitty.xiaokele.core.database.delivery.DeliveryDedupRequest
import com.littlekitty.xiaokele.core.database.entity.ActionPlanEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.policy.DevelopmentCandidatePolicies
import com.littlekitty.xiaokele.core.database.policy.PolicyGateResult
import com.littlekitty.xiaokele.core.database.policy.PolicyModes
import com.littlekitty.xiaokele.core.database.policy.PolicyResolution
import com.littlekitty.xiaokele.core.database.policy.PolicyResolver
import com.littlekitty.xiaokele.core.database.repository.BackgroundMutationExecutor
import com.littlekitty.xiaokele.core.database.repository.MutationExecutionException
import com.littlekitty.xiaokele.core.database.repository.RecoveryManager
import com.littlekitty.xiaokele.core.database.repository.TodoClaimants
import com.littlekitty.xiaokele.core.database.repository.TodoClaimCoordinator
import com.littlekitty.xiaokele.core.database.repository.TodoManager
import com.littlekitty.xiaokele.core.database.repository.TriggerManager
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import com.littlekitty.xiaokele.core.database.state.TriggerStatuses
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Phase4PolicyAndManagersTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private val now = 1_756_400_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun resolverSupportsStrictGuidedOpenAndForcedNoAction() = runBlocking {
        DevelopmentCandidatePolicies.ensureSeeded(db)
        val resolver = PolicyResolver(db)

        val strictEvent = event(type = DevelopmentCandidatePolicies.EVENT_TYPE_TEST_STRICT, id = "evt_00000000000000000000000000000001", key = "k1")
        val strict = resolver.resolve(strictEvent)
        assertTrue(strict is PolicyResolution.Matched)
        assertEquals(PolicyModes.STRICT, (strict as PolicyResolution.Matched).mode)
        assertTrue(resolver.gate(strict) is PolicyGateResult.AllowAgent)

        val guidedEvent = event(type = DevelopmentCandidatePolicies.EVENT_TYPE_TEST_GUIDED, id = "evt_00000000000000000000000000000002", key = "k2")
        val guided = resolver.resolve(guidedEvent)
        assertEquals(PolicyModes.GUIDED, (guided as PolicyResolution.Matched).mode)

        val noActionEvent = event(type = DevelopmentCandidatePolicies.EVENT_TYPE_TEST_NO_ACTION, id = "evt_00000000000000000000000000000003", key = "k3")
        val noActionGate = resolver.gate(resolver.resolve(noActionEvent))
        assertTrue(noActionGate is PolicyGateResult.ForceNoAction)

        val unknownEvent = event(type = "UNKNOWN_OPEN_EVENT", id = "evt_00000000000000000000000000000004", key = "k4")
        val open = resolver.resolve(unknownEvent)
        assertTrue(open is PolicyResolution.OpenFallback)
        assertTrue(resolver.gate(open) is PolicyGateResult.AllowAgent)
    }

    @Test
    fun todoManagerCompletesWaitingReadyClaimExecuteFlow() = runBlocking {
        val manager = TodoManager(db, db.todoDao())
        val todo = manager.create(
            type = TodoTypeNames.DISCUSS_LATER,
            now = now,
            availableAt = now + 60_000,
            expiresAt = now + 3600_000,
        )
        assertEquals(TodoStatuses.WAITING, todo.status)

        assertEquals(0, manager.promoteDueWaiting(now))
        assertEquals(1, manager.promoteDueWaiting(now + 60_001))
        assertEquals(TodoStatuses.READY, manager.byId(todo.id)?.status)

        val claimed = manager.claim(todo.id, "${TodoClaimants.CHAT}:msg", now + 70_000)
        assertEquals(TodoStatuses.CLAIMED, claimed?.status)
        assertNull(manager.claim(todo.id, "${TodoClaimants.BACKGROUND}:evt", now + 70_001))

        val executed = manager.markExecuted(todo.id, now + 80_000)
        assertEquals(TodoStatuses.EXECUTED, executed.status)
    }

    @Test
    fun triggerManagerFiresAtomicallyThenCompletesFired() = runBlocking {
        val todoManager = TodoManager(db, db.todoDao())
        val todo = todoManager.create(type = TodoTypeNames.REEVALUATE_LATER, now = now)
        val triggerManager = TriggerManager(db, db.triggerDao())
        val trigger = triggerManager.create(
            todoId = todo.id,
            triggerType = TriggerTypeNames.TIME,
            triggerConfigJson = """{"fire_at":"future"}""",
            nextFireAt = now + 10_000,
        )

        assertNull(triggerManager.fireDue(trigger.id, now))
        val fired = triggerManager.fireDue(trigger.id, now + 10_000)
        assertEquals(TriggerStatuses.FIRED, fired?.status)
        assertNull(triggerManager.fireDue(trigger.id, now + 20_000))
        assertEquals(TriggerStatuses.COMPLETED, triggerManager.completeFired(trigger.id).status)
    }

    @Test
    fun mutationExecutorCreatesTodoAndTriggerInOneTransactionAndRejectsCrossEvent() = runBlocking {
        val executor = BackgroundMutationExecutor(db, db.todoDao(), db.triggerDao())
        val event = event(type = "TEST", id = "evt_00000000000000000000000000000010", key = "k10").copy(status = EventStatuses.PROCESSING)

        val report = executor.apply(
            event = event,
            todoCommands = listOf(
                TodoMutationCommand.Create(
                    requestedId = "todo_00000000000000000000000000000010",
                    type = TodoTypeNames.DISCUSS_LATER,
                    title = "候选话题",
                    payload = buildJsonObject { put("topic", "test") },
                    sourceEventId = event.id,
                    priority = 30,
                    availableAtEpochMillis = null,
                    expiresAtEpochMillis = now + 3_600_000,
                ),
            ),
            triggerCommands = listOf(
                TriggerMutationCommand.Create(
                    requestedId = "trg_00000000000000000000000000000010",
                    todoId = "todo_00000000000000000000000000000010",
                    triggerType = TriggerTypeNames.TIME,
                    nextFireAtEpochMillis = now + 120_000,
                    triggerConfigJson = buildJsonObject { put("delay", "PT2M") },
                ),
            ),
            now = now,
        )
        assertEquals(1, report.todosCreated)
        assertEquals(1, report.triggersCreated)
        assertEquals(TodoStatuses.READY, db.todoDao().byId("todo_00000000000000000000000000000010")?.status)
        assertEquals(TriggerStatuses.ACTIVE, db.triggerDao().byId("trg_00000000000000000000000000000010")?.status)

        val crossEvent = event(type = "TEST", id = "evt_00000000000000000000000000000011", key = "k11").copy(status = EventStatuses.PROCESSING)
        val error = runCatching {
            executor.apply(
                event = crossEvent,
                todoCommands = listOf(TodoMutationCommand.Cancel("todo_00000000000000000000000000000010")),
                triggerCommands = emptyList(),
                now = now + 1,
            )
        }.exceptionOrNull()
        assertTrue(error is MutationExecutionException)
        assertEquals(TodoStatuses.READY, db.todoDao().byId("todo_00000000000000000000000000000010")?.status)
    }

    @Test
    fun mutationExecutorRejectsCreateIdCollisionWithoutReplacingExistingRow() = runBlocking {
        val executor = BackgroundMutationExecutor(db, db.todoDao(), db.triggerDao())
        val existing = TodoManager(db, db.todoDao()).create(
            type = TodoTypeNames.DISCUSS_LATER,
            title = "保留原记录",
            sourceEventId = "evt_00000000000000000000000000000040",
            now = now,
        )
        val event = event(type = "TEST", id = "evt_00000000000000000000000000000041", key = "k41")
            .copy(status = EventStatuses.PROCESSING)

        val error = runCatching {
            executor.apply(
                event = event,
                todoCommands = listOf(
                    TodoMutationCommand.Create(
                        requestedId = existing.id,
                        type = TodoTypeNames.DISCUSS_LATER,
                        title = "不能覆盖",
                        payload = null,
                        sourceEventId = event.id,
                        priority = 0,
                        availableAtEpochMillis = null,
                        expiresAtEpochMillis = now + 60_000,
                    ),
                ),
                triggerCommands = emptyList(),
                now = now,
            )
        }.exceptionOrNull()

        assertTrue(error is MutationExecutionException)
        assertEquals("保留原记录", db.todoDao().byId(existing.id)?.title)
        assertEquals(existing.sourceEventId, db.todoDao().byId(existing.id)?.sourceEventId)
    }

    @Test
    fun deliveryGuardBlocksDuplicateContentAndCooldownButAllowsFirst() = runBlocking {
        val guard = DeliveryDedupGuard(db.deliveryHistoryDao())
        val first = guard.check(
            DeliveryDedupRequest(
                eventId = "evt_00000000000000000000000000000020",
                todoId = null,
                channel = DeliveryDedupGuard.CHANNEL_NOTIFICATION,
                content = "same-content",
                nowEpochMillis = now,
            ),
        )
        assertTrue(first is DeliveryDedupDecision.Allowed)
        guard.recordAllowed(
            DeliveryDedupRequest(
                eventId = "evt_00000000000000000000000000000020",
                todoId = null,
                channel = DeliveryDedupGuard.CHANNEL_NOTIFICATION,
                content = "same-content",
                nowEpochMillis = now,
            ),
            (first as DeliveryDedupDecision.Allowed).contentHash,
        )

        val duplicate = guard.check(
            DeliveryDedupRequest(
                eventId = "evt_00000000000000000000000000000021",
                todoId = null,
                channel = DeliveryDedupGuard.CHANNEL_NOTIFICATION,
                content = "same-content",
                nowEpochMillis = now + 1_000,
            ),
        )
        assertEquals(DeliveryBlockReasons.DUPLICATE_CONTENT, (duplicate as DeliveryDedupDecision.Blocked).reason)

        val cooldown = guard.check(
            DeliveryDedupRequest(
                eventId = "evt_00000000000000000000000000000022",
                todoId = null,
                channel = DeliveryDedupGuard.CHANNEL_NOTIFICATION,
                content = "different-content",
                nowEpochMillis = now + 60_000,
            ),
        )
        assertEquals(DeliveryBlockReasons.MIN_INTERVAL, (cooldown as DeliveryDedupDecision.Blocked).reason)
    }

    @Test
    fun terminalTodoCascadesToItsTriggers() = runBlocking {
        val todoManager = TodoManager(db, db.todoDao(), db.triggerDao())
        val triggerManager = TriggerManager(db, db.triggerDao())
        val todo = todoManager.create(type = TodoTypeNames.REEVALUATE_LATER, now = now)
        val trigger = triggerManager.create(
            todoId = todo.id,
            triggerType = TriggerTypeNames.TIME,
            triggerConfigJson = """{"fire_at":"future"}""",
            nextFireAt = now + 60_000,
        )

        todoManager.markExecuted(todo.id, now + 1_000)

        assertEquals(TriggerStatuses.COMPLETED, triggerManager.byId(trigger.id)?.status)
    }

    @Test
    fun recoveryFailsStaleActiveActionPlansAndReleasesSlot() = runBlocking {
        db.actionPlanDao().insert(
            ActionPlanEntity(
                id = "plan_stale",
                eventId = "evt_00000000000000000000000000000030",
                agentOutputJson = "{}",
                status = "RUNNING",
                activeEventKey = "evt_00000000000000000000000000000030",
                createdAt = now,
            ),
        )

        val report = RecoveryManager(db).run(now)

        assertEquals(1, report.failedStaleActionPlans)
        assertEquals("FAILED", db.actionPlanDao().byId("plan_stale")?.status)
        assertNull(db.actionPlanDao().byId("plan_stale")?.activeEventKey)
        assertNull(db.actionPlanDao().activeForEvent("evt_00000000000000000000000000000030"))
    }

    @Test
    fun chatClaimCoordinatorAndBackgroundCannotBothWin() = runBlocking {
        val todoManager = TodoManager(db, db.todoDao())
        val coordinator = TodoClaimCoordinator(todoManager)
        val todo = todoManager.create(type = TodoTypeNames.CHAT_OPPORTUNISTIC, now = now)

        val chat = coordinator.claimForChat(todo.id, "msg_00000000000000000000000000000001", now)
        val background = coordinator.claimForBackground(todo.id, "evt_00000000000000000000000000000001", now + 1)

        assertNotNull(chat)
        assertNull(background)
        assertEquals(TodoStatuses.CLAIMED, todoManager.byId(todo.id)?.status)
    }

    private fun event(type: String, id: String, key: String): EventEntity = EventEntity(
        id = id,
        eventKey = key,
        type = type,
        source = "TEST",
        payloadJson = "{}",
        occurredAt = now,
        receivedAt = now,
        expiresAt = now + 3_600_000,
        priority = 50,
        status = EventStatuses.RECEIVED,
    )
}
