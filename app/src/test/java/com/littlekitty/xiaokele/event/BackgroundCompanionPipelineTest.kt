package com.littlekitty.xiaokele.event

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.animation.CatalogAnimationAdapter
import com.littlekitty.xiaokele.automation.AutomationRule
import com.littlekitty.xiaokele.automation.AutomationRuleDocument
import com.littlekitty.xiaokele.automation.AutomationRuleModes
import com.littlekitty.xiaokele.automation.AutomationRuleStore
import com.littlekitty.xiaokele.automation.AutomationTrigger
import com.littlekitty.xiaokele.channel.ChannelExecutionCoordinator
import com.littlekitty.xiaokele.chat.CharacterCoreBootstrap
import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionNames
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.AgentOutput
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.DecisionNames
import com.littlekitty.xiaokele.core.common.protocol.MutationOperationNames
import com.littlekitty.xiaokele.core.common.protocol.TodoMutation
import com.littlekitty.xiaokele.core.common.protocol.TodoTypeNames
import com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.delivery.DeliveryDedupGuard
import com.littlekitty.xiaokele.core.database.delivery.DeliveryOutbox
import com.littlekitty.xiaokele.core.database.policy.DevelopmentCandidatePolicies
import com.littlekitty.xiaokele.core.database.policy.PolicyResolver
import com.littlekitty.xiaokele.core.database.policy.ProductScenePolicyCatalog
import com.littlekitty.xiaokele.core.database.repository.BackgroundMutationExecutor
import com.littlekitty.xiaokele.core.database.repository.TriggerManager
import com.littlekitty.xiaokele.core.database.repository.TodoManager
import com.littlekitty.xiaokele.core.database.state.ActionExecutionStatuses
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import com.littlekitty.xiaokele.core.database.state.TriggerStatuses
import com.littlekitty.xiaokele.core.network.model.FakeModelMode
import com.littlekitty.xiaokele.core.network.model.FakeModelService
import com.littlekitty.xiaokele.core.network.model.ModelService
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import com.littlekitty.xiaokele.notification.NotificationAdapter
import com.littlekitty.xiaokele.notification.NotificationSendRequest
import com.littlekitty.xiaokele.notification.NotificationSendResult
import com.littlekitty.xiaokele.widget.WidgetRefreshResult
import com.littlekitty.xiaokele.widget.WidgetStateAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class BackgroundCompanionPipelineTest {

    private lateinit var db: AppDatabase
    private lateinit var timeProvider: TimeProvider
    private lateinit var fakeModel: FakeModelService
    private lateinit var dispatcher: LocalBackgroundDispatcher
    private lateinit var triggerManager: TriggerManager
    private lateinit var todoManager: TodoManager
    private var now: Instant = Instant.parse("2026-08-16T10:00:00Z")

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        timeProvider = object : TimeProvider {
            override fun now(): Instant = now
            override fun zone(): ZoneId = ZoneId.of("UTC")
        }
        CharacterCoreBootstrap.ensureSeeded(db, now.toEpochMilli())
        DevelopmentCandidatePolicies.ensureSeeded(db)
        ProductScenePolicyCatalog.ensureSeeded(db)
        fakeModel = FakeModelService(FakeModelMode.BACKGROUND_NO_ACTION)
        val pipeline = BackgroundCompanionPipeline(
            db = db,
            modelService = fakeModel,
            policyResolver = PolicyResolver(db),
            contextBuilder = BackgroundContextBuilder(db, timeProvider),
            mutationExecutor = BackgroundMutationExecutor(db, db.todoDao(), db.triggerDao()),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
            channelCoordinator = buildCoordinator(),
        )
        dispatcher = LocalBackgroundDispatcher(EventNormalizer(db.eventDao(), timeProvider), pipeline)
        triggerManager = TriggerManager(db, db.triggerDao())
        todoManager = TodoManager(db, db.todoDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun unknownEventOpenPolicyCanBeJudgedNoActionWithAuditAndNoDelivery() = runBlocking {
        fakeModel.mode = FakeModelMode.BACKGROUND_NO_ACTION

        val result = dispatcher.dispatch(event(type = "DEBUG_UNKNOWN_EVENT", key = "open:1"))

        assertTrue(result is EventDispatchResult.Processed)
        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue(processing is BackgroundProcessingResult.NoActionAudited)
        val eventId = (processing as BackgroundProcessingResult.NoActionAudited).eventId
        assertEquals(EventStatuses.FILTERED, db.eventDao().byId(eventId)?.status)
        val plan = db.actionPlanDao().forEvent(eventId).single()
        assertTrue(plan.agentOutputJson.contains("\"NO_ACTION\""))
        assertEquals(0, db.actionExecutionDao().forActionPlan(plan.id).size)
        assertEquals(0, db.deliveryHistoryDao().count())
        assertEquals(0, db.todoDao().count())
    }

    @Test
    fun guidedEventExecutesAllowedBackgroundAction() = runBlocking {
        fakeModel.mode = FakeModelMode.BACKGROUND_ACTION

        val result = dispatcher.dispatch(event(type = DevelopmentCandidatePolicies.EVENT_TYPE_TEST_GUIDED, key = "guided:1"))

        assertTrue(result is EventDispatchResult.Processed)
        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue("processing=$processing", processing is BackgroundProcessingResult.PlanExecuted)
        val eventId = (processing as BackgroundProcessingResult.PlanExecuted).eventId
        assertEquals(EventStatuses.COMPLETED, db.eventDao().byId(eventId)?.status)
        val plan = db.actionPlanDao().forEvent(eventId).single()
        assertEquals(1, db.actionExecutionDao().forActionPlan(plan.id).size)
        assertEquals(ActionExecutionStatuses.COMPLETED, db.actionExecutionDao().forActionPlan(plan.id).single().status)
    }

    @Test
    fun invalidReplyChatGetsOneRepairAndThenExecutesValidBackgroundPlan() = runBlocking {
        val invalid = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.REPLY_CHAT,
                    payload = buildJsonObject { put("content", "错误的后台聊天回复") },
                ),
            ),
        )
        val repaired = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.SEND_PROACTIVE_MESSAGE,
                    payload = buildJsonObject { put("content", "改成合法的后台主动消息") },
                ),
            ),
        )
        val model = SequenceDecisionModelService(
            listOf(JsonCodec.encode(invalid), JsonCodec.encode(repaired)),
        )
        val pipeline = BackgroundCompanionPipeline(
            db = db,
            modelService = model,
            policyResolver = PolicyResolver(db),
            contextBuilder = BackgroundContextBuilder(db, timeProvider),
            mutationExecutor = BackgroundMutationExecutor(db, db.todoDao(), db.triggerDao()),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
            channelCoordinator = buildCoordinator(),
        )
        val result = LocalBackgroundDispatcher(EventNormalizer(db.eventDao(), timeProvider), pipeline)
            .dispatch(event(type = "DEBUG_UNKNOWN_EVENT", key = "open:repair-reply"))

        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue("processing=$processing", processing is BackgroundProcessingResult.PlanExecuted)
        assertEquals(2, model.contexts.size)
        assertTrue(model.contexts[1].event?.containsKey("model_repair") == true)
        assertEquals(EventStatuses.COMPLETED, db.eventDao().byId((processing as BackgroundProcessingResult.PlanExecuted).eventId)?.status)
        assertEquals(1, db.deliveryHistoryDao().countDelivered())
    }

    @Test
    fun adapterExtractionFailureAlsoGetsExactlyOneRepair() = runBlocking {
        val repaired = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.SEND_PROACTIVE_MESSAGE,
                    payload = buildJsonObject { put("content", "修复后的结构化主动消息") },
                ),
            ),
        )
        val model = SequenceModelResultService(
            listOf(
                ModelServiceResult.Failure(
                    AppError(
                        code = ErrorCode.MODEL_OUTPUT_INVALID,
                        technicalDetail = "minimax_decision_extraction_failed",
                    ),
                ),
                ModelServiceResult.Success(JsonCodec.encode(repaired)),
            ),
        )
        val pipeline = BackgroundCompanionPipeline(
            db = db,
            modelService = model,
            policyResolver = PolicyResolver(db),
            contextBuilder = BackgroundContextBuilder(db, timeProvider),
            mutationExecutor = BackgroundMutationExecutor(db, db.todoDao(), db.triggerDao()),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
            channelCoordinator = buildCoordinator(),
        )
        val result = LocalBackgroundDispatcher(EventNormalizer(db.eventDao(), timeProvider), pipeline)
            .dispatch(event(type = "DEBUG_UNKNOWN_EVENT", key = "open:repair-extraction"))

        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue("processing=$processing", processing is BackgroundProcessingResult.PlanExecuted)
        assertEquals(2, model.contexts.size)
        assertTrue(model.contexts[1].event?.containsKey("model_repair") == true)
    }

    @Test
    fun requiredProductSceneTodoIsCreatedAndLinkedLocally() = runBlocking {
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.SEND_PROACTIVE_MESSAGE,
                    payload = buildJsonObject { put("content", "好久没见，今天过得怎么样？") },
                ),
            ),
        )
        val pipeline = BackgroundCompanionPipeline(
            db = db,
            modelService = RawDecisionModelService(JsonCodec.encode(decision)),
            policyResolver = PolicyResolver(db),
            contextBuilder = BackgroundContextBuilder(db, timeProvider),
            mutationExecutor = BackgroundMutationExecutor(db, db.todoDao(), db.triggerDao()),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
            channelCoordinator = buildCoordinator(),
        )
        val result = LocalBackgroundDispatcher(EventNormalizer(db.eventDao(), timeProvider), pipeline)
            .dispatch(event(type = "INTERACTION_GAP", key = "gap:required-todo"))

        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue("processing=$processing", processing is BackgroundProcessingResult.PlanExecuted)
        val executed = processing as BackgroundProcessingResult.PlanExecuted
        assertEquals(1, executed.mutationReport.todosCreated)
        val todo = db.todoDao().byId(executed.mutationReport.touchedTodoIds.single())
        assertEquals(TodoTypeNames.DISCUSS_LATER, todo?.type)
        val action = db.actionExecutionDao().forActionPlan(executed.planId).single()
        assertTrue(action.payloadJson.orEmpty().contains(todo!!.id))
    }

    @Test
    fun openEventCanCreateTodoAndTriggerTransactionally() = runBlocking {
        fakeModel.mode = FakeModelMode.BACKGROUND_TODO_TRIGGER

        val result = dispatcher.dispatch(event(type = "DEBUG_UNKNOWN_EVENT", key = "open:2"))

        assertTrue(result is EventDispatchResult.Processed)
        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue("processing=$processing", processing is BackgroundProcessingResult.PlanExecuted)
        val report = (processing as BackgroundProcessingResult.PlanExecuted).mutationReport
        assertEquals(1, report.todosCreated)
        assertEquals(1, report.triggersCreated)
        val todo = db.todoDao().byId(report.touchedTodoIds.single())
        assertEquals(TodoStatuses.READY, todo?.status)
        val trigger = db.triggerDao().byId(report.touchedTriggerIds.single())
        assertEquals(TriggerStatuses.ACTIVE, trigger?.status)
        assertTrue(trigger?.nextFireAt!! > now.toEpochMilli())
    }

    @Test
    fun strictNoActionPolicyForcesNoActionWithoutModelOutput() = runBlocking {
        fakeModel.mode = FakeModelMode.FAILURE

        val result = dispatcher.dispatch(event(type = DevelopmentCandidatePolicies.EVENT_TYPE_TEST_NO_ACTION, key = "strict:no"))

        assertTrue(result is EventDispatchResult.Processed)
        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue(processing is BackgroundProcessingResult.NoActionAudited)
        val eventId = (processing as BackgroundProcessingResult.NoActionAudited).eventId
        assertEquals(EventStatuses.FILTERED, db.eventDao().byId(eventId)?.status)
        assertEquals(0, db.deliveryHistoryDao().count())
    }

    @Test
    fun modelFailureIsAuditedAndRetryScheduledInsteadOfImmediateTerminalState() = runBlocking {
        fakeModel.mode = FakeModelMode.FAILURE

        val result = dispatcher.dispatch(event(type = "DEBUG_UNKNOWN_EVENT", key = "open:fail"))

        assertTrue(result is EventDispatchResult.Processed)
        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue("processing=$processing", processing is BackgroundProcessingResult.RetryScheduled)
        val retry = processing as BackgroundProcessingResult.RetryScheduled
        val event = db.eventDao().byId(retry.eventId)
        assertEquals(EventStatuses.RECEIVED, event?.status)
        assertEquals(1, event?.attemptCount)
        assertEquals("MODEL_FAILURE", event?.lastErrorCode)
        assertTrue((event?.nextRetryAt ?: 0L) > now.toEpochMilli())
        assertEquals(0, db.actionPlanDao().forEvent(retry.eventId).size)
        assertEquals(0, db.deliveryHistoryDao().count())
        assertEquals(0, db.todoDao().count())
    }

    @Test
    fun persistentQuotaDenialIsTerminalAndIsNotRetried() = runBlocking {
        val model = SequenceModelResultService(
            listOf(
                ModelServiceResult.Failure(
                    AppError(
                        code = ErrorCode.MODEL_FAILURE,
                        technicalDetail = "persistent_generation_quota_exhausted",
                    ),
                ),
            ),
        )
        val pipelineWithQuotaDenial = BackgroundCompanionPipeline(
            db = db,
            modelService = model,
            policyResolver = PolicyResolver(db),
            contextBuilder = BackgroundContextBuilder(db, timeProvider),
            mutationExecutor = BackgroundMutationExecutor(db, db.todoDao(), db.triggerDao()),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
            channelCoordinator = buildCoordinator(),
        )

        val result = LocalBackgroundDispatcher(EventNormalizer(db.eventDao(), timeProvider), pipelineWithQuotaDenial)
            .dispatch(event(type = "DEBUG_UNKNOWN_EVENT", key = "open:quota-denial"))

        assertTrue(result is EventDispatchResult.Processed)
        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue("processing=$processing", processing is BackgroundProcessingResult.Failed)
        val failed = processing as BackgroundProcessingResult.Failed
        assertEquals(EventStatuses.FAILED, db.eventDao().byId(failed.eventId)?.status)
        assertEquals(1, db.eventDao().byId(failed.eventId)?.attemptCount)
    }

    @Test
    fun invalidMutationFailsEventWithNoPartialMutationWrites() = runBlocking {
        val pipelineWithRaw = BackgroundCompanionPipeline(
            db = db,
            modelService = RawDecisionModelService(invalidMutationDecision()),
            policyResolver = PolicyResolver(db),
            contextBuilder = BackgroundContextBuilder(db, timeProvider),
            mutationExecutor = BackgroundMutationExecutor(db, db.todoDao(), db.triggerDao()),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
            channelCoordinator = buildCoordinator(),
        )
        val rawDispatcher = LocalBackgroundDispatcher(EventNormalizer(db.eventDao(), timeProvider), pipelineWithRaw)

        val result = rawDispatcher.dispatch(event(type = "DEBUG_UNKNOWN_EVENT", key = "open:invalid"))

        assertTrue(result is EventDispatchResult.Processed)
        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue(processing is BackgroundProcessingResult.Failed)
        val eventId = (processing as BackgroundProcessingResult.Failed).eventId
        assertEquals(EventStatuses.FAILED, db.eventDao().byId(eventId)?.status)
        assertEquals(0, db.todoDao().count())
        assertEquals(0, db.triggerDao().count())
        assertEquals(0, db.actionPlanDao().count())
    }

    @Test
    fun guidedPolicyRejectsActionOutsidePolicyAllowlist() = runBlocking {
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.SEND_NOTIFICATION,
                    payload = buildJsonObject { put("title", "越权通知") },
                ),
            ),
        )
        val pipelineWithRaw = BackgroundCompanionPipeline(
            db = db,
            modelService = RawDecisionModelService(
                com.littlekitty.xiaokele.core.common.json.JsonCodec.encode(decision),
            ),
            policyResolver = PolicyResolver(db),
            contextBuilder = BackgroundContextBuilder(db, timeProvider),
            mutationExecutor = BackgroundMutationExecutor(db, db.todoDao(), db.triggerDao()),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
            channelCoordinator = buildCoordinator(),
        )
        val rawDispatcher = LocalBackgroundDispatcher(EventNormalizer(db.eventDao(), timeProvider), pipelineWithRaw)

        val result = rawDispatcher.dispatch(event(type = DevelopmentCandidatePolicies.EVENT_TYPE_TEST_GUIDED, key = "guided:disallow"))

        assertTrue(result is EventDispatchResult.Processed)
        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue("processing=$processing", processing is BackgroundProcessingResult.Failed)
        assertEquals("MODEL_OUTPUT_INVALID", (processing as BackgroundProcessingResult.Failed).errorCode)
        assertEquals(EventStatuses.FAILED, db.eventDao().byId(processing.eventId)?.status)
        assertEquals(0, db.actionPlanDao().count())
    }

    @Test
    fun dueTriggerReEntersDecisionAndCompletesAfterFire() = runBlocking {
        val todo = todoManager.create(type = TodoTypeNames.REEVALUATE_LATER, now = now.toEpochMilli())
        triggerManager.create(
            todoId = todo.id,
            triggerType = "TIME",
            triggerConfigJson = """{"fire_at":"due"}""",
            nextFireAt = now.toEpochMilli() - 1,
        )
        fakeModel.mode = FakeModelMode.BACKGROUND_NO_ACTION

        val scheduler = DueTriggerScheduler(db, triggerManager, dispatcher, timeProvider)
        val report = scheduler.dispatchDue(now)

        assertEquals(1, report.fired)
        assertEquals(1, report.eventsDispatched)
        assertEquals(1, report.completedAfterFire)
        assertEquals(TriggerStatuses.COMPLETED, triggerManager.forTodo(todo.id).single().status)
        assertEquals(1, db.eventDao().count())
        assertTrue(report.results.single().second is BackgroundProcessingResult.NoActionAudited)
    }

    @Test
    fun staleDueTriggerExpiresWithoutCreatingFreshEventAtRecoveryTime() = runBlocking {
        val todo = todoManager.create(type = TodoTypeNames.REEVALUATE_LATER, now = now.toEpochMilli())
        val trigger = triggerManager.create(
            todoId = todo.id,
            triggerType = "TIME",
            triggerConfigJson = """{"fire_at":"stale"}""",
            nextFireAt = now.toEpochMilli() - TriggerDispatchPolicy.MAX_LATENESS_MILLIS - 1L,
        )

        val scheduler = DueTriggerScheduler(db, triggerManager, dispatcher, timeProvider)
        val report = scheduler.dispatchDue(now)

        assertEquals(1, report.expiredLate)
        assertEquals(0, report.eventsDispatched)
        assertEquals(0, db.eventDao().count())
        assertEquals(TriggerStatuses.EXPIRED, triggerManager.byId(trigger.id)?.status)
    }

    @Test
    fun expiredTodoCancelsItsFiredTriggerInsteadOfDispatchingIt() = runBlocking {
        val todo = todoManager.create(
            type = TodoTypeNames.REEVALUATE_LATER,
            now = now.toEpochMilli() - 120_000L,
            expiresAt = now.toEpochMilli() - 1L,
        )
        val trigger = triggerManager.create(
            todoId = todo.id,
            triggerType = "TIME",
            triggerConfigJson = """{"fire_at":"expired-todo"}""",
            nextFireAt = now.toEpochMilli() - 60_000L,
        )

        DueTriggerScheduler(db, triggerManager, dispatcher, timeProvider).dispatchDue(now)

        assertEquals(0, db.eventDao().count())
        assertEquals(TriggerStatuses.CANCELLED, triggerManager.byId(trigger.id)?.status)
    }

    @Test
    fun processingCompletedEventAgainReturnsAlreadyTerminal() = runBlocking {
        fakeModel.mode = FakeModelMode.BACKGROUND_NO_ACTION
        val first = dispatcher.dispatch(event(type = "DEBUG_UNKNOWN_EVENT", key = "open:again"))
        val eventId = ((first as EventDispatchResult.Processed).processing as BackgroundProcessingResult.NoActionAudited).eventId

        val second = (dispatcher as LocalBackgroundDispatcher).dispatch(
            event(type = "DEBUG_UNKNOWN_EVENT", key = "open:again-2"),
        )
        val secondEventId = ((second as EventDispatchResult.Processed).processing as BackgroundProcessingResult.NoActionAudited).eventId
        assertTrue(secondEventId != eventId)

        val repeated = BackgroundCompanionPipeline(
            db = db,
            modelService = fakeModel,
            policyResolver = PolicyResolver(db),
            contextBuilder = BackgroundContextBuilder(db, timeProvider),
            mutationExecutor = BackgroundMutationExecutor(db, db.todoDao(), db.triggerDao()),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
            channelCoordinator = buildCoordinator(),
        ).process(eventId)
        assertTrue(repeated is BackgroundProcessingResult.AlreadyTerminal)
    }

    private fun buildCoordinator(): ChannelExecutionCoordinator = ChannelExecutionCoordinator(
        db = db,
        deliveryOutbox = DeliveryOutbox(db, db.deliveryHistoryDao()),
        notificationAdapter = TestNotificationAdapter(),
        widgetStateAdapter = TestWidgetStateAdapter(),
        animationAdapter = CatalogAnimationAdapter(),
        timeProvider = timeProvider,
    )

    private class TestNotificationAdapter : NotificationAdapter {
        override suspend fun send(request: NotificationSendRequest): NotificationSendResult =
            NotificationSendResult.Delivered
    }

    private class TestWidgetStateAdapter : WidgetStateAdapter {
        override suspend fun refreshCurrentPetState(): WidgetRefreshResult =
            WidgetRefreshResult.Refreshed(emptyList())
    }

    @Test
    fun phase5ProactiveChannelReallyExecutesInsteadOfDeferring() = runBlocking {
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(
                    action = AgentActionNames.SEND_PROACTIVE_MESSAGE,
                    payload = buildJsonObject { put("content", "Phase 5 主动消息真实落库") },
                ),
            ),
        )
        val pipelineWithRaw = BackgroundCompanionPipeline(
            db = db,
            modelService = RawDecisionModelService(
                com.littlekitty.xiaokele.core.common.json.JsonCodec.encode(decision),
            ),
            policyResolver = PolicyResolver(db),
            contextBuilder = BackgroundContextBuilder(db, timeProvider),
            mutationExecutor = BackgroundMutationExecutor(db, db.todoDao(), db.triggerDao()),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
            channelCoordinator = buildCoordinator(),
        )
        val rawDispatcher = LocalBackgroundDispatcher(EventNormalizer(db.eventDao(), timeProvider), pipelineWithRaw)

        val result = rawDispatcher.dispatch(event(type = "DEBUG_UNKNOWN_EVENT", key = "open:phase5"))

        assertTrue(result is EventDispatchResult.Processed)
        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue(processing is BackgroundProcessingResult.PlanExecuted)
        val channelReport = (processing as BackgroundProcessingResult.PlanExecuted).channelReport
        assertEquals(1, channelReport.completed)
        assertEquals(0, channelReport.cancelled)
        assertEquals(1, db.messageDao().count())
        assertEquals(1, db.deliveryHistoryDao().countDelivered())
    }

    @Test
    fun importedFixedRuleRunsThroughMatchingPlanAndDelivery() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AutomationRuleStore(context)
        store.save(
            AutomationRuleDocument(
                rules = listOf(
                    AutomationRule(
                        id = "custom-weather-change",
                        name = "自定义天气变化问候",
                        trigger = AutomationTrigger(ExternalEventTypeNames.WEATHER_CHANGE),
                        mode = AutomationRuleModes.FIXED_MESSAGE,
                        fixedMessage = "变天了，出门记得带伞。",
                        animationId = "dog_caring",
                    ),
                ),
            ),
        )
        val pipeline = BackgroundCompanionPipeline(
            db = db,
            modelService = FakeModelService(FakeModelMode.BACKGROUND_NO_ACTION),
            policyResolver = PolicyResolver(db),
            contextBuilder = BackgroundContextBuilder(db, timeProvider),
            mutationExecutor = BackgroundMutationExecutor(db, db.todoDao(), db.triggerDao()),
            animationAdapter = CatalogAnimationAdapter(),
            timeProvider = timeProvider,
            channelCoordinator = buildCoordinator(),
            automationRuleStore = store,
        )

        val result = LocalBackgroundDispatcher(EventNormalizer(db.eventDao(), timeProvider), pipeline)
            .dispatch(event(type = ExternalEventTypeNames.WEATHER_CHANGE, key = "custom-rule:weather-change"))

        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue("processing=$processing", processing is BackgroundProcessingResult.PlanExecuted)
        val executed = processing as BackgroundProcessingResult.PlanExecuted
        val plan = db.actionPlanDao().forEvent(executed.eventId).single()
        val actions = db.actionExecutionDao().forActionPlan(plan.id).map { it.actionType }
        assertTrue(actions.contains(AgentActionNames.SEND_PROACTIVE_MESSAGE))
        assertTrue(actions.contains(AgentActionNames.PLAY_ANIMATION))
        assertEquals("变天了，出门记得带伞。", db.messageDao().assistantForEvent(executed.eventId)?.content)
        assertEquals(2, db.actionExecutionDao().forActionPlan(plan.id).count { it.status == ActionExecutionStatuses.COMPLETED })
    }

    private fun invalidMutationDecision(): String {
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            todoMutations = listOf(
                TodoMutation(
                    operation = MutationOperationNames.CREATE,
                    todo = buildJsonObject { put("type", "NOT_A_CANDIDATE_TYPE") },
                ),
            ),
        )
        return com.littlekitty.xiaokele.core.common.json.JsonCodec.encode(decision)
    }

    private fun event(type: String, key: String): com.littlekitty.xiaokele.core.common.protocol.NormalizedEvent =
        com.littlekitty.xiaokele.core.common.protocol.NormalizedEvent(
            eventKey = key,
            type = type,
            source = "TEST",
            occurredAt = InstantJson.formatUtc(now),
            receivedAt = InstantJson.formatUtc(now),
            expiresAt = InstantJson.formatUtc(now.plusSeconds(600)),
            priority = 50,
            payload = buildJsonObject { put("test", true) },
        )

    private class RawDecisionModelService(
        private val raw: String,
    ) : ModelService {
        override val serviceName: String = "raw_background_test_model"
        override suspend fun generate(context: ContextPack): ModelServiceResult =
            ModelServiceResult.Success(raw)
    }

    private class SequenceDecisionModelService(
        private val decisions: List<String>,
    ) : ModelService {
        override val serviceName: String = "sequence_background_test_model"
        val contexts = mutableListOf<ContextPack>()

        override suspend fun generate(context: ContextPack): ModelServiceResult {
            contexts += context
            return ModelServiceResult.Success(decisions[contexts.lastIndex.coerceAtMost(decisions.lastIndex)])
        }
    }

    private class SequenceModelResultService(
        private val results: List<ModelServiceResult>,
    ) : ModelService {
        override val serviceName: String = "sequence_result_background_test_model"
        val contexts = mutableListOf<ContextPack>()

        override suspend fun generate(context: ContextPack): ModelServiceResult {
            contexts += context
            return results[contexts.lastIndex.coerceAtMost(results.lastIndex)]
        }
    }
}
