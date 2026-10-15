package com.littlekitty.xiaokele.scene

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import com.littlekitty.xiaokele.core.common.protocol.AgentDecision
import com.littlekitty.xiaokele.core.common.protocol.AgentOutput
import com.littlekitty.xiaokele.core.common.protocol.DecisionNames
import com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames
import com.littlekitty.xiaokele.core.common.scene.SceneEventTypes
import com.littlekitty.xiaokele.core.common.scene.ScenePriorities
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.PetStateEntity
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.PetStateIds
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
class LocalP0MatrixTest {

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
    fun roomStaysAtLatestVersionWithoutDestructiveMigration() {
        assertEquals(12, AppDatabase.VERSION)
        assertEquals(AppDatabase.VERSION, ctx.db.openHelper.readableDatabase.version)
    }

    @Test
    fun ordinaryGapCannotOverwriteWorkGuardButHighAlertCaringCan() = runBlocking {
        setPet(PetStateIds.WORK_GUARD)
        ctx.insertUserMessage(at = now.minusSeconds(25L * 3600))
        val ordinary = ctx.buildDispatcher(
            RawDecisionModelService(JsonCodec.encode(updatePetDecision(PetStateIds.WAITING))),
        ).dispatch(
            interactionGapEvent("p0:gap:work_guard"),
        )
        assertTrue(ordinary is EventDispatchResult.Processed)
        assertTrue((ordinary as EventDispatchResult.Processed).processing is BackgroundProcessingResult.PlanExecuted)
        assertEquals(PetStateIds.WORK_GUARD, ctx.db.petStateDao().get()?.stateId)

        val highAlert = ctx.buildDispatcher(
            RawDecisionModelService(JsonCodec.encode(updatePetDecision(PetStateIds.CARING))),
        ).dispatch(
            highAlertEvent("p0:alert:work_guard_caring"),
        )
        assertTrue(highAlert is EventDispatchResult.Processed)
        assertTrue((highAlert as EventDispatchResult.Processed).processing is BackgroundProcessingResult.NoActionAudited)
        assertEquals(PetStateIds.WORK_GUARD, ctx.db.petStateDao().get()?.stateId)
    }

    @Test
    fun multiPrimaryChannelPlanIsRejectedDeterministically() = runBlocking {
        ctx.insertUserMessage(at = now.minusSeconds(25L * 3600))
        val decision = AgentDecision(
            decision = DecisionNames.CREATE_PLAN,
            outputs = listOf(
                AgentOutput(
                    action = AgentActionRegistry.SET_WIDGET_STATE,
                    payload = buildJsonObject { put("state_id", "WAITING") },
                ),
                AgentOutput(
                    action = AgentActionRegistry.SEND_PROACTIVE_MESSAGE,
                    payload = buildJsonObject { put("content", "重复主渠道") },
                ),
            ),
        )
        val result = ctx.buildDispatcher(RawDecisionModelService(JsonCodec.encode(decision))).dispatch(
            interactionGapEvent("p0:multi_primary"),
        )
        assertTrue(result is EventDispatchResult.Processed)
        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue(processing is BackgroundProcessingResult.Failed)
        assertEquals("MODEL_OUTPUT_INVALID", (processing as BackgroundProcessingResult.Failed).errorCode)
        assertEquals(0, ctx.db.deliveryHistoryDao().count())
    }

