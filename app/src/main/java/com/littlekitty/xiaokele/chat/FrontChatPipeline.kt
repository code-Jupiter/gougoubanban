package com.littlekitty.xiaokele.chat

import androidx.room.withTransaction
import com.littlekitty.xiaokele.animation.AnimationAdapter
import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionNames
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.AgentDecisionValidation
import com.littlekitty.xiaokele.core.common.protocol.AgentDecisionValidator
import com.littlekitty.xiaokele.core.common.protocol.AgentOutput
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.ActionExecutionEntity
import com.littlekitty.xiaokele.core.database.entity.ActionPlanEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.entity.PetStateEntity
import com.littlekitty.xiaokele.core.database.repository.TodoClaimCoordinator
import com.littlekitty.xiaokele.core.database.state.ActionExecutionStateMachine
import com.littlekitty.xiaokele.core.database.state.ActionExecutionStatuses
import com.littlekitty.xiaokele.core.database.state.ActionPlanStateMachine
import com.littlekitty.xiaokele.core.database.state.ActionPlanStatuses
import com.littlekitty.xiaokele.core.database.state.EventStateMachine
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import com.littlekitty.xiaokele.core.database.state.PetStateIds
import com.littlekitty.xiaokele.core.database.state.PetStateMachine
import com.littlekitty.xiaokele.core.network.model.ModelService
import com.littlekitty.xiaokele.handoff.DeliveryEngagement
import com.littlekitty.xiaokele.handoff.NoOpDeliveryEngagement
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogCategories
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogNames
import com.littlekitty.xiaokele.core.database.entity.DiagnosticLogStatuses
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import com.littlekitty.xiaokele.memory.MemoryAnalysisHook
import com.littlekitty.xiaokele.memory.MemoryAnalysisRequest
import com.littlekitty.xiaokele.memory.NoOpMemoryAnalysisHook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import com.littlekitty.xiaokele.creative.CreativeChatCoordinator
import com.littlekitty.xiaokele.provider.ReplyCoordinator
import com.littlekitty.xiaokele.provider.ReplyEntryPoint
import com.littlekitty.xiaokele.provider.CompanionFallback
import com.littlekitty.xiaokele.provider.CompanionFallbackReplyCatalog
import com.littlekitty.xiaokele.core.common.protocol.CharacterContext
import com.littlekitty.xiaokele.core.common.protocol.CurrentContext
import kotlinx.coroutines.withTimeoutOrNull
import com.littlekitty.xiaokele.settings.RouteConfigurationBarrier

/**
 * Phase 2 front-chat pipeline (architecture section 8):
 *
 * save user message -> build minimal ContextPack -> ModelService ->
 * AgentDecision validation -> one transaction for ActionPlan / assistant
 * reply / ActionExecutions / Pet State reducer -> UI animation id.
 *
 * The model never writes scheduling state directly (C4); every persisted
 * transition is checked by the Phase 1 deterministic state machines.
 */
