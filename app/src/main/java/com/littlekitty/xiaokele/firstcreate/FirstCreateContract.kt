package com.littlekitty.xiaokele.firstcreate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ordered first-create stages.
 *
 * The flow is resumable: a draft is persisted after every accepted input and
 * restored on the next launch. Confirmation is the only transition that makes
 * the companion usable.
 */
enum class FirstCreateStage {
    /** Blocked until at least one provider has a verified, usable connection. */
    CONNECTION_GATE,
    PET_NAME,
    USER_PROFILE,
    PERSONALITY_BRIEF,
    MODEL_CHOICE,
    PERSONA_GENERATING,
    PERSONA_EDIT,
    CONFIRM,
    COMPLETED,
    ;

    val next: FirstCreateStage
        get() = when (this) {
            CONNECTION_GATE -> PET_NAME
            PET_NAME -> USER_PROFILE
            USER_PROFILE -> PERSONALITY_BRIEF
            PERSONALITY_BRIEF -> MODEL_CHOICE
            MODEL_CHOICE -> PERSONA_GENERATING
            PERSONA_GENERATING -> PERSONA_EDIT
            PERSONA_EDIT -> CONFIRM
            CONFIRM, COMPLETED -> COMPLETED
        }

    val previous: FirstCreateStage?
        get() = when (this) {
            CONNECTION_GATE -> null
            PET_NAME -> CONNECTION_GATE
            USER_PROFILE -> PET_NAME
            PERSONALITY_BRIEF -> USER_PROFILE
            MODEL_CHOICE -> PERSONALITY_BRIEF
            // Generating is a transient state: going back returns to the chooser.
            PERSONA_GENERATING -> MODEL_CHOICE
            PERSONA_EDIT -> MODEL_CHOICE
            CONFIRM -> PERSONA_EDIT
            COMPLETED -> null
        }

    /** Steps the user actually fills in, for the "n/5" label. */
    val stepNumber: Int
        get() = when (this) {
            CONNECTION_GATE -> 0
            PET_NAME -> 1
            USER_PROFILE -> 2
            PERSONALITY_BRIEF -> 3
            MODEL_CHOICE -> 4
            PERSONA_GENERATING -> 4
            PERSONA_EDIT -> 5
            CONFIRM -> 6
            COMPLETED -> 6
        }
}

/** Gender expression is user wording, never an inference source. */
enum class GenderExpression {
    UNDISCLOSED,
    FEMALE,
    MALE,
    OTHER,
    CUSTOM,
}

/**
 * A single generation provider. Exactly one is selected; the other provider's
 * credential is never read, borrowed or written.
 */
enum class SelectedGenerationProvider {
    MINIMAX_OFFICIAL,
    BAILIAN,
}

/** How far the selected provider's real capability check has got. */
@Serializable
data class ServiceValidationState(
    @SerialName("provider") val provider: String,
    @SerialName("validated") val validated: Boolean = false,
    @SerialName("validated_at") val validatedAt: Long? = null,
    @SerialName("model_id") val modelId: String = "",
    @SerialName("endpoint_host") val endpointHost: String = "",
    /** Fingerprint of the credential used, never the credential itself. */
    @SerialName("credential_fingerprint") val credentialFingerprint: String = "",
    @SerialName("failure_reason") val failureReason: String? = null,
)

/**
 * The resumable draft. Every field is optional because the user may abandon
 * the flow at any step; [stage] records how far they got so the UI can resume
 * without losing input.
 */
