package com.littlekitty.xiaokele.creative

import androidx.room.withTransaction
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.chat.CharacterCoreBootstrap
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.CharacterContext
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.CurrentContext
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.AppMetadataEntity
import com.littlekitty.xiaokele.core.database.creative.CreativeCandidateTypes
import com.littlekitty.xiaokele.core.database.creative.CreativeCoverageRoles
import com.littlekitty.xiaokele.core.database.creative.CreativeSourceTypes
import com.littlekitty.xiaokele.core.database.creative.CreativeTaskKinds
import com.littlekitty.xiaokele.core.database.creative.CreativeWorkTypes
import com.littlekitty.xiaokele.core.database.entity.CreativeCandidateEntity
import com.littlekitty.xiaokele.core.database.entity.CreativeSourceLinkEntity
import com.littlekitty.xiaokele.core.database.entity.CreativeTaskRunEntity
import com.littlekitty.xiaokele.core.database.entity.CreativeTopicEntity
import com.littlekitty.xiaokele.core.database.entity.CreativeWorkEntity
import com.littlekitty.xiaokele.core.database.state.CreativeCandidateStatuses
import com.littlekitty.xiaokele.core.database.state.CreativeTaskStatuses
import com.littlekitty.xiaokele.core.database.state.CreativeTopicStatuses
import com.littlekitty.xiaokele.core.database.state.CreativeWorkStatuses
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class CreativeCatchUpReport(
    val initialized: Boolean,
    val tasksConsidered: Int,
    val taskStarted: String? = null,
    val status: String? = null,
    val resultWorkId: String? = null,
    val reason: String? = null,
)

private data class CreativePeriod(
    val taskKind: String,
    val periodKey: String,
    val start: Long,
    val end: Long,
    val staleAfterMillis: Long,
)

/**
 * Delayed-task coordinator for the creative domain. It performs one model
 * evaluation per invocation and atomically commits work, source links and the
 * task checkpoint. Missing network/model results remain RETRYABLE.
 */
