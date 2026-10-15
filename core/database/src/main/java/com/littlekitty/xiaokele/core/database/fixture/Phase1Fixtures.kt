package com.littlekitty.xiaokele.core.database.fixture

import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.ActionExecutionEntity
import com.littlekitty.xiaokele.core.database.entity.ActionPlanEntity
import com.littlekitty.xiaokele.core.database.entity.BehaviorTendencyEntity
import com.littlekitty.xiaokele.core.database.entity.CharacterCoreEntity
import com.littlekitty.xiaokele.core.database.entity.ConversationSummaryEntity
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.ExternalCacheEntity
import com.littlekitty.xiaokele.core.database.entity.MemoryEmbeddingEntity
import com.littlekitty.xiaokele.core.database.entity.MemoryEntity
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.entity.PetStateEntity
import com.littlekitty.xiaokele.core.database.entity.ScenarioPolicyEntity
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.entity.TriggerEntity
import com.littlekitty.xiaokele.core.database.entity.UserTraitEntity

/**
 * Deterministic Phase 1 debug/test fixtures. They exercise every local fact
 * source table with fake data only: no model, weather, GPS, shift or any other
 * real external service is contacted (C13 boundary).
 */
object Phase1Fixtures {

    const val FIXTURE_EVENT_ID = "evt_00000000000000000000000000000001"
    const val FIXTURE_TODO_ID = "todo_00000000000000000000000000000001"

    private const val ID_SUFFIX = "00000000000000000000000000000001"

    private fun id(prefix: String): String = "${prefix}_$ID_SUFFIX"

