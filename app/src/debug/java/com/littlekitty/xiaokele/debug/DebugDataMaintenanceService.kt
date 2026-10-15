package com.littlekitty.xiaokele.debug

import android.content.Context
import androidx.room.withTransaction
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.MemoryCandidate
import com.littlekitty.xiaokele.core.common.protocol.MemoryEvaluation
import com.littlekitty.xiaokele.core.common.protocol.MemoryTypeNames
import com.littlekitty.xiaokele.core.common.protocol.MemoryValidityNames
import com.littlekitty.xiaokele.core.common.protocol.UserProfileLayerNames
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.BehaviorTendencyEntity
import com.littlekitty.xiaokele.core.database.entity.CharacterCoreEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.MemoryEntity
import com.littlekitty.xiaokele.core.database.entity.UserTraitEntity
import com.littlekitty.xiaokele.core.database.state.ActionPlanStatuses
import com.littlekitty.xiaokele.core.database.state.BehaviorTendencyStatuses
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.MemoryStatuses
import com.littlekitty.xiaokele.floating.PendingFloatingBubbleStore
import com.littlekitty.xiaokele.memory.MemoryAnalysisOutcome
import com.littlekitty.xiaokele.memory.MemoryAnalysisProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Chinese-first entry points for the three common manual-memory intents. */
enum class DebugMemoryIntent(
    val label: String,
    val description: String,
    val type: String,
    val validity: String,
) {
    USER_FACT(
        label = "记住关于我的信息",
        description = "长期偏好、经历或关系事实",
        type = MemoryTypeNames.USER_PREFERENCE,
        validity = MemoryValidityNames.LONG_TERM,
    ),
    TEMPORARY_STATE(
        label = "记录我的临时状态",
        description = "近期心情、身体或当前安排",
        type = MemoryTypeNames.TEMPORARY_STATE,
        validity = MemoryValidityNames.TEMPORARY,
    ),
    COMPANIONSHIP_ADVICE(
        label = "相处建议",
        description = "希望以后如何回应",
        type = MemoryTypeNames.USER_PREFERENCE,
        validity = MemoryValidityNames.LONG_TERM,
    ),
}

data class DebugDataSnapshot(
    val messageCount: Int,
    val messages: List<com.littlekitty.xiaokele.core.database.entity.MessageEntity>,
    val conversationSummaryCount: Int,
    val memoryCount: Int,
    val activeMemoryCount: Int,
    val pendingChatEventCount: Int,
)

data class ChatClearReport(
    val messageCount: Int,
    val summaryCount: Int,
    val chatEventCount: Int,
    val cancelledPlanCount: Int,
    val cancelledDeliveryCount: Int,
) {
    val description: String
        get() = "已清空 $messageCount 条消息、$summaryCount 条摘要；" +
            "标记 $chatEventCount 个聊天事件结束，取消 $cancelledDeliveryCount 个待投递项。"
}

data class DebugMemoryWriteReport(
    val message: String,
    val success: Boolean,
)

data class DebugProfileSnapshot(
    val traits: List<UserTraitEntity>,
    val characterCore: CharacterCoreEntity?,
    val tendencies: List<BehaviorTendencyEntity>,
)

/**
 * Owns Debug data mutations so the screen does not know Room table boundaries.
 * Chat deletion intentionally leaves memories, profile rows and diagnostics
 * untouched, while cancelling work that could still surface deleted content.
 */
