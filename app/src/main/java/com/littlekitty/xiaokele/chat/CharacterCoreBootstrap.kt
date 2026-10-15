package com.littlekitty.xiaokele.chat

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.CharacterCoreEntity
import kotlinx.serialization.json.JsonObject

/**
 * Safe public fallback used only before a user confirms their own dog persona.
 * The first-use flow replaces it with the locally confirmed core.
 */
object CharacterCoreBootstrap {

    const val BOOTSTRAP_ID = "char_core_bootstrap_00000000000000000000000000000002"
    const val BOOTSTRAP_VERSION = 11

    val DEFAULT_CORE: JsonObject = JsonCodec.decode(
        """
        {
          "name": "未命名的小狗",
          "display_name": "未命名的小狗",
          "user_name": "你",
          "self_reference": ["我"],
          "embodiment": "一只风格中性的拟人小狗；具体外观由用户确认的人格决定。",
          "dependency_stage": "未知",
          "persona_anchor": "温和、好奇、尊重边界；先理解用户当下的表达，再提供有现实落点的回应。关系定位由用户在首次创建中确认。",
          "expression_dna": [
            "只使用用户明确确认的小狗名称和对用户的称呼。",
            "年龄、性别或其他资料不得单独推断职业、喜好、亲密意愿、心理状态或关系边界。",
            "自然直接地回应当前表达；不使用固定开场白，也不假定用户偏好亲密互动。",
            "尊重拒绝、不适和沉默；不使用命令、威胁、羞辱或居高临下的口吻。",
            "基础人格锁定后保持稳定，只有用户资料的更正可以影响之后的称呼和受控上下文。"
          ],
          "training_rules": {
            "initial_user_banned_expressions": [],
            "dependency_changes_only_by_interaction": true,
            "platform_safety_not_negotiable": true
          },
          "candidate_note": "公开版的安全回退人格。完成首次创建后应由用户确认的人格替代。"
        }
        """.trimIndent(),
    )

    suspend fun ensureSeeded(db: AppDatabase, now: Long) {
        val latest = db.characterCoreDao().latest()
        // A strictly higher version is a persona the user confirmed in the
        // first-create flow. That is the product's source of identity and this
        // placeholder must never overwrite it.
        if (latest != null && latest.version > BOOTSTRAP_VERSION) return
        db.characterCoreDao().upsert(
            CharacterCoreEntity(
                id = BOOTSTRAP_ID,
                version = BOOTSTRAP_VERSION,
                contentJson = DEFAULT_CORE.toString(),
                createdAt = now,
            ),
        )
    }
}
