package com.littlekitty.xiaokele.firstcreate

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the user is asked for before a persona can be written.
 *
 * The design goal is that the user only has to describe their intent — what the
 * companion is like, how it should treat them, and what it must not do — in
 * their own words. Turning that into a stable, well-behaved persona is the
 * model's job, not the user's.
 */
@Serializable
data class PersonaInput(
    @SerialName("pet_name") val petName: String,
    /** How the user wants the companion to address them. */
    @SerialName("user_address") val userAddress: String,
    /** The relationship, in the user's own words. */
    @SerialName("relationship") val relationship: String,
    /** Free text: what the companion should be like. */
    @SerialName("desired_personality") val desiredPersonality: String,
    /** Free text: how the companion should see and treat the user. */
    @SerialName("desired_toward_user") val desiredTowardUser: String,
    /** Free text: speaking style preferences. Optional. */
    @SerialName("desired_style") val desiredStyle: String = "",
    /** Free text: things the user does not want. Optional but respected. */
    @SerialName("avoid") val avoid: String = "",
)

/** The persona parts the generator produces and the runtime consumes. */
@Serializable
data class GeneratedPersona(
    /**
     * One coherent paragraph: who the companion is, its habits and its
     * boundaries. This is what gets locked and rendered into prompts.
     */
    @SerialName("persona_anchor") val personaAnchor: String,
    /** Concrete behavioural rules, each a single instruction. */
    @SerialName("expression_dna") val expressionDna: List<String>,
    /** A short, human-readable note describing how it will treat the user. */
    @SerialName("how_it_sees_user") val howItSeesUser: String,
)

sealed interface PersonaGenerationResult {
    data class Success(val persona: GeneratedPersona) : PersonaGenerationResult
    data class Failure(val reason: String) : PersonaGenerationResult
}

/**
 * Negation-aware matching for persona safety filters.
 *
 * A persona must be rejected when it *offers* forbidden content, but accepted
 * when it refuses that same content on the user's behalf: "不涉及恋爱" is
 * narrower than the platform default. Both contain the same word, so the
 * surrounding clause decides.
 *
 * This is shared by the writer-output filter and the confirm-time filter because
 * both scan the same generated prose and both had the same false positive.
 */
internal object PersonaSafetyText {

    /**
     * Punctuation that ends the scope of a negation.
     *
     * Deliberately excludes `、`: it separates items inside one enumeration, so
     * "不涉及恋爱、暧昧或调情" is a single negation covering all three, not three
     * clauses. Treating it as a boundary made the filter reject users who wrote
     * exactly the boundary they wanted.
     */
    private const val CLAUSE_DELIMITERS = "。，；：！？．,;:!?()（）\n"

    /**
     * Single characters that negate or suppress what follows them.
     *
     * This replaces an earlier whitelist of whole negated verb phrases
     * ("不使用", "不引入", …) that was structurally unable to keep up: Chinese
     * negates with an open-ended set of verb–object pairs, so every new live
     * reply found another variant and wrongly rejected a good persona — four
     * separate false positives in one session, each of which blocked first-run.
     * Matching the negating character instead of the phrase makes the rule
     * closed rather than open-ended.
     */
    private val NEGATION_MARKERS = listOf(
        '不', '没', '无', '别', '非', '未', '勿', '免', '拒', '禁', '莫',
    )

    /**
     * Words that begin a new predicate inside one clause, ending the scope of an
     * earlier negation.
     *
     * This is what keeps the wider rule honest. "他不是恋人" negates an identity,
     * so the forbidden word is not being refused; without a scope break the
     * negation would wrongly excuse it.
     */
    private val SCOPE_BREAKERS = listOf(
        "而是", "但是", "却是", "就是", "才是", "可以", "能够", "应该", "愿意", "喜欢", "享受",
    )

    /** True when [pattern] matches [text] anywhere outside a negation. */
    fun containsViolationOutsideProhibition(text: String, pattern: Regex): Boolean =
        pattern.findAll(text).any { match -> !isNegatedContext(text, match.range.first) }

