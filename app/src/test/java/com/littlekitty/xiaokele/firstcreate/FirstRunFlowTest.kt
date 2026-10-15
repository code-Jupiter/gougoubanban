package com.littlekitty.xiaokele.firstcreate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The new first-run order and its gate.
 *
 * The product promise being protected here: a brand-new install cannot reach
 * chat until at least one provider has a *verified* connection, and the user is
 * never asked to write rules — only to describe what they want.
 */
class FirstRunFlowTest {

    private fun draft(stage: FirstCreateStage) = FirstCreateDraft(stage = stage)

    @Test
    fun theFlowStartsAtTheConnectionGate() {
        assertEquals(FirstCreateStage.CONNECTION_GATE, FirstCreateDraft().stage)
    }

    @Test
    fun connectionGateIsStepZeroAndNeverCountsAsUserInput() {
        assertEquals(0, FirstCreateStage.CONNECTION_GATE.stepNumber)
        assertEquals(1, FirstCreateStage.PET_NAME.stepNumber)
        assertEquals(6, FirstCreateStage.CONFIRM.stepNumber)
    }

    @Test
    fun stagesAdvanceInTheDocumentedOrder() {
        assertEquals(FirstCreateStage.PET_NAME, FirstCreateStage.CONNECTION_GATE.next)
        assertEquals(FirstCreateStage.USER_PROFILE, FirstCreateStage.PET_NAME.next)
        assertEquals(FirstCreateStage.PERSONALITY_BRIEF, FirstCreateStage.USER_PROFILE.next)
        assertEquals(FirstCreateStage.MODEL_CHOICE, FirstCreateStage.PERSONALITY_BRIEF.next)
        assertEquals(FirstCreateStage.PERSONA_GENERATING, FirstCreateStage.MODEL_CHOICE.next)
        assertEquals(FirstCreateStage.PERSONA_EDIT, FirstCreateStage.PERSONA_GENERATING.next)
        assertEquals(FirstCreateStage.CONFIRM, FirstCreateStage.PERSONA_EDIT.next)
        assertEquals(FirstCreateStage.COMPLETED, FirstCreateStage.CONFIRM.next)
    }

    @Test
    fun goingBackFromThePersonaEditorReturnsToTheModelChooser() {
        // Re-generation must be reachable without re-entering the brief.
        assertEquals(FirstCreateStage.MODEL_CHOICE, FirstCreateStage.PERSONA_EDIT.previous)
        assertEquals(FirstCreateStage.MODEL_CHOICE, FirstCreateStage.PERSONA_GENERATING.previous)
    }

    @Test
    fun briefRequiresBothDescriptionsButNotTheOptionalOnes() {
        val missingPersonality = draft(FirstCreateStage.PERSONALITY_BRIEF)
            .copy(desiredPersonality = "", desiredTowardUser = "把我当朋友")
        assertTrue(
            FirstCreateRules.validateStage(missingPersonality) is FirstCreateValidation.Invalid,
        )

        val missingToward = draft(FirstCreateStage.PERSONALITY_BRIEF)
            .copy(desiredPersonality = "安静", desiredTowardUser = "")
        assertTrue(
            FirstCreateRules.validateStage(missingToward) is FirstCreateValidation.Invalid,
        )

        // Style and "avoid" are optional: a newcomer must not be blocked by them.
        val minimal = draft(FirstCreateStage.PERSONALITY_BRIEF)
            .copy(desiredPersonality = "安静", desiredTowardUser = "把我当朋友")
        assertEquals(FirstCreateValidation.Valid, FirstCreateRules.validateStage(minimal))
    }

    @Test
    fun briefRejectsOverlongText() {
        val tooLong = draft(FirstCreateStage.PERSONALITY_BRIEF).copy(
            desiredPersonality = "x".repeat(FirstCreateRules.MAX_BRIEF_CHARS + 1),
            desiredTowardUser = "把我当朋友",
        )
        assertTrue(FirstCreateRules.validateStage(tooLong) is FirstCreateValidation.Invalid)
    }

    @Test
    fun modelChoiceMustNameAProviderAndAModel() {
        val noProvider = draft(FirstCreateStage.MODEL_CHOICE)
            .copy(personaGeneratorProvider = null, personaGeneratorModel = "MiniMax-M3")
        assertTrue(FirstCreateRules.validateStage(noProvider) is FirstCreateValidation.Invalid)

        val noModel = draft(FirstCreateStage.MODEL_CHOICE)
            .copy(
                personaGeneratorProvider = SelectedGenerationProvider.MINIMAX_OFFICIAL,
                personaGeneratorModel = "",
            )
        assertTrue(FirstCreateRules.validateStage(noModel) is FirstCreateValidation.Invalid)

        val chosen = draft(FirstCreateStage.MODEL_CHOICE).copy(
            personaGeneratorProvider = SelectedGenerationProvider.BAILIAN,
            personaGeneratorModel = "deepseek-v4-flash",
        )
        assertEquals(FirstCreateValidation.Valid, FirstCreateRules.validateStage(chosen))
    }
}

/**
 * Connection readiness: having typed a key is not the same as being usable.
 */
class ConnectionReadinessTest {

    private fun connection(provider: SelectedGenerationProvider, hasKey: Boolean, validated: Boolean) =
        ProviderConnection(
            provider = provider,
            label = provider.name,
            hasKey = hasKey,
            validated = validated,
        )

    @Test
    fun aKeyWithoutValidationIsNotReady() {
        val snapshot = ConnectionSnapshot(
            listOf(
                connection(SelectedGenerationProvider.MINIMAX_OFFICIAL, hasKey = true, validated = false),
                connection(SelectedGenerationProvider.BAILIAN, hasKey = false, validated = false),
            ),
        )
        assertFalse(snapshot.hasUsableProvider)
        assertTrue(snapshot.readyProviders.isEmpty())
    }

    @Test
    fun oneValidatedProviderIsEnoughToStart() {
        val snapshot = ConnectionSnapshot(
            listOf(
                connection(SelectedGenerationProvider.MINIMAX_OFFICIAL, hasKey = true, validated = false),
                connection(SelectedGenerationProvider.BAILIAN, hasKey = true, validated = true),
            ),
        )
        assertTrue(snapshot.hasUsableProvider)
        assertEquals(listOf(SelectedGenerationProvider.BAILIAN), snapshot.readyProviders)
    }

    @Test
    fun bothProvidersCanBeReadyAtOnce() {
        // Multi-provider configuration is supported: nothing here forces a
        // single choice, so per-Agent provider mixing stays possible.
        val snapshot = ConnectionSnapshot(
            listOf(
                connection(SelectedGenerationProvider.MINIMAX_OFFICIAL, hasKey = true, validated = true),
                connection(SelectedGenerationProvider.BAILIAN, hasKey = true, validated = true),
            ),
        )
        assertEquals(2, snapshot.readyProviders.size)
    }
}
