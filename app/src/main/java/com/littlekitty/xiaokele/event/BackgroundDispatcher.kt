package com.littlekitty.xiaokele.event

import com.littlekitty.xiaokele.core.common.protocol.NormalizedEvent
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.policy.DevelopmentCandidatePolicies
import com.littlekitty.xiaokele.core.database.repository.TriggerManager
import com.littlekitty.xiaokele.core.database.state.TriggerStatuses
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogCategories
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogNames
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

sealed interface EventDispatchResult {
    data class Processed(
        val eventId: String,
        val processing: BackgroundProcessingResult,
    ) : EventDispatchResult

    data class Duplicate(
        val eventKey: String,
        val existingEventId: String,
    ) : EventDispatchResult

    data class Expired(
        val eventId: String,
    ) : EventDispatchResult

    data class Invalid(
        val violations: List<EventNormalizationViolation>,
    ) : EventDispatchResult
}

data class TriggerDispatchReport(
    val dueTriggerCount: Int,
    val fired: Int,
    val eventsDispatched: Int,
    val completedAfterFire: Int,
    val results: List<Pair<String, BackgroundProcessingResult>> = emptyList(),
    val firedRecovered: Int = 0,
    val expiredLate: Int = 0,
    val hasRemainingDue: Boolean = false,
    val hasRemainingFired: Boolean = false,
)

/** Explicit fire-time deadline and bounded pass policy for delayed trigger work. */
object TriggerDispatchPolicy {
    const val MAX_LATENESS_MILLIS = 30L * 60L * 1_000L
    const val MAX_TRIGGERS_PER_PASS = 20

    fun cutoffEpochMillis(nowEpochMillis: Long): Long = nowEpochMillis - MAX_LATENESS_MILLIS

    fun expiresAtEpochMillis(fireAtEpochMillis: Long, todoExpiresAtEpochMillis: Long?): Long =
        minOf(fireAtEpochMillis + MAX_LATENESS_MILLIS, todoExpiresAtEpochMillis ?: Long.MAX_VALUE)

    fun isExpired(nowEpochMillis: Long, fireAtEpochMillis: Long, todoExpiresAtEpochMillis: Long?): Boolean =
        nowEpochMillis >= expiresAtEpochMillis(fireAtEpochMillis, todoExpiresAtEpochMillis)
}

/**
 * Testable scheduler interface (requirement 8). The production wake path is
 * [TriggerWakeWorker]; startup also performs one deterministic due-trigger pass
 * after recovery. A trigger only re-enters decision, never sends directly.
 */
interface BackgroundScheduler {
    suspend fun dispatchDue(now: Instant): TriggerDispatchReport

    suspend fun recoverFired(now: Instant): TriggerDispatchReport = TriggerDispatchReport(
        dueTriggerCount = 0,
        fired = 0,
        eventsDispatched = 0,
        completedAfterFire = 0,
    )
}

/**
 * Local implementation: fire due triggers atomically, convert each fired
 * trigger into a NormalizedEvent for the same background Companion Agent, then
 * complete the FIRED trigger after the re-judgement attempt has finished.
 *
 * Phase 8 stability rules:
 * - event_key is deterministic as `trigger:fire:<trigger_id>:<fire_at>`;
 * - a FIRED row left behind by process death is recovered from the same key
 *   instead of being fired again;
 * - concurrent fire attempts lose on the conditional ACTIVE -> FIRED update,
 *   so one due trigger yields at most one processing attempt.
 */
