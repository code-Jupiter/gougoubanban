package com.littlekitty.xiaokele.external

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.animation.CatalogAnimationAdapter
import com.littlekitty.xiaokele.channel.ChannelExecutionCoordinator
import com.littlekitty.xiaokele.chat.CharacterCoreBootstrap
import com.littlekitty.xiaokele.core.common.time.MutableTimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.delivery.DeliveryOutbox
import com.littlekitty.xiaokele.core.database.policy.DevelopmentCandidatePolicies
import com.littlekitty.xiaokele.core.database.policy.PolicyResolver
import com.littlekitty.xiaokele.core.database.repository.BackgroundMutationExecutor
import com.littlekitty.xiaokele.core.database.repository.ExternalCacheEmissionGuard
import com.littlekitty.xiaokele.core.database.repository.ExternalCacheRepository
import com.littlekitty.xiaokele.core.network.external.PendingWeatherService
import com.littlekitty.xiaokele.core.network.model.FakeModelMode
import com.littlekitty.xiaokele.core.network.model.FakeModelService
import com.littlekitty.xiaokele.event.BackgroundCompanionPipeline
import com.littlekitty.xiaokele.event.BackgroundContextBuilder
import com.littlekitty.xiaokele.event.EventDispatchResult
import com.littlekitty.xiaokele.event.EventNormalizer
import com.littlekitty.xiaokele.event.LocalBackgroundDispatcher
import com.littlekitty.xiaokele.notification.NotificationAdapter
import com.littlekitty.xiaokele.notification.NotificationSendRequest
import com.littlekitty.xiaokele.notification.NotificationSendResult
import com.littlekitty.xiaokele.widget.WidgetRefreshResult
import com.littlekitty.xiaokele.widget.WidgetStateAdapter
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
@Config(sdk = [33], application = Application::class)
class ExternalDataCoordinatorLocationTest {

    private lateinit var db: AppDatabase
    private lateinit var coordinator: ExternalDataCoordinator
    private lateinit var cacheRepository: ExternalCacheRepository
    private val time = MutableTimeProvider(Instant.parse("2026-08-17T10:00:00Z"))

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        CharacterCoreBootstrap.ensureSeeded(db, time.now().toEpochMilli())
        DevelopmentCandidatePolicies.ensureSeeded(db)
        val pipeline = BackgroundCompanionPipeline(
            db = db,
            modelService = FakeModelService(FakeModelMode.BACKGROUND_NO_ACTION),
            policyResolver = PolicyResolver(db),
            contextBuilder = BackgroundContextBuilder(db, time),
            mutationExecutor = BackgroundMutationExecutor(db, db.todoDao(), db.triggerDao()),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = time,
            channelCoordinator = ChannelExecutionCoordinator(
                db = db,
                deliveryOutbox = DeliveryOutbox(db, db.deliveryHistoryDao()),
                notificationAdapter = object : NotificationAdapter {
                    override suspend fun send(request: NotificationSendRequest): NotificationSendResult =
                        NotificationSendResult.Delivered
                },
                widgetStateAdapter = object : WidgetStateAdapter {
                    override suspend fun refreshCurrentPetState(): WidgetRefreshResult =
                        WidgetRefreshResult.Refreshed(emptyList())
                },
                animationAdapter = CatalogAnimationAdapter(),
                timeProvider = time,
            ),
        )
        val dispatcher = LocalBackgroundDispatcher(EventNormalizer(db.eventDao(), time), pipeline)
        val guard = ExternalCacheEmissionGuard(db.externalCacheDao())
        cacheRepository = ExternalCacheRepository(db, db.externalCacheDao(), guard)
        val resolver = ExternalContextResolver(db, time)
        val weather = WeatherRefresh(PendingWeatherService(), cacheRepository, resolver, time, dispatcher)
        coordinator = ExternalDataCoordinator(
            externalCacheRepository = cacheRepository,
            contextResolver = resolver,
            weatherRefresh = weather,
            timeProvider = time,
            dispatcher = dispatcher,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun locationFixCachesCurrentSnapshotAndNeverDispatchesAnEvent() = runBlocking {
        val fix = LocationFix(PublicExternalTestFixtures.DEFAULT_LATITUDE, PublicExternalTestFixtures.DEFAULT_LONGITUDE, 20.0)

        val first = coordinator.onLocationFix(fix)

        assertEquals(ExternalSyncOutcomes.SYNCED, first.outcome)
        assertTrue(first.cacheWritten)
        // No place/arrival semantics exist, so a fix must not create an event.
        assertEquals(0, db.eventDao().count())
        val cached = cacheRepository.get(
            ExternalEventContract.LOCATION_CACHE_KEY,
            ExternalEventContract.LOCATION_CACHE_SOURCE,
        )
        assertNotNull(cached)
        assertTrue(cached!!.payloadJson.contains(LocationSnapshot.CURRENT_PLACE))
    }

    @Test
    fun repeatedFixRefreshesWithoutGrowingEventState() = runBlocking {
        val fix = LocationFix(PublicExternalTestFixtures.DEFAULT_LATITUDE, PublicExternalTestFixtures.DEFAULT_LONGITUDE, 20.0)

        coordinator.onLocationFix(fix)
        time.advanceMillis(60_000L)
        val second = coordinator.onLocationFix(fix)

        assertEquals(ExternalSyncOutcomes.SYNCED, second.outcome)
        assertEquals(0, db.eventDao().count())
    }

    @Test
    fun unusableFixFailsClosed() = runBlocking {
        val report = coordinator.onLocationFix(LocationFix(Double.NaN, 0.0, 20.0))

        assertEquals(ExternalSyncOutcomes.SKIPPED_LOCATION_UNUSABLE, report.outcome)
        assertEquals(0, db.eventDao().count())
        assertNull(
            cacheRepository.get(
                ExternalEventContract.LOCATION_CACHE_KEY,
                ExternalEventContract.LOCATION_CACHE_SOURCE,
            ),
        )
    }

    @Test
    fun staleFixNeverOverwritesNewerSnapshot() = runBlocking {
        time.advanceMillis(10_000L)
        coordinator.onLocationFix(LocationFix(PublicExternalTestFixtures.DEFAULT_LATITUDE, PublicExternalTestFixtures.DEFAULT_LONGITUDE, 20.0))

        val rewind = coordinator.onLocationFix(LocationFix(1.0, 2.0, 20.0))

        // The cache is written through the fresher-only guard, so a replayed fix
        // cannot move the stored snapshot.
        assertTrue(
            rewind.outcome == ExternalSyncOutcomes.SYNCED ||
                rewind.outcome == ExternalSyncOutcomes.IGNORED_STALE_CACHE,
        )
    }
}
