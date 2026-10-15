package com.littlekitty.xiaokele.scene

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.AgentOutput
import com.littlekitty.xiaokele.core.common.protocol.DecisionNames
import com.littlekitty.xiaokele.core.common.scene.SceneEventTypes
import com.littlekitty.xiaokele.core.common.scene.ScenePriorities
import com.littlekitty.xiaokele.core.database.state.DeliveryStatuses
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.event.BackgroundProcessingResult
import com.littlekitty.xiaokele.event.EventDispatchResult
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
class SceneCooldownTest {

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
    fun deliveredFloatingBubbleCoolsSameSceneAcrossChannelsForSixHours() = runBlocking {
        val model = RawDecisionModelService(JsonCodec.encode(widgetDecision()))
        val dispatcher = ctx.buildDispatcher(model)
        ctx.insertUserMessage(at = now.minusSeconds(25L * 3600))

        val first = dispatcher.dispatch(interactionGapEvent("scene:cooldown:1"))
        assertTrue(first is EventDispatchResult.Processed)
        assertTrue((first as EventDispatchResult.Processed).processing is BackgroundProcessingResult.PlanExecuted)
        assertEquals(1, ctx.db.deliveryHistoryDao().countDelivered())
        val delivered = ctx.db.deliveryHistoryDao().recent(1).single()
        assertEquals("scene:interaction_gap", delivered.cooldownGroup)
        assertEquals(DeliveryStatuses.DELIVERED, delivered.status)

        val second = dispatcher.dispatch(interactionGapEvent("scene:cooldown:2"))
        assertTrue(second is EventDispatchResult.Processed)
        val processing = (second as EventDispatchResult.Processed).processing
        assertTrue("processing=$processing", processing is BackgroundProcessingResult.NoActionAudited)
        val reason = (processing as BackgroundProcessingResult.NoActionAudited).reason
        assertEquals("scene_cooldown_active", reason)
        assertEquals(1, model.calls)

        ctx.time.advanceTo(now.plusMillis(6L * 3600_000 + 1))
        val third = dispatcher.dispatch(interactionGapEvent("scene:cooldown:3"))
        assertTrue((third as EventDispatchResult.Processed).processing is BackgroundProcessingResult.PlanExecuted)
        assertEquals(2, model.calls)
    }

    private fun interactionGapEvent(key: String): com.littlekitty.xiaokele.core.common.protocol.NormalizedEvent {
        val current = ctx.time.now()
        return com.littlekitty.xiaokele.core.common.protocol.NormalizedEvent(
            eventKey = key,
            type = SceneEventTypes.INTERACTION_GAP,
            source = "TEST_SCENE",
            occurredAt = com.littlekitty.xiaokele.core.common.time.InstantJson.formatUtc(current),
            receivedAt = com.littlekitty.xiaokele.core.common.time.InstantJson.formatUtc(current),
            expiresAt = com.littlekitty.xiaokele.core.common.time.InstantJson.formatUtc(current.plusSeconds(3600)),
            priority = ScenePriorities.INTERACTION_GAP,
            payload = buildJsonObject {
                put("last_interaction_at_epoch_millis", current.toEpochMilli() - 25L * 3600_000)
                put("gap_millis", 25L * 3600_000)
            },
        )
    }

    private fun widgetDecision(): AgentDecision = AgentDecision(
        decision = DecisionNames.CREATE_PLAN,
        decisionSummary = "scene cooldown test",
        outputs = listOf(
            AgentOutput(
                action = AgentActionRegistry.SEND_PROACTIVE_MESSAGE,
                payload = buildJsonObject {
                    put("content", "冷却测试")
                    put("state_id", "WAITING")
                    put("short_text", "test")
                    put("expires_at", com.littlekitty.xiaokele.core.common.time.InstantJson.formatUtc(now.plusSeconds(600)))
                },
            ),
        ),
    )
}
