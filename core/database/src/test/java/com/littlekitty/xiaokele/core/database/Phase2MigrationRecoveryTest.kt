package com.littlekitty.xiaokele.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.repository.RecoveryManager
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Phase2MigrationRecoveryTest {

    private lateinit var context: Context
    private lateinit var dbName: String
    private var db: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "phase2-migration-${UUID.randomUUID().toString().replace("-", "")}"
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        db?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun version2ToLatestMigrationKeepsOldMessagesCompletedAndAddsRetryColumns() = runBlocking {
        createVersion2Database()
        db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(*AppMigrations.all())
            .build()
        val room = db ?: error("db not created")

        assertEquals(AppDatabase.VERSION, room.openHelper.readableDatabase.version)
        val migrated = room.messageDao().byId("msg_phase2_migration_0000000000000000001")
        assertEquals(MessageStatuses.COMPLETED, migrated?.status)
        assertNull(migrated?.errorCode)

        val columns = room.openHelper.readableDatabase
            .query("PRAGMA table_info(`messages`)")
            .use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
        assertEquals(setOf("id", "role", "content", "created_at", "source_event_id", "source_todo_id", "animation_id", "status", "error_code", "read_at"), columns)
    }

    @Test
    fun staleSendingMessageIsRecoveredAsRetryableFailure() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val room = db ?: error("db not created")
        val now = 1_700_000_000_000L
        room.messageDao().upsert(
            MessageEntity(
                id = "msg_sending",
                role = "USER",
                content = "发送中崩溃",
                createdAt = now - 60_000,
                status = MessageStatuses.SENDING,
            ),
        )

        val report = RecoveryManager(room).run(now)

        assertEquals(1, report.recoveredStaleMessages)
        val recovered = room.messageDao().byId("msg_sending")
        assertEquals(MessageStatuses.FAILED, recovered?.status)
        assertEquals("STALE_AFTER_RESTART", recovered?.errorCode)
    }

    private fun createVersion2Database() {
        val framework = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        Phase1MigrationSql.statements.forEach { statement -> framework.execSQL(statement) }
        framework.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        framework.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '0396f5a58102b3d352b67d43b78a6dcd')",
        )
        framework.execSQL(
            """
            INSERT INTO `messages` (`id`, `role`, `content`, `created_at`, `source_event_id`, `source_todo_id`, `animation_id`)
            VALUES ('msg_phase2_migration_0000000000000000001', 'USER', 'v2 历史消息', 1700000000000, NULL, NULL, NULL)
            """.trimIndent(),
        )
        framework.version = 2
        framework.close()
    }
}
