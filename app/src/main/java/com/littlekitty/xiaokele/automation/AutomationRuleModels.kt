package com.littlekitty.xiaokele.automation

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.littlekitty.xiaokele.animation.AnimationCatalog
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.AgentActionRegistry
import com.littlekitty.xiaokele.core.common.scene.ExternalEventTypeNames
import com.littlekitty.xiaokele.core.common.scene.SceneEventTypes
import com.littlekitty.xiaokele.core.database.policy.PolicyGateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AutomationEventTypes {
    const val TIME_POINT = "TIME_POINT"
    const val TRIGGER_FIRE = "TRIGGER_FIRE"

    /**
     * Product-driven scenes (wake weather brief, discovery topic nudge) are
     * deliberately excluded: no user automation rule is required for them to
     * run, and a missing stored rule would otherwise hard-block the event.
     */
    val supported = SceneEventTypes.all -
        ExternalEventTypeNames.WAKE_WEATHER_BRIEF -
        ExternalEventTypeNames.DISCOVERY_TOPIC_NUDGE +
        TIME_POINT + TRIGGER_FIRE
}

object AutomationToolNames {
    const val QUERY_WEATHER = "query_weather"

    val all = setOf(QUERY_WEATHER)
}

@Serializable
data class AutomationRuleDocument(
    @SerialName("schema_version")
    val schemaVersion: String = "1.0",
    val rules: List<AutomationRule> = emptyList(),
)

@Serializable
data class AutomationRule(
    val id: String,
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val priority: Int = 50,
    val trigger: AutomationTrigger,
    val mode: String = AutomationRuleModes.MODEL_GUIDED,
    @SerialName("model_instruction")
    val modelInstruction: String = "",
    @SerialName("fixed_message")
    val fixedMessage: String? = null,
    @SerialName("allowed_tools")
    val allowedTools: List<String> = AutomationToolNames.all.toList().sorted(),
    @SerialName("allowed_actions")
    val allowedActions: List<String> = listOf(
        AgentActionRegistry.SEND_PROACTIVE_MESSAGE,
        AgentActionRegistry.PLAY_ANIMATION,
        AgentActionRegistry.UPDATE_PET_STATE,
    ),
    @SerialName("animation_id")
    val animationId: String? = null,
    @SerialName("cooldown_minutes")
    val cooldownMinutes: Long = 360L,
)

@Serializable
data class AutomationTrigger(
    @SerialName("event_type")
    val eventType: String,
    val conditions: List<AutomationCondition> = emptyList(),
)

@Serializable
data class AutomationCondition(
    val field: String,
    val operator: String,
    val value: JsonElement = JsonPrimitive(""),
)

object AutomationRuleModes {
    const val MODEL_GUIDED = "MODEL_GUIDED"
    const val FIXED_MESSAGE = "FIXED_MESSAGE"
    val all = setOf(MODEL_GUIDED, FIXED_MESSAGE)
}

sealed interface AutomationRuleImportResult {
    data class Success(val document: AutomationRuleDocument) : AutomationRuleImportResult
    data class Failure(val message: String) : AutomationRuleImportResult
}

object AutomationRuleValidator {
    fun parse(raw: String): AutomationRuleImportResult {
        val document = runCatching { JsonCodec.decode<AutomationRuleDocument>(raw) }
            .getOrElse { return AutomationRuleImportResult.Failure("JSON 格式无效：${it.message.orEmpty().take(120)}") }
        val error = validate(document)
        return if (error == null) AutomationRuleImportResult.Success(document)
        else AutomationRuleImportResult.Failure(error)
    }

