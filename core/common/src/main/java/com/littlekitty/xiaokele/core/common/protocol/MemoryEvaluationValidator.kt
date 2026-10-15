package com.littlekitty.xiaokele.core.common.protocol

import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.time.InstantJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import java.time.Instant

data class MemoryEvaluationViolation(
    val path: String,
    val message: String,
)

sealed interface MemoryEvaluationValidation {
    data class Valid(
        val evaluation: MemoryEvaluation,
        val warnings: List<String>,
    ) : MemoryEvaluationValidation

    data class Invalid(
        val violations: List<MemoryEvaluationViolation>,
    ) : MemoryEvaluationValidation
}

/**
 * Deterministic validation for asynchronous memory learning (C4, FR-MEM-01/02/09).
 *
 * The validator rejects unknown enums, out-of-range importance/confidence/amount,
 * malformed evidence or memory references, and any behavior patch that targets the
 * immutable Character Core. It never contacts a model or mutates local data.
 */
object MemoryEvaluationValidator {

    const val MAX_MEMORY_CONTENT_LENGTH = 2_000
    const val MAX_SHORT_TEXT_LENGTH = 500
    const val MAX_KEY_LENGTH = 120

    fun validate(
        rawEvaluationJson: String,
        allowedBehaviorKeys: Set<String> = BehaviorTendencyKeyNames.all,
    ): MemoryEvaluationValidation {
        val evaluation = try {
            JsonCodec.decode<MemoryEvaluation>(rawEvaluationJson)
        } catch (_: SerializationException) {
            return MemoryEvaluationValidation.Invalid(
                listOf(MemoryEvaluationViolation("$", "MemoryEvaluation JSON 无法解析")),
            )
        } catch (_: IllegalArgumentException) {
            return MemoryEvaluationValidation.Invalid(
                listOf(MemoryEvaluationViolation("$", "MemoryEvaluation JSON 无法解析")),
            )
        }

        val violations = mutableListOf<MemoryEvaluationViolation>()
        val warnings = mutableListOf<String>()

        evaluation.memoryCandidates.forEachIndexed { index, candidate ->
            val path = "memory_candidates[$index]"
            if (candidate.type !in MemoryTypeNames.all) {
                violations += MemoryEvaluationViolation("$path.type", "未知记忆类型: ${candidate.type}")
            }
            if (candidate.content.isBlank()) {
                violations += MemoryEvaluationViolation("$path.content", "记忆内容不能为空")
            } else if (candidate.content.length > MAX_MEMORY_CONTENT_LENGTH) {
                violations += MemoryEvaluationViolation(
                    "$path.content",
                    "记忆内容超过 $MAX_MEMORY_CONTENT_LENGTH 字符",
                )
            }
            if (candidate.subject != null && candidate.subject.length > MAX_SHORT_TEXT_LENGTH) {
                violations += MemoryEvaluationViolation("$path.subject", "subject 过长")
            }
            if (candidate.summary != null && candidate.summary.length > MAX_SHORT_TEXT_LENGTH) {
                violations += MemoryEvaluationViolation("$path.summary", "summary 过长")
            }
            if (candidate.emotionAtEvent != null && candidate.emotionAtEvent.toString().length > MAX_SHORT_TEXT_LENGTH) {
                violations += MemoryEvaluationViolation("$path.emotion_at_event", "emotion_at_event 过长")
            }
            if (candidate.currentAppraisal != null && candidate.currentAppraisal.toString().length > MAX_SHORT_TEXT_LENGTH) {
                violations += MemoryEvaluationViolation("$path.current_appraisal", "current_appraisal 过长")
            }
            validateUnitRange(candidate.importance, "$path.importance", violations)
            validateUnitRange(candidate.confidence, "$path.confidence", violations)
            if (candidate.validity !in MemoryValidityNames.all) {
                violations += MemoryEvaluationViolation("$path.validity", "未知有效期类型: ${candidate.validity}")
            }
            if (candidate.type == MemoryTypeNames.TEMPORARY_STATE &&
                candidate.validity != MemoryValidityNames.TEMPORARY
            ) {
                violations += MemoryEvaluationViolation(
                    "$path.validity",
                    "TEMPORARY_STATE 必须使用 validity=TEMPORARY",
                )
            }
            candidate.evidenceMessageIds.forEachIndexed { evidenceIndex, id ->
                validateEntityId(id, "msg", "$path.evidence_message_ids[$evidenceIndex]", violations)
            }
            candidate.sourceMessageId?.let { validateEntityId(it, "msg", "$path.source_message_id", violations) }
            candidate.supersedesMemoryId?.let {
                validateEntityId(it, "mem", "$path.supersedes_memory_id", violations)
            }
            candidate.validUntil?.let { raw ->
                val parsed = InstantJson.parseOrNull(raw)
                if (parsed == null) {
                    violations += MemoryEvaluationViolation("$path.valid_until", "valid_until 不是合法 ISO-8601 时间")
                } else if (!parsed.isAfter(Instant.EPOCH)) {
                    violations += MemoryEvaluationViolation("$path.valid_until", "valid_until 必须晚于 epoch")
                }
            }
        }

        evaluation.userTraitPatches.forEachIndexed { index, patch ->
            val path = "user_trait_patches[$index]"
            validateKey(patch.key, path, violations)
            if (ImmutableCharacterCoreNames.isProtectedBehaviorKey(patch.key)) {
                violations += MemoryEvaluationViolation(path, "user_trait_patches 不得修改不可变 Character Core")
            }
            if (patch.operation !in UserTraitPatchOperationNames.all) {
                violations += MemoryEvaluationViolation("$path.operation", "未知 user trait 操作: ${patch.operation}")
            }
            if (patch.operation == UserTraitPatchOperationNames.UPSERT && patch.value == null) {
                violations += MemoryEvaluationViolation("$path.value", "UPSERT 必须提供 value")
            }
            if (patch.operation == UserTraitPatchOperationNames.DEACTIVATE && patch.value != null) {
                warnings += "$path: DEACTIVATE 的 value 将被忽略"
            }
            if (patch.profileLayer !in UserProfileLayerNames.all) {
                violations += MemoryEvaluationViolation(
                    "$path.profile_layer",
                    "未知用户画像层级: ${patch.profileLayer}",
                )
            }
            validateUnitRange(patch.confidence, "$path.confidence", violations)
            patch.evidenceMemoryIds.forEachIndexed { evidenceIndex, id ->
                validateMemoryEvidenceId(id, "$path.evidence_memory_ids[$evidenceIndex]", violations)
            }
        }

        evaluation.behaviorPatches.forEachIndexed { index, patch ->
            val path = "behavior_patches[$index]"
            validateKey(patch.key, path, violations)
            if (ImmutableCharacterCoreNames.isProtectedBehaviorKey(patch.key)) {
                violations += MemoryEvaluationViolation(path, "行为补丁不得修改不可变 Character Core")
            } else if (patch.key !in allowedBehaviorKeys) {
                violations += MemoryEvaluationViolation(path, "行为倾向 key 不在白名单: ${patch.key}")
            }
            if (patch.operation !in BehaviorPatchOperationNames.all) {
                violations += MemoryEvaluationViolation("$path.operation", "未知行为补丁操作: ${patch.operation}")
            }
            validateFinite(patch.amount, "$path.amount", violations)
            when (patch.operation) {
                BehaviorPatchOperationNames.INCREASE,
                BehaviorPatchOperationNames.DECREASE,
                -> {
                    if (!patch.amount.isNaN() && (patch.amount <= 0.0 || patch.amount > 1.0)) {
                        violations += MemoryEvaluationViolation(
                            "$path.amount",
                            "INCREASE/DECREASE 的 amount 必须在 (0, 1]",
                        )
                    }
                }

                BehaviorPatchOperationNames.SET -> {
                    if (!patch.amount.isNaN() && (patch.amount < 0.0 || patch.amount > 1.0)) {
                        violations += MemoryEvaluationViolation(
                            "$path.amount",
                            "SET 的 amount 必须在 [0, 1]",
                        )
                    }
                }
            }
            patch.evidenceMemoryIds.forEachIndexed { evidenceIndex, id ->
                validateMemoryEvidenceId(id, "$path.evidence_memory_ids[$evidenceIndex]", violations)
            }
        }

        evaluation.deactivateMemoryIds.forEachIndexed { index, id ->
            validateEntityId(id, "mem", "deactivate_memory_ids[$index]", violations)
        }

        evaluation.patternObservations.forEachIndexed { index, observation ->
            val path = "pattern_observations[$index]"
            validateKey(observation.key, path, violations)
            if (observation.observation.isBlank()) {
                violations += MemoryEvaluationViolation("$path.observation", "模式观察不能为空")
            } else if (observation.observation.length > MAX_SHORT_TEXT_LENGTH) {
                violations += MemoryEvaluationViolation("$path.observation", "模式观察过长")
            }
            validateUnitRange(observation.confidence, "$path.confidence", violations)
            observation.evidenceMessageIds.forEachIndexed { evidenceIndex, id ->
                validateEntityId(id, "msg", "$path.evidence_message_ids[$evidenceIndex]", violations)
            }
        }

        // A response that names none of the five contract fields is not an empty
        // evaluation, it is a different schema. Deserialization ignores unknown
        // keys, so without this check such a response parsed as "no candidates"
        // and was reported as a successful analysis that persisted nothing — a
        // live MiniMax-M3 reply using an `operations` shape was logged as
        // MEMORY_APPLICATION SUCCESS with zero rows written. Fail loudly instead
        // so the caller can repair the format.
        if (!namesAtLeastOneContractField(rawEvaluationJson)) {
            return MemoryEvaluationValidation.Invalid(
                listOf(
                    MemoryEvaluationViolation(
                        "$",
                        "MemoryEvaluation 缺少全部契约字段（memory_candidates/user_trait_patches/behavior_patches/deactivate_memory_ids/pattern_observations）",
                    ),
                ),
            )
        }

        if (violations.isNotEmpty()) {
            return MemoryEvaluationValidation.Invalid(violations)
        }
        return MemoryEvaluationValidation.Valid(evaluation, warnings)
    }