    suspend fun seed(db: AppDatabase, now: Long = 1_756_400_000_000L) {
        val dayMillis = 24 * 60 * 60 * 1_000L

        db.messageDao().upsert(
            MessageEntity(
                id = id("msg"),
                role = "USER",
                content = "Phase 1 fixture 消息：用于验证本地持久化与进程重建恢复。",
                createdAt = now,
                sourceEventId = FIXTURE_EVENT_ID,
                sourceTodoId = FIXTURE_TODO_ID,
                animationId = null,
            ),
        )
        db.conversationSummaryDao().upsert(
            ConversationSummaryEntity(
                id = id("csum"),
                rangeStartMessageId = id("msg"),
                rangeEndMessageId = id("msg"),
                summary = "Phase 1 fixture 会话摘要",
                createdAt = now,
            ),
        )
        db.eventDao().upsert(
            EventEntity(
                id = FIXTURE_EVENT_ID,
                eventKey = "fixture:test_event:phase1:0001",
                type = "TEST_EVENT",
                source = "DEBUG_FIXTURE",
                payloadJson = """{"fixture":true}""",
                occurredAt = now,
                receivedAt = now,
                expiresAt = now + dayMillis,
                priority = 50,
                status = "RECEIVED",
            ),
        )
        db.scenarioPolicyDao().upsert(
            ScenarioPolicyEntity(
                id = id("policy"),
                eventType = "TEST_EVENT",
                mode = "GUIDED",
                matcherJson = """{"type":"TEST_EVENT"}""",
                promptTemplate = "这是 Phase 1 fixture 策略样例，不调用真实模型。",
                allowedActionsJson = """["NO_ACTION"]""",
                version = 1,
            ),
        )
        db.todoDao().upsert(
            TodoEntity(
                id = FIXTURE_TODO_ID,
                type = "DISCUSS_LATER",
                title = "Phase 1 fixture 待聊话题",
                payloadJson = """{"topic":"本地事实源恢复"}""",
                sourceEventId = FIXTURE_EVENT_ID,
                status = "READY",
                priority = 10,
                availableAt = now,
                expiresAt = now + dayMillis,
                createdAt = now,
                updatedAt = now,
            ),
        )
        db.triggerDao().upsert(
            TriggerEntity(
                id = id("trg"),
                todoId = FIXTURE_TODO_ID,
                triggerType = "TIME",
                triggerConfigJson = """{"delay":"P1D"}""",
                nextFireAt = now + dayMillis,
                status = "ACTIVE",
            ),
        )
        db.memoryDao().upsert(
            MemoryEntity(
                id = id("mem"),
                type = "USER_PREFERENCE",
                subject = "Phase 1 fixture",
                content = "用户偏好通过本地记忆表恢复。",
                summary = "fixture memory",
                importance = 0.8,
                confidence = 0.9,
                validFrom = now,
                validUntil = now + 30 * dayMillis,
                sourceMessageId = id("msg"),
                sourceEventId = FIXTURE_EVENT_ID,
                status = "ACTIVE",
                createdAt = now,
                updatedAt = now,
            ),
        )
        db.memoryEmbeddingDao().upsert(
            MemoryEmbeddingEntity(
                memoryId = id("mem"),
                modelId = "fixture-embedding-v1",
                dimension = 8,
                vectorBlob = ByteArray(8) { it.toByte() },
                createdAt = now,
            ),
        )
        db.userTraitDao().upsert(
            UserTraitEntity(
                id = id("trait"),
                key = "fixture.taste",
                valueJson = """{"prefers":"local-first"}""",
                confidence = 0.8,
                evidenceMemoryIdsJson = """["${id("mem")}"]""",
                status = "ACTIVE",
                updatedAt = now,
            ),
        )
        db.characterCoreDao().upsert(
            CharacterCoreEntity(
                id = id("char"),
                version = 1,
                contentJson = """{"name":"测试小狗","display_name":"测试小狗","user_name":"测试用户"}""",
                createdAt = now,
            ),
        )
        db.behaviorTendencyDao().upsert(
            BehaviorTendencyEntity(
                id = id("tend"),
                key = "proactive_level",
                originalValue = """{"level":0.6}""",
                currentValue = """{"level":0.6}""",
                suppressionLevel = 0.0,
                status = "ACTIVE",
                updatedAt = now,
            ),
        )
        db.petStateDao().upsert(
            PetStateEntity(
                singletonId = PetStateEntity.SINGLETON_ID,
                stateId = "IDLE",
                startsAt = now,
                updatedAt = now,
            ),
        )
        if (db.actionPlanDao().byId(id("plan")) == null) {
            db.actionPlanDao().insert(
                ActionPlanEntity(
                    id = id("plan"),
                    eventId = FIXTURE_EVENT_ID,
                    agentOutputJson = """{"decision":"NO_ACTION","outputs":[]}""",
                    decisionSummary = "fixture no_action",
                    status = "PENDING",
                    activeEventKey = FIXTURE_EVENT_ID,
                    createdAt = now,
                ),
            )
        }
        db.actionExecutionDao().upsert(
            ActionExecutionEntity(
                id = id("exec"),
                actionPlanId = id("plan"),
                actionType = "NO_ACTION",
                payloadJson = """{}""",
                status = "PENDING",
                errorCode = null,
            ),
        )
        db.deliveryHistoryDao().upsert(
            DeliveryHistoryEntity(
                id = id("dlv"),
                eventId = FIXTURE_EVENT_ID,
                todoId = FIXTURE_TODO_ID,
                channel = "DEBUG",
                contentHash = "fixture-content-hash",
                deliveredAt = now,
            ),
        )
        db.externalCacheDao().upsert(
            ExternalCacheEntity(
                cacheKey = "fixture:cache:1",
                source = "DEBUG_FIXTURE",
                payloadJson = """{"fixture":true}""",
                fetchedAt = now,
                expiresAt = now + dayMillis,
            ),
        )
    }
}

data class Phase1Snapshot(
    val messages: Int,
    val conversationSummaries: Int,
    val events: Int,
    val scenarioPolicies: Int,
    val todos: Int,
    val triggers: Int,
    val memories: Int,
    val memoryEmbeddings: Int,
    val userTraits: Int,
    val characterCore: Int,
    val behaviorTendencies: Int,
    val petStates: Int,
    val actionPlans: Int,
    val actionExecutions: Int,
    val deliveryHistory: Int,
    val externalCache: Int,
)

object Phase1Snapshotter {
    suspend fun snapshot(db: AppDatabase): Phase1Snapshot = Phase1Snapshot(
        messages = db.messageDao().count(),
        conversationSummaries = db.conversationSummaryDao().count(),
        events = db.eventDao().count(),
        scenarioPolicies = db.scenarioPolicyDao().count(),
        todos = db.todoDao().count(),
        triggers = db.triggerDao().count(),
        memories = db.memoryDao().count(),
        memoryEmbeddings = db.memoryEmbeddingDao().count(),
        userTraits = db.userTraitDao().count(),
        characterCore = db.characterCoreDao().count(),
        behaviorTendencies = db.behaviorTendencyDao().count(),
        petStates = db.petStateDao().count(),
        actionPlans = db.actionPlanDao().count(),
        actionExecutions = db.actionExecutionDao().count(),
        deliveryHistory = db.deliveryHistoryDao().count(),
        externalCache = db.externalCacheDao().count(),
    )
}