    fun validate(document: AutomationRuleDocument): String? {
        if (document.schemaVersion != "1.0") return "不支持的规则 schema_version：${document.schemaVersion}"
        if (document.rules.size > MAX_RULES) return "规则数量不能超过 $MAX_RULES 条"
        val ids = mutableSetOf<String>()
        document.rules.forEachIndexed { index, rule ->
            val prefix = "rules[$index]"
            if (rule.id.matches(ID_PATTERN).not()) return "$prefix.id 只能包含字母、数字、点、下划线和短横线"
            if (!ids.add(rule.id)) return "$prefix.id 重复：${rule.id}"
            if (rule.name.isBlank() || rule.name.length > 80) return "$prefix.name 不能为空且不能超过 80 字符"
            if (rule.description.length > 240) return "$prefix.description 不能超过 240 字符"
            if (rule.priority !in 0..100) return "$prefix.priority 必须在 0 到 100 之间"
            if (rule.trigger.eventType !in AutomationEventTypes.supported) {
                return "$prefix.trigger.event_type 不支持：${rule.trigger.eventType}"
            }
            val hasExactLocalTime = rule.trigger.conditions.any {
                it.field == "local_time" && it.operator == ConditionOperators.EQUALS
            }
            val hasExactHourAndMinute = rule.trigger.conditions.any {
                it.field == "local_hour" && it.operator == ConditionOperators.EQUALS
            } && rule.trigger.conditions.any {
                it.field == "local_minute" && it.operator == ConditionOperators.EQUALS
            }
            if (rule.trigger.eventType == AutomationEventTypes.TIME_POINT && !hasExactLocalTime && !hasExactHourAndMinute) {
                return "$prefix.trigger.conditions 必须包含 local_time，或同时包含 local_hour 和 local_minute 的 equals 条件"
            }
            if (rule.trigger.eventType == AutomationEventTypes.TIME_POINT) {
                rule.trigger.conditions.forEachIndexed { conditionIndex, condition ->
                    if (condition.field == "local_time" && condition.operator == ConditionOperators.EQUALS) {
                        val localTime = (condition.value as? JsonPrimitive)?.contentOrNull
                        if (localTime == null || !LOCAL_TIME_PATTERN.matches(localTime)) {
                            return "$prefix.trigger.conditions[$conditionIndex].value 必须是 HH:mm 格式的本地时间"
                        }
                    }
                }
            }
            if (rule.mode !in AutomationRuleModes.all) return "$prefix.mode 不支持：${rule.mode}"
            if (rule.mode == AutomationRuleModes.MODEL_GUIDED && rule.modelInstruction.length > 2_000) {
                return "$prefix.model_instruction 不能超过 2000 字符"
            }
            if (rule.mode == AutomationRuleModes.FIXED_MESSAGE && rule.fixedMessage.isNullOrBlank()) {
                return "$prefix.fixed_message 在 FIXED_MESSAGE 模式下不能为空"
            }
            if (rule.mode == AutomationRuleModes.FIXED_MESSAGE &&
                AgentActionRegistry.SEND_PROACTIVE_MESSAGE !in rule.allowedActions
            ) {
                return "$prefix.allowed_actions 必须包含 SEND_PROACTIVE_MESSAGE"
            }
            if (rule.fixedMessage.orEmpty().length > 500) return "$prefix.fixed_message 不能超过 500 字符"
            if (rule.allowedTools.any { it !in AutomationToolNames.all }) {
                return "$prefix.allowed_tools 包含未开放的工具"
            }
            if (rule.allowedActions.any { it !in AgentActionRegistry.backgroundAllowed }) {
                return "$prefix.allowed_actions 包含不允许的后台动作"
            }
            if (rule.enabled && rule.allowedActions.isEmpty() && rule.mode == AutomationRuleModes.MODEL_GUIDED) {
                return "$prefix.allowed_actions 不能为空"
            }
            if (rule.animationId != null && rule.animationId !in AnimationCatalog.knownIds) {
                return "$prefix.animation_id 不是当前已安装的动画：${rule.animationId}"
            }
            if (rule.animationId != null && AgentActionRegistry.PLAY_ANIMATION !in rule.allowedActions) {
                return "$prefix.animation_id 已配置，但 allowed_actions 未包含 PLAY_ANIMATION"
            }
            if (rule.cooldownMinutes !in 0L..7L * 24L * 60L) {
                return "$prefix.cooldown_minutes 必须在 0 到 10080 分钟之间"
            }
            rule.trigger.conditions.forEachIndexed { conditionIndex, condition ->
                val conditionPrefix = "$prefix.trigger.conditions[$conditionIndex]"
                if (condition.field.isBlank() || condition.field.length > 120) return "$conditionPrefix.field 无效"
                if (condition.operator !in ConditionOperators.all) return "$conditionPrefix.operator 不支持：${condition.operator}"
                if (condition.value is JsonObject) return "$conditionPrefix.value 不能是对象"
            }
        }
        return null
    }

