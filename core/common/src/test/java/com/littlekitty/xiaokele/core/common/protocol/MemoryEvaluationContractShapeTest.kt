package com.littlekitty.xiaokele.core.common.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The evaluator's contract is enforced by a deterministic validator, but
 * deserialization ignores unknown keys, so a response written in a *different*
 * schema used to parse as "no candidates" and be reported as a successful
 * analysis that persisted nothing.
 *
 * Observed live: MiniMax-M3 answered a memory-evaluation request with
 * `{"memoryEvaluation":{"operations":[{"operation":"new",...}]}}` — none of the
 * five contract fields — and the run was logged MEMORY_APPLICATION SUCCESS with
 * zero rows written. The user's explicit "please remember …" was lost silently.
 */
class MemoryEvaluationContractShapeTest {

    private val shapedLikeAnotherContract = """
        {
          "memoryEvaluation": {
            "eventId": "session:conv_session_example",
            "evaluatedAt": "2026-01-01T00:00:00.000Z",
            "operations": [
              {
                "operation": "new",
                "evidenceId": "msg_00000000000000000000000000000001",
                "subject": "user",
                "validity": "short-term",
                "contentType": "user-statement",
                "content": "User reports a hard day at work.",
                "scope": "current work context",
                "status": "proposed"
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun aResponseInAnotherSchemaIsRejectedInsteadOfSilentlyEmpty() {
        val validation = MemoryEvaluationValidator.validate(shapedLikeAnotherContract)

        assertTrue(
            "a foreign schema must not validate as an empty evaluation",
            validation is MemoryEvaluationValidation.Invalid,
        )
        val violations = (validation as MemoryEvaluationValidation.Invalid).violations
        assertTrue(
            "the violation must name the missing contract fields: $violations",
            violations.any { it.path == "$" && it.message.contains("memory_candidates") },
        )
    }

    @Test
    fun anExplicitlyEmptyEvaluationIsStillValid() {
        // "Nothing worth remembering" is a legitimate answer, and it must be
        // expressed in the contract rather than by omission.
        val explicit = """{"memory_candidates":[],"deactivate_memory_ids":[]}"""

        val validation = MemoryEvaluationValidator.validate(explicit)

        assertTrue(validation is MemoryEvaluationValidation.Valid)
        assertEquals(
            0,
            (validation as MemoryEvaluationValidation.Valid).evaluation.memoryCandidates.size,
        )
    }

    @Test
    fun aConformingCandidateStillValidates() {
        val candidate = """
            {"memory_candidates":[{"type":"USER_PREFERENCE","subject":"USER",
             "content":"User's favourite drink is jasmine tea.","importance":0.6,"confidence":0.8,
             "validity":"LONG_TERM","evidence_message_ids":["msg_00000000000000000000000000000001"],
             "source_message_id":"msg_00000000000000000000000000000001"}]}
        """.trimIndent()

        val validation = MemoryEvaluationValidator.validate(candidate)

        assertTrue(
            "a conforming candidate must validate: $validation",
            validation is MemoryEvaluationValidation.Valid,
        )
        assertEquals(
            1,
            (validation as MemoryEvaluationValidation.Valid).evaluation.memoryCandidates.size,
        )
    }
}