class DebugDataMaintenanceService(
    private val db: AppDatabase,
    private val memoryAnalysisProcessor: MemoryAnalysisProcessor,
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val chatHistoryImporter = DebugChatHistoryImporter(db)

    suspend fun snapshot(): DebugDataSnapshot = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        DebugDataSnapshot(
            messageCount = db.messageDao().count(),
            messages = db.messageDao().recentAsc(MAX_RECENT_MESSAGES),
            conversationSummaryCount = db.conversationSummaryDao().count(),
            memoryCount = db.memoryDao().count(),
            activeMemoryCount = db.memoryDao().activeAt(now).size,
            pendingChatEventCount = db.eventDao().allAscending().count { it.isChatEvent() && it.status !in EventStatuses.terminal },
        )
    }

    suspend fun clearChat(): ChatClearReport = withContext(Dispatchers.IO) {
        // Preferences are not part of Room's transaction. Clear the durable
        // bubble before and after the database transaction so a stale message
        // cannot be shown even if the process is interrupted around the edge.
        val pendingBubble = PendingFloatingBubbleStore(applicationContext)
        pendingBubble.clear()
        val report = db.withTransaction {
            val chatEvents = db.eventDao().allAscending().filter { it.isChatEvent() }
            var cancelledPlans = 0
            var cancelledDeliveries = 0
            chatEvents.forEach { event ->
                cancelledDeliveries += db.deliveryHistoryDao().cancelReservedReachForEventOrTodo(
                    eventId = event.id,
                    todoId = null,
                    errorCode = CHAT_CLEARED_ERROR,
                )
                cancelledDeliveries += db.deliveryHistoryDao().cancelFailedRetryableReachForEventOrTodo(
                    eventId = event.id,
                    todoId = null,
                    errorCode = CHAT_CLEARED_ERROR,
                )
                db.actionExecutionDao().cancelPendingReachForEventOrTodo(
                    eventId = event.id,
                    todoId = null,
                    errorCode = CHAT_CLEARED_ERROR,
                    now = System.currentTimeMillis(),
                )
                db.actionExecutionDao().cancelFailedRetryableReachExecutionsForEventOrTodo(
                    eventId = event.id,
                    todoId = null,
                    errorCode = CHAT_CLEARED_ERROR,
                    now = System.currentTimeMillis(),
                )
                db.actionPlanDao().forEvent(event.id)
                    .filter { it.status in ActionPlanStatuses.active }
                    .forEach { plan ->
                        cancelledPlans += db.actionPlanDao().updateStatusWithActiveKey(
                            plan.id,
                            ActionPlanStatuses.CANCELLED,
                        )
                    }
                if (event.status !in EventStatuses.terminal) {
                    db.eventDao().markTerminalFailure(
                        id = event.id,
                        errorCode = CHAT_CLEARED_ERROR,
                        errorDetail = "debug_chat_history_cleared",
                    )
                }
            }
            ChatClearReport(
                messageCount = db.messageDao().deleteAll(),
                summaryCount = db.conversationSummaryDao().deleteAll(),
                chatEventCount = chatEvents.size,
                cancelledPlanCount = cancelledPlans,
                cancelledDeliveryCount = cancelledDeliveries,
            )
        }
        pendingBubble.clear()
        report
    }

    suspend fun importChatHistory(rawJson: String): DebugChatImportResult = withContext(Dispatchers.IO) {
        chatHistoryImporter.import(rawJson)
    }

    suspend fun addMemory(content: String, intent: DebugMemoryIntent): DebugMemoryWriteReport =
        withContext(Dispatchers.IO) {
            val normalized = content.trim()
            if (normalized.isBlank()) {
                return@withContext DebugMemoryWriteReport("新增失败：请先输入一段自然语言内容。", false)
            }
            val evaluation = MemoryEvaluation(
                memoryCandidates = listOf(
                    MemoryCandidate(
                        type = intent.type,
                        content = normalized,
                        summary = normalized.take(500),
                        importance = 1.0,
                        confidence = 1.0,
                        validity = intent.validity,
                    ),
                ),
            )
            when (val outcome = memoryAnalysisProcessor.applyRawEvaluation(
                JsonCodec.encode(evaluation),
                correlationId = "debug_manual_memory",
            )) {
                is MemoryAnalysisOutcome.Applied -> {
                    val report = outcome.memoryWriteReport
                    DebugMemoryWriteReport(
                        message = "已保存：新增 ${report.inserted} 条，合并 ${report.merged} 条，" +
                            "向量 ${outcome.embeddingCount} 条${if (outcome.embeddingDegraded) "（检索降级）" else ""}。",
                        success = true,
                    )
                }
                is MemoryAnalysisOutcome.Failure -> DebugMemoryWriteReport(
                    message = "新增失败：${outcome.error.technicalDetail ?: outcome.error.code.name}",
                    success = false,
                )
            }
        }

    suspend fun memories(): List<MemoryEntity> = withContext(Dispatchers.IO) {
        db.memoryDao().all()
    }

    suspend fun archiveMemory(id: String): Boolean = withContext(Dispatchers.IO) {
        db.memoryDao().transition(id, MemoryStatuses.ACTIVE, MemoryStatuses.ARCHIVED, System.currentTimeMillis()) == 1
    }

    suspend fun deleteMemory(id: String): Boolean = withContext(Dispatchers.IO) {
        db.withTransaction {
            db.memoryEmbeddingDao().deleteForMemory(id)
            db.memoryDao().deleteById(id) == 1
        }
    }

    /** Restores an archived row by creating a fresh ACTIVE memory through the normal validator. */
    suspend fun restoreMemory(id: String): Boolean = withContext(Dispatchers.IO) {
        val archived = db.memoryDao().byId(id)?.takeIf { it.status != MemoryStatuses.ACTIVE } ?: return@withContext false
        val validUntil = archived.validUntil
        val validity = when {
            validUntil != null && validUntil <= System.currentTimeMillis() -> MemoryValidityNames.SHORT_TERM
            validUntil != null -> MemoryValidityNames.SHORT_TERM
            else -> MemoryValidityNames.LONG_TERM
        }
        val evaluation = MemoryEvaluation(
            memoryCandidates = listOf(
                MemoryCandidate(
                    type = archived.type,
                    subject = archived.subject,
                    content = archived.content,
                    summary = archived.summary,
                    importance = archived.importance,
                    confidence = archived.confidence,
                    validity = validity,
                ),
            ),
        )
        memoryAnalysisProcessor.applyRawEvaluation(
            JsonCodec.encode(evaluation),
            correlationId = "debug_restore_memory_${EntityId.generate("memory").value}",
        ) is MemoryAnalysisOutcome.Applied
    }

    suspend fun profileSnapshot(): DebugProfileSnapshot = withContext(Dispatchers.IO) {
        DebugProfileSnapshot(
            traits = db.userTraitDao().all().filter { it.status == "ACTIVE" },
            characterCore = db.characterCoreDao().latest(),
            tendencies = db.behaviorTendencyDao().all().filter { it.status == BehaviorTendencyStatuses.ACTIVE },
        )
    }

    suspend fun saveProfileTrait(key: String, content: String, layer: String): String = withContext(Dispatchers.IO) {
        val normalizedKey = key.trim()
        val normalizedContent = content.trim()
        if (normalizedKey.isBlank() || normalizedContent.isBlank()) return@withContext "画像保存失败：名称和内容都不能为空。"
        val now = System.currentTimeMillis()
        val old = db.userTraitDao().byKey(normalizedKey)
        db.userTraitDao().upsert(
            (old ?: UserTraitEntity(
                id = EntityId.generate("trait").value,
                key = normalizedKey,
                valueJson = "{}",
                profileLayer = layer,
                status = "ACTIVE",
                updatedAt = now,
            )).copy(
                valueJson = buildJsonObject { put("text", normalizedContent) }.toString(),
                confidence = 1.0,
                profileLayer = if (layer == UserProfileLayerNames.CORE) UserProfileLayerNames.CORE else UserProfileLayerNames.DYNAMIC,
                status = "ACTIVE",
                updatedAt = now,
            ),
        )
        "画像已保存：$normalizedKey"
    }

    suspend fun saveCharacterCore(name: String, content: String): String = withContext(Dispatchers.IO) {
        val normalizedName = name.trim()
        val normalizedContent = content.trim()
        if (normalizedName.isBlank() || normalizedContent.isBlank()) return@withContext "人格保存失败：名称和内容都不能为空。"
        val nextVersion = (db.characterCoreDao().latest()?.version ?: 0) + 1
        db.characterCoreDao().upsert(
            CharacterCoreEntity(
                id = EntityId.generate("character").value,
                version = nextVersion,
                contentJson = buildJsonObject {
                    put("name", normalizedName)
                    put("content", normalizedContent)
                }.toString(),
                createdAt = System.currentTimeMillis(),
            ),
        )
        "人格设定已保存：$normalizedName（版本 $nextVersion）"
    }

    suspend fun saveTendency(key: String, content: String): String = withContext(Dispatchers.IO) {
        val normalizedKey = key.trim()
        val normalizedContent = content.trim()
        if (normalizedKey.isBlank() || normalizedContent.isBlank()) return@withContext "行为倾向保存失败：名称和内容都不能为空。"
        val now = System.currentTimeMillis()
        val old = db.behaviorTendencyDao().forKey(normalizedKey).firstOrNull { it.status == BehaviorTendencyStatuses.ACTIVE }
        db.behaviorTendencyDao().upsert(
            (old ?: BehaviorTendencyEntity(
                id = EntityId.generate("tendency").value,
                key = normalizedKey,
                status = BehaviorTendencyStatuses.ACTIVE,
                updatedAt = now,
            )).copy(
                currentValue = normalizedContent,
                originalValue = old?.originalValue ?: normalizedContent,
                status = BehaviorTendencyStatuses.ACTIVE,
                updatedAt = now,
            ),
        )
        "行为倾向已保存：$normalizedKey"
    }

    private fun EventEntity.isChatEvent(): Boolean =
        (type == "USER_MESSAGE" && source == "CHAT_INPUT") ||
            (type == "IDLE_FOLLOW_UP" && source == "CHAT_IDLE_FOLLOW_UP")

    private companion object {
        const val MAX_RECENT_MESSAGES = 12
        const val CHAT_CLEARED_ERROR = "DEBUG_CHAT_HISTORY_CLEARED"
    }
}
