package com.littlekitty.xiaokele.core.database.repository

import androidx.room.withTransaction
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.MemoryCandidate
import com.littlekitty.xiaokele.core.common.protocol.MemoryValidityNames
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.dao.ActionPlanDao
import com.littlekitty.xiaokele.core.database.dao.EventDao
import com.littlekitty.xiaokele.core.database.dao.MemoryDao
import com.littlekitty.xiaokele.core.database.dao.PetStateDao
import com.littlekitty.xiaokele.core.database.dao.TodoDao
import com.littlekitty.xiaokele.core.database.entity.ActionPlanEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.MemoryEntity
import com.littlekitty.xiaokele.core.database.entity.PetStateEntity
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.state.ActionPlanStateMachine
import com.littlekitty.xiaokele.core.database.state.EventStateMachine
import com.littlekitty.xiaokele.core.database.state.MemoryStateMachine
import com.littlekitty.xiaokele.core.database.state.MemoryStatuses
import com.littlekitty.xiaokele.core.database.state.PetStateIds
import com.littlekitty.xiaokele.core.database.state.PetStateMachine
import com.littlekitty.xiaokele.core.database.state.TodoStateMachine
import android.database.sqlite.SQLiteConstraintException
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 1 local repositories. Each aggregate owns exactly one table/DAO and
 * enforces deterministic transitions before writes (C4/C6). The repositories
 * never call a model or an external service.
 */
class EventRepository(private val db: AppDatabase, private val dao: EventDao) {

    suspend fun save(entity: EventEntity): EventEntity = db.withTransaction {
        require(entity.eventKey.isNotBlank()) { "event_key must not be blank" }
        dao.upsert(entity)
        entity
    }

    suspend fun byId(id: String): EventEntity? = dao.byId(id)

    suspend fun transition(id: String, newStatus: String): EventEntity = db.withTransaction {
        val current = dao.byId(id) ?: error("Unknown event: $id")
        EventStateMachine.requireTransition(current.status, newStatus)
        val updated = dao.updateStatus(id, newStatus)
        check(updated == 1) { "Event transition affected $updated rows" }
        dao.byId(id) ?: error("Event disappeared during transition: $id")
    }
}

class TodoRepository(private val db: AppDatabase, private val dao: TodoDao) {

    suspend fun create(
        type: String,
        now: Long,
        title: String? = null,
        payloadJson: String? = null,
        sourceEventId: String? = null,
        priority: Int = 0,
        availableAt: Long? = null,
        expiresAt: Long? = null,
        initialStatus: String = TodoStatuses.READY,
        id: String = EntityId.generate("todo").value,
    ): TodoEntity {
        require(initialStatus in TodoStatuses.claimable + TodoStatuses.CANDIDATE) {
            "New todos must start in CANDIDATE, WAITING or READY"
        }
        require(expiresAt == null || expiresAt > now) { "New todo must not be created already expired" }
        val entity = TodoEntity(
            id = id,
            type = type,
            title = title,
            payloadJson = payloadJson,
            sourceEventId = sourceEventId,
            status = initialStatus,
            priority = priority,
            availableAt = availableAt,
            expiresAt = expiresAt,
            claimedBy = null,
            claimedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(entity)
        return entity
    }

    suspend fun byId(id: String): TodoEntity? = dao.byId(id)

    /**
     * Atomic claim. The conditional DAO UPDATE is the concurrency primitive:
     * exactly one claimant can match WAITING/READY and non-expired state.
     */
    suspend fun claim(id: String, claimedBy: String, now: Long): TodoEntity? = db.withTransaction {
        if (dao.claim(id, claimedBy, now, now, now) == 1) {
            dao.byId(id)
        } else {
            null
        }
    }

    suspend fun releaseClaim(id: String, now: Long): TodoEntity = db.withTransaction {
        val current = dao.byId(id) ?: error("Unknown todo: $id")
        TodoStateMachine.requireTransition(current.status, TodoStatuses.READY)
        check(dao.releaseClaim(id, now) == 1) { "Could not release todo claim: $id" }
        dao.byId(id) ?: error("Todo disappeared during release: $id")
    }

    suspend fun markExecuted(id: String, now: Long): TodoEntity = transition(id, TodoStatuses.EXECUTED, now)

    suspend fun cancel(id: String, now: Long): TodoEntity = transition(id, TodoStatuses.CANCELLED, now)

    suspend fun fail(id: String, now: Long): TodoEntity = transition(id, TodoStatuses.FAILED, now)

    suspend fun expire(id: String, now: Long): TodoEntity = transition(id, TodoStatuses.EXPIRED, now)

    private suspend fun transition(id: String, newStatus: String, now: Long): TodoEntity = db.withTransaction {
        val current = dao.byId(id) ?: error("Unknown todo: $id")
        TodoStateMachine.requireTransition(current.status, newStatus)
        check(dao.transition(id, current.status, newStatus, now) == 1) {
            "Todo transition affected 0 rows: $id"
        }
        dao.byId(id) ?: error("Todo disappeared during transition: $id")
    }

    /** Recovers CLAIMED rows whose lease expired after process death (startup recovery). */
    suspend fun recoverStaleClaims(now: Long, staleClaimLeaseMillis: Long): StaleClaimRecovery =
        db.withTransaction {
            val stale = dao.staleClaims(now - staleClaimLeaseMillis)
            var returnedToReady = 0
            var expired = 0
            stale.forEach { todo ->
                val alreadyExpired = todo.expiresAt != null && todo.expiresAt <= now
                if (alreadyExpired) {
                    if (dao.transition(todo.id, TodoStatuses.CLAIMED, TodoStatuses.EXPIRED, now) == 1) expired++
                } else if (dao.releaseClaim(todo.id, now) == 1) {
                    returnedToReady++
                }
            }
            StaleClaimRecovery(staleCount = stale.size, returnedToReady = returnedToReady, expired = expired)
        }
}

data class StaleClaimRecovery(
    val staleCount: Int,
    val returnedToReady: Int,
    val expired: Int,
)

class MemoryRepository(private val db: AppDatabase, private val dao: MemoryDao) {

