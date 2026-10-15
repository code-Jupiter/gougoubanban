package com.littlekitty.xiaokele.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.database.delivery.DeliveryOutbox
import com.littlekitty.xiaokele.core.database.delivery.DeliveryReservationRequest
import com.littlekitty.xiaokele.core.database.delivery.DeliveryReservationResult
import com.littlekitty.xiaokele.core.database.delivery.NotificationQuotaPolicy
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.repository.RecoveryManager
import com.littlekitty.xiaokele.core.database.state.DeliveryStatuses
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Phase8DatabaseAuditTest {

    private lateinit var context: Context
    private var db: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        db?.close()
    }

    @Test
    fun schemaVersion12HasNoShiftCacheTableOrShiftRows() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db = room

        assertEquals(12, AppDatabase.VERSION)
        assertEquals(AppDatabase.VERSION, room.openHelper.readableDatabase.version)
        assertFalse("shift_cache must not exist in the public edition", tableNames(room).contains("shift_cache"))
        assertTrue("external_cache must survive", tableNames(room).contains("external_cache"))
    }

    @Test
    fun migration11To12DropsAPreExistingShiftCacheTable() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db = room
        val support = room.openHelper.writableDatabase
        support.execSQL("CREATE TABLE IF NOT EXISTS `shift_cache` (`id` TEXT NOT NULL, PRIMARY KEY(`id`))")
        assertTrue("precondition: legacy table exists", tableNames(room).contains("shift_cache"))

        AppMigrations.MIGRATION_11_12.migrate(support)

        assertFalse(
            "migration 11 -> 12 must drop the legacy shift cache",
            tableNames(room).contains("shift_cache"),
        )
    }

    private fun tableNames(room: AppDatabase): Set<String> = room.openHelper.readableDatabase
        .query("SELECT name FROM sqlite_master WHERE type = 'table'")
        .use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    @Test
    fun notificationDailyBudgetUsesAsiaShanghaiProductDay() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db = room
        val outbox = DeliveryOutbox(
            db = room,
            dao = room.deliveryHistoryDao(),
            notificationQuota = NotificationQuotaPolicy(
                dailyLimit = 1,
                minIntervalMillis = 0L,
                sameTypeCooldownMillis = 0L,
            ),
        )
        // 2026-08-16T15:59:59Z == 2026-08-16 23:59:59 Asia/Shanghai.
        val first = outbox.reserve(request("evt_a", now = 1_786_895_940_000L)) as DeliveryReservationResult.Reserved
        outbox.commitDelivered(first.delivery.id, 1_786_895_940_000L)

        // 2026-08-16T16:00:01Z == 2026-08-17 00:00:01 Asia/Shanghai, a new product day.
        val second = outbox.reserve(request("evt_b", now = 1_786_896_000_001L))
        assertTrue("expected a fresh product-day quota, got $second", second is DeliveryReservationResult.Reserved)
    }

    @Test
    fun chatClaimedTodoIsNeverReturnedToReadyByStaleLeaseRecovery() = runBlocking {
        val room = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db = room
        val now = 1_756_296_000_000L
        val chatTodo = TodoEntity(
            id = "todo_chat_claim",
            type = "DISCUSS_LATER",
            status = TodoStatuses.CLAIMED,
            claimedBy = "chat:msg_00000000000000000000000000000001",
            claimedAt = now - 20L * 60_000,
            createdAt = now - 60_000,
            updatedAt = now - 60_000,
        )
        room.todoDao().upsert(chatTodo)

        RecoveryManager(room).run(now, staleClaimLeaseMillis = 15L * 60_000)

        val after = room.todoDao().byId(chatTodo.id)
        assertEquals(TodoStatuses.CLAIMED, after?.status)
        assertEquals(chatTodo.claimedBy, after?.claimedBy)
    }

    @Test
    fun dirtyV3UpgradeDedupesDeterministicallyAndKeepsUnrelatedRows() = runBlocking {
        val dbName = "phase8-dirty-v3-${UUID.randomUUID().toString().replace("-", "")}"
        context.deleteDatabase(dbName)
        createDirtyV3Database(dbName)
        val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
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
        db = room
        try {
            assertEquals(AppDatabase.VERSION, room.openHelper.readableDatabase.version)

            // Duplicate messages keep the earliest (created_at, id) row.
            assertEquals(3, room.messageDao().count())
            assertEquals("msg_event_old", room.messageDao().assistantForEvent("evt_dup")?.id)
            assertEquals("msg_todo_old", room.messageDao().assistantForTodo("todo_dup")?.id)
            assertNotNull(room.messageDao().byId("msg_unrelated"))

            // Duplicate delivery keeps the earliest (delivered_at, id) row.
            assertEquals(2, room.deliveryHistoryDao().count())
            assertEquals("dlv_old", room.deliveryHistoryDao().forEventAndChannel("evt_dlv", "NOTIFICATION")?.id)
            assertNotNull(room.deliveryHistoryDao().byId("dlv_unrelated"))
        } finally {
            room.close()
            context.deleteDatabase(dbName)
        }
    }

    private fun createDirtyV3Database(dbName: String) {
        val framework = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        Phase1MigrationSql.statements.forEach { statement -> framework.execSQL(statement) }
        framework.execSQL("ALTER TABLE `messages` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'COMPLETED'")
        framework.execSQL("ALTER TABLE `messages` ADD COLUMN `error_code` TEXT")
        framework.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_status` ON `messages` (`status`)")
        framework.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        framework.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'ce203d6712b85ea992f9292bfe32ff9b')",
        )
        val messageSql =
            "INSERT INTO `messages` (`id`,`role`,`content`,`created_at`,`source_event_id`,`source_todo_id`,`animation_id`,`status`,`error_code`) VALUES"
        framework.execSQL("$messageSql ('msg_event_old','ASSISTANT','old',100,'evt_dup',NULL,NULL,'COMPLETED',NULL)")
        framework.execSQL("$messageSql ('msg_event_new','ASSISTANT','new',200,'evt_dup',NULL,NULL,'COMPLETED',NULL)")
        framework.execSQL("$messageSql ('msg_todo_old','ASSISTANT','old',100,NULL,'todo_dup',NULL,'COMPLETED',NULL)")
        framework.execSQL("$messageSql ('msg_todo_new','ASSISTANT','new',200,NULL,'todo_dup',NULL,'COMPLETED',NULL)")
        framework.execSQL("$messageSql ('msg_unrelated','USER','keep',300,NULL,NULL,NULL,'COMPLETED',NULL)")
        val deliverySql =
            "INSERT INTO `delivery_history` (`id`,`event_id`,`todo_id`,`channel`,`content_hash`,`delivered_at`,`user_opened_at`,`user_replied_at`) VALUES"
        framework.execSQL("$deliverySql ('dlv_old','evt_dlv',NULL,'NOTIFICATION','hash-old',100,NULL,NULL)")
        framework.execSQL("$deliverySql ('dlv_new','evt_dlv',NULL,'NOTIFICATION','hash-new',200,NULL,NULL)")
        framework.execSQL("$deliverySql ('dlv_unrelated','evt_other',NULL,'WIDGET','hash-other',300,NULL,NULL)")
        framework.version = 3
        framework.close()
    }

    private fun request(eventId: String, now: Long): DeliveryReservationRequest = DeliveryReservationRequest(
        eventId = eventId,
        todoId = null,
        channel = DeliveryOutbox.CHANNEL_NOTIFICATION,
        content = "content-$eventId",
        executionId = "exec_00000000000000000000000000000001",
        nowEpochMillis = now,
    )
}
