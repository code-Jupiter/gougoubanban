package com.littlekitty.xiaokele.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.database.entity.PetStateEntity
import com.littlekitty.xiaokele.core.database.repository.ActionPlanRepository
import com.littlekitty.xiaokele.core.database.repository.PetStateRepository
import com.littlekitty.xiaokele.core.database.repository.TodoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Phase1ConcurrencyTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private var db: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "phase1-concurrency-${UUID.randomUUID().toString().replace("-", "")}"
        context.deleteDatabase(dbName)
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName).build()
    }

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun concurrentTodoClaimHasExactlyOneWinner() = runBlocking {
        val room = db ?: error("db not created")
        val repo = TodoRepository(room, room.todoDao())
        val now = 1_700_000_000_000L
        val todo = repo.create(type = "DISCUSS_LATER", now = now, title = "race")

        val results = coroutineScope {
            (1..20).map { index ->
                async(Dispatchers.IO) { repo.claim(todo.id, "claimant-$index", now + index) != null }
            }.awaitAll()
        }

        assertEquals(1, results.count { it })
        val stored = room.todoDao().byId(todo.id)
        assertEquals("CLAIMED", stored?.status)
    }

    @Test
    fun concurrentActionPlanCreationKeepsOneActivePlanPerEvent() = runBlocking {
        val room = db ?: error("db not created")
        val repo = ActionPlanRepository(room, room.actionPlanDao())
        val now = 1_700_000_000_000L

        val results = coroutineScope {
            (1..20).map { index ->
                async(Dispatchers.IO) {
                    runCatching {
                        repo.createForEvent(
                            eventId = "evt_race",
                            agentOutputJson = """{"decision":"NO_ACTION"}""",
                            now = now,
                            id = "plan_race_${index.toString().padStart(2, '0')}",
                        )
                    }.isSuccess
                }
            }.awaitAll()
        }

        assertEquals(1, results.count { it })
        assertEquals(1, room.actionPlanDao().activeForEvent("evt_race")?.let { 1 } ?: 0)
    }

    @Test
    fun concurrentPetStateReducerUpdatesKeepExactlyOneSingletonRow() = runBlocking {
        val room = db ?: error("db not created")
        val repo = PetStateRepository(room, room.petStateDao())
        val now = 1_700_000_000_000L

        coroutineScope {
            (1..20).map {
                async(Dispatchers.IO) {
                    repo.setState(PetStateEntity(stateId = "IDLE", startsAt = now, updatedAt = now), now)
                }
            }.awaitAll()
        }

        assertEquals(1, room.petStateDao().count())
        assertEquals("IDLE", room.petStateDao().get()?.stateId)
    }
}