class CreativeCatchUpCoordinator(
    private val db: AppDatabase,
    private val modelService: CreativeEvaluationModelService,
    private val timeProvider: TimeProvider,
) {

    suspend fun catchUp(now: Instant = timeProvider.now()): CreativeCatchUpReport {
        val nowEpoch = now.toEpochMilli()
        if (!ensureInitialized(nowEpoch)) {
            return CreativeCatchUpReport(initialized = true, tasksConsidered = 0, status = "INITIALIZED")
        }
        db.creativeTaskRunDao().requeueStaleRunning(
            startedBefore = nowEpoch - RUNNING_LEASE_MILLIS,
            reason = "RECOVERED_AFTER_PROCESS_DEATH",
            now = nowEpoch,
        )
        val periods = buildPeriods(nowEpoch)
        periods.forEach { ensureRun(it, nowEpoch) }
        val runs = periods.mapNotNull { period ->
            db.creativeTaskRunDao().byKey(period.taskKind, period.periodKey, TimeProvider.DISPLAY_ZONE.id)
        }
        val candidate = runs.firstOrNull { it.status == CreativeTaskStatuses.PENDING || it.status == CreativeTaskStatuses.RETRYABLE }
            ?: return CreativeCatchUpReport(true, periods.size, status = runs.firstOrNull()?.status, reason = "NO_DUE_CREATIVE_TASK")
        val period = periods.first { it.taskKind == candidate.taskKind && it.periodKey == candidate.periodKey }
        if (nowEpoch - period.start > period.staleAfterMillis) {
            db.withTransaction {
                db.creativeTaskRunDao().finish(
                    id = candidate.id,
                    status = CreativeTaskStatuses.SKIPPED_STALE,
                    completedAt = nowEpoch,
                    resultWorkId = null,
                    sourceWatermark = period.end,
                    inputFingerprint = candidate.inputFingerprint,
                    reason = "TASK_TOO_OLD",
                    now = nowEpoch,
                )
                advanceWatermarkInTransaction(period, nowEpoch)
            }
            return CreativeCatchUpReport(true, periods.size, candidate.id, CreativeTaskStatuses.SKIPPED_STALE, reason = "TASK_TOO_OLD")
        }
        val sources = sourceMessages(period)
        if (sources.isEmpty()) {
            db.withTransaction {
                db.creativeTaskRunDao().finish(
                    candidate.id, CreativeTaskStatuses.NO_ACTION, nowEpoch, null,
                    period.end, inputFingerprint(sources, emptyList()), "NO_NEW_VALID_SOURCE", nowEpoch,
                )
                advanceWatermarkInTransaction(period, nowEpoch)
            }
            return CreativeCatchUpReport(true, periods.size, candidate.id, CreativeTaskStatuses.NO_ACTION, reason = "NO_NEW_VALID_SOURCE")
        }
        if (db.creativeTaskRunDao().claim(candidate.id, CreativeTaskStatuses.RUNNING, nowEpoch, nowEpoch) != 1) {
            return CreativeCatchUpReport(true, periods.size, candidate.id, "COALESCED", reason = "CONCURRENT_OWNER")
        }
        val existingWorks = db.creativeWorkDao().recent(8)
        val sourceIds = sources.map { it.id }.toSet()
        val fingerprint = inputFingerprint(sources, existingWorks)
        val model = modelService.generateCreativeEvaluation(buildEvaluationContext(period, sources, existingWorks, fingerprint))
        return when (model) {
            is ModelServiceResult.Failure -> {
                val retryable = model.error.code == ErrorCode.NETWORK_UNAVAILABLE || model.error.code == ErrorCode.TIMEOUT
                if (retryable) {
                    db.creativeTaskRunDao().retryable(candidate.id, model.error.code.name, nowEpoch)
                } else {
                    db.creativeTaskRunDao().finish(
                        candidate.id,
                        CreativeTaskStatuses.FAILED,
                        nowEpoch,
                        null,
                        null,
                        null,
                        model.error.code.name,
                        nowEpoch,
                    )
                }
                CreativeCatchUpReport(
                    true,
                    periods.size,
                    candidate.id,
                    if (retryable) CreativeTaskStatuses.RETRYABLE else CreativeTaskStatuses.FAILED,
                    reason = model.error.code.name,
                )
            }
            is ModelServiceResult.Success -> completeModelEvaluation(
                candidate, period, sources, sourceIds, fingerprint, model.rawDecisionJson, nowEpoch,
            )
            is ModelServiceResult.NoAction -> {
                db.withTransaction {
                    db.creativeTaskRunDao().finish(
                        candidate.id,
                        CreativeTaskStatuses.NO_ACTION,
                        nowEpoch,
                        null,
                        period.end,
                        fingerprint,
                        "MODEL_NO_ACTION:${model.reason}",
                        nowEpoch,
                    )
                    advanceWatermarkInTransaction(period, nowEpoch)
                }
                CreativeCatchUpReport(
                    true,
                    periods.size,
                    candidate.id,
                    CreativeTaskStatuses.NO_ACTION,
                    reason = model.reason,
                )
            }
        }
    }

    private suspend fun completeModelEvaluation(
        run: CreativeTaskRunEntity,
        period: CreativePeriod,
        sources: List<com.littlekitty.xiaokele.core.database.entity.MessageEntity>,
        sourceIds: Set<String>,
        fingerprint: String,
        raw: String,
        now: Long,
    ): CreativeCatchUpReport {
        val output = CreativeEvaluationValidator.parse(raw, sourceIds)
        if (output == null) {
            db.creativeTaskRunDao().finish(run.id, CreativeTaskStatuses.FAILED, now, null, period.end, fingerprint, "INVALID_MODEL_OUTPUT", now)
            return CreativeCatchUpReport(true, 1, run.id, CreativeTaskStatuses.FAILED, reason = "INVALID_MODEL_OUTPUT")
        }
        val sourceDays = sources.map { Instant.ofEpochMilli(it.createdAt).atZone(TimeProvider.DISPLAY_ZONE).toLocalDate() }.toSet().size
        val periodShapeInvalid = when {
            period.taskKind == CreativeTaskKinds.DAILY_CATCH_UP && output.outcome == CreativeEvaluationOutcomes.DAILY_DIARY -> true
            period.taskKind != CreativeTaskKinds.DAILY_EVALUATION &&
                period.taskKind != CreativeTaskKinds.DAILY_CATCH_UP &&
                output.outcome == CreativeEvaluationOutcomes.DAILY_DIARY -> true
            period.taskKind != CreativeTaskKinds.WEEKLY_EVALUATION &&
                output.outcome == CreativeEvaluationOutcomes.WEEKLY_DIARY -> true
            period.taskKind != CreativeTaskKinds.MONTHLY_EVALUATION &&
                output.outcome == CreativeEvaluationOutcomes.MONTHLY_DIARY -> true
            output.outcome == CreativeEvaluationOutcomes.WEEKLY_DIARY &&
                (period.taskKind != CreativeTaskKinds.WEEKLY_EVALUATION || sourceDays < 2 || output.scores.crossDayContinuity < 0.3) -> true
            output.outcome == CreativeEvaluationOutcomes.MONTHLY_DIARY &&
                (period.taskKind != CreativeTaskKinds.MONTHLY_EVALUATION || sourceDays < 2 || output.scores.longTermChange < 0.3) -> true
            else -> false
        }
        if (periodShapeInvalid) {
            db.withTransaction {
                db.creativeTaskRunDao().finish(run.id, CreativeTaskStatuses.NO_ACTION, now, null, period.end, fingerprint, "PERIOD_SHAPE_NOT_MET", now)
                advanceWatermarkInTransaction(period, now)
            }
            return CreativeCatchUpReport(true, 1, run.id, CreativeTaskStatuses.NO_ACTION, reason = "PERIOD_SHAPE_NOT_MET")
        }
        if (output.outcome == CreativeEvaluationOutcomes.NO_ACTION || output.outcome.startsWith("HOLD_")) {
            db.withTransaction {
                db.creativeTaskRunDao().finish(run.id, CreativeTaskStatuses.NO_ACTION, now, null, period.end, fingerprint, output.outcome, now)
                advanceWatermarkInTransaction(period, now)
            }
            return CreativeCatchUpReport(true, 1, run.id, CreativeTaskStatuses.NO_ACTION, reason = output.outcome)
        }
        if (output.outcome == CreativeEvaluationOutcomes.INSPIRED_SHORT_STORY_CANDIDATE) {
            val candidateId = EntityId.generate("candidate").value
            val candidate = CreativeCandidateEntity(
                id = candidateId,
                candidateType = CreativeCandidateTypes.INSPIRED_SHORT_STORY_CANDIDATE,
                sourcePeriodStart = period.start,
                sourcePeriodEnd = period.end,
                sourceWatermarkStart = sources.minOf { it.createdAt },
                sourceWatermarkEnd = sources.maxOf { it.createdAt },
                theme = output.theme.orEmpty(),
                ideaSeed = output.ideaSeed.orEmpty(),
                dogFeelingSeed = output.dogFeelingSeed.orEmpty(),
                riskLevel = output.riskLevel,
                noveltyScore = output.scores.newness,
                repeatRisk = output.scores.repeatRisk,
                status = CreativeCandidateStatuses.AVAILABLE,
                expiresAt = now + CANDIDATE_TTL_MILLIS,
                createdAt = now,
                updatedAt = now,
            )
            db.withTransaction {
                db.creativeCandidateDao().upsert(candidate)
                db.creativeTopicDao().upsert(
                    CreativeTopicEntity(
                        id = EntityId.generate("topic").value,
                        candidateId = candidateId,
                        topicSummary = output.theme.orEmpty(),
                        openingHint = output.ideaSeed.orEmpty().take(240),
                        sensitivity = output.riskLevel,
                        priority = 40,
                        novelty = output.scores.newness,
                        status = CreativeTopicStatuses.AVAILABLE,
                        expiresAt = candidate.expiresAt,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                db.creativeTaskRunDao().finish(run.id, CreativeTaskStatuses.COMPLETED, now, null, period.end, fingerprint, "CANDIDATE_CREATED", now)
                advanceWatermarkInTransaction(period, now)
            }
            return CreativeCatchUpReport(true, 1, run.id, CreativeTaskStatuses.COMPLETED, reason = "CANDIDATE_CREATED")
        }

        val workType = output.workType ?: return failedOutput(run, period, fingerprint, now, "WORK_TYPE_MISSING")
        val normalizedContent = normalizeCreativeText(output.content.orEmpty())
        val isDuplicateContent = normalizedContent.isNotBlank() && db.creativeWorkDao().recent(32)
            .filter { it.workType == workType }
            .any { work ->
            creativeTextSimilarity(normalizedContent, normalizeCreativeText(work.content)) >= DUPLICATE_SIMILARITY_THRESHOLD
        }
        if (isDuplicateContent) {
            db.withTransaction {
                db.creativeTaskRunDao().finish(run.id, CreativeTaskStatuses.NO_ACTION, now, null, period.end, fingerprint, "DUPLICATE_CONTENT", now)
                advanceWatermarkInTransaction(period, now)
            }
            return CreativeCatchUpReport(true, 1, run.id, CreativeTaskStatuses.NO_ACTION, reason = "DUPLICATE_CONTENT")
        }
        if (db.creativeWorkDao().byPeriod(workType, period.start, period.end, TimeProvider.DISPLAY_ZONE.id) != null ||
            db.creativeWorkDao().byFingerprint(fingerprint) != null
        ) {
            db.withTransaction {
                db.creativeTaskRunDao().finish(run.id, CreativeTaskStatuses.NO_ACTION, now, null, period.end, fingerprint, "DUPLICATE_WORK", now)
                advanceWatermarkInTransaction(period, now)
            }
            return CreativeCatchUpReport(true, 1, run.id, CreativeTaskStatuses.NO_ACTION, reason = "DUPLICATE_WORK")
        }
        val work = CreativeWorkEntity(
            id = EntityId.generate("work").value,
            workType = workType,
            title = output.title.orEmpty(),
            content = output.content.orEmpty(),
            summary = output.summary.orEmpty(),
            dogEmotionSummary = output.dogEmotionSummary.orEmpty(),
            periodStart = period.start,
            periodEnd = period.end,
            timezone = TimeProvider.DISPLAY_ZONE.id,
            inputFingerprint = fingerprint,
            status = CreativeWorkStatuses.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
        val sourceLinks = sources.map { message ->
            val hasPrimary = db.creativeSourceLinkDao().forSource(CreativeSourceTypes.USER_MESSAGE, message.id)
                .any { it.coverageRole == CreativeCoverageRoles.PRIMARY }
            CreativeSourceLinkEntity(
                id = EntityId.generate("src").value,
                workId = work.id,
                sourceType = CreativeSourceTypes.USER_MESSAGE,
                sourceId = message.id,
                sourceTimestamp = message.createdAt,
                coverageRole = if (hasPrimary) CreativeCoverageRoles.REFERENCE_ONLY else CreativeCoverageRoles.PRIMARY,
                createdAt = now,
            )
        }
        db.withTransaction {
            db.creativeWorkDao().insert(work)
            db.creativeSourceLinkDao().insertAll(sourceLinks)
            db.creativeTopicDao().upsert(
                CreativeTopicEntity(
                    id = EntityId.generate("topic").value,
                    workId = work.id,
                    topicSummary = work.summary,
                    openingHint = "把一段最近记住的细节写进了自己的日记本。",
                    sensitivity = output.riskLevel,
                    priority = 35,
                    novelty = output.scores.newness,
                    status = CreativeTopicStatuses.AVAILABLE,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            db.creativeTaskRunDao().finish(run.id, CreativeTaskStatuses.COMPLETED, now, work.id, period.end, fingerprint, output.newMeaning, now)
            advanceWatermarkInTransaction(period, now)
        }
        return CreativeCatchUpReport(true, 1, run.id, CreativeTaskStatuses.COMPLETED, work.id)
    }

    private suspend fun failedOutput(run: CreativeTaskRunEntity, period: CreativePeriod, fingerprint: String, now: Long, reason: String): CreativeCatchUpReport {
        db.creativeTaskRunDao().finish(run.id, CreativeTaskStatuses.FAILED, now, null, period.end, fingerprint, reason, now)
        return CreativeCatchUpReport(true, 1, run.id, CreativeTaskStatuses.FAILED, reason = reason)
    }

    private suspend fun ensureInitialized(now: Long): Boolean {
        val key = "creative.enabled_at"
        if (db.metadataDao().get(key) != null) return true
        db.metadataDao().put(AppMetadataEntity(key, now.toString(), now))
        db.metadataDao().put(AppMetadataEntity("creative.daily_watermark", now.toString(), now))
        return false
    }

    private suspend fun ensureRun(period: CreativePeriod, now: Long) {
        if (db.creativeTaskRunDao().byKey(period.taskKind, period.periodKey, TimeProvider.DISPLAY_ZONE.id) != null) return
        db.creativeTaskRunDao().insert(
            CreativeTaskRunEntity(
                id = EntityId.generate("ctask").value,
                taskKind = period.taskKind,
                periodKey = period.periodKey,
                timezone = TimeProvider.DISPLAY_ZONE.id,
                scheduledAt = period.end,
                dueAt = period.end,
                status = CreativeTaskStatuses.PENDING,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun sourceMessages(period: CreativePeriod): List<com.littlekitty.xiaokele.core.database.entity.MessageEntity> =
        db.messageDao().between(period.start, period.end)
            .filter { it.role == "USER" && it.status == "COMPLETED" }
            .take(MAX_SOURCE_MESSAGES)

    private fun buildEvaluationContext(
        period: CreativePeriod,
        sources: List<com.littlekitty.xiaokele.core.database.entity.MessageEntity>,
        existingWorks: List<CreativeWorkEntity>,
        fingerprint: String,
    ): ContextPack = ContextPack(
        event = buildJsonObject {
            put("id", "creative:${period.periodKey}")
            put("type", "CREATIVE_EVALUATION")
            put("source", "CREATIVE_CATCH_UP")
            put("occurred_at", InstantJson.formatInZone(Instant.ofEpochMilli(period.end)))
            put("payload", buildJsonObject {
                put("task_kind", period.taskKind)
                put("period_key", period.periodKey)
                put("period_start", period.start)
                put("period_end", period.end)
                put("timezone", TimeProvider.DISPLAY_ZONE.id)
                put("input_fingerprint", fingerprint)
                put("sources", buildJsonArray {
                    sources.forEach { message -> add(buildJsonObject {
                        put("source_type", CreativeSourceTypes.USER_MESSAGE)
                        put("source_id", message.id)
                        put("content", message.content)
                        put("created_at", message.createdAt)
                    }) }
                })
                put("previous_work_fingerprints", buildJsonArray {
                    existingWorks.forEach { work -> add(JsonPrimitive(work.inputFingerprint)) }
                })
            })
        },
        currentContext = CurrentContext(localTime = InstantJson.formatInZone(Instant.ofEpochMilli(period.end))),
        character = CharacterContext(immutableCore = CharacterCoreBootstrap.DEFAULT_CORE),
        creativeContext = buildJsonObject { put("task_kind", period.taskKind); put("evaluation_only", true) },
    )

    private suspend fun buildPeriods(now: Long): List<CreativePeriod> {
        val date = Instant.ofEpochMilli(now).atZone(TimeProvider.DISPLAY_ZONE).toLocalDate()
        val todayStart = date.atStartOfDay(TimeProvider.DISPLAY_ZONE).toInstant().toEpochMilli()
        val yesterdayStart = date.minusDays(1).atStartOfDay(TimeProvider.DISPLAY_ZONE).toInstant().toEpochMilli()
        val watermark = db.metadataDao().get("creative.daily_watermark")?.toLongOrNull()
        val dailyStart = (watermark ?: yesterdayStart).coerceAtMost(yesterdayStart)
        val dailyEnd = todayStart
        val weekStartDate = date.minusDays(date.dayOfWeek.value.toLong() - 1)
        val previousWeekStart = weekStartDate.minusWeeks(1)
        val monthStartDate = date.withDayOfMonth(1)
        val previousMonthStart = monthStartDate.minusMonths(1)
        return listOf(
            CreativePeriod(
                if (dailyStart < yesterdayStart) CreativeTaskKinds.DAILY_CATCH_UP else CreativeTaskKinds.DAILY_EVALUATION,
                "$dailyStart-$dailyEnd",
                dailyStart,
                dailyEnd,
                if (dailyStart < yesterdayStart) 7 * 24 * 60 * 60 * 1_000L else 36 * 60 * 60 * 1_000L,
            ),
            CreativePeriod(CreativeTaskKinds.WEEKLY_EVALUATION, "${previousWeekStart}_$weekStartDate", atStart(previousWeekStart), atStart(weekStartDate), 7 * 24 * 60 * 60 * 1_000L),
            CreativePeriod(CreativeTaskKinds.MONTHLY_EVALUATION, "${previousMonthStart}_$monthStartDate", atStart(previousMonthStart), atStart(monthStartDate), 14 * 24 * 60 * 60 * 1_000L),
        )
    }

    private suspend fun advanceWatermarkInTransaction(period: CreativePeriod, now: Long) {
        if (period.taskKind == CreativeTaskKinds.DAILY_EVALUATION || period.taskKind == CreativeTaskKinds.DAILY_CATCH_UP) {
            db.metadataDao().put(AppMetadataEntity("creative.daily_watermark", period.end.toString(), now))
        }
    }

    private fun inputFingerprint(sources: List<com.littlekitty.xiaokele.core.database.entity.MessageEntity>, works: List<CreativeWorkEntity>): String = sha256(
        sources.joinToString("|") { "${it.id}:${it.createdAt}:${it.content.trim()}" } + "#" + works.joinToString("|") { it.inputFingerprint },
    )

    private fun atStart(date: LocalDate): Long = date.atStartOfDay(TimeProvider.DISPLAY_ZONE).toInstant().toEpochMilli()

    companion object {
        const val MAX_SOURCE_MESSAGES = 100
        const val CANDIDATE_TTL_MILLIS = 30 * 24 * 60 * 60 * 1_000L
        const val RUNNING_LEASE_MILLIS = 30 * 60 * 1_000L
        const val DUPLICATE_SIMILARITY_THRESHOLD = 0.82
    }
}

private fun normalizeCreativeText(value: String): String = value
    .lowercase()
    .filter { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' }

private fun creativeTextSimilarity(left: String, right: String): Double {
    if (left == right) return 1.0
    if (left.length < 2 || right.length < 2) return 0.0
    val leftChunks = left.windowed(2).toSet()
    val rightChunks = right.windowed(2).toSet()
    return leftChunks.intersect(rightChunks).size.toDouble() /
        leftChunks.union(rightChunks).size.toDouble()
}

private fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
    .digest(raw.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