    @Test
    fun significantWeatherAndInteractionGapReachThePipeline() = runBlocking {
        ctx.insertUserMessage(at = now.minusSeconds(25L * 3600))
        val weather = ctx.buildDispatcher(
            RawDecisionModelService(JsonCodec.encode(updatePetDecision(PetStateIds.CARING))),
        ).dispatch(
            sceneEvent(
                ExternalEventTypeNames.WEATHER_CHANGE,
                "p0:significant_weather",
                ScenePriorities.WEATHER_CHANGE,
                buildJsonObject { put("condition", "rain"); put("precipitation_mm", 2.0) },
            ),
        )
        assertTrue(weather is EventDispatchResult.Processed)
        assertTrue((weather as EventDispatchResult.Processed).processing is BackgroundProcessingResult.PlanExecuted)
        assertEquals(1, ctx.db.deliveryHistoryDao().count())

        val gap = ctx.buildDispatcher(
            RawDecisionModelService(
                JsonCodec.encode(
                    AgentDecision(
                        decision = DecisionNames.CREATE_PLAN,
                        outputs = listOf(
                            AgentOutput(
                                action = AgentActionRegistry.SEND_PROACTIVE_MESSAGE,
                                payload = buildJsonObject { put("content", "班中也可以自然承接的低压力话头") },
                            ),
                        ),
                    ),
                ),
            ),
        ).dispatch(
            interactionGapEvent("p0:gap_after_weather"),
        )
        assertTrue(gap is EventDispatchResult.Processed)
        assertTrue((gap as EventDispatchResult.Processed).processing is BackgroundProcessingResult.PlanExecuted)
        assertEquals(2, ctx.db.deliveryHistoryDao().count())
    }

    @Test
    fun unknownEventStillGetsStructuredNoActionAuditWithoutProviderCopy() = runBlocking {
        val result = ctx.buildDispatcher(RawDecisionModelService(JsonCodec.encode(noActionDecision()))).dispatch(
            sceneEvent(
                "DEBUG_UNKNOWN_EVENT",
                "p0:unknown",
                50,
                buildJsonObject { put("provider_unknown", true) },
            ),
        )
        assertTrue(result is EventDispatchResult.Processed)
        val processing = (result as EventDispatchResult.Processed).processing
        assertTrue(processing is BackgroundProcessingResult.NoActionAudited)
        assertEquals(EventStatuses.FILTERED, ctx.db.eventDao().byId((processing as BackgroundProcessingResult.NoActionAudited).eventId)?.status)
        assertEquals(0, ctx.db.messageDao().count())
        assertEquals(0, ctx.db.deliveryHistoryDao().count())
    }

    private suspend fun setPet(state: String) {
        ctx.db.petStateDao().upsert(
            PetStateEntity(
                singletonId = PetStateEntity.SINGLETON_ID,
                stateId = state,
                startsAt = now.toEpochMilli(),
                updatedAt = now.toEpochMilli(),
            ),
        )
    }

    private fun updatePetDecision(state: String): AgentDecision = AgentDecision(
        decision = DecisionNames.CREATE_PLAN,
        outputs = listOf(
            AgentOutput(
                action = AgentActionRegistry.UPDATE_PET_STATE,
                payload = buildJsonObject {
                    put("state_id", state)
                    put("animation_id", "default_idle")
                    put("expires_at", com.littlekitty.xiaokele.core.common.time.InstantJson.formatUtc(ctx.time.now().plusSeconds(600)))
                },
            ),
        ),
    )

    private fun noActionDecision(): AgentDecision = AgentDecision(decision = DecisionNames.NO_ACTION)

    private fun interactionGapEvent(key: String) = sceneEvent(
        SceneEventTypes.INTERACTION_GAP,
        key,
        ScenePriorities.INTERACTION_GAP,
        buildJsonObject {
            put("last_interaction_at_epoch_millis", ctx.time.now().toEpochMilli() - 6L * 3600_000)
            put("gap_millis", 6L * 3600_000)
        },
    )

    private fun highAlertEvent(key: String) = sceneEvent(
        ExternalEventTypeNames.WEATHER_ALERT,
        key,
        ScenePriorities.HIGH_WEATHER_ALERT,
        buildJsonObject { put("severity", "HIGH"); put("alert_id", key) },
    )

    private fun sceneEvent(
        type: String,
        key: String,
        priority: Int,
        payload: kotlinx.serialization.json.JsonObject,
    ) = com.littlekitty.xiaokele.core.common.protocol.NormalizedEvent(
        eventKey = key,
        type = type,
        source = "TEST_SCENE",
        occurredAt = com.littlekitty.xiaokele.core.common.time.InstantJson.formatUtc(ctx.time.now()),
        receivedAt = com.littlekitty.xiaokele.core.common.time.InstantJson.formatUtc(ctx.time.now()),
        expiresAt = com.littlekitty.xiaokele.core.common.time.InstantJson.formatUtc(ctx.time.now().plusSeconds(3600)),
        priority = priority,
        payload = payload,
    )
}
