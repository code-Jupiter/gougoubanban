package com.littlekitty.xiaokele.chat

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.time.MutableTimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.network.external.WeatherFetchResult
import com.littlekitty.xiaokele.core.network.external.WeatherLocation
import com.littlekitty.xiaokele.core.network.external.PendingWeatherService
import com.littlekitty.xiaokele.core.network.external.WeatherService
import com.littlekitty.xiaokele.discovery.CurrentLocationDetails
import com.littlekitty.xiaokele.discovery.CurrentLocationResolver
import com.littlekitty.xiaokele.external.ExternalContextResolver
import com.littlekitty.xiaokele.external.LocationFix
import com.littlekitty.xiaokele.external.LocationSnapshotRefresher
import com.littlekitty.xiaokele.external.LocationUpdateReport
import com.littlekitty.xiaokele.external.ExternalSyncOutcomes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
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
class DefaultLocalChatToolExecutorTest {

    private lateinit var db: AppDatabase
    private lateinit var time: MutableTimeProvider

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        time = MutableTimeProvider(Instant.parse("2026-08-26T10:00:00Z"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun queryLocationRequestsFreshFixAndReturnsCoordinates() = runBlocking {
        val provider = RecordingLocationFixProvider(
            CurrentLocationDetails(
                fix = LocationFix(latitude = 31.2304, longitude = 121.4737, accuracyMeters = 12.5),
                province = "浙江省",
                city = "杭州市",
                district = "西湖区",
                searchArea = "hangzhou",
            ),
        )
        val executor = executor(provider)

        val result = executor.execute(
            FrontChatToolCall(
                id = "call_location_1",
                name = DefaultLocalChatToolExecutor.TOOL_QUERY_LOCATION,
                arguments = buildJsonObject {},
                assistantMessage = buildJsonObject {},
            ),
        )

        val data = result["result"]!!.jsonObject["data"]!!.jsonObject
        assertEquals(1, provider.calls)
        assertEquals("true", result["result"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
        assertEquals(31.2304, data["latitude"]!!.jsonPrimitive.double, 0.000001)
        assertEquals(121.4737, data["longitude"]!!.jsonPrimitive.double, 0.000001)
        assertEquals(12.5, data["accuracy_meters"]!!.jsonPrimitive.double, 0.000001)
        assertEquals("浙江省", data["province"]!!.jsonPrimitive.content)
        assertEquals("杭州市", data["city"]!!.jsonPrimitive.content)
        assertEquals("西湖区", data["district"]!!.jsonPrimitive.content)
        assertEquals("hangzhou", data["search_area"]!!.jsonPrimitive.content)
        assertTrue(data["fresh"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(time.now().toEpochMilli(), data["observed_at_epoch_millis"]!!.jsonPrimitive.long)
    }

    @Test
    fun queryLocationFailsClosedWhenPermissionOrProviderIsUnavailable() = runBlocking {
        val executor = executor(RecordingLocationFixProvider(null))

        val result = executor.execute(
            FrontChatToolCall(
                id = "call_location_2",
                name = DefaultLocalChatToolExecutor.TOOL_QUERY_LOCATION,
                arguments = buildJsonObject {},
                assistantMessage = buildJsonObject {},
            ),
        )

        assertEquals("false", result["result"]!!.jsonObject["ok"]!!.jsonPrimitive.content)
        assertEquals(
            "location_unavailable",
            result["result"]!!.jsonObject["error_code"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun queryWeatherUsesFreshFixAndPersistsItBeforeCallingWeatherService() = runBlocking {
        val current = CurrentLocationDetails(
            fix = LocationFix(latitude = 31.2304, longitude = 121.4737, accuracyMeters = 12.5),
            city = "杭州市",
        )
        val provider = RecordingLocationFixProvider(current)
        val refresher = RecordingLocationSnapshotRefresher()
        val weather = RecordingWeatherService()
        val executor = executor(provider, weather, refresher)

        val result = executor.execute(
            FrontChatToolCall(
                id = "call_weather_1",
                name = DefaultLocalChatToolExecutor.TOOL_QUERY_WEATHER,
                arguments = buildJsonObject {
                    put("latitude", JsonPrimitive(1.0))
                    put("longitude", JsonPrimitive(2.0))
                    put("city", JsonPrimitive("模型猜测的城市"))
                },
                assistantMessage = buildJsonObject {},
            ),
        )

        assertEquals(1, provider.calls)
        assertEquals(current.fix, refresher.fix)
        assertEquals(current.fix.latitude, weather.location?.latitude)
        assertEquals(current.fix.longitude, weather.location?.longitude)
        assertEquals(current.city, weather.location?.city)
        assertEquals("weather_empty", result["result"]!!.jsonObject["error_code"]!!.jsonPrimitive.content)
    }

    @Test
    fun readOnlyWeatherEvidenceDoesNotPersistLocation() = runBlocking {
        val current = CurrentLocationDetails(
            fix = LocationFix(latitude = 31.2304, longitude = 121.4737, accuracyMeters = 12.5),
            city = "杭州市",
        )
        val refresher = RecordingLocationSnapshotRefresher()
        val weather = RecordingWeatherService()
        val executor = executor(
            provider = RecordingLocationFixProvider(current),
            weatherService = weather,
            locationSnapshotRefresher = refresher,
            allowDurableWrites = false,
        )

        val result = executor.execute(
            FrontChatToolCall(
                id = "call_weather_read_only",
                name = DefaultLocalChatToolExecutor.TOOL_QUERY_WEATHER,
                arguments = buildJsonObject {},
                assistantMessage = buildJsonObject {},
            ),
        )

        assertNull(refresher.fix)
        assertEquals("weather_empty", result["result"]!!.jsonObject["error_code"]!!.jsonPrimitive.content)
        assertEquals(31.2304, weather.location?.latitude)
        assertEquals("杭州市", weather.location?.city)
    }

    /**
     * Degradation must name the cause. Saying only "service temporarily
     * unavailable" tells the user to retry something that retrying cannot fix:
     * no QWeather credential is configured.
     */
    @Test
    fun unconfiguredWeatherNamesTheMissingSetupInsteadOfBlamingTheNetwork() = runBlocking {
        val current = CurrentLocationDetails(
            fix = LocationFix(latitude = 31.2304, longitude = 121.4737, accuracyMeters = 12.5),
            city = "杭州市",
        )
        val executor = executor(
            provider = RecordingLocationFixProvider(current),
            weatherService = UnconfiguredWeatherService(),
            // Read-only evidence skips the durable-location refresh guard, which
            // legitimately fires first when no refresher is wired.
            allowDurableWrites = false,
        )

        val result = executor.execute(
            FrontChatToolCall(
                id = "call_weather_unconfigured",
                name = DefaultLocalChatToolExecutor.TOOL_QUERY_WEATHER,
                arguments = buildJsonObject {},
                assistantMessage = buildJsonObject {},
            ),
        )

        val payload = result["result"]!!.jsonObject
        assertEquals("weather_unavailable", payload["error_code"]!!.jsonPrimitive.content)
        val message = payload["message"]!!.jsonPrimitive.content
        assertTrue(
            "an unconfigured provider must be named, got: $message",
            message.contains("配置"),
        )
    }

    private fun executor(
        provider: CurrentLocationResolver,
        weatherService: WeatherService = PendingWeatherService(),
        locationSnapshotRefresher: LocationSnapshotRefresher? = null,
        allowDurableWrites: Boolean = true,
    ): DefaultLocalChatToolExecutor {
        val resolver = ExternalContextResolver(
            db = db,
            timeProvider = time,
        )
        return DefaultLocalChatToolExecutor(
            weatherService = weatherService,
            contextResolver = resolver,
            timeProvider = time,
            currentLocationResolver = provider,
            locationSnapshotRefresher = locationSnapshotRefresher,
            allowDurableWrites = allowDurableWrites,
        )
    }

    private class RecordingLocationFixProvider(
        private val location: CurrentLocationDetails?,
    ) : CurrentLocationResolver {
        var calls: Int = 0

        override suspend fun resolveCurrent(): CurrentLocationDetails? {
            calls += 1
            return location
        }
    }

    private class RecordingLocationSnapshotRefresher : LocationSnapshotRefresher {
        var fix: LocationFix? = null

        override suspend fun persistFreshLocation(fix: LocationFix): LocationUpdateReport {
            this.fix = fix
            return LocationUpdateReport(ExternalSyncOutcomes.SYNCED)
        }
    }

    private class RecordingWeatherService : WeatherService {
        override val serviceName: String = "test_weather"
        var location: WeatherLocation? = null

        override suspend fun fetch(location: WeatherLocation): WeatherFetchResult {
            this.location = location
            return WeatherFetchResult.Empty
        }
    }

    /** Mirrors the real adapter when no QWeather credential is saved. */
    private class UnconfiguredWeatherService : WeatherService {
        override val serviceName: String = "unconfigured_weather"

        override suspend fun fetch(location: WeatherLocation): WeatherFetchResult =
            WeatherFetchResult.Failure(
                com.littlekitty.xiaokele.core.common.error.AppError(
                    com.littlekitty.xiaokele.core.common.error.ErrorCode.WEATHER_NOT_CONFIGURED,
                    userMessage = null,
                    technicalDetail = "qweather_config_incomplete",
                ),
            )
    }
}
