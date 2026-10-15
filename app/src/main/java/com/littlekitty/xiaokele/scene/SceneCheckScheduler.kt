package com.littlekitty.xiaokele.scene

import com.littlekitty.xiaokele.core.common.protocol.NormalizedEvent
import com.littlekitty.xiaokele.core.common.protocol.BehaviorTendencyKeyNames
import com.littlekitty.xiaokele.core.common.scene.SceneCandidateThresholds
import com.littlekitty.xiaokele.core.common.scene.SceneEventTypes
import com.littlekitty.xiaokele.core.common.scene.ScenePriorities
import com.littlekitty.xiaokele.core.common.scene.SceneSources
import com.littlekitty.xiaokele.core.common.scene.SceneThresholdProvider
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.repository.BehaviorTendencyRepository
import com.littlekitty.xiaokele.core.database.state.EventStateMachine
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.PetStatePriorityMatrix
import com.littlekitty.xiaokele.event.BackgroundCompanionPipeline
import com.littlekitty.xiaokele.event.BackgroundProcessingResult
import com.littlekitty.xiaokele.event.EventDispatchResult
import com.littlekitty.xiaokele.event.LocalBackgroundDispatcher
import com.littlekitty.xiaokele.external.ExternalContextResolver
import com.littlekitty.xiaokele.external.ExternalEventContract
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

data class SceneCheckReport(
    val expiredEvents: Int = 0,
    val backlogProcessed: Int = 0,
    val backlogResults: List<Pair<String, BackgroundProcessingResult>> = emptyList(),
    val backlogRemaining: Int = 0,
    val backlogBudgetExhausted: Boolean = false,
    val petStateRestored: Int = 0,
    val interactionGapEventDispatched: Boolean = false,
    val interactionGapPriority: Int? = null,
    val interactionGapMillis: Long = 0L,
)

/**
 * Phase 7 local scene check aggregate. It is the only production place that
 * periodically scans RECEIVED backlog, expires overdue events, restores the
 * situational PetState default and emits the interaction-gap candidate.
 * Weather is refreshed by its own worker; this scheduler only evaluates local
 * scene candidates and the already-normalized event backlog.
 */
