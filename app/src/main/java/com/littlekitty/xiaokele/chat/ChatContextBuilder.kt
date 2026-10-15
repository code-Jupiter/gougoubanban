package com.littlekitty.xiaokele.chat

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import com.littlekitty.xiaokele.core.common.protocol.CharacterContext
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.CurrentContext
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.memory.MemoryHit
import com.littlekitty.xiaokele.core.database.memory.MemoryQuery
import com.littlekitty.xiaokele.core.database.memory.MemorySearch
import com.littlekitty.xiaokele.core.database.repository.BehaviorTendencyRepository
import com.littlekitty.xiaokele.core.database.repository.UserTraitRepository
import com.littlekitty.xiaokele.core.common.protocol.UserProfileLayerNames
import com.littlekitty.xiaokele.core.database.state.PetStateIds
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import com.littlekitty.xiaokele.external.ExternalContextResolver
import com.littlekitty.xiaokele.external.toWeatherContextJson
import com.littlekitty.xiaokele.discovery.DiscoveryKnowledgeProvider
import com.littlekitty.xiaokele.creative.CreativeContextProvider
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * Phase 3 Context Builder (architecture section 17/30).
 *
 * The chat event now carries retrieved memories, the stable user profile,
 * active tendencies and suppressed legacy tendencies. Policy Resolver,
 * shift/GPS/weather providers remain Phase 4/6 and stay empty here.
 */
