package com.littlekitty.xiaokele.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.database.entity.ActionExecutionEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.ExternalCacheEntity
import com.littlekitty.xiaokele.core.database.entity.MemoryEntity
import com.littlekitty.xiaokele.core.database.entity.PetStateEntity
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.repository.ActionPlanRepository
import com.littlekitty.xiaokele.core.database.repository.EventRepository
import com.littlekitty.xiaokele.core.database.repository.PetStateRepository
import com.littlekitty.xiaokele.core.database.repository.RecoveryManager
import com.littlekitty.xiaokele.core.database.repository.TodoRepository
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Phase1RepositoryRecoveryTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private var db: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "phase1-repo-${UUID.randomUUID().toString().replace("-", "")}"
        context.deleteDatabase(dbName)
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
    }

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun startupRecoveryExpiresDueRowsAndRecoversStaleClaims() = runBlocking {
        val room = db ?: error("db not created")
        val now = 1_700_000_000_000L
        val minute = 60_000L

        room.eventDao().upsert(
            EventEntity(
                id = "evt_due", eventKey = "evt:due", type = "TEST", source = "TEST",
                payloadJson = "{}", occurredAt = now - 10_000, receivedAt = now - 10_000,
                expiresAt = now - 1, priority = 0, status = "RECEIVED",
            ),
        )
        room.eventDao().upsert(
            EventEntity(
                id = "evt_processing", eventKey = "evt:processing", type = "TEST", source = "TEST",
                payloadJson = "{}", occurredAt = now - 10_000, receivedAt = now - 10_000,
                expiresAt = now + minute, priority = 0, status = "PROCESSING",
            ),
        )

        room.todoDao().upsert(
            TodoEntity(
                id = "todo_due", type = "T", status = "READY", priority = 0,
                availableAt = now - minute, expiresAt = now - 1, createdAt = now - minute, updatedAt = now - minute,
            ),
        )
        room.todoDao().upsert(
            TodoEntity(
                id = "todo_claimed_stale_due", type = "T", status = "CLAIMED", priority = 0,
                availableAt = now - minute, expiresAt = now - 1,
                claimedBy = "dead-process", claimedAt = now - 10 * minute,
                createdAt = now - minute, updatedAt = now - 5 * minute,
            ),
        )
        room.todoDao().upsert(
            TodoEntity(
                id = "todo_claimed_stale_live", type = "T", status = "CLAIMED", priority = 0,
                availableAt = now - minute, expiresAt = now + 60 * minute,
                claimedBy = "dead-process", claimedAt = now - 10 * minute,
                createdAt = now - minute, updatedAt = now - 5 * minute,
            ),
        )

        room.memoryDao().upsert(
            MemoryEntity(
                id = "mem_due", type = "EVENT", content = "due", status = "ACTIVE",
                validUntil = now - 1, createdAt = now - minute, updatedAt = now - minute,
            ),
        )
        room.petStateDao().upsert(
            PetStateEntity(
                singletonId = PetStateEntity.SINGLETON_ID, stateId = "WAITING",
                startsAt = now - minute, expiresAt = now - 1, updatedAt = now - minute,
            ),
        )
        room.actionExecutionDao().upsert(
            ActionExecutionEntity(
                id = "exec_running", actionPlanId = "plan_x", actionType = "T",
                status = "RUNNING", errorCode = null,
            ),
        )
        room.externalCacheDao().upsert(
            ExternalCacheEntity(
                cacheKey = "cache_due", source = "TEST", payloadJson = "{}",
                fetchedAt = now - minute, expiresAt = now - 1,
            ),
        )

        val report = RecoveryManager(room).run(now, staleClaimLeaseMillis = 5 * minute)

        assertEquals(1, report.expiredEvents)
        assertEquals(1, report.recoveredProcessingEvents)
        assertEquals(1, report.expiredTodos)
        assertEquals(2, report.staleClaims)
        assertEquals(1, report.staleClaimsReturnedToReady)
        assertEquals(1, report.staleClaimsExpired)
        assertEquals(1, report.expiredMemories)
        assertEquals(1, report.expiredPetStates)
        assertEquals(1, report.failedStaleActionExecutions)
        assertEquals(1, report.deletedExpiredExternalCaches)

        assertEquals("EXPIRED", room.eventDao().byId("evt_due")?.status)
        assertEquals("RECEIVED", room.eventDao().byId("evt_processing")?.status)
        assertEquals("EXPIRED", room.todoDao().byId("todo_due")?.status)
        assertEquals("EXPIRED", room.todoDao().byId("todo_claimed_stale_due")?.status)
        assertEquals("READY", room.todoDao().byId("todo_claimed_stale_live")?.status)
        assertNull(room.todoDao().byId("todo_claimed_stale_live")?.claimedBy)
        assertEquals("EXPIRED", room.memoryDao().byId("mem_due")?.status)
        assertEquals("IDLE", room.petStateDao().get()?.stateId)
        assertNull(room.petStateDao().get()?.expiresAt)
        assertEquals("FAILED", room.actionExecutionDao().byId("exec_running")?.status)
        assertNull(room.externalCacheDao().byKeyAndSource("cache_due", "TEST"))

        // Simulate process death and reopening the same local database.
        room.close()
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        val reopened = db ?: error("db not reopened")
        assertEquals("EXPIRED", reopened.eventDao().byId("evt_due")?.status)
        assertEquals("READY", reopened.todoDao().byId("todo_claimed_stale_live")?.status)
        assertEquals("IDLE", reopened.petStateDao().get()?.stateId)
    }

    @Test
    fun startupRecoveryFailsIdleFollowUpBeforeBackgroundBacklogCanClaimIt() = runBlocking {
        val room = db ?: error("db not created")
        val now = 1_700_000_000_000L
        room.eventDao().upsert(
            EventEntity(
                id = "evt_idle_received",
                eventKey = "chat:idle:received",
                type = "IDLE_FOLLOW_UP",
                source = "CHAT_IDLE_FOLLOW_UP",
                payloadJson = "{}",
                occurredAt = now - 1_000,
                receivedAt = now - 1_000,
                expiresAt = now + 60_000,
                status = "RECEIVED",
            ),
        )

        val report = RecoveryManager(room).run(now)
        val recovered = room.eventDao().byId("evt_idle_received")

        assertEquals(1, report.failedStaleChatEvents)
        assertEquals("FAILED", recovered?.status)
        assertEquals(1, recovered?.attemptCount)
        assertEquals("STALE_AFTER_RESTART", recovered?.lastErrorCode)
        assertEquals(0, room.eventDao().backgroundBacklogCount(now))
    }

    @Test
    fun todoClaimIsAtomicAndSingleUse() = runBlocking {
        val room = db ?: error("db not created")
        val repo = TodoRepository(room, room.todoDao())
        val now = 1_700_000_000_000L
        val todo = repo.create(type = "T", now = now)

        val first = repo.claim(todo.id, "claimant-a", now + 1)
        val second = repo.claim(todo.id, "claimant-b", now + 2)

        assertNotNull(first)
        assertEquals("CLAIMED", first?.status)
        assertEquals("claimant-a", first?.claimedBy)
        assertNull(second)
        assertEquals("CLAIMED", room.todoDao().byId(todo.id)?.status)
    }

    @Test
    fun oneActiveActionPlanPerEventIsEnforcedByUniqueConstraint() = runBlocking {
        val room = db ?: error("db not created")
        val eventRepo = EventRepository(room, room.eventDao())
        val planRepo = ActionPlanRepository(room, room.actionPlanDao())
        val now = 1_700_000_000_000L

        eventRepo.save(
            EventEntity(
                id = "evt_plan", eventKey = "evt:plan", type = "TEST", source = "TEST",
                payloadJson = "{}", occurredAt = now, receivedAt = now,
                expiresAt = now + 60_000, priority = 0, status = "RECEIVED",
            ),
        )
        val first = planRepo.createForEvent("evt_plan", """{"decision":"NO_ACTION"}""", now)
        val duplicateError = runCatching {
            planRepo.createForEvent("evt_plan", """{"decision":"NO_ACTION"}""", now, id = "plan_second")
        }.exceptionOrNull()
        assertTrue(duplicateError is IllegalStateException)

        planRepo.transition(first.id, "RUNNING")
        planRepo.transition(first.id, "COMPLETED")
        assertNull(room.actionPlanDao().byId(first.id)?.activeEventKey)

        // After terminal state the unique slot is free again.
        val second = planRepo.createForEvent("evt_plan", """{"decision":"NO_ACTION"}""", now + 1, id = "plan_third")
        assertEquals("evt_plan", second.activeEventKey)
    }

    @Test
    fun petStateReducerRejectsIllegalTransitionsAndKeepsSingleton() = runBlocking {
        val room = db ?: error("db not created")
        val repo = PetStateRepository(room, room.petStateDao())
        val now = 1_700_000_000_000L

        repo.setState(PetStateEntity(stateId = "SLEEPING", startsAt = now, updatedAt = now), now)
        val illegalTransition = runCatching {
            repo.setState(PetStateEntity(stateId = "CARING", startsAt = now, updatedAt = now), now)
        }.exceptionOrNull()
        assertTrue(illegalTransition is IllegalArgumentException)
        repo.setState(PetStateEntity(stateId = "IDLE", startsAt = now, updatedAt = now), now)
        assertEquals(1, room.petStateDao().count())
        assertEquals("IDLE", repo.current()?.stateId)
    }

    @Test
    fun fixtureSeedsEveryPhase1TableAndSurvivesReopen() = runBlocking {
        val room = db ?: error("db not created")
        com.littlekitty.xiaokele.core.database.fixture.Phase1Fixtures.seed(room)
        val before = com.littlekitty.xiaokele.core.database.fixture.Phase1Snapshotter.snapshot(room)
        assertTrue(before.messages == 1 && before.petStates == 1)

        room.close()
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
        val reopened = db ?: error("db not reopened")
        val after = com.littlekitty.xiaokele.core.database.fixture.Phase1Snapshotter.snapshot(reopened)
        assertEquals(before, after)
    }
}
