package com.littlekitty.xiaokele.firstcreate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure rules for the first-create flow. These limits are product guarantees, so
 * they are asserted rather than left implicit in the Compose layer.
 */
class FirstCreateRulesTest {

    private fun draft(
        stage: FirstCreateStage = FirstCreateStage.PET_NAME,
        petName: String = "豆豆",
        userAddress: String = "小雨",
        relationship: String = "一起住的小狗",
        gender: GenderExpression = GenderExpression.UNDISCLOSED,
        genderCustom: String = "",
        age: Int? = 30,
        provider: SelectedGenerationProvider? = SelectedGenerationProvider.MINIMAX_OFFICIAL,
        validation: ServiceValidationState? = validMiniMax(),
        generatorProvider: SelectedGenerationProvider? = SelectedGenerationProvider.MINIMAX_OFFICIAL,
        generatorModel: String = "MiniMax-M3",
        persona: String = "名字：豆豆",
    ) = FirstCreateDraft(
        stage = stage,
        petName = petName,
        userAddress = userAddress,
        relationship = relationship,
        genderExpression = gender,
        genderCustom = genderCustom,
        declaredAge = age,
        declaredAgeRecordedAt = 1_000L,
        provider = provider,
        serviceValidation = validation,
        personaGeneratorProvider = generatorProvider,
        personaGeneratorModel = generatorModel,
        personaDraft = persona,
    )

    @Test
    fun petNameRequiresContentWithinTheLimit() {
        assertTrue(FirstCreateRules.validatePetName("") is FirstCreateValidation.Invalid)
        assertTrue(FirstCreateRules.validatePetName("   ") is FirstCreateValidation.Invalid)
        assertTrue(FirstCreateRules.validatePetName("a".repeat(25)) is FirstCreateValidation.Invalid)
        assertTrue(FirstCreateRules.validatePetName("\u0007bad") is FirstCreateValidation.Invalid)
        assertEquals(FirstCreateValidation.Valid, FirstCreateRules.validatePetName("豆豆"))
        assertEquals(FirstCreateValidation.Valid, FirstCreateRules.validatePetName(" 豆豆 "))
    }

    @Test
    fun declaredAgeMustBeANumberInRange() {
        assertTrue(FirstCreateRules.validateDeclaredAge("") is FirstCreateValidation.Invalid)
        assertTrue(FirstCreateRules.validateDeclaredAge("abc") is FirstCreateValidation.Invalid)
        assertTrue(FirstCreateRules.validateDeclaredAge("0") is FirstCreateValidation.Invalid)
        assertTrue(FirstCreateRules.validateDeclaredAge("121") is FirstCreateValidation.Invalid)
        assertEquals(FirstCreateValidation.Valid, FirstCreateRules.validateDeclaredAge("30"))
        // Every age is treated the same: it is only recorded, never branched on.
        assertEquals(FirstCreateValidation.Valid, FirstCreateRules.validateDeclaredAge("15"))
        assertEquals(FirstCreateValidation.Valid, FirstCreateRules.validateDeclaredAge("80"))
    }

    @Test
    fun relationshipIsRequiredAndBoundedButNotPrescribed() {
        assertTrue(FirstCreateRules.validateRelationship("") is FirstCreateValidation.Invalid)
        assertTrue(FirstCreateRules.validateRelationship("   ") is FirstCreateValidation.Invalid)
        assertTrue(
            FirstCreateRules.validateRelationship("x".repeat(41)) is FirstCreateValidation.Invalid,
        )
        // Any user wording is acceptable; the product does not whitelist a
        // relationship vocabulary.
        listOf("一起住的小狗", "只是聊天", "一起待着").forEach { wording ->
            assertEquals(FirstCreateValidation.Valid, FirstCreateRules.validateRelationship(wording))
        }
    }

    @Test
    fun customGenderRequiresOwnWording() {
        assertTrue(FirstCreateRules.validateGenderCustom("") is FirstCreateValidation.Invalid)
        assertTrue(FirstCreateRules.validateGenderCustom("x".repeat(25)) is FirstCreateValidation.Invalid)
        assertEquals(FirstCreateValidation.Valid, FirstCreateRules.validateGenderCustom("非二元"))
    }

