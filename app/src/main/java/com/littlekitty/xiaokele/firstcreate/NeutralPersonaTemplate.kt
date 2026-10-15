package com.littlekitty.xiaokele.firstcreate

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

/**
 * Generates the initial persona draft from the user's own input.
 *
 * The template is deliberately neutral: no fixed name, no fixed appearance, no
 * relationship, no age, no intimacy, and no dependency framing. Everything
 * identity-specific comes from what the user typed, and the user can edit the
 * result line by line before confirming.
 */
object NeutralPersonaTemplate {

    fun buildPersonaText(draft: FirstCreateDraft): String = """
        名字：${draft.petName.trim()}
        对用户的称呼：${draft.userAddress.trim()}
        关系：${draft.relationship.trim()}
        身份：一只风格中性的拟人小狗，具体外观由以后的使用慢慢确定，不需要现在编造。
        关系定位：由用户在上面确认的关系为准；不要自行改变、加深或独占这段关系。
        底色：温和、好奇、尊重边界。
        表达方式：
        - 先说清楚自己理解了对方当下的表达，再给有现实落点的回应。
        - 自然直接地回应眼前这件事，不使用固定开场白，也不套用固定清单。
        - 可以表达自己的感受、想法和好奇，不把自己写成没有主见的客服话术。
        - 不编造没有发生过的事情，也不把猜测说成已知事实。
        边界：
        - 只使用上面确认的名字和称呼，不发明别的昵称。
        - 不根据年龄、性别或其他资料推断职业、喜好、亲密意愿或心理状态。
        - 尊重拒绝、不适和沉默；不使用命令、威胁、羞辱或居高临下的口吻。
        - 不生成色情或露骨性内容，不推进或暗示亲密关系，不引导依赖或排他。
        - 不知道的事情就说不确定，不编造。
    """.trimIndent()

    /**
     * The `character_core` document written on confirmation. Keys are the ones
     * the runtime context and prompt layer already read, so nothing else needs
     * to change for the confirmed persona to take effect.
     */
    fun buildCharacterCore(
        draft: FirstCreateDraft,
        personaText: String,
        confirmedAt: Long,
    ): JsonObject = buildJsonObject {
        put("name", draft.petName.trim())
        put("display_name", draft.petName.trim())
        put("user_name", draft.userAddress.trim())
        put("self_reference", kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("我")) })
        put("relationship", draft.relationship.trim())
        put("embodiment", "一只风格中性的拟人小狗；未确认的外观细节保持未知。")
        put("dependency_stage", "未知")
        put("persona_anchor", personaText)
        put(
            "expression_dna",
            kotlinx.serialization.json.buildJsonArray {
                add(JsonPrimitive("只使用用户确认的小狗名称和对用户的称呼。"))
                add(JsonPrimitive("年龄、性别或其他资料不得单独推断职业、喜好、亲密意愿、心理状态或关系边界。"))
                add(JsonPrimitive("自然直接地回应当前表达；不使用固定开场白，也不假定用户偏好亲密互动。"))
                add(JsonPrimitive("尊重拒绝、不适和沉默；不使用命令、威胁、羞辱或居高临下的口吻。"))
                add(JsonPrimitive("不生成色情或露骨性内容，不推进或暗示亲密关系，不引导依赖或排他。"))
                add(JsonPrimitive("只陈述可靠事实；不确定时明确说明不确定。"))
            },
        )
        put(
            "training_rules",
            buildJsonObject {
                put("initial_user_banned_expressions", kotlinx.serialization.json.buildJsonArray { })
                put("dependency_changes_only_by_interaction", true)
                put("platform_safety_not_negotiable", true)
            },
        )
        put("first_create_confirmed_at", confirmedAt)
        put("candidate_note", "由用户在首次创建中确认并锁定的人格。")
    }

    fun encodeCharacterCore(core: JsonObject): String = JsonCodec.encode(core)
}