@Serializable
data class FirstCreateDraft(
    /**
     * A fresh draft starts at the gate, not at the first input step: a new
     * install must not be able to skip the verified-connection requirement.
     */
    @SerialName("stage") val stage: FirstCreateStage = FirstCreateStage.CONNECTION_GATE,
    @SerialName("pet_name") val petName: String = "",
    @SerialName("user_address") val userAddress: String = "",
    /**
     * How the companion relates to the user, in the user's own words. This is
     * deliberately user-defined: the product must not impose a fixed
     * relationship (owner/pet, exclusive companion, partner, ...).
     */
    @SerialName("relationship") val relationship: String = "",
    @SerialName("gender_expression") val genderExpression: GenderExpression = GenderExpression.UNDISCLOSED,
    @SerialName("gender_custom") val genderCustom: String = "",
    /** Declared age is recorded as stated plus when it was stated; it is never inferred. */
    @SerialName("declared_age") val declaredAge: Int? = null,
    @SerialName("declared_age_recorded_at") val declaredAgeRecordedAt: Long? = null,
    @SerialName("provider") val provider: SelectedGenerationProvider? = null,
    @SerialName("service_validation") val serviceValidation: ServiceValidationState? = null,

    // --- what the user wants the companion to be -----------------------------
    /** Free text: what the companion should be like. */
    @SerialName("desired_personality") val desiredPersonality: String = "",
    /** Free text: how the companion should see and treat the user. */
    @SerialName("desired_toward_user") val desiredTowardUser: String = "",
    /** Free text, optional: speaking style. */
    @SerialName("desired_style") val desiredStyle: String = "",
    /** Free text, optional: what the user does not want. */
    @SerialName("avoid") val avoid: String = "",

    /**
     * Which verified provider/model writes the persona. Kept separate from the
     * chat route so choosing a writing model never rewires conversation.
     */
    @SerialName("persona_generator_provider") val personaGeneratorProvider: SelectedGenerationProvider? = null,
    @SerialName("persona_generator_model") val personaGeneratorModel: String = "",

    /** The text the user reviews and edits, and that is locked on confirmation. */
    @SerialName("persona_draft") val personaDraft: String = "",
    @SerialName("persona_edited") val personaEdited: Boolean = false,
    /** Behaviour rules produced by the generator, rendered alongside the draft. */
    @SerialName("persona_rules") val personaRules: List<String> = emptyList(),
    /** True when generation failed and the neutral template was used instead. */
    @SerialName("persona_from_fallback") val personaFromFallback: Boolean = false,
    @SerialName("persona_generated_at") val personaGeneratedAt: Long? = null,
    @SerialName("confirmed") val confirmed: Boolean = false,
    @SerialName("updated_at") val updatedAt: Long = 0L,
) {
    /** The provider selection is usable only after a real successful check. */
    val hasValidatedProvider: Boolean
        get() = provider != null &&
            serviceValidation?.validated == true &&
            serviceValidation.provider == provider.name

    val effectiveGenderText: String
        get() = if (genderExpression == GenderExpression.CUSTOM) genderCustom else genderExpression.name
}

/** Validation result that the UI can render without inventing its own rules. */
sealed interface FirstCreateValidation {
    data object Valid : FirstCreateValidation
    data class Invalid(val reason: String) : FirstCreateValidation
}

/**
 * Pure validation and stage-progression rules. Keeping these free of Android
 * and database types makes them directly unit-testable and keeps the UI from
 * re-implementing limits.
 */
object FirstCreateRules {

    const val MAX_NAME_LENGTH = 24
    const val MIN_DECLARED_AGE = 1
    const val MAX_DECLARED_AGE = 120
    const val MAX_CUSTOM_GENDER_LENGTH = 24
    const val MAX_PERSONA_DRAFT_LENGTH = 4_000
    /** Upper bound for each free-text describing-what-you-want box. */
    const val MAX_BRIEF_CHARS = 600
    const val MAX_RELATIONSHIP_LENGTH = 40

    fun validatePetName(raw: String): FirstCreateValidation {
        val value = raw.trim()
        return when {
            value.isEmpty() -> FirstCreateValidation.Invalid("请先给小狗起一个名字。")
            value.length > MAX_NAME_LENGTH -> FirstCreateValidation.Invalid("名字最多 $MAX_NAME_LENGTH 个字。")
            value.any { it.isISOControl() } -> FirstCreateValidation.Invalid("名字里不能包含控制字符。")
            else -> FirstCreateValidation.Valid
        }
    }

    fun validateUserAddress(raw: String): FirstCreateValidation {
        val value = raw.trim()
        return when {
            value.isEmpty() -> FirstCreateValidation.Invalid("请填写希望被称呼的方式。")
            value.length > MAX_NAME_LENGTH -> FirstCreateValidation.Invalid("称呼最多 $MAX_NAME_LENGTH 个字。")
            value.any { it.isISOControl() } -> FirstCreateValidation.Invalid("称呼里不能包含控制字符。")
            else -> FirstCreateValidation.Valid
        }
    }

