package com.littlekitty.xiaokele.automation

import com.littlekitty.xiaokele.animation.AnimationCatalog
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames
import com.littlekitty.xiaokele.core.common.scene.SceneEventTypes
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationRuleModelsTest {

    @Test
    fun defaultCatalogCoversExistingSceneEventsAndTimeExtension() {
        val rules = AutomationRuleCatalog.defaults().rules

        // Product-driven scenes (wake weather, discovery nudge) intentionally
        // have no user automation rule; every other scene event keeps a preset.
        val automationConfigurable = SceneEventTypes.all -
            ExternalEventTypeNames.WAKE_WEATHER_BRIEF -
            ExternalEventTypeNames.DISCOVERY_TOPIC_NUDGE
        assertTrue(rules.map { it.trigger.eventType }.containsAll(automationConfigurable))
        assertTrue(rules.any { it.trigger.eventType == AutomationEventTypes.TRIGGER_FIRE })
        assertTrue(rules.all { AutomationRuleValidator.validate(AutomationRuleDocument(rules = listOf(it))) == null })
    }

    @Test
    fun importedJsonRejectsUnknownActionAndAnimation() {
        val actionResult = AutomationRuleValidator.parse(
            JsonCodec.encode(
                AutomationRuleDocument(
                    rules = listOf(
                        AutomationRule(
                            id = "unsafe",
                            name = "不安全规则",
                            trigger = AutomationTrigger(SceneEventTypes.INTERACTION_GAP),
                            allowedActions = listOf("RUN_SHELL"),
                        ),
                    ),
                ),
            ),
        )
        val animationResult = AutomationRuleValidator.parse(
            JsonCodec.encode(
                AutomationRuleDocument(
                    rules = listOf(
                        AutomationRule(
                            id = "bad-animation",
                            name = "错误动画",
                            trigger = AutomationTrigger(SceneEventTypes.INTERACTION_GAP),
                            animationId = "not-installed",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(actionResult is AutomationRuleImportResult.Failure)
        assertTrue(animationResult is AutomationRuleImportResult.Failure)
    }

    @Test
    fun matcherSupportsPayloadAndLocalTimeConditions() {
        val rule = AutomationRule(
            id = "rain-hours",
            name = "下雨时段提醒",
            trigger = AutomationTrigger(
                eventType = ExternalEventTypeNames.WEATHER_CHANGE,
                conditions = listOf(
                    AutomationCondition("payload.condition", ConditionOperators.EQUALS, JsonPrimitive("rain")),
                    AutomationCondition("local_hour", ConditionOperators.GTE, JsonPrimitive(8)),
                ),
            ),
        )
        val event = AutomationEventView(
            type = ExternalEventTypeNames.WEATHER_CHANGE,
            source = "WEATHER",
            payload = buildJsonObject { put("condition", "rain") },
        )

        assertTrue(AutomationRuleMatcher.matches(rule, event, Instant.parse("2026-08-26T01:30:00Z")))
        assertTrue(!AutomationRuleMatcher.matches(rule, event, Instant.parse("2026-08-25T23:30:00Z")))
        assertEquals(null, AutomationRuleValidator.validate(AutomationRuleDocument(rules = listOf(rule))))
    }

    @Test
    fun timePointUsesScheduledLocalTimeDuringBoundedCatchUp() {
        val rule = AutomationRule(
            id = "nine-am",
            name = "九点提醒",
            trigger = AutomationTrigger(
                eventType = AutomationEventTypes.TIME_POINT,
                conditions = listOf(AutomationCondition("local_time", ConditionOperators.EQUALS, JsonPrimitive("09:00"))),
            ),
        )
        val event = AutomationEventView(
            type = AutomationEventTypes.TIME_POINT,
            source = "AUTOMATION_RULE",
            payload = buildJsonObject {
                put("automation_rule_id", "nine-am")
                put("scheduled_local_time", "09:00")
                put("scheduled_local_hour", 9)
                put("scheduled_local_minute", 0)
            },
        )

        // 09:15 Asia/Shanghai: the bounded catch-up still represents the
        // requested 09:00 rule, rather than comparing against 09:15.
        assertTrue(AutomationRuleMatcher.matches(rule, event, Instant.parse("2026-08-26T01:15:00Z")))
    }

    @Test
    fun timePointRejectsMalformedLocalTime() {
        val rule = AutomationRule(
            id = "bad-time",
            name = "坏时间",
            trigger = AutomationTrigger(
                eventType = AutomationEventTypes.TIME_POINT,
                conditions = listOf(AutomationCondition("local_time", ConditionOperators.EQUALS, JsonPrimitive("9:75"))),
            ),
        )

        assertEquals(
            "rules[0].trigger.conditions[0].value 必须是 HH:mm 格式的本地时间",
            AutomationRuleValidator.validate(AutomationRuleDocument(rules = listOf(rule))),
        )
    }

    @Test
    fun fixedMessageRequiresProactiveMessageAction() {
        val rule = AutomationRule(
            id = "fixed",
            name = "固定消息",
            trigger = AutomationTrigger(
                AutomationEventTypes.TIME_POINT,
                conditions = listOf(AutomationCondition("local_time", ConditionOperators.EQUALS, JsonPrimitive("09:00"))),
            ),
            mode = AutomationRuleModes.FIXED_MESSAGE,
            fixedMessage = "辛苦啦",
            allowedActions = listOf(AgentActionRegistry.PLAY_ANIMATION),
        )

        assertEquals("rules[0].allowed_actions 必须包含 SEND_PROACTIVE_MESSAGE", AutomationRuleValidator.validate(AutomationRuleDocument(rules = listOf(rule))))
        assertTrue(AnimationCatalog.knownIds.contains(AnimationCatalog.ID_WELCOME_BACK))
    }
}
