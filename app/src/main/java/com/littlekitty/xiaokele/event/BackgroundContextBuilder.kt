package com.littlekitty.xiaokele.event

import com.littlekitty.xiaokele.chat.CharacterCoreBootstrap
import com.littlekitty.xiaokele.chat.PetDailyLifeContext
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.CharacterContext
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.CurrentContext
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.memory.MemoryHit
import com.littlekitty.xiaokele.core.database.memory.MemoryQuery
import com.littlekitty.xiaokele.core.database.memory.MemorySearch
import com.littlekitty.xiaokele.core.database.policy.PolicyGateResult
import com.littlekitty.xiaokele.core.database.repository.BehaviorTendencyRepository
import com.littlekitty.xiaokele.core.database.repository.UserTraitRepository
import com.littlekitty.xiaokele.core.database.state.PetStateIds
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import com.littlekitty.xiaokele.external.ExternalContextResolver
import com.littlekitty.xiaokele.external.toWeatherContextJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Phase 4 background Context Builder (architecture section 17/30).
 * It builds only what the current event needs: character, tendencies, profile,
 * related memories, related todos, recent deliveries, current pet state and
 * the resolved scene policy.
 */
class BackgroundContextBuilder(
    private val db: AppDatabase,
    private val timeProvider: TimeProvider,
    private val memorySearch: MemorySearch? = null,
    private val userTraitRepository: UserTraitRepository? = null,
    private val behaviorTendencyRepository: BehaviorTendencyRepository? = null,
    private val externalContextResolver: ExternalContextResolver? = null,
    private val discoveryKnowledgeProvider: com.littlekitty.xiaokele.discovery.DiscoveryKnowledgeProvider? = null,
) {

    suspend fun buildFor(
        event: EventEntity,
        gate: PolicyGateResult,
        sceneEvaluation: com.littlekitty.xiaokele.scene.SceneEvaluation? = null,
        allowNetworkMemorySearch: Boolean = true,
    ): ContextPack {
        val now = timeProvider.now()
        val nowEpoch = now.toEpochMilli()
        val character = db.characterCoreDao().latest()
        val petState = db.petStateDao().get()
        // Background scene selection must be able to continue the user's
        // recent conversation instead of generating the same scene-specific
        // reminder every time. `recent()` is newest-first, so reverse it for
        // the model's natural chronological reading order.
        val recentMessages = db.messageDao().recent(MAX_RECENT_MESSAGES).asReversed()
        val todos = db.todoDao()
            .byStatuses(listOf(TodoStatuses.CANDIDATE, TodoStatuses.WAITING, TodoStatuses.READY))
            .sortedWith(compareByDescending<TodoEntity> { it.priority }.thenBy { it.availableAt ?: Long.MAX_VALUE })
            .take(MAX_TODOS)
        val recentDeliveries = db.deliveryHistoryDao().recent(MAX_RECENT_DELIVERIES)
        val recentDeliveryContents = recentDeliveries.associate { delivery ->
            delivery.id to delivery.executionId
                ?.let { db.actionExecutionDao().byId(it) }
                ?.payloadJson
                ?.let(::proactiveContentFromPayload)
        }
        val immutableCore = character
            ?.let { runCatching { JsonCodec.json.parseToJsonElement(it.contentJson) as? JsonObject }.getOrNull() }
            ?: CharacterCoreBootstrap.DEFAULT_CORE

        val queryText = buildString {
            append(event.type)
            append(' ')
            append(event.payloadJson)
            recentMessages.forEach { message ->
                append(' ')
                append(message.role)
                append(':')
                append(message.content)
            }
        }
        val retrievedMemories = (if (allowNetworkMemorySearch) memorySearch
            ?.search(MemoryQuery(text = queryText, limit = MAX_MEMORIES)) else emptyList())
            .orEmpty()
        val userTraits = userTraitRepository?.active().orEmpty()
        val activeTendencies = behaviorTendencyRepository?.activeTendencies().orEmpty()
        val legacyTendencies = behaviorTendencyRepository?.legacyTendencies().orEmpty()
        val availableActions = when (gate) {
            is PolicyGateResult.AllowAgent -> gate.availableActions
            is PolicyGateResult.ForceNoAction -> emptySet()
        }
        val policyJson = when (gate) {
            is PolicyGateResult.AllowAgent -> gate.policyJson
            is PolicyGateResult.ForceNoAction -> gate.policyJson
        }
        val lastInteractionAt = db.messageDao().latestInteractionAtEpochMillis()
        val discoverySnapshot = discoveryKnowledgeProvider?.loadFresh(nowEpoch)

        return ContextPack(
            event = event.toContextJson(recentMessages),
            currentContext = CurrentContext(
                localTime = InstantJson.formatInZone(now),
                petState = petState?.stateId ?: PetStateIds.IDLE,
                lastInteractionAt = lastInteractionAt.takeIf { it > 0 }?.let {
                    InstantJson.formatInZone(Instant.ofEpochMilli(it))
                },
                weather = externalContextResolver?.resolveWeatherSnapshot(nowEpoch)?.toWeatherContextJson(),
                petDailyLife = PetDailyLifeContext.build(
                    now = now,
                    zone = timeProvider.zone(),
                    petState = petState?.stateId ?: PetStateIds.IDLE,
                ),
            ),
            character = CharacterContext(
                immutableCore = immutableCore,
                activeTendencies = buildJsonArray {
                    activeTendencies.forEach { add(it.toContextJson()) }
                },
                relevantLegacyTendencies = buildJsonArray {
                    legacyTendencies.take(MAX_LEGACY_TENDENCIES).forEach { add(it.toContextJson()) }
                },
            ),
            userProfile = buildJsonArray {
                userTraits.take(MAX_USER_TRAITS).forEach { add(it.toContextJson()) }
            },
            retrievedMemories = buildJsonArray {
                retrievedMemories.take(MAX_MEMORIES).forEach { hit -> add(hit.toContextJson()) }
            },
            relevantTodos = buildJsonArray {
                todos.forEach { todo -> add(todo.toContextJson()) }
            },
            recentDeliveries = buildJsonArray {
                recentDeliveries.forEach { delivery ->
                    add(
                        buildJsonObject {
                            put("id", delivery.id)
                            delivery.eventId?.let { put("event_id", it) }
                            delivery.todoId?.let { put("todo_id", it) }
                            put("channel", delivery.channel)
                            delivery.contentHash?.let { put("content_hash", it) }
                            recentDeliveryContents[delivery.id]?.let { content ->
                                put("content_excerpt", content.take(MAX_DELIVERY_EXCERPT_CHARS))
                                put("topic_tags", buildJsonArray {
                                    deliveryTopicTags(content).forEach { tag -> add(JsonPrimitive(tag)) }
                                })
                                put("opening_pattern", deliveryOpeningPattern(content))
                            }
                            put("delivered_at", InstantJson.formatInZone(Instant.ofEpochMilli(delivery.deliveredAt)))
                        },
                    )
                }
            },
            scenePolicy = policyJson,
            availableActions = availableActions.toList().sorted(),
            sceneEvaluation = sceneEvaluation?.toContextJson(),
            localDiscoveries = buildJsonArray {
                discoverySnapshot?.items.orEmpty().take(MAX_LOCAL_DISCOVERIES).forEach { item ->
                    add(buildJsonObject {
                        put("id", item.id)
                        put("area", item.area)
                        put("category", item.category)
                        put("title", item.title)
                        put("summary", item.summary)
                        put("source_name", item.sourceName)
                        put("source_url", item.sourceUrl)
                        item.publishedAt?.let { put("published_at", it) }
                        item.eventStart?.let { put("event_start", it) }
                        item.eventEnd?.let { put("event_end", it) }
                    })
                }
            },
            localDiscoveriesUpdatedAt = discoverySnapshot?.generatedAt
                ?: discoverySnapshot?.savedAt?.let { InstantJson.formatInZone(Instant.ofEpochMilli(it)) },
        )
    }

    private fun EventEntity.toContextJson(recentMessages: List<com.littlekitty.xiaokele.core.database.entity.MessageEntity>): JsonObject = buildJsonObject {
        put("id", id)
        put("event_key", eventKey)
        put("type", type)
        put("source", source)
        put("occurred_at", InstantJson.formatInZone(Instant.ofEpochMilli(occurredAt)))
        put("received_at", InstantJson.formatInZone(Instant.ofEpochMilli(receivedAt)))
        expiresAt?.let { put("expires_at", InstantJson.formatInZone(Instant.ofEpochMilli(it))) }
        put("priority", priority)
        val payload = runCatching { JsonCodec.json.parseToJsonElement(payloadJson) as? JsonObject }.getOrNull()
        put("payload", buildJsonObject {
            payload?.forEach { (key, value) -> put(key, value) }
            put("recent_messages", buildJsonArray {
                recentMessages.forEach { message ->
                    add(
                        buildJsonObject {
                            put("id", message.id)
                            put("role", message.role)
                            put("content", message.content)
                            put("created_at", InstantJson.formatInZone(Instant.ofEpochMilli(message.createdAt)))
                        },
                    )
                }
            })
        })
    }

    private fun TodoEntity.toContextJson(): JsonElement = buildJsonObject {
        put("id", id)
        put("type", type)
        title?.let { put("title", it) }
        put("status", status)
        put("priority", priority)
        availableAt?.let { put("available_at", InstantJson.formatInZone(Instant.ofEpochMilli(it))) }
        expiresAt?.let { put("expires_at", InstantJson.formatInZone(Instant.ofEpochMilli(it))) }
        sourceEventId?.let { put("source_event_id", it) }
        payloadJson?.let { payload ->
            runCatching { JsonCodec.json.parseToJsonElement(payload) }.getOrNull()?.let { put("payload", it) }
        }
    }

    private fun MemoryHit.toContextJson(): JsonElement = buildJsonObject {
        put("id", memory.id)
        put("type", memory.type)
        memory.subject?.let { put("subject", it) }
        put("content", memory.content)
        memory.summary?.let { put("summary", it) }
        put("importance", memory.importance)
        put("confidence", memory.confidence)
        put("matched_by", matchedBy.name)
        put("score", score)
    }

    private fun com.littlekitty.xiaokele.core.database.entity.UserTraitEntity.toContextJson(): JsonElement =
        buildJsonObject {
            put("key", key)
            put("value", runCatching { JsonCodec.json.parseToJsonElement(valueJson) }.getOrNull() ?: JsonObject(emptyMap()))
            put("confidence", confidence)
        }

    private fun com.littlekitty.xiaokele.core.database.entity.BehaviorTendencyEntity.toContextJson(): JsonElement =
        buildJsonObject {
            put("key", key)
            put("current_value", currentValue ?: "")
            put("original_value", originalValue ?: "")
            put("status", status)
            suppressionLevel?.let { put("suppression_level", it) }
            legacyExpressionRuleJson?.let { raw ->
                runCatching { JsonCodec.json.parseToJsonElement(raw) }.getOrNull()?.let { put("legacy_expression_rule", it) }
            }
        }

    private fun proactiveContentFromPayload(raw: String): String? = runCatching {
        JsonCodec.json.parseToJsonElement(raw).jsonObject["content"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.replace(WHITESPACE_PATTERN, " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun deliveryTopicTags(content: String): List<String> = buildList {
        if (content.containsAny("工作", "上班", "下班", "加班", "值班")) add("work")
        if (content.containsAny("天气", "下雨", "雨伞", "降温", "升温", "空气")) add("weather")
        if (content.containsAny("吃", "饭", "餐", "饿")) add("food")
        if (content.containsAny("睡", "困", "休息", "醒")) add("sleep")
        if (content.containsAny("想你", "抱", "亲", "贴贴", "喜欢", "爱")) add("affection")
        if (content.containsAny("回家", "出发", "路上", "通勤", "到家")) add("commute")
        if (isEmpty()) add("other")
    }

    private fun deliveryOpeningPattern(content: String): String = content
        .trimStart()
        .takeWhile { it !in OPENING_BREAKS }
        .take(MAX_OPENING_PATTERN_CHARS)
        .ifBlank { content.take(MAX_OPENING_PATTERN_CHARS) }

    private fun String.containsAny(vararg candidates: String): Boolean = candidates.any(::contains)

    companion object {
        const val MAX_TODOS = 3
        const val MAX_RECENT_MESSAGES = 14
        const val MAX_MEMORIES = 6
        const val MAX_USER_TRAITS = 8
        const val MAX_LEGACY_TENDENCIES = 4
        const val MAX_RECENT_DELIVERIES = 5
        const val MAX_LOCAL_DISCOVERIES = 8
        const val MAX_DELIVERY_EXCERPT_CHARS = 160
        const val MAX_OPENING_PATTERN_CHARS = 24
        private val WHITESPACE_PATTERN = Regex("\\s+")
        private val OPENING_BREAKS = setOf('，', '。', '！', '？', ',', '.', '!', '?', '\n')
    }
}