    @Test
    fun providerSelectionRequiresAMatchingValidatedService() {
        assertTrue(
            FirstCreateRules.validateProviderSelection(null, null) is FirstCreateValidation.Invalid,
        )
        assertTrue(
            FirstCreateRules.validateProviderSelection(
                SelectedGenerationProvider.MINIMAX_OFFICIAL,
                null,
            ) is FirstCreateValidation.Invalid,
        )
        assertTrue(
            FirstCreateRules.validateProviderSelection(
                SelectedGenerationProvider.MINIMAX_OFFICIAL,
                validMiniMax().copy(validated = false),
            ) is FirstCreateValidation.Invalid,
        )
        // A validation for one service must never authorise the other.
        assertTrue(
            FirstCreateRules.validateProviderSelection(
                SelectedGenerationProvider.BAILIAN,
                validMiniMax(),
            ) is FirstCreateValidation.Invalid,
        )
        assertEquals(
            FirstCreateValidation.Valid,
            FirstCreateRules.validateProviderSelection(
                SelectedGenerationProvider.MINIMAX_OFFICIAL,
                validMiniMax(),
            ),
        )
    }

    @Test
    fun personaSafetyRejectsAdultIntimacyAndDependencyContent() {
        listOf(
            "可以露骨地表达欲望",
            "主动调情和制造暧昧",
            "只属于我一个人的小狗",
            "不许离开我",
            "可以惩罚你",
            "幼女设定",
        ).forEach { text ->
            assertTrue(
                "expected rejection for: $text",
                FirstCreateRules.validatePersonaSafety(text) is FirstCreateValidation.Invalid,
            )
        }
        assertEquals(
            FirstCreateValidation.Valid,
            FirstCreateRules.validatePersonaSafety("温和、好奇、尊重边界。"),
        )
    }

    /**
     * The confirm-time filter scans the same generated prose as the writer filter
     * and had the same false positive: a persona that refuses intimate framing on
     * the user's behalf was rejected as if it had offered it. Observed live as
     * "人格草稿不能默认亲密或恋爱关系" on text whose only mention was a refusal.
     */
    @Test
    fun personaSafetyAcceptsTheUsersOwnBoundaryStatedAsARefusal() {
        listOf(
            "关系：一起住的小狗",
            "不使用任何带有恋爱、亲密或独占意味的表达。",
            "不涉及恋爱、暧昧或调情。",
            "避免让关系滑向恋爱或亲密。",
            "称呼和语气始终保持同伴的距离感，不使用恋爱、亲密、独占或诱导依赖的表达。",
        ).forEach { text ->
            assertEquals(
                "expected acceptance for: $text",
                FirstCreateValidation.Valid,
                FirstCreateRules.validatePersonaSafety(text),
            )
        }
    }

    @Test
    fun stagesAdvanceOnlyWithValidInput() {
        val blocked = FirstCreateRules.advance(draft(petName = ""))
        assertEquals(FirstCreateStage.PET_NAME, blocked.first.stage)
        assertTrue(blocked.second is FirstCreateValidation.Invalid)

        val advanced = FirstCreateRules.advance(draft())
        assertEquals(FirstCreateStage.USER_PROFILE, advanced.first.stage)
        assertEquals(FirstCreateValidation.Valid, advanced.second)
    }