    /** Serializes candidate batches so duplicate decisions use a fresh merge base. */
    private val writeMutex = Mutex()

    suspend fun save(entity: MemoryEntity) {
        dao.upsert(entity)
    }

    suspend fun byId(id: String): MemoryEntity? = dao.byId(id)

    suspend fun activeAt(now: Long): List<MemoryEntity> = dao.activeAt(now)

    suspend fun supersede(id: String, replacementId: String, now: Long): MemoryEntity = db.withTransaction {
        val current = dao.byId(id) ?: error("Unknown memory: $id")
        MemoryStateMachine.requireTransition(current.status, MemoryStatuses.SUPERSEDED)
        check(dao.transition(id, current.status, MemoryStatuses.SUPERSEDED, now) == 1) {
            "Could not supersede memory $id"
        }
        val replacement = dao.byId(replacementId) ?: error("Replacement memory does not exist: $replacementId")
        check(replacement.supersedesMemoryId == id) {
            "Replacement memory must reference the superseded memory id"
        }
        replacement
    }

    /**
     * Phase 3 structured memory write with deterministic merge/conflict rules.
     * Exact duplicates are merged in-place; explicit supersedes references and
     * high-similarity same-type conflicts with stronger new confidence create a
     * new ACTIVE memory and move the old one to SUPERSEDED.
     */
    suspend fun writeCandidates(
        candidates: List<MemoryCandidate>,
        now: Long,
    ): MemoryWriteReport = writeMutex.withLock {
        db.withTransaction {
        val active = dao.activeAt(now).toMutableList()
        var inserted = 0
        var merged = 0
        var superseded = 0
        var skipped = 0
        val touchedIds = mutableListOf<String>()

        candidates.forEach { candidate ->
            val explicitTargetId = candidate.supersedesMemoryId
            if (explicitTargetId != null) {
                val target = dao.byId(explicitTargetId)
                val transitioned = target != null && runCatching {
                    MemoryStateMachine.requireTransition(target.status, MemoryStatuses.SUPERSEDED)
                }.isSuccess
                if (target == null || !transitioned) {
                    skipped++
                    return@forEach
                }
                val entity = candidate.toEntity(now, explicitTargetId)
                dao.upsert(entity)
                check(dao.transition(target.id, target.status, MemoryStatuses.SUPERSEDED, now) == 1) {
                    "Could not supersede explicit target ${target.id}"
                }
                active.removeAll { it.id == target.id }
                active += entity
                superseded++
                inserted++
                touchedIds += entity.id
                return@forEach
            }

            val exactDuplicate = active.firstOrNull {
                it.type == candidate.type &&
                    normalizedContent(it.content) == normalizedContent(candidate.content)
            }
            if (exactDuplicate != null) {
                val mergedEntity = exactDuplicate.mergedWith(candidate, now)
                dao.upsert(mergedEntity)
                active.replace(exactDuplicate, mergedEntity)
                merged++
                touchedIds += exactDuplicate.id
                return@forEach
            }

            val conflict = active.firstOrNull {
                it.type == candidate.type &&
                    (
                        (candidate.subject != null && it.subject == candidate.subject) ||
                            contentSimilarity(it.content, candidate.content) >= CONFLICT_SIMILARITY_THRESHOLD
                        )
            }
            if (conflict != null && candidate.confidence > conflict.confidence) {
                val entity = candidate.toEntity(now, conflict.id)
                dao.upsert(entity)
                MemoryStateMachine.requireTransition(conflict.status, MemoryStatuses.SUPERSEDED)
                check(dao.transition(conflict.id, conflict.status, MemoryStatuses.SUPERSEDED, now) == 1) {
                    "Could not supersede conflicting memory ${conflict.id}"
                }
                active.replace(conflict, entity)
                superseded++
                inserted++
                touchedIds += entity.id
            } else if (conflict != null) {
                val mergedEntity = conflict.mergedWith(candidate, now)
                dao.upsert(mergedEntity)
                active.replace(conflict, mergedEntity)
                merged++
                touchedIds += conflict.id
            } else {
                val entity = candidate.toEntity(now, null)
                dao.upsert(entity)
                active += entity
                inserted++
                touchedIds += entity.id
            }
        }

        MemoryWriteReport(
            inserted = inserted,
            merged = merged,
            superseded = superseded,
            skipped = skipped,
            memoryIds = touchedIds.toList(),
        )
        }
    }

