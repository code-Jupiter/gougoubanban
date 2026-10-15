package com.littlekitty.xiaokele.debug

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.scene.FrozenSceneThresholdProvider
import com.littlekitty.xiaokele.core.common.scene.SceneCandidateThresholds
import com.littlekitty.xiaokele.core.common.scene.SceneThresholdProvider
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.network.external.NearbyCandidateDto
import com.littlekitty.xiaokele.core.network.external.NearbyCandidateService
import com.littlekitty.xiaokele.core.network.external.NearbySearchRequest
import com.littlekitty.xiaokele.core.network.external.NearbySearchResult
import com.littlekitty.xiaokele.core.network.external.WeatherAlertDto
import com.littlekitty.xiaokele.core.network.external.WeatherFetchResult
import com.littlekitty.xiaokele.core.network.external.WeatherLocation
import com.littlekitty.xiaokele.core.network.external.WeatherService
import com.littlekitty.xiaokele.chat.SceneTestCommandHandler
import com.littlekitty.xiaokele.core.network.model.FakeModelService
import com.littlekitty.xiaokele.core.network.model.ModelService
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import com.littlekitty.xiaokele.core.network.model.RoundDiagnosticsOwner
import com.littlekitty.xiaokele.provider.AppModelRouter
import com.littlekitty.xiaokele.core.network.external.WeatherSnapshotDto
import com.littlekitty.xiaokele.core.network.external.QWeatherServiceAdapter
import com.littlekitty.xiaokele.core.network.external.QWeatherServiceConfig
import com.littlekitty.xiaokele.settings.QWeatherSettingsStore
import com.littlekitty.xiaokele.external.LocationFix
import com.littlekitty.xiaokele.external.AndroidLocationFixProvider
import com.littlekitty.xiaokele.external.LocationFixProvider
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.time.Instant

enum class DebugWeatherMode {
    QWEATHER,
    SUCCESS,
    NORMAL,
    HIGH_ALERT,
    EMPTY,
    FAILURE,
}

enum class DebugNearbyMode {
    SUCCESS,
    EMPTY,
    FAILURE,
}

/**
 * Debug-only Phase 6 external data fakes. Every fake marks provenance as
 * DEBUG_FAKE; this file lives in app/src/debug and is never compiled into
 * release APKs.
 */
class DebugModelRouter(
    private val sceneModel: DebugSceneModelService,
    private val appModelRouter: AppModelRouter,
    private val context: android.content.Context,
) : ModelService, RoundDiagnosticsOwner {
    override val serviceName: String = "debug_model_router"
    override val recordsOwnModelRounds: Boolean
        get() = (sceneModel.sceneMode == DebugSceneMode.NONE ||
            RealAiAcceptanceStore(context).realChainMode) && appModelRouter.recordsOwnModelRounds

    override suspend fun generate(context: com.littlekitty.xiaokele.core.common.protocol.ContextPack): ModelServiceResult =
        if (sceneModel.sceneMode == DebugSceneMode.NONE ||
            RealAiAcceptanceStore(this.context).realChainMode
        ) {
            appModelRouter.generate(context)
        } else {
            sceneModel.generate(context)
        }
}

object DebugExternalDataModule {
    const val DEBUG_FAKE = "DEBUG_FAKE"

