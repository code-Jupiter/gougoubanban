package com.littlekitty.xiaokele.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebugSceneScenarioTest {

    @Test
    fun chatAliasesMapToConfirmedScenes() {
        assertEquals(DebugSceneScenario.INTERACTION_GAP, DebugSceneScenario.from("/测试 久未互动"))
        assertEquals(DebugSceneScenario.DAILY_WEATHER, DebugSceneScenario.from("/SC-05"))
        assertEquals(DebugSceneScenario.WAKE_WEATHER, DebugSceneScenario.from("起床天气"))
        assertEquals(DebugSceneScenario.DISCOVERY_NUDGE, DebugSceneScenario.from("本地生活"))
        assertEquals(DebugSceneScenario.HIGH_WEATHER_ALERT, DebugSceneScenario.from("天气预警"))
        assertEquals(DebugSceneScenario.LIVE_INTERACTION_GAP, DebugSceneScenario.from("/真实久未互动"))
        assertEquals(DebugSceneScenario.INVALID_BACKGROUND_OUTPUT, DebugSceneScenario.from("/非法后台"))
    }

    @Test
    fun removedShiftAndPlaceScenariosAreNoLongerRecognised() {
        assertEquals(
            setOf(
                DebugSceneScenario.INTERACTION_GAP,
                DebugSceneScenario.DAILY_WEATHER,
                DebugSceneScenario.WAKE_WEATHER,
                DebugSceneScenario.DISCOVERY_NUDGE,
                DebugSceneScenario.HIGH_WEATHER_ALERT,
                DebugSceneScenario.LIVE_INTERACTION_GAP,
                DebugSceneScenario.INVALID_BACKGROUND_OUTPUT,
            ),
            DebugSceneScenario.entries.toSet(),
        )
        listOf("/上班", "场景下班", "到家", "班前", "排班").forEach { alias ->
            assertNull("$alias must not resolve to a scene anymore", DebugSceneScenario.from(alias))
        }
    }

    @Test
    fun ordinaryChatTextIsNotIntercepted() {
        assertNull(DebugSceneScenario.from("今天上班好累"))
        assertNull(DebugSceneScenario.from("你好，测试小狗"))
    }
}
