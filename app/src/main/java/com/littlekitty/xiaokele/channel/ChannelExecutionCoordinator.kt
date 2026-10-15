package com.littlekitty.xiaokele.channel

import android.content.Context
import androidx.room.withTransaction
import com.littlekitty.xiaokele.animation.AnimationAdapter
import com.littlekitty.xiaokele.animation.AnimationCatalog
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames
import com.littlekitty.xiaokele.core.common.scene.FrozenSceneThresholdProvider
import com.littlekitty.xiaokele.core.common.scene.SceneCooldown
import com.littlekitty.xiaokele.core.common.scene.SceneThresholdProvider
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.delivery.DeliveryBlockReasons
import com.littlekitty.xiaokele.core.database.delivery.DeliveryOutbox
import com.littlekitty.xiaokele.core.database.delivery.DeliveryReservationRequest
import com.littlekitty.xiaokele.core.database.delivery.DeliveryReservationResult
import com.littlekitty.xiaokele.core.database.entity.ActionExecutionEntity
import com.littlekitty.xiaokele.core.database.entity.ActionPlanEntity
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.entity.PetStateEntity
import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.repository.TodoClaimants
import com.littlekitty.xiaokele.core.database.state.ActionExecutionStateMachine
import com.littlekitty.xiaokele.core.database.state.ActionExecutionStatuses
import com.littlekitty.xiaokele.core.database.state.ActionPlanStateMachine
import com.littlekitty.xiaokele.core.database.state.ActionPlanStatuses
import com.littlekitty.xiaokele.core.database.state.DeliveryStateMachine
import com.littlekitty.xiaokele.core.database.state.DeliveryStatuses
import com.littlekitty.xiaokele.core.database.state.EventStateMachine
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import com.littlekitty.xiaokele.core.database.state.PetStateIds
import com.littlekitty.xiaokele.core.database.state.PetStateMachine
import com.littlekitty.xiaokele.core.database.state.PetStatePriorityMatrix
import com.littlekitty.xiaokele.core.database.state.TodoStateMachine
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import com.littlekitty.xiaokele.floating.FloatingPetService
import com.littlekitty.xiaokele.notification.NotificationAdapter
import com.littlekitty.xiaokele.notification.NotificationSendRequest
import com.littlekitty.xiaokele.notification.NotificationSendResult
import com.littlekitty.xiaokele.widget.WidgetStateAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ChannelExecutionResult(
    val executionId: String,
    val actionType: String,
    val status: String,
    val errorCode: String? = null,
)

data class ChannelPlanReport(
    val planId: String,
    val eventId: String,
    val results: List<ChannelExecutionResult>,
    val completed: Int,
    val failed: Int,
    val cancelled: Int,
)

/**
 * Unified Phase 5 channel execution boundary. The background pipeline persists
 * an ActionPlan plus PENDING executions in one Room transaction, commits it,
 * then hands the plan to this coordinator. Every OS/system adapter call happens
 * outside Room transactions; notification/widget failures only mark their own
 * execution and reservation, never roll back chat, PetState or the plan.
 */
