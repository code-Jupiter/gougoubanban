package com.littlekitty.xiaokele.core.common.scene

import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import kotlinx.serialization.Serializable

/**
 * Phase 7 frozen scene contract.
 *
 * Every product scene name, priority band, cooldown group and CANDIDATE
 * threshold used by the first seven P0 scenes lives in this file. Providers
 * keep producing only NormalizedEvent; scene code decides allow/no-action and
 * never writes fixed user copy.
 *
 * See docs/implementation/PHASE_7_GATE.md for the frozen policy before
 * changing any value here.
 */
/**
 * Kept in core/common so the app layer and tests never duplicate external
 * event type strings. Phase 6 `ExternalEventContract` is an app-layer detail;
 * scene code uses these stable names.
 */
object ExternalEventTypeNames {
    const val WEATHER_CHANGE = "WEATHER_CHANGE"
    const val WEATHER_ALERT = "WEATHER_ALERT"
    const val DAILY_WEATHER_BRIEF = "DAILY_WEATHER_BRIEF"
    const val WAKE_WEATHER_BRIEF = "WAKE_WEATHER_BRIEF"
    const val DISCOVERY_TOPIC_NUDGE = "DISCOVERY_TOPIC_NUDGE"
    const val NEARBY_CANDIDATE = "NEARBY_CANDIDATE"
}

object SceneEventTypes {
    /** Local deterministic candidate emitted by SceneCheckScheduler. */
    const val INTERACTION_GAP = "INTERACTION_GAP"

    val all = setOf(
        INTERACTION_GAP,
        ExternalEventTypeNames.WEATHER_CHANGE,
        ExternalEventTypeNames.WEATHER_ALERT,
        ExternalEventTypeNames.DAILY_WEATHER_BRIEF,
        ExternalEventTypeNames.WAKE_WEATHER_BRIEF,
        ExternalEventTypeNames.DISCOVERY_TOPIC_NUDGE,
        ExternalEventTypeNames.NEARBY_CANDIDATE,
    )
}

object SceneSources {
    const val LOCAL_SCENE_SCHEDULER = "LOCAL_SCENE_SCHEDULER"
    const val DEBUG_SCENE_SCRIPT = "DEBUG_SCENE_SCRIPT"
}

/**
 * Priority bands for scene dispatch. The full ordered set is 90/80/55/50/40.
 *
 * Shift bands and the in-shift/rest split were removed with the shift feature:
 * an ordinary interaction gap has exactly one priority, and there is no
 * location-transition or shift event band.
 */
object ScenePriorities {
    const val HIGH_WEATHER_ALERT = 90
    const val INTERACTION_GAP = 80
    const val WEATHER_CHANGE = 55
    const val LOCAL_CANDIDATE_DEFAULT = 50
    const val NEARBY_CANDIDATE = 40

    val all: Set<Int> = setOf(
        HIGH_WEATHER_ALERT,
        INTERACTION_GAP,
        WEATHER_CHANGE,
        LOCAL_CANDIDATE_DEFAULT,
        NEARBY_CANDIDATE,
    )
}

/**
 * Scene channel policy. Only one main outreach channel may exist in one
 * ActionPlan; PetState and Animation are secondary state side effects.
 */
object ScenePolicyContract {
    const val PRIMARY_CHANNEL_NOTIFICATION = "NOTIFICATION"
    const val PRIMARY_CHANNEL_PROACTIVE = "PROACTIVE_MESSAGE"
    const val PRIMARY_CHANNEL_WIDGET = "WIDGET"

    const val KEY_PRIMARY_CHANNEL = "primary_channel"
    const val KEY_ALLOWED_TODO_TYPES = "allowed_todo_types"
    const val KEY_ALLOWED_TRIGGER_TYPES = "allowed_trigger_types"
    const val KEY_SCENE_COOLDOWN_GROUP = "scene_cooldown_group"
    const val KEY_SCENE_COOLDOWN_MILLIS = "scene_cooldown_millis"
    const val KEY_NOTIFICATION_QUOTA_EXEMPT = "notification_quota_exempt"
    const val KEY_MAX_OUTPUTS = "max_outputs"
    const val KEY_REQUIRE_TODO_FOR_PROACTIVE = "require_todo_for_proactive"

