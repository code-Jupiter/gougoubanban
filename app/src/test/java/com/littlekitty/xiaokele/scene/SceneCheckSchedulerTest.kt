package com.littlekitty.xiaokele.scene

import com.littlekitty.xiaokele.core.common.protocol.DecisionNames
import com.littlekitty.xiaokele.core.common.scene.SceneCandidateThresholds
import com.littlekitty.xiaokele.core.common.scene.SceneEventTypes
import com.littlekitty.xiaokele.core.common.scene.ScenePriorities
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import com.littlekitty.xiaokele.core.database.state.PetStateIds
import com.littlekitty.xiaokele.core.database.entity.PetStateEntity
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
class SceneCheckSchedulerTest {

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
    fun backlogIsProcessedByPriorityDescThenReceivedAtAsc() = runBlocking {
        ctx.insertUserMessage()
        val model = NoActionRecordingModel()
        val pipeline = ctx.buildPipeline(model)
        val scheduler = ctx.buildScheduler(pipeline)
        val lowPriority = ctx.insertEvent(
            type = "DEBUG_UNKNOWN_EVENT",
            key = "backlog:low",
            payload = buildJsonObject { put("order", "low") },
            priority = 30,
            receivedAt = now.minusSeconds(100),
        )
        val highPriority = ctx.insertEvent(
            type = "DEBUG_UNKNOWN_EVENT",
            key = "backlog:high",
            payload = buildJsonObject { put("order", "high") },
            priority = 80,
            receivedAt = now.minusSeconds(10),
        )

        val report = scheduler.checkAt(now)

        assertEquals(2, report.backlogProcessed)
        assertEquals(listOf(highPriority.id, lowPriority.id), model.processedEventIds)
        assertEquals(
            listOf(highPriority.id, lowPriority.id).map { EventStatuses.FILTERED },
            listOf(
                ctx.db.eventDao().byId(highPriority.id)?.status,
                ctx.db.eventDao().byId(lowPriority.id)?.status,
            ),
        )
    }

    @Test
    fun interactionGapCandidateRespectsCandidateThresholdAndPriorityBand() = runBlocking {
        ctx.thresholds.currentThresholds = SceneCandidateThresholds(
            interactionGapRestCandidateMillis = 4L * 3600_000,
        )
        ctx.insertUserMessage(at = now.minusSeconds(3L * 3600))
        val model = NoActionRecordingModel()
        val scheduler = ctx.buildScheduler(ctx.buildPipeline(model))

        val below = scheduler.checkAt(now)
        assertTrue(!below.interactionGapEventDispatched)

        ctx.time.advanceTo(now.plusSeconds(2L * 3600))
        val above = scheduler.checkAt(ctx.time.now())
        assertTrue(above.interactionGapEventDispatched)
        assertEquals(ScenePriorities.INTERACTION_GAP, above.interactionGapPriority)
        assertEquals(1, ctx.db.eventDao().count())
    }

    @Test
    fun interactionGapIsEmittedAtTheFrozenPriorityAndReachesTheModel() = runBlocking {
        ctx.insertUserMessage(at = now.minusSeconds(25L * 3600))
        val model = NoActionRecordingModel()
        val scheduler = ctx.buildScheduler(ctx.buildPipeline(model))

        val report = scheduler.checkAt(now)

        assertTrue(report.interactionGapEventDispatched)
        assertEquals(ScenePriorities.INTERACTION_GAP, report.interactionGapPriority)
        val event = ctx.db.eventDao().recent(1).single()
        assertEquals(EventStatuses.FILTERED, event.status)
        assertEquals(0, ctx.db.deliveryHistoryDao().count())
        // The candidate reaches the model, which still decides NO_ACTION
        // deterministically in this fixture.
        assertEquals(listOf(event.id), model.processedEventIds)
    }

    @Test
    fun expiredPetStateRestoresToSituationalDefault() = runBlocking {
        ctx.db.petStateDao().upsert(
            PetStateEntity(
                singletonId = PetStateEntity.SINGLETON_ID,
                stateId = PetStateIds.CARING,
                startsAt = now.toEpochMilli() - 3_600_000,
                expiresAt = now.toEpochMilli() - 1,
                updatedAt = now.toEpochMilli() - 3_600_000,
            ),
        )
        val scheduler = ctx.buildScheduler(ctx.buildPipeline(NoActionRecordingModel()))

        val report = scheduler.checkAt(now)

        assertEquals(1, report.petStateRestored)
        // Without a shift there is no situational guard state to restore to.
        assertEquals(PetStateIds.IDLE, ctx.db.petStateDao().get()?.stateId)
    }

    @Test
    fun debugAdjustableCheckAtDoesNotChangePersistedFacts() = runBlocking {
        val event = ctx.insertEvent(
            type = "DEBUG_UNKNOWN_EVENT",
            key = "expires:future",
            payload = buildJsonObject { put("x", 1) },
            priority = 50,
            expiresAt = now.plusSeconds(600),
        )
        val scheduler = ctx.buildScheduler(ctx.buildPipeline(NoActionRecordingModel()))
        scheduler.checkAt(now.plusSeconds(1200))
        assertEquals(EventStatuses.EXPIRED, ctx.db.eventDao().byId(event.id)?.status)
    }
}