    val module = module {
        single { DebugSceneThresholdProvider() }
        single<SceneThresholdProvider> { get<DebugSceneThresholdProvider>() }
        single { DebugDataMaintenanceService(get(), get(), get()) }
        single { BackgroundTaskInspector(get(), get()) }
        single {
            DebugRealAiAcceptanceService(
                db = get(),
                appContext = androidContext(),
                appModelRouter = get(),
                policyResolver = get(),
                backgroundContextBuilder = get(),
                chatContextBuilder = get(),
                sceneGate = get(),
                automationRuleStore = get(),
                timeProvider = get(),
                byokSettingsStore = get(),
            )
        }
        single<SceneTestCommandHandler> { DebugSceneTestCommandHandler(get()) }
        // The Debug APK uses the same real router as release. Script models
        // remain available only to unit tests and are not runtime-wired.
        // Debug runtime uses the actual device location provider. Permission
        // failures are surfaced as missing input; no fixed coordinates exist.
        single<LocationFixProvider> { AndroidLocationFixProvider(androidContext()) }
        single<WeatherService> {
            val qWeatherSettings = get<QWeatherSettingsStore>()
            QWeatherServiceAdapter(
                transport = get(),
                configProvider = {
                    QWeatherServiceConfig(
                        apiHost = qWeatherSettings.apiHost(),
                        apiKey = qWeatherSettings.loadApiKeyOrNull().orEmpty(),
                    )
                },
            )
        }
        single {
            RealAiAcceptanceService(
                context = get(),
                db = get(),
                timeProvider = get(),
                diagnosticsLogger = get(),
                dataMaintenance = get(),
                behaviorTrainer = get(),
                conversationContextManager = get(),
                byokSettingsStore = get(),
                debugRealAi = get(),
            )
        }
    }
}

class DebugLocationFixProvider : LocationFixProvider {
    override suspend fun currentFix(): LocationFix = LocationFix(
        latitude = 0.0,
        longitude = 0.0,
        accuracyMeters = 1_000.0,
    )
}

class DebugWeatherService(
    private val realService: WeatherService,
    private val configured: Boolean,
) : WeatherService {

    override val serviceName: String
        get() = if (mode == DebugWeatherMode.QWEATHER) realService.serviceName else "weather_debug_fake"
    override val provenance: String
        get() = if (mode == DebugWeatherMode.QWEATHER) realService.provenance else DebugExternalDataModule.DEBUG_FAKE
    override val isDebugFake: Boolean
        get() = mode != DebugWeatherMode.QWEATHER

    @Volatile
    var mode: DebugWeatherMode = if (configured) DebugWeatherMode.QWEATHER else DebugWeatherMode.SUCCESS

    @Volatile
    var calls: Int = 0

    override suspend fun fetch(location: WeatherLocation): WeatherFetchResult {
        calls++
        return when (mode) {
            DebugWeatherMode.QWEATHER -> realService.fetch(location)

            DebugWeatherMode.FAILURE -> WeatherFetchResult.Failure(
                AppError(
                    code = ErrorCode.WEATHER_FAILURE,
                    userMessage = null,
                    technicalDetail = "debug_fake_weather_failure",
                ),
            )

            DebugWeatherMode.NORMAL -> {
                val now = Instant.now()
                WeatherFetchResult.Success(
                    WeatherSnapshotDto(
                        locationKey = location.placeType.lowercase(),
                        observedAt = InstantJson.formatUtc(now),
                        condition = "cloudy",
                        temperatureCelsius = 24.0,
                        precipitationMm = 0.0,
                        temperatureTrend = "steady",
                        temperatureDeltaCelsius = 0.0,
                        aqi = 52,
                        aqiCategory = "good",
                        alerts = emptyList(),
                        expiresAt = InstantJson.formatUtc(now.plusSeconds(3600)),
                        provenance = DebugExternalDataModule.DEBUG_FAKE,
                    ),
                )
            }

            DebugWeatherMode.HIGH_ALERT -> {
                val now = Instant.now()
                WeatherFetchResult.Success(
                    WeatherSnapshotDto(
                        locationKey = location.placeType.lowercase(),
                        observedAt = InstantJson.formatUtc(now),
                        condition = "storm",
                        temperatureCelsius = 20.0,
                        precipitationMm = 12.0,
                        temperatureTrend = "cooling",
                        temperatureDeltaCelsius = -6.0,
                        aqi = 60,
                        aqiCategory = "good",
                        alerts = listOf(
                            WeatherAlertDto(
                                id = "debug_alert_storm_high",
                                severity = "HIGH",
                                title = "暴雨橙色预警",
                                source = "DEBUG_FAKE",
                                startsAt = InstantJson.formatUtc(now),
                                endsAt = InstantJson.formatUtc(now.plusSeconds(6 * 3600)),
                                instruction = "debug fake only",
                            ),
                        ),
                        expiresAt = InstantJson.formatUtc(now.plusSeconds(3600)),
                        provenance = DebugExternalDataModule.DEBUG_FAKE,
                    ),
                )
            }

            DebugWeatherMode.EMPTY -> WeatherFetchResult.Empty

            DebugWeatherMode.SUCCESS -> {
                val now = Instant.now()
                WeatherFetchResult.Success(
                    WeatherSnapshotDto(
                        locationKey = location.placeType.lowercase(),
                        observedAt = InstantJson.formatUtc(now),
                        condition = "rain",
                        temperatureCelsius = 22.0,
                        precipitationMm = 2.5,
                        temperatureTrend = "cooling",
                        temperatureDeltaCelsius = -4.0,
                        aqi = 48,
                        aqiCategory = "good",
                        alerts = listOf(
                            WeatherAlertDto(
                                id = "debug_alert_rain",
                                severity = "HIGH",
                                title = "短时强降雨",
                                source = "DEBUG_FAKE",
                                startsAt = InstantJson.formatUtc(now),
                                endsAt = InstantJson.formatUtc(now.plusSeconds(6 * 3600)),
                                instruction = "debug fake only",
                            ),
                        ),
                        expiresAt = InstantJson.formatUtc(now.plusSeconds(3600)),
                        provenance = DebugExternalDataModule.DEBUG_FAKE,
                    ),
                )
            }
        }
    }
}

