package com.littlekitty.xiaokele

import androidx.room.withTransaction
import com.littlekitty.xiaokele.channel.OutboxRetryWorker
import com.littlekitty.xiaokele.channel.OutboxRetryWorkScheduler
import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.DecisionNames
import com.littlekitty.xiaokele.core.common.scene.SceneCandidateThresholds
import com.littlekitty.xiaokele.core.database.delivery.DeliveryOutbox
import com.littlekitty.xiaokele.core.database.entity.ActionExecutionEntity
import com.littlekitty.xiaokele.core.database.entity.ActionPlanEntity
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.repository.RecoveryManager
import com.littlekitty.xiaokele.core.database.repository.TriggerManager
import com.littlekitty.xiaokele.core.database.state.ActionExecutionStatuses
import com.littlekitty.xiaokele.core.database.state.ActionPlanStatuses
import com.littlekitty.xiaokele.core.database.state.DeliveryStatuses
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import com.littlekitty.xiaokele.core.database.state.TriggerStatuses
import com.littlekitty.xiaokele.core.network.external.WeatherFetchResult
import com.littlekitty.xiaokele.core.network.external.WeatherLocation
import com.littlekitty.xiaokele.core.network.external.WeatherService
import com.littlekitty.xiaokele.core.network.external.WeatherSnapshotDto
import com.littlekitty.xiaokele.core.network.model.ModelService
import com.littlekitty.xiaokele.core.network.model.ModelServiceResult
import com.littlekitty.xiaokele.event.BackgroundProcessingResult
import com.littlekitty.xiaokele.event.DueTriggerScheduler
import com.littlekitty.xiaokele.external.ExternalEventContract
import com.littlekitty.xiaokele.external.WeatherRefresh
import com.littlekitty.xiaokele.handoff.InternalHandoffRequest
import com.littlekitty.xiaokele.handoff.InternalHandoffResult
import com.littlekitty.xiaokele.handoff.IntentHandoffManager
import com.littlekitty.xiaokele.scene.Phase7TestContext
import com.littlekitty.xiaokele.scene.SceneContextSnapshot
import com.littlekitty.xiaokele.scene.SceneDecision
import com.littlekitty.xiaokele.scene.WeatherSceneEvaluator
import com.littlekitty.xiaokele.scene.buildPhase7TestContext
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.scene.NoActionRecordingModel
import com.littlekitty.xiaokele.scene.RawDecisionModelService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class Phase8AuditMatrixTest {

    private lateinit var ctx: Phase7TestContext
    private val now = Instant.parse("2026-08-16T10:00:00Z")

    @Before
    fun setUp() {
        ctx = buildPhase7TestContext(now)
    }

    @After
    fun tearDown() {
        ctx.close()
    }

    @Test
    fun firedTriggerCrashRecoveryUsesDeterministicKeyAndCompletesOnce() = runBlocking {
        val todo = ctx.db.todoDao().byStatuses(listOf(TodoStatuses.READY)).firstOrNull()
            ?: com.littlekitty.xiaokele.core.database.repository.TodoManager(ctx.db, ctx.db.todoDao())
                .create(type = "DISCUSS_LATER", now = now.toEpochMilli())
        val triggerId = "trg_00000000000000000000000000000001"
        val fireAt = now.minusSeconds(30).toEpochMilli()
        ctx.db.triggerDao().upsert(
            com.littlekitty.xiaokele.core.database.entity.TriggerEntity(
                id = triggerId,
                todoId = todo.id,
                triggerType = "TIME",
                triggerConfigJson = "{}",
                nextFireAt = fireAt,
                lastFireAt = fireAt,
                status = TriggerStatuses.FIRED,
            ),
        )
        val model = NoActionRecordingModel()
        val scheduler = DueTriggerScheduler(
            db = ctx.db,
            triggerManager = TriggerManager(ctx.db, ctx.db.triggerDao()),
            dispatcher = ctx.buildDispatcher(model),
            timeProvider = ctx.time,
        )

        val recovered = scheduler.recoverFired(ctx.time.now())
        assertEquals(1, recovered.firedRecovered)
        assertEquals(1, ctx.db.eventDao().count())
        val event = ctx.db.eventDao().recent(1).single()
        assertEquals(DueTriggerScheduler.triggerEventKey(triggerId, fireAt), event.eventKey)
        assertEquals(TriggerStatuses.COMPLETED, ctx.db.triggerDao().byId(triggerId)?.status)
        assertEquals(listOf(event.id), model.processedEventIds)

        val second = scheduler.recoverFired(ctx.time.now())
        assertEquals(0, second.firedRecovered)
        assertEquals(1, ctx.db.eventDao().count())
    }

    @Test
    fun dueTriggerIsExactlyOnceUnderConcurrentDispatchers() = runBlocking {
        val todo = com.littlekitty.xiaokele.core.database.repository.TodoManager(ctx.db, ctx.db.todoDao())
            .create(type = "DISCUSS_LATER", now = now.toEpochMilli())
        val triggerId = "trg_00000000000000000000000000000002"
        val fireAt = now.minusSeconds(10).toEpochMilli()
        ctx.db.triggerDao().upsert(
            com.littlekitty.xiaokele.core.database.entity.TriggerEntity(
                id = triggerId,
                todoId = todo.id,
                triggerType = "TIME",
                triggerConfigJson = "{}",
                nextFireAt = fireAt,
                status = TriggerStatuses.ACTIVE,
            ),
        )
        val model = NoActionRecordingModel()
        val dispatcher = ctx.buildDispatcher(model)

        val reports = (1..8).map {
            async(Dispatchers.IO) {
                DueTriggerScheduler(ctx.db, TriggerManager(ctx.db, ctx.db.triggerDao()), dispatcher, ctx.time)
                    .dispatchDue(now)
            }
        }.awaitAll()

        assertEquals(1, reports.sumOf { it.fired })
        assertEquals(1, reports.sumOf { it.eventsDispatched })
        assertEquals(1, ctx.db.eventDao().count())
        assertEquals(TriggerStatuses.COMPLETED, ctx.db.triggerDao().byId(triggerId)?.status)
        assertEquals(1, model.processedEventIds.size)
        assertTrue(ctx.db.eventDao().recent(1).single().eventKey.startsWith("trigger:fire:$triggerId:$fireAt"))
    }

    @Test
    fun modelReturningAfterEventExpiryCommitsZeroSideEffects() = runBlocking {
        val event = ctx.insertEvent(
            type = "DEBUG_UNKNOWN_EVENT",
            key = "llm:expiry:1",
            payload = buildJsonObject { put("test", true) },
            priority = 50,
        )
        val model = GateNoActionModel()
        val pipeline = ctx.buildPipeline(model)
        val deferred = async(Dispatchers.IO) { pipeline.process(event.id) }
        model.started.await()
        ctx.time.advanceTo(now.plusSeconds(2 * 3600))
        model.release.complete(Unit)
        val result = deferred.await()

        assertTrue("result=$result", result is BackgroundProcessingResult.Expired)
        val fresh = ctx.db.eventDao().byId(event.id)
        assertEquals(EventStatuses.EXPIRED, fresh?.status)
        assertEquals(0, ctx.db.actionPlanDao().forEvent(event.id).size)
        assertEquals(0, ctx.db.actionExecutionDao().count())
        assertEquals(0, ctx.db.deliveryHistoryDao().count())
    }

    @Test
    fun retryableModelFailureIsAuditedRetriedThenFiniteTerminal() = runBlocking {
        val event = ctx.insertEvent(
            type = "DEBUG_UNKNOWN_EVENT",
            key = "llm:retry:1",
            payload = buildJsonObject { put("test", true) },
            priority = 50,
        )
        val model = SequenceModel(
            ModelServiceResult.Failure(AppError(ErrorCode.MODEL_FAILURE, technicalDetail = "fail-1")),
            ModelServiceResult.Failure(AppError(ErrorCode.NETWORK_UNAVAILABLE, technicalDetail = "fail-2")),
            ModelServiceResult.Failure(AppError(ErrorCode.MODEL_FAILURE, technicalDetail = "fail-3")),
        )
        val pipeline = ctx.buildPipeline(model)

        val first = pipeline.process(event.id)
        assertTrue(first is BackgroundProcessingResult.RetryScheduled)
        val firstScheduled = first as BackgroundProcessingResult.RetryScheduled
        assertEquals(1, firstScheduled.attemptCount)
        assertEquals("MODEL_FAILURE", ctx.db.eventDao().byId(event.id)?.lastErrorCode)

        ctx.time.advanceTo(Instant.ofEpochMilli(firstScheduled.nextRetryAt + 1))
        val second = pipeline.process(event.id)
        assertTrue(second is BackgroundProcessingResult.RetryScheduled)
        assertEquals(2, (second as BackgroundProcessingResult.RetryScheduled).attemptCount)
        assertEquals("NETWORK_UNAVAILABLE", ctx.db.eventDao().byId(event.id)?.lastErrorCode)

        ctx.time.advanceTo(Instant.ofEpochMilli((second as BackgroundProcessingResult.RetryScheduled).nextRetryAt + 1))
        val third = pipeline.process(event.id)
        assertTrue("third=$third", third is BackgroundProcessingResult.Failed)
        val fresh = ctx.db.eventDao().byId(event.id)
        assertEquals(EventStatuses.FAILED, fresh?.status)
        assertEquals(3, fresh?.attemptCount)
        assertEquals("MODEL_FAILURE", fresh?.lastErrorCode)
        assertTrue(fresh?.nextRetryAt == null)
        assertEquals(0, ctx.db.actionPlanDao().forEvent(event.id).size)
    }

    @Test
    fun chatInputUserMessageNeverEntersBackgroundRecoveryBacklog() = runBlocking {
        val userMessage = EventEntity(
            id = "evt_00000000000000000000000000000001",
            eventKey = "chat:user_message:msg:stale",
            type = "USER_MESSAGE",
            source = "CHAT_INPUT",
            payloadJson = "{}",
            occurredAt = now.toEpochMilli() - 1_000,
            receivedAt = now.toEpochMilli() - 1_000,
            expiresAt = now.toEpochMilli() + 600_000,
            priority = 90,
            status = EventStatuses.PROCESSING,
        )
        ctx.db.eventDao().upsert(userMessage)
        val backgroundEvent = ctx.insertEvent(
            type = "DEBUG_UNKNOWN_EVENT",
            key = "background:after:chat:crash",
            payload = buildJsonObject { put("test", true) },
            priority = 50,
        )

        RecoveryManager(ctx.db).run(now.toEpochMilli())
        assertEquals(EventStatuses.FAILED, ctx.db.eventDao().byId(userMessage.id)?.status)
        assertEquals(EventStatuses.RECEIVED, ctx.db.eventDao().byId(backgroundEvent.id)?.status)

        val model = NoActionRecordingModel()
        val scheduler = ctx.buildScheduler(ctx.buildPipeline(model))
        scheduler.checkAt(now)

        assertTrue(model.processedEventIds.contains(backgroundEvent.id))
        assertTrue(model.processedEventIds.contains(userMessage.id).not())
    }

    @Test
    fun handoffCancelsFailedRetryableReachAndChatClaimSurvivesRestartLease() = runBlocking {
        val eventId = "evt_00000000000000000000000000000002"
        val todoId = "todo_00000000000000000000000000000001"
        val planId = "plan_00000000000000000000000000000001"
        val executionId = "exec_00000000000000000000000000000001"
        val deliveryId = "dlv_00000000000000000000000000000001"
        ctx.db.eventDao().upsert(
            EventEntity(
                id = eventId,
                eventKey = "handoff:failed:retry",
                type = "DEBUG_UNKNOWN_EVENT",
                source = "TEST",
                payloadJson = "{}",
                occurredAt = now.toEpochMilli(),
                receivedAt = now.toEpochMilli(),
                expiresAt = now.toEpochMilli() + 3_600_000,
                priority = 50,
                status = EventStatuses.COMPLETED,
            ),
        )
        ctx.db.todoDao().upsert(
            com.littlekitty.xiaokele.core.database.entity.TodoEntity(
                id = todoId,
                type = "DISCUSS_LATER",
                title = "失败通知后的 Widget 承接",
                sourceEventId = eventId,
                status = TodoStatuses.READY,
                priority = 50,
                createdAt = now.toEpochMilli(),
                updatedAt = now.toEpochMilli(),
            ),
        )
        ctx.db.actionPlanDao().insert(
            ActionPlanEntity(
                id = planId,
                eventId = eventId,
                agentOutputJson = "{}",
                status = ActionPlanStatuses.RUNNING,
                activeEventKey = eventId,
                createdAt = now.toEpochMilli(),
            ),
        )
        ctx.db.actionExecutionDao().upsert(
            ActionExecutionEntity(
                id = executionId,
                actionPlanId = planId,
                actionType = "SEND_NOTIFICATION",
                status = ActionExecutionStatuses.FAILED,
                errorCode = "NOTIFICATION_ADAPTER_FAILURE",
            ),
        )
        ctx.db.deliveryHistoryDao().insert(
            DeliveryHistoryEntity(
                id = deliveryId,
                eventId = eventId,
                todoId = todoId,
                channel = "NOTIFICATION",
                status = DeliveryStatuses.FAILED,
                reservedAt = now.toEpochMilli(),
                attemptCount = 1,
                nextRetryAt = now.toEpochMilli() + 60_000,
                errorCode = "NOTIFICATION_ADAPTER_FAILURE",
                executionId = executionId,
                deliveredAt = 0L,
            ),
        )

        val handoff = IntentHandoffManager(ctx.db, ctx.time)
        val result = handoff.handle(
            InternalHandoffRequest(eventId = eventId, todoId = todoId, source = "WIDGET"),
        )
        assertTrue(result is InternalHandoffResult.Opened)
        assertTrue((result as InternalHandoffResult.Opened).cancelledFailedRetryableReach >= 1)
        assertEquals(DeliveryStatuses.CANCELLED, ctx.db.deliveryHistoryDao().byId(deliveryId)?.status)
        assertEquals(ActionExecutionStatuses.CANCELLED, ctx.db.actionExecutionDao().byId(executionId)?.status)
        assertEquals(TodoStatuses.CLAIMED, ctx.db.todoDao().byId(todoId)?.status)

        ctx.time.advanceTo(now.plusSeconds(16 * 60))
        RecoveryManager(ctx.db).run(ctx.time.now().toEpochMilli())
        assertEquals(TodoStatuses.CLAIMED, ctx.db.todoDao().byId(todoId)?.status)
        assertTrue(ctx.db.deliveryHistoryDao().retryable(ctx.time.now().toEpochMilli(), DeliveryOutbox.MAX_ATTEMPTS).isEmpty())
    }

    @Test
    fun osInvokedReservedRowSurvivesClickRaceAndOutcomeUnknownNeverAutoRetries() = runBlocking {
        val eventId = "evt_00000000000000000000000000000003"
        val todoId = "todo_00000000000000000000000000000002"
        val planId = "plan_00000000000000000000000000000002"
        val executionId = "exec_00000000000000000000000000000002"
        val deliveryId = "dlv_00000000000000000000000000000002"
        ctx.db.eventDao().upsert(
            EventEntity(
                id = eventId,
                eventKey = "handoff:os:race",
                type = "DEBUG_UNKNOWN_EVENT",
                source = "TEST",
                payloadJson = "{}",
                occurredAt = now.toEpochMilli(),
                receivedAt = now.toEpochMilli(),
                status = EventStatuses.COMPLETED,
            ),
        )
        ctx.db.todoDao().upsert(
            com.littlekitty.xiaokele.core.database.entity.TodoEntity(
                id = todoId,
                type = "DISCUSS_LATER",
                status = TodoStatuses.READY,
                createdAt = now.toEpochMilli(),
                updatedAt = now.toEpochMilli(),
            ),
        )
        ctx.db.actionPlanDao().insert(
            ActionPlanEntity(
                id = planId,
                eventId = eventId,
                agentOutputJson = "{}",
                status = ActionPlanStatuses.RUNNING,
                activeEventKey = eventId,
                createdAt = now.toEpochMilli(),
            ),
        )
        ctx.db.actionExecutionDao().upsert(
            ActionExecutionEntity(
                id = executionId,
                actionPlanId = planId,
                actionType = "SEND_NOTIFICATION",
                status = ActionExecutionStatuses.RUNNING,
            ),
        )
        ctx.db.deliveryHistoryDao().insert(
            DeliveryHistoryEntity(
                id = deliveryId,
                eventId = eventId,
                todoId = todoId,
                channel = "NOTIFICATION",
                status = DeliveryStatuses.RESERVED,
                reservedAt = now.toEpochMilli(),
                attemptStartedAt = now.toEpochMilli(),
                attemptCount = 1,
                executionId = executionId,
                deliveredAt = 0L,
            ),
        )

        val result = IntentHandoffManager(ctx.db, ctx.time).handle(
            InternalHandoffRequest(eventId = eventId, todoId = todoId, source = "WIDGET"),
        )
        assertTrue(result is InternalHandoffResult.Opened)
        assertEquals(DeliveryStatuses.RESERVED, ctx.db.deliveryHistoryDao().byId(deliveryId)?.status)

        RecoveryManager(ctx.db).run(ctx.time.now().toEpochMilli())
        assertEquals(DeliveryStatuses.OUTCOME_UNKNOWN, ctx.db.deliveryHistoryDao().byId(deliveryId)?.status)
        assertTrue(ctx.db.deliveryHistoryDao().retryable(ctx.time.now().toEpochMilli() + 3600_000, DeliveryOutbox.MAX_ATTEMPTS).isEmpty())
    }

    @Test
    fun sceneBacklogHonoursBatchLimitAndReportsRemaining() = runBlocking {
        ctx.insertUserMessage(at = now)
        repeat(22) { index ->
            ctx.insertEvent(
                type = "DEBUG_UNKNOWN_EVENT",
                key = "backlog:batch:$index",
                payload = buildJsonObject { put("index", index) },
                priority = 50 - index % 3,
            )
        }
        val model = NoActionRecordingModel()
        val scheduler = ctx.buildScheduler(ctx.buildPipeline(model))
        assertEquals("total events before scan", 22, ctx.db.eventDao().count())
        assertEquals("backlog before scan", 22, ctx.db.eventDao().backgroundBacklogCount(now.toEpochMilli()))
        val expectedBatch = ctx.db.eventDao().backgroundBacklog(now.toEpochMilli(), 20).map { it.id }

        val report = scheduler.checkAt(now)

        assertEquals(20, report.backlogProcessed)
        assertTrue("report=$report received=${ctx.db.eventDao().byStatuses(listOf(EventStatuses.RECEIVED)).size}", report.backlogRemaining >= 2)
        assertEquals(expectedBatch, model.processedEventIds)
    }

    @Test
    fun weatherRefreshDeltaFieldIsTheExactFieldSceneEvaluatorReads() = runBlocking {
        ctx.cacheLocation()
        val model = NoActionRecordingModel()
        val dispatcher = ctx.buildDispatcher(model)
        val weather = WeatherRefresh(
            service = DeltaWeatherService(now),
            externalCacheRepository = com.littlekitty.xiaokele.core.database.repository.ExternalCacheRepository(
                ctx.db,
                ctx.db.externalCacheDao(),
                com.littlekitty.xiaokele.core.database.repository.ExternalCacheEmissionGuard(ctx.db.externalCacheDao()),
            ),
            contextResolver = ctx.contextResolver,
            timeProvider = ctx.time,
            dispatcher = dispatcher,
        )

        val report = weather.refresh(force = true)
        assertEquals(com.littlekitty.xiaokele.external.ExternalSyncOutcomes.SYNCED, report.outcome)
        val event = ctx.db.eventDao().recent(1).single()
        val payload = JsonCodec.json.parseToJsonElement(event.payloadJson) as JsonObject
        assertEquals(-6.0, payload["temperature_delta_celsius"]?.jsonPrimitive?.content?.toDouble() ?: 0.0, 0.001)

        val snapshot = SceneContextSnapshot(
            nowEpochMillis = now.toEpochMilli(),
            locationSnapshot = ctx.contextResolver.resolveLocationSnapshot(now.toEpochMilli()),
            petState = ctx.db.petStateDao().get(),
            lastInteractionAtEpochMillis = now.toEpochMilli(),
        )
        val evaluation = WeatherSceneEvaluator { ctx.thresholds.current() }.evaluate(event, snapshot)
        assertNotNull(evaluation)
        assertEquals(SceneDecision.ALLOW, evaluation?.decision)
        assertEquals("significant_weather_change_candidate", evaluation?.reasonCode)
    }

    @Test
    fun outboxWorkerDelayAndRetryLookupAreFrozenDeterministically() = runBlocking {
        assertEquals("outbox_retry_v1", OutboxRetryWorker.UNIQUE_OUTBOX_RETRY_WORK)
        assertEquals(0L, OutboxRetryWorkScheduler.delayMillis(nowEpochMillis = 100L, nextRetryAt = 100L))
        assertEquals(50L, OutboxRetryWorkScheduler.delayMillis(nowEpochMillis = 100L, nextRetryAt = 150L))
        assertEquals(null, OutboxRetryWorkScheduler.delayMillis(nowEpochMillis = 100L, nextRetryAt = null))

        ctx.db.deliveryHistoryDao().insert(
            DeliveryHistoryEntity(
                id = "dlv_00000000000000000000000000000003",
                eventId = "evt_00000000000000000000000000000004",
                channel = "NOTIFICATION",
                status = DeliveryStatuses.FAILED,
                reservedAt = now.toEpochMilli(),
                attemptCount = 1,
                nextRetryAt = now.toEpochMilli() + 120_000,
                errorCode = "NOTIFICATION_ADAPTER_FAILURE",
                executionId = "exec_00000000000000000000000000000003",
                deliveredAt = 0L,
            ),
        )
        val outbox = DeliveryOutbox(ctx.db, ctx.db.deliveryHistoryDao())
        assertEquals(now.toEpochMilli() + 120_000, outbox.nextRetryAt())
    }

    private class GateNoActionModel : ModelService {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override val serviceName: String = "gate_no_action_model"

        override suspend fun generate(context: ContextPack): ModelServiceResult {
            started.complete(Unit)
            release.await()
            return ModelServiceResult.Success(
                JsonCodec.encode(AgentDecision(decision = DecisionNames.NO_ACTION)),
            )
        }
    }

    private class SequenceModel(
        private vararg val results: ModelServiceResult,
    ) : ModelService {
        private var index = 0
        override val serviceName: String = "sequence_model"

        override suspend fun generate(context: ContextPack): ModelServiceResult {
            val result = results[index.coerceAtMost(results.lastIndex)]
            index++
            return result
        }
    }

    private class DeltaWeatherService(
        private val now: Instant,
    ) : WeatherService {
        override val serviceName: String = "weather_delta_test"
        override val provenance: String = "TEST_DELTA"

        override suspend fun fetch(location: WeatherLocation): WeatherFetchResult =
            WeatherFetchResult.Success(
                WeatherSnapshotDto(
                    locationKey = location.placeType,
                    observedAt = InstantJson.formatUtc(now),
                    condition = "cloudy",
                    temperatureCelsius = 20.0,
                    precipitationMm = 0.0,
                    temperatureDeltaCelsius = -6.0,
                    alerts = emptyList(),
                    expiresAt = InstantJson.formatUtc(now.plusSeconds(3600)),
                ),
            )
    }
}
