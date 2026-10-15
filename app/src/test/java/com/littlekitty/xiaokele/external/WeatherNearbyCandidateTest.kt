package com.littlekitty.xiaokele.external

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.animation.CatalogAnimationAdapter
import com.littlekitty.xiaokele.channel.ChannelExecutionCoordinator
import com.littlekitty.xiaokele.chat.CharacterCoreBootstrap
import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.MutableTimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.delivery.DeliveryOutbox
import com.littlekitty.xiaokele.core.database.policy.DevelopmentCandidatePolicies
import com.littlekitty.xiaokele.core.database.policy.PolicyResolver
import com.littlekitty.xiaokele.core.database.repository.BackgroundMutationExecutor
import com.littlekitty.xiaokele.core.database.repository.ExternalCacheEmissionGuard
import com.littlekitty.xiaokele.core.database.repository.ExternalCacheRepository
import com.littlekitty.xiaokele.core.network.external.WeatherFetchResult
import com.littlekitty.xiaokele.core.network.external.WeatherLocation
import com.littlekitty.xiaokele.core.network.external.WeatherService
import com.littlekitty.xiaokele.core.network.external.WeatherSnapshotDto
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WeatherNearbyCandidateTest {

    private lateinit var db: AppDatabase
    private lateinit var weatherRefresh: WeatherRefresh
    private lateinit var coordinator: ExternalDataCoordinator
    private lateinit var cacheRepository: ExternalCacheRepository
    private lateinit var dispatcher: LocalBackgroundDispatcher
    private lateinit var resolver: ExternalContextResolver
    private val time = MutableTimeProvider(Instant.parse("2026-08-17T10:00:00Z"))

    @Before
    fun setUp(): Unit = runBlocking {
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
        dispatcher = LocalBackgroundDispatcher(EventNormalizer(db.eventDao(), time), pipeline)
        val guard = ExternalCacheEmissionGuard(db.externalCacheDao())
        cacheRepository = ExternalCacheRepository(db, db.externalCacheDao(), guard)
        resolver = ExternalContextResolver(db, time)
        weatherRefresh = WeatherRefresh(RecordingWeatherService(time.now()), cacheRepository, resolver, time, dispatcher)
        coordinator = ExternalDataCoordinator(
            externalCacheRepository = cacheRepository,
            contextResolver = resolver,
            weatherRefresh = weatherRefresh,
            timeProvider = time,
            dispatcher = dispatcher,
        )
        PublicExternalTestFixtures.seedLocationAndWeather(cacheRepository, time.now().toEpochMilli())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun weatherSuccessCachesBeforeDispatchAndDuplicateIsDeduped() = runBlocking {
        val first = weatherRefresh.refresh(force = true)
        assertEquals(ExternalSyncOutcomes.SYNCED, first.outcome)
        assertTrue(first.cacheWritten)
        assertEquals(1, first.generatedEventKeys.size)
        assertEquals(1, db.eventDao().count())
        assertEquals(0, db.messageDao().count())
        assertEquals(0, db.deliveryHistoryDao().count())

        val duplicate = weatherRefresh.refresh(force = true)
        assertTrue(duplicate.dispatchResults.all { it is EventDispatchResult.Duplicate })
        assertEquals(1, db.eventDao().count())
    }

    @Test
    fun transientWeatherFailureCreatesPersistentCooldownBeforeAnotherProviderCall() = runBlocking {
        val failingService = FailingWeatherService()
        val guardedRefresh = WeatherRefresh(failingService, cacheRepository, resolver, time, dispatcher)

        val first = guardedRefresh.refresh(force = true)
        assertEquals(ExternalSyncOutcomes.FAILED, first.outcome)
        assertEquals(1, failingService.calls)

        val suppressed = guardedRefresh.refresh(force = true)
        assertEquals(ExternalSyncOutcomes.SKIPPED_NOT_DUE, suppressed.outcome)
        assertTrue(suppressed.skippedFailureCooldown)
        assertEquals(1, failingService.calls)

        time.advanceMillis(ExternalEventContract.WEATHER_FAILURE_COOLDOWN_MILLIS)
        guardedRefresh.refresh(force = true)
        assertEquals(2, failingService.calls)
    }

    @Test
    fun weatherAuthenticationFailureDoesNotCreateATransientCooldown() = runBlocking {
        val failingService = FailingWeatherService(detail = "qweather_http_401")
        val guardedRefresh = WeatherRefresh(failingService, cacheRepository, resolver, time, dispatcher)

        assertEquals(ExternalSyncOutcomes.FAILED, guardedRefresh.refresh(force = true).outcome)
        assertEquals(ExternalSyncOutcomes.FAILED, guardedRefresh.refresh(force = true).outcome)
        assertEquals(2, failingService.calls)
    }

    @Test
    fun weatherRateLimitUsesTheLongerPersistentCooldown() = runBlocking {
        val failingService = FailingWeatherService(detail = "qweather_http_429")
        val guardedRefresh = WeatherRefresh(failingService, cacheRepository, resolver, time, dispatcher)

        assertEquals(ExternalSyncOutcomes.FAILED, guardedRefresh.refresh(force = true).outcome)
        time.advanceMillis(ExternalEventContract.WEATHER_FAILURE_COOLDOWN_MILLIS)
        val stillSuppressed = guardedRefresh.refresh(force = true)
        assertTrue(stillSuppressed.skippedFailureCooldown)
        assertEquals(1, failingService.calls)

        time.advanceMillis(
            ExternalEventContract.WEATHER_RATE_LIMIT_COOLDOWN_MILLIS -
                ExternalEventContract.WEATHER_FAILURE_COOLDOWN_MILLIS,
        )
        guardedRefresh.refresh(force = true)
        assertEquals(2, failingService.calls)
    }

    @Test
    fun nearbyFeatureIsDisabledBeforeAnyProviderQueryOrEvent() = runBlocking {
        val skipped = coordinator.searchNearby(force = true)
        assertEquals(ExternalSyncOutcomes.SKIPPED_FEATURE_DISABLED, skipped.outcome)

        val forced = coordinator.searchNearby(force = true)
        assertEquals(ExternalSyncOutcomes.SKIPPED_FEATURE_DISABLED, forced.outcome)
        assertEquals(0, db.eventDao().count())
        assertEquals(0, db.messageDao().count())
        assertEquals(0, db.deliveryHistoryDao().count())
    }

    private class RecordingWeatherService(
        private val now: Instant,
    ) : WeatherService {
        override val serviceName: String = "weather_test_fake"
        override val provenance: String = "TEST_FAKE"

        override suspend fun fetch(location: WeatherLocation): WeatherFetchResult =
            WeatherFetchResult.Success(
                WeatherSnapshotDto(
                    locationKey = location.placeType,
                    observedAt = InstantJson.formatUtc(now),
                    condition = "rain",
                    alerts = emptyList(),
                    expiresAt = InstantJson.formatUtc(now.plusSeconds(3600)),
                ),
            )
    }

    private class FailingWeatherService(
        private val detail: String = "qweather_http_500",
    ) : WeatherService {
        override val serviceName: String = "weather_failure_test_fake"
        override val provenance: String = "TEST_FAKE"
        var calls = 0

        override suspend fun fetch(location: WeatherLocation): WeatherFetchResult {
            calls += 1
            return WeatherFetchResult.Failure(
                AppError(ErrorCode.WEATHER_FAILURE, technicalDetail = detail),
            )
        }
    }
}
