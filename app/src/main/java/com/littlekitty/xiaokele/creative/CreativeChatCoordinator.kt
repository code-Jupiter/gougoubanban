package com.littlekitty.xiaokele.creative

import androidx.room.withTransaction
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.creative.CreativeBuckets
import com.littlekitty.xiaokele.core.database.creative.CreativeCoverageRoles
import com.littlekitty.xiaokele.core.database.creative.CreativeOfferLedger
import com.littlekitty.xiaokele.core.database.creative.CreativeOfferFrequencyPolicy
import com.littlekitty.xiaokele.core.database.creative.CreativeSourceTypes
import com.littlekitty.xiaokele.core.database.creative.CreativeTurnTracker
import com.littlekitty.xiaokele.core.database.creative.CreativeWorkTypes
import com.littlekitty.xiaokele.core.database.entity.CreativePreferenceEntity
import com.littlekitty.xiaokele.core.database.entity.CreativeSourceLinkEntity
import com.littlekitty.xiaokele.core.database.entity.CreativeTopicEntity
import com.littlekitty.xiaokele.core.database.entity.CreativeWorkEntity
import com.littlekitty.xiaokele.core.database.state.CreativeOfferStatuses
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

interface CreativeContextProvider {
    suspend fun contextFor(userMessage: com.littlekitty.xiaokele.core.database.entity.MessageEntity): JsonObject
    suspend fun contextForIdleFollowUp(): JsonObject
}

interface CreativeLibraryAccess {
    suspend fun listAvailableTopics(limit: Int = 3): List<JsonObject>
    suspend fun readCandidate(candidateId: String): JsonObject?
    suspend fun readWork(workId: String): JsonObject?
    suspend fun readSession(sessionId: String): JsonObject?
    suspend fun markTopicDiscussed(topicId: String): Boolean
    suspend fun saveSessionProgress(
        sessionId: String,
        stage: String,
        outline: String?,
        direction: String?,
        lastMessageId: String?,
    ): Boolean
}

/**
 * Owns the creative boundary around front chat. It records turns and offers,
 * but never writes MemoryEntity or UserTraitEntity, so literary content cannot
 * become factual memory by accident.
 */