class ChatContextBuilder(
    private val db: AppDatabase,
    private val timeProvider: TimeProvider,
    private val memorySearch: MemorySearch? = null,
    private val userTraitRepository: UserTraitRepository? = null,
    private val behaviorTendencyRepository: BehaviorTendencyRepository? = null,
    private val externalContextResolver: ExternalContextResolver? = null,
    private val conversationContextManager: ConversationContextManager? = null,
    private val userProfileContextManager: UserProfileContextManager? = null,
    private val discoveryKnowledgeProvider: DiscoveryKnowledgeProvider? = null,
    private val creativeContextProvider: CreativeContextProvider? = null,
) {

    suspend fun buildFor(
        userMessage: MessageEntity,
        allowNetworkMemorySearch: Boolean = true,
    ): ContextPack = buildForEvent(
        eventId = userMessage.sourceEventId ?: "",
        eventKey = "chat:user_message:${userMessage.id}",
        eventType = "USER_MESSAGE",
        eventSource = "CHAT_INPUT",
        memoryQuery = userMessage.content,
        currentMessageId = userMessage.id,
        allowNetworkMemorySearch = allowNetworkMemorySearch,
    ) { _, recentMessages, _ ->
        buildJsonObject {
            put(
                "user_message",
                buildJsonObject {
                    put("id", userMessage.id)
                    put("role", userMessage.role)
                    put("content", userMessage.content)
                    put("created_at", InstantJson.formatInZone(Instant.ofEpochMilli(userMessage.createdAt)))
                },
            )
            put(
                "recent_messages",
                recentMessages.messagesToContextJson(),
            )
        }
    }

    suspend fun buildForIdleFollowUp(anchorAssistant: MessageEntity, eventId: String = ""): ContextPack = buildForEvent(
        eventId = eventId,
        eventKey = "chat:idle_follow_up:${anchorAssistant.id}:$eventId",
        eventType = "IDLE_FOLLOW_UP",
        eventSource = "CHAT_IDLE_FOLLOW_UP",
        memoryQuery = anchorAssistant.content,
    ) { _, recentMessages, _ ->
        buildJsonObject {
            put(
                "idle_follow_up",
                buildJsonObject {
                    put("anchor_assistant_message_id", anchorAssistant.id)
                    put("anchor_assistant_content", anchorAssistant.content)
                    put(
                        "decision_request",
                        "请先从用户视角判断上一话题是否仍有较强的继续回复意愿；如果有，优先沿当前话题递进并给出一个具体、容易回答的切口；如果没有，换一个自然相关或轻松的新话题，但仍只给一个接话方向。",
                    )
                },
            )
            put("recent_messages", recentMessages.messagesToContextJson())
        }
    }

    private suspend fun buildForEvent(
        eventId: String,
        eventKey: String,
        eventType: String,
        eventSource: String,
        memoryQuery: String,
        currentMessageId: String? = null,
        allowNetworkMemorySearch: Boolean = true,
        payloadBuilder: (Instant, List<MessageEntity>, List<TodoEntity>) -> JsonObject,
    ): ContextPack {
        val now = timeProvider.now()
        val nowEpoch = now.toEpochMilli()
        val character = db.characterCoreDao().latest()
        val petState = db.petStateDao().get()
        val conversationWindow = conversationContextManager?.prepare()
        val fallbackLimit = if (eventType == "USER_MESSAGE") {
            LAST_MESSAGES_LIMIT - 1
        } else {
            LAST_MESSAGES_LIMIT
        }
        val recentMessages = if (eventType == "USER_MESSAGE") {
            conversationWindow?.rawMessages
                ?.filterNot { it.id == currentMessageId }
                ?.takeLast(fallbackLimit)
                ?: db.messageDao().recentAsc(fallbackLimit)
        } else {
            conversationWindow?.rawMessages
                ?: db.messageDao().recentAsc(fallbackLimit)
        }
        val conversationSummaries = conversationWindow?.recentSummaries.orEmpty()
        val todos = db.todoDao().byStatuses(listOf(TodoStatuses.READY, TodoStatuses.WAITING))
            .filter { todo -> todo.expiresAt?.let { it > nowEpoch } != false }
            .sortedWith(compareByDescending<TodoEntity> { it.priority }.thenBy { it.availableAt ?: Long.MAX_VALUE })
            .take(MAX_TODOS)
        val todoSources = todos.associate { todo ->
            val event = todo.sourceEventId?.let { db.eventDao().byId(it) }
            val payload = event?.let {
                runCatching { JsonCodec.json.parseToJsonElement(it.payloadJson).jsonObject }.getOrNull()
            }
            val sourceMessageId = payload?.get("user_message_id")?.jsonPrimitive?.contentOrNull
                ?: payload?.get("user_message")?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
            val sourceMessage = sourceMessageId?.let { db.messageDao().byId(it) }
            todo.id to event?.let { TodoSourceContext(it, sourceMessage) }
        }

        val immutableCore = character
            ?.let { runCatching { JsonCodec.json.parseToJsonElement(it.contentJson) as? JsonObject }.getOrNull() }
            ?: CharacterCoreBootstrap.DEFAULT_CORE

        val retrievedMemories = (if (allowNetworkMemorySearch) memorySearch
            ?.search(MemoryQuery(text = memoryQuery, limit = MAX_MEMORY_HARD_LIMIT)) else emptyList())
            .orEmpty()
            .filter { it.score >= MIN_MEMORY_CONTEXT_SCORE }
            .let(::selectMemoryHitsWithinBudget)
        val userTraits = userTraitRepository?.active().orEmpty()
        val coreTraits = userTraits.filter { it.isCoreProfileTrait() }
            .let { userProfileContextManager?.prepare(it) ?: it }
        val dynamicTraits = userTraits.filterNot { it.isCoreProfileTrait() }.take(MAX_DYNAMIC_USER_TRAITS)
        val activeTendencies = behaviorTendencyRepository?.activeTendencies().orEmpty()
        val legacyTendencies = behaviorTendencyRepository?.legacyTendencies().orEmpty()
        val localDiscoverySnapshot = discoveryKnowledgeProvider?.loadFresh(nowEpoch)
        val localDiscoveries = localDiscoverySnapshot?.items
            .orEmpty()
            .take(MAX_LOCAL_DISCOVERIES)

        return ContextPack(
            event = buildJsonObject {
                put("id", eventId)
                put("event_key", eventKey)
                put("type", eventType)
                put("source", eventSource)
                put("occurred_at", InstantJson.formatInZone(now))
                put("priority", 90)
                put("payload", payloadBuilder(now, recentMessages, todos))
            },
            currentContext = CurrentContext(
                localTime = InstantJson.formatInZone(now),
                petState = petState?.stateId ?: PetStateIds.IDLE,
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
                coreTraits.forEach { add(it.toContextJson()) }
            },
            dynamicUserProfile = buildJsonArray {
                dynamicTraits.forEach { add(it.toContextJson()) }
            },
            conversationSummaries = buildJsonArray {
                val messageById = db.messageDao().allAscending().associateBy { it.id }
                conversationSummaries.forEach { summary ->
                    add(summary.toContextJson(messageById[summary.rangeEndMessageId]))
                }
            },
            activeConversationState = conversationWindow?.temporarySessionState?.let { summary ->
                buildJsonObject {
                    put("kind", "HIGH_FREQUENCY_ROLLING_STATE")
                    put("summary", summary)
                    put("instruction", "仅用于承接当前仍在进行的高频会话；优先服从当前用户消息与最近原文。")
                }
            },
            retrievedMemories = buildJsonArray {
                retrievedMemories.forEach { hit -> add(hit.toContextJson(now)) }
            },
            relevantTodos = buildJsonArray {
                todos.forEach { todo -> add(todo.toContextJson(todoSources[todo.id])) }
            },
            localDiscoveries = buildJsonArray {
                localDiscoveries.forEach { item -> add(item.toContextJson()) }
            },
            localDiscoveriesUpdatedAt = localDiscoverySnapshot?.generatedAt
                ?: localDiscoverySnapshot?.savedAt?.let { InstantJson.formatInZone(Instant.ofEpochMilli(it)) },
            availableActions = AgentActionRegistry.frontChatAllowed.sorted(),
            creativeContext = currentMessageId?.let { id ->
                db.messageDao().byId(id)?.let { message -> creativeContextProvider?.contextFor(message) }
            } ?: if (eventType == "IDLE_FOLLOW_UP") {
                creativeContextProvider?.contextForIdleFollowUp()
            } else {
                null
            },
        )
    }

    private fun List<MessageEntity>.messagesToContextJson(): JsonElement = buildJsonArray {
        filterNot { it.errorCode == com.littlekitty.xiaokele.provider.CompanionFallback.MESSAGE_MARKER }.forEach { message ->
            add(
                buildJsonObject {
                    put("id", message.id)
                    put("role", message.role)
                    put("content", message.content)
                    put("created_at", InstantJson.formatInZone(Instant.ofEpochMilli(message.createdAt)))
                },
            )
        }
    }

    private fun TodoEntity.toContextJson(sourceContext: TodoSourceContext?): JsonElement = buildJsonObject {
        put("id", id)
        put("type", type)
        title?.let { put("title", it) }
        put("status", status)
        put("priority", priority)
        availableAt?.let { put("available_at", InstantJson.formatInZone(Instant.ofEpochMilli(it))) }
        expiresAt?.let { put("expires_at", InstantJson.formatInZone(Instant.ofEpochMilli(it))) }
        payloadJson?.let { payload ->
            runCatching { JsonCodec.json.parseToJsonElement(payload) }.getOrNull()?.let {
                put("payload", it)
            }
        }
        sourceContext?.let {
            put("source", it.event.toTodoSourceJson(it.sourceMessage))
        }
    }

    private fun EventEntity.toTodoSourceJson(sourceMessage: MessageEntity?): JsonElement = buildJsonObject {
        put("event_id", id)
        put("kind", todoSourceKind(type, source))
        put("event_type", type)
        put("event_source", source)
        put("occurred_at", InstantJson.formatInZone(Instant.ofEpochMilli(occurredAt)))
        val payload = runCatching { JsonCodec.json.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
        val userWording = sourceMessage?.content ?: payload?.get("user_message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
        sourceMessage?.let { put("source_message_id", it.id) }
        userWording?.takeIf { it.isNotBlank() }?.let { put("user_wording", it.take(MAX_SOURCE_WORDING_CHARS)) }
    }

    private fun todoSourceKind(type: String, source: String): String = when {
        source == "CHAT_INPUT" || type == "USER_MESSAGE" -> "USER_REQUEST"
        source.contains("WEATHER", ignoreCase = true) -> "WEATHER"
        source.contains("SCHEDULE", ignoreCase = true) || source.contains("SHIFT", ignoreCase = true) -> "SCHEDULE"
        source.contains("MEMORY", ignoreCase = true) -> "MEMORY"
        else -> "SYSTEM_EVENT"
    }

    private fun MemoryHit.toContextJson(now: Instant): JsonElement = buildJsonObject {
        put("id", memory.id)
        put("type", memory.type)
        memory.subject?.let { put("subject", it) }
        put("content", memory.content)
        memory.summary?.let { put("summary", it) }
        put("importance", memory.importance)
        put("confidence", memory.confidence)
        put("matched_by", matchedBy.name)
        put("score", score)
        put("occurred_at", InstantJson.formatInZone(Instant.ofEpochMilli(memory.createdAt)))
        memory.validFrom?.let { put("valid_from", InstantJson.formatInZone(Instant.ofEpochMilli(it))) }
        memory.validUntil?.let {
            put("valid_until", InstantJson.formatInZone(Instant.ofEpochMilli(it)))
            put("remaining_validity_millis", (it - now.toEpochMilli()).coerceAtLeast(0L))
        }
        memory.emotionAtEventJson?.let { raw ->
            runCatching { JsonCodec.json.parseToJsonElement(raw) }.getOrNull()?.let { put("emotion_at_event", it) }
        }
        memory.currentAppraisalJson?.let { raw ->
            runCatching { JsonCodec.json.parseToJsonElement(raw) }.getOrNull()?.let { put("current_appraisal", it) }
        }
        memory.lastReassessedAt?.let {
            put("last_reassessed_at", InstantJson.formatInZone(Instant.ofEpochMilli(it)))
        }
    }

    private fun com.littlekitty.xiaokele.core.database.entity.UserTraitEntity.toContextJson(): JsonElement =
        buildJsonObject {
            put("key", key)
            put("value", runCatching { JsonCodec.json.parseToJsonElement(valueJson) }.getOrNull() ?: JsonObject(emptyMap()))
            put("confidence", confidence)
            put("profile_layer", profileLayer)
        }

    private fun com.littlekitty.xiaokele.core.database.entity.UserTraitEntity.isCoreProfileTrait(): Boolean {
        if (profileLayer == UserProfileLayerNames.CORE) return true
        val normalized = key.lowercase()
        return CORE_PROFILE_KEY_MARKERS.any(normalized::contains) && confidence >= CORE_PROFILE_MIN_CONFIDENCE
    }

    private fun selectMemoryHitsWithinBudget(hits: List<MemoryHit>): List<MemoryHit> {
        var used = 0
        val selected = mutableListOf<MemoryHit>()
        hits.take(MAX_MEMORY_HARD_LIMIT).forEach { hit ->
            val cost = ContextTokenEstimator.estimate(
                listOfNotNull(hit.memory.subject, hit.memory.content, hit.memory.summary).joinToString(" "),
            ) + MEMORY_JSON_OVERHEAD_TOKENS
            if (selected.isEmpty() || used + cost <= MEMORY_TOKEN_BUDGET) {
                selected += hit
                used += cost
            }
        }
        return selected
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

    private fun com.littlekitty.xiaokele.discovery.DiscoveryItem.toContextJson(): JsonElement =
        buildJsonObject {
            put("id", id)
            put("area", area)
            put("category", category)
            put("title", title)
            put("summary", summary)
            put("source_name", sourceName)
            put("source_url", sourceUrl)
            publishedAt?.let { put("published_at", it) }
            eventStart?.let { put("event_start", it) }
            eventEnd?.let { put("event_end", it) }
            discoveredAt?.let { put("discovered_at", it) }
            put("score", score)
        }

    private data class TodoSourceContext(
        val event: EventEntity,
        val sourceMessage: MessageEntity?,
    )

    companion object {
        /** Fallback window; the current user message is counted separately by buildFor(). */
        const val LAST_MESSAGES_LIMIT = 14
        const val MAX_TODOS = 3
        const val MAX_MEMORY_HARD_LIMIT = 20
        const val MAX_DYNAMIC_USER_TRAITS = 8
        const val MAX_LEGACY_TENDENCIES = 4
        const val MAX_LOCAL_DISCOVERIES = 8
        const val MEMORY_TOKEN_BUDGET = 1_800
        const val MEMORY_JSON_OVERHEAD_TOKENS = 30
        const val MIN_MEMORY_CONTEXT_SCORE = 0.3
        const val CORE_PROFILE_MIN_CONFIDENCE = 0.75
        const val MAX_SOURCE_WORDING_CHARS = 300
        val CORE_PROFILE_KEY_MARKERS = listOf(
            "identity", "occupation", "name", "preference", "dislike", "allergy",
            "constraint", "communication", "relationship", "food_", "work_",
        )
    }
}
