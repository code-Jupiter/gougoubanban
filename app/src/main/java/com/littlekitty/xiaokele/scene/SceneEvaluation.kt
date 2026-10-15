package com.littlekitty.xiaokele.scene

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class SceneDecision {
    ALLOW,
    NO_ACTION,
}

data class SceneEvaluation(
    val decision: SceneDecision,
    val reasonCode: String,
    val sceneGroup: String? = null,
    val summary: String? = null,
    val derivedFields: Map<String, JsonElement> = emptyMap(),
) {
    fun toContextJson(): JsonObject = buildJsonObject {
        put("decision", decision.name)
        put("reason_code", reasonCode)
        sceneGroup?.let { put("scene_group", it) }
        summary?.let { put("summary", it) }
        if (derivedFields.isNotEmpty()) {
            put(
                "derived",
                buildJsonObject { derivedFields.forEach { (key, value) -> put(key, value) } },
            )
        }
    }

    companion object {
        fun allow(
            reasonCode: String,
            sceneGroup: String? = null,
            summary: String? = null,
            derivedFields: Map<String, JsonElement> = emptyMap(),
        ): SceneEvaluation = SceneEvaluation(
            decision = SceneDecision.ALLOW,
            reasonCode = reasonCode,
            sceneGroup = sceneGroup,
            summary = summary,
            derivedFields = derivedFields,
        )

        fun noAction(
            reasonCode: String,
            sceneGroup: String? = null,
            summary: String? = null,
            derivedFields: Map<String, JsonElement> = emptyMap(),
        ): SceneEvaluation = SceneEvaluation(
            decision = SceneDecision.NO_ACTION,
            reasonCode = reasonCode,
            sceneGroup = sceneGroup,
            summary = summary,
            derivedFields = derivedFields,
        )
    }
}

fun JsonElement.asTextOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

sealed interface SceneGateResult {
    data class Allow(
        val evaluation: SceneEvaluation,
    ) : SceneGateResult

    data class ForceNoAction(
        val evaluation: SceneEvaluation,
    ) : SceneGateResult
}
