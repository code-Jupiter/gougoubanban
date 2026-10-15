package com.littlekitty.xiaokele.core.common.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Phase 3 MemoryEvaluation wire shape, aligned with architecture section 32.
 *
 * Memory candidates are facts/preferences/relationship events extracted from a
 * completed chat turn. User-trait and behavior patches are only applied after
 * deterministic validation and by local repositories; the immutable character
 * core has no patch field and cannot be targeted here.
 */
@Serializable
data class MemoryEvaluation(
    @SerialName("memory_candidates")
    val memoryCandidates: List<MemoryCandidate> = emptyList(),
    @SerialName("user_trait_patches")
    val userTraitPatches: List<UserTraitPatch> = emptyList(),
    @SerialName("behavior_patches")
    val behaviorPatches: List<BehaviorPatch> = emptyList(),
    @SerialName("deactivate_memory_ids")
    val deactivateMemoryIds: List<String> = emptyList(),
    @SerialName("pattern_observations")
    val patternObservations: List<PatternObservation> = emptyList(),
)

@Serializable
data class PatternObservation(
    val key: String,
    val observation: String,
    val confidence: Double,
    @SerialName("evidence_message_ids")
    val evidenceMessageIds: List<String> = emptyList(),
)

@Serializable
data class MemoryCandidate(
    val type: String,
    val subject: String? = null,
    val content: String,
    val summary: String? = null,
    val importance: Double,
    val confidence: Double,
    val validity: String,
    @SerialName("evidence_message_ids")
    val evidenceMessageIds: List<String> = emptyList(),
    @SerialName("source_message_id")
    val sourceMessageId: String? = null,
    @SerialName("valid_until")
    val validUntil: String? = null,
    @SerialName("supersedes_memory_id")
    val supersedesMemoryId: String? = null,
    @SerialName("emotion_at_event")
    val emotionAtEvent: JsonObject? = null,
    @SerialName("current_appraisal")
    val currentAppraisal: JsonObject? = null,
)

@Serializable
data class UserTraitPatch(
    val key: String,
    val operation: String = UserTraitPatchOperationNames.UPSERT,
    val value: JsonObject? = null,
    val confidence: Double = 0.0,
    @SerialName("profile_layer")
    val profileLayer: String = UserProfileLayerNames.DYNAMIC,
    @SerialName("evidence_memory_ids")
    val evidenceMemoryIds: List<String> = emptyList(),
)

object UserProfileLayerNames {
    const val CORE = "CORE"
    const val DYNAMIC = "DYNAMIC"

    val all = setOf(CORE, DYNAMIC)
}

@Serializable
data class BehaviorPatch(
    val key: String,
    val operation: String,
    val amount: Double,
    @SerialName("keep_as_legacy")
    val keepAsLegacy: Boolean = false,
    @SerialName("evidence_memory_ids")
    val evidenceMemoryIds: List<String> = emptyList(),
)

object MemoryTypeNames {
    const val TEMPORARY_STATE = "TEMPORARY_STATE"
    const val SHORT_TERM_MEMORY = "SHORT_TERM_MEMORY"
    const val USER_PREFERENCE = "USER_PREFERENCE"
    const val RELATIONSHIP_EVENT = "RELATIONSHIP_EVENT"

    val all = setOf(TEMPORARY_STATE, SHORT_TERM_MEMORY, USER_PREFERENCE, RELATIONSHIP_EVENT)

    /** Types that participate in semantic retrieval and therefore embedding. */
    val embeddingEligible = setOf(SHORT_TERM_MEMORY, USER_PREFERENCE, RELATIONSHIP_EVENT)
}

object MemoryValidityNames {
    const val TEMPORARY = "TEMPORARY"
    const val SHORT_TERM = "SHORT_TERM"
    const val LONG_TERM = "LONG_TERM"

    val all = setOf(TEMPORARY, SHORT_TERM, LONG_TERM)
}

object UserTraitPatchOperationNames {
    const val UPSERT = "UPSERT"
    const val DEACTIVATE = "DEACTIVATE"

    val all = setOf(UPSERT, DEACTIVATE)
}

object BehaviorPatchOperationNames {
    const val INCREASE = "INCREASE"
    const val DECREASE = "DECREASE"
    const val SET = "SET"

    val all = setOf(INCREASE, DECREASE, SET)
}

/**
 * Candidate tendency key namespace for Phase 3. Product docs name reminder
 * frequency, proactive degree, clinginess/委屈/quiet expression and
 * recommendation feedback; exact numeric defaults remain local candidate
 * configuration and are not part of the wire protocol.
 */
object BehaviorTendencyKeyNames {
    const val REMINDER_INTENSITY = "reminder_intensity"
    const val PRE_SHIFT_REMINDER_INTENSITY = "pre_shift_reminder_intensity"
    const val PROACTIVE_CONTACT_LEVEL = "proactive_contact_level"
    const val CLINGINESS_LEVEL = "clinginess_level"
    const val WRONGED_EXPRESSION_LEVEL = "wronged_expression_level"
    const val QUIET_EXPRESSION_LEVEL = "quiet_expression_level"
    const val FOOD_RECOMMENDATION_LEVEL = "food_recommendation_level"

    val all = setOf(
        REMINDER_INTENSITY,
        PRE_SHIFT_REMINDER_INTENSITY,
        PROACTIVE_CONTACT_LEVEL,
        CLINGINESS_LEVEL,
        WRONGED_EXPRESSION_LEVEL,
        QUIET_EXPRESSION_LEVEL,
        FOOD_RECOMMENDATION_LEVEL,
    )
}

object ImmutableCharacterCoreNames {
    val protectedBehaviorKeys = setOf(
        "character_core",
        "immutable_core",
        "identity",
        "name",
        "display_name",
        "user_name",
        "relationship",
        "persona_anchor",
    )

    fun isProtectedBehaviorKey(key: String): Boolean {
        val normalized = key.trim().lowercase()
        return normalized in protectedBehaviorKeys ||
            normalized.startsWith("character_core.") ||
            normalized.startsWith("immutable_core.") ||
            normalized.startsWith("identity.")
    }
}