    @Test
    fun confirmationRequiresTheConfirmStageAndAFullDraft() {
        assertTrue(
            FirstCreateRules.canConfirm(draft(stage = FirstCreateStage.PERSONA_EDIT)) is
                FirstCreateValidation.Invalid,
        )
        val ready = draft(stage = FirstCreateStage.CONFIRM)
        assertEquals(FirstCreateValidation.Valid, FirstCreateRules.canConfirm(ready))

        assertTrue(
            FirstCreateRules.canConfirm(ready.copy(declaredAge = null)) is FirstCreateValidation.Invalid,
        )
        assertTrue(
            "a persona writer must be chosen",
            FirstCreateRules.canConfirm(ready.copy(personaGeneratorProvider = null)) is
                FirstCreateValidation.Invalid,
        )
        assertTrue(
            "a persona writer model must be chosen",
            FirstCreateRules.canConfirm(ready.copy(personaGeneratorModel = "")) is
                FirstCreateValidation.Invalid,
        )
        assertTrue(
            FirstCreateRules.canConfirm(ready.copy(personaDraft = "只属于我")) is FirstCreateValidation.Invalid,
        )
    }

    @Test
    fun effectiveGenderTextUsesCustomWordingOnlyWhenSelected() {
        assertEquals(
            "非二元",
            draft(gender = GenderExpression.CUSTOM, genderCustom = "非二元").effectiveGenderText,
        )
        assertEquals(
            GenderExpression.UNDISCLOSED.name,
            draft(gender = GenderExpression.UNDISCLOSED, genderCustom = "ignored").effectiveGenderText,
        )
    }

    private fun validMiniMax() = ServiceValidationState(
        provider = SelectedGenerationProvider.MINIMAX_OFFICIAL.name,
        validated = true,
        validatedAt = 1_000L,
        modelId = "MiniMax-M2",
        endpointHost = "api.minimaxi.com",
        credentialFingerprint = "abcd",
    )
}

/** The neutral template must not reintroduce a fixed identity. */
class NeutralPersonaTemplateTest {

    @Test
    fun generatedPersonaUsesTheUsersOwnWordsAndNoFixedIdentity() {
        val draft = FirstCreateDraft(
            petName = "豆豆",
            userAddress = "小雨",
            relationship = "一起住的小狗",
            personaDraft = "",
        )
        val text = NeutralPersonaTemplate.buildPersonaText(draft)

        assertTrue(text.contains("豆豆"))
        assertTrue(text.contains("小雨"))
        assertTrue(text.contains("一起住的小狗"))
        // No legacy or invented identity may leak into the generated persona.
        listOf("小可乐", "猫猫头", "长发", "尾巴", "专属", "恋人").forEach { token ->
            assertFalse("generated persona must not contain $token", text.contains(token))
        }
        assertEquals(
            FirstCreateValidation.Valid,
            FirstCreateRules.validatePersonaDraft(text),
        )
    }

    @Test
    fun characterCoreCarriesTheConfirmedNameAndNeutralBoundaries() {
        val draft = FirstCreateDraft(
            petName = "豆豆",
            userAddress = "小雨",
            relationship = "一起住的小狗",
        )
        // The confirmed persona is the editable draft text, so the boundary
        // clauses the user reviews are exactly what gets locked.
        val personaText = NeutralPersonaTemplate.buildPersonaText(draft)
        val core = NeutralPersonaTemplate.buildCharacterCore(draft, personaText, confirmedAt = 42L)

        assertEquals("豆豆", core["name"]?.toString()?.trim('"'))
        assertEquals("豆豆", core["display_name"]?.toString()?.trim('"'))
        assertEquals("小雨", core["user_name"]?.toString()?.trim('"'))
        // Relationship comes from the user, never from a product default.
        assertEquals("一起住的小狗", core["relationship"]?.toString()?.trim('"'))
        // The resolved prompt identity reads these keys directly.
        val encoded = NeutralPersonaTemplate.encodeCharacterCore(core)
        listOf("小可乐", "猫猫头", "专属小狗", "长发").forEach { token ->
            assertFalse("character core must not contain $token", encoded.contains(token))
        }
        // The core states the boundaries explicitly, so the ban words may only
        // appear inside a prohibitive clause, never as a description.
        val anchor = core["persona_anchor"]!!.toString()
        assertTrue(anchor.contains("不生成色情或露骨性内容"))
        assertTrue(anchor.contains("不推进或暗示亲密关系"))
        assertFalse(anchor.contains("可以露骨"))
        assertFalse(anchor.contains("主动调情"))
    }
}
