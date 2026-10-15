package com.littlekitty.xiaokele.core.database.policy

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.ScenarioPolicyEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object PolicyModes {
    const val STRICT = "STRICT"
    const val GUIDED = "GUIDED"
    const val OPEN = "OPEN"

    val all: Set<String> = setOf(STRICT, GUIDED, OPEN)
}

sealed interface PolicyResolution {
    data class Matched(
        val policy: ScenarioPolicyEntity,
        val mode: String,
        val allowedActions: Set<String>,
        val constraints: JsonObject,
    ) : PolicyResolution

    data class OpenFallback(
        val reason: String,
        val allowedActions: Set<String> = AgentActionRegistry.backgroundAllowed,
    ) : PolicyResolution
}

sealed interface PolicyGateResult {
    data class AllowAgent(
        val availableActions: Set<String>,
        val policyJson: JsonObject,
        val maxOutputs: Int? = null,
    ) : PolicyGateResult

    data class ForceNoAction(
        val reason: String,
        val policyJson: JsonObject,
    ) : PolicyGateResult
}

/**
 * Phase 4 Policy Resolver (architecture section 16, FR-EVENT-04/05).
 *
 * Resolution is deterministic: latest policy by event_type wins. Unknown
 * events fall back to OPEN and may enter the background Companion Agent.
 * The gate only enforces what is already persisted in the policy row; it never
 * invents product rules and never calls a model.
 */
class PolicyResolver(private val db: AppDatabase) {

    suspend fun resolve(event: EventEntity): PolicyResolution {
        val policies = db.scenarioPolicyDao().forEventType(event.type)
        if (policies.isEmpty()) {
            return PolicyResolution.OpenFallback(
                reason = "no_scene_policy_for_event_type",
                allowedActions = AgentActionRegistry.backgroundAllowed,
            )
        }
        val matching = policies.filter { ScenePolicyMatcher.matches(event, it.matcherJson) }
        val policy = matching.maxByOrNull { it.version }
        if (policy == null) {
            return PolicyResolution.OpenFallback(
                reason = "no_matching_scene_policy_matcher",
                allowedActions = AgentActionRegistry.backgroundAllowed,
            )
        }
        val allowedActions = parseStringArray(policy.allowedActionsJson)
            .filter { action -> action != "NO_ACTION" }
            .toSet()
        val constraints = parseJsonObject(policy.constraintsJson)
        return PolicyResolution.Matched(
            policy = policy,
            mode = policy.mode,
            allowedActions = allowedActions,
            constraints = constraints,
        )
    }

    fun gate(resolution: PolicyResolution): PolicyGateResult = when (resolution) {
        is PolicyResolution.OpenFallback -> PolicyGateResult.AllowAgent(
            availableActions = resolution.allowedActions,
            policyJson = openPolicyJson(resolution.reason),
        )

        is PolicyResolution.Matched -> {
            val policyJson = matchedPolicyJson(resolution)
            val noActionConstraint = resolution.constraints["no_action"] as? JsonPrimitive
            val forcedNoAction = noActionConstraint?.takeIf { it.isString || it == JsonPrimitive(true) }
                ?.let { element ->
                    element == JsonPrimitive(true) || (element.isString && element.content.equals("true", ignoreCase = true))
                } == true
            if (forcedNoAction) {
                PolicyGateResult.ForceNoAction("scene_policy_no_action_constraint", policyJson)
            } else if (resolution.mode == PolicyModes.STRICT && resolution.allowedActions.isEmpty()) {
                PolicyGateResult.ForceNoAction("strict_policy_has_no_executable_actions", policyJson)
            } else {
                val maxOutputs = (resolution.constraints["max_outputs"] as? JsonPrimitive)
                    ?.let { element ->
                        val parsed = if (element.isString) element.content.toIntOrNull() else element.content.toIntOrNull()
                        parsed?.takeIf { it > 0 }
                    }
                PolicyGateResult.AllowAgent(
                    availableActions = resolution.allowedActions,
                    policyJson = policyJson,
                    maxOutputs = maxOutputs,
                )
            }
        }
    }