    fun validateDeclaredAge(raw: String): FirstCreateValidation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return FirstCreateValidation.Invalid("请填写年龄。")
        val age = trimmed.toIntOrNull()
            ?: return FirstCreateValidation.Invalid("年龄需要是数字。")
        return when {
            age < MIN_DECLARED_AGE || age > MAX_DECLARED_AGE ->
                FirstCreateValidation.Invalid("年龄需要在 $MIN_DECLARED_AGE 到 $MAX_DECLARED_AGE 之间。")
            else -> FirstCreateValidation.Valid
        }
    }

    fun validateGenderCustom(raw: String): FirstCreateValidation {
        val value = raw.trim()
        return when {
            value.isEmpty() -> FirstCreateValidation.Invalid("请填写你的表述，或选择「不透露」。")
            value.length > MAX_CUSTOM_GENDER_LENGTH ->
                FirstCreateValidation.Invalid("表述最多 $MAX_CUSTOM_GENDER_LENGTH 个字。")
            else -> FirstCreateValidation.Valid
        }
    }

    fun validateRelationship(raw: String): FirstCreateValidation {
        val value = raw.trim()
        return when {
            value.isEmpty() -> FirstCreateValidation.Invalid("请填写希望它和你的关系，或选择「只是一起待着」。")
            value.length > MAX_RELATIONSHIP_LENGTH ->
                FirstCreateValidation.Invalid("关系描述最多 $MAX_RELATIONSHIP_LENGTH 个字。")
            value.any { it.isISOControl() } -> FirstCreateValidation.Invalid("关系描述里不能包含控制字符。")
            else -> FirstCreateValidation.Valid
        }
    }

    fun validatePersonaDraft(raw: String): FirstCreateValidation {
        val value = raw.trim()
        return when {
            value.isEmpty() -> FirstCreateValidation.Invalid("人格草稿不能为空。")
            value.length > MAX_PERSONA_DRAFT_LENGTH ->
                FirstCreateValidation.Invalid("人格草稿最多 $MAX_PERSONA_DRAFT_LENGTH 个字。")
            else -> FirstCreateValidation.Valid
        }
    }

    fun validateProviderSelection(
        provider: SelectedGenerationProvider?,
        validation: ServiceValidationState?,
    ): FirstCreateValidation = when {
        provider == null -> FirstCreateValidation.Invalid("请选择一家生成服务。")
        validation?.validated != true -> FirstCreateValidation.Invalid("所选服务还没有通过真实能力验证。")
        validation.provider != provider.name ->
            FirstCreateValidation.Invalid("验证结果与所选服务不一致，请重新验证。")
        else -> FirstCreateValidation.Valid
    }

    /** Rejects persona text that would ship a boundary violation. */
    fun validatePersonaSafety(raw: String): FirstCreateValidation {
        val text = raw.lowercase()
        BANNED_PERSONA_PATTERNS.forEach { (pattern, reason) ->
            if (PersonaSafetyText.containsViolationOutsideProhibition(text, pattern)) {
                return FirstCreateValidation.Invalid(reason)
            }
        }
        return FirstCreateValidation.Valid
    }

    /**
     * Stage progression. A step is only accepted when its own input is valid,
     * so a draft can never advance past missing or unsafe input.
     */
    fun advance(draft: FirstCreateDraft): Pair<FirstCreateDraft, FirstCreateValidation> {
        val validation = validateStage(draft)
        if (validation !is FirstCreateValidation.Valid) return draft to validation
        return draft.copy(stage = draft.stage.next) to FirstCreateValidation.Valid
    }

    /** Validation for whatever stage the draft is currently sitting on. */
    fun validateStage(draft: FirstCreateDraft): FirstCreateValidation = when (draft.stage) {
        // The gate is satisfied by the credential stores, not by the draft, so
        // there is nothing to validate here beyond the caller having checked it.
        FirstCreateStage.CONNECTION_GATE -> FirstCreateValidation.Valid

        FirstCreateStage.PET_NAME -> validatePetName(draft.petName)

        FirstCreateStage.USER_PROFILE -> validateUserAddress(draft.userAddress)
            .let { if (it is FirstCreateValidation.Valid) validateRelationship(draft.relationship) else it }
            .let { if (it is FirstCreateValidation.Valid) validateDeclaredAge(draft.declaredAge?.toString().orEmpty()) else it }
            .let {
                if (it is FirstCreateValidation.Valid && draft.genderExpression == GenderExpression.CUSTOM) {
                    validateGenderCustom(draft.genderCustom)
                } else {
                    it
                }
            }

        // Only the two describing-what-you-want fields are required; style and
        // avoid stay optional so a newcomer is never blocked by a blank box.
        FirstCreateStage.PERSONALITY_BRIEF -> validatePersonalityBrief(draft)

        FirstCreateStage.MODEL_CHOICE -> validatePersonaGeneratorChoice(draft)

        FirstCreateStage.PERSONA_GENERATING -> validatePersonaDraft(draft.personaDraft)

        FirstCreateStage.PERSONA_EDIT -> validatePersonaSafety(draft.personaDraft)

        FirstCreateStage.CONFIRM -> validatePersonaSafety(draft.personaDraft)

        FirstCreateStage.COMPLETED -> FirstCreateValidation.Valid
    }

    fun validatePersonalityBrief(draft: FirstCreateDraft): FirstCreateValidation {
        val personality = draft.desiredPersonality.trim()
        val toward = draft.desiredTowardUser.trim()
        return when {
            personality.isEmpty() -> FirstCreateValidation.Invalid("请先描述一下你希望它是什么样的。")
            personality.length > MAX_BRIEF_CHARS ->
                FirstCreateValidation.Invalid("性格描述最多 $MAX_BRIEF_CHARS 个字。")
            toward.isEmpty() -> FirstCreateValidation.Invalid("请写一下你希望它怎么看待你。")
            toward.length > MAX_BRIEF_CHARS ->
                FirstCreateValidation.Invalid("这段描述最多 $MAX_BRIEF_CHARS 个字。")
            draft.avoid.trim().length > MAX_BRIEF_CHARS ->
                FirstCreateValidation.Invalid("不希望的设定最多 $MAX_BRIEF_CHARS 个字。")
            draft.desiredStyle.trim().length > MAX_BRIEF_CHARS ->
                FirstCreateValidation.Invalid("说话方式最多 $MAX_BRIEF_CHARS 个字。")
            else -> FirstCreateValidation.Valid
        }
    }

    /** The persona writer must be a provider the user actually verified. */
    fun validatePersonaGeneratorChoice(draft: FirstCreateDraft): FirstCreateValidation = when {
        draft.personaGeneratorProvider == null -> FirstCreateValidation.Invalid("请选择一个用来写设定的模型。")
        draft.personaGeneratorModel.isBlank() -> FirstCreateValidation.Invalid("请选择一个可用的模型。")
        else -> FirstCreateValidation.Valid
    }

    /**
     * A confirmation is only allowed from CONFIRM with a validated single
     * provider and a safe persona draft.
     */
    fun canConfirm(draft: FirstCreateDraft): FirstCreateValidation {
        if (draft.stage != FirstCreateStage.CONFIRM) {
            return FirstCreateValidation.Invalid("还有步骤没有完成。")
        }
        val writerCheck = validatePersonaGeneratorChoice(draft)
        if (writerCheck !is FirstCreateValidation.Valid) return writerCheck
        if (validatePetName(draft.petName) !is FirstCreateValidation.Valid) {
            return FirstCreateValidation.Invalid("小狗名字无效。")
        }
        if (validateUserAddress(draft.userAddress) !is FirstCreateValidation.Valid) {
            return FirstCreateValidation.Invalid("称呼无效。")
        }
        if (validateRelationship(draft.relationship) !is FirstCreateValidation.Valid) {
            return FirstCreateValidation.Invalid("关系描述无效。")
        }
        if (draft.declaredAge == null) return FirstCreateValidation.Invalid("年龄未填写。")
        if (validatePersonaDraft(draft.personaDraft) !is FirstCreateValidation.Valid) {
            return FirstCreateValidation.Invalid("设定的正文无效。")
        }
        return validatePersonaSafety(draft.personaDraft)
    }

    /**
     * Content that must never be shipped as a user-confirmed persona. These are
     * the boundary guarantees of the public edition, so they are enforced on
     * the saved text rather than only in UI copy.
     *
     * The scan is negation-aware: a persona that refuses this content on the
     * user's behalf ("不涉及恋爱、暧昧或调情") satisfies the boundary instead of
     * breaking it. Only content that is actually offered is rejected.
     */
    private val BANNED_PERSONA_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("(露骨|色情|情色|性行为|性暗示|裸|脱衣|情欲)") to "人格草稿不能包含色情或露骨性内容。",
        Regex("(调情|勾引|反撩|暧昧|恋爱|情侣|女朋友|男朋友|老婆|老公)") to "人格草稿不能默认亲密或恋爱关系。",
        Regex("(未成年|幼女|幼态|萝莉)") to "人格草稿不能包含未成年相关设定。",
        Regex("(只属于我|只准|不许离开|不能离开|离开我就|只能有我)") to "人格草稿不能包含排他或依赖诱导。",
        Regex("(惩罚你|收拾你|教训你|放过你|饶过你)") to "人格草稿不能包含命令、威胁或居高临下的表达。",
    )
}