class CreativeChatCoordinator(
    private val db: AppDatabase,
    private val timeProvider: TimeProvider,
) : CreativeContextProvider, CreativeLibraryAccess {

    private val turnTracker = CreativeTurnTracker(db)
    private val offerLedger = CreativeOfferLedger(db)

    suspend fun recordUserDialogueTurnInTransaction(
        message: com.littlekitty.xiaokele.core.database.entity.MessageEntity,
    ) {
        turnTracker.recordInTransaction(message.id, message.createdAt)
    }

    suspend fun onUserMessagePersisted(message: com.littlekitty.xiaokele.core.database.entity.MessageEntity) {
        val now = message.createdAt
        val text = message.content
        val explicitDisable = isPermanentCreativeRejection(text)
        if (explicitDisable) {
            val preference = db.creativePreferenceDao().byKey(CreativeBuckets.BEDTIME_STORY)
                ?: defaultPreference(CreativeBuckets.BEDTIME_STORY, now)
            db.creativePreferenceDao().upsert(
                preference.copy(
                    proactiveEnabled = false,
                    disabledReason = "USER_EXPLICITLY_DISABLED",
                    updatedAt = now,
                ),
            )
            return
        }
        val recentBedtime = db.creativeOfferEventDao().latestSentSince(
            CreativeBuckets.BEDTIME_STORY,
            now - BEDTIME_REJECTION_WINDOW_MILLIS,
        )
        if (recentBedtime != null && isCreativeRejection(text)) {
            val preference = db.creativePreferenceDao().byKey(CreativeBuckets.BEDTIME_STORY)
                ?: defaultPreference(CreativeBuckets.BEDTIME_STORY, now)
            val withinWindow = preference.rejectWindowStartedAt?.let {
                now - it < BEDTIME_REJECTION_WINDOW_MILLIS
            } ?: false
            val nextCount = if (withinWindow) preference.rejectCountWindow + 1 else 1
            db.creativePreferenceDao().upsert(
                preference.copy(
                    proactiveEnabled = if (explicitDisable || nextCount >= BEDTIME_REJECTION_LIMIT) false else preference.proactiveEnabled,
                    rejectCountWindow = nextCount,
                    rejectWindowStartedAt = if (withinWindow) preference.rejectWindowStartedAt else now,
                    disabledReason = when {
                        explicitDisable -> "USER_EXPLICITLY_DISABLED"
                        nextCount >= BEDTIME_REJECTION_LIMIT -> "THREE_REJECTIONS_IN_72_HOURS"
                        else -> preference.disabledReason
                    },
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun onAssistantReplyPersisted(
        userMessageId: String?,
        assistantMessageId: String,
        replyText: String,
        replyPayload: JsonObject,
    ) {
        val now = timeProvider.now().toEpochMilli()
        val marker = replyPayload[CREATIVE_OFFER_BUCKET_KEY]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it in setOf(CreativeBuckets.MICRO_INLINE, CreativeBuckets.LIBRARY_TOPIC, CreativeBuckets.BEDTIME_STORY) }
        if (marker != null) {
            val userText = userMessageId?.let { db.messageDao().byId(it)?.content }.orEmpty()
            val turnId = userMessageId?.let { db.userDialogueTurnDao().byMessageId(it)?.id }
                ?: db.userDialogueTurnDao().recent(1).firstOrNull()?.id
            val reservation = offerLedger.reserve(
                bucket = marker,
                offerType = marker,
                dedupKey = assistantMessageId,
                turnId = turnId,
                now = now,
                userInitiated = isUserInitiatedCreativeRequest(userText),
            )
            if (reservation != null) offerLedger.markSent(reservation.id, assistantMessageId, now)
        }

        val userText = userMessageId?.let { db.messageDao().byId(it)?.content }.orEmpty()
        if (isExplicitPoemSave(userText)) {
            val requestedAt = userMessageId?.let { db.messageDao().byId(it)?.createdAt }
            val earlierPoem = requestedAt?.let { userCreatedAt ->
                db.messageDao().recent(32).firstOrNull { message ->
                    message.role == "ASSISTANT" &&
                        message.id != assistantMessageId &&
                        message.createdAt <= userCreatedAt &&
                        message.content.isNotBlank()
                }?.content
            }
            saveUserPoem(userMessageId, assistantMessageId, earlierPoem ?: replyText, now)
        }
    }

    override suspend fun contextFor(userMessage: com.littlekitty.xiaokele.core.database.entity.MessageEntity): JsonObject {
        val now = userMessage.createdAt
        val turn = db.userDialogueTurnDao().byMessageId(userMessage.id)
        val userInitiated = isUserInitiatedCreativeRequest(userMessage.content)
        val proactiveEnabled = db.creativePreferenceDao().byKey(CreativeBuckets.LIBRARY_TOPIC)
            ?.proactiveEnabled ?: true
        val micro = if (userInitiated) true else turn?.let {
            com.littlekitty.xiaokele.core.database.creative.CreativeOfferFrequencyPolicy(db)
                .check(CreativeBuckets.MICRO_INLINE, it.id, now).allowed
        } ?: false
        val library = if (userInitiated) true else if (!proactiveEnabled) false else turn?.let {
            com.littlekitty.xiaokele.core.database.creative.CreativeOfferFrequencyPolicy(db)
                .check(CreativeBuckets.LIBRARY_TOPIC, it.id, now).allowed
        } ?: false
        val bedtime = bedtimeEligibility(userMessage, userInitiated, now)
        val topics = if (library) listAvailableTopics(3) else emptyList()
        return buildJsonObject {
            put("purpose", "creative_capability_gate")
            put("user_initiated_request", userInitiated)
            put("micro_inline", buildJsonObject {
                put("can_offer", micro)
                put("bucket", CreativeBuckets.MICRO_INLINE)
                put("user_initiated_bypasses_frequency", userInitiated)
            })
            put("library_topic", buildJsonObject {
                put("can_offer", library && topics.isNotEmpty())
                put("bucket", CreativeBuckets.LIBRARY_TOPIC)
                put("available_topic_summaries", buildJsonArray { topics.forEach(::add) })
            })
            put("bedtime_story", bedtime)
            put("rules", buildJsonObject {
                put("creative_work_is_not_fact", true)
                put("dog_feeling_is_not_user_psychology", true)
                put("full_work_requires_local_read_tool", true)
                put("proactive_frequency_is_maximum_not_minimum", true)
            })
        }
    }

    override suspend fun contextForIdleFollowUp(): JsonObject {
        val now = timeProvider.now().toEpochMilli()
        val turn = db.userDialogueTurnDao().recent(1).firstOrNull()
        val allowed = turn != null && CreativeOfferFrequencyPolicy(db)
            .check(CreativeBuckets.LIBRARY_TOPIC, turn.id, now).allowed
        val topics = if (allowed) listAvailableTopics(3) else emptyList()
        return buildJsonObject {
            put("purpose", "creative_capability_gate")
            put("user_initiated_request", false)
            put("micro_inline", buildJsonObject {
                put("can_offer", false)
                put("bucket", CreativeBuckets.MICRO_INLINE)
            })
            put("library_topic", buildJsonObject {
                put("can_offer", topics.isNotEmpty())
                put("bucket", CreativeBuckets.LIBRARY_TOPIC)
                put("available_topic_summaries", buildJsonArray { topics.forEach(::add) })
            })
            put("bedtime_story", buildJsonObject {
                put("can_offer", false)
                put("bucket", CreativeBuckets.BEDTIME_STORY)
            })
            put("rules", buildJsonObject {
                put("creative_work_is_not_fact", true)
                put("proactive_frequency_is_maximum_not_minimum", true)
            })
        }
    }

    private suspend fun bedtimeEligibility(
        message: com.littlekitty.xiaokele.core.database.entity.MessageEntity,
        userInitiated: Boolean,
        now: Long,
    ): JsonObject {
        val explicitStoryRequest = isExplicitStoryRequest(message.content)
        val sleepIntent = SleepIntentDetector.detect(message.content)
        val preference = db.creativePreferenceDao().byKey(CreativeBuckets.BEDTIME_STORY)
        val enabled = preference?.proactiveEnabled ?: true
        val day = Instant.ofEpochMilli(now).atZone(TimeProvider.DISPLAY_ZONE).toLocalDate()
        val dayStart = day.atStartOfDay(TimeProvider.DISPLAY_ZONE).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(TimeProvider.DISPLAY_ZONE).toInstant().toEpochMilli()
        val alreadyChecked = db.creativeOfferEventDao().bedtimeCheckBetween(
            CreativeBuckets.BEDTIME_STORY,
            dayStart,
            dayEnd,
        ) != null
        val checkedNow = if (sleepIntent && !explicitStoryRequest && enabled && !alreadyChecked) {
            val hit = StableCreativeRandom.hit(message.id, day, BEDTIME_PROPOSAL_PROBABILITY)
            db.creativeOfferEventDao().insert(
                com.littlekitty.xiaokele.core.database.entity.CreativeOfferEventEntity(
                    id = EntityId.generate("offer").value,
                    bucket = CreativeBuckets.BEDTIME_STORY,
                    userDialogueTurnId = db.userDialogueTurnDao().byMessageId(message.id)?.id,
                    messageId = message.id,
                    offerType = "BEDTIME_CHECK",
                    dedupKey = day.toString(),
                    reservedAt = null,
                    sentAt = null,
                    status = CreativeOfferStatuses.CHECKED,
                    isUserInitiated = false,
                    createdAt = now,
                ),
            )
            hit
        } else {
            false
        }
        return buildJsonObject {
            put("can_offer", explicitStoryRequest || (sleepIntent && enabled && checkedNow) || (userInitiated && explicitStoryRequest))
            put("sleep_intent_detected", sleepIntent)
            put("explicit_user_request", explicitStoryRequest)
            put("proactive_enabled", enabled)
            put("random_probability", BEDTIME_PROPOSAL_PROBABILITY)
            put("random_checked", checkedNow || alreadyChecked)
            put("bucket", CreativeBuckets.BEDTIME_STORY)
            put("default_style", "成人、温柔、有想象力，不幼儿化")
        }
    }

    override suspend fun listAvailableTopics(limit: Int): List<JsonObject> {
        val now = timeProvider.now().toEpochMilli()
        return db.creativeTopicDao().available(now, limit).map { topic ->
            buildJsonObject {
                put("id", topic.id)
                topic.workId?.let { put("work_id", it) }
                topic.candidateId?.let { put("candidate_id", it) }
                put("topic_summary", topic.topicSummary)
                put("opening_hint", topic.openingHint)
                put("sensitivity", topic.sensitivity)
                put("priority", topic.priority)
                put("novelty", topic.novelty)
                put("status", topic.status)
                put("discussed", topic.status == "DISCUSSED")
            }
        }
    }

    override suspend fun readWork(workId: String): JsonObject? {
        val work = db.creativeWorkDao().byId(workId) ?: return null
        if (work.status != "ACTIVE") return null
        val links = db.creativeSourceLinkDao().forWork(work.id)
        return buildJsonObject {
            put("source_kind", "CREATIVE_WORK")
            put("id", work.id)
            put("work_type", work.workType)
            put("title", work.title)
            put("content", work.content)
            put("summary", work.summary)
            put("dog_emotion_summary", work.dogEmotionSummary)
            put("period_start", work.periodStart)
            put("period_end", work.periodEnd)
            put("timezone", work.timezone)
            put("source_links", buildJsonArray {
                links.forEach { link -> add(buildJsonObject {
                    put("source_type", link.sourceType)
                    put("source_id", link.sourceId)
                    put("coverage_role", link.coverageRole)
                    put("source_timestamp", link.sourceTimestamp)
                }) }
            })
            put("not_factual_memory", true)
        }
    }

    override suspend fun readCandidate(candidateId: String): JsonObject? {
        val candidate = db.creativeCandidateDao().byId(candidateId) ?: return null
        if (candidate.status != "AVAILABLE" || candidate.expiresAt?.let { it <= timeProvider.now().toEpochMilli() } == true) return null
        return buildJsonObject {
            put("source_kind", "CREATIVE_CANDIDATE")
            put("id", candidate.id)
            put("candidate_type", candidate.candidateType)
            put("theme", candidate.theme)
            put("idea_seed", candidate.ideaSeed)
            put("dog_feeling_seed", candidate.dogFeelingSeed)
            put("risk_level", candidate.riskLevel)
            put("not_factual_memory", true)
        }
    }

    override suspend fun readSession(sessionId: String): JsonObject? {
        val session = db.creativeSessionDao().byId(sessionId) ?: return null
        if (session.status == "ABANDONED") return null
        return buildJsonObject {
            put("source_kind", "CREATIVE_SESSION")
            put("id", session.id)
            session.candidateId?.let { put("candidate_id", it) }
            session.workId?.let { put("work_id", it) }
            put("session_type", session.sessionType)
            session.currentOutline?.let { put("current_outline", it) }
            put("current_stage", session.currentStage)
            session.userDirection?.let { put("user_direction", it) }
            put("status", session.status)
            put("not_factual_memory", true)
        }
    }

    override suspend fun markTopicDiscussed(topicId: String): Boolean =
        db.creativeTopicDao().updateStatus(topicId, "DISCUSSED", timeProvider.now().toEpochMilli()) == 1

    override suspend fun saveSessionProgress(
        sessionId: String,
        stage: String,
        outline: String?,
        direction: String?,
        lastMessageId: String?,
    ): Boolean {
        val current = db.creativeSessionDao().byId(sessionId) ?: return false
        db.creativeSessionDao().upsert(
            current.copy(
                currentStage = stage.take(80),
                currentOutline = outline?.take(4_000),
                userDirection = direction?.take(1_000),
                lastMessageId = lastMessageId,
                updatedAt = timeProvider.now().toEpochMilli(),
            ),
        )
        return true
    }

    private suspend fun saveUserPoem(userMessageId: String?, assistantMessageId: String, text: String, now: Long) {
        val fingerprint = sha256("poem:$assistantMessageId:$text")
        if (db.creativeWorkDao().byFingerprint(fingerprint) != null) return
        val work = CreativeWorkEntity(
            id = EntityId.generate("work").value,
            workType = CreativeWorkTypes.USER_SAVED_POEM,
            title = "写给你的一小首诗",
            content = text,
            summary = text.replace(Regex("\\s+"), " ").take(160),
            dogEmotionSummary = "这是在聊天里被选择留下的一点灵感。",
            periodStart = now,
            periodEnd = now + 1,
            timezone = TimeProvider.DISPLAY_ZONE.id,
            inputFingerprint = fingerprint,
            status = "ACTIVE",
            createdAt = now,
            updatedAt = now,
        )
        db.withTransaction {
            db.creativeWorkDao().insert(work)
            db.creativeSourceLinkDao().insertAll(
                listOfNotNull(
                    userMessageId?.let { sourceLink(work.id, CreativeSourceTypes.USER_MESSAGE, it, now, now) },
                    sourceLink(work.id, CreativeSourceTypes.ASSISTANT_MESSAGE, assistantMessageId, now, now),
                ),
            )
            db.creativeTopicDao().upsert(
                CreativeTopicEntity(
                    id = EntityId.generate("topic").value,
                    workId = work.id,
                    topicSummary = work.summary,
                    openingHint = "我把刚才那几句留进了自己的日记本里。",
                    sensitivity = "LOW",
                    priority = 30,
                    novelty = 1.0,
                    status = "AVAILABLE",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    private fun sourceLink(workId: String, type: String, id: String, timestamp: Long, now: Long) =
        CreativeSourceLinkEntity(
            id = EntityId.generate("src").value,
            workId = workId,
            sourceType = type,
            sourceId = id,
            sourceTimestamp = timestamp,
            coverageRole = CreativeCoverageRoles.PRIMARY,
            createdAt = now,
        )

    private fun defaultPreference(key: String, now: Long) = CreativePreferenceEntity(
        featureKey = key,
        proactiveEnabled = true,
        updatedAt = now,
    )

    companion object {
        const val CREATIVE_OFFER_BUCKET_KEY = "creative_offer_bucket"
        const val BEDTIME_PROPOSAL_PROBABILITY = 0.30
        const val BEDTIME_REJECTION_LIMIT = 3
        const val BEDTIME_REJECTION_WINDOW_MILLIS = 72 * 60 * 60 * 1_000L
        private val rejectionWords = listOf("不用了", "不想听", "不需要", "别再问", "以后不要再问", "不要再问")
        private val permanentWords = listOf("以后不要再问", "不要再提供", "关闭睡前故事", "不需要这个功能")
        private val poemSaveWords = listOf("保存这首", "把这首", "留在作品库", "留在日记本", "存下来")
        private val creationWords = listOf("写一首诗", "写诗", "作诗", "讲个故事", "讲一个故事", "写个故事", "日记", "手记", "随笔", "找歌", "歌词", "歌曲")

        fun isUserInitiatedCreativeRequest(text: String): Boolean = creationWords.any(text::contains)
        fun isExplicitStoryRequest(text: String): Boolean = listOf("讲个故事", "讲一个故事", "睡前故事").any(text::contains)
        fun isCreativeRejection(text: String): Boolean = rejectionWords.any(text::contains)
        fun isPermanentCreativeRejection(text: String): Boolean = permanentWords.any(text::contains)
        fun isExplicitPoemSave(text: String): Boolean = poemSaveWords.any(text::contains) && text.contains("诗")
    }
}

object SleepIntentDetector {
    private val positive = listOf("我要睡", "准备睡", "先去睡", "去睡觉", "睡前故事")
    private val nonIntent = listOf("他说晚安", "她说晚安", "引用", "昨天晚安")

    fun detect(text: String): Boolean =
        nonIntent.none(text::contains) && positive.any(text::contains)
}

object StableCreativeRandom {
    fun hit(seed: String, date: LocalDate, probability: Double): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$seed:$date".toByteArray(Charsets.UTF_8))
        val value = digest.take(8).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
        val normalized = (value ushr 1).toDouble() / Long.MAX_VALUE.toDouble()
        return normalized < probability
    }

}

private fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
    .digest(raw.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
