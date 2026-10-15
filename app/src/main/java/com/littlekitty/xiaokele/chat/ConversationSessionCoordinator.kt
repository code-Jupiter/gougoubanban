package com.littlekitty.xiaokele.chat

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.ConversationSessionEntity
import com.littlekitty.xiaokele.core.database.entity.ConversationSessionModes
import com.littlekitty.xiaokele.core.database.entity.ConversationSessionStatuses
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import com.littlekitty.xiaokele.external.ExternalContextResolver
import com.littlekitty.xiaokele.memory.MemoryAnalysisProcessor
import com.littlekitty.xiaokele.memory.MemoryAnalysisRequest
import com.littlekitty.xiaokele.memory.MemoryAnalysisOutcome
import org.koin.java.KoinJavaComponent
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Implements the user-facing session rules independently of creative turns. */
class ConversationSessionCoordinator(
    private val appContext: Context,
    private val db: AppDatabase,
    private val timeProvider: TimeProvider,
    private val memoryAnalysisProcessor: MemoryAnalysisProcessor,
) {
    suspend fun onUserMessage(message: MessageEntity) {
        touch(message, isUser = true)
        ConversationSessionScheduler.reconcile(appContext)
    }

    suspend fun onAssistantMessage(message: MessageEntity) {
        val session = touch(message, isUser = false)
        if (session.mode == ConversationSessionModes.LOW_FREQUENCY &&
            session.userMessageCount >= LOW_FREQUENCY_BATCH_USER_MESSAGE_COUNT
        ) {
            db.conversationSessionDao().upsert(
                session.copy(status = ConversationSessionStatuses.CLOSED, updatedAt = message.createdAt),
            )
            ConversationSessionScheduler.enqueueSettlement(appContext)
        } else {
            ConversationSessionScheduler.reconcile(appContext)
        }
    }

    suspend fun settleInactiveSessions(now: Long = timeProvider.now().toEpochMilli()): Boolean {
        var retryRequired = false
        // SETTLING is a single-worker claim. Only a claim old enough to have
        // survived process death may be returned to the retry queue; never
        // treat a freshly claimed row as work for a second worker.
        db.conversationSessionDao().recoverStaleSettling(
            staleBefore = now - SETTLING_RECOVERY_TIMEOUT_MILLIS,
            now = now,
        )
        val active = db.conversationSessionDao().active()
        if (active != null && now - active.lastMessageAt >= SESSION_END_GAP_MILLIS) {
            db.conversationSessionDao().transition(
                active.id, ConversationSessionStatuses.ACTIVE, ConversationSessionStatuses.CLOSED, now,
            )
        }
        db.conversationSessionDao().awaitingSettlement(MAX_SETTLEMENTS_PER_RUN).forEach { session ->
            // Keep a monotonic claim token in the existing updated_at column.
            // A worker that outlives lease recovery must not be able to
            // settle (or retry) the newer worker's claim.
            val claimUpdatedAt = maxOf(now, session.updatedAt + 1L)
            val attempt = db.withTransaction {
                val key = "memory_settlement_attempts:${session.id}"
                val used = db.metadataDao().get(key)?.toIntOrNull() ?: 0
                if (used >= MAX_SETTLEMENT_ATTEMPTS) {
                    db.conversationSessionDao().transition(session.id, session.status, SETTLEMENT_EXHAUSTED, claimUpdatedAt)
                    return@withTransaction null
                }
                if (db.conversationSessionDao().transition(
                        session.id, session.status, ConversationSessionStatuses.SETTLING, claimUpdatedAt) != 1) {
                    return@withTransaction null
                }
                db.metadataDao().put(com.littlekitty.xiaokele.core.database.AppMetadataEntity(
                    key = key, value = (used + 1).toString(), updatedAtEpochMillis = claimUpdatedAt))
                used + 1
            } ?: return@forEach
            val messages = messagesFor(session).filter { it.status == MessageStatuses.COMPLETED }
            val lastUser = messages.lastOrNull { it.role == FrontChatPipeline.ROLE_USER }
            val lastAssistant = messages.lastOrNull { it.role == FrontChatPipeline.ROLE_ASSISTANT }
            val settled = if (lastUser != null && lastAssistant != null) {
                memoryAnalysisProcessor.analyze(
                    MemoryAnalysisRequest(
                        userMessageId = lastUser.id,
                        assistantMessageId = lastAssistant.id,
                        eventId = "session:${session.id}",
                        rawDecisionJson = "",
                        analysisMode = "SESSION_SETTLEMENT",
                        sourceMessageIds = messages.map { it.id },
                        sourcePayloadJson = com.littlekitty.xiaokele.core.common.json.JsonCodec.encode(
                            messages.map { message ->
                                ConversationConsolidationMessage(
                                    id = message.id,
                                    role = message.role,
                                    content = message.content,
                                    createdAt = com.littlekitty.xiaokele.core.common.time.InstantJson.formatUtc(
                                        Instant.ofEpochMilli(message.createdAt),
                                    ),
                                )
                            },
                        ),
                        settlementClaimUpdatedAt = claimUpdatedAt,
                    ),
                ) !is MemoryAnalysisOutcome.Failure
            } else {
                true
            }
            val finished = db.conversationSessionDao().finishSettlement(
                id = session.id,
                expectedClaimUpdatedAt = claimUpdatedAt,
                status = if (settled) ConversationSessionStatuses.SETTLED
                    else if (attempt >= MAX_SETTLEMENT_ATTEMPTS) SETTLEMENT_EXHAUSTED
                    else ConversationSessionStatuses.RETRYABLE,
                settledAt = if (settled) now else null,
                now = maxOf(now, claimUpdatedAt),
            )
            if (!settled && finished == 1 && attempt < MAX_SETTLEMENT_ATTEMPTS) retryRequired = true
        }
        return retryRequired
    }

    suspend fun activeSession(): ConversationSessionEntity? = db.conversationSessionDao().active()

    private suspend fun touch(message: MessageEntity, isUser: Boolean): ConversationSessionEntity {
        val now = message.createdAt
        return db.withTransaction {
            val dao = db.conversationSessionDao()
            val active = dao.active()
            val current = if (active == null || now - active.lastMessageAt >= SESSION_END_GAP_MILLIS) {
                active?.let { dao.upsert(it.copy(status = ConversationSessionStatuses.CLOSED, updatedAt = now)) }
                ConversationSessionEntity(
                    id = EntityId.generate("conv_session").value,
                    mode = ConversationSessionModes.LOW_FREQUENCY,
                    status = ConversationSessionStatuses.ACTIVE,
                    operationalDayKey = operationalDayKey(now),
                    startedAt = now,
                    lastMessageAt = now,
                    firstMessageId = message.id,
                    lastMessageId = message.id,
                    userMessageCount = if (isUser) 1 else 0,
                    createdAt = now,
                    updatedAt = now,
                )
            } else {
                active.copy(
                    lastMessageAt = now,
                    lastMessageId = message.id,
                    userMessageCount = active.userMessageCount + if (isUser) 1 else 0,
                    updatedAt = now,
                )
            }
            val high = isUser && recentUserMessageCount(current.firstMessageId, now) >= HIGH_FREQUENCY_USER_MESSAGE_COUNT
            val next = current.copy(mode = if (high || current.mode == ConversationSessionModes.HIGH_FREQUENCY) {
                ConversationSessionModes.HIGH_FREQUENCY
            } else {
                ConversationSessionModes.LOW_FREQUENCY
            })
            dao.upsert(next)
            next
        }
    }

    private suspend fun recentUserMessageCount(firstId: String, now: Long): Int {
        val all = db.messageDao().allAscending()
        val firstIndex = all.indexOfFirst { it.id == firstId }.coerceAtLeast(0)
        return all.drop(firstIndex).count {
            it.role == FrontChatPipeline.ROLE_USER &&
                it.status != MessageStatuses.FAILED &&
                it.createdAt >= now - HIGH_FREQUENCY_WINDOW_MILLIS
        }
    }

    private suspend fun operationalDayKey(now: Long): String {
        val zone = timeProvider.zone()
        // The public edition has no shift, so the operational day boundary is a
        // fixed local hour rather than a shift-type-dependent one.
        val boundaryHour = OPERATIONAL_DAY_BOUNDARY_HOUR
        var boundary = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
            .withHour(boundaryHour).withMinute(0).withSecond(0).withNano(0)
        if (ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).isBefore(boundary)) boundary = boundary.minusDays(1)
        // A boundary is postponed hour-by-hour while the preceding hour has chat.
        while (true) {
            val preceding = db.messageDao().between(
                boundary.minusHours(1).toInstant().toEpochMilli(),
                boundary.toInstant().toEpochMilli(),
            )
            if (preceding.isEmpty() || boundary.toInstant().toEpochMilli() > now) break
            boundary = boundary.plusHours(1)
        }
        return boundary.toLocalDate().toString() + "@" + boundary.hour
    }

    private suspend fun messagesFor(session: ConversationSessionEntity): List<MessageEntity> {
        val all = db.messageDao().allAscending()
        val start = all.indexOfFirst { it.id == session.firstMessageId }
        val end = all.indexOfLast { it.id == session.lastMessageId }
        return if (start >= 0 && end >= start) all.subList(start, end + 1)
            .filterNot { it.errorCode == com.littlekitty.xiaokele.provider.CompanionFallback.MESSAGE_MARKER }
        else emptyList()
    }

    companion object {
        const val MAX_SETTLEMENT_ATTEMPTS = 3
        const val SETTLEMENT_EXHAUSTED = "EXHAUSTED"
        const val HIGH_FREQUENCY_USER_MESSAGE_COUNT = 5
        const val LOW_FREQUENCY_BATCH_USER_MESSAGE_COUNT = 5
        const val HIGH_FREQUENCY_WINDOW_MILLIS = 15L * 60L * 1_000L
        const val SESSION_END_GAP_MILLIS = 45L * 60L * 1_000L
        const val OPERATIONAL_DAY_BOUNDARY_HOUR = 4
        private const val SETTLING_RECOVERY_TIMEOUT_MILLIS = 5L * 60L * 1_000L
        private const val MAX_SETTLEMENTS_PER_RUN = 8
    }
}

class ConversationSessionWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = runCatching {
        val coordinator = KoinJavaComponent.getOrNull<ConversationSessionCoordinator>(ConversationSessionCoordinator::class.java)
            ?: return Result.failure()
        if (coordinator.settleInactiveSessions()) return if (runAttemptCount < 2) Result.retry() else Result.failure()
        ConversationSessionScheduler.reconcile(applicationContext)
        Result.success()
    }.getOrElse {
        if (it is kotlinx.coroutines.CancellationException) throw it
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }

    companion object { const val UNIQUE_WORK = "conversation_session_settlement_v1" }
}

object ConversationSessionScheduler {
    fun enqueueSettlement(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ConversationSessionWorker.UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ConversationSessionWorker>().build(),
        )
    }

    suspend fun reconcile(context: Context) {
        val coordinator = runCatching {
            KoinJavaComponent.getOrNull<ConversationSessionCoordinator>(ConversationSessionCoordinator::class.java)
        }.getOrNull() ?: return
        val active = coordinator.activeSession()
        val manager = WorkManager.getInstance(context)
        if (active == null) {
            manager.cancelUniqueWork(ConversationSessionWorker.UNIQUE_WORK)
            return
        }
        val now = System.currentTimeMillis()
        val delay = (active.lastMessageAt + ConversationSessionCoordinator.SESSION_END_GAP_MILLIS - now).coerceAtLeast(0L)
        manager.enqueueUniqueWork(
            ConversationSessionWorker.UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ConversationSessionWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build(),
        )
    }
}