    /**
     * True when the payload speaks the MemoryEvaluation contract at all. An
     * explicitly empty `[]`/`{}`/null for one of the fields counts: that is a
     * deliberate "nothing to record".
     */
    private fun namesAtLeastOneContractField(rawEvaluationJson: String): Boolean = runCatching {
        val root = JsonCodec.json.parseToJsonElement(rawEvaluationJson) as? JsonObject ?: return false
        CONTRACT_FIELDS.any(root::containsKey)
    }.getOrDefault(false)

    private val CONTRACT_FIELDS = listOf(
        "memory_candidates",
        "user_trait_patches",
        "behavior_patches",
        "deactivate_memory_ids",
        "pattern_observations",
    )

    private fun validateUnitRange(
        value: Double,
        path: String,
        violations: MutableList<MemoryEvaluationViolation>,
    ) {
        validateFinite(value, path, violations)
        if (!value.isNaN() && (value < 0.0 || value > 1.0)) {
            violations += MemoryEvaluationViolation(path, "数值必须在 [0, 1]")
        }
    }

    private fun validateFinite(
        value: Double,
        path: String,
        violations: MutableList<MemoryEvaluationViolation>,
    ) {
        if (!value.isFinite()) {
            violations += MemoryEvaluationViolation(path, "数值必须是有限小数")
        }
    }

    private fun validateKey(
        key: String,
        path: String,
        violations: MutableList<MemoryEvaluationViolation>,
    ) {
        if (key.isBlank()) {
            violations += MemoryEvaluationViolation(path, "key 不能为空")
        } else if (key.length > MAX_KEY_LENGTH) {
            violations += MemoryEvaluationViolation(path, "key 超过 $MAX_KEY_LENGTH 字符")
        }
    }

    private fun validateEntityId(
        id: String,
        expectedPrefix: String,
        path: String,
        violations: MutableList<MemoryEvaluationViolation>,
    ) {
        if (!EntityId.isValid(id) || !id.startsWith("${expectedPrefix}_")) {
            violations += MemoryEvaluationViolation(path, "ID 必须符合 ${expectedPrefix}_<32位hex> 格式")
        }
    }

    private fun validateMemoryEvidenceId(
        id: String,
        path: String,
        violations: MutableList<MemoryEvaluationViolation>,
    ) {
        val valid = EntityId.isValid(id) && (id.startsWith("mem_") || id.startsWith("msg_"))
        if (!valid) {
            violations += MemoryEvaluationViolation(path, "证据 ID 必须是 msg_* 或 mem_* 实体 ID")
        }
    }
}
