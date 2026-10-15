package com.littlekitty.xiaokele.debug

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.littlekitty.xiaokele.chat.SceneTestCommandHandler
import com.littlekitty.xiaokele.core.common.protocol.NormalizedEvent
import com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames
import com.littlekitty.xiaokele.core.common.scene.SceneEventTypes
import com.littlekitty.xiaokele.core.common.scene.ScenePriorities
import com.littlekitty.xiaokele.core.common.scene.SceneSources
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.network.external.WeatherQueryProfile
import com.littlekitty.xiaokele.discovery.DiscoveryCacheStore
import com.littlekitty.xiaokele.external.ExternalDataCoordinator
import com.littlekitty.xiaokele.external.ExternalEventContract
import com.littlekitty.xiaokele.external.LocationFix
import com.littlekitty.xiaokele.external.WeatherRefresh
import com.littlekitty.xiaokele.event.LocalBackgroundDispatcher
import com.littlekitty.xiaokele.floating.FloatingPetService
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** Debug chat commands that schedule an actual background scene after a short delay. */
class DebugSceneTestCommandHandler(
    private val context: Context,
) : SceneTestCommandHandler {

    override suspend fun trySchedule(text: String): String? {
        if (DebugSceneScenario.from(text) == null) return null
        // Scripted scene commands used to be able to produce fake success
        // messages. Keep the parser for compatibility, but make the old
        // chat-triggered path inert; manual acceptance now lives in the
        // two-stage Real AI page.
        return "已停用脚本场景验收：请在 Debug 中心选择场景，先生成报文、人工确认后再提交真实模型。"
    }

    companion object {
        const val DEBUG_SCENE_WORK_TAG = "little_kitty_debug_scene_test"
    }
}

enum class DebugSceneScenario(val label: String) {
    INTERACTION_GAP("SC-01 久未互动"),
    DAILY_WEATHER("SC-05 普通天气关心"),
    WAKE_WEATHER("起床天气关心"),
    DISCOVERY_NUDGE("本地生活轻提"),
    HIGH_WEATHER_ALERT("高风险天气上下文"),
    LIVE_INTERACTION_GAP("真实 MiniMax 久未互动"),
    INVALID_BACKGROUND_OUTPUT("后台非法输出安全拦截");

    companion object {
        fun from(raw: String): DebugSceneScenario? {
            val value = raw.trim().lowercase()
                .replace(" ", "")
                .removePrefix("/")
                .removePrefix("测试")
                .removePrefix("场景")
            return when (value) {
                "久未互动", "久未联系", "sc01", "sc-01" -> INTERACTION_GAP
                "天气", "普通天气", "天气关心", "sc05", "sc-05" -> DAILY_WEATHER
                "起床天气", "起床", "wake-weather" -> WAKE_WEATHER
                "轻提", "本地生活", "美食", "活动", "discovery-nudge" -> DISCOVERY_NUDGE
                "预警", "高风险天气", "天气预警" -> HIGH_WEATHER_ALERT
                "真实久未互动", "真实sc01", "live-sc01" -> LIVE_INTERACTION_GAP
                "非法后台", "后台非法输出", "invalid-background" -> INVALID_BACKGROUND_OUTPUT
                else -> null
            }
        }
    }
}

class DebugSceneTestWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // The former worker injected fake inputs and entered the production
        // delivery pipeline. Manual acceptance is now exclusively the
        // two-stage DebugRealAiAcceptanceService flow.
        return Result.failure()
    }

    companion object {
        const val KEY_SCENARIO = "debug_scene_scenario"
    }
}

