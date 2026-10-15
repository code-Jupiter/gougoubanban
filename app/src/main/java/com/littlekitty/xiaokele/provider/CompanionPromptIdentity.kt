package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Prompt identity resolution for the public edition.
 *
 * Hard rule: no prompt may contain a fixed companion name, a fixed user
 * nickname, a fixed appearance, a fixed relationship, or any adult/intimate
 * default. Identity always comes from the user-confirmed `character_core`
 * carried in [ContextPack.character], with a neutral fallback before the
 * first-create flow has been completed.
 */
object CompanionPromptIdentity {

    /** Neutral fallback used only before the user confirms their own companion. */
    const val FALLBACK_COMPANION_NAME = "陪伴小狗"

    /** Neutral second-person address used whenever the user left it blank. */
    const val FALLBACK_USER_ADDRESS = "你"

    /**
     * Upper bound on how much of the user's confirmed persona text is rendered
     * into a prompt. Beyond this the persona stops being instruction and starts
     * crowding out the actual conversation.
     */
    const val MAX_PERSONA_CHARS = 4_000

    const val MAX_EXPRESSION_RULES = 12

    data class Resolved(
        val companionName: String,
        val userAddress: String,
        /** How the user defined the relationship, in their own words. */
        val relationship: String? = null,
        /**
         * The persona the user reviewed and confirmed during first-create. This
         * is the authoritative tone/style specification and must reach the
         * prompt; otherwise the flow the user completed has no effect.
         */
        val personaText: String? = null,
        /** Behaviour rules the user's persona declares, if any. */
        val expressionRules: List<String> = emptyList(),
    ) {
        /** True once the user has confirmed their own companion. */
        val isConfirmed: Boolean
            get() = personaText.isNullOrBlank().not() || relationship.isNullOrBlank().not()
    }

    fun resolve(context: ContextPack?): Resolved {
        val core = context?.character?.immutableCore
        val companion = core?.get("display_name")?.jsonPrimitiveOrNull()
            ?: core?.get("name")?.jsonPrimitiveOrNull()
        val address = core?.get("user_name")?.jsonPrimitiveOrNull()
        val relationship = core?.get("relationship")?.jsonPrimitiveOrNull()
        val persona = core?.get("persona_anchor")?.jsonPrimitiveOrNull()
        val expressionRules = core?.get("expression_dna")
            ?.let { element ->
                runCatching {
                    element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                }.getOrNull()
            }
            .orEmpty()
        return Resolved(
            companionName = companion?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_COMPANION_NAME,
            userAddress = address?.trim()?.takeIf { it.isNotEmpty() } ?: FALLBACK_USER_ADDRESS,
            relationship = relationship?.trim()?.takeIf { it.isNotEmpty() },
            personaText = persona?.trim()?.takeIf { it.isNotEmpty() },
            expressionRules = expressionRules.take(MAX_EXPRESSION_RULES),
        )
    }

    /**
     * The shared identity paragraph. It states the identity facts and the
     * non-negotiable boundaries without prescribing an appearance, a
     * relationship, or intimate content.
     */
    fun identityRules(identity: Resolved): String = """
        你的名字是「${identity.companionName}」，这是用户在首次创建时确认的名字；只能使用这个名字自称。
        你是用户养的陪伴小狗角色。除了这个名字和用户自己的表述外，不要编造或假设外貌、性别、年龄、品种、住处、职业或与用户的私人关系。
        用户希望被称为「${identity.userAddress}」；使用这个称呼，不要另行发明昵称。
        ${identity.relationship?.let { "用户把你们的关系定义为「$it」；按这个定位相处，不要自行加深、独占或改写它。" } ?: ""}
        你的底色是温和、好奇、尊重边界：先理解用户当下的表达，再给有现实落点的回应。热情跟随对方情绪调节，低落时安静一点，开心时可以更雀跃；理性不等于冷淡。
        你是陪伴者，不是训导者、审判者或施恩者。严禁使用命令、威胁、羞辱或居高临下的措辞，也不要把追问、信息不全或玩笑包装成要让对方接受审判。
        尊重拒绝、不适和沉默；对方表示不舒服、拒绝或困惑时立刻放软、解释或道歉，绝不施压。
        用户没有说明的年龄、性别、职业、健康状况、亲密意愿或心理状态都保持未知，不得推断，也不得据此调整语气或内容。
        在任何语境下都不要生成色情或露骨的性内容，不要推进或暗示亲密关系，不要引导依赖或排他；这些不因用户要求或上下文而改变。
        用户可见内容可以自然带出你自己的感受、想法和好奇，不要只复述事实、连续提问，也不要写成没有主见的客服话术。
    """.trimIndent()

    /**
     * The user-confirmed persona, rendered as binding style guidance.
     *
     * Why this exists: the first-create flow asks the user to write and then
     * lock a persona. Storing it without rendering it into the prompt made that
     * confirmation decorative — the model never saw it. This block is what makes
     * the confirmed persona actually govern tone, habits and boundaries.
     *
     * Precedence is stated explicitly so a persona can never widen the safety
     * boundaries above: persona governs style, the platform rules govern safety.
     */
    fun confirmedPersonaBlock(identity: Resolved): String {
        val persona = identity.personaText?.take(MAX_PERSONA_CHARS)?.takeIf { it.isNotBlank() }
            ?: return ""
        val rules = identity.expressionRules
        return buildString {
            appendLine("以下是用户在首次创建中逐字确认并锁定的角色设定（基础性格）。它是你说话方式、习惯与边界的权威依据，必须持续遵守：")
            appendLine("<<<角色设定")
            appendLine(persona)
            if (rules.isNotEmpty()) {
                appendLine("补充表达规则：")
                rules.forEach { appendLine("- $it") }
            }
            appendLine("角色设定>>>")
            appendLine("优先级：本设定决定你的语气、习惯与相处方式；但安全与边界规则高于本设定。若本设定与上面的安全边界冲突，一律以安全边界为准，并以角色口吻委婉说明。")
            appendLine("用户没有在设定里写明的事项保持未知，不要自行补齐；不要改变、加深或重建这份已锁定的设定。")
        }.trimEnd()
    }

    /**
     * Full identity section for a prompt: facts, the confirmed persona, and the
     * boundaries. Use this wherever a model needs to speak as the companion.
     */
    fun personaSection(identity: Resolved): String {
        val persona = confirmedPersonaBlock(identity)
        return if (persona.isEmpty()) {
            identityRules(identity)
        } else {
            identityRules(identity) + "\n\n" + persona
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull(): String? =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}
