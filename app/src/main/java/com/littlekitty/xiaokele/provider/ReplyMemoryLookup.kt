package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.memory.MemoryQuery
import com.littlekitty.xiaokele.core.database.memory.MemorySearch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun interface ReplyMemoryLookup {
    suspend fun search(query: String): JsonArray
}

/** Read-only, bounded supplement. A memory hit is not itself proof of its contents. */
class SourceGroundedReplyMemoryLookup(
    private val db: AppDatabase,
    private val search: MemorySearch,
) : ReplyMemoryLookup {
    override suspend fun search(query: String): JsonArray {
        val items = mutableListOf<JsonObject>()
        var characters = 0
        for (hit in search.search(MemoryQuery(text = query.take(1_000), limit = 4)).take(4)) {
            val source = hit.memory.sourceMessageId?.let { db.messageDao().byId(it) } ?: continue
            if (source.errorCode == CompanionFallback.MESSAGE_MARKER || source.status != "COMPLETED") continue
            // Never cut a source in the middle and pretend the excerpt is complete.
            if (source.content.length > 4_000 || hit.memory.content.length > 2_000) continue
            val item = buildJsonObject {
                put("id", hit.memory.id)
                put("content", hit.memory.content)
                put("source_message_id", source.id)
                put("source_role", source.role)
                put("source_text", source.content)
                put("created_at", hit.memory.createdAt)
                hit.memory.validUntil?.let { put("valid_until", it) }
            }
            characters += item.toString().length
            if (characters > 12_000) break
            items += item
        }
        return JsonArray(items)
    }
}
