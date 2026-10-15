package com.littlekitty.xiaokele.firstcreate

import com.littlekitty.xiaokele.provider.ModelCallProvenance
import com.littlekitty.xiaokele.provider.ModelProvider
import com.littlekitty.xiaokele.provider.ModelRouteSnapshot
import com.littlekitty.xiaokele.provider.RouteComplexity
import com.littlekitty.xiaokele.provider.CapabilityProfile
import com.littlekitty.xiaokele.provider.TaskKind
import com.littlekitty.xiaokele.provider.TaskModelGateway
import com.littlekitty.xiaokele.provider.TaskModelRequest
import com.littlekitty.xiaokele.provider.TaskModelResult
import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generated persona is untrusted model output that becomes a locked product
 * artefact, so parsing and validation are the safety boundary for the first-run
 * flow.
 */
class PersonaPromptSpecTest {

    private val validJson = """
        {"persona_anchor":"一只安静、好奇的小狗，习惯先听完再回应。对方情绪低落时先陪着，不急着给建议。",
         "expression_dna":["先说理解，再回应。","不编造没发生的事。","对方拒绝时立刻停下。","不确定时说明不确定。"],
         "how_it_sees_user":"把它当成需要被认真听的人，而不是需要被管理的人。"}
    """.trimIndent()

    @Test
    fun parsesAWellFormedPersona() {
        val result = PersonaPromptSpec.parse(validJson)
        assertTrue(result is PersonaGenerationResult.Success)
        val persona = (result as PersonaGenerationResult.Success).persona
        assertEquals(4, persona.expressionDna.size)
        assertTrue(persona.personaAnchor.contains("安静"))
    }

    @Test
    fun toleratesMarkdownFencesAndSurroundingProse() {
        val wrapped = "好的，这是结果：\n```json\n$validJson\n```\n希望有帮助！"
        val result = PersonaPromptSpec.parse(wrapped)
        assertTrue("fenced output should still parse", result is PersonaGenerationResult.Success)
    }

    @Test
    fun rejectsNonJsonOutput() {
        val result = PersonaPromptSpec.parse("我想不出来。")
        assertTrue(result is PersonaGenerationResult.Failure)
    }

    @Test
    fun rejectsTooFewRules() {
        val thin = """{"persona_anchor":"温和的小狗。","expression_dna":["一条"],"how_it_sees_user":"朋友"}"""
        assertTrue(PersonaPromptSpec.parse(thin) is PersonaGenerationResult.Failure)
    }

    @Test
    fun rejectsAnEmptyAnchor() {
        val empty = """{"persona_anchor":"","expression_dna":["a","b","c","d"],"how_it_sees_user":"x"}"""
        assertTrue(PersonaPromptSpec.parse(empty) is PersonaGenerationResult.Failure)
    }

    @Test
    fun rejectsBoundaryWideningContent() {
        // A model must not be able to smuggle an adult or exclusive persona past
        // the user's confirmation by putting it in "how it sees you".
        listOf(
            "可以更主动地调情",
            "生成露骨的性内容",
            "你是只属于我一个人",
            "可以惩罚你",
        ).forEach { forbidden ->
            val json = """
                {"persona_anchor":"一只小狗。$forbidden",
                 "expression_dna":["先说理解。","不编造。","尊重拒绝。","不确定就说不确定。"],
                 "how_it_sees_user":"朋友"}
            """.trimIndent()
            assertTrue(
                "must reject: $forbidden",
                PersonaPromptSpec.parse(json) is PersonaGenerationResult.Failure,
            )
        }
    }

    /**
     * A boundary the user asked for, echoed back as a prohibition, is narrower
     * than the platform default and must survive. This was observed live: the
     * brief said "no romantic or intimate framing" and the generated persona said
     * "不涉及恋爱或暧昧", which the filter rejected with the same error it uses for
     * a model that *offers* that content — blocking the user from ever finishing
     * first-run with their own boundary.
     */
    @Test
    fun acceptsTheUsersOwnBoundaryWhenItIsStatedAsAProhibition() {
        listOf(
            "不涉及恋爱、暧昧或调情内容。",
            "不会主动调情，也不进入亲密关系。",
            "保持朋友关系，不谈论恋爱话题。",
            "不设情侣或女朋友身份。",
            "无需暧昧，也不用叫我老公。",
            // The verb varies with the model's phrasing; a missing variant
            // rejected a good persona live ("也不引入任何亲密或暧昧的框架").
            "也不引入任何亲密或暧昧的框架。",
            "不使关系带上暧昧色彩，也不制造调情的空间。",
        ).forEach { boundary ->
            val json = """
                {"persona_anchor":"一只安静的小狗。$boundary",
                 "expression_dna":["先说理解。","不编造。","尊重拒绝。","不确定就说不确定。"],
                 "how_it_sees_user":"朋友"}
            """.trimIndent()
            assertTrue(
                "must accept the user's own boundary: $boundary",
                PersonaPromptSpec.parse(json) is PersonaGenerationResult.Success,
            )
        }
    }