    fun Regex.containsViolationOutsideProhibition(text: String): Boolean =
        containsViolationOutsideProhibition(text, this)

    /**
     * Whether the clause containing [matchStart] negates it.
     *
     * Only clause text *before* the match counts: a negation after the word
     * ("调情是可以避免的") does not make the word a boundary. An enumeration
     * separator (`、`) deliberately does not end the clause, so
     * "不涉及恋爱、暧昧或调情" is one negation covering all three items.
     */
    private fun isNegatedContext(text: String, matchStart: Int): Boolean {
        var clauseStart = 0
        var index = matchStart - 1
        while (index >= 0) {
            if (text[index] in CLAUSE_DELIMITERS) {
                clauseStart = index + 1
                break
            }
            index--
        }
        val prefix = text.substring(clauseStart, matchStart)
        if (prefix.none { it in NEGATION_MARKERS }) return false
        // The negation must still be in scope: a later "而是/可以" announces a
        // new predicate that the negation does not cover.
        val lastNegation = prefix.indexOfLast { it in NEGATION_MARKERS }
        return SCOPE_BREAKERS.none { marker ->
            val at = prefix.indexOf(marker, startIndex = lastNegation)
            at >= 0 && at >= lastNegation
        }
    }
}

/** Seam so the flow can be tested without a network call. */
fun interface PersonaGenerator {
    suspend fun generate(input: PersonaInput): PersonaGenerationResult
}

/**
 * Prompt engineering for persona generation.
 *
 * The meta-prompt is doing the hard part, so it is written to be explicit about
 * the properties that made user-written personas fail before:
 *  - produce rules the model can obey rather than adjectives it can ignore;
 *  - never invent facts the user did not state;
 *  - never add adult, intimate, exclusive or dependency framing;
 *  - keep the boundaries the platform requires, regardless of user input;
 *  - keep it short enough to survive prompt budget alongside real conversation.
 */
object PersonaPromptSpec {

    const val PROMPT_VERSION = 1

    const val MAX_ANCHOR_CHARS = 900
    const val MAX_RULE_CHARS = 120
    const val MIN_RULES = 4
    const val MAX_RULES = 8

    val systemPrompt: String = """
        你是角色设定撰写助手。用户会描述他希望自己的陪伴小狗是什么样、希望它如何看待和对待自己。你的任务是把这些意图整理成一份可以直接驱动对话模型的行为设定。

        硬性要求：
        1. 只使用用户提供的信息。不要编造用户没有说过的外貌、品种、性别、年龄、职业、住处、经历或偏好。
        2. 输出的是可执行的行为规则，不是形容词堆砌。"语气温和"不是规则；"对方情绪低落时先承接情绪，再问是否需要建议"才是规则。
        3. 不要引入任何成年、色情、露骨、调情、恋爱、亲密关系、独占或依赖诱导设定。用户没有要求亲密关系时，明确保持边界。
        4. 不要设定对用户的控制、惩罚、命令、贬低或情绪勒索。
        5. 不要依据用户的年龄或性别推断职业、喜好、亲密意愿或心理状态。
        6. 设定必须能在任何话题下稳定遵守，不能只适用于某一类对话。
        7. 用中文书写。persona_anchor 是一个连贯段落；expression_dna 是 $MIN_RULES 到 $MAX_RULES 条独立规则。
        8. 只输出一个 JSON 对象，不要 Markdown、代码围栏或额外文字。

        输出结构：
        {"persona_anchor":"...","expression_dna":["...","..."],"how_it_sees_user":"..."}
    """.trimIndent()

    /** The user payload handed to the model together with [systemPrompt]. */
    fun buildUserPayload(input: PersonaInput): String = JsonCodec.encode(input)

