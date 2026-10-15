package com.littlekitty.xiaokele.creative

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.database.creative.CreativeCandidateTypes
import com.littlekitty.xiaokele.core.database.creative.CreativeWorkTypes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Separate model boundary for background creative evaluation. Its raw result
 * is the strict creative schema, never an AgentDecision, so an evaluation
 * cannot accidentally enter the normal chat/action pipeline.
 */
interface CreativeEvaluationModelService {
    suspend fun generateCreativeEvaluation(
        context: com.littlekitty.xiaokele.core.common.protocol.ContextPack,
    ): com.littlekitty.xiaokele.core.network.model.ModelServiceResult
}

@Serializable
data class CreativeEvaluationModelOutput(
    val outcome: String,
    @SerialName("work_type") val workType: String? = null,
    val title: String? = null,
    val content: String? = null,
    val summary: String? = null,
    @SerialName("dog_emotion_summary") val dogEmotionSummary: String? = null,
    @SerialName("idea_seed") val ideaSeed: String? = null,
    @SerialName("dog_feeling_seed") val dogFeelingSeed: String? = null,
    @SerialName("source_ids") val sourceIds: List<String> = emptyList(),
    @SerialName("risk_level") val riskLevel: String = "LOW",
    val theme: String? = null,
    val scores: CreativeScores = CreativeScores(),
    @SerialName("new_meaning") val newMeaning: String? = null,
)

@Serializable
data class CreativeScores(
    val newness: Double = 0.0,
    @SerialName("emotional_intensity") val emotionalIntensity: Double = 0.0,
    @SerialName("dog_perspective_value") val dogPerspectiveValue: Double = 0.0,
    @SerialName("cross_day_continuity") val crossDayContinuity: Double = 0.0,
    @SerialName("long_term_change") val longTermChange: Double = 0.0,
    @SerialName("creative_potential") val creativePotential: Double = 0.0,
    @SerialName("user_sensitivity_risk") val userSensitivityRisk: Double = 0.0,
    @SerialName("factual_confidence") val factualConfidence: Double = 0.0,
    @SerialName("repeat_risk") val repeatRisk: Double = 0.0,
)

object CreativeEvaluationOutcomes {
    const val NO_ACTION = "NO_ACTION"
    const val HOLD_FOR_WEEKLY = "HOLD_FOR_WEEKLY"
    const val HOLD_FOR_MONTHLY = "HOLD_FOR_MONTHLY"
    const val DAILY_DIARY = "DAILY_DIARY"
    const val WEEKLY_DIARY = "WEEKLY_DIARY"
    const val MONTHLY_DIARY = "MONTHLY_DIARY"
    const val DOG_LIFE_ESSAY = "DOG_LIFE_ESSAY"
    const val FABLE = "FABLE"
    const val MICRO_STORY = "MICRO_STORY"
    const val INSPIRED_SHORT_STORY_CANDIDATE = "INSPIRED_SHORT_STORY_CANDIDATE"

    val all = setOf(
        NO_ACTION,
        HOLD_FOR_WEEKLY,
        HOLD_FOR_MONTHLY,
        DAILY_DIARY,
        WEEKLY_DIARY,
        MONTHLY_DIARY,
        DOG_LIFE_ESSAY,
        FABLE,
        MICRO_STORY,
        INSPIRED_SHORT_STORY_CANDIDATE,
    )
}

object CreativeEvaluationValidator {
    private val workTypes = setOf(
        CreativeWorkTypes.DAILY_DIARY,
        CreativeWorkTypes.WEEKLY_DIARY,
        CreativeWorkTypes.MONTHLY_DIARY,
        CreativeWorkTypes.DOG_LIFE_ESSAY,
        CreativeWorkTypes.FABLE,
        CreativeWorkTypes.MICRO_STORY,
    )

    fun parse(raw: String, allowedSourceIds: Set<String>): CreativeEvaluationModelOutput? {
        val output = runCatching { JsonCodec.decode<CreativeEvaluationModelOutput>(raw) }.getOrNull() ?: return null
        if (output.outcome !in CreativeEvaluationOutcomes.all) return null
        if (output.sourceIds.any { it !in allowedSourceIds } || output.sourceIds.distinct().size != output.sourceIds.size) return null
        val normalizedRisk = output.riskLevel.uppercase()
        if (normalizedRisk !in setOf("LOW", "MEDIUM", "HIGH")) return null
        val normalizedOutput = output.copy(riskLevel = normalizedRisk)
        if (normalizedOutput.scores.anyOutOfRange()) return null
        if (normalizedOutput.outcome == CreativeEvaluationOutcomes.NO_ACTION || normalizedOutput.outcome.startsWith("HOLD_")) return normalizedOutput
        if (normalizedOutput.outcome == CreativeEvaluationOutcomes.INSPIRED_SHORT_STORY_CANDIDATE) {
            return normalizedOutput.takeIf {
                it.sourceIds.isNotEmpty() &&
                    !it.ideaSeed.isNullOrBlank() &&
                    !it.theme.isNullOrBlank()
            }
        }
        val expectedType = normalizedOutput.outcome
        return normalizedOutput.takeIf {
            expectedType in workTypes &&
                it.workType.orEmpty() == expectedType &&
                !it.title.isNullOrBlank() && it.title.length <= 120 &&
                !it.content.isNullOrBlank() && it.content.length <= 12_000 &&
                !it.summary.isNullOrBlank() && it.summary.length <= 500 &&
                !it.dogEmotionSummary.isNullOrBlank() && it.dogEmotionSummary.length <= 500 &&
                it.sourceIds.isNotEmpty() &&
                !it.newMeaning.isNullOrBlank()
        }
    }

    private fun CreativeScores.anyOutOfRange(): Boolean = listOf(
        newness, emotionalIntensity, dogPerspectiveValue, crossDayContinuity,
        longTermChange, creativePotential, userSensitivityRisk, factualConfidence,
        repeatRisk,
    ).any { it !in 0.0..1.0 }
}