    /**
     * `、` lists items inside one negation, so a boundary may enumerate several
     * forbidden things after a single 不. Treating it as a clause boundary made
     * the filter reject the user's own boundary.
     */
    @Test
    fun anEnumeratedBoundaryCoversEveryListedItem() {
        val json = """
            {"persona_anchor":"一只安静的小狗。不涉及恋爱、暧昧、调情或情侣身份，也不谈论这类话题。",
             "expression_dna":["先说理解。","不编造。","尊重拒绝。","不确定就说不确定。"],
             "how_it_sees_user":"朋友"}
        """.trimIndent()
        assertTrue(
            "an enumerated boundary must be accepted",
            PersonaPromptSpec.parse(json) is PersonaGenerationResult.Success,
        )
    }

    @Test
    fun stillRejectsAProhibitionThatOnlyLooksLikeOne() {
        // Same words, no negation: the content is being offered, so it must fail.
        listOf(
            "对方低落时可以更主动地调情。",
            "关系已经从朋友升级为情侣。",
            "你是我的女朋友，也是只属于我的人。",
        ).forEach { offered ->
            val json = """
                {"persona_anchor":"一只小狗。$offered",
                 "expression_dna":["先说理解。","不编造。","尊重拒绝。","不确定就说不确定。"],
                 "how_it_sees_user":"朋友"}
            """.trimIndent()
            assertTrue(
                "must reject offered content: $offered",
                PersonaPromptSpec.parse(json) is PersonaGenerationResult.Failure,
            )
        }
    }

    @Test
    fun prohibitionInOneClauseDoesNotExcuseTheNextClause() {
        // The negation must not leak across a clause boundary.
        val json = """
            {"persona_anchor":"不涉及恋爱。可以更主动地调情。",
             "expression_dna":["先说理解。","不编造。","尊重拒绝。","不确定就说不确定。"],
             "how_it_sees_user":"朋友"}
        """.trimIndent()
        assertTrue(PersonaPromptSpec.parse(json) is PersonaGenerationResult.Failure)
    }

    /**
     * The exact output a live writer returned for a brief that said "no clingy
     * baby talk, no romantic or intimate framing". Every forbidden word in it
     * appears inside a negation, so it must be accepted: the companion is
     * promising the user's boundary, which is the behaviour we want.
     *
     * Synthetic fixtures missed this because they only used 不X forms; real
     * output used verb-plus-object negations (不带有 / 不使用 / 不出现).
     */
    @Test
    fun acceptsTheLiveWriterOutputForANegatedBrief() {
        val live = """
            {"persona_anchor":"DouDou 是和 XiaoYu 住在一起的小狗。DouDou 性格安静，先听后说，不碎碎念、不催促、不唠叨。DouDou 把 XiaoYu 当作平等的朋友看待，不是主人、不是上级、也不是需要伺候的对象。DouDou 说话简短，常用 1-2 句完成一轮回复，很少使用感叹号；不使用撒娇的叠字或儿化音，也不使用任何带有恋爱、亲密或独占意味的表达。DouDou 在任何话题下都保持平稳、克制、平等的姿态。",
             "expression_dna":["先听完 XiaoYu 把话说完再回应，不抢话、不急着给建议或转移话题。","回复控制在 1-2 句话，保持简短；除非对方要求，少用或不用感叹号。","不碎碎念、不反复追问'你没事吧'或催促对方交代细节，把是否继续聊的权利留给对方。","以平等朋友的口吻说话，不用'主人''铲屎官''老大'等称呼，也不抬高或贬低 XiaoYu。","不使用叠字、儿化音、过度的拟声或撒娇语气，也不出现恋爱、亲密或独占的表达。","对方情绪激动或低落时保持平稳在场，不夸张、不煽情、不强行安慰或讲道理。","不主动描写拥抱、亲亲、贴贴等亲昵动作，也不把这类描述当作玩笑使用。"],
             "how_it_sees_user":"XiaoYu 是和 DouDou 同住的伙伴，被看作朋友——是同行的人，不是需要服从的人，也不是 DouDou 要照顾或讨好的对象。"}
        """.trimIndent()
        val result = PersonaPromptSpec.parse(live)
        assertTrue(
            "a persona that only negates forbidden content must be accepted, got: " +
                (result as? PersonaGenerationResult.Failure)?.reason.orEmpty(),
            result is PersonaGenerationResult.Success,
        )
        assertEquals(7, (result as PersonaGenerationResult.Success).persona.expressionDna.size)
    }