    private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
    private val LOCAL_TIME_PATTERN = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
    private const val MAX_RULES = 100
}

object ConditionOperators {
    const val EQUALS = "equals"
    const val NOT_EQUALS = "not_equals"
    const val IN = "in"
    const val CONTAINS = "contains"
    const val GTE = "gte"
    const val LTE = "lte"
    const val EXISTS = "exists"
    val all = setOf(EQUALS, NOT_EQUALS, IN, CONTAINS, GTE, LTE, EXISTS)
}

object AutomationRuleCatalog {
    fun defaults(): AutomationRuleDocument = AutomationRuleDocument(
        rules = listOf(
            rule("interaction-gap", "久未互动召回", SceneEventTypes.INTERACTION_GAP, "如果最近互动间隔达到本地按主动联系倾向计算出的候选阈值，结合最近对话和近期已发送内容生成一条低压力、容易回复且不重复的新话题；不要机械提醒用户多久没回复，也不要自动附加吃饭、喝水、睡觉或上班提醒。可以推测用户当前处境或下一步，但必须标明是推测。已有双方自愿的成年人亲密关系时，可以主动加入更亲近、暧昧的切入。"),
            rule("weather-change", "天气变化关心", ExternalEventTypeNames.WEATHER_CHANGE, "只有天气变化确实会影响用户时才主动关心，结合可靠天气数据给出简短可执行建议。"),
            rule("daily-weather", "日常天气关心", ExternalEventTypeNames.DAILY_WEATHER_BRIEF, "只在天气信息可靠时给出带伞、穿衣或空气质量等可执行建议，不罗列完整预报。"),
            rule("weather-alert", "天气预警事实", ExternalEventTypeNames.WEATHER_ALERT, "把天气预警作为上下文事实，不主动制造恐慌；只有策略允许时才输出用户可见内容。", actions = listOf(AgentActionRegistry.UPDATE_PET_STATE)),
            rule("nearby-candidate", "附近候选", ExternalEventTypeNames.NEARBY_CANDIDATE, "当前版本不触发附近候选主动场景。", enabled = false, actions = emptyList()),
            rule("trigger-fire", "待办到期重判", AutomationEventTypes.TRIGGER_FIRE, "重新判断待办是否值得现在承接；没有自然理由就不输出。"),
            rule(
                id = "time-point-example",
                name = "定时规则示例",
                eventType = AutomationEventTypes.TIME_POINT,
                instruction = "这是一个可复制修改的定时规则示例。",
                enabled = false,
                actions = emptyList(),
                conditions = listOf(AutomationCondition("local_time", ConditionOperators.EQUALS, JsonPrimitive("09:00"))),
            ),
        ),
    )

    private fun rule(
        id: String,
        name: String,
        eventType: String,
        instruction: String,
        enabled: Boolean = true,
        actions: List<String> = listOf(
            AgentActionRegistry.SEND_PROACTIVE_MESSAGE,
            AgentActionRegistry.PLAY_ANIMATION,
            AgentActionRegistry.UPDATE_PET_STATE,
        ),
        conditions: List<AutomationCondition> = emptyList(),
    ): AutomationRule = AutomationRule(
        id = id,
        name = name,
        enabled = enabled,
        trigger = AutomationTrigger(eventType = eventType, conditions = conditions),
        modelInstruction = instruction,
        allowedActions = actions,
    )
}

class AutomationRuleStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AutomationRuleDocument {
        val stored = prefs.getString(KEY_DOCUMENT, null)
        return stored?.let { raw ->
            when (val result = AutomationRuleValidator.parse(raw)) {
                is AutomationRuleImportResult.Success -> result.document
                is AutomationRuleImportResult.Failure -> null
            }
        } ?: AutomationRuleCatalog.defaults()
    }

    fun save(document: AutomationRuleDocument) {
        require(AutomationRuleValidator.validate(document) == null)
        prefs.edit().putString(KEY_DOCUMENT, JsonCodec.encode(document)).apply()
    }

    fun exportJson(): String = JsonCodec.pretty(JsonCodec.encode(load()))

    fun importJson(raw: String): AutomationRuleImportResult = when (val result = AutomationRuleValidator.parse(raw)) {
        is AutomationRuleImportResult.Success -> result.also { save(it.document) }
        is AutomationRuleImportResult.Failure -> result
    }

    suspend fun importFromUri(contentResolver: ContentResolver, uri: Uri): AutomationRuleImportResult =
        withContext(Dispatchers.IO) {
            val raw = runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("无法读取文件")
            }.getOrElse { return@withContext AutomationRuleImportResult.Failure("读取文件失败：${it.message.orEmpty()}") }
            importJson(raw)
        }

    suspend fun exportToUri(contentResolver: ContentResolver, uri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportJson()) }
                    ?: error("无法写入文件")
            }
        }

    fun matchingRule(eventType: String, eventPayload: JsonObject, source: String? = null, now: Instant = Instant.now()): AutomationRule? {
        val event = AutomationEventView(type = eventType, source = source, payload = eventPayload)
        return load().rules
            .filter { AutomationRuleMatcher.matches(it, event, now) }
            .maxWithOrNull(compareBy<AutomationRule> { it.priority }.thenBy { it.id })
    }

    fun matchingRule(event: com.littlekitty.xiaokele.core.database.entity.EventEntity, now: Instant = Instant.now()): AutomationRule? {
        val payload = runCatching { JsonCodec.json.parseToJsonElement(event.payloadJson).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
        return matchingRule(event.type, payload, event.source, now)
    }

    fun constrainGate(base: PolicyGateResult, rule: AutomationRule): PolicyGateResult = when (base) {
        is PolicyGateResult.ForceNoAction -> base
        is PolicyGateResult.AllowAgent -> {
            val actions = base.availableActions.intersect(rule.allowedActions.toSet())
            if (actions.isEmpty()) {
                PolicyGateResult.ForceNoAction("automation_rule_has_no_allowed_action", enrichPolicy(base.policyJson, rule))
            } else {
                PolicyGateResult.AllowAgent(
                    availableActions = actions,
                    policyJson = enrichPolicy(base.policyJson, rule),
                    maxOutputs = base.maxOutputs,
                )
            }
        }
    }

    private fun enrichPolicy(base: JsonObject, rule: AutomationRule): JsonObject = buildJsonObject {
        base.forEach { (key, value) -> put(key, value) }
        put("automation_rule", buildJsonObject {
            put("id", rule.id)
            put("name", rule.name)
            put("mode", rule.mode)
            put("model_instruction", rule.modelInstruction)
            rule.fixedMessage?.let { put("fixed_message", it) }
            put("allowed_tools", rule.allowedTools.joinToString(","))
            put("allowed_actions", rule.allowedActions.joinToString(","))
            rule.animationId?.let { put("animation_id", it) }
        })
    }

    companion object {
        const val PREFS_NAME = "little_kitty_automation_rules"
        const val KEY_DOCUMENT = "document"
    }
}

data class AutomationEventView(
    val type: String,
    val source: String?,
    val payload: JsonObject,
)