class FrontChatPipeline(
    private val db: AppDatabase,
    private val modelService: ModelService,
    private val contextBuilder: ChatContextBuilder,
    private val animationAdapter: AnimationAdapter,
    private val timeProvider: TimeProvider,
    private val memoryAnalysisHook: MemoryAnalysisHook = NoOpMemoryAnalysisHook,
    private val todoClaimCoordinator: TodoClaimCoordinator? = null,
    private val deliveryEngagement: DeliveryEngagement = NoOpDeliveryEngagement,
    private val diagnosticsLogger: DiagnosticsLogger? = null,
    private val frontChatModelService: FrontChatModelService? = null,
    private val localChatToolExecutor: LocalChatToolExecutor? = null,
    private val creativeChatCoordinator: CreativeChatCoordinator? = null,
    private val conversationSessionCoordinator: ConversationSessionCoordinator? = null,
    private val replyCoordinator: ReplyCoordinator? = null,
    private val fallbackReplyCatalog: CompanionFallbackReplyCatalog? = null,
) {

    private val mutex = Mutex()
    /** Serializes epoch mutation with the final Room publication critical section. */
    private val attemptCommitMutex = Mutex()
    private val userAttemptLock = Any()
    private var latestUserAttempt: UserAttempt? = null
    private val idleFollowUpCancellationLock = Any()
    private var activeIdleFollowUpEventId: String? = null
    private val cancelledIdleFollowUpEventIds = mutableSetOf<String>()

    suspend fun send(rawText: String): ChatSendOutcome {
        val text = rawText.trim()
        if (text.isEmpty()) {
            return ChatSendOutcome.Failure(AppError(ErrorCode.INVALID_ARGUMENT))
        }
        if (text.length > MAX_USER_MESSAGE_LENGTH) {
            return ChatSendOutcome.Failure(
                AppError(ErrorCode.INVALID_ARGUMENT, userMessage = "消息太长了，短一点就能看清楚"),
            )
        }

        val turn = createTurn(text)
        val attempt = beginUserAttempt(turn)
        diagnosticsLogger?.record(
            category = DiagnosticLogCategories.CHAT,
            name = DiagnosticLogNames.CHAT_TURN,
            status = DiagnosticLogStatuses.STARTED,
            subjectId = turn.message.id,
            summary = "用户消息已写入，开始构建上下文并请求 AI",
            payloadJson = stagePayload(turn.eventId, "CHAT_TURN_STARTED") {
                put("message_id", turn.message.id)
                put("raw_user_content", turn.message.content)
            },
        )
        val outcome = executeTurn(turn, attempt)
        diagnosticsLogger?.record(
            category = DiagnosticLogCategories.AI,
            name = DiagnosticLogNames.TURN_COMPLETED,
            status = if (outcome is ChatSendOutcome.Success) DiagnosticLogStatuses.SUCCESS else DiagnosticLogStatuses.FAILED,
            subjectId = turn.message.id,
            payloadJson = stagePayload(turn.eventId, "CHAT_TURN_COMPLETED") {
                put("outcome", outcome.toString().take(2_000))
            },
            summary = outcome.toString().take(500),
        )
        return outcome
    }

    suspend fun retry(messageId: String): ChatSendOutcome {
        val turn = mutex.withLock {
            prepareRetry(messageId)
        } ?: return ChatSendOutcome.Failure(
            AppError(ErrorCode.INVALID_ARGUMENT),
            userMessageId = messageId,
        )
        val attempt = beginUserAttempt(turn)
        return executeTurn(turn, attempt)
    }

    /** Stop only the newest foreground user attempt; older attempts are already superseded. */
    suspend fun cancelCurrentUserAttempt(expectedMessageId: String? = null): Boolean {
        return attemptCommitMutex.withLock {
            val attempt = synchronized(userAttemptLock) {
                latestUserAttempt?.takeIf { expectedMessageId == null || it.messageId == expectedMessageId }
                    ?.also { latestUserAttempt = null }
            }
                ?: return@withLock false
            markAttemptTerminal(
                attempt = attempt,
                errorCode = FRONT_CHAT_CANCELLED_BY_USER,
                errorDetail = FRONT_CHAT_CANCELLED,
            )
            true
        }
    }

    /** Generates one continuation without creating a hidden user message. */
    suspend fun sendIdleFollowUp(anchorAssistantMessageId: String): ChatSendOutcome = mutex.withLock {
        val anchor = db.messageDao().byId(anchorAssistantMessageId)
        if (anchor == null || anchor.role != ROLE_ASSISTANT || anchor.status != MessageStatuses.COMPLETED) {
            return@withLock ChatSendOutcome.Failure(AppError(ErrorCode.INVALID_ARGUMENT))
        }
        existingIdleFollowUp(anchorAssistantMessageId)?.let { existing ->
            return@withLock ChatSendOutcome.Success(
                userMessageId = anchorAssistantMessageId,
                assistantMessageId = existing.id,
                animationId = existing.animationId,
            )
        }

        val now = timeProvider.now().toEpochMilli()
        val eventId = EntityId.generate("evt").value
        registerActiveIdleFollowUp(eventId)
        try {
            db.eventDao().upsert(newIdleFollowUpEvent(eventId, anchorAssistantMessageId, now))
            val context = try {
                contextBuilder.buildForIdleFollowUp(anchor, eventId)
            } catch (cancelled: CancellationException) {
                diagnosticsLogger?.record(
                    category = DiagnosticLogCategories.CHAT,
                    name = DiagnosticLogNames.CONTEXT_BUILT,
                    status = DiagnosticLogStatuses.FAILED,
                    subjectId = eventId,
                    summary = "前台续话上下文构建被取消",
                    payloadJson = stagePayload(eventId, "CONTEXT_BUILD_CANCELLED"),
                )
                withContext(NonCancellable) {
                    failTurn(null, eventId, cancelledRequestError(isIdleFollowUp = true))
                }
                throw cancelled
            } catch (throwable: Throwable) {
                val error = AppError(
                    code = ErrorCode.LOCAL_STORAGE_FAILURE,
                    technicalDetail = throwable.message,
                )
                diagnosticsLogger?.record(
                    category = DiagnosticLogCategories.CHAT,
                    name = DiagnosticLogNames.CONTEXT_BUILT,
                    status = DiagnosticLogStatuses.FAILED,
                    subjectId = eventId,
                    summary = "前台续话上下文构建失败",
                    payloadJson = stagePayload(eventId, "CONTEXT_BUILD_FAILED") {
                        put("error_code", error.code.name)
                        error.technicalDetail?.let { put("technical_detail", it) }
                    },
                )
                runCatching { failTurn(null, eventId, error) }
                return@withLock ChatSendOutcome.Failure(error)
            }
            executeGeneratedResponse(
                eventId = eventId,
                userMessageId = null,
                outcomeUserMessageId = anchorAssistantMessageId,
                context = context,
            )
        } finally {
            clearActiveIdleFollowUp(eventId)
        }
    }

    private suspend fun existingIdleFollowUp(anchorAssistantMessageId: String): MessageEntity? =
        db.messageDao().idleFollowUpAssistants()
            .firstOrNull { record ->
                runCatching {
                    JsonCodec.json.parseToJsonElement(record.eventPayloadJson)
                        .jsonObject["anchor_assistant_message_id"]
                        ?.jsonPrimitive
                        ?.contentOrNull == anchorAssistantMessageId
                }.getOrDefault(false)
            }
            ?.assistant

    /** Invalidates any auto-follow-up currently being generated by the chat UI. */
    fun cancelActiveIdleFollowUps() {
        synchronized(idleFollowUpCancellationLock) {
            activeIdleFollowUpEventId?.let(cancelledIdleFollowUpEventIds::add)
        }
    }

    private suspend fun createTurn(text: String): ChatTurn {
        val now = timeProvider.now().toEpochMilli()
        val messageId = EntityId.generate("msg").value
        val eventId = EntityId.generate("evt").value
        val event = newChatEvent(eventId, messageId, now)
        db.withTransaction {
            db.eventDao().upsert(event)
            db.messageDao().upsert(
                MessageEntity(
                    id = messageId,
                    role = ROLE_USER,
                    content = text,
                    createdAt = now,
                    sourceEventId = eventId,
                    status = MessageStatuses.SENDING,
                ),
            )
            // A new user message is the user's reply to the latest delivered
            // event/todo; associate the delivery engagement idempotently.
            deliveryEngagement.onUserReplied(now)
            creativeChatCoordinator?.recordUserDialogueTurnInTransaction(
                MessageEntity(
                    id = messageId,
                    role = ROLE_USER,
                    content = text,
                    createdAt = now,
                    sourceEventId = eventId,
                    status = MessageStatuses.SENDING,
                ),
            )
        }
        val saved = checkNotNull(db.messageDao().byId(messageId)) { "saved user message disappeared" }
        creativeChatCoordinator?.onUserMessagePersisted(saved)
        conversationSessionCoordinator?.onUserMessage(saved)
        return ChatTurn(saved, eventId)
    }

    private suspend fun prepareRetry(messageId: String): ChatTurn? {
        val existing = db.messageDao().byId(messageId) ?: return null
        if (existing.role != ROLE_USER || existing.status != MessageStatuses.FAILED) return null

        val now = timeProvider.now().toEpochMilli()
        val eventId = EntityId.generate("evt").value
        val prepared = db.withTransaction {
            val oldEventId = existing.sourceEventId ?: return@withTransaction false
            val oldEvent = db.eventDao().byId(oldEventId) ?: return@withTransaction false
            // A user may explicitly retry one failed attempt, but repeated
            // taps must not mint fresh six-request attempts for the same
            // input. Reuse the durable event attempt_count as the retry
            // authorization and make the message update conditional on the
            // event id so concurrent callers cannot both win.
            // The terminal-failure transition increments attempt_count once
            // for the original request. The one explicit retry is therefore
            // allowed while the durable count is still at that first-attempt
            // value, but no further retry is allowed after another failure.
            if (oldEvent.attemptCount > MAX_MANUAL_RETRIES) return@withTransaction false
            if (EventStateMachine.canTransition(oldEvent.status, EventStatuses.FAILED)) {
                db.eventDao().updateStatus(oldEventId, EventStatuses.FAILED)
            }
            db.eventDao().upsert(newChatEvent(eventId, messageId, now, oldEvent.attemptCount)
                .copy(generationRequestCount = oldEvent.generationRequestCount))
            check(
                db.messageDao().prepareRetry(
                    id = messageId,
                    status = MessageStatuses.SENDING,
                    sourceEventId = eventId,
                    expectedSourceEventId = oldEventId,
                    expectedStatus = MessageStatuses.FAILED,
                ) == 1,
            ) {
                "prepareRetry affected 0 rows"
            }
            true
        }
        if (!prepared) return null
        val saved = checkNotNull(db.messageDao().byId(messageId)) { "retry user message disappeared" }
        return ChatTurn(saved, eventId)
    }

    private suspend fun beginUserAttempt(turn: ChatTurn): UserAttempt {
        return attemptCommitMutex.withLock {
            val transition = synchronized(userAttemptLock) {
                val previous = latestUserAttempt
                val attempt = UserAttempt(
                    messageId = turn.message.id,
                    eventId = turn.eventId,
                    epoch = (previous?.epoch ?: 0L) + 1L,
                )
                latestUserAttempt = attempt
                attempt to previous
            }
            val attempt = transition.first
            transition.second?.takeIf { it.messageId != attempt.messageId }?.let {
                markAttemptTerminal(
                    attempt = it,
                    errorCode = SUPERSEDED_BY_NEW_INPUT,
                    errorDetail = SUPERSEDED_BY_NEW_INPUT,
                )
            }
            attempt
        }
    }

    private fun ensureUserAttemptCurrent(attempt: UserAttempt) {
        val current = synchronized(userAttemptLock) { latestUserAttempt }
        if (current != attempt) throw SupersededAttemptCancellation()
    }

    private suspend fun markAttemptTerminal(
        attempt: UserAttempt,
        errorCode: String,
        errorDetail: String,
    ) {
        db.withTransaction {
            db.messageDao().updateStatusIfCurrent(
                id = attempt.messageId,
                expectedStatus = MessageStatuses.SENDING,
                newStatus = MessageStatuses.FAILED,
                errorCode = errorCode,
            )
            val event = db.eventDao().byId(attempt.eventId)
            if (event != null && EventStateMachine.canTransition(event.status, EventStatuses.FAILED)) {
                db.eventDao().markTerminalFailure(
                    id = attempt.eventId,
                    errorCode = errorCode,
                    errorDetail = errorDetail.take(MAX_ERROR_DETAIL_LENGTH),
                )
            }
        }
    }

    private suspend fun executeTurn(turn: ChatTurn, attempt: UserAttempt): ChatSendOutcome {
        val context = try {
            ensureUserAttemptCurrent(attempt)
            (withTimeoutOrNull(20_000L) { contextBuilder.buildFor(turn.message) }
                ?: throw IllegalStateException("context_build_timeout"))
                .also { ensureUserAttemptCurrent(attempt) }
        } catch (superseded: SupersededAttemptCancellation) {
            markAttemptTerminal(attempt, SUPERSEDED_BY_NEW_INPUT, SUPERSEDED_BY_NEW_INPUT)
            return ChatSendOutcome.Failure(supersededError(), userMessageId = turn.message.id)
        } catch (cancelled: CancellationException) {
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.CHAT,
                name = DiagnosticLogNames.CONTEXT_BUILT,
                status = DiagnosticLogStatuses.FAILED,
                subjectId = turn.eventId,
                summary = "前台聊天上下文构建被取消",
                payloadJson = stagePayload(turn.eventId, "CONTEXT_BUILD_CANCELLED"),
            )
            withContext(NonCancellable) {
                failTurn(turn.message.id, turn.eventId, cancelledRequestError(isIdleFollowUp = false))
            }
            throw cancelled
        } catch (throwable: Throwable) {
            val error = AppError(
                code = ErrorCode.LOCAL_STORAGE_FAILURE,
                technicalDetail = throwable.message,
            )
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.CHAT,
                name = DiagnosticLogNames.CONTEXT_BUILT,
                status = DiagnosticLogStatuses.FAILED,
                subjectId = turn.eventId,
                summary = "前台聊天上下文构建失败",
                payloadJson = stagePayload(turn.eventId, "CONTEXT_BUILD_FAILED") {
                    put("error_code", error.code.name)
                    error.technicalDetail?.let { put("technical_detail", it) }
                },
            )
            return deliverLocalFallback(turn.eventId, turn.message.id, turn.message.id, null, error, attempt)
        }
        return executeGeneratedResponse(
            eventId = turn.eventId,
            userMessageId = turn.message.id,
            outcomeUserMessageId = turn.message.id,
            context = context,
            userAttempt = attempt,
        )
    }

    private suspend fun executeGeneratedResponse(
        eventId: String,
        userMessageId: String?,
        outcomeUserMessageId: String,
        context: ContextPack,
        userAttempt: UserAttempt? = null,
    ): ChatSendOutcome {
        return try {
            userAttempt?.let(::ensureUserAttemptCurrent)
            transitionEvent(eventId, EventStatuses.PROCESSING)
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.CHAT,
                name = DiagnosticLogNames.CONTEXT_BUILT,
                status = DiagnosticLogStatuses.SUCCESS,
                subjectId = eventId,
                payloadJson = stagePayload(eventId, "CONTEXT_BUILT") {
                    put("operation", if (userMessageId == null) "front_chat_idle_follow_up" else "front_chat")
                    put("raw_context", JsonCodec.encode(context))
                },
                summary = "前台 AI 上下文已构建",
            )
            val operation = if (userMessageId == null) "front_chat_idle_follow_up" else "front_chat"
            val generated = try { generateWithDiagnostics(
                operation = operation,
                correlationId = eventId,
                subjectId = eventId,
                context = context,
            ) } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { ModelServiceResult.Failure(AppError(ErrorCode.MODEL_FAILURE, technicalDetail = "generation_exception")) }
            userAttempt?.let(::ensureUserAttemptCurrent)
            ensureIdleFollowUpIsNotCancelled(eventId, userMessageId)
            // A foreground user always gets a companion reply, even when C rejects,
            // the service is offline, or the final protocol is invalid. No extra API.
            val modelResult = if (userMessageId != null) foregroundResult(context, generated) else generated
            when (modelResult) {
                is ModelServiceResult.NoAction -> {
                    if (userMessageId != null) {
                        val error = AppError(
                            code = ErrorCode.MODEL_OUTPUT_INVALID,
                            technicalDetail = "foreground_no_action_forbidden",
                        )
                        failTurn(userMessageId, eventId, error)
                        ChatSendOutcome.Failure(error, userMessageId = userMessageId)
                    } else {
                        completeNoAction(eventId, modelResult.reason)
                        ChatSendOutcome.NoAction(
                            anchorMessageId = outcomeUserMessageId,
                            reason = modelResult.reason,
                        )
                    }
                }

                is ModelServiceResult.Failure -> {
                    failTurn(userMessageId, eventId, modelResult.error)
                    ChatSendOutcome.Failure(modelResult.error, userMessageId = userMessageId)
                }

                is ModelServiceResult.Success -> {
                    when (
                        val validation = AgentDecisionValidator.validate(
                            rawDecisionJson = modelResult.rawDecisionJson,
                            allowedAnimationIds = ALLOWED_ANIMATION_IDS,
                            requireFrontChatReply = true,
                        )
                    ) {
                        is AgentDecisionValidation.Invalid -> {
                            diagnosticsLogger?.record(
                                category = DiagnosticLogCategories.AI,
                                name = DiagnosticLogNames.DECISION_VALIDATION,
                                status = DiagnosticLogStatuses.FAILED,
                                subjectId = eventId,
                                payloadJson = stagePayload(eventId, "DECISION_VALIDATION_FAILED") {
                                    put("operation", operation)
                                    put("raw_output", modelResult.rawDecisionJson)
                                    put("violations", validation.violations.joinToString("; ") { "${it.path}:${it.message}" })
                                },
                                summary = "前台 AgentDecision 校验失败",
                            )
                            val error = AppError(
                                code = ErrorCode.MODEL_OUTPUT_INVALID,
                                technicalDetail = validation.violations.joinToString("; ") { "${it.path}:${it.message}" },
                            )
                            failTurn(userMessageId, eventId, error)
                            ChatSendOutcome.Failure(error, userMessageId = userMessageId)
                        }

                        is AgentDecisionValidation.Valid -> {
                            diagnosticsLogger?.record(
                                category = DiagnosticLogCategories.AI,
                                name = DiagnosticLogNames.DECISION_VALIDATION,
                                status = DiagnosticLogStatuses.SUCCESS,
                                subjectId = eventId,
                                payloadJson = stagePayload(eventId, "DECISION_VALIDATION_SUCCESS") {
                                    put("operation", operation)
                                    put("normalized_decision", JsonCodec.encode(validation.decision))
                                },
                                summary = "前台 AgentDecision 校验通过",
                            )
                            if (modelResult.commitGuard?.invoke() == false) {
                                val stale = AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "stale_model_route_before_persist")
                                if (userMessageId != null) return@executeGeneratedResponse deliverLocalFallback(
                                    eventId, userMessageId, outcomeUserMessageId, context, stale, userAttempt)
                                failTurn(userMessageId, eventId, stale)
                                return@executeGeneratedResponse ChatSendOutcome.Failure(stale, userMessageId = userMessageId)
                            }
                            runCatching {
                                persistSuccess(
                                    eventId = eventId,
                                    userMessageId = userMessageId,
                                    rawDecisionJson = modelResult.rawDecisionJson,
                                    validation = validation,
                                    commitGuard = modelResult.commitGuard,
                                    userAttempt = userAttempt,
                                )
                            }.fold(
                                onSuccess = { persisted ->
                                    diagnosticsLogger?.record(
                                        category = DiagnosticLogCategories.AI,
                                        name = DiagnosticLogNames.PLAN_PERSISTED,
                                        status = DiagnosticLogStatuses.SUCCESS,
                                        subjectId = eventId,
                                        payloadJson = stagePayload(eventId, "PLAN_PERSISTED") {
                                            put("assistant_message_id", persisted.first)
                                            persisted.second?.let { put("animation_id", it) }
                                            put("reply_evidence_ids", JsonCodec.encode(modelResult.evidenceIds))
                                            modelResult.trace?.let { trace ->
                                                trace.provider?.let { put("provider", it) }
                                                trace.modelId?.let { put("model_id", it) }
                                                trace.task?.let { put("task", it) }
                                                trace.promptVersion?.let { put("prompt_version", it) }
                                                trace.routeVersion?.let { put("route_version", it) }
                                                trace.inputTokens?.let { put("input_tokens", it) }
                                                trace.outputTokens?.let { put("output_tokens", it) }
                                                trace.totalTokens?.let { put("total_tokens", it) }
                                                put("cost_status", trace.costStatus ?: "UNKNOWN")
                                                trace.latencyMillis?.let { put("latency_millis", it) }
                                            }
                                        },
                                        summary = "前台 ActionPlan、回复和状态已提交",
                                    )
                                    val immediateMemoryAnalysis = userMessageId
                                        ?.let { db.messageDao().byId(it)?.content }
                                        ?.let(ImmediateMemorySignalDetector::matches)
                                        ?: false
                                    val localFallback = CompanionFallback.isFallback(modelResult.rawDecisionJson)
                                    if (userMessageId != null && immediateMemoryAnalysis && !localFallback) {
                                        runCatching {
                                            memoryAnalysisHook.enqueue(
                                                MemoryAnalysisRequest(
                                                    userMessageId = userMessageId,
                                                    assistantMessageId = persisted.first,
                                                    eventId = eventId,
                                                    rawDecisionJson = modelResult.rawDecisionJson,
                                                ),
                                            )
                                        }
                                    }
                                    db.messageDao().byId(persisted.first)?.takeUnless { localFallback }?.let { assistant ->
                                        conversationSessionCoordinator?.onAssistantMessage(assistant)
                                    }
                                    ChatSendOutcome.Success(outcomeUserMessageId, persisted.first, persisted.second)
                                },
                                onFailure = { throwable ->
                                    if (throwable is CancellationException) throw throwable
                                    val staleRoute = throwable.message == "stale_model_route_during_persist"
                                    val error = if (staleRoute) {
                                        AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "stale_model_route_during_persist")
                                    } else {
                                        AppError(
                                            code = ErrorCode.LOCAL_STORAGE_FAILURE,
                                            technicalDetail = throwable.message,
                                        )
                                    }
                                    diagnosticsLogger?.record(
                                        category = DiagnosticLogCategories.AI,
                                        name = DiagnosticLogNames.PLAN_PERSISTED,
                                        status = DiagnosticLogStatuses.FAILED,
                                        subjectId = eventId,
                                        payloadJson = stagePayload(eventId, "PLAN_PERSIST_FAILED") {
                                            put("error_code", error.code.name)
                                            error.technicalDetail?.let { put("technical_detail", it) }
                                        },
                                        summary = "前台 ActionPlan 落库失败",
                                    )
                                    if (userMessageId != null) {
                                        deliverLocalFallback(eventId, userMessageId, outcomeUserMessageId, context, error, userAttempt)
                                    } else {
                                        failTurn(userMessageId, eventId, error)
                                        ChatSendOutcome.Failure(error, userMessageId = userMessageId)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        } catch (superseded: SupersededAttemptCancellation) {
            userAttempt?.let {
                markAttemptTerminal(it, SUPERSEDED_BY_NEW_INPUT, SUPERSEDED_BY_NEW_INPUT)
            }
            ChatSendOutcome.Failure(supersededError(), userMessageId = userMessageId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                failTurn(
                    userMessageId,
                    eventId,
                    cancelledRequestError(isIdleFollowUp = userMessageId == null),
                )
            }
            throw cancelled
        } catch (throwable: Throwable) {
            val error = AppError(
                code = ErrorCode.LOCAL_STORAGE_FAILURE,
                technicalDetail = throwable.message,
            )
            if (userMessageId != null) {
                deliverLocalFallback(eventId, userMessageId, outcomeUserMessageId, context, error, userAttempt)
            } else {
                runCatching { failTurn(userMessageId, eventId, error) }
                ChatSendOutcome.Failure(error, userMessageId = userMessageId)
            }
        }
    }

    private suspend fun foregroundResult(context: ContextPack, result: ModelServiceResult): ModelServiceResult {
        val error = when (result) {
            is ModelServiceResult.Failure -> result.error
            is ModelServiceResult.NoAction -> AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "foreground_no_action")
            is ModelServiceResult.Success -> {
                if (result.commitGuard?.invoke() == false) {
                    AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "stale_model_route")
                } else if (AgentDecisionValidator.validate(result.rawDecisionJson, ALLOWED_ANIMATION_IDS,
                        requireFrontChatReply = true) is AgentDecisionValidation.Invalid) {
                    AppError(ErrorCode.MODEL_OUTPUT_INVALID, technicalDetail = "final_protocol_invalid")
                } else return result
            }
        }
        return CompanionFallback.result(context, error, fallbackReplyCatalog?.cached(context))
    }

    private suspend fun deliverLocalFallback(
        eventId: String,
        userMessageId: String,
        outcomeUserMessageId: String,
        context: ContextPack?,
        error: AppError,
        attempt: UserAttempt?,
    ): ChatSendOutcome {
        var localContext = context
        return try {
            attempt?.let(::ensureUserAttemptCurrent)
            if (localContext == null) {
                val core = db.characterCoreDao().latest()?.contentJson
                    ?.let { JsonCodec.json.parseToJsonElement(it).jsonObject } ?: buildJsonObject {}
                localContext = ContextPack(event = buildJsonObject {}, currentContext = CurrentContext("", "IDLE"),
                    character = CharacterContext(core))
            }
            if (db.eventDao().byId(eventId)?.status != EventStatuses.PROCESSING) {
                transitionEvent(eventId, EventStatuses.PROCESSING)
            }
            // Never generate in a failure path: a provider outage must not turn
            // into another provider request. Setup/app-start warmups populate
            // this catalogue ahead of time; older installs retain a safe local
            // template until the next successful warmup.
            val generatedReplies = fallbackReplyCatalog?.cached(localContext)
            val raw = CompanionFallback.result(localContext, error, generatedReplies).rawDecisionJson
            val validation = AgentDecisionValidator.validate(raw, ALLOWED_ANIMATION_IDS, requireFrontChatReply = true)
            check(validation is AgentDecisionValidation.Valid)
            val persisted = persistSuccess(eventId, userMessageId, raw, validation, userAttempt = attempt)
            ChatSendOutcome.Success(outcomeUserMessageId, persisted.first, persisted.second)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
            runCatching { failTurn(userMessageId, eventId, error) }
            // If storage itself is unavailable the controller still displays dog speech;
            // never claim a message was saved when Room could not commit it.
            val speech = CompanionFallback.text(localContext, error)
            ChatSendOutcome.Failure(error.copy(userMessage = speech), userMessageId, companionReply = speech)
        }
    }

    private suspend fun generateWithDiagnostics(
        operation: String,
        correlationId: String,
        subjectId: String?,
        context: ContextPack,
    ): ModelServiceResult {
        if (frontChatModelService != null && localChatToolExecutor != null) {
            // The tool loop below records exactly one request/response pair per
            // MiniMax round. Do not wrap it in the legacy final-result record,
            // otherwise acceptance exports double-count the last provider call.
            return generateFrontModelResult(context, operation, correlationId, subjectId)
        }
        val requestBody = JsonCodec.encode(context)
        diagnosticsLogger?.aiRequestStarted(
            operation = operation,
            correlationId = correlationId,
            subjectId = subjectId,
            serviceName = modelService.serviceName,
            requestBody = requestBody,
        )
        val result = generateFrontModelResult(context, operation, correlationId, subjectId)
        diagnosticsLogger?.recordAiResult(
            operation = operation,
            correlationId = correlationId,
            subjectId = subjectId,
            context = context,
            result = result,
        )
        return result
    }

    /** Front chat tool loop; legacy ModelService remains the test/fallback path. */
    private suspend fun generateFrontModelResult(
        context: ContextPack,
        operation: String,
        correlationId: String,
        subjectId: String?,
    ): ModelServiceResult {
        replyCoordinator?.let { coordinator ->
            // The profile-aware ReplyCoordinator owns A/B1/B2/C. This keeps
            // HER pure-text and prevents the expression model from selecting
            // tools; legacy tool-loop behavior remains available only when an
            // explicit coordinator is not wired (tests/legacy mode).
            return coordinator.generate(
                context,
                operation = operation,
                correlationId = correlationId,
                // Idle follow-ups are still owned by the front-chat transaction:
                // they must publish REPLY_CHAT and use the front-channel
                // validator, while retaining the legitimate NO_ACTION escape
                // hatch for an unhelpful continuation.
                entryPoint = if (operation == "front_chat_idle_follow_up") ReplyEntryPoint.FOREGROUND_IDLE else ReplyEntryPoint.FOREGROUND,
                // Persist the provider-request count on the owning event for
                // both idle and ordinary foreground attempts.  An in-memory
                // six-call counter alone would be reset by a process restart
                // or a late retry, effectively minting a fresh attempt.
                requestGate = {
                    reserveLifecycleGenerationRequest(
                        eventId = correlationId,
                        maxRequests = if (operation == "front_chat_idle_follow_up") {
                            MAX_LIFECYCLE_GENERATION_REQUESTS
                        } else {
                            MAX_FOREGROUND_GENERATION_REQUESTS
                        },
                    )
                },
            )
        }
        val model = frontChatModelService
        val executor = localChatToolExecutor
        if (model == null || executor == null) return modelService.generate(context)

        val turns = mutableListOf<FrontChatToolTurn>()
        val toolBudget = ToolCallBudget(
            maxCallsPerRound = FrontChatToolRegistry.MAX_CALLS_PER_ROUND,
            maxCallsPerSession = FrontChatToolRegistry.MAX_CALLS_PER_SESSION,
        )
        repeat(MAX_FRONT_TOOL_CALL_ROUNDS) { round ->
            val attempt = round + 1
            if (diagnosticsLogger != null) {
                diagnosticsLogger.aiRequestStarted(
                    operation = operation,
                    correlationId = correlationId,
                    subjectId = subjectId,
                    serviceName = modelService.serviceName,
                    requestBody = if (turns.isEmpty()) JsonCodec.encode(context) else buildToolTurnsPayload(turns),
                    requestKind = if (turns.isEmpty()) "front_chat_context" else "front_chat_tool_round",
                    attempt = attempt,
                )
            }
            when (val result = model.generateFrontChat(context, turns)) {
                is FrontChatModelResult.Final -> {
                    diagnosticsLogger?.aiResponseRecorded(
                        operation = operation,
                        correlationId = correlationId,
                        subjectId = subjectId,
                        status = DiagnosticLogStatuses.SUCCESS,
                        trace = result.trace,
                        fallbackRequestBody = buildToolTurnsPayload(turns),
                        normalizedOutput = result.rawDecisionJson,
                        attempt = attempt,
                    )
                    return ModelServiceResult.Success(
                        rawDecisionJson = result.rawDecisionJson,
                        trace = result.trace,
                    )
                }

                is FrontChatModelResult.Failure -> {
                    diagnosticsLogger?.aiResponseRecorded(
                        operation = operation,
                        correlationId = correlationId,
                        subjectId = subjectId,
                        status = DiagnosticLogStatuses.FAILED,
                        trace = result.trace,
                        fallbackRequestBody = buildToolTurnsPayload(turns),
                        errorCode = result.error.code.name,
                        technicalDetail = result.error.technicalDetail,
                        attempt = attempt,
                    )
                    return ModelServiceResult.Failure(
                        error = result.error,
                        trace = result.trace,
                    )
                }

                is FrontChatModelResult.ToolCalls -> {
                    diagnosticsLogger?.aiResponseRecorded(
                        operation = operation,
                        correlationId = correlationId,
                        subjectId = subjectId,
                        status = DiagnosticLogStatuses.SUCCESS,
                        trace = result.trace,
                        fallbackRequestBody = buildToolTurnsPayload(turns),
                        normalizedOutput = result.calls.firstOrNull()?.assistantMessage?.toString(),
                        attempt = attempt,
                    )
                    if (result.calls.isEmpty()) {
                        return ModelServiceResult.Failure(
                            AppError(
                                code = ErrorCode.MODEL_OUTPUT_INVALID,
                                technicalDetail = "front_chat_empty_tool_calls",
                            ),
                        )
                    }
                    val selection = toolBudget.select(result.calls)
                    turns += FrontChatToolTurn.AssistantToolCalls(selection.assistantMessage)
                    selection.executions.forEach { execution ->
                        val call = execution.call
                        val startedAt = System.currentTimeMillis()
                        val toolResult = when {
                            !execution.allowed -> execution.structuredRejectionResult()
                            FrontChatToolRegistry.validateReadOnlyArguments(call.name, call.arguments) != null ->
                                rejectedToolResult(call, "tool_arguments_invalid")
                            else -> executor.execute(call)
                        }.let(::boundToolResult)
                        val durationMillis = System.currentTimeMillis() - startedAt
                        diagnosticsLogger?.toolExecutionRecorded(
                            operation = operation,
                            correlationId = correlationId,
                            attempt = attempt,
                            toolCallId = call.id,
                            toolName = call.name,
                            allowed = execution.allowed,
                            succeeded = execution.allowed && toolExecutionSucceeded(toolResult),
                            arguments = call.arguments,
                            result = toolResult,
                            durationMillis = durationMillis,
                        )
                        turns += FrontChatToolTurn.ToolResult(
                            call = call,
                            result = toolResult,
                        )
                    }
                }
            }
        }
        return ModelServiceResult.Failure(
            AppError(
                code = ErrorCode.MODEL_OUTPUT_INVALID,
                userMessage = "查询次数太多了，请换一种说法再试",
                technicalDetail = "front_chat_tool_call_limit_exceeded",
            ),
        )
    }

    private fun stagePayload(
        correlationId: String,
        marker: String,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ): String = buildJsonObject {
        put("marker", marker)
        put("correlation_id", correlationId)
        fields()
    }.toString()

    private fun toolExecutionSucceeded(toolResult: JsonObject): Boolean = runCatching {
        val inner = (toolResult["result"] as? JsonObject) ?: toolResult
        val ok = inner["ok"] as? JsonPrimitive ?: return@runCatching true
        ok.contentOrNull?.toBooleanStrictOrNull() ?: true
    }.getOrDefault(true)

    private fun rejectedToolResult(call: FrontChatToolCall, errorCode: String): JsonObject = buildJsonObject {
        put("type", "tool_result")
        put("tool_name", call.name)
        put("arguments", call.arguments)
        put("result", buildJsonObject {
            put("ok", false)
            put("error_code", errorCode)
            put("message", "工具参数不符合白名单协议，未执行。")
        })
    }

    private fun boundToolResult(result: JsonObject): JsonObject {
        val serialized = result.toString()
        if (serialized.length <= MAX_TOOL_RESULT_LENGTH) return result
        return buildJsonObject {
            put("type", "tool_result")
            put("ok", false)
            put("error_code", "tool_result_truncated")
            put("message", "工具结果过长，已安全截断；不得把截断文本当作完整事实。")
            put("raw_prefix", serialized.take(MAX_TOOL_RESULT_PREFIX_LENGTH))
            put("truncated", true)
        }
    }

    private suspend fun persistSuccess(
        eventId: String,
        userMessageId: String?,
        rawDecisionJson: String,
        validation: AgentDecisionValidation.Valid,
        commitGuard: (() -> Boolean)? = null,
        userAttempt: UserAttempt? = null,
    ): Pair<String, String?> {
        val decision = validation.decision
        val replyOutput = decision.outputs.single { it.action == AgentActionNames.REPLY_CHAT }
        val replyText = replyOutput.requiredString("content")
            ?: error("validated reply content is missing")
        val requestedAnimation = replyOutput.optionalString("animation_id")
            ?: validation.normalizedAnimationId
        val resolvedAnimation = animationAdapter.normalize(requestedAnimation)
        val assistantMessageId = EntityId.generate("msg").value
        val planId = EntityId.generate("plan").value
        val now = timeProvider.now().toEpochMilli()

        attemptCommitMutex.withLock {
            RouteConfigurationBarrier.withReadSuspend {
                db.withTransaction {
                userAttempt?.let(::ensureUserAttemptCurrent)
                check(commitGuard?.invoke() != false) { "stale_model_route_during_persist" }
            ensureIdleFollowUpIsNotCancelled(eventId, userMessageId)
            db.actionPlanDao().insert(
                ActionPlanEntity(
                    id = planId,
                    eventId = eventId,
                    agentOutputJson = rawDecisionJson,
                    decisionSummary = decision.decisionSummary,
                    status = ActionPlanStatuses.PENDING,
                    activeEventKey = eventId,
                    createdAt = now,
                ),
            )

            ActionPlanStateMachine.requireTransition(ActionPlanStatuses.PENDING, ActionPlanStatuses.RUNNING)
            check(db.actionPlanDao().updateStatusWithActiveKey(planId, ActionPlanStatuses.RUNNING) == 1) {
                "action plan did not reach RUNNING"
            }

            decision.outputs.forEach { output ->
                executeOutput(
                    planId = planId,
                    eventId = eventId,
                    output = output,
                    decision = decision,
                    assistantMessageId = assistantMessageId,
                    replyText = replyText,
                    replyAnimationId = resolvedAnimation,
                    now = now,
                )
            }

            val claimedTodoId = replyOutput.optionalString("todo_id")?.let { requestedTodoId ->
                val claimed = todoClaimCoordinator?.claimForChat(requestedTodoId, assistantMessageId, now)
                if (claimed != null) requestedTodoId else null
            }

            ensureIdleFollowUpIsNotCancelled(eventId, userMessageId)
            db.messageDao().upsert(
                MessageEntity(
                    id = assistantMessageId,
                    role = ROLE_ASSISTANT,
                    content = replyText,
                    createdAt = now,
                    sourceEventId = eventId,
                    sourceTodoId = claimedTodoId,
                    animationId = resolvedAnimation,
                    status = MessageStatuses.COMPLETED,
                    errorCode = if (CompanionFallback.isFallback(rawDecisionJson)) CompanionFallback.MESSAGE_MARKER else null,
                    // In-chat replies are seen immediately; only background
                    // proactive messages start unread.
                    readAt = now,
                ),
            )
            if (userMessageId != null) {
                check(
                    db.messageDao().updateStatusIfCurrent(
                        id = userMessageId,
                        expectedStatus = MessageStatuses.SENDING,
                        newStatus = MessageStatuses.COMPLETED,
                        errorCode = null,
                    ) == 1,
                ) {
                    "user message was cancelled or no longer sending"
                }
            }

            EventStateMachine.requireTransition(EventStatuses.PROCESSING, EventStatuses.COMPLETED)
            check(db.eventDao().updateStatus(eventId, EventStatuses.COMPLETED) == 1) {
                "chat event did not reach COMPLETED"
            }
            ActionPlanStateMachine.requireTransition(ActionPlanStatuses.RUNNING, ActionPlanStatuses.COMPLETED)
            check(db.actionPlanDao().updateStatusWithActiveKey(planId, ActionPlanStatuses.COMPLETED) == 1) {
                "action plan did not reach COMPLETED"
            }
            ensureIdleFollowUpIsNotCancelled(eventId, userMessageId)
            // Recheck after every write as well as before the transaction. A
            // route change during the transaction must roll the whole Room
            // unit back instead of publishing a stale reply.
                userAttempt?.let(::ensureUserAttemptCurrent)
                check(commitGuard?.invoke() != false) { "stale_model_route_during_persist" }
            }

        }
        }

        // Creative indexing is best-effort and deliberately runs after the
        // route/Room publication barrier, so no callback or downstream work is
        // performed while a route read lock is held.
        runCatching {
            creativeChatCoordinator?.onAssistantReplyPersisted(
                userMessageId = userMessageId,
                assistantMessageId = assistantMessageId,
                replyText = replyText,
                replyPayload = replyOutput.payload,
            )
        }

        return assistantMessageId to resolvedAnimation
    }

    private suspend fun executeOutput(
        planId: String,
        eventId: String,
        output: AgentOutput,
        decision: AgentDecision,
        assistantMessageId: String,
        replyText: String,
        replyAnimationId: String?,
        now: Long,
    ) {
        val executionId = EntityId.generate("exec").value
        db.actionExecutionDao().upsert(
            ActionExecutionEntity(
                id = executionId,
                actionPlanId = planId,
                actionType = output.action,
                payloadJson = output.payload.toString(),
                status = ActionExecutionStatuses.PENDING,
            ),
        )

        when (output.action) {
            AgentActionNames.REPLY_CHAT -> {
                ActionExecutionStateMachine.requireTransition(ActionExecutionStatuses.PENDING, ActionExecutionStatuses.RUNNING)
                check(db.actionExecutionDao().updateStatus(executionId, ActionExecutionStatuses.RUNNING, now) == 1)
                ActionExecutionStateMachine.requireTransition(ActionExecutionStatuses.RUNNING, ActionExecutionStatuses.COMPLETED)
                check(db.actionExecutionDao().updateStatus(executionId, ActionExecutionStatuses.COMPLETED, now) == 1)
            }

            AgentActionNames.UPDATE_PET_STATE -> {
                val stateId = output.optionalString("state_id")
                val currentStateId = db.petStateDao().get()?.stateId ?: PetStateIds.IDLE
                if (stateId == null || !PetStateMachine.canTransition(currentStateId, stateId)) {
                    ActionExecutionStateMachine.requireTransition(
                        ActionExecutionStatuses.PENDING,
                        ActionExecutionStatuses.FAILED,
                    )
                    db.actionExecutionDao().updateStatusAndError(
                        executionId,
                        ActionExecutionStatuses.FAILED,
                        PET_STATE_TRANSITION_REJECTED,
                        now,
                    )
                } else {
                    ActionExecutionStateMachine.requireTransition(ActionExecutionStatuses.PENDING, ActionExecutionStatuses.RUNNING)
                    check(db.actionExecutionDao().updateStatus(executionId, ActionExecutionStatuses.RUNNING, now) == 1)
                    val expiresAt = output.optionalString("expires_at")
                        ?.let { InstantJson.parseOrNull(it)?.toEpochMilli() }
                    val clickTarget = output.payload["click_target"]?.toString()
                    db.petStateDao().upsert(
                        PetStateEntity(
                            singletonId = PetStateEntity.SINGLETON_ID,
                            stateId = stateId,
                            reasonEventId = eventId,
                            animationId = animationAdapter.normalize(output.optionalString("animation_id")),
                            shortText = output.optionalString("short_text"),
                            startsAt = now,
                            expiresAt = expiresAt,
                            clickTargetJson = clickTarget,
                            updatedAt = now,
                        ),
                    )
                    ActionExecutionStateMachine.requireTransition(ActionExecutionStatuses.RUNNING, ActionExecutionStatuses.COMPLETED)
                    check(db.actionExecutionDao().updateStatus(executionId, ActionExecutionStatuses.COMPLETED, now) == 1)
                }
            }

            else -> {
                // Schema-legal but later-phase output channel: record it as a
                // deterministic safe deferral, never execute it in Phase 2.
                ActionExecutionStateMachine.requireTransition(ActionExecutionStatuses.PENDING, ActionExecutionStatuses.CANCELLED)
                db.actionExecutionDao().updateStatusAndError(
                    executionId,
                    ActionExecutionStatuses.CANCELLED,
                    DEFERRED_TO_LATER_PHASE,
                    now,
                )
            }
        }
    }

    private suspend fun transitionEvent(eventId: String, newStatus: String) {
        db.withTransaction {
            val current = checkNotNull(db.eventDao().byId(eventId)) { "unknown chat event $eventId" }
            EventStateMachine.requireTransition(current.status, newStatus)
            check(db.eventDao().updateStatus(eventId, newStatus) == 1) { "event transition failed" }
        }
    }

    private suspend fun failTurn(messageId: String?, eventId: String, error: AppError) {
        db.withTransaction {
            if (messageId != null) {
                db.messageDao().updateStatus(messageId, MessageStatuses.FAILED, error.code.name)
            }
            val event = db.eventDao().byId(eventId)
            if (event != null && EventStateMachine.canTransition(event.status, EventStatuses.FAILED)) {
                db.eventDao().markTerminalFailure(
                    id = eventId,
                    errorCode = error.code.name,
                    errorDetail = error.technicalDetail?.take(MAX_ERROR_DETAIL_LENGTH),
                )
            }
        }
    }

    private suspend fun completeNoAction(eventId: String, reason: String) {
        db.withTransaction {
            val event = db.eventDao().byId(eventId) ?: return@withTransaction
            if (event.status in EventStatuses.terminal) return@withTransaction
            EventStateMachine.requireTransition(event.status, EventStatuses.COMPLETED)
            check(db.eventDao().updateStatus(eventId, EventStatuses.COMPLETED) == 1) {
                "idle no-action event did not reach COMPLETED"
            }
            diagnosticsLogger?.record(
                category = DiagnosticLogCategories.AI,
                name = DiagnosticLogNames.DECISION_VALIDATION,
                status = DiagnosticLogStatuses.NO_ACTION,
                subjectId = eventId,
                summary = "空闲续话判断无需表达",
                payloadJson = stagePayload(eventId, "IDLE_NO_ACTION") { put("reason", reason) },
            )
        }
    }

    /**
     * Idle follow-ups are persisted events, so their six-call attempts also
     * need a crash-safe lifetime ceiling.  The reservation is made before
     * every provider call and is deliberately not refunded on cancellation.
     */
    private suspend fun reserveLifecycleGenerationRequest(
        eventId: String,
        maxRequests: Int,
    ): Boolean =
        db.eventDao().reserveGenerationRequests(
            id = eventId,
            amount = 1,
            maxRequests = maxRequests,
        ) == 1

    private fun registerActiveIdleFollowUp(eventId: String) {
        synchronized(idleFollowUpCancellationLock) {
            activeIdleFollowUpEventId = eventId
            cancelledIdleFollowUpEventIds.remove(eventId)
        }
    }

    private fun clearActiveIdleFollowUp(eventId: String) {
        synchronized(idleFollowUpCancellationLock) {
            if (activeIdleFollowUpEventId == eventId) {
                activeIdleFollowUpEventId = null
            }
            cancelledIdleFollowUpEventIds.remove(eventId)
        }
    }

    private fun ensureIdleFollowUpIsNotCancelled(eventId: String, userMessageId: String?) {
        if (userMessageId == null && synchronized(idleFollowUpCancellationLock) {
                eventId in cancelledIdleFollowUpEventIds
            }
        ) {
            throw CancellationException(IDLE_FOLLOW_UP_CANCELLED)
        }
    }

    private fun cancelledRequestError(isIdleFollowUp: Boolean): AppError = AppError(
        code = ErrorCode.MODEL_FAILURE,
        userMessage = if (isIdleFollowUp) "续话已中止，可以稍后再试" else "发送已中止，可以重试",
        technicalDetail = if (isIdleFollowUp) IDLE_FOLLOW_UP_CANCELLED else FRONT_CHAT_CANCELLED,
    )

    private fun supersededError(): AppError = AppError(
        code = ErrorCode.MODEL_FAILURE,
        userMessage = "这条回复已让位给你的新消息",
        technicalDetail = SUPERSEDED_BY_NEW_INPUT,
    )

    private fun newChatEvent(eventId: String, messageId: String, now: Long, attemptCount: Int = 0): EventEntity {
        val attempt = UUID.randomUUID().toString().replace("-", "")
        return EventEntity(
            id = eventId,
            eventKey = "chat:user_message:$messageId:$attempt",
            type = EVENT_TYPE_USER_MESSAGE,
            source = EVENT_SOURCE_CHAT_INPUT,
            payloadJson = """{"user_message_id":"$messageId"}""",
            occurredAt = now,
            receivedAt = now,
            expiresAt = now + CHAT_EVENT_TTL_MILLIS,
            priority = 90,
            status = EventStatuses.RECEIVED,
            attemptCount = attemptCount,
        )
    }

    private fun newIdleFollowUpEvent(eventId: String, anchorAssistantMessageId: String, now: Long): EventEntity {
        val attempt = UUID.randomUUID().toString().replace("-", "")
        return EventEntity(
            id = eventId,
            eventKey = "chat:idle_follow_up:$anchorAssistantMessageId:$attempt",
            type = EVENT_TYPE_IDLE_FOLLOW_UP,
            source = EVENT_SOURCE_IDLE_FOLLOW_UP,
            payloadJson = "{\"anchor_assistant_message_id\":\"$anchorAssistantMessageId\"}",
            occurredAt = now,
            receivedAt = now,
            expiresAt = now + CHAT_EVENT_TTL_MILLIS,
            priority = 80,
            status = EventStatuses.RECEIVED,
        )
    }

    private fun AgentOutput.requiredString(key: String): String? =
        (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun AgentOutput.optionalString(key: String): String? =
        (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private data class ChatTurn(
        val message: MessageEntity,
        val eventId: String,
    )

    private data class UserAttempt(
        val messageId: String,
        val eventId: String,
        val epoch: Long,
    )

    private class SupersededAttemptCancellation : CancellationException("user_attempt_superseded")

    companion object {
        private const val MAX_FRONT_TOOL_CALL_ROUNDS = 3
        private const val MAX_MANUAL_RETRIES = 1
        internal const val MAX_FRONT_TOOL_CALLS_PER_ROUND = FrontChatToolRegistry.MAX_CALLS_PER_ROUND
        internal const val MAX_FRONT_TOOL_CALLS_PER_SESSION = FrontChatToolRegistry.MAX_CALLS_PER_SESSION
        const val MAX_USER_MESSAGE_LENGTH = 2_000
        const val CHAT_EVENT_TTL_MILLIS = 10 * 60 * 1_000L
        const val ROLE_USER = "USER"
        const val ROLE_ASSISTANT = "ASSISTANT"
        const val EVENT_TYPE_USER_MESSAGE = "USER_MESSAGE"
        const val EVENT_SOURCE_CHAT_INPUT = "CHAT_INPUT"
        const val EVENT_TYPE_IDLE_FOLLOW_UP = "IDLE_FOLLOW_UP"
        const val EVENT_SOURCE_IDLE_FOLLOW_UP = "CHAT_IDLE_FOLLOW_UP"
        const val DEFERRED_TO_LATER_PHASE = "DEFERRED_TO_LATER_PHASE"
        const val PET_STATE_TRANSITION_REJECTED = "PET_STATE_TRANSITION_REJECTED"
        const val IDLE_FOLLOW_UP_CANCELLED = "idle_follow_up_coroutine_cancelled"
        const val FRONT_CHAT_CANCELLED = "front_chat_coroutine_cancelled"
        const val FRONT_CHAT_CANCELLED_BY_USER = "CANCELLED_BY_USER"
        const val SUPERSEDED_BY_NEW_INPUT = "SUPERSEDED_BY_NEW_INPUT"
        const val MAX_ERROR_DETAIL_LENGTH = 500
        const val MAX_FOREGROUND_GENERATION_REQUESTS = ReplyCoordinator.MAX_GENERATION_REQUESTS
        const val MAX_LIFECYCLE_GENERATION_REQUESTS = 12
        private const val MAX_TOOL_RESULT_LENGTH = 8_000
        private const val MAX_TOOL_RESULT_PREFIX_LENGTH = 2_000
        val ALLOWED_ANIMATION_IDS: Set<String> = com.littlekitty.xiaokele.animation.AnimationCatalog.knownIds
    }
}

/** Explicit memory/correction commands bypass window-pressure consolidation. */
object ImmediateMemorySignalDetector {
    private val signals = listOf(
        "记住", "记得", "别忘", "忘掉", "忘记", "不要再", "不要催", "不要一直", "别催", "以后不要",
        "以后别", "不喜欢被", "你说错了", "不是这样", "纠正一下",
    )

    fun matches(text: String): Boolean = signals.any(text::contains)
}