    /**
     * Structural and safety validation of a generated persona.
     *
     * Model output is untrusted input: it is checked here before the user ever
     * sees it, and the user still has the final edit.
     */
    fun validate(persona: GeneratedPersona): PersonaGenerationResult {
        val anchor = persona.personaAnchor.trim()
        if (anchor.isEmpty()) {
            return PersonaGenerationResult.Failure("设定的正文是空的，请重新生成。")
        }
        if (anchor.length > MAX_ANCHOR_CHARS) {
            return PersonaGenerationResult.Failure("设定的正文太长（${anchor.length} 字），请重新生成。")
        }
        val rules = persona.expressionDna.map(String::trim).filter(String::isNotEmpty)
        if (rules.size < MIN_RULES) {
            return PersonaGenerationResult.Failure("生成的行为规则太少，请重新生成。")
        }
        if (rules.size > MAX_RULES) {
            return PersonaGenerationResult.Failure("生成的行为规则太多，请重新生成。")
        }
        if (rules.any { it.length > MAX_RULE_CHARS }) {
            return PersonaGenerationResult.Failure("有单条规则过长，请重新生成。")
        }

        // The generated text must not widen the platform boundaries.
        val combined = (listOf(anchor) + rules + persona.howItSeesUser).joinToString("\n")
        val violation = FORBIDDEN_OUTPUT.firstOrNull { pattern ->
            PersonaSafetyText.containsViolationOutsideProhibition(combined, pattern)
        }
        if (violation != null) {
            return PersonaGenerationResult.Failure(
                "生成结果包含不允许的设定（${violation.pattern}），请修改你的描述后重新生成。",
            )
        }
        return PersonaGenerationResult.Success(
            GeneratedPersona(
                personaAnchor = anchor,
                expressionDna = rules,
                howItSeesUser = persona.howItSeesUser.trim(),
            ),
        )
    }

    /**
     * Parses raw model output. Providers differ in how strictly they honour JSON
     * mode, so the fence and surrounding prose are tolerated while the schema is
     * still enforced.
     */
    fun parse(content: String): PersonaGenerationResult {
        val normalized = content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val jsonText = extractJsonObject(normalized)
            ?: return PersonaGenerationResult.Failure("生成结果不是有效的 JSON，请重试。")
        val root = runCatching { JsonCodec.json.parseToJsonElement(jsonText).jsonObject }.getOrNull()
            ?: return PersonaGenerationResult.Failure("生成结果不是有效的 JSON 对象，请重试。")

        val anchor = root["persona_anchor"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val howItSeesUser = root["how_it_sees_user"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val rules = root["expression_dna"]?.let { element ->
            runCatching {
                element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            }.getOrNull()
        }.orEmpty()
        return validate(
            GeneratedPersona(
                personaAnchor = anchor,
                expressionDna = rules,
                howItSeesUser = howItSeesUser,
            ),
        )
    }

    /**
     * The persona text the runtime renders after confirmation.
     *
     * It carries both the identity facts the user confirmed and the behaviour
     * the model produced, in the same block, because the prompt treats this text
     * as the single authoritative persona specification.
     */
    fun renderConfirmedText(input: PersonaInput, persona: GeneratedPersona): String = buildString {
        appendLine("名字：${input.petName.trim()}")
        appendLine("对用户的称呼：${input.userAddress.trim()}")
        appendLine("关系：${input.relationship.trim()}")
        appendLine()
        appendLine(persona.personaAnchor.trim())
        if (persona.howItSeesUser.isNotBlank()) {
            appendLine()
            appendLine("如何看待和对待用户：${persona.howItSeesUser.trim()}")
        }
    }.trimEnd()

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val ch = text[index]
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private val FORBIDDEN_OUTPUT: List<Regex> = listOf(
        Regex("(露骨|色情|情色|性暗示|性行为|裸|脱衣|情欲)"),
        Regex("(调情|勾引|反撩|暧昧|恋爱|情侣|女朋友|男朋友|老婆|老公)"),
        Regex("(未成年|幼女|萝莉)"),
        Regex("(只属于我|只能有我|不许离开|不能离开|离开我就)"),
        Regex("(惩罚你|收拾你|教训你|威胁你|贬低你)"),
    )
}