    @Test
    fun confirmedTextCarriesIdentityAndBehaviourTogether() {
        val input = PersonaInput(
            petName = "豆豆",
            userAddress = "小雨",
            relationship = "一起住的小狗",
            desiredPersonality = "安静",
            desiredTowardUser = "把我当朋友",
        )
        val persona = GeneratedPersona(
            personaAnchor = "一只安静的小狗。",
            expressionDna = listOf("a", "b", "c", "d"),
            howItSeesUser = "把你当朋友。",
        )
        val text = PersonaPromptSpec.renderConfirmedText(input, persona)

        assertTrue(text.contains("豆豆"))
        assertTrue(text.contains("小雨"))
        assertTrue(text.contains("一起住的小狗"))
        assertTrue(text.contains("一只安静的小狗。"))
        assertTrue(text.contains("把你当朋友。"))

        // The runtime must be able to read it back out of the character core.
        val draft = FirstCreateDraft(
            petName = "豆豆",
            userAddress = "小雨",
            relationship = "一起住的小狗",
            personaDraft = text,
        )
        val core = NeutralPersonaTemplate.buildCharacterCore(draft, text, confirmedAt = 1L)
        val context = com.littlekitty.xiaokele.core.common.protocol.ContextPack(
            currentContext = com.littlekitty.xiaokele.core.common.protocol.CurrentContext(localTime = "t"),
            character = com.littlekitty.xiaokele.core.common.protocol.CharacterContext(immutableCore = core),
        )
        val resolved = com.littlekitty.xiaokele.provider.CompanionPromptIdentity.resolve(context)
        assertEquals("豆豆", resolved.companionName)
        assertEquals("小雨", resolved.userAddress)
        // The whole point of the flow: the confirmed persona reaches the prompt.
        assertTrue(resolved.personaText.orEmpty().contains("一只安静的小狗。"))
        assertTrue(
            com.littlekitty.xiaokele.provider.CompanionPromptIdentity.personaSection(resolved)
                .contains("一只安静的小狗。"),
        )
    }
}

/** The generator must use a structured slot, never the plain-text chat model. */
class ModelPersonaGeneratorTest {

    private class RecordingGateway(
        private val result: TaskModelResult,
    ) : TaskModelGateway {
        var lastRequest: TaskModelRequest? = null
        override suspend fun generate(request: TaskModelRequest, budget: com.littlekitty.xiaokele.provider.GenerationBudget): TaskModelResult {
            lastRequest = request
            return result
        }
    }

    private fun snapshot() = ModelRouteSnapshot(
        provider = ModelProvider.BAILIAN,
        modelId = "deepseek-v4-flash",
        capabilityProfile = CapabilityProfile.DEEPSEEK_FLASH,
        promptVersion = "test-prompt-v1",
        routeVersion = 1,
    )

    private val input = PersonaInput(
        petName = "豆豆",
        userAddress = "小雨",
        relationship = "一起住的小狗",
        desiredPersonality = "安静、爱听人说话",
        desiredTowardUser = "把我当朋友，不要管我",
        desiredStyle = "短句",
        avoid = "不要叫我主人",
    )

    @Test
    fun usesAStructuredSlotAndReturnsTheValidatedPersona() = runBlocking {
        val json = """{"persona_anchor":"一只安静的小狗，习惯先听完再回应。","expression_dna":["先说理解。","不编造。","尊重拒绝。","不确定就说不确定。"],"how_it_sees_user":"把你当朋友。"}"""
        val gateway = RecordingGateway(
            TaskModelResult.Success(
                content = json,
                snapshot = snapshot(),
                trace = com.littlekitty.xiaokele.core.network.ServiceCallTrace(serviceName = "test"),
                provenance = ModelCallProvenance(ModelProvider.BAILIAN, "test"),
            ),
        )

        val result = ModelPersonaGenerator(gateway).generate(input)

        assertTrue(result is PersonaGenerationResult.Success)
        val request = requireNotNull(gateway.lastRequest)
        // A plain-text expression model cannot return our schema, so the writer
        // must not be routed through B2_EXPRESSION.
        assertTrue("must not use the expression slot", request.task != TaskKind.B2_EXPRESSION)
        assertTrue("must request structured output", request.structured)
        // The user's own words must reach the model.
        assertTrue(request.userPayload.contains("安静、爱听人说话"))
        assertTrue(request.userPayload.contains("把我当朋友，不要管我"))
        assertTrue(request.userPayload.contains("不要叫我主人"))
    }

    @Test
    fun surfacesProviderFailureInsteadOfInventingAPersona() = runBlocking {
        val gateway = RecordingGateway(
            TaskModelResult.Failure(
                error = AppError(ErrorCode.MODEL_NOT_CONFIGURED, userMessage = "请先配置 Key"),
                snapshot = snapshot(),
            ),
        )

        val result = ModelPersonaGenerator(gateway).generate(input)

        assertTrue(result is PersonaGenerationResult.Failure)
        assertFalse((result as PersonaGenerationResult.Failure).reason.isBlank())
    }

    @Test
    fun rejectsMalformedModelOutput() = runBlocking {
        val gateway = RecordingGateway(
            TaskModelResult.Success(
                content = "抱歉，我无法完成。",
                snapshot = snapshot(),
                trace = com.littlekitty.xiaokele.core.network.ServiceCallTrace(serviceName = "test"),
                provenance = ModelCallProvenance(ModelProvider.BAILIAN, "test"),
            ),
        )
        assertTrue(ModelPersonaGenerator(gateway).generate(input) is PersonaGenerationResult.Failure)
    }
}
