package com.littlekitty.xiaokele.scene

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.scene.ScenePolicyContract
import com.littlekitty.xiaokele.core.database.policy.PolicyGateResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class SceneOutputViolation(
    val path: String,
    val message: String,
)

/**
 * Phase 7 deterministic output-shape enforcement. The model never chooses
 * whether a scene may use a channel: this object rejects multi-primary-channel
 * plans and mutation types outside the policy constraints before any row is
 * persisted.
 */
object SceneOutputPolicyEnforcer {

    fun validate(
        decision: AgentDecision,
        gate: PolicyGateResult,
    ): List<SceneOutputViolation> {
        val violations = mutableListOf<SceneOutputViolation>()
        val constraints = (gate as? PolicyGateResult.AllowAgent)?.policyJson
            ?.get("constraints") as? JsonObject
            ?: JsonObject(emptyMap())

        val reachActions = decision.outputs.filter {
            it.action in ScenePolicyContract.primaryReachActions
        }
        if (reachActions.size > 1) {
            violations += SceneOutputViolation(
                "outputs",
                "单个场景最多一个主触达渠道，实际=${reachActions.size}",
            )
        }

        val expectedPrimary = string(constraints, ScenePolicyContract.KEY_PRIMARY_CHANNEL)
        reachActions.firstOrNull()?.let { output ->
            val actual = ScenePolicyContract.channelForAction(output.action)
            if (expectedPrimary != null && actual != expectedPrimary) {
                violations += SceneOutputViolation(
                    "outputs",
                    "场景主触达渠道必须为 $expectedPrimary，实际=${actual ?: output.action}",
                )
            }
        }

        val allowedTodoTypes = stringSet(constraints, ScenePolicyContract.KEY_ALLOWED_TODO_TYPES)
        if (allowedTodoTypes != null) {
            decision.todoMutations.forEachIndexed { index, mutation ->
                val type = (mutation.todo["type"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.contentOrNull
                if (type != null && type !in allowedTodoTypes) {
                    violations += SceneOutputViolation(
                        "todo_mutations[$index].todo.type",
                        "场景不允许 Todo type=$type，允许=${allowedTodoTypes.sorted()}",
                    )
                }
            }
        }

        val allowedTriggerTypes = stringSet(constraints, ScenePolicyContract.KEY_ALLOWED_TRIGGER_TYPES)
        if (allowedTriggerTypes != null) {
            decision.triggerMutations.forEachIndexed { index, mutation ->
                val type = (mutation.trigger["trigger_type"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.contentOrNull
                if (type != null && type !in allowedTriggerTypes) {
                    violations += SceneOutputViolation(
                        "trigger_mutations[$index].trigger.trigger_type",
                        "场景不允许 trigger_type=$type，允许=${allowedTriggerTypes.sorted()}",
                    )
                }
            }
        }
        return violations
    }

    private fun string(json: JsonObject, key: String): String? =
        (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun stringSet(json: JsonObject, key: String): Set<String>? {
        val array = json[key] as? JsonArray ?: return null
        return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { prim -> prim.isString }?.content }.toSet()
    }
}
