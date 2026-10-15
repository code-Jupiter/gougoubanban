package com.littlekitty.xiaokele.scene

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames
import com.littlekitty.xiaokele.core.common.scene.SceneCandidateThresholds
import com.littlekitty.xiaokele.core.common.scene.SceneCooldown
import com.littlekitty.xiaokele.core.common.scene.SceneEventTypes
import com.littlekitty.xiaokele.core.common.scene.SceneSources
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.external.ExternalEventContract
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface SceneEvaluator {
    suspend fun evaluate(
        event: EventEntity,
        context: SceneContextSnapshot,
    ): SceneEvaluation?
}

internal fun EventEntity.payloadObject(): JsonObject =
    runCatching { JsonCodec.json.parseToJsonElement(payloadJson) as? JsonObject }
        .getOrNull() ?: JsonObject(emptyMap())

internal fun JsonObject.string(key: String): String? =
    this[key]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.contentOrNull

internal fun JsonObject.doubleOrNull(key: String): Double? =
    this[key]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.content?.toDoubleOrNull()

internal fun JsonObject.intOrNull(key: String): Int? =
    this[key]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.content?.toIntOrNull()

internal fun JsonObject.longOrNull(key: String): Long? =
    this[key]?.takeIf { it is JsonPrimitive }?.jsonPrimitive?.content?.toLongOrNull()

class InteractionSceneEvaluator(
    private val thresholds: () -> SceneCandidateThresholds,
) : SceneEvaluator {
    override suspend fun evaluate(event: EventEntity, context: SceneContextSnapshot): SceneEvaluation? {
        if (event.type != SceneEventTypes.INTERACTION_GAP) return null
        val payload = event.payloadObject()
        // Debug builds can provide a synthetic historical timestamp without
        // mutating the user's real chat history. Production event producers
        // never set this field because the source check is explicit.
        val debugLastInteraction = payload.longOrNull("debug_last_interaction_at_epoch_millis")
            ?.takeIf { event.source == SceneSources.DEBUG_SCENE_SCRIPT }
        val lastInteractionAt = debugLastInteraction ?: context.lastInteractionAtEpochMillis
        val gapMillis = (context.nowEpochMillis - lastInteractionAt).coerceAtLeast(0L)
        val effectiveGapCandidateMillis = payload.longOrNull("effective_gap_candidate_millis")
            ?.coerceAtLeast(1L)
            ?: thresholds().interactionGapRestCandidateMillis
        val derived = mapOf<String, JsonElement>(
            "last_interaction_at_epoch_millis" to JsonPrimitive(lastInteractionAt),
            "gap_millis" to JsonPrimitive(gapMillis),
            "campaign_stage" to JsonPrimitive(interactionCampaignStage(gapMillis)),
            "effective_gap_candidate_millis" to JsonPrimitive(effectiveGapCandidateMillis),
        )
        if (lastInteractionAt <= 0L) {
            return SceneEvaluation.noAction(
                reasonCode = "interaction_gap_no_interaction_history",
                sceneGroup = SceneCooldown.GROUP_INTERACTION_GAP,
                summary = "没有有效互动历史，不启动久未互动召回。",
                derivedFields = derived,
            )
        }
        if (gapMillis < effectiveGapCandidateMillis) {
            return SceneEvaluation.noAction(
                reasonCode = "interaction_gap_below_candidate_threshold",
                sceneGroup = SceneCooldown.GROUP_INTERACTION_GAP,
                summary = "距上次有效互动尚未达到候选阈值。",
                derivedFields = derived,
            )
        }
        if (gapMillis >= INTERACTION_CAMPAIGN_MAX_MILLIS) {
            return SceneEvaluation.noAction(
                reasonCode = "interaction_gap_campaign_ended",
                sceneGroup = SceneCooldown.GROUP_INTERACTION_GAP,
                summary = "久未互动召回周期已结束，等待下一次有效互动。",
                derivedFields = derived,
            )
        }
        return SceneEvaluation.allow(
            reasonCode = "interaction_gap_candidate",
            sceneGroup = SceneCooldown.GROUP_INTERACTION_GAP,
            summary = "休息期久未互动候选。",
            derivedFields = derived,
        )
    }

    private fun interactionCampaignStage(gapMillis: Long): String = when {
        gapMillis < 36L * 60L * 60L * 1000L -> "HOOK_1"
        gapMillis < 48L * 60L * 60L * 1000L -> "HOOK_2"
        gapMillis < 60L * 60L * 60L * 1000L -> "REENGAGE_ANIMATION"
        gapMillis < INTERACTION_CAMPAIGN_MAX_MILLIS -> "FINAL_HOOK"
        else -> "ENDED"
    }

    private companion object {
        const val INTERACTION_CAMPAIGN_MAX_MILLIS = 72L * 60L * 60L * 1000L
    }
}

