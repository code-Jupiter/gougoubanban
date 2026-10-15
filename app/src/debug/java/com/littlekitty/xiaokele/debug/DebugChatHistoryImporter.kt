package com.littlekitty.xiaokele.debug

import androidx.room.withTransaction
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Serializable
data class DebugChatHistoryDocument(
    @SerialName("schema_version")
    val schemaVersion: Int,
    val messages: List<DebugChatHistoryMessage>,
)

@Serializable
data class DebugChatHistoryMessage(
    val role: String,
    val content: String,
    @SerialName("created_at")
    val createdAt: String,
)

sealed interface DebugChatImportResult {
    val description: String

    data class Success(
        val insertedCount: Int,
        val skippedCount: Int,
        val firstCreatedAt: String,
        val lastCreatedAt: String,
    ) : DebugChatImportResult {
        override val description: String
            get() = "导入完成：新增 $insertedCount 条，跳过 $skippedCount 条重复记录；" +
                "时间范围 $firstCreatedAt 至 $lastCreatedAt。"
    }

    data class Failure(override val description: String) : DebugChatImportResult
}

/**
 * Debug-only, append-only chat-history importer.
 *
 * The whole document is decoded and validated before Room is touched. Inserts
 * then run in one transaction, so a malformed row can never leave a partial
 * simulated history behind. Stable IDs make importing the same document twice
 * idempotent without replacing messages that were created by the app.
 */
class DebugChatHistoryImporter(private val db: AppDatabase) {

    suspend fun import(rawJson: String): DebugChatImportResult {
        val document = decodeAndValidate(rawJson)
            ?: return validationFailure(rawJson)
        val rows = document.messages.mapIndexed { index, message ->
            MessageEntity(
                id = stableMessageId(index, message),
                role = message.role,
                content = message.content.trim(),
                createdAt = parseCreatedAt(message.createdAt),
                status = MessageStatuses.COMPLETED,
            )
        }

        return runCatching {
            var inserted = 0
            var skipped = 0
            db.withTransaction {
                rows.forEach { row ->
                    val existing = db.messageDao().byId(row.id)
                    when {
                        existing == null -> {
                            db.messageDao().insert(row)
                            inserted++
                        }
                        existing.role == row.role &&
                            existing.content == row.content &&
                            existing.createdAt == row.createdAt -> skipped++
                        else -> error("检测到稳定 ID 冲突，未写入任何消息。")
                    }
                }
            }
            DebugChatImportResult.Success(
                insertedCount = inserted,
                skippedCount = skipped,
                firstCreatedAt = document.messages.first().createdAt,
                lastCreatedAt = document.messages.last().createdAt,
            )
        }.getOrElse { error ->
            DebugChatImportResult.Failure("导入失败：${error.message ?: "数据库写入失败"}")
        }
    }

    private fun decodeAndValidate(rawJson: String): DebugChatHistoryDocument? {
        if (rawJson.isBlank() || rawJson.length > MAX_JSON_CHARACTERS) return null
        val document = runCatching { JsonCodec.decode<DebugChatHistoryDocument>(rawJson) }.getOrNull()
            ?: return null
        if (document.schemaVersion != SUPPORTED_SCHEMA_VERSION) return null
        if (document.messages.isEmpty() || document.messages.size > MAX_MESSAGES) return null

        var previousTime: Long? = null
        document.messages.forEach { message ->
            if (message.role !in ALLOWED_ROLES) return null
            val content = message.content.trim()
            if (content.isEmpty() || content.length > MAX_CONTENT_CHARACTERS) return null
            val createdAt = runCatching { parseCreatedAt(message.createdAt) }.getOrNull() ?: return null
            if (previousTime != null && createdAt < previousTime!!) return null
            previousTime = createdAt
        }
        return document
    }

    private fun validationFailure(rawJson: String): DebugChatImportResult.Failure {
        if (rawJson.isBlank()) return DebugChatImportResult.Failure("导入失败：JSON 不能为空。")
        if (rawJson.length > MAX_JSON_CHARACTERS) {
            return DebugChatImportResult.Failure("导入失败：JSON 超过 ${MAX_JSON_CHARACTERS / 1_000_000} MB 字符上限。")
        }
        val document = runCatching { JsonCodec.decode<DebugChatHistoryDocument>(rawJson) }
            .getOrElse { return DebugChatImportResult.Failure("导入失败：JSON 结构无效（${it.message?.take(160)}）。") }
        if (document.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            return DebugChatImportResult.Failure("导入失败：仅支持 schema_version=$SUPPORTED_SCHEMA_VERSION。")
        }
        if (document.messages.isEmpty()) return DebugChatImportResult.Failure("导入失败：messages 不能为空。")
        if (document.messages.size > MAX_MESSAGES) {
            return DebugChatImportResult.Failure("导入失败：单次最多 $MAX_MESSAGES 条消息。")
        }
        var previousTime: Long? = null
        document.messages.forEachIndexed { index, message ->
            val row = index + 1
            if (message.role !in ALLOWED_ROLES) {
                return DebugChatImportResult.Failure("导入失败：第 $row 条 role 只能是 USER 或 ASSISTANT。")
            }
            val content = message.content.trim()
            if (content.isEmpty()) return DebugChatImportResult.Failure("导入失败：第 $row 条 content 不能为空。")
            if (content.length > MAX_CONTENT_CHARACTERS) {
                return DebugChatImportResult.Failure("导入失败：第 $row 条 content 超过 $MAX_CONTENT_CHARACTERS 字符。")
            }
            val createdAt = try {
                parseCreatedAt(message.createdAt)
            } catch (_: DateTimeParseException) {
                return DebugChatImportResult.Failure(
                    "导入失败：第 $row 条 created_at 必须是带时区偏移的 ISO-8601 时间，例如 2026-08-01T09:30:00+08:00。",
                )
            }
            if (previousTime != null && createdAt < previousTime!!) {
                return DebugChatImportResult.Failure("导入失败：第 $row 条 created_at 早于上一条；消息必须按时间升序排列。")
            }
            previousTime = createdAt
        }
        return DebugChatImportResult.Failure("导入失败：JSON 未通过校验。")
    }

    private fun parseCreatedAt(value: String): Long =
        OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()

    private fun stableMessageId(index: Int, message: DebugChatHistoryMessage): String {
        val canonical = "$index\u0000${message.role}\u0000${message.createdAt}\u0000${message.content.trim()}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "msg_import_${index.toString(16).padStart(8, '0')}${digest.take(24)}"
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val MAX_MESSAGES = 5_000
        const val MAX_JSON_CHARACTERS = 5_000_000
        const val MAX_CONTENT_CHARACTERS = 20_000
        val ALLOWED_ROLES = setOf("USER", "ASSISTANT")

        val EXAMPLE_JSON: String = """
            {
              "schema_version": 1,
              "messages": [
                {
                  "role": "USER",
                  "content": "示例用户消息",
                  "created_at": "2026-08-01T09:30:00+08:00"
                },
                {
                  "role": "ASSISTANT",
                  "content": "示例 AI 回复",
                  "created_at": "2026-08-01T09:30:05+08:00"
                }
              ]
            }
        """.trimIndent()
    }
}