class ChannelExecutionCoordinator(
    private val db: AppDatabase,
    private val deliveryOutbox: DeliveryOutbox,
    private val notificationAdapter: NotificationAdapter,
    private val widgetStateAdapter: WidgetStateAdapter,
    private val animationAdapter: AnimationAdapter,
    private val timeProvider: TimeProvider,
    private val appContext: Context? = null,
    private val thresholdProvider: SceneThresholdProvider = FrozenSceneThresholdProvider(),
) {

    suspend fun executePlan(planId: String): ChannelPlanReport {
        val initialPlan = db.actionPlanDao().byId(planId) ?: error("Unknown action plan: $planId")
        if (initialPlan.status in ActionPlanStatuses.terminal) {
            return snapshotPlan(initialPlan)
        }
        val eligibility = ensurePlanEventEligible(planId, timeProvider.now().toEpochMilli())
        if (eligibility is PlanEventEligibility.Ineligible) {
            return snapshotPlan(eligibility.plan)
        }
        var plan = (eligibility as PlanEventEligibility.Eligible).plan
        if (plan.status in ActionPlanStatuses.terminal) {
            return snapshotPlan(plan)
        }
        if (plan.status in setOf(ActionPlanStatuses.PENDING, ActionPlanStatuses.VALIDATED)) {
            ActionPlanStateMachine.requireTransition(plan.status, ActionPlanStatuses.RUNNING)
            if (db.actionPlanDao().transitionStatusWithActiveKey(
                    planId,
                    plan.status,
                    ActionPlanStatuses.RUNNING,
                ) != 1
            ) {
                return snapshotPlan(db.actionPlanDao().byId(planId) ?: plan)
            }
            plan = db.actionPlanDao().byId(planId) ?: return snapshotPlan(plan)
        }

        val executions = db.actionExecutionDao().forActionPlan(planId)
        val results = executions.map { execution -> executeExecution(plan, execution) }
        finalizePlan(planId)
        return ChannelPlanReport(
            planId = planId,
            eventId = plan.eventId,
            results = results,
            completed = results.count { it.status == ActionExecutionStatuses.COMPLETED },
            failed = results.count { it.status == ActionExecutionStatuses.FAILED },
            cancelled = results.count { it.status == ActionExecutionStatuses.CANCELLED },
        )
    }

    /**
     * Startup recovery entry for ActionPlans left RUNNING by process death.
     * RecoveryManager has already returned not-invoked executions to PENDING
     * and converted attempted-but-undelivered OS calls to OUTCOME_UNKNOWN, so
     * this method never blindly re-posts a possibly-delivered notification.
     */
    suspend fun recoverInterruptedPlans(): Int {
        var recovered = 0
        db.actionPlanDao().activePlans().forEach { plan ->
            val eligibility = ensurePlanEventEligible(plan.id, timeProvider.now().toEpochMilli())
            if (eligibility is PlanEventEligibility.Ineligible) return@forEach
            val executions = db.actionExecutionDao().forActionPlan(plan.id)
            if (executions.any { it.status in setOf(ActionExecutionStatuses.PENDING, ActionExecutionStatuses.RUNNING) }) {
                executePlan(plan.id)
                recovered++
            }
        }
        return recovered
    }

    /**
     * Testable persisted-outbox retry path. Only FAILED delivery rows whose
     * next_retry_at is due enter here; OUTCOME_UNKNOWN rows are deliberately
     * never retried automatically.
     */
    suspend fun retryFailedExecution(executionId: String): ChannelExecutionResult? {
        val now = timeProvider.now().toEpochMilli()
        val retryClaim = db.withTransaction {
            val execution = db.actionExecutionDao().byId(executionId) ?: return@withTransaction null
            val delivery = db.deliveryHistoryDao().byExecutionId(executionId) ?: return@withTransaction null
            if (execution.status != ActionExecutionStatuses.FAILED ||
                delivery.status != DeliveryStatuses.FAILED ||
                delivery.attemptCount >= DeliveryOutbox.MAX_ATTEMPTS
            ) {
                return@withTransaction null
            }

            val plan = db.actionPlanDao().byId(execution.actionPlanId) ?: return@withTransaction null
            if (ensurePlanEventEligibleInTransaction(
                    plan = plan,
                    now = now,
                    allowCompletedEvent = true,
                ) is PlanEventEligibility.Ineligible
            ) {
                return@withTransaction null
            }

            ActionExecutionStateMachine.requireTransition(execution.status, ActionExecutionStatuses.RUNNING)
            check(db.actionExecutionDao().retryFailed(executionId, now) == 1) {
                "action execution retry affected 0 rows"
            }
            DeliveryStateMachine.requireTransition(delivery.status, DeliveryStatuses.RESERVED)
            check(db.deliveryHistoryDao().prepareRetry(delivery.id, now) == 1) {
                "delivery retry prepare affected 0 rows"
            }
            RetryClaim(
                plan = plan,
                execution = execution.copy(
                    status = ActionExecutionStatuses.RUNNING,
                    executedAt = now,
                    errorCode = null,
                ),
            )
        }
        retryClaim ?: return null

        val result = executeClaimedExecution(
            plan = retryClaim.plan,
            execution = retryClaim.execution,
            allowCompletedEvent = true,
        )
        finalizePlan(retryClaim.plan.id)
        return result
    }

    private suspend fun executeExecution(
        plan: ActionPlanEntity,
        execution: ActionExecutionEntity,
    ): ChannelExecutionResult {
        val now = timeProvider.now().toEpochMilli()
        if (ensurePlanEventEligible(plan.id, now) is PlanEventEligibility.Ineligible) {
            return db.actionExecutionDao().byId(execution.id)?.toResult()
                ?: ChannelExecutionResult(execution.id, execution.actionType, ActionExecutionStatuses.CANCELLED, EVENT_NOT_EXECUTABLE)
        }
        val claimResult = db.withTransaction {
            val current = db.actionExecutionDao().byId(execution.id)
                ?: return@withTransaction null
            if (current.status != ActionExecutionStatuses.PENDING) {
                return@withTransaction current to false
            }
            if (db.actionExecutionDao().claimPending(current.id, now) != 1) {
                return@withTransaction (db.actionExecutionDao().byId(current.id) ?: current) to false
            }
            current.copy(
                status = ActionExecutionStatuses.RUNNING,
                executedAt = now,
                errorCode = null,
            ) to true
        } ?: return ChannelExecutionResult(
            execution.id,
            execution.actionType,
            ActionExecutionStatuses.CANCELLED,
            EVENT_NOT_EXECUTABLE,
        )
        val claimed = claimResult.first
        if (!claimResult.second) return claimed.toResult()
        return executeClaimedExecution(plan, claimed)
    }

    private suspend fun executeClaimedExecution(
        plan: ActionPlanEntity,
        execution: ActionExecutionEntity,
        allowCompletedEvent: Boolean = false,
    ): ChannelExecutionResult {
        val now = timeProvider.now().toEpochMilli()
        if (ensurePlanEventEligible(
                planId = plan.id,
                now = now,
                allowCompletedEvent = allowCompletedEvent,
            ) is PlanEventEligibility.Ineligible
        ) {
            return db.actionExecutionDao().byId(execution.id)?.toResult()
                ?: ChannelExecutionResult(execution.id, execution.actionType, ActionExecutionStatuses.CANCELLED, EVENT_NOT_EXECUTABLE)
        }
        return when (execution.actionType) {
            com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry.SEND_NOTIFICATION ->
                executeNotification(plan, execution, now)

            com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry.SEND_PROACTIVE_MESSAGE ->
                executeProactive(plan, execution, now)

            com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry.SET_WIDGET_STATE ->
                executePetStateChannel(plan, execution, now, channel = DeliveryOutbox.CHANNEL_WIDGET)

            com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry.UPDATE_PET_STATE ->
                executePetStateChannel(plan, execution, now, channel = DeliveryOutbox.CHANNEL_PET_STATE)

            com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry.PLAY_ANIMATION ->
                executeAnimation(plan, execution, now)

            else -> ChannelExecutionResult(
                executionId = execution.id,
                actionType = execution.actionType,
                status = ActionExecutionStatuses.CANCELLED,
                errorCode = "CHANNEL_NOT_ALLOWED",
            )
        }
    }

    private suspend fun executeNotification(
        plan: ActionPlanEntity,
        execution: ActionExecutionEntity,
        now: Long,
    ): ChannelExecutionResult {
        if (execution.status in setOf(ActionExecutionStatuses.COMPLETED, ActionExecutionStatuses.CANCELLED)) {
            return execution.toResult()
        }
        val payload = parsePayload(execution)
        val todoId = payload.optionalString("todo_id")
        if (todoId != null && EntityId.isValid(todoId).not()) {
            return cancelExecution(execution, "INVALID_TODO_ID")
        }
        if (todoAlreadyTouched(todoId, execution.id)) {
            return cancelExecution(execution, SUPERSEDED_BY_DELIVERY)
        }
        val claim = todoId?.let { claimTodo(it, plan, execution.id, now) }
        if (claim is TodoClaimResult.NotClaimable) {
            return cancelExecution(execution, "TODO_NOT_CLAIMABLE")
        }

        val title = payload.optionalString("title").orEmpty()
        val body = payload.optionalString("body").orEmpty()
        val reservation = existingReservationOrReserve(
            plan = plan,
            execution = execution,
            channel = DeliveryOutbox.CHANNEL_NOTIFICATION,
            todoId = claim.claimedTodo()?.id,
            cooldownGroup = sceneCooldownGroupForPlan(plan)
                ?: payload.optionalString("notification_type")
                ?: DeliveryOutbox.DEFAULT_NOTIFICATION_COOLDOWN_GROUP,
            quotaExempt = isHighWeatherAlertQuotaExempt(plan),
        )
        if (reservation !is DeliveryReservationResult.Reserved) {
            releaseClaimIfOurs(claim)
            return blockedExecution(execution, reservation as DeliveryReservationResult.Blocked)
        }

        markRunning(execution.id, now)
        deliveryOutbox.markInvoked(reservation.delivery.id, now)

        val result = try {
            notificationAdapter.send(
                NotificationSendRequest(
                    executionId = execution.id,
                    eventId = plan.eventId,
                    todoId = claim.claimedTodo()?.id,
                    title = title.ifBlank { "陪伴" },
                    body = body,
                ),
            )
        } catch (throwable: Throwable) {
            NotificationSendResult.AdapterFailure(throwable.message ?: "notification adapter threw")
        }
        return when (result) {
            is NotificationSendResult.Delivered -> {
                deliveryOutbox.commitDelivered(reservation.delivery.id, now)
                completeDelivery(
                    execution = execution,
                    eventId = plan.eventId,
                    claimedTodo = claim.claimedTodo(),
                    now = now,
                )
            }

            is NotificationSendResult.PermissionDenied -> {
                deliveryOutbox.fail(
                    reservation.delivery.id,
                    NOTIFICATION_PERMISSION_DENIED,
                    now,
                    retryable = false,
                )
                failDelivery(execution, claim.claimedTodo(), NOTIFICATION_PERMISSION_DENIED, now)
            }

            is NotificationSendResult.AdapterFailure -> {
                deliveryOutbox.fail(
                    reservation.delivery.id,
                    NOTIFICATION_ADAPTER_FAILURE,
                    now,
                    retryable = true,
                )
                failDelivery(execution, claim.claimedTodo(), NOTIFICATION_ADAPTER_FAILURE, now)
            }
        }
    }

    private suspend fun executeProactive(
        plan: ActionPlanEntity,
        execution: ActionExecutionEntity,
        now: Long,
    ): ChannelExecutionResult {
        if (execution.status in setOf(ActionExecutionStatuses.COMPLETED, ActionExecutionStatuses.CANCELLED)) {
            return execution.toResult()
        }
        val payload = parsePayload(execution)
        val content = payload.optionalString("content")
            ?: return cancelExecution(execution, "MISSING_PROACTIVE_CONTENT")
        val todoId = payload.optionalString("todo_id")
        if (todoId != null && EntityId.isValid(todoId).not()) {
            return cancelExecution(execution, "INVALID_TODO_ID")
        }
        if (todoAlreadyTouched(todoId, execution.id)) {
            return cancelExecution(execution, SUPERSEDED_BY_DELIVERY)
        }
        val claim = todoId?.let { claimTodo(it, plan, execution.id, now) }
        if (claim is TodoClaimResult.NotClaimable) {
            return cancelExecution(execution, "TODO_NOT_CLAIMABLE")
        }

        val reservation = existingReservationOrReserve(
            plan = plan,
            execution = execution,
            channel = DeliveryOutbox.CHANNEL_PROACTIVE_MESSAGE,
            todoId = claim.claimedTodo()?.id,
            cooldownGroup = sceneCooldownGroupForPlan(plan),
        )
        if (reservation !is DeliveryReservationResult.Reserved) {
            releaseClaimIfOurs(claim)
            return blockedExecution(execution, reservation as DeliveryReservationResult.Blocked)
        }

        markRunning(execution.id, now)
        deliveryOutbox.markInvoked(reservation.delivery.id, now)

        val messageResult = writeProactiveMessage(
            eventId = plan.eventId,
            todoId = claim.claimedTodo()?.id,
            content = content,
            now = now,
        )
        return when (messageResult) {
            is ProactiveMessageWrite.Created, is ProactiveMessageWrite.AlreadyExists -> {
                refreshUnreadSurfaces()
                val deliveryOutcome = if (wakeWeatherSilentNotification(plan.eventId)) {
                    deliverSilentNotification(plan, execution, claim.claimedTodo(), content)
                } else {
                    appContext?.let { context ->
                        val event = db.eventDao().byId(plan.eventId)
                        FloatingPetService.enqueuePendingBubble(
                            context = context,
                            text = content,
                            eventId = plan.eventId,
                            priority = payload.optionalInt("priority") ?: 50,
                            expiresAt = event?.expiresAt ?: (now + DEFAULT_BUBBLE_TTL_MILLIS),
                        )
                    }
                    NotificationSendResult.Delivered
                }
                when (deliveryOutcome) {
                    is NotificationSendResult.Delivered -> {
                        deliveryOutbox.commitDelivered(reservation.delivery.id, now)
                        completeDelivery(
                            execution = execution,
                            eventId = plan.eventId,
                            claimedTodo = claim.claimedTodo(),
                            now = now,
                        )
                    }

                    is NotificationSendResult.PermissionDenied -> {
                        // Message exists in chat; retrying a denied permission
                        // cannot succeed, so fail without retry.
                        deliveryOutbox.fail(reservation.delivery.id, NOTIFICATION_PERMISSION_DENIED, now, retryable = false)
                        failDelivery(execution, claim.claimedTodo(), NOTIFICATION_PERMISSION_DENIED, now)
                    }

                    is NotificationSendResult.AdapterFailure -> {
                        deliveryOutbox.fail(reservation.delivery.id, NOTIFICATION_ADAPTER_FAILURE, now, retryable = true)
                        failDelivery(execution, claim.claimedTodo(), NOTIFICATION_ADAPTER_FAILURE, now)
                    }
                }
            }

            is ProactiveMessageWrite.BudgetExceeded -> {
                deliveryOutbox.cancel(reservation.delivery.id, DAILY_PROACTIVE_BUDGET_BLOCKED)
                releaseClaimIfOurs(claim)
                cancelExecution(execution, DAILY_PROACTIVE_BUDGET_BLOCKED)
            }

            is ProactiveMessageWrite.Failed -> {
                deliveryOutbox.fail(reservation.delivery.id, PROACTIVE_WRITE_FAILED, now, retryable = true)
                failDelivery(execution, claim.claimedTodo(), PROACTIVE_WRITE_FAILED, now)
            }
        }
    }

    /**
     * The wake weather brief is delivered as a silent lockscreen notification
     * plus the chat record (no floating bubble): the user should meet the
     * 24h weather right on the lock screen after waking up.
     */
    private suspend fun wakeWeatherSilentNotification(eventId: String): Boolean {
        val event = db.eventDao().byId(eventId) ?: return false
        return event.type == ExternalEventTypeNames.WAKE_WEATHER_BRIEF
    }

    private suspend fun deliverSilentNotification(
        plan: ActionPlanEntity,
        execution: ActionExecutionEntity,
        claimedTodo: TodoEntity?,
        content: String,
    ): NotificationSendResult {
        return notificationAdapter.send(
            NotificationSendRequest(
                executionId = execution.id,
                eventId = plan.eventId,
                todoId = claimedTodo?.id,
                title = "",
                body = content,
                silent = true,
            ),
        )
    }

    private suspend fun executePetStateChannel(
        plan: ActionPlanEntity,
        execution: ActionExecutionEntity,
        now: Long,
        channel: String,
    ): ChannelExecutionResult {
        if (execution.status in setOf(ActionExecutionStatuses.COMPLETED, ActionExecutionStatuses.CANCELLED)) {
            return execution.toResult()
        }
        val payload = parsePayload(execution)
        val stateId = payload.optionalString("state_id")
        if (stateId == null || (stateId in PetStateIds.all).not()) {
            return cancelExecution(execution, PET_STATE_TRANSITION_REJECTED)
        }
        val currentBeforeReserve = db.petStateDao().get()
        val eventForState = db.eventDao().byId(plan.eventId)
        if (PetStatePriorityMatrix.canOverride(currentBeforeReserve?.stateId, stateId, eventForState).not()) {
            return cancelExecution(execution, PET_STATE_TRANSITION_REJECTED)
        }
        val reservation = existingReservationOrReserve(
            plan = plan,
            execution = execution,
            channel = channel,
            todoId = null,
            cooldownGroup = sceneCooldownGroupForPlan(plan),
        )
        if (reservation !is DeliveryReservationResult.Reserved) {
            return blockedExecution(execution, reservation as DeliveryReservationResult.Blocked)
        }

        markRunning(execution.id, now)
        deliveryOutbox.markInvoked(reservation.delivery.id, now)

        val expiresAt = payload.optionalString("expires_at")?.let { InstantJson.parseOrNull(it)?.toEpochMilli() }
        if (expiresAt != null && expiresAt <= now) {
            deliveryOutbox.fail(reservation.delivery.id, PET_STATE_EXPIRED, now, retryable = false)
            return failDelivery(execution, null, PET_STATE_EXPIRED, now)
        }
        val current = db.petStateDao().get()
        val from = current?.stateId ?: PetStateIds.IDLE
        if (PetStateMachine.canTransition(from, stateId).not()) {
            deliveryOutbox.fail(reservation.delivery.id, PET_STATE_TRANSITION_REJECTED, now, retryable = false)
            return failDelivery(execution, null, PET_STATE_TRANSITION_REJECTED, now)
        }

        db.withTransaction {
            val entity = PetStateEntity(
                singletonId = PetStateEntity.SINGLETON_ID,
                stateId = stateId,
                reasonEventId = plan.eventId,
                animationId = animationAdapter.normalize(payload.optionalString("animation_id")),
                shortText = payload.optionalString("short_text"),
                startsAt = now,
                expiresAt = expiresAt,
                clickTargetJson = payload["click_target"]?.toString(),
                updatedAt = now,
            )
            db.petStateDao().upsert(entity)
        }
        deliveryOutbox.commitDelivered(reservation.delivery.id, now)
        db.withTransaction {
            ActionExecutionStateMachine.requireTransition(
                db.actionExecutionDao().byId(execution.id)?.status ?: ActionExecutionStatuses.RUNNING,
                ActionExecutionStatuses.COMPLETED,
            )
            db.actionExecutionDao().updateStatus(execution.id, ActionExecutionStatuses.COMPLETED, now)
        }
        // System widget refresh is outside every Room transaction. Failure here
        // does not roll back the already-committed PetState.
        runCatching { widgetStateAdapter.refreshCurrentPetState() }
        return ChannelExecutionResult(execution.id, execution.actionType, ActionExecutionStatuses.COMPLETED)
    }

    private suspend fun executeAnimation(
        plan: ActionPlanEntity,
        execution: ActionExecutionEntity,
        now: Long,
    ): ChannelExecutionResult {
        if (execution.status in setOf(ActionExecutionStatuses.COMPLETED, ActionExecutionStatuses.CANCELLED)) {
            return execution.toResult()
        }
        val payload = parsePayload(execution)
        val requested = payload.optionalString("animation_id")
        val normalized = animationAdapter.normalize(requested)
        val reservation = existingReservationOrReserve(
            plan = plan,
            execution = execution,
            channel = DeliveryOutbox.CHANNEL_ANIMATION,
            todoId = null,
            cooldownGroup = sceneCooldownGroupForPlan(plan),
        )
        if (reservation !is DeliveryReservationResult.Reserved) {
            return blockedExecution(execution, reservation as DeliveryReservationResult.Blocked)
        }
        markRunning(execution.id, now)
        deliveryOutbox.markInvoked(reservation.delivery.id, now)

        // The semantic directory now resolves to the bundled transparent
        // Animated WebP resources. Delivery remains local and idempotent; the
        // chat/widget renderers consume the same resource mapping.
        AnimationCatalog.resourceFor(normalized)
        appContext?.let { context -> runCatching { FloatingPetService.playAnimation(context, normalized) } }
        deliveryOutbox.commitDelivered(reservation.delivery.id, now)
        db.withTransaction {
            ActionExecutionStateMachine.requireTransition(
                db.actionExecutionDao().byId(execution.id)?.status ?: ActionExecutionStatuses.RUNNING,
                ActionExecutionStatuses.COMPLETED,
            )
            db.actionExecutionDao().updateStatus(execution.id, ActionExecutionStatuses.COMPLETED, now)
        }
        return ChannelExecutionResult(execution.id, execution.actionType, ActionExecutionStatuses.COMPLETED)
    }

    private suspend fun existingReservationOrReserve(
        plan: ActionPlanEntity,
        execution: ActionExecutionEntity,
        channel: String,
        todoId: String?,
        cooldownGroup: String? = null,
        quotaExempt: Boolean = false,
    ): DeliveryReservationResult {
        val existing = db.deliveryHistoryDao().byExecutionId(execution.id)
        if (existing != null && existing.status == DeliveryStatuses.RESERVED && existing.attemptStartedAt == null) {
            return DeliveryReservationResult.Reserved(existing)
        }
        return reserve(plan, execution, channel, todoId, cooldownGroup, quotaExempt)
    }

    private suspend fun reserve(
        plan: ActionPlanEntity,
        execution: ActionExecutionEntity,
        channel: String,
        todoId: String?,
        cooldownGroup: String? = null,
        quotaExempt: Boolean = false,
    ): DeliveryReservationResult = deliveryOutbox.reserve(
        DeliveryReservationRequest(
            eventId = plan.eventId,
            todoId = todoId,
            channel = channel,
            content = execution.payloadJson ?: "{}",
            executionId = execution.id,
            cooldownGroup = cooldownGroup,
            nowEpochMillis = timeProvider.now().toEpochMilli(),
            quotaExempt = quotaExempt,
        ),
    )

    private suspend fun todoAlreadyTouched(todoId: String?, executionId: String): Boolean {
        if (todoId == null) return false
        val existing = db.deliveryHistoryDao().activeOrDeliveredForTodo(todoId) ?: return false
        return existing.executionId != executionId
    }

    private suspend fun claimTodo(
        todoId: String,
        plan: ActionPlanEntity,
        executionId: String,
        now: Long,
    ): TodoClaimResult {
        val todo = db.todoDao().byId(todoId) ?: return TodoClaimResult.NotClaimable
        if (todo.status !in TodoStatuses.claimable) return TodoClaimResult.NotClaimable
        val claimedBy = "${TodoClaimants.BACKGROUND}:${plan.eventId}:$executionId"
        return if (db.todoDao().claim(todo.id, claimedBy, now, now, now) == 1) {
            TodoClaimResult.Claimed(db.todoDao().byId(todo.id) ?: return TodoClaimResult.NotClaimable)
        } else {
            TodoClaimResult.NotClaimable
        }
    }

    private suspend fun writeProactiveMessage(
        eventId: String,
        todoId: String?,
        content: String,
        now: Long,
    ): ProactiveMessageWrite = db.withTransaction {
        val existing = db.messageDao().assistantForEvent(eventId)
            ?: todoId?.let { db.messageDao().assistantForTodo(it) }
        if (existing != null) return@withTransaction ProactiveMessageWrite.AlreadyExists
        val budget = thresholdProvider.current().dailyProactiveOutreachBudget
        if (budget > 0) {
            val startOfProductDay = java.time.Instant.ofEpochMilli(now)
                .atZone(TimeProvider.DISPLAY_ZONE)
                .toLocalDate()
                .atStartOfDay(TimeProvider.DISPLAY_ZONE)
                .toInstant()
                .toEpochMilli()
            val writtenToday = db.messageDao().countProactiveAssistantBetween(startOfProductDay, now)
            if (writtenToday >= budget) {
                return@withTransaction ProactiveMessageWrite.BudgetExceeded
            }
        }
        val entity = MessageEntity(
            id = EntityId.generate("msg").value,
            role = ROLE_ASSISTANT,
            content = content,
            createdAt = now,
            sourceEventId = eventId,
            sourceTodoId = todoId,
            animationId = AnimationCatalog.ID_DEFAULT_IDLE,
            status = MessageStatuses.COMPLETED,
        )
        try {
            db.messageDao().insert(entity)
            ProactiveMessageWrite.Created
        } catch (throwable: Throwable) {
            ProactiveMessageWrite.Failed(throwable.message ?: "proactive message write failed")
        }
    }

    /**
     * Once chat contains the message, all in-app unread surfaces must reflect
     * it even when the optional lock-screen notification is unavailable.
     */
    private suspend fun refreshUnreadSurfaces() {
        runCatching { widgetStateAdapter.refreshCurrentPetState() }
        appContext?.let { context ->
            runCatching {
                com.littlekitty.xiaokele.notification.UnreadBadgeNotifier.refresh(
                    context,
                    db.messageDao().countUnreadProactive(),
                )
            }
        }
    }

    private suspend fun completeDelivery(
        execution: ActionExecutionEntity,
        eventId: String,
        claimedTodo: TodoEntity?,
        now: Long,
    ): ChannelExecutionResult = db.withTransaction {
        val current = db.actionExecutionDao().byId(execution.id)
        if (current != null) {
            ActionExecutionStateMachine.requireTransition(current.status, ActionExecutionStatuses.COMPLETED)
            check(db.actionExecutionDao().updateStatus(execution.id, ActionExecutionStatuses.COMPLETED, now) == 1)
        }
        claimedTodo?.let { todo ->
            val fresh = db.todoDao().byId(todo.id)
            if (fresh != null && fresh.status == TodoStatuses.CLAIMED) {
                if (appContext != null) {
                    // A background proactive message is only queued for the
                    // next unlock. The Todo remains claimable until the user
                    // opens the bubble/chat handoff.
                    db.todoDao().releaseClaim(fresh.id, now)
                } else {
                    TodoStateMachine.requireTransition(fresh.status, TodoStatuses.EXECUTED)
                    db.todoDao().transition(fresh.id, TodoStatuses.CLAIMED, TodoStatuses.EXECUTED, now)
                }
            }
        }
        cancelDuplicateReservations(eventId = eventId, todoId = claimedTodo?.id, winnerExecutionId = execution.id)
        ChannelExecutionResult(execution.id, execution.actionType, ActionExecutionStatuses.COMPLETED)
    }

    private suspend fun failDelivery(
        execution: ActionExecutionEntity,
        claimedTodo: TodoEntity?,
        errorCode: String,
        now: Long,
    ): ChannelExecutionResult = db.withTransaction {
        val current = db.actionExecutionDao().byId(execution.id)
        if (current != null) {
            ActionExecutionStateMachine.requireTransition(current.status, ActionExecutionStatuses.FAILED)
            db.actionExecutionDao().updateStatusAndError(execution.id, ActionExecutionStatuses.FAILED, errorCode, now)
        }
        releaseClaimIfOurs(claimedTodo, now)
        ChannelExecutionResult(execution.id, execution.actionType, ActionExecutionStatuses.FAILED, errorCode)
    }

    private suspend fun cancelExecution(
        execution: ActionExecutionEntity,
        errorCode: String,
    ): ChannelExecutionResult {
        val now = timeProvider.now().toEpochMilli()
        db.withTransaction {
            val current = db.actionExecutionDao().byId(execution.id)
            if (current != null) {
                ActionExecutionStateMachine.requireTransition(current.status, ActionExecutionStatuses.CANCELLED)
                db.actionExecutionDao().updateStatusAndError(
                    execution.id,
                    ActionExecutionStatuses.CANCELLED,
                    errorCode,
                    now,
                )
            }
        }
        return ChannelExecutionResult(execution.id, execution.actionType, ActionExecutionStatuses.CANCELLED, errorCode)
    }

    private suspend fun blockedExecution(
        execution: ActionExecutionEntity,
        blocked: DeliveryReservationResult.Blocked,
    ): ChannelExecutionResult {
        val errorCode = "DELIVERY_BLOCKED_${blocked.reason}"
        if (blocked.reason == DeliveryBlockReasons.DUPLICATE_EVENT_CHANNEL ||
            blocked.reason == DeliveryBlockReasons.DUPLICATE_TODO ||
            blocked.reason == DeliveryBlockReasons.DUPLICATE_CONTENT
        ) {
            val now = timeProvider.now().toEpochMilli()
            db.withTransaction {
                blocked.existingDeliveryId?.let { id ->
                    val row = db.deliveryHistoryDao().byId(id)
                    if (row != null && row.status == DeliveryStatuses.RESERVED) {
                        db.deliveryHistoryDao().cancelReserved(id, "SUPERSEDED_BY_DUPLICATE")
                    }
                }
                val current = db.actionExecutionDao().byId(execution.id)
                if (current != null) {
                    ActionExecutionStateMachine.requireTransition(current.status, ActionExecutionStatuses.CANCELLED)
                    db.actionExecutionDao().updateStatusAndError(
                        execution.id,
                        ActionExecutionStatuses.CANCELLED,
                        errorCode,
                        now,
                    )
                }
            }
        } else {
            return cancelExecution(execution, errorCode)
        }
        return ChannelExecutionResult(execution.id, execution.actionType, ActionExecutionStatuses.CANCELLED, errorCode)
    }

    /**
     * A persisted ActionPlan is only executable while its source event still
     * owns the PROCESSING state and has not expired. This guard is deliberately
     * applied at plan entry, retry entry, and immediately before adapter work:
     * startup recovery and outbox workers may otherwise resurrect stale plans.
     */
    private suspend fun ensurePlanEventEligible(
        planId: String,
        now: Long,
        allowCompletedEvent: Boolean = false,
    ): PlanEventEligibility = db.withTransaction {
        val plan = db.actionPlanDao().byId(planId) ?: error("Unknown action plan: $planId")
        ensurePlanEventEligibleInTransaction(plan, now, allowCompletedEvent)
    }

    private suspend fun ensurePlanEventEligibleInTransaction(
        plan: ActionPlanEntity,
        now: Long,
        allowCompletedEvent: Boolean = false,
    ): PlanEventEligibility {
        val event = db.eventDao().byId(plan.eventId)
        val expiresAt = event?.expiresAt
        val reason = when {
            event == null -> EVENT_MISSING
            expiresAt != null && expiresAt <= now -> EVENT_EXPIRED_BEFORE_DELIVERY
            event.status != EventStatuses.PROCESSING &&
                !(allowCompletedEvent && event.status == EventStatuses.COMPLETED) -> EVENT_NOT_EXECUTABLE
            else -> null
        }
        if (reason == null) return PlanEventEligibility.Eligible(plan)

        if (event != null &&
            reason == EVENT_EXPIRED_BEFORE_DELIVERY &&
            event.status !in EventStatuses.terminal &&
            EventStateMachine.canTransition(event.status, EventStatuses.EXPIRED)
        ) {
            db.eventDao().updateStatus(event.id, EventStatuses.EXPIRED)
        }
        quarantineIneligiblePlanInTransaction(plan, event, reason, now)
        return PlanEventEligibility.Ineligible(plan.copy(status = ActionPlanStatuses.CANCELLED), reason)
    }

    /**
     * No adapter call occurs here. Known retryable work is cancelled, while an
     * already-invoked external notification is frozen as OUTCOME_UNKNOWN. This
     * avoids both stale replay and falsely claiming a system side effect never
     * happened.
     */
    private suspend fun quarantineIneligiblePlanInTransaction(
        plan: ActionPlanEntity,
        event: EventEntity?,
        reason: String,
        now: Long,
    ) {
        if (event != null && reason == EVENT_EXPIRED_BEFORE_DELIVERY) {
            // Persisted mutation side effects belong to the source event. Keep
            // chat-claimed/executed work intact, but ensure unaccepted ACTIVE
            // and FIRED triggers cannot be recovered after this event expires.
            db.triggerDao().cancelActiveOrFiredForUnacceptedSourceEvent(event.id)
            db.todoDao().cancelUnacceptedForSourceEvent(event.id, now)
        }

        db.actionExecutionDao().forActionPlan(plan.id).forEach { execution ->
            quarantineIneligibleExecutionInTransaction(event, execution, reason, now)
        }
        db.actionPlanDao().cancelActive(plan.id)
    }

    private suspend fun quarantineIneligibleExecutionInTransaction(
        event: EventEntity?,
        execution: ActionExecutionEntity,
        reason: String,
        now: Long,
    ) {
        val deliveryDao = db.deliveryHistoryDao()
        val delivery = deliveryDao.byExecutionId(execution.id)
        when {
            delivery == null -> Unit

            delivery.status == DeliveryStatuses.DELIVERED && delivery.deliveredAt > 0 -> {
                if (execution.status == ActionExecutionStatuses.RUNNING) {
                    db.actionExecutionDao().updateStatus(
                        execution.id,
                        ActionExecutionStatuses.COMPLETED,
                        now,
                    )
                }
                return
            }

            delivery.status == DeliveryStatuses.RESERVED &&
                delivery.attemptStartedAt != null &&
                hasExternalSystemOutcome(event, delivery) -> {
                deliveryDao.markOutcomeUnknown(delivery.id, UNKNOWN_SYSTEM_OUTCOME)
                if (execution.status in setOf(ActionExecutionStatuses.PENDING, ActionExecutionStatuses.RUNNING)) {
                    db.actionExecutionDao().updateStatusAndError(
                        execution.id,
                        ActionExecutionStatuses.FAILED,
                        UNKNOWN_SYSTEM_OUTCOME,
                        now,
                    )
                }
                return
            }

            delivery.status == DeliveryStatuses.RESERVED -> {
                deliveryDao.cancelReserved(delivery.id, reason)
            }

            delivery.status == DeliveryStatuses.FAILED -> {
                deliveryDao.cancelFailed(delivery.id, reason)
            }
        }
        db.actionExecutionDao().cancelReplayable(execution.id, reason, now)
    }

    private fun hasExternalSystemOutcome(
        event: EventEntity?,
        delivery: DeliveryHistoryEntity,
    ): Boolean =
        delivery.channel == DeliveryOutbox.CHANNEL_NOTIFICATION ||
            (delivery.channel == DeliveryOutbox.CHANNEL_PROACTIVE_MESSAGE &&
                event?.type == ExternalEventTypeNames.WAKE_WEATHER_BRIEF)

    private suspend fun markRunning(executionId: String, now: Long) {
        db.withTransaction {
            val current = db.actionExecutionDao().byId(executionId) ?: return@withTransaction
            if (current.status == ActionExecutionStatuses.RUNNING) return@withTransaction
            ActionExecutionStateMachine.requireTransition(current.status, ActionExecutionStatuses.RUNNING)
            db.actionExecutionDao().updateStatus(executionId, ActionExecutionStatuses.RUNNING, now)
        }
    }

    private fun TodoClaimResult?.claimedTodo(): TodoEntity? = (this as? TodoClaimResult.Claimed)?.todo

    private suspend fun releaseClaimIfOurs(claim: TodoClaimResult?) {
        if (claim !is TodoClaimResult.Claimed) return
        releaseClaimIfOurs(claim.todo, timeProvider.now().toEpochMilli())
    }

    private suspend fun releaseClaimIfOurs(todo: TodoEntity?, now: Long) {
        if (todo == null) return
        val fresh = db.todoDao().byId(todo.id) ?: return
        if (fresh.status == TodoStatuses.CLAIMED && fresh.claimedBy != null) {
            TodoStateMachine.requireTransition(fresh.status, TodoStatuses.READY)
            db.todoDao().releaseClaim(fresh.id, now)
        }
    }

    private suspend fun cancelDuplicateReservations(
        eventId: String,
        todoId: String?,
        winnerExecutionId: String,
    ) {
        val competitors = linkedSetOf<String>()
        db.deliveryHistoryDao().reservedCompetitorsForEvent(eventId, winnerExecutionId).forEach {
            competitors += it.executionId ?: return@forEach
            db.deliveryHistoryDao().cancelReserved(it.id, SUPERSEDED_BY_DELIVERY)
        }
        todoId?.let { id ->
            db.deliveryHistoryDao().reservedCompetitorsForTodo(id, winnerExecutionId).forEach {
                competitors += it.executionId ?: return@forEach
                db.deliveryHistoryDao().cancelReserved(it.id, SUPERSEDED_BY_DELIVERY)
            }
        }
        competitors.forEach { executionId ->
            val execution = db.actionExecutionDao().byId(executionId) ?: return@forEach
            if (execution.status in setOf(ActionExecutionStatuses.PENDING, ActionExecutionStatuses.RUNNING)) {
                db.actionExecutionDao().updateStatusAndError(
                    executionId,
                    ActionExecutionStatuses.CANCELLED,
                    SUPERSEDED_BY_DELIVERY,
                    timeProvider.now().toEpochMilli(),
                )
            }
        }
    }

    private suspend fun finalizePlan(planId: String) {
        db.withTransaction {
            val plan = db.actionPlanDao().byId(planId) ?: return@withTransaction
            if (plan.status in ActionPlanStatuses.terminal) return@withTransaction
            val executions = db.actionExecutionDao().forActionPlan(planId)
            if (executions.any { it.status in setOf(ActionExecutionStatuses.PENDING, ActionExecutionStatuses.RUNNING) }) {
                return@withTransaction
            }
            ActionPlanStateMachine.requireTransition(plan.status, ActionPlanStatuses.COMPLETED)
            if (db.actionPlanDao().transitionStatusWithActiveKey(
                    planId,
                    plan.status,
                    ActionPlanStatuses.COMPLETED,
                ) != 1
            ) {
                return@withTransaction
            }

            val event = db.eventDao().byId(plan.eventId)
            if (event != null && EventStateMachine.canTransition(event.status, EventStatuses.COMPLETED)) {
                db.eventDao().updateStatus(event.id, EventStatuses.COMPLETED)
            }
        }
    }

    private fun snapshotPlan(plan: ActionPlanEntity): ChannelPlanReport = ChannelPlanReport(
        planId = plan.id,
        eventId = plan.eventId,
        results = emptyList(),
        completed = 0,
        failed = 0,
        cancelled = 0,
    )

    private suspend fun sceneCooldownGroupForPlan(plan: ActionPlanEntity): String? {
        val event = db.eventDao().byId(plan.eventId) ?: return null
        return SceneCooldown.forEventType(event.type)
    }

    private suspend fun isHighWeatherAlertQuotaExempt(plan: ActionPlanEntity): Boolean {
        val event = db.eventDao().byId(plan.eventId) ?: return false
        if (event.type != ExternalEventTypeNames.WEATHER_ALERT) return false
        val payload = parsePayload(event.payloadJson)
        return payload["severity"]?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content == "HIGH"
    }

    private fun parsePayload(rawJson: String): JsonObject =
        runCatching { com.littlekitty.xiaokele.core.common.json.JsonCodec.decode<JsonObject>(rawJson) }
            .getOrNull() ?: JsonObject(emptyMap())

    private fun parsePayload(execution: ActionExecutionEntity): JsonObject =
        runCatching { com.littlekitty.xiaokele.core.common.json.JsonCodec.decode<JsonObject>(execution.payloadJson ?: "{}") }
            .getOrNull() ?: JsonObject(emptyMap())

    private fun JsonObject.optionalString(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.optionalInt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun ActionExecutionEntity.toResult(): ChannelExecutionResult =
        ChannelExecutionResult(id, actionType, status, errorCode)

    private sealed interface TodoClaimResult {
        data class Claimed(val todo: TodoEntity) : TodoClaimResult
        data object NotClaimable : TodoClaimResult
    }

    private sealed interface PlanEventEligibility {
        data class Eligible(val plan: ActionPlanEntity) : PlanEventEligibility
        data class Ineligible(val plan: ActionPlanEntity, val reason: String) : PlanEventEligibility
    }

    private data class RetryClaim(
        val plan: ActionPlanEntity,
        val execution: ActionExecutionEntity,
    )

    private sealed interface ProactiveMessageWrite {
        data object Created : ProactiveMessageWrite
        data object AlreadyExists : ProactiveMessageWrite
        data object BudgetExceeded : ProactiveMessageWrite
        data class Failed(val detail: String) : ProactiveMessageWrite
    }

    companion object {
        const val ROLE_ASSISTANT = "ASSISTANT"
        const val NOTIFICATION_PERMISSION_DENIED = "NOTIFICATION_PERMISSION_DENIED"
        const val NOTIFICATION_ADAPTER_FAILURE = "NOTIFICATION_ADAPTER_FAILURE"
        const val PROACTIVE_WRITE_FAILED = "PROACTIVE_WRITE_FAILED"
        const val DAILY_PROACTIVE_BUDGET_BLOCKED = "DELIVERY_BLOCKED_DAILY_PROACTIVE_BUDGET"
        const val PET_STATE_TRANSITION_REJECTED = "PET_STATE_TRANSITION_REJECTED"
        const val PET_STATE_EXPIRED = "PET_STATE_EXPIRED"
        const val SUPERSEDED_BY_DELIVERY = "SUPERSEDED_BY_DELIVERY"
        const val EVENT_EXPIRED_BEFORE_DELIVERY = "EVENT_EXPIRED_BEFORE_DELIVERY"
        const val EVENT_NOT_EXECUTABLE = "EVENT_NOT_EXECUTABLE"
        const val EVENT_MISSING = "EVENT_MISSING"
        const val UNKNOWN_SYSTEM_OUTCOME = "UNKNOWN_SYSTEM_OUTCOME_AFTER_RESTART"

        /** Default pending-bubble validity when the event carries no expiry. */
        const val DEFAULT_BUBBLE_TTL_MILLIS = 30L * 60L * 1000L
    }
}
