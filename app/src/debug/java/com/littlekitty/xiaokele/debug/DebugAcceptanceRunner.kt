package com.littlekitty.xiaokele.debug

enum class DebugAcceptanceMode(val label: String) {
    REAL_DATA_REAL_MODEL("真实数据 / 真实模型（Debug dry-run，不写业务数据）"),
    TEST_INPUT_REAL_MODEL("允许的测试输入 / 真实模型（不写业务数据）"),
}

enum class DebugAcceptanceScenario(
    val label: String,
    val mode: DebugAcceptanceMode,
    val executorScenario: DebugSceneScenario,
) {
    INTERACTION_GAP("久未互动", DebugAcceptanceMode.TEST_INPUT_REAL_MODEL, DebugSceneScenario.INTERACTION_GAP),
    DAILY_WEATHER("普通前台天气（仅兼容）", DebugAcceptanceMode.REAL_DATA_REAL_MODEL, DebugSceneScenario.DAILY_WEATHER),
    WAKE_WEATHER("起床天气", DebugAcceptanceMode.REAL_DATA_REAL_MODEL, DebugSceneScenario.WAKE_WEATHER),
    DISCOVERY_NUDGE("本地生活轻提", DebugAcceptanceMode.REAL_DATA_REAL_MODEL, DebugSceneScenario.DISCOVERY_NUDGE),
    HIGH_WEATHER_ALERT("天气高风险（仅安全回归）", DebugAcceptanceMode.TEST_INPUT_REAL_MODEL, DebugSceneScenario.HIGH_WEATHER_ALERT),
    FRONT_CHAT("普通前台聊天", DebugAcceptanceMode.REAL_DATA_REAL_MODEL, DebugSceneScenario.LIVE_INTERACTION_GAP),
    LIVE_INTERACTION_GAP("真实模型久未互动", DebugAcceptanceMode.REAL_DATA_REAL_MODEL, DebugSceneScenario.LIVE_INTERACTION_GAP),
    INVALID_BACKGROUND_OUTPUT("非法模型输出拦截（安全回归）", DebugAcceptanceMode.TEST_INPUT_REAL_MODEL, DebugSceneScenario.INVALID_BACKGROUND_OUTPUT),
    DIRECT_CHAT("普通 DIRECT 两次调用", DebugAcceptanceMode.TEST_INPUT_REAL_MODEL, DebugSceneScenario.LIVE_INTERACTION_GAP),
    PLAN_HISTORY("历史回忆 PLAN 链路", DebugAcceptanceMode.TEST_INPUT_REAL_MODEL, DebugSceneScenario.INTERACTION_GAP),
    EXPLICIT_ADVICE("明确要办法", DebugAcceptanceMode.TEST_INPUT_REAL_MODEL, DebugSceneScenario.LIVE_INTERACTION_GAP),
    NO_ADVICE_BOUNDARY("不要建议/不要追问", DebugAcceptanceMode.TEST_INPUT_REAL_MODEL, DebugSceneScenario.LIVE_INTERACTION_GAP),
    MEMORY_CORRECTION("纠正记忆", DebugAcceptanceMode.TEST_INPUT_REAL_MODEL, DebugSceneScenario.INTERACTION_GAP),
    CLOSE_CONVERSATION("告别 CLOSE", DebugAcceptanceMode.TEST_INPUT_REAL_MODEL, DebugSceneScenario.LIVE_INTERACTION_GAP),
    NO_ACTION("后台 NO_ACTION", DebugAcceptanceMode.TEST_INPUT_REAL_MODEL, DebugSceneScenario.INTERACTION_GAP),
    DIARY_SUMMARY("跨日 diary 总结", DebugAcceptanceMode.TEST_INPUT_REAL_MODEL, DebugSceneScenario.INTERACTION_GAP),
    CREATIVE_CANDIDATE("中文创作候选", DebugAcceptanceMode.TEST_INPUT_REAL_MODEL, DebugSceneScenario.INTERACTION_GAP),
    MEMORY_SUMMARY("记忆总结 D→C", DebugAcceptanceMode.TEST_INPUT_REAL_MODEL, DebugSceneScenario.INTERACTION_GAP),
    INTERACTION_IDLE("空闲续话", DebugAcceptanceMode.REAL_DATA_REAL_MODEL, DebugSceneScenario.LIVE_INTERACTION_GAP),
    SCENE_GATE_BLOCKED("场景门禁拒绝", DebugAcceptanceMode.REAL_DATA_REAL_MODEL, DebugSceneScenario.INTERACTION_GAP),
    PROACTIVE_BUDGET_EXHAUSTED("每日主动预算耗尽", DebugAcceptanceMode.TEST_INPUT_REAL_MODEL, DebugSceneScenario.INTERACTION_GAP),
    LOCAL_FOOD_SEARCH("本地生活美食搜索", DebugAcceptanceMode.REAL_DATA_REAL_MODEL, DebugSceneScenario.DISCOVERY_NUDGE),
    LOCAL_EVENTS_SEARCH("本地生活活动搜索", DebugAcceptanceMode.REAL_DATA_REAL_MODEL, DebugSceneScenario.DISCOVERY_NUDGE),
    LOCATION_MISSING("定位缺失 fail-closed", DebugAcceptanceMode.REAL_DATA_REAL_MODEL, DebugSceneScenario.DISCOVERY_NUDGE),
}

enum class DebugRunStageStatus {
    PASS,
    WARNING,
    FAIL,
    SKIPPED,
}

data class DebugRunStage(
    val name: String,
    val status: DebugRunStageStatus,
    val detail: String,
)

data class DebugRunReport(
    val runId: String,
    val scenario: DebugAcceptanceScenario,
    val stages: List<DebugRunStage>,
    val durationMillis: Long,
) {
    val isSuccessful: Boolean
        get() = stages.isNotEmpty() && stages.all { it.status == DebugRunStageStatus.PASS }
}