class DebugNearbyCandidateService : NearbyCandidateService {

    override val serviceName: String = "nearby_debug_fake"
    override val provenance: String = DebugExternalDataModule.DEBUG_FAKE
    override val isDebugFake: Boolean = true

    @Volatile
    var mode: DebugNearbyMode = DebugNearbyMode.SUCCESS

    @Volatile
    var calls: Int = 0

    override suspend fun search(request: NearbySearchRequest): NearbySearchResult {
        calls++
        return when (mode) {
            DebugNearbyMode.FAILURE -> NearbySearchResult.Failure(
                AppError(
                    code = ErrorCode.NEARBY_FAILURE,
                    userMessage = null,
                    technicalDetail = "debug_fake_nearby_failure",
                ),
            )

            DebugNearbyMode.EMPTY -> NearbySearchResult.Empty

            DebugNearbyMode.SUCCESS -> NearbySearchResult.Success(
                listOf(
                    NearbyCandidateDto(
                        merchantId = "debug_merchant_noodle",
                        name = "小面馆（调试）",
                        category = "noodle",
                        representativeDishes = listOf("牛肉面"),
                        priceHint = "25",
                        distanceMeters = 300,
                        detailUrl = "https://example.invalid/debug",
                        source = "DEBUG_FAKE",
                        publishedAt = InstantJson.formatUtc(Instant.now().minusSeconds(600)),
                        verified = false,
                    ),
                ),
            )
        }
    }
}

/**
 * Debug-only mutable CANDIDATE threshold overrides. The class and its controls
 * exist only in app/src/debug; release keeps FrozenSceneThresholdProvider.
 */
class DebugSceneThresholdProvider : SceneThresholdProvider {
    @Volatile
    var overrides: SceneCandidateThresholds? = null

    @Volatile
    private var defaults: SceneCandidateThresholds = SceneCandidateThresholds()

    override fun current(): SceneCandidateThresholds = overrides ?: defaults

    fun reset(thresholds: SceneCandidateThresholds = SceneCandidateThresholds()) {
        defaults = thresholds
        overrides = null
    }
}
