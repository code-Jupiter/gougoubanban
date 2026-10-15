package com.littlekitty.xiaokele.external

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.repository.ExternalCacheEmissionGuard
import com.littlekitty.xiaokele.core.database.repository.ExternalCacheRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId

/**
 * Public-edition resolver contract: it resolves a current location snapshot and
 * the weather cached for it, and it never fabricates a shift, a schedule state
 * or a fixed place.
 */
@RunWith(RobolectricTestRunner::class)
class ExternalContextResolverTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ExternalCacheRepository
    private lateinit var resolver: ExternalContextResolver
    private val now = 1_800_000_000_000L

    private val timeProvider = object : TimeProvider {
        override fun now(): Instant = Instant.ofEpochMilli(now)
        override fun zone(): ZoneId = ZoneId.of("Asia/Shanghai")
    }

    @Before
    fun setUp() {
        // Another test class in this JVM may have left a global Koin app
        // running; this test does not use Koin, so make that state harmless.
        org.koin.core.context.stopKoin()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ExternalCacheRepository(
            db = db,
            dao = db.externalCacheDao(),
            guard = ExternalCacheEmissionGuard(db.externalCacheDao()),
        )
        resolver = ExternalContextResolver(db = db, timeProvider = timeProvider)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun locationSnapshotIsNullWithoutCache() = runBlocking {
        assertNull(resolver.resolveLocationSnapshot(now))
        assertNull(resolver.resolveWeatherSnapshot(now))
    }

    @Test
    fun locationSnapshotResolvesCachedCurrentPlace() = runBlocking {
        PublicExternalTestFixtures.seedLocationAndWeather(repository, now)

        val snapshot = resolver.resolveLocationSnapshot(now)

        assertEquals(LocationSnapshot.CURRENT_PLACE, snapshot?.placeType)
        assertEquals(PublicExternalTestFixtures.DEFAULT_LATITUDE, snapshot?.latitude ?: 0.0, 0.0001)
        assertEquals(PublicExternalTestFixtures.DEFAULT_LONGITUDE, snapshot?.longitude ?: 0.0, 0.0001)
    }

    @Test
    fun expiredLocationSnapshotResolvesToNull() = runBlocking {
        val expired = LocationFix(
            latitude = PublicExternalTestFixtures.DEFAULT_LATITUDE,
            longitude = PublicExternalTestFixtures.DEFAULT_LONGITUDE,
            accuracyMeters = 10.0,
        ).toSnapshot(now - ExternalEventContract.LOCATION_CACHE_TTL_MILLIS - 1L)
        repository.put(
            cacheKey = ExternalEventContract.LOCATION_CACHE_KEY,
            source = ExternalEventContract.LOCATION_CACHE_SOURCE,
            payloadJson = JsonCodec.encode(expired),
            fetchedAt = expired.observedAtEpochMillis,
            expiresAt = now - 1L,
        )

        assertNull(resolver.resolveLocationSnapshot(now))
    }

    @Test
    fun weatherResolvesOnlyForTheCachedLocation() = runBlocking {
        PublicExternalTestFixtures.seedLocationAndWeather(repository, now, condition = "rain")

        val weather = resolver.resolveWeatherSnapshot(now)

        assertEquals("rain", weather?.condition)
    }

    @Test
    fun weatherIsNullWithoutLocation() = runBlocking {
        repository.put(
            cacheKey = "weather:latest:${LocationSnapshot.CURRENT_PLACE.lowercase()}",
            source = ExternalEventContract.WEATHER_CACHE_SOURCE,
            payloadJson = PublicExternalTestFixtures.weatherSnapshotJson(),
            fetchedAt = now,
            expiresAt = now + 60_000L,
        )

        assertNull(resolver.resolveWeatherSnapshot(now))
    }

    @Test
    fun malformedLocationPayloadResolvesToNull() = runBlocking {
        repository.put(
            cacheKey = ExternalEventContract.LOCATION_CACHE_KEY,
            source = ExternalEventContract.LOCATION_CACHE_SOURCE,
            payloadJson = "{not-json",
            fetchedAt = now,
            expiresAt = now + 60_000L,
        )

        assertNull(resolver.resolveLocationSnapshot(now))
    }
}
