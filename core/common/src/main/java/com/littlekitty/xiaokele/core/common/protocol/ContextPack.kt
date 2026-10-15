package com.littlekitty.xiaokele.core.common.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Phase 2 minimal ContextPack, aligned with architecture section 30.
 *
 * The current chat turn and a small recent-conversation window are carried
 * inside [event.payload] without adding new top-level fields to the frozen
 * protocol shape. Memory-search and weather fields stay null/empty until their
 * owning phases (4/6) are implemented.
 *
 * Public-edition note: this pack carries no shift, schedule or fixed-place
 * context. The companion has no shift/geofence concept, so no model call may
 * receive one.
 */
@Serializable
data class ContextPack(
    val event: JsonObject? = null,
    @SerialName("current_context")
    val currentContext: CurrentContext,
    val character: CharacterContext,
    @SerialName("user_profile")
    val userProfile: List<JsonElement> = emptyList(),
    @SerialName("dynamic_user_profile")
    val dynamicUserProfile: List<JsonElement> = emptyList(),
    @SerialName("conversation_summaries")
    val conversationSummaries: List<JsonElement> = emptyList(),
    /** Present only while an oversized high-frequency session is still active. */
    @SerialName("active_conversation_state")
    val activeConversationState: JsonObject? = null,
    @SerialName("retrieved_memories")
    val retrievedMemories: List<JsonElement> = emptyList(),
    @SerialName("relevant_todos")
    val relevantTodos: List<JsonElement> = emptyList(),
    @SerialName("recent_deliveries")
    val recentDeliveries: List<JsonElement> = emptyList(),
    @SerialName("scene_policy")
    val scenePolicy: JsonObject? = null,
    @SerialName("available_actions")
    val availableActions: List<String> = AgentActionNames.allowed.toList(),
    @SerialName("scene_evaluation")
    val sceneEvaluation: JsonObject? = null,
    @SerialName("local_discoveries")
    val localDiscoveries: List<JsonElement> = emptyList(),
    @SerialName("local_discoveries_updated_at")
    val localDiscoveriesUpdatedAt: String? = null,
    /**
     * Small, permissioned creative capability payload. It contains only
     * eligibility and summaries; full works are loaded through local tools.
     */
    @SerialName("creative_context")
    val creativeContext: JsonObject? = null,
)

@Serializable
data class CurrentContext(
    @SerialName("local_time")
    val localTime: String,
    @SerialName("pet_state")
    val petState: String = "IDLE",
    @SerialName("last_interaction_at")
    val lastInteractionAt: String? = null,
    val weather: JsonObject? = null,
    @SerialName("pet_daily_life")
    val petDailyLife: JsonObject? = null,
)

@Serializable
data class CharacterContext(
    @SerialName("immutable_core")
    val immutableCore: JsonObject,
    @SerialName("active_tendencies")
    val activeTendencies: List<JsonElement> = emptyList(),
    @SerialName("relevant_legacy_tendencies")
    val relevantLegacyTendencies: List<JsonElement> = emptyList(),
)
