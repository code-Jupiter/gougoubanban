package com.littlekitty.xiaokele.event

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.protocol.NormalizedEvent
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class EventNormalizerTest {

    private lateinit var db: AppDatabase
    private lateinit var timeProvider: TimeProvider
    private lateinit var normalizer: EventNormalizer
    private val now: Instant = Instant.parse("2026-08-16T10:00:00Z")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        timeProvider = object : TimeProvider {
            override fun now(): Instant = now
            override fun zone(): ZoneId = ZoneId.of("UTC")
        }
        normalizer = EventNormalizer(db.eventDao(), timeProvider)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun validEventIsStoredFirstAsReceived() = runBlocking {
        val raw = event(key = "weather:test:1", expiresInSeconds = 600)

        val result = normalizer.ingest(raw)

        assertTrue(result is EventIngestion.Stored)
        val stored = (result as EventIngestion.Stored).event
        assertEquals(EventStatuses.RECEIVED, stored.status)
        assertEquals("weather:test:1", stored.eventKey)
        assertEquals(now.plusSeconds(600).toEpochMilli(), stored.expiresAt)
        assertEquals(1, db.eventDao().count())
    }

    @Test
    fun duplicateEventKeyIsRejectedAndExistingRowIsAuditable() = runBlocking {
        val first = event(key = "weather:test:dup", expiresInSeconds = 600)
        val storedFirst = normalizer.ingest(first)

        val second = normalizer.ingest(first.copy(occurredAt = InstantJson.formatUtc(now.plusSeconds(1))))

        assertTrue(second is EventIngestion.RejectedDuplicate)
        assertEquals(
            (storedFirst as EventIngestion.Stored).event.id,
            (second as EventIngestion.RejectedDuplicate).existingEvent.id,
        )
        assertEquals(1, db.eventDao().count())
    }

    @Test
    fun expiredEventIsStoredAsExpiredAndFilterable() = runBlocking {
        val raw = event(key = "weather:test:expired", expiresInSeconds = -60)

        val result = normalizer.ingest(raw)

        assertTrue(result is EventIngestion.RejectedExpired)
        val stored = (result as EventIngestion.RejectedExpired).event
        assertEquals(EventStatuses.EXPIRED, stored.status)
        assertEquals(1, db.eventDao().countByStatus(EventStatuses.EXPIRED))
    }

    @Test
    fun invalidEventIsRejectedWithoutPersistedRow() = runBlocking {
        val raw = NormalizedEvent(
            eventKey = " ",
            type = "",
            source = "",
            occurredAt = "not-a-time",
            expiresAt = "not-a-time",
            priority = 999,
        )

        val result = normalizer.ingest(raw)

        assertTrue(result is EventIngestion.RejectedInvalid)
        assertTrue((result as EventIngestion.RejectedInvalid).violations.size >= 5)
        assertEquals(0, db.eventDao().count())
    }

    @Test
    fun idIsGeneratedWhenMissing() = runBlocking {
        val raw = event(key = "weather:test:id", expiresInSeconds = 600).copy(id = null)

        val result = normalizer.ingest(raw)

        assertTrue(result is EventIngestion.Stored)
        val stored = (result as EventIngestion.Stored).event
        assertTrue(stored.id.startsWith("evt_"))
    }

    private fun event(key: String, expiresInSeconds: Long): NormalizedEvent = NormalizedEvent(
        eventKey = key,
        type = "WEATHER_CHANGE",
        source = "WEATHER_PROVIDER_TEST",
        occurredAt = InstantJson.formatUtc(now),
        receivedAt = InstantJson.formatUtc(now),
        expiresAt = InstantJson.formatUtc(now.plusSeconds(expiresInSeconds)),
        priority = 60,
        payload = buildJsonObject { put("condition", "rain") },
    )
}