    /** These are the three user-facing outreach channels. */
    val primaryReachActions: Set<String> = setOf(
        AgentActionRegistry.SEND_NOTIFICATION,
        AgentActionRegistry.SEND_PROACTIVE_MESSAGE,
        AgentActionRegistry.SET_WIDGET_STATE,
    )

    fun channelForAction(action: String): String? = when (action) {
        AgentActionRegistry.SEND_NOTIFICATION -> PRIMARY_CHANNEL_NOTIFICATION
        AgentActionRegistry.SEND_PROACTIVE_MESSAGE -> PRIMARY_CHANNEL_PROACTIVE
        AgentActionRegistry.SET_WIDGET_STATE -> PRIMARY_CHANNEL_WIDGET
        else -> null
    }
}

/**
 * Scene cross-channel cooldown groups. All channel deliveries for a scene use
 * the same group, so one widget outreach blocks a duplicate notification or
 * proactive message for the same scene for the configured cooldown window.
 */
object SceneCooldown {
    const val DEFAULT_SCENE_COOLDOWN_MILLIS = 6L * 60L * 60L * 1000L

    const val GROUP_INTERACTION_GAP = "scene:interaction_gap"
    const val GROUP_WEATHER_CHANGE = "scene:weather_change"
    const val GROUP_WEATHER_ALERT_HIGH = "scene:weather_alert_high"
    const val GROUP_WEATHER_DAILY = "scene:weather_daily"
    const val GROUP_WEATHER_WAKE = "scene:weather_wake"
    const val GROUP_DISCOVERY_NUDGE = "scene:discovery_nudge"
    const val GROUP_NEARBY = "scene:nearby"

    fun forEventType(type: String): String? = when (type) {
        SceneEventTypes.INTERACTION_GAP -> GROUP_INTERACTION_GAP
        ExternalEventTypeNames.WEATHER_CHANGE -> GROUP_WEATHER_CHANGE
        ExternalEventTypeNames.WEATHER_ALERT -> GROUP_WEATHER_ALERT_HIGH
        ExternalEventTypeNames.DAILY_WEATHER_BRIEF -> GROUP_WEATHER_DAILY
        ExternalEventTypeNames.WAKE_WEATHER_BRIEF -> GROUP_WEATHER_WAKE
        ExternalEventTypeNames.DISCOVERY_TOPIC_NUDGE -> GROUP_DISCOVERY_NUDGE
        ExternalEventTypeNames.NEARBY_CANDIDATE -> GROUP_NEARBY
        else -> null
    }
}

/**
 * CANDIDATE product thresholds. Values are frozen in one place and are
 * explicitly marked to-verify in docs/implementation/PHASE_7_GATE.md.
 * Debug builds may override them through SceneThresholdProvider only.
 */
@Serializable
data class SceneCandidateThresholds(
    val interactionGapRestCandidateMillis: Long = 24L * 60L * 60L * 1000L,
    val interactionGapEventBucketMillis: Long = 12L * 60L * 60L * 1000L,
    val interactionGapEventTtlMillis: Long = 60L * 60L * 1000L,
    val sceneCooldownMillis: Long = 6L * 60L * 60L * 1000L,
    val weatherChangeSignificantPrecipitationMm: Double = 1.0,
    val weatherChangeSignificantTemperatureDeltaCelsius: Double = 5.0,
    val weatherChangeSignificantConditions: Set<String> = setOf(
        "rain",
        "snow",
        "sleet",
        "storm",
        "thunderstorm",
        "hail",
        "typhoon",
    ),
    /** High weather warnings remain context facts; they are not mandatory outreach. */
    val weatherHighAlertSeverity: String = "HIGH",
    val nearbyCandidateMaxDistanceMeters: Double = 1_500.0,
    /**
     * Daily cap of companion-initiated proactive deliveries (product-day in
     * Asia/Shanghai). Wake weather + discovery nudges count; user chat never
     * does.
     */
    val dailyProactiveOutreachBudget: Int = 3,
)

interface SceneThresholdProvider {
    fun current(): SceneCandidateThresholds
}

/**
 * Release/debug-shared immutable provider. Debug builds override this Koin
 * binding with a mutable provider from app/src/debug; release contains only
 * the frozen defaults.
 */
class FrozenSceneThresholdProvider(
    private val thresholds: SceneCandidateThresholds = SceneCandidateThresholds(),
) : SceneThresholdProvider {
    override fun current(): SceneCandidateThresholds = thresholds
}
