package com.littlekitty.xiaokele.core.common.protocol

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEvaluationValidatorTest {

    private val messageId = "msg_00000000000000000000000000000001"
    private val memoryId = "mem_00000000000000000000000000000001"

    @Test
    fun validCorrectionEvaluationIsAccepted() {
        val evaluation = MemoryEvaluation(
            memoryCandidates = listOf(
                MemoryCandidate(
                    type = MemoryTypeNames.USER_PREFERENCE,
                    content = "测试用户不喜欢被反复催促",
                    importance = 0.8,
                    confidence = 0.9,
                    validity = MemoryValidityNames.LONG_TERM,
                    evidenceMessageIds = listOf(messageId),
                ),
            ),
            behaviorPatches = listOf(
                BehaviorPatch(
                    key = BehaviorTendencyKeyNames.PRE_SHIFT_REMINDER_INTENSITY,
                    operation = BehaviorPatchOperationNames.DECREASE,
                    amount = 0.2,
                    keepAsLegacy = true,
                    evidenceMemoryIds = listOf(memoryId),
                ),
            ),
        )

        val result = MemoryEvaluationValidator.validate(JsonCodec.encode(evaluation))

        assertTrue(result is MemoryEvaluationValidation.Valid)
        val valid = result as MemoryEvaluationValidation.Valid
        assertEquals(1, valid.evaluation.memoryCandidates.size)
        assertEquals(1, valid.evaluation.behaviorPatches.size)
    }

    @Test
    fun invalidJsonIsRejected() {
        val result = MemoryEvaluationValidator.validate("{not-valid")

        assertTrue(result is MemoryEvaluationValidation.Invalid)
    }

    @Test
    fun outOfRangeConfidenceAndImportanceAreRejected() {
        val evaluation = MemoryEvaluation(
            memoryCandidates = listOf(
                MemoryCandidate(
                    type = MemoryTypeNames.USER_PREFERENCE,
                    content = "bad",
                    importance = 1.2,
                    confidence = -0.1,
                    validity = MemoryValidityNames.LONG_TERM,
                ),
            ),
        )

        val result = MemoryEvaluationValidator.validate(JsonCodec.encode(evaluation)) as MemoryEvaluationValidation.Invalid

        assertEquals(2, result.violations.size)
        assertTrue(result.violations.any { it.path.contains("importance") })
        assertTrue(result.violations.any { it.path.contains("confidence") })
    }

    @Test
    fun unknownEnumsAndInvalidEvidenceAreRejected() {
        val evaluation = MemoryEvaluation(
            memoryCandidates = listOf(
                MemoryCandidate(
                    type = "FUTURE_UNKNOWN",
                    content = "content",
                    importance = 0.5,
                    confidence = 0.5,
                    validity = "FOREVER",
                    evidenceMessageIds = listOf("not-an-id"),
                ),
            ),
        )

        val result = MemoryEvaluationValidator.validate(JsonCodec.encode(evaluation)) as MemoryEvaluationValidation.Invalid

        assertTrue(result.violations.any { it.path.endsWith(".type") })
        assertTrue(result.violations.any { it.path.endsWith(".validity") })
        assertTrue(result.violations.any { it.path.contains("evidence_message_ids") })
    }

    @Test
    fun temporaryStateMustUseTemporaryValidity() {
        val evaluation = MemoryEvaluation(
            memoryCandidates = listOf(
                MemoryCandidate(
                    type = MemoryTypeNames.TEMPORARY_STATE,
                    content = "temporary",
                    importance = 0.5,
                    confidence = 0.5,
                    validity = MemoryValidityNames.LONG_TERM,
                ),
            ),
        )

        val result = MemoryEvaluationValidator.validate(JsonCodec.encode(evaluation)) as MemoryEvaluationValidation.Invalid

        assertTrue(result.violations.any { it.message.contains("TEMPORARY") })
    }

    @Test
    fun behaviorPatchTargetingImmutableCoreIsRejected() {
        val evaluation = MemoryEvaluation(
            behaviorPatches = listOf(
                BehaviorPatch(
                    key = "character_core",
                    operation = BehaviorPatchOperationNames.SET,
                    amount = 1.0,
                ),
            ),
        )

        val result = MemoryEvaluationValidator.validate(JsonCodec.encode(evaluation)) as MemoryEvaluationValidation.Invalid

        assertTrue(result.violations.any { it.message.contains("不可变 Character Core") })
    }

    @Test
    fun unknownBehaviorKeyAndInvalidOperationAreRejected() {
        val evaluation = MemoryEvaluation(
            behaviorPatches = listOf(
                BehaviorPatch(
                    key = "not_allowed_key",
                    operation = "DOUBLE",
                    amount = 1.0,
                ),
            ),
        )

        val result = MemoryEvaluationValidator.validate(JsonCodec.encode(evaluation)) as MemoryEvaluationValidation.Invalid

        assertEquals(2, result.violations.size)
    }

    @Test
    fun userTraitUpsertRequiresValue() {
        val evaluation = MemoryEvaluation(
            userTraitPatches = listOf(
                UserTraitPatch(
                    key = "food_preference",
                    operation = UserTraitPatchOperationNames.UPSERT,
                    confidence = 0.8,
                ),
            ),
        )

        val result = MemoryEvaluationValidator.validate(JsonCodec.encode(evaluation)) as MemoryEvaluationValidation.Invalid

        assertTrue(result.violations.any { it.path.endsWith(".value") })
    }

    @Test
    fun userTraitDeactivateIsAcceptedWithoutValue() {
        val evaluation = MemoryEvaluation(
            userTraitPatches = listOf(
                UserTraitPatch(
                    key = "food_preference",
                    operation = UserTraitPatchOperationNames.DEACTIVATE,
                    confidence = 0.8,
                ),
            ),
        )

        val result = MemoryEvaluationValidator.validate(JsonCodec.encode(evaluation))

        assertTrue(result is MemoryEvaluationValidation.Valid)
    }

    @Test
    fun invalidSupersedesIdIsRejected() {
        val evaluation = MemoryEvaluation(
            memoryCandidates = listOf(
                MemoryCandidate(
                    type = MemoryTypeNames.USER_PREFERENCE,
                    content = "content",
                    importance = 0.5,
                    confidence = 0.5,
                    validity = MemoryValidityNames.LONG_TERM,
                    supersedesMemoryId = "bad",
                ),
            ),
        )

        val result = MemoryEvaluationValidator.validate(JsonCodec.encode(evaluation)) as MemoryEvaluationValidation.Invalid

        assertTrue(result.violations.any { it.path.endsWith(".supersedes_memory_id") })
    }

    @Test
    fun validTraitPatchValueRoundTripsAsJsonObject() {
        val evaluation = MemoryEvaluation(
            userTraitPatches = listOf(
                UserTraitPatch(
                    key = "food_preference",
                    value = buildJsonObject { put("likes", "清淡") },
                    confidence = 0.7,
                ),
            ),
        )

        val result = MemoryEvaluationValidator.validate(JsonCodec.encode(evaluation)) as MemoryEvaluationValidation.Valid

        assertEquals("{\"likes\":\"清淡\"}", result.evaluation.userTraitPatches.single().value.toString())
    }
}
