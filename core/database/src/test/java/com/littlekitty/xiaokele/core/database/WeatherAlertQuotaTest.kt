package com.littlekitty.xiaokele.core.database.delivery

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.dao.DeliveryHistoryDao
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity
import com.littlekitty.xiaokele.core.database.state.DeliveryStatuses
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class WeatherAlertQuotaTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DeliveryHistoryDao
    private lateinit var outbox: DeliveryOutbox
    private val now = Instant.parse("2026-08-16T10:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.deliveryHistoryDao()
        outbox = DeliveryOutbox(db, dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun highAlertNotificationIsQuotaExemptButSameCooldownStillBlocks() = runBlocking {
        // Fill the ordinary daily quota and min-interval window.
        repeat(3) { index ->
            insertDelivered(
                channel = DeliveryOutbox.CHANNEL_NOTIFICATION,
                group = "ordinary:$index",
                eventId = "evt_quota_${index}_000000000000000001",
                deliveredAt = now.toEpochMilli() - 60_000,
            )
        }
        val regular = outbox.reserve(
            DeliveryReservationRequest(
                eventId = "evt_regular_000000000000000001",
                todoId = null,
                channel = DeliveryOutbox.CHANNEL_NOTIFICATION,
                content = "regular",
                executionId = "exec_regular_000000000000000001",
                cooldownGroup = "scene:weather_alert_high",
                nowEpochMillis = now.toEpochMilli(),
                quotaExempt = false,
            ),
        )
        assertTrue(regular is DeliveryReservationResult.Blocked)

        val highAlert = outbox.reserve(
            DeliveryReservationRequest(
                eventId = "evt_high_alert_000000000000000001",
                todoId = null,
                channel = DeliveryOutbox.CHANNEL_NOTIFICATION,
                content = "high-alert",
                executionId = "exec_high_alert_000000000000000001",
                cooldownGroup = "scene:weather_alert_high",
                nowEpochMillis = now.toEpochMilli(),
                quotaExempt = true,
            ),
        )
        assertTrue("highAlert=$highAlert", highAlert is DeliveryReservationResult.Reserved)
        val first = (highAlert as DeliveryReservationResult.Reserved).delivery
        assertTrue(outbox.markInvoked(first.id, now.toEpochMilli()))
        assertTrue(outbox.commitDelivered(first.id, now.toEpochMilli()) != null)

        val duplicateAlert = outbox.reserve(
            DeliveryReservationRequest(
                eventId = "evt_high_alert_2_00000000000000001",
                todoId = null,
                channel = DeliveryOutbox.CHANNEL_NOTIFICATION,
                content = "high-alert-2",
                executionId = "exec_high_alert_2_0000000000000001",
                cooldownGroup = "scene:weather_alert_high",
                nowEpochMillis = now.toEpochMilli() + 1_000,
                quotaExempt = true,
            ),
        )
        assertTrue(duplicateAlert is DeliveryReservationResult.Blocked)
        assertEquals(
            DeliveryBlockReasons.CHANNEL_COOLDOWN,
            (duplicateAlert as DeliveryReservationResult.Blocked).reason,
        )
    }

    private suspend fun insertDelivered(
        channel: String,
        group: String,
        eventId: String,
        deliveredAt: Long,
    ) {
        dao.insert(
            DeliveryHistoryEntity(
                id = com.littlekitty.xiaokele.core.common.id.EntityId.generate("dlv").value,
                eventId = eventId,
                todoId = null,
                channel = channel,
                contentHash = DeliveryOutbox.sha256("content-$eventId-$group"),
                status = DeliveryStatuses.DELIVERED,
                deliveredAt = deliveredAt,
                executionId = "exec_${eventId}_0000000000000001",
                cooldownGroup = group,
            ),
        )
    }
}
