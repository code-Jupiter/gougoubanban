package com.littlekitty.xiaokele.scene

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.AgentOutput
import com.littlekitty.xiaokele.core.common.protocol.DecisionNames
import com.littlekitty.xiaokele.core.common.protocol.MutationOperationNames
import com.littlekitty.xiaokele.core.common.protocol.NormalizedEvent
import com.littlekitty.xiaokele.core.common.protocol.TodoMutation
import com.littlekitty.xiaokele.core.common.protocol.TodoTypeNames
import com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames
import com.littlekitty.xiaokele.core.common.scene.SceneEventTypes
import com.littlekitty.xiaokele.core.common.scene.ScenePriorities
import com.littlekitty.xiaokele.core.common.time.InstantJson
import com.littlekitty.xiaokele.core.database.delivery.DeliveryOutbox
import com.littlekitty.xiaokele.core.database.entity.DeliveryHistoryEntity
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.PetStateIds
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import com.littlekitty.xiaokele.event.BackgroundProcessingResult
import com.littlekitty.xiaokele.event.EventDispatchResult
import com.littlekitty.xiaokele.external.ExternalEventContract
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class Phase7SceneE2ETest {

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
    fun interactionGapSceneEndToEndUsesFloatingBubbleAndSceneTodo() = runBlocking {
        ctx.insertUserMessage(at = now.minusSeconds(25L * 3600))
        val dispatcher = ctx.buildDispatcher(RawDecisionModelService(JsonCodec.encode(widgetAndTodoDecision("WAITING"))))
        val result = dispatcher.dispatch(sceneEvent(SceneEventTypes.INTERACTION_GAP, "e2e:gap:1", ScenePriorities.INTERACTION_GAP, gapPayload()))

        val planResult = assertPlanExecuted(result)
        assertEquals(EventStatuses.COMPLETED, ctx.db.eventDao().byId(planResult.eventId)?.status)
        assertEquals(1, ctx.db.deliveryHistoryDao().countDelivered())
        assertEquals(1, ctx.db.todoDao().count())
        val todo = ctx.db.todoDao().byId(planResult.mutationReport.touchedTodoIds.single())
        assertEquals(TodoStatuses.READY, todo?.status)
        assertTrue(todo?.payloadJson?.contains("handoff_text") == true)
    }

    @Test
    fun highWeatherAlertIsContextOnlyAndDoesNotForceDelivery() = runBlocking {
        fillOrdinaryNotificationQuota()
        val dispatcher = ctx.buildDispatcher(RawDecisionModelService(JsonCodec.encode(highAlertDecision())))
        val event = sceneEvent(
            ExternalEventTypeNames.WEATHER_ALERT,
            "e2e:alert:high:1",
            ScenePriorities.HIGH_WEATHER_ALERT,
            buildJsonObject {
                put("alert_id", "alert_high_e2e")
                put("severity", "HIGH")
                put("title", "暴雨")
            },
        )
        val result = dispatcher.dispatch(event)

        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue(processing is BackgroundProcessingResult.NoActionAudited)
        assertEquals(0, ctx.notification.sent.size)
        assertEquals(null, ctx.db.petStateDao().get()?.stateId)
        assertEquals(0, ctx.db.todoDao().count())
    }

    @Test
    fun nearbySceneIsFilteredWithoutCallingTheModel() = runBlocking {
        ctx.cacheLocation()
        val dispatcher = ctx.buildDispatcher(RawDecisionModelService(JsonCodec.encode(widgetAndTodoDecision("EXPECTING"))))
        val result = dispatcher.dispatch(
            sceneEvent(
                ExternalEventTypeNames.NEARBY_CANDIDATE,
                "e2e:nearby:1",
                ScenePriorities.NEARBY_CANDIDATE,
                buildJsonObject {
                    put("merchant_id", "noodle_1")
                    put("name", "小面")
                    put("distance_meters", 300.0)
                },
            ),
        )

        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue(processing is BackgroundProcessingResult.NoActionAudited)
        assertEquals("dining_feature_disabled", (processing as BackgroundProcessingResult.NoActionAudited).reason)
        assertEquals(0, ctx.db.todoDao().count())
        assertEquals(0, ctx.notification.sent.size)
        assertEquals(0, ctx.db.messageDao().count())
    }

    private suspend fun assertPlanExecuted(result: EventDispatchResult): BackgroundProcessingResult.PlanExecuted {
        assertTrue("result=$result", result is EventDispatchResult.Processed)
        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue("processing=$processing", processing is BackgroundProcessingResult.PlanExecuted)
        return processing as BackgroundProcessingResult.PlanExecuted
    }

    private fun sceneEvent(
        type: String,
        key: String,
        priority: Int,
        payload: kotlinx.serialization.json.JsonObject,
    ): NormalizedEvent = NormalizedEvent(
        eventKey = key,
        type = type,
        source = "TEST_SCENE",
        occurredAt = InstantJson.formatUtc(ctx.time.now()),
        receivedAt = InstantJson.formatUtc(ctx.time.now()),
        expiresAt = InstantJson.formatUtc(ctx.time.now().plusSeconds(3600)),
        priority = priority,
        payload = payload,
    )

    private fun gapPayload() = buildJsonObject {
        put("last_interaction_at_epoch_millis", ctx.time.now().toEpochMilli() - 25L * 3600_000)
        put("gap_millis", 25L * 3600_000)
    }

    private fun locationPayload(from: String, to: String) = buildJsonObject {
        put("from_place", from)
        put("to_place", to)
        put("place_type", to)
    }

    private fun widgetAndTodoDecision(stateId: String): AgentDecision {
        val todoId = com.littlekitty.xiaokele.core.common.id.EntityId.generate("todo").value
        return AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(
                    action = AgentActionRegistry.SEND_PROACTIVE_MESSAGE,
                    payload = buildJsonObject {
                        put("content", "场景测试：${shortTextFor(stateId)}")
                        put("todo_id", todoId)
                        put("state_id", stateId)
                        put("animation_id", "default_idle")
                        put("short_text", "场景测试")
                        put("expires_at", InstantJson.formatUtc(ctx.time.now().plusSeconds(600)))
                        put("click_target", buildJsonObject { put("todo_id", todoId) })
                    },
                ),
            ),
            todoMutations = listOf(
                TodoMutation(
                    operation = MutationOperationNames.CREATE,
                    todo = buildJsonObject {
                        put("id", todoId)
                        put("type", TodoTypeNames.DISCUSS_LATER)
                        put("title", "场景承接")
                        put("priority", 50)
                        put("expires_at", InstantJson.formatUtc(ctx.time.now().plusSeconds(600)))
                        put("payload", buildJsonObject { put("handoff_text", "这是场景承接内容。") })
                    },
                ),
            ),
        )
    }

    private fun widgetDecision(stateId: String): AgentDecision = AgentDecision(
        decision = DecisionNames.CREATE_PLAN,
        outputs = listOf(
            AgentOutput(
                action = AgentActionRegistry.SEND_PROACTIVE_MESSAGE,
                payload = buildJsonObject {
                    put("content", "场景测试：${shortTextFor(stateId)}")
                    put("state_id", stateId)
                    put("animation_id", "default_idle")
                    put("short_text", "场景测试")
                    put("expires_at", InstantJson.formatUtc(ctx.time.now().plusSeconds(600)))
                },
            ),
        ),
    )

    private fun shortTextFor(stateId: String): String = when (stateId) {
        "WELCOME_BACK" -> "到家承接"
        "EXPECTING" -> "班前陪伴"
        else -> "悬浮窗承接"
    }

    private fun highAlertDecision(): AgentDecision = AgentDecision(
        decision = DecisionNames.CREATE_PLAN,
        outputs = listOf(
            AgentOutput(
                action = AgentActionRegistry.SEND_NOTIFICATION,
                payload = buildJsonObject {
                    put("title", "测试小狗天气提醒")
                    put("body", "短时强降雨，出门注意。")
                },
            ),
            AgentOutput(
                action = AgentActionRegistry.UPDATE_PET_STATE,
                payload = buildJsonObject {
                    put("state_id", PetStateIds.CARING)
                    put("animation_id", "default_idle")
                    put("short_text", "短时强降雨")
                    put("expires_at", InstantJson.formatUtc(ctx.time.now().plusSeconds(600)))
                },
            ),
        ),
        todoMutations = listOf(
            TodoMutation(
                operation = MutationOperationNames.CREATE,
                todo = buildJsonObject {
                    put("id", com.littlekitty.xiaokele.core.common.id.EntityId.generate("todo").value)
                    put("type", TodoTypeNames.DISCUSS_LATER)
                    put("title", "天气承接")
                    put("priority", 90)
                    put("expires_at", InstantJson.formatUtc(ctx.time.now().plusSeconds(600)))
                    put("payload", buildJsonObject { put("handoff_text", "天气预警承接。") })
                },
            ),
        ),
    )

    private suspend fun fillOrdinaryNotificationQuota() {
        repeat(3) { index ->
            ctx.db.deliveryHistoryDao().insert(
                DeliveryHistoryEntity(
                    id = com.littlekitty.xiaokele.core.common.id.EntityId.generate("dlv").value,
                    eventId = "evt_quota_${index}_000000000000000001",
                    todoId = null,
                    channel = DeliveryOutbox.CHANNEL_NOTIFICATION,
                    contentHash = DeliveryOutbox.sha256("quota-$index"),
                    status = com.littlekitty.xiaokele.core.database.state.DeliveryStatuses.DELIVERED,
                    deliveredAt = ctx.time.now().toEpochMilli() - 60_000,
                    executionId = "exec_quota_${index}_00000000000000001",
                    cooldownGroup = "ordinary:$index",
                ),
            )
        }
    }
}
