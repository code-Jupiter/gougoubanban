package com.littlekitty.xiaokele.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.database.fixture.Phase1Fixtures
import com.littlekitty.xiaokele.core.database.fixture.Phase1Snapshotter
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Phase1MigrationTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private var db: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "phase1-migration-${UUID.randomUUID().toString().replace("-", "")}"
        context.deleteDatabase(dbName)
        createVersion1Database()
    }

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun version1ToLatestMigrationCreatesEveryTableAndDiagnosticLogTable() = runBlocking {
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppMigrations.all())
            .build()
        val room = db ?: error("db not created")

        // Opening the database triggers migration 1 -> ... -> 12 and Room schema validation.
        room.metadataDao().put(
            AppMetadataEntity(key = "migration.marker", value = "ok", updatedAtEpochMillis = 1L),
        )

        assertEquals(12, AppDatabase.VERSION)
        assertEquals(11, AppMigrations.all().size)
        assertEquals(AppDatabase.VERSION, room.openHelper.readableDatabase.version)

        val actualColumns = room.openHelper.readableDatabase
            .query("PRAGMA table_info(`messages`)")
            .use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        assertTrue("messages missing status column", "status" in actualColumns)
        assertTrue("messages missing error_code column", "error_code" in actualColumns)

        val expectedTables = setOf(
            "app_metadata", "messages", "conversation_summaries", "events",
            "scenario_policies", "todos", "triggers", "memories", "memory_embeddings",
            "user_traits", "character_core", "behavior_tendencies", "pet_state",
            "action_plans", "action_executions", "delivery_history",
            "external_cache", "diagnostic_logs", "creative_candidates", "creative_works",
            "creative_source_links", "creative_topics", "creative_sessions", "creative_preferences",
            "creative_offer_events", "user_dialogue_turns", "creative_task_runs", "conversation_sessions",
        )
        val actualTables = room.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type = 'table'")
            .use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        assertTrue("Missing tables: ${expectedTables - actualTables}", actualTables.containsAll(expectedTables))
        assertFalse("shift_cache must not exist in the public edition", "shift_cache" in actualTables)

        Phase1Fixtures.seed(room)
        val snapshot = Phase1Snapshotter.snapshot(room)
        assertEquals(1, snapshot.messages)
        assertEquals(1, snapshot.petStates)
        assertEquals(1, snapshot.externalCache)
    }

    private fun createVersion1Database() {
        val framework = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        framework.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `app_metadata` (
                `key` TEXT NOT NULL,
                `value` TEXT NOT NULL,
                `updated_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`key`)
            )
            """.trimIndent(),
        )
        framework.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        framework.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '0e391c313ffc19b632fa8150b2f88084')",
        )
        framework.version = 1
        framework.close()
    }
}
