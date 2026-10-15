package com.littlekitty.xiaokele.chat

import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.database.state.PetStateIds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId

/** A deterministic, local-only daily life anchor so the character does not invent a new day on every turn. */
object PetDailyLifeContext {
    private val ordinaryActivities = listOf(
        "把窝边的小东西整理了一遍，让常用的东西都待在顺手的位置",
        "在窗边待了一会儿，观察今天光线变化得有多快",
        "研究怎样把手边的事情安排得更舒服，最后挪出一个不妨碍活动的角落",
        "把常用的小物件重新排了一遍，又趴下来想今天会有什么新鲜事",
    )

    fun build(now: Instant, zone: ZoneId, petState: String): JsonObject {
        val date = now.atZone(zone).toLocalDate()
        val activity = when (petState) {
            PetStateIds.WORK_GUARD -> "安静守在旁边，尽量不制造打扰"
            PetStateIds.WELCOME_BACK -> "听见回来的动静后立刻跑去迎接"
            PetStateIds.CARING -> "把手边的小事放下，认真留意对方现在的情绪和处境"
            PetStateIds.SLEEPING, PetStateIds.CURLED_UP -> "放松下来，团在舒服的位置安静休息了一阵"
            else -> ordinaryActivities[Math.floorMod(date.toEpochDay(), ordinaryActivities.size.toLong()).toInt()]
        }
        val mood = when (petState) {
            PetStateIds.WORK_GUARD -> "安静专注"
            PetStateIds.WELCOME_BACK -> "明显开心"
            PetStateIds.CARING -> "认真关心"
            PetStateIds.EXPECTING, PetStateIds.WAITING -> "有点期待"
            PetStateIds.SLEEPING, PetStateIds.CURLED_UP -> "放松"
            else -> "平静亲近"
        }
        val expiresAt = date.plusDays(1).atStartOfDay(zone).toInstant()
        return buildJsonObject {
            put("date", date.toString())
            put("activity", activity)
            put("mood", mood)
            put("next_intention", "等对方愿意聊时，听听今天最想分享的一件事")
            put("source", "LOCAL_DAILY_CANON")
            put("expires_at", InstantJson.formatInZone(expiresAt, zone))
        }
    }
}