    private fun MutableList<MemoryEntity>.replace(previous: MemoryEntity, replacement: MemoryEntity) {
        val index = indexOfFirst { it.id == previous.id }
        if (index >= 0) this[index] = replacement else add(replacement)
    }

    private fun MemoryCandidate.toEntity(now: Long, supersedesMemoryId: String?): MemoryEntity {
        val resolvedValidUntil = resolvedValidUntil(now)
        return MemoryEntity(
            id = EntityId.generate("mem").value,
            type = type,
            subject = subject,
            content = content,
            summary = summary,
            importance = importance,
            confidence = confidence,
            validFrom = now,
            validUntil = resolvedValidUntil,
            sourceMessageId = sourceMessageId ?: evidenceMessageIds.firstOrNull(),
            sourceEventId = null,
            supersedesMemoryId = supersedesMemoryId,
            emotionAtEventJson = emotionAtEvent?.let { JsonCodec.encode(it) },
            currentAppraisalJson = currentAppraisal?.let { JsonCodec.encode(it) },
            lastReassessedAt = currentAppraisal?.let { now },
            status = MemoryStatuses.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun MemoryEntity.mergedWith(candidate: MemoryCandidate, now: Long): MemoryEntity {
        val candidateValidUntil = candidate.resolvedValidUntil(now)
        val currentEffectiveValidUntil = validUntil ?: defaultValidUntil(type, now)
        return copy(
            subject = subject ?: candidate.subject,
            content = candidate.content,
            summary = summary ?: candidate.summary,
            importance = maxOf(importance, candidate.importance),
            confidence = maxOf(confidence, candidate.confidence),
            validUntil = mergeValidUntil(currentEffectiveValidUntil, candidateValidUntil),
            sourceMessageId = sourceMessageId ?: candidate.sourceMessageId ?: candidate.evidenceMessageIds.firstOrNull(),
            emotionAtEventJson = emotionAtEventJson ?: candidate.emotionAtEvent?.let { JsonCodec.encode(it) },
            currentAppraisalJson = candidate.currentAppraisal?.let { JsonCodec.encode(it) } ?: currentAppraisalJson,
            lastReassessedAt = if (candidate.currentAppraisal != null) now else lastReassessedAt,
            updatedAt = now,
        )
    }

    private fun MemoryCandidate.resolvedValidUntil(now: Long): Long? =
        validUntil?.let { InstantJson.parseOrNull(it)?.toEpochMilli() }
            ?: when (validity) {
                MemoryValidityNames.TEMPORARY -> now + TEMPORARY_TTL_MILLIS
                MemoryValidityNames.SHORT_TERM -> now + SHORT_TERM_TTL_MILLIS
                else -> null
            }

    private fun defaultValidUntil(type: String, now: Long): Long? = when (type) {
        com.littlekitty.xiaokele.core.common.protocol.MemoryTypeNames.TEMPORARY_STATE -> now + TEMPORARY_TTL_MILLIS
        com.littlekitty.xiaokele.core.common.protocol.MemoryTypeNames.SHORT_TERM_MEMORY -> now + SHORT_TERM_TTL_MILLIS
        else -> null
    }

    private fun mergeValidUntil(current: Long?, candidate: Long?): Long? = when {
        current == null || candidate == null -> null
        else -> maxOf(current, candidate)
    }

    companion object {
        const val CONFLICT_SIMILARITY_THRESHOLD = 0.8
        const val TEMPORARY_TTL_MILLIS = 24L * 60L * 60L * 1_000L
        const val SHORT_TERM_TTL_MILLIS = 14L * 24L * 60L * 60L * 1_000L

        fun normalizedContent(raw: String): String = raw.trim().lowercase().replace(Regex("\\s+"), "")

        fun contentSimilarity(left: String, right: String): Double {
            val a = normalizedContent(left)
            val b = normalizedContent(right)
            if (a == b) return 1.0
            if (a.isBlank() || b.isBlank()) return 0.0
            val aGrams = bigrams(a)
            val bGrams = bigrams(b)
            val union = aGrams + bGrams
            if (union.isEmpty()) return 0.0
            val overlap = aGrams.intersect(bGrams).size
            return (2.0 * overlap) / union.size
        }

        private fun bigrams(raw: String): Set<String> =
            raw.windowed(size = 2, step = 1, partialWindows = false).toSet()
    }
}

data class MemoryWriteReport(
    val inserted: Int,
    val merged: Int,
    val superseded: Int,
    val skipped: Int,
    val memoryIds: List<String>,
)

class PetStateRepository(private val db: AppDatabase, private val dao: PetStateDao) {

    suspend fun current(): PetStateEntity? = dao.get()

    /**
     * All Pet State changes go through this reducer. The singleton primary key
     * guarantees at most one current state row; the transaction validates the
     * deterministic transition before the REPLACE is committed.
     */
    suspend fun setState(entity: PetStateEntity, now: Long): PetStateEntity = db.withTransaction {
        require(entity.stateId in PetStateIds.all) { "Unknown pet state: ${entity.stateId}" }
        require(entity.expiresAt == null || entity.expiresAt > now) { "Pet state must not be created already expired" }
        val current = dao.get()
        val from = current?.stateId ?: PetStateIds.IDLE
        PetStateMachine.requireTransition(from, entity.stateId)
        val normalized = entity.copy(singletonId = PetStateEntity.SINGLETON_ID, updatedAt = now)
        dao.upsert(normalized)
        normalized
    }

    suspend fun resetToIdle(now: Long): PetStateEntity = db.withTransaction {
        val entity = PetStateEntity(
            singletonId = PetStateEntity.SINGLETON_ID,
            stateId = PetStateIds.IDLE,
            startsAt = now,
            updatedAt = now,
        )
        dao.upsert(entity)
        entity
    }
}

class ActionPlanRepository(private val db: AppDatabase, private val dao: ActionPlanDao) {

    /**
     * `active_event_key` is a declared unique index column: while a plan is
     * PENDING/VALIDATED/RUNNING it stores event_id, so SQLite itself rejects a
     * second active plan for the same event even under concurrent writers.
     */
    suspend fun createForEvent(
        eventId: String,
        agentOutputJson: String,
        now: Long,
        decisionSummary: String? = null,
        id: String = EntityId.generate("plan").value,
    ): ActionPlanEntity = db.withTransaction {
        require(agentOutputJson.isNotBlank()) { "agent_output_json must not be blank" }
        val entity = ActionPlanEntity(
            id = id,
            eventId = eventId,
            agentOutputJson = agentOutputJson,
            decisionSummary = decisionSummary,
            status = "PENDING",
            activeEventKey = eventId,
            createdAt = now,
        )
        try {
            dao.insert(entity)
        } catch (e: SQLiteConstraintException) {
            throw IllegalStateException("Event $eventId already has an active ActionPlan", e)
        }
        entity
    }

    suspend fun byId(id: String): ActionPlanEntity? = dao.byId(id)

    suspend fun activeForEvent(eventId: String): ActionPlanEntity? = dao.activeForEvent(eventId)

    suspend fun transition(id: String, newStatus: String): ActionPlanEntity = db.withTransaction {
        val current = dao.byId(id) ?: error("Unknown action plan: $id")
        ActionPlanStateMachine.requireTransition(current.status, newStatus)
        check(dao.updateStatusWithActiveKey(id, newStatus) == 1) { "ActionPlan transition affected 0 rows: $id" }
        dao.byId(id) ?: error("ActionPlan disappeared during transition: $id")
    }
}
