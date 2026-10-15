package com.littlekitty.xiaokele.scene

import com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames
import com.littlekitty.xiaokele.core.common.scene.SceneEventTypes
import com.littlekitty.xiaokele.core.common.scene.ScenePriorities
import com.littlekitty.xiaokele.core.database.state.EventStatuses
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
class ScenePreconditionGateTest {

    private lateinit var ctx: Phase7TestContext
    private lateinit var gate: ScenePreconditionGate
    private val now = Instant.parse("2026-08-16T10:00:00Z")

    @Before
    fun setUp() {
        ctx = buildPhase7TestContext(now)
        gate = ScenePreconditionGate(ctx.db, ctx.contextResolver, ctx.thresholds, ctx.time)
    }

    @After
    fun tearDown() {
        ctx.close()
    }

    @Test
    fun interactionEvaluatorAllowsADueGapCandidate() = runBlocking {
        val payload = buildJsonObject {
            put("last_interaction_at_epoch_millis", now.toEpochMilli() - 25 * 3600_000L)
            put("gap_millis", 25 * 3600_000L)
        }
        ctx.insertUserMessage(at = now.minusSeconds(25L * 3600))
        val allowance = gate.evaluate(
            ctx.insertEvent(
                type = SceneEventTypes.INTERACTION_GAP,
                key = "local:gap:due:1",
                payload = payload,
                priority = ScenePriorities.INTERACTION_GAP,
            ),
        )
        assertTrue("allowance=$allowance", allowance is SceneGateResult.Allow)
        assertEquals(
            "interaction_gap_candidate",
            (allowance as SceneGateResult.Allow).evaluation.reasonCode,
        )
    }

    @Test
    fun interactionEvaluatorRejectsAnEventBelowTheCandidateThreshold() = runBlocking {
        val payload = buildJsonObject {
            put("last_interaction_at_epoch_millis", now.toEpochMilli() - 60_000L)
            put("gap_millis", 60_000L)
        }
        ctx.insertUserMessage(at = now.minusSeconds(60))
        val result = gate.evaluate(
            ctx.insertEvent(
                type = SceneEventTypes.INTERACTION_GAP,
                key = "local:gap:too_soon:1",
                payload = payload,
                priority = ScenePriorities.INTERACTION_GAP,
            ),
        )
        assertTrue("result=$result", result is SceneGateResult.ForceNoAction)
        assertEquals(
            "interaction_gap_below_candidate_threshold",
            (result as SceneGateResult.ForceNoAction).evaluation.reasonCode,
        )
    }

    @Test
    fun weatherEvaluatorAllowsSignificantChangeAndKeepsHighAlertAsContextOnly() = runBlocking {
        val ordinary = gate.evaluate(
            ctx.insertEvent(
                type = ExternalEventTypeNames.WEATHER_CHANGE,
                key = "weather:change:significant:1",
                payload = buildJsonObject {
                    put("condition", "rain")
                    put("precipitation_mm", 2.0)
                },
                priority = ScenePriorities.WEATHER_CHANGE,
            ),
        )
        assertTrue("ordinary=$ordinary", ordinary is SceneGateResult.Allow)
        assertEquals(
            "significant_weather_change_candidate",
            (ordinary as SceneGateResult.Allow).evaluation.reasonCode,
        )

        val calm = gate.evaluate(
            ctx.insertEvent(
                type = ExternalEventTypeNames.WEATHER_CHANGE,
                key = "weather:change:calm:1",
                payload = buildJsonObject {
                    put("condition", "cloudy")
                    put("precipitation_mm", 0.0)
                },
                priority = ScenePriorities.WEATHER_CHANGE,
            ),
        )
        assertTrue("calm=$calm", calm is SceneGateResult.ForceNoAction)
        assertEquals(
            "ordinary_weather_not_significant",
            (calm as SceneGateResult.ForceNoAction).evaluation.reasonCode,
        )

        val high = gate.evaluate(
            ctx.insertEvent(
                type = ExternalEventTypeNames.WEATHER_ALERT,
                key = "weather:alert:high:1",
                payload = buildJsonObject {
                    put("alert_id", "alert_high")
                    put("severity", "HIGH")
                },
                priority = ScenePriorities.HIGH_WEATHER_ALERT,
            ),
        )
        assertTrue("high=$high", high is SceneGateResult.ForceNoAction)
        assertEquals(
            "high_weather_alert_context_only",
            (high as SceneGateResult.ForceNoAction).evaluation.reasonCode,
        )
    }

    @Test
    fun diningScenesAreDisabledBeforeTheModel() = runBlocking {
        val nearby = gate.evaluate(
            ctx.insertEvent(
                type = ExternalEventTypeNames.NEARBY_CANDIDATE,
                key = "nearby:disabled:1",
                payload = buildJsonObject {
                    put("merchant_id", "m1")
                    put("distance_meters", 300.0)
                },
                priority = ScenePriorities.NEARBY_CANDIDATE,
            ),
        )
        assertTrue(nearby is SceneGateResult.ForceNoAction)
        assertEquals(
            "dining_feature_disabled",
            (nearby as SceneGateResult.ForceNoAction).evaluation.reasonCode,
        )
    }

    @Test
    fun evaluatorCoverageDoesNotCrashForEveryProductEventType() = runBlocking {
        val payloads = mapOf(
            SceneEventTypes.INTERACTION_GAP to buildJsonObject {
                put("last_interaction_at_epoch_millis", 0L)
                put("gap_millis", now.toEpochMilli())
            },
            ExternalEventTypeNames.WEATHER_CHANGE to buildJsonObject { put("condition", "rain") },
            ExternalEventTypeNames.WEATHER_ALERT to buildJsonObject { put("severity", "HIGH") },
            ExternalEventTypeNames.DAILY_WEATHER_BRIEF to buildJsonObject { put("eligible", true) },
            ExternalEventTypeNames.WAKE_WEATHER_BRIEF to buildJsonObject { put("eligible", true) },
            ExternalEventTypeNames.DISCOVERY_TOPIC_NUDGE to buildJsonObject { put("items", "[]") },
            ExternalEventTypeNames.NEARBY_CANDIDATE to buildJsonObject {
                put("merchant_id", "m")
                put("distance_meters", 100.0)
            },
        )
        assertEquals(SceneEventTypes.all, payloads.keys)
        payloads.forEach { (type, payload) ->
            val result = gate.evaluate(
                ctx.insertEvent(
                    type = type,
                    key = "coverage:$type",
                    payload = payload,
                    priority = 50,
                    status = EventStatuses.RECEIVED,
                ),
            )
            assertTrue("$type returned $result", result is SceneGateResult)
        }
    }
}