/** Reuses the real event normalizer, scene gate, background pipeline and channel adapters. */
class DebugSceneTestExecutor(
    private val db: AppDatabase,
    private val dispatcher: LocalBackgroundDispatcher,
    private val externalDataCoordinator: ExternalDataCoordinator,
    private val weatherRefresh: WeatherRefresh,
    private val debugSceneModel: DebugSceneModelService,
    private val debugWeather: DebugWeatherService,
    private val debugSceneThresholds: DebugSceneThresholdProvider,
    private val discoveryCache: DiscoveryCacheStore,
    private val context: Context,
) {

    suspend fun execute(scenario: DebugSceneScenario) {
        when (scenario) {
            DebugSceneScenario.INTERACTION_GAP -> interactionGap()
            DebugSceneScenario.DAILY_WEATHER -> dailyWeather()
            DebugSceneScenario.WAKE_WEATHER -> wakeWeather()
            DebugSceneScenario.DISCOVERY_NUDGE -> discoveryNudge()
            DebugSceneScenario.HIGH_WEATHER_ALERT -> highWeatherAlert()
            DebugSceneScenario.LIVE_INTERACTION_GAP -> liveInteractionGap()
            DebugSceneScenario.INVALID_BACKGROUND_OUTPUT -> invalidBackgroundOutput()
        }
        if (scenario in setOf(
                DebugSceneScenario.INTERACTION_GAP,
                DebugSceneScenario.DAILY_WEATHER,
                DebugSceneScenario.DISCOVERY_NUDGE,
                DebugSceneScenario.LIVE_INTERACTION_GAP,
            )) {
            // The user deliberately left the app during the five-second arm
            // window. Ask the existing floating channel to consume the
            // persisted bubble now; if Android defers the service start, the
            // normal next-unlock path still consumes the same durable item.
            runCatching { FloatingPetService.showPending(context) }
        }
    }

    private suspend fun interactionGap() {
        debugSceneModel.sceneMode = DebugSceneMode.SCENE_INTERACTION_REENGAGE
        val now = Instant.now()
        dispatch(
            type = SceneEventTypes.INTERACTION_GAP,
            key = "debug:scene:interaction_gap:${now.toEpochMilli()}",
            priority = ScenePriorities.INTERACTION_GAP,
            expiresAt = now.plusSeconds(30 * 60),
            payload = buildJsonObject {
                put("debug_last_interaction_at_epoch_millis", now.toEpochMilli() - 25L * 60 * 60 * 1000)
                put("gap_millis", 25L * 60 * 60 * 1000)
            },
        )
    }

    /** Debug injection only; model generation remains the real AppModelRouter. */
    private suspend fun liveInteractionGap() {
        debugSceneModel.sceneMode = DebugSceneMode.NONE
        // This command exists specifically for repeated real-model regression.
        // Do not let an earlier debug delivery prevent the event from reaching
        // AppModelRouter; process restart restores the normal defaults.
        debugSceneThresholds.overrides = debugSceneThresholds.current().copy(
            sceneCooldownMillis = 0L,
        )
        val now = Instant.now()
        dispatch(
            type = SceneEventTypes.INTERACTION_GAP,
            key = "debug:live:interaction_gap:${now.toEpochMilli()}",
            priority = ScenePriorities.INTERACTION_GAP,
            expiresAt = now.plusSeconds(30 * 60),
            payload = buildJsonObject {
                put("debug_last_interaction_at_epoch_millis", now.toEpochMilli() - 25L * 60 * 60 * 1000)
                put("gap_millis", 25L * 60 * 60 * 1000)
                put("live_model_regression", true)
            },
        )
    }

    /** Forces both the initial and bounded repair result to stay invalid. */
    private suspend fun invalidBackgroundOutput() {
        debugSceneModel.sceneMode = DebugSceneMode.SCENE_INVALID_OUTPUT
        val now = Instant.now()
        dispatch(
            type = SceneEventTypes.INTERACTION_GAP,
            key = "debug:invalid:background:${now.toEpochMilli()}",
            priority = ScenePriorities.INTERACTION_GAP,
            expiresAt = now.plusSeconds(30 * 60),
            payload = buildJsonObject {
                put("debug_last_interaction_at_epoch_millis", now.toEpochMilli() - 25L * 60 * 60 * 1000)
                put("gap_millis", 25L * 60 * 60 * 1000)
                put("invalid_output_regression", true)
            },
        )
    }

    private suspend fun dailyWeather() {
        debugSceneModel.sceneMode = DebugSceneMode.SCENE_DAILY_WEATHER
        debugWeather.mode = weatherMode(DebugWeatherMode.NORMAL)
        val now = Instant.now()
        refreshWeatherForScenario(WeatherQueryProfile.DAILY_BRIEF)
        dispatch(
            type = ExternalEventTypeNames.DAILY_WEATHER_BRIEF,
            key = "debug:scene:weather_daily:${now.toEpochMilli()}",
            priority = ScenePriorities.LOCAL_CANDIDATE_DEFAULT,
            expiresAt = now.plusSeconds(30 * 60),
            payload = buildJsonObject {
                put("eligible", true)
                put("local_date", now.toString().take(10))
                put("weather_condition", "cloudy")
                put("weather_test", true)
            },
        )
    }

    private suspend fun wakeWeather() {
        debugSceneModel.sceneMode = DebugSceneMode.SCENE_WAKE_WEATHER
        debugWeather.mode = weatherMode(DebugWeatherMode.NORMAL)
        val now = Instant.now()
        refreshWeatherForScenario(WeatherQueryProfile.DAILY_BRIEF)
        dispatch(
            type = ExternalEventTypeNames.WAKE_WEATHER_BRIEF,
            key = "debug:scene:weather_wake:${now.toEpochMilli()}",
            priority = ScenePriorities.LOCAL_CANDIDATE_DEFAULT,
            expiresAt = now.plusSeconds(4 * 60 * 60),
            payload = buildJsonObject {
                put("eligible", true)
                put("basis", "DEBUG")
                put("local_date", now.toString().take(10))
                put("weather_condition", "cloudy")
                put("weather_test", true)
            },
        )
    }

    private suspend fun discoveryNudge() {
        debugSceneModel.sceneMode = DebugSceneMode.SCENE_DISCOVERY_NUDGE
        val now = Instant.now()
        val items = if (RealAiAcceptanceStore(context).realChainMode) {
            discoveryCache.loadFresh(now.toEpochMilli())?.items
                ?.filter { it.category == "food" || it.category == "events" }
                ?.take(5)
                ?: error("real_discovery_cache_unavailable")
        } else {
            emptyList()
        }
        dispatch(
            type = ExternalEventTypeNames.DISCOVERY_TOPIC_NUDGE,
            key = "debug:scene:discovery_nudge:${now.toEpochMilli()}",
            priority = ScenePriorities.LOCAL_CANDIDATE_DEFAULT,
            expiresAt = now.plusSeconds(12 * 60 * 60),
            payload = buildJsonObject {
                put("local_date", now.toString().take(10))
                put("items", kotlinx.serialization.json.buildJsonArray {
                    if (items.isEmpty()) {
                        add(buildJsonObject {
                            put("item_id", "debug_item_${now.toEpochMilli()}")
                            put("title", "Debug 周末市集（测试）")
                            put("category", "events")
                            put("summary", "周末在莲都老街的市集，有很多小吃摊位（测试数据）。")
                            put("source_url", "https://example.com/debug-market")
                            put("event_start", now.toString())
                        })
                        add(buildJsonObject {
                            put("item_id", "debug_food_${now.toEpochMilli()}")
                            put("title", "Debug 新开面馆（测试）")
                            put("category", "food")
                            put("summary", "巷子里新开的一家手工面馆（测试数据）。")
                            put("source_url", "https://example.com/debug-noodle")
                        })
                    } else {
                        items.forEach { item ->
                            add(buildJsonObject {
                                put("item_id", item.id)
                                put("title", item.title)
                                put("category", item.category)
                                put("summary", item.summary)
                                put("source_url", item.sourceUrl)
                                item.eventStart?.let { put("event_start", it) }
                                item.eventEnd?.let { put("event_end", it) }
                            })
                        }
                    }
                })
            },
        )
    }

    private fun weatherMode(fakeMode: DebugWeatherMode): DebugWeatherMode =
        if (RealAiAcceptanceStore(context).realChainMode) DebugWeatherMode.QWEATHER else fakeMode

    private suspend fun refreshWeatherForScenario(profile: WeatherQueryProfile) {
        if (RealAiAcceptanceStore(context).realChainMode) {
            val report = externalDataCoordinator.refreshWeather(force = true, queryProfile = profile)
            check(report.cacheWritten || report.skippedStaleCache) {
                "real_weather_refresh_${report.outcome}"
            }
        } else {
            externalDataCoordinator.onLocationFix(debugFix())
            weatherRefresh.refresh(force = true, queryProfile = profile)
        }
    }

    private suspend fun highWeatherAlert() {
        check(!RealAiAcceptanceStore(context).realChainMode) {
            "real_chain_high_weather_alert_requires_a_provider_alert"
        }
        debugSceneModel.sceneMode = DebugSceneMode.SCENE_HIGH_ALERT_CARING
        debugWeather.mode = DebugWeatherMode.HIGH_ALERT
        val now = Instant.now()
        externalDataCoordinator.onLocationFix(debugFix())
        weatherRefresh.refresh(force = true, queryProfile = WeatherQueryProfile.CORE)
        dispatch(
            type = ExternalEventTypeNames.WEATHER_ALERT,
            key = "debug:scene:weather_alert:${now.toEpochMilli()}",
            priority = ScenePriorities.HIGH_WEATHER_ALERT,
            expiresAt = now.plusSeconds(60 * 60),
            payload = buildJsonObject {
                put("alert_id", "debug_test_high_alert")
                put("severity", "HIGH")
                put("title", "暴雨橙色预警（测试）")
            },
        )
    }

    private suspend fun dispatch(
        type: String,
        key: String,
        priority: Int,
        payload: kotlinx.serialization.json.JsonObject,
        expiresAt: Instant = Instant.now().plusSeconds(60 * 60),
    ) {
        val now = Instant.now()
        dispatcher.dispatch(
            NormalizedEvent(
                eventKey = key,
                type = type,
                source = SceneSources.DEBUG_SCENE_SCRIPT,
                occurredAt = InstantJson.formatUtc(now),
                receivedAt = InstantJson.formatUtc(now),
                expiresAt = InstantJson.formatUtc(expiresAt),
                priority = priority,
                payload = payload,
            ),
        )
    }

    private fun debugFix() = LocationFix(30.0000, 120.0000, 20.0)
}