class DueTriggerScheduler(
    private val db: AppDatabase,
    private val triggerManager: TriggerManager,
    private val dispatcher: LocalBackgroundDispatcher,
    private val timeProvider: TimeProvider,
) : BackgroundScheduler {

    override suspend fun dispatchDue(now: Instant): TriggerDispatchReport {
        val nowEpoch = now.toEpochMilli()
        val cutoff = TriggerDispatchPolicy.cutoffEpochMillis(nowEpoch)
        val expiredLate = triggerManager.expireActiveAtOrBefore(cutoff)
        val due = triggerManager.due(
            now = nowEpoch,
            notBefore = cutoff,
            limit = TriggerDispatchPolicy.MAX_TRIGGERS_PER_PASS,
        )
        var fired = 0
        var dispatched = 0
        var completed = 0
        val results = mutableListOf<Pair<String, BackgroundProcessingResult>>()

        due.forEach { trigger ->
            val firedTrigger = triggerManager.fireDue(trigger.id, nowEpoch, cutoff) ?: return@forEach
            fired++
            val outcome = dispatchTriggerEvent(firedTrigger, now)
            if (outcome is TriggerEventOutcome.Dispatched) {
                dispatched++
                results += firedTrigger.id to outcome.result
            }
            if (triggerManager.byId(firedTrigger.id)?.status == TriggerStatuses.FIRED) {
                triggerManager.completeFired(firedTrigger.id)
                completed++
            }
        }

        return TriggerDispatchReport(
            dueTriggerCount = due.size,
            fired = fired,
            eventsDispatched = dispatched,
            completedAfterFire = completed,
            results = results,
            expiredLate = expiredLate,
            hasRemainingDue = triggerManager.due(
                now = nowEpoch,
                notBefore = cutoff,
                limit = 1,
            ).isNotEmpty(),
        )
    }

    /**
     * Startup recovery for FIRED rows left behind when the process died after
     * the atomic fire but before completion. Reconstructs the exact same
     * deterministic event key; duplicate ingestion never creates a second row.
     */
    override suspend fun recoverFired(now: Instant): TriggerDispatchReport = recoverFiredTriggers(now)

    suspend fun recoverFiredTriggers(now: Instant): TriggerDispatchReport {
        val nowEpoch = now.toEpochMilli()
        val cutoff = TriggerDispatchPolicy.cutoffEpochMillis(nowEpoch)
        val expiredLate = triggerManager.expireFiredAtOrBefore(cutoff)
        val firedTriggers = triggerManager.byFired(TriggerDispatchPolicy.MAX_TRIGGERS_PER_PASS)
        var recovered = 0
        var completed = 0
        val results = mutableListOf<Pair<String, BackgroundProcessingResult>>()

        firedTriggers.forEach { trigger ->
            recovered++
            val outcome = dispatchTriggerEvent(trigger, now)
            if (outcome is TriggerEventOutcome.Dispatched) {
                results += trigger.id to outcome.result
            }
            if (triggerManager.byId(trigger.id)?.status == TriggerStatuses.FIRED) {
                triggerManager.completeFired(trigger.id)
                completed++
            }
        }
        return TriggerDispatchReport(
            dueTriggerCount = 0,
            fired = 0,
            eventsDispatched = results.size,
            completedAfterFire = completed,
            results = results,
            firedRecovered = recovered,
            expiredLate = expiredLate,
            hasRemainingFired = triggerManager.hasFired(),
        )
    }

    private suspend fun dispatchTriggerEvent(
        trigger: com.littlekitty.xiaokele.core.database.entity.TriggerEntity,
        now: Instant,
    ): TriggerEventOutcome {
        val nowEpoch = now.toEpochMilli()
        val fireAt = trigger.nextFireAt ?: trigger.lastFireAt ?: nowEpoch
        val eventKey = triggerEventKey(trigger.id, fireAt)
        val todo = trigger.todoId?.let { db.todoDao().byId(it) }
        if (todo?.status in setOf(TodoStatuses.CANCELLED, TodoStatuses.EXPIRED, TodoStatuses.FAILED)) {
            if (triggerManager.byId(trigger.id)?.status == TriggerStatuses.FIRED) {
                triggerManager.cancel(trigger.id)
            }
            return TriggerEventOutcome.NotDispatched("TODO_TERMINAL")
        }
        // Recovery normally marks this Todo EXPIRED before the trigger pass.
        // Retain the same cancellation meaning even if a worker runs in the
        // narrow interval before that startup recovery has persisted it.
        if (todo?.expiresAt?.let { it <= nowEpoch } == true) {
            if (triggerManager.byId(trigger.id)?.status == TriggerStatuses.FIRED) {
                triggerManager.cancel(trigger.id)
            }
            return TriggerEventOutcome.NotDispatched("TODO_EXPIRED")
        }
        if (todo?.status == TodoStatuses.EXECUTED) {
            if (triggerManager.byId(trigger.id)?.status == TriggerStatuses.FIRED) {
                triggerManager.completeFired(trigger.id)
            }
            return TriggerEventOutcome.NotDispatched("TODO_EXECUTED")
        }
        if (TriggerDispatchPolicy.isExpired(nowEpoch, fireAt, todo?.expiresAt)) {
            if (triggerManager.byId(trigger.id)?.status == TriggerStatuses.FIRED) {
                triggerManager.expire(trigger.id)
            }
            return TriggerEventOutcome.NotDispatched("EXPIRED")
        }
        val eventExpiresAt = TriggerDispatchPolicy.expiresAtEpochMillis(fireAt, todo?.expiresAt)
        val raw = NormalizedEvent(
            eventKey = eventKey,
            type = DevelopmentCandidatePolicies.EVENT_TYPE_TRIGGER_FIRE,
            source = "LOCAL_TRIGGER",
            occurredAt = InstantJson.formatUtc(Instant.ofEpochMilli(fireAt)),
            receivedAt = InstantJson.formatUtc(now),
            // The event lifetime belongs to the trigger fire time, never to the
            // delayed recovery moment.
            expiresAt = InstantJson.formatUtc(Instant.ofEpochMilli(eventExpiresAt)),
            priority = todo?.priority ?: 0,
            payload = buildJsonObject {
                put("trigger_id", trigger.id)
                put("fire_at_epoch_millis", fireAt)
                todo?.id?.let { put("todo_id", it) }
                todo?.type?.let { put("todo_type", it) }
                todo?.title?.let { put("todo_title", it) }
            },
        )
        val dispatch = dispatcher.dispatch(raw)
        return when (dispatch) {
            is EventDispatchResult.Processed -> TriggerEventOutcome.Dispatched(dispatch.processing)

            is EventDispatchResult.Duplicate -> {
                val existing = db.eventDao().byId(dispatch.existingEventId)
                if (existing != null && existing.status in com.littlekitty.xiaokele.core.database.state.EventStatuses.terminal) {
                    TriggerEventOutcome.AlreadyTerminal(existing.status)
                } else {
                    val processing = dispatcher.pipeline.process(dispatch.existingEventId)
                    TriggerEventOutcome.Dispatched(processing)
                }
            }

            is EventDispatchResult.Expired -> TriggerEventOutcome.NotDispatched("EXPIRED")
            is EventDispatchResult.Invalid -> TriggerEventOutcome.NotDispatched("INVALID")
        }
    }

    private sealed interface TriggerEventOutcome {
        data class Dispatched(val result: BackgroundProcessingResult) : TriggerEventOutcome
        data class AlreadyTerminal(val status: String) : TriggerEventOutcome
        data class NotDispatched(val reason: String) : TriggerEventOutcome
    }

    companion object {
        fun triggerEventKey(triggerId: String, fireAtEpochMillis: Long): String =
            "trigger:fire:$triggerId:$fireAtEpochMillis"
    }
}