    private fun parseStringArray(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            JsonCodec.json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
        }.getOrElse { emptyList() }
    }

    private fun parseJsonObject(raw: String?): JsonObject =
        if (raw.isNullOrBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching { JsonCodec.json.parseToJsonElement(raw) as? JsonObject }
                .getOrNull() ?: JsonObject(emptyMap())
        }

    private fun matchedPolicyJson(resolution: PolicyResolution.Matched): JsonObject = buildJsonObject {
        put("policy_id", resolution.policy.id)
        put("event_type", resolution.policy.eventType)
        put("mode", resolution.mode)
        resolution.policy.promptTemplate?.let { put("prompt_template", it) }
        resolution.policy.examplesJson?.let { raw ->
            runCatching { JsonCodec.json.parseToJsonElement(raw) }.getOrNull()?.let { put("examples", it) }
        }
        put("allowed_actions", buildJsonArray { resolution.allowedActions.forEach { add(JsonPrimitive(it)) } })
        if (resolution.constraints.isNotEmpty()) put("constraints", resolution.constraints)
        put("version", resolution.policy.version)
    }

    private fun openPolicyJson(reason: String): JsonObject = buildJsonObject {
        put("policy_id", null)
        put("event_type", "*")
        put("mode", PolicyModes.OPEN)
        put("open_fallback_reason", reason)
        put(
            "allowed_actions",
            buildJsonArray { AgentActionRegistry.backgroundAllowed.forEach { add(JsonPrimitive(it)) } },
        )
    }
}

/**
 * Minimal development-candidate Scene Policies. These rows exist only so the
 * local Phase 4 closed loop and debug panel have deterministic policy paths.
 * They are explicitly DEV_CANDIDATE and are NOT frozen product configuration.
 */
object DevelopmentCandidatePolicies {

    const val POLICY_ID_TEST_STRICT = "dev_candidate_policy_test_strict_v1"
    const val POLICY_ID_TEST_GUIDED = "dev_candidate_policy_test_guided_v1"
    const val POLICY_ID_TEST_NO_ACTION = "dev_candidate_policy_test_no_action_v1"
    const val POLICY_ID_TRIGGER_FIRE = "dev_candidate_policy_trigger_fire_v1"

    const val EVENT_TYPE_TEST_STRICT = "TEST_STRICT_EVENT"
    const val EVENT_TYPE_TEST_GUIDED = "TEST_GUIDED_EVENT"
    const val EVENT_TYPE_TEST_NO_ACTION = "TEST_NO_ACTION_EVENT"
    const val EVENT_TYPE_TRIGGER_FIRE = "TRIGGER_FIRE"

    suspend fun ensureSeeded(db: AppDatabase) {
        val dao = db.scenarioPolicyDao()
        if (dao.byId(POLICY_ID_TEST_STRICT) == null) {
            dao.upsert(
                ScenarioPolicyEntity(
                    id = POLICY_ID_TEST_STRICT,
                    eventType = EVENT_TYPE_TEST_STRICT,
                    mode = PolicyModes.STRICT,
                    matcherJson = """{"type":"TEST_STRICT_EVENT"}""",
                    promptTemplate = "DEV_CANDIDATE STRICT 测试策略：仅允许更新 Pet State。",
                    examplesJson = "[]",
                    allowedActionsJson = """["UPDATE_PET_STATE"]""",
                    constraintsJson = """{"max_outputs":1}""",
                    version = 1,
                ),
            )
        }
        if (dao.byId(POLICY_ID_TEST_GUIDED) == null) {
            dao.upsert(
                ScenarioPolicyEntity(
                    id = POLICY_ID_TEST_GUIDED,
                    eventType = EVENT_TYPE_TEST_GUIDED,
                    mode = PolicyModes.GUIDED,
                    matcherJson = """{"type":"TEST_GUIDED_EVENT"}""",
                    promptTemplate = "DEV_CANDIDATE GUIDED 测试策略：模型在允许范围内决定。",
                    examplesJson = "[]",
                    allowedActionsJson = """["UPDATE_PET_STATE"]""",
                    constraintsJson = """{"max_outputs":1}""",
                    version = 1,
                ),
            )
        }
        if (dao.byId(POLICY_ID_TEST_NO_ACTION) == null) {
            dao.upsert(
                ScenarioPolicyEntity(
                    id = POLICY_ID_TEST_NO_ACTION,
                    eventType = EVENT_TYPE_TEST_NO_ACTION,
                    mode = PolicyModes.STRICT,
                    matcherJson = """{"type":"TEST_NO_ACTION_EVENT"}""",
                    promptTemplate = "DEV_CANDIDATE：该事件由代码规则静默。",
                    examplesJson = "[]",
                    allowedActionsJson = "[]",
                    constraintsJson = """{"no_action":true}""",
                    version = 1,
                ),
            )
        }
        if (dao.byId(POLICY_ID_TRIGGER_FIRE) == null) {
            dao.upsert(
                ScenarioPolicyEntity(
                    id = POLICY_ID_TRIGGER_FIRE,
                    eventType = EVENT_TYPE_TRIGGER_FIRE,
                    mode = PolicyModes.GUIDED,
                    matcherJson = """{"type":"TRIGGER_FIRE"}""",
                    promptTemplate = "DEV_CANDIDATE Trigger 到期重判策略。",
                    examplesJson = "[]",
                    allowedActionsJson = """["UPDATE_PET_STATE"]""",
                    constraintsJson = """{"max_outputs":1}""",
                    version = 1,
                ),
            )
        }
    }
}