class WeatherSceneEvaluator(
    private val thresholds: () -> SceneCandidateThresholds,
) : SceneEvaluator {
    override suspend fun evaluate(event: EventEntity, context: SceneContextSnapshot): SceneEvaluation? {
        val payload = event.payloadObject()
        return when (event.type) {
            ExternalEventTypeNames.WEATHER_CHANGE -> evaluateWeatherChange(event, payload, context)
            ExternalEventTypeNames.WEATHER_ALERT -> evaluateWeatherAlert(event, payload)
            else -> null
        }
    }

    private fun evaluateWeatherChange(
        event: EventEntity,
        payload: JsonObject,
        context: SceneContextSnapshot,
    ): SceneEvaluation {
        val condition = payload.string("condition").orEmpty()
        val precipitation = payload.doubleOrNull("precipitation_mm")
        val temperatureDelta = payload.doubleOrNull("temperature_delta_celsius")
        val aqi = payload.intOrNull("aqi")
        val aqiCategory = payload.string("aqi_category").orEmpty()
        val t = thresholds()
        val poorAir = (aqi != null && aqi >= 150) || aqiCategory.contains("差") || aqiCategory.contains("重度") || aqiCategory.contains("严重")
        val significant = condition.lowercase() in t.weatherChangeSignificantConditions ||
            (precipitation != null && precipitation >= t.weatherChangeSignificantPrecipitationMm) ||
            (temperatureDelta != null &&
                kotlin.math.abs(temperatureDelta) >= t.weatherChangeSignificantTemperatureDeltaCelsius) ||
            poorAir

        val derived = mapOf<String, JsonElement>(
            "condition" to JsonPrimitive(condition),
            "weather_significant" to JsonPrimitive(significant),
            "poor_air_quality" to JsonPrimitive(poorAir),
        )
        // Only genuinely significant weather changes become candidates; calm
        // weather must never produce a mechanical daily broadcast.
        if (!significant) {
            return SceneEvaluation.noAction(
                reasonCode = "ordinary_weather_not_significant",
                sceneGroup = SceneCooldown.GROUP_WEATHER_CHANGE,
                summary = "平稳天气不得产生机械日播。",
                derivedFields = derived,
            )
        }
        return SceneEvaluation.allow(
            reasonCode = "significant_weather_change_candidate",
            sceneGroup = SceneCooldown.GROUP_WEATHER_CHANGE,
            derivedFields = derived,
        )
    }

    private fun evaluateWeatherAlert(event: EventEntity, payload: JsonObject): SceneEvaluation {
        val severity = payload.string("severity").orEmpty()
        val isHigh = severity == thresholds().weatherHighAlertSeverity
        val derived = mapOf<String, JsonElement>(
            "severity" to JsonPrimitive(severity),
            "high_alert" to JsonPrimitive(isHigh),
        )
        return SceneEvaluation.noAction(
            reasonCode = if (isHigh) "high_weather_alert_context_only" else "weather_alert_context_only",
            sceneGroup = SceneCooldown.GROUP_WEATHER_ALERT_HIGH,
            summary = "天气预警只保留为 AI 可读取的天气事实，不作为必达触达。",
            derivedFields = derived,
        )
    }
}

class DailyWeatherSceneEvaluator : SceneEvaluator {
    override suspend fun evaluate(event: EventEntity, context: SceneContextSnapshot): SceneEvaluation? {
        if (event.type != ExternalEventTypeNames.DAILY_WEATHER_BRIEF) return null
        val payload = event.payloadObject()
        val eligible = payload.string("eligible") == "true"
        return if (eligible) {
            SceneEvaluation.allow(
                reasonCode = "daily_weather_brief_candidate",
                sceneGroup = SceneCooldown.GROUP_WEATHER_DAILY,
                summary = "当天天气关心候选。",
                derivedFields = mapOf(
                    "local_date" to JsonPrimitive(payload.string("local_date").orEmpty()),
                ),
            )
        } else {
            SceneEvaluation.noAction(
                reasonCode = "daily_weather_brief_not_eligible",
                sceneGroup = SceneCooldown.GROUP_WEATHER_DAILY,
                summary = "当前不是日常天气关心的有效窗口。",
            )
        }
    }
}

class NearbySceneEvaluator(
    private val thresholds: () -> SceneCandidateThresholds,
) : SceneEvaluator {
    override suspend fun evaluate(event: EventEntity, context: SceneContextSnapshot): SceneEvaluation? {
        if (event.type != ExternalEventTypeNames.NEARBY_CANDIDATE) return null
        return SceneEvaluation.noAction(
            reasonCode = "dining_feature_disabled",
            sceneGroup = SceneCooldown.GROUP_NEARBY,
            summary = "当前版本未启用附近餐饮，不查询也不调用模型。",
        )
    }
}