class LocalBackgroundDispatcher(
    private val normalizer: EventNormalizer,
    val pipeline: BackgroundCompanionPipeline,
    private val diagnosticsLogger: DiagnosticsLogger? = null,
) {
    suspend fun dispatch(raw: NormalizedEvent): EventDispatchResult {
        val ingestion = normalizer.ingest(raw)
        return when (ingestion) {
            is EventIngestion.Stored -> {
                diagnosticsLogger?.record(
                    category = DiagnosticLogCategories.SCENE,
                    name = DiagnosticLogNames.EVENT_INGESTED,
                    status = DiagnosticLogStatuses.STARTED,
                    subjectId = ingestion.event.id,
                    summary = "事件已入库，开始场景判断与 AI 决策 type=${raw.type}",
                    payloadJson = buildJsonObject {
                        put("marker", DiagnosticLogNames.EVENT_INGESTED)
                        put("event_id", ingestion.event.id)
                        put("event_key", raw.eventKey)
                        put("event_type", raw.type)
                        put("event_source", raw.source)
                        put("raw_event_payload", raw.payload.toString())
                    }.toString(),
                )
                val processing = pipeline.process(ingestion.event.id)
                diagnosticsLogger?.record(
                    category = DiagnosticLogCategories.AI,
                    name = DiagnosticLogNames.TURN_COMPLETED,
                    status = processing.diagnosticStatus(),
                    subjectId = ingestion.event.id,
                    payloadJson = buildJsonObject {
                        put("marker", DiagnosticLogNames.TURN_COMPLETED)
                        put("correlation_id", ingestion.event.id)
                        put("event_type", raw.type)
                        put("processing_result", processing.toString())
                    }.toString(),
                    summary = processing.toString().take(500),
                )
                EventDispatchResult.Processed(ingestion.event.id, processing)
            }

            is EventIngestion.RejectedDuplicate -> {
                diagnosticsLogger?.record(
                    category = DiagnosticLogCategories.SCENE,
                    name = DiagnosticLogNames.EVENT_DEDUPLICATED,
                    status = DiagnosticLogStatuses.SKIPPED,
                    subjectId = ingestion.existingEvent.id,
                    summary = "重复 event_key，被去重",
                )
                EventDispatchResult.Duplicate(raw.eventKey, ingestion.existingEvent.id)
            }

            is EventIngestion.RejectedExpired -> {
                diagnosticsLogger?.record(
                    category = DiagnosticLogCategories.SCENE,
                    name = DiagnosticLogNames.EVENT_REJECTED,
                    status = DiagnosticLogStatuses.SKIPPED,
                    subjectId = ingestion.event.id,
                    summary = "事件进入时已过期",
                )
                EventDispatchResult.Expired(ingestion.event.id)
            }

            is EventIngestion.RejectedInvalid -> {
                diagnosticsLogger?.record(
                    category = DiagnosticLogCategories.SCENE,
                    name = DiagnosticLogNames.EVENT_REJECTED,
                    status = DiagnosticLogStatuses.FAILED,
                    summary = ingestion.violations.joinToString("; ") { "${it.path}:${it.message}" },
                )
                EventDispatchResult.Invalid(ingestion.violations)
            }
        }
    }

    private fun BackgroundProcessingResult.diagnosticStatus(): String = when (this) {
        is BackgroundProcessingResult.PlanExecuted -> DiagnosticLogStatuses.SUCCESS
        is BackgroundProcessingResult.NoActionAudited -> DiagnosticLogStatuses.NO_ACTION
        is BackgroundProcessingResult.RetryScheduled -> DiagnosticLogStatuses.RETRY
        is BackgroundProcessingResult.Failed -> DiagnosticLogStatuses.FAILED
        is BackgroundProcessingResult.Expired -> DiagnosticLogStatuses.SKIPPED
        is BackgroundProcessingResult.AlreadyTerminal -> DiagnosticLogStatuses.SKIPPED
        is BackgroundProcessingResult.ActivePlanConflict -> DiagnosticLogStatuses.RETRY
        is BackgroundProcessingResult.UnknownEvent -> DiagnosticLogStatuses.FAILED
    }
}
