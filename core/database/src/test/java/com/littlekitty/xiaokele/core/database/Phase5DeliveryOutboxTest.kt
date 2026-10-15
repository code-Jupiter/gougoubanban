package com.littlekitty.xiaokele.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.time.MutableTimeProvider
import com.littlekitty.xiaokele.core.database.delivery.DeliveryBlockReasons
import com.littlekitty.xiaokele.core.database.delivery.DeliveryOutbox
import com.littlekitty.xiaokele.core.database.delivery.DeliveryReservationRequest
import com.littlekitty.xiaokele.core.database.delivery.DeliveryReservationResult
import com.littlekitty.xiaokele.core.database.delivery.NotificationQuotaPolicy
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity
import com.littlekitty.xiaokele.core.database.state.DeliveryStatuses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Phase5DeliveryOutboxTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var outbox: DeliveryOutbox
    private val time = MutableTimeProvider(Instant.parse("2026-08-16T10:00:00Z"))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        outbox = DeliveryOutbox(db, db.deliveryHistoryDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun reserveInvokeCommitWritesDeliveredOnlyAfterSuccess() = runBlocking {
        val now = time.now().toEpochMilli()
        val reservation = reserve(1, DeliveryOutbox.CHANNEL_NOTIFICATION, now)

        val reserved = (reservation as DeliveryReservationResult.Reserved).delivery
        assertEquals(DeliveryStatuses.RESERVED, reserved.status)
        assertEquals(0L, reserved.deliveredAt)
        assertNull(reserved.attemptStartedAt)
        assertEquals(0, db.deliveryHistoryDao().countDelivered())

        outbox.markInvoked(reserved.id, now)
        val invoked = db.deliveryHistoryDao().byId(reserved.id)
        assertEquals(now, invoked?.attemptStartedAt)
        assertEquals(0L, invoked?.deliveredAt)
        assertEquals(0, db.deliveryHistoryDao().countDelivered())

        val committed = outbox.commitDelivered(reserved.id, now + 1)
        assertEquals(DeliveryStatuses.DELIVERED, committed?.status)
        assertEquals(now + 1, committed?.deliveredAt)
        assertEquals(1, db.deliveryHistoryDao().countDelivered())
    }

    @Test
    fun failedReservationIsRetryableAndNeverFakesDeliveredAt() = runBlocking {
        val now = time.now().toEpochMilli()
        val row = reserve(1, DeliveryOutbox.CHANNEL_NOTIFICATION, now).asReserved().delivery
        outbox.markInvoked(row.id, now)
        val failed = outbox.fail(row.id, "ADAPTER_FAILURE", now + 10, retryable = true)

        assertEquals(DeliveryStatuses.FAILED, failed?.status)
        assertEquals(0L, failed?.deliveredAt)
        assertEquals(0, db.deliveryHistoryDao().countDelivered())
        assertNotNull(failed?.nextRetryAt)
        assertTrue(db.deliveryHistoryDao().retryable(now + 10 + 120_001, 3).isNotEmpty())

        val retried = outbox.prepareRetry(row.id, now + 20)
        assertEquals(DeliveryStatuses.RESERVED, retried?.status)
        assertNull(retried?.attemptStartedAt)
        assertEquals(0L, retried?.deliveredAt)
    }

    @Test
    fun twentyConcurrentReservationsProduceExactlyOneReservedAndOneDelivered() = runBlocking {
        val now = time.now().toEpochMilli()
        val request = DeliveryReservationRequest(
            eventId = "evt_00000000000000000000000000000001",
            todoId = null,
            channel = DeliveryOutbox.CHANNEL_NOTIFICATION,
            content = "concurrent-content",
            executionId = "exec_00000000000000000000000000000001",
            nowEpochMillis = now,
        )
        val results = coroutineScope {
            (1..20).map {
                async(Dispatchers.IO) { outbox.reserve(request) }
            }.awaitAll()
        }
        val reservedCount = results.count { it is DeliveryReservationResult.Reserved }
        val blockedCount = results.count { it is DeliveryReservationResult.Blocked }
        assertEquals(1, reservedCount)
        assertEquals(19, blockedCount)
        assertEquals(1, db.deliveryHistoryDao().count())
        assertEquals(0, db.deliveryHistoryDao().countDelivered())

        val winner = results.filterIsInstance<DeliveryReservationResult.Reserved>().single().delivery
        outbox.markInvoked(winner.id, now)
        outbox.commitDelivered(winner.id, now + 1)
        assertEquals(1, db.deliveryHistoryDao().count())
        assertEquals(1, db.deliveryHistoryDao().countDelivered())
    }

    @Test
    fun notificationDailyMinIntervalAndSameTypeCooldownAreAppliedOnlyToNotifications() = runBlocking {
        val now = time.now().toEpochMilli()
        val smallDaily = DeliveryOutbox(
            db,
            db.deliveryHistoryDao(),
            NotificationQuotaPolicy(dailyLimit = 1),
        )
        insertDelivered("dlv_daily", "evt_daily_01", DeliveryOutbox.CHANNEL_NOTIFICATION, now - 30_000, "g")
        val daily = smallDaily.reserve(
            request(2, DeliveryOutbox.CHANNEL_NOTIFICATION, now, group = "other"),
        )
        assertEquals(DeliveryBlockReasons.DAILY_LIMIT, daily.asBlocked().reason)

        val minOutbox = DeliveryOutbox(
            db,
            db.deliveryHistoryDao(),
            NotificationQuotaPolicy(dailyLimit = 10),
        )
        val minBlocked = minOutbox.reserve(
            request(3, DeliveryOutbox.CHANNEL_NOTIFICATION, now, group = "other"),
        )
        assertEquals(DeliveryBlockReasons.MIN_INTERVAL, minBlocked.asBlocked().reason)

        insertDelivered(
            id = "dlv_same_group",
            eventId = "evt_group_01",
            channel = DeliveryOutbox.CHANNEL_NOTIFICATION,
            deliveredAt = now - 5 * 60 * 60 * 1_000L,
            group = "same_group",
        )
        val cooldownOutbox = DeliveryOutbox(
            db,
            db.deliveryHistoryDao(),
            NotificationQuotaPolicy(dailyLimit = 10, minIntervalMillis = 0L),
        )
        val cooldownBlocked = cooldownOutbox.reserve(
            request(4, DeliveryOutbox.CHANNEL_NOTIFICATION, now, group = "same_group"),
        )
        assertEquals(DeliveryBlockReasons.CHANNEL_COOLDOWN, cooldownBlocked.asBlocked().reason)

        val widgetAllowed = cooldownOutbox.reserve(
            request(5, DeliveryOutbox.CHANNEL_WIDGET, now, group = null),
        )
        assertTrue(widgetAllowed is DeliveryReservationResult.Reserved)
    }

    private suspend fun reserve(
        n: Int,
        channel: String,
        now: Long,
        group: String? = null,
    ): DeliveryReservationResult = outbox.reserve(request(n, channel, now, group))

    private fun request(n: Int, channel: String, now: Long, group: String?): DeliveryReservationRequest =
        DeliveryReservationRequest(
            eventId = "evt_0000000000000000000000000000${n.toString().padStart(4, '0')}",
            todoId = null,
            channel = channel,
            content = "content-$n-$channel-$group",
            executionId = "exec_0000000000000000000000000000${n.toString().padStart(4, '0')}",
            cooldownGroup = group,
            nowEpochMillis = now,
        )

    private suspend fun insertDelivered(
        id: String,
        eventId: String,
        channel: String,
        deliveredAt: Long,
        group: String?,
    ) {
        db.deliveryHistoryDao().upsert(
            DeliveryHistoryEntity(
                id = id,
                eventId = eventId,
                channel = channel,
                contentHash = "hash-$id",
                status = DeliveryStatuses.DELIVERED,
                deliveredAt = deliveredAt,
                cooldownGroup = group,
            ),
        )
    }

    private fun DeliveryReservationResult.asReserved(): DeliveryReservationResult.Reserved {
        assertTrue(this is DeliveryReservationResult.Reserved)
        return this as DeliveryReservationResult.Reserved
    }

    private fun DeliveryReservationResult.asBlocked(): DeliveryReservationResult.Blocked {
        assertTrue(this is DeliveryReservationResult.Blocked)
        return this as DeliveryReservationResult.Blocked
    }
}
