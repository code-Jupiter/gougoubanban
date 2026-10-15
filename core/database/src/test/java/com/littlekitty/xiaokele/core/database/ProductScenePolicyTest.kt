package com.littlekitty.xiaokele.core.database.policy

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames
import com.littlekitty.xiaokele.core.common.scene.SceneCooldown
import com.littlekitty.xiaokele.core.common.scene.SceneEventTypes
import com.littlekitty.xiaokele.core.common.scene.ScenePolicyContract
import com.littlekitty.xiaokele.core.common.scene.ScenePriorities
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import com.littlekitty.xiaokele.core.database.state.EventStatuses
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProductScenePolicyTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ProductScenePolicyCatalog.ensureSeeded(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun catalogFreezesProductPoliciesWithFloatingPrimaryChannel() = runBlocking {
        val policies = ProductScenePolicyCatalog.productPolicies()
        assertEquals(7, policies.size)
        policies.filter {
            it.id !in setOf(
                ProductScenePolicyCatalog.POLICY_ID_WEATHER_ALERT_HIGH,
                ProductScenePolicyCatalog.POLICY_ID_NEARBY,
            )
        }.forEach { policy ->
            val constraints = com.littlekitty.xiaokele.core.common.json.JsonCodec.json
                .parseToJsonElement(policy.constraintsJson ?: "{}")
                .toString()
            assertFalse(
                "policy=${policy.id} must not allow notification",
                policy.allowedActionsJson.orEmpty().contains(AgentActionRegistry.SEND_NOTIFICATION),
            )
            assertTrue(
                "policy=${policy.id} must allow floating proactive delivery",
                policy.allowedActionsJson.orEmpty().contains(AgentActionRegistry.SEND_PROACTIVE_MESSAGE),
            )
            assertTrue(
                "policy=${policy.id} must freeze primary_channel",
                constraints.contains(ScenePolicyContract.KEY_PRIMARY_CHANNEL),
            )
            assertTrue(
                "policy=${policy.id} must freeze scene cooldown group",
                constraints.contains(ScenePolicyContract.KEY_SCENE_COOLDOWN_GROUP),
            )
        }
        listOf(
            ProductScenePolicyCatalog.POLICY_ID_NEARBY,
        ).forEach { policyId ->
            val policy = policies.single { it.id == policyId }
            assertEquals("[]", policy.allowedActionsJson)
            assertTrue(policy.constraintsJson.orEmpty().contains("\"no_action\":true"))
        }
        assertTrue(policies.all { it.examplesJson.orEmpty() != "[]" })
    }

    @Test
    fun noShiftOrLocationTransitionPolicyRowExists() = runBlocking {
        val rows = db.scenarioPolicyDao().all()
        assertEquals(ProductScenePolicyCatalog.productPolicies().size, rows.size)
        rows.forEach { row ->
            assertFalse(
                "removed shift policy row still seeded: ${row.id}/${row.eventType}",
                row.id.contains("shift", ignoreCase = true) ||
                    row.eventType.contains("SHIFT") ||
                    row.eventType.contains("LOCATION_TRANSITION"),
            )
        }
        assertTrue(
            "removed shift/location event types must not be scene event types",
            SceneEventTypes.all.none { it.contains("SHIFT") || it.contains("LOCATION_TRANSITION") },
        )
        assertEquals(null, SceneCooldown.forEventType("SHIFT_UPCOMING"))
        assertEquals(null, SceneCooldown.forEventType("LOCATION_TRANSITION"))
    }

    @Test
    fun priorityBandsAreExactlyFrozen() {
        assertEquals(
            setOf(90, 80, 55, 50, 40),
            ScenePriorities.all,
        )
        assertEquals(90, ScenePriorities.HIGH_WEATHER_ALERT)
        assertEquals(80, ScenePriorities.INTERACTION_GAP)
        assertEquals(55, ScenePriorities.WEATHER_CHANGE)
        assertEquals(50, ScenePriorities.LOCAL_CANDIDATE_DEFAULT)
        assertEquals(40, ScenePriorities.NEARBY_CANDIDATE)
    }

    @Test
    fun highAlertMatcherOnlyMatchesHighSeverityPayload() = runBlocking {
        val resolver = PolicyResolver(db)
        val high = event(
            type = ExternalEventTypeNames.WEATHER_ALERT,
            payload = buildJsonObject { put("severity", "HIGH") },
        )
        val low = event(
            type = ExternalEventTypeNames.WEATHER_ALERT,
            payload = buildJsonObject { put("severity", "MEDIUM") },
        )
        val highResolution = resolver.resolve(high)
        val lowResolution = resolver.resolve(low)
        assertTrue(highResolution is PolicyResolution.Matched)
        assertEquals(
            ProductScenePolicyCatalog.POLICY_ID_WEATHER_ALERT_HIGH,
            (highResolution as PolicyResolution.Matched).policy.id,
        )
        assertTrue(lowResolution is PolicyResolution.OpenFallback)
        assertEquals(
            "no_matching_scene_policy_matcher",
            (lowResolution as PolicyResolution.OpenFallback).reason,
        )
    }

    @Test
    fun matcherSupportsExactMembershipAndMalformedFailsClosed() {
        val event = event(
            type = "TEST",
            payload = buildJsonObject {
                put("severity", "HIGH")
                put("condition", "rain")
            },
        )
        assertTrue(
            ScenePolicyMatcher.matches(
                event,
                """{"type":"TEST","payload":{"severity":{"in":["HIGH","MEDIUM"]},"condition":"rain"}}""",
            ),
        )
        assertTrue(
            ScenePolicyMatcher.matches(
                event,
                """{"type":"TEST","payload_present":["condition"]}""",
            ),
        )
        assertFalse(
            ScenePolicyMatcher.matches(
                event,
                """{"type":"TEST","payload":{"severity":"LOW"}}""",
            ),
        )
        assertFalse(ScenePolicyMatcher.matches(event, "{not json"))
    }

    @Test
    fun sceneCooldownGroupsCoverAllProductEventTypes() {
        SceneEventTypes.all.forEach { type ->
            assertTrue("$type missing scene cooldown group", SceneCooldown.forEventType(type) != null)
        }
    }

    private fun event(type: String, payload: kotlinx.serialization.json.JsonObject): EventEntity = EventEntity(
        id = com.littlekitty.xiaokele.core.common.id.EntityId.generate("evt").value,
        eventKey = "test:${type}:${System.nanoTime()}",
        type = type,
        source = "TEST",
        payloadJson = payload.toString(),
        occurredAt = 1L,
        receivedAt = 1L,
        expiresAt = null,
        priority = 50,
        status = EventStatuses.RECEIVED,
    )
}