class SceneCheckScheduler(
    private val db: AppDatabase,
    private val pipeline: BackgroundCompanionPipeline,
    private val dispatcher: LocalBackgroundDispatcher,
    private val contextResolver: ExternalContextResolver,
    private val thresholdProvider: SceneThresholdProvider,
    private val timeProvider: TimeProvider,
    private val behaviorTendencyRepository: BehaviorTendencyRepository? = null,
) {

    suspend fun check(): SceneCheckReport = checkAt(timeProvider.now())

    suspend fun checkAt(now: Instant): SceneCheckReport {
        val nowEpoch = now.toEpochMilli()
        val expired = expireOverdue(nowEpoch)
        val backlog = processBacklog(nowEpoch)
        val restored = restoreExpiredPetState(nowEpoch)
        val gap = dispatchInteractionGapIfDue(now, nowEpoch)
        return SceneCheckReport(
            expiredEvents = expired,
            backlogProcessed = backlog.results.size,
            backlogResults = backlog.results,
            backlogRemaining = backlog.remaining,
            backlogBudgetExhausted = backlog.budgetExhausted,
            petStateRestored = restored,
            interactionGapEventDispatched = gap != null,
            interactionGapPriority = gap?.priority,
            interactionGapMillis = gap?.gapMillis ?: 0L,
        )
    }

    private suspend fun expireOverdue(nowEpoch: Long): Int {
        var expired = 0
        db.eventDao().dueBefore(
            now = nowEpoch,
            nonTerminal = listOf(EventStatuses.RECEIVED, EventStatuses.PROCESSING),
        ).forEach { event ->
            if (EventStateMachine.canTransition(event.status, EventStatuses.EXPIRED)) {
                db.eventDao().updateStatus(event.id, EventStatuses.EXPIRED)
                expired++
            }
        }
        return expired
    }

    /**
     * Ordered processing: priority desc, then received_at asc, then id asc.
     * Phase 8 adds a hard batch size and wall-clock budget so one startup pass
     * can never monopolise the process or the LLM budget. USER_MESSAGE rows
     * created by CHAT_INPUT never enter this background recovery path.
     */
    private suspend fun processBacklog(nowEpoch: Long): BacklogProcessingResult {
        val startedAt = System.nanoTime()
        val candidates = db.eventDao().backgroundBacklog(nowEpoch, MAX_BACKLOG_BATCH_SIZE + 1)
        val batch = candidates.take(MAX_BACKLOG_BATCH_SIZE)
        val results = mutableListOf<Pair<String, BackgroundProcessingResult>>()
        var budgetExhausted = false

        batch.forEach { event ->
            if (System.nanoTime() - startedAt >= BACKLOG_BUDGET_NANOS) {
                budgetExhausted = true
                return@forEach
            }
            results += event.id to pipeline.process(event.id)
        }
        val remaining = db.eventDao().backgroundBacklogCount(nowEpoch)
        return BacklogProcessingResult(
            results = results,
            remaining = remaining.coerceAtLeast(0),
            budgetExhausted = budgetExhausted,
        )
    }

    private data class BacklogProcessingResult(
        val results: List<Pair<String, BackgroundProcessingResult>>,
        val remaining: Int,
        val budgetExhausted: Boolean,
    )

    private suspend fun restoreExpiredPetState(nowEpoch: Long): Int {
        val defaultState = PetStatePriorityMatrix.contextualDefault()
        return db.petStateDao().resetExpiredTo(
            singletonId = com.littlekitty.xiaokele.core.database.entity.PetStateEntity.SINGLETON_ID,
            contextualDefaultState = defaultState,
            now = nowEpoch,
        )
    }

    private suspend fun dispatchInteractionGapIfDue(now: Instant, nowEpoch: Long): InteractionGapCandidate? {
        val thresholds: SceneCandidateThresholds = thresholdProvider.current()
        val lastInteractionAt = db.messageDao().latestInteractionAtEpochMillis()
        if (lastInteractionAt <= 0L) return null
        val gapMillis = (nowEpoch - lastInteractionAt).coerceAtLeast(0L)
        val proactiveLevel = behaviorTendencyRepository?.activeTendencies()
            ?.firstOrNull { it.key == BehaviorTendencyKeyNames.PROACTIVE_CONTACT_LEVEL }
            ?.currentValue
            .let(ProactiveTendencyPolicy::normalizedLevel)
        val effectiveGapMillis = ProactiveTendencyPolicy.interactionGapMillis(
            thresholds.interactionGapRestCandidateMillis,
            proactiveLevel,
        )
        if (gapMillis < effectiveGapMillis) return null

        val bucket = ExternalEventContract.bucket(nowEpoch, thresholds.interactionGapEventBucketMillis)
        val event = NormalizedEvent(
            eventKey = "local:interaction_gap:$bucket",
            type = SceneEventTypes.INTERACTION_GAP,
            source = SceneSources.LOCAL_SCENE_SCHEDULER,
            occurredAt = InstantJson.formatUtc(now),
            receivedAt = InstantJson.formatUtc(now),
            expiresAt = InstantJson.formatUtc(now.plusMillis(thresholds.interactionGapEventTtlMillis)),
            priority = ScenePriorities.INTERACTION_GAP,
            payload = buildJsonObject {
                put("last_interaction_at_epoch_millis", lastInteractionAt)
                put("gap_millis", gapMillis)
                put("effective_gap_candidate_millis", effectiveGapMillis)
                put("proactive_contact_level", proactiveLevel)
            },
        )
        val dispatched = dispatcher.dispatch(event) is EventDispatchResult.Processed
        return if (dispatched) {
            InteractionGapCandidate(gapMillis, event.priority)
        } else {
            null
        }
    }

    private data class InteractionGapCandidate(
        val gapMillis: Long,
        val priority: Int,
    )

    companion object {
        const val MAX_BACKLOG_BATCH_SIZE = 20
        const val BACKLOG_BUDGET_MILLIS = 15_000L
        const val BACKLOG_BUDGET_NANOS = BACKLOG_BUDGET_MILLIS * 1_000_000L
    }
}