object AutomationRuleMatcher {
    fun matches(rule: AutomationRule, event: AutomationEventView, now: Instant): Boolean {
        if (!rule.enabled || rule.trigger.eventType != event.type) return false
        val explicitRuleId = event.payload["automation_rule_id"]?.jsonPrimitive?.contentOrNull
        if (explicitRuleId != null && explicitRuleId != rule.id) return false
        return rule.trigger.conditions.all { condition -> evaluate(condition, valueFor(condition.field, event, now)) }
    }

    private fun valueFor(field: String, event: AutomationEventView, now: Instant): JsonElement? {
        if (field == "event.type") return JsonPrimitive(event.type)
        if (field == "event.source") return event.source?.let(::JsonPrimitive)
        if (field.startsWith("payload.")) {
            var current: JsonElement? = event.payload
            field.removePrefix("payload.").split('.').forEach { part ->
                current = (current as? JsonObject)?.get(part)
            }
            return current
        }
        scheduledTimePointValue(field, event)?.let { return it }
        val local = now.atZone(ZoneId.of("Asia/Shanghai"))
        return when (field) {
            "local_time" -> JsonPrimitive(local.format(LOCAL_TIME_FORMAT))
            "local_date" -> JsonPrimitive(local.toLocalDate().toString())
            "local_hour" -> JsonPrimitive(local.hour)
            "local_minute" -> JsonPrimitive(local.minute)
            "local_day_of_week" -> JsonPrimitive(local.dayOfWeek.value)
            else -> null
        }
    }

    /**
     * TIME_POINT conditions describe the requested wall-clock time. When
     * WorkManager starts a valid catch-up a few minutes late, matching against
     * the actual dispatch clock would incorrectly reject its own rule.
     */
    private fun scheduledTimePointValue(field: String, event: AutomationEventView): JsonElement? {
        if (event.type != AutomationEventTypes.TIME_POINT) return null
        return when (field) {
            "local_time" -> event.payload["scheduled_local_time"] ?: scheduledTimeText(event.payload)
            "local_date" -> event.payload["scheduled_local_date"]
            "local_hour" -> event.payload["scheduled_local_hour"]
            "local_minute" -> event.payload["scheduled_local_minute"]
            "local_day_of_week" -> event.payload["scheduled_local_day_of_week"]
            else -> null
        }
    }

    private fun scheduledTimeText(payload: JsonObject): JsonPrimitive? {
        val hour = (payload["scheduled_local_hour"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null
        val minute = (payload["scheduled_local_minute"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return JsonPrimitive(hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0'))
    }

    private fun evaluate(condition: AutomationCondition, actual: JsonElement?): Boolean {
        if (condition.operator == ConditionOperators.EXISTS) {
            return (actual != null) == ((condition.value as? JsonPrimitive)?.booleanOrNull ?: true)
        }
        if (actual == null) return false
        val expected = condition.value
        return when (condition.operator) {
            ConditionOperators.EQUALS -> actual == expected || scalarContent(actual) == scalarContent(expected)
            ConditionOperators.NOT_EQUALS -> !evaluate(condition.copy(operator = ConditionOperators.EQUALS), actual)
            ConditionOperators.CONTAINS -> scalarContent(actual)?.contains(scalarContent(expected).orEmpty(), ignoreCase = true) == true
            ConditionOperators.GTE -> compareNumbers(actual, expected) { a, b -> a >= b }
            ConditionOperators.LTE -> compareNumbers(actual, expected) { a, b -> a <= b }
            ConditionOperators.IN -> expected.toString().contains(actual.toString()) || expected.toString().contains(scalarContent(actual).orEmpty())
            else -> false
        }
    }

    private fun compareNumbers(actual: JsonElement, expected: JsonElement, predicate: (Double, Double) -> Boolean): Boolean {
        val a = scalarContent(actual)?.toDoubleOrNull() ?: return false
        val b = scalarContent(expected)?.toDoubleOrNull() ?: return false
        return predicate(a, b)
    }

    private fun scalarContent(element: JsonElement): String? =
        (element as? JsonPrimitive)?.contentOrNull ?: element.toString()

    private val LOCAL_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
