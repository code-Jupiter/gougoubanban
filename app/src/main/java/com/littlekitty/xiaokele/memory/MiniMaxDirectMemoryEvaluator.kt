package com.littlekitty.xiaokele.memory

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.network.HttpMethod
import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import com.littlekitty.xiaokele.core.network.ServiceCallTrace
import com.littlekitty.xiaokele.core.network.memory.MemoryEvaluationRequest
import com.littlekitty.xiaokele.core.network.memory.MemoryEvaluationResult
import com.littlekitty.xiaokele.core.network.memory.MemoryEvaluator
import com.littlekitty.xiaokele.provider.MiniMaxDirectConfig
import com.littlekitty.xiaokele.provider.ModelProvider
import com.littlekitty.xiaokele.provider.TaskKind
import com.littlekitty.xiaokele.provider.TaskModelGateway
import com.littlekitty.xiaokele.provider.TaskModelRequest
import com.littlekitty.xiaokele.provider.TaskModelResult
import com.littlekitty.xiaokele.provider.ReplyPromptPack
import com.littlekitty.xiaokele.provider.GenerationBudget
import com.littlekitty.xiaokele.provider.StructuredJsonRepair
import kotlinx.coroutines.withTimeoutOrNull
import com.littlekitty.xiaokele.settings.ByokRuntimeConfig
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** BYOK-backed evaluator used only for explicit remember/forget/correction turns. */
class MiniMaxDirectMemoryEvaluator(
    private val settings: ByokRuntimeConfig,
    private val transport: HttpTransport,
    private val taskGateway: TaskModelGateway? = null,
) : MemoryEvaluator {
    override val serviceName: String = "minimax_byok_memory_evaluator"

    override suspend fun evaluate(request: MemoryEvaluationRequest): MemoryEvaluationResult {
        taskGateway?.let { gateway ->
            return withTimeoutOrNull(60_000L) {
                val modelRequest = TaskModelRequest(
                    task = TaskKind.D_MEMORY,
                    userPayload = JsonCodec.encode(request),
                    systemPrompt = buildString {
                        // The output contract goes first and is stated as a
                        // top-level shape: a live M3 reply wrapped everything in
                        // `{"memoryEvaluation":{"operations":...}}` when the same
                        // schema was appended after the prose, and the app then
                        // parsed zero candidates.
                        append("只输出下面这个 JSON 对象本身，不要包装、不要增加外层键、不要用 operations/ADD/REVISE 之类的结构：\n")
                        append(ReplyPromptPack.MEMORY_EVALUATION_SCHEMA)
                        append("\n\n五个数组都必须出现在最外层；没有可沉淀内容时保留数组并留空。\n\n")
                        append(ReplyPromptPack.systemPrompt(TaskKind.D_MEMORY))
                        append("\n\n只能引用请求内真实 evidenceId；无法确定就不要写入候选。")
                    },
                )
                // The processor may already have requested its single schema retry.
                // That retry receives no new repair budget (at most 2 + 1 D calls).
                val budget = GenerationBudget(maxRequests = if (request.formatRepairInstruction == null) 2 else 1)
                var result = gateway.generate(modelRequest, budget)
                val failure = result as? TaskModelResult.Failure
                if (request.formatRepairInstruction == null && failure?.error?.code == ErrorCode.MODEL_OUTPUT_INVALID) {
                    val source = failure.recoverableContent
                    if (source != null && StructuredJsonRepair.eligible(source)) {
                        val repair = gateway.generate(modelRequest.copy(
                            systemPrompt = "只修复完整 JSON 对象的标点，不增删改字段和值，不改变对象层级或字段归属。只输出 JSON。",
                            userPayload = source,
                            redactDiagnostics = true,
                        ), budget)
                        if (repair is TaskModelResult.Success && StructuredJsonRepair.lossless(source, repair.content)) result = repair
                    } else {
                        result = gateway.generate(modelRequest, budget)
                    }
                }
                when (val final = result) {
                    is TaskModelResult.Success -> MemoryEvaluationResult.Success(final.content, final.trace, final.commitGuard)
                    is TaskModelResult.Failure -> MemoryEvaluationResult.Failure(final.error, final.trace)
                }
            } ?: MemoryEvaluationResult.Failure(AppError(ErrorCode.MODEL_FAILURE, technicalDetail = "memory_evaluation_timeout"))
        }
        // The direct MiniMax implementation is an explicit legacy/test
        // Compatibility path for explicit callers without TaskModelGateway.
        // It is still scoped to D_MEMORY's configured provider, never to a
        // whole-generation profile.
        if (settings.agentModelRoute(TaskKind.D_MEMORY).provider != ModelProvider.MINIMAX_OFFICIAL) {
            return MemoryEvaluationResult.Failure(
                AppError(
                    ErrorCode.MODEL_NOT_CONFIGURED,
                    technicalDetail = "profiled_task_gateway_required_for_selected_profile",
                ),
            )
        }
        val key = settings.loadKeyOrNull()
        if (key.isNullOrBlank()) {
            return MemoryEvaluationResult.Failure(
                AppError(ErrorCode.MEMORY_EVALUATOR_NOT_CONFIGURED, technicalDetail = "memory_byok_key_missing"),
            )
        }
        val endpoint = settings.endpoint().ifBlank {
            if (settings.region() == "INTL") MiniMaxDirectConfig.M2_INTL_ENDPOINT else MiniMaxDirectConfig.M2_CN_ENDPOINT
        }
        val body = buildJsonObject {
            put("model", MiniMaxDirectConfig.DEFAULT_OFFICIAL_MODEL)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", buildString {
                        append("只输出下面这个 JSON 对象本身，不要包装、不要增加外层键：\n")
                        append(ReplyPromptPack.MEMORY_EVALUATION_SCHEMA)
                        append("\n\n")
                        append(SYSTEM_PROMPT)
                        request.formatRepairInstruction?.let {
                            append("\n\n格式修复重试要求：\n")
                            append(it)
                        }
                    })
                })
                add(buildJsonObject { put("role", "user"); put("content", JsonCodec.encode(request)) })
            })
            put("temperature", 0.1)
            // Keep the explicit legacy adapter on the same D_MEMORY budget as
            // the profiled gateway; a caller must not inherit an unbounded
            // provider default merely because it did not inject TaskGateway.
            put("max_completion_tokens", 2_048)
        }.toString()
        val startedAt = System.currentTimeMillis()
        return when (
            val result = transport.execute(
                HttpRequest(
                    method = HttpMethod.POST,
                    url = endpoint,
                    headers = mapOf(
                        "Authorization" to "Bearer $key",
                        "Content-Type" to "application/json",
                    ),
                    body = body,
                ),
            )
        ) {
            is NetworkResult.Failure -> MemoryEvaluationResult.Failure(
                result.error,
                trace = ServiceCallTrace(
                    serviceName = serviceName,
                    requestBody = body,
                    latencyMillis = System.currentTimeMillis() - startedAt,
                ),
            )
            is NetworkResult.Success -> parseResponse(
                result.statusCode,
                result.body,
                ServiceCallTrace(
                    serviceName = serviceName,
                    requestBody = body,
                    rawResponseBody = result.body,
                    responseStatusCode = result.statusCode,
                    latencyMillis = System.currentTimeMillis() - startedAt,
                ),
            )
        }
    }

    private fun parseResponse(statusCode: Int, body: String, trace: ServiceCallTrace): MemoryEvaluationResult {
        if (statusCode !in 200..299) {
            return MemoryEvaluationResult.Failure(
                AppError(ErrorCode.MODEL_FAILURE, technicalDetail = "memory_evaluation_http_$statusCode"),
                trace = trace,
            )
        }
        return runCatching {
            val root = JsonCodec.json.parseToJsonElement(body).jsonObject
            val baseStatus = root["base_resp"]?.jsonObject
                ?.get("status_code")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            require(baseStatus == 0) { "provider_status_$baseStatus" }
            val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                .orEmpty()
            val normalized = content.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            require(normalized.isNotBlank()) { "memory_evaluation_blank" }
            MemoryEvaluationResult.Success(normalized, trace)
        }.getOrElse { throwable ->
            MemoryEvaluationResult.Failure(
                AppError(
                    ErrorCode.MODEL_OUTPUT_INVALID,
                    technicalDetail = "memory_evaluation_invalid:${throwable.message}",
                ),
                trace = trace,
            )
        }
    }

    companion object {
        private val SYSTEM_PROMPT = """
            你负责处理记忆评估。event_type=EXPLICIT_TURN 时，只处理用户明确提出的记住、忘记、纠正或长期行为要求；event_type=SESSION_SETTLEMENT 时，审阅该轮完整对话，只沉淀未来确实有用且有明确依据的稳定偏好、重要关系事件、持续约束或短期状态。两种模式都不得把推测写成事实。
            只能依据输入消息，不得把推测写成事实。普通寒暄、知识问题和无未来用途的信息不写入记忆。
            输出必须是合法 MemoryEvaluation JSON，且只能包含 memory_candidates、user_trait_patches、behavior_patches、deactivate_memory_ids、pattern_observations。
            validity 只能是 TEMPORARY、SHORT_TERM、LONG_TERM；TEMPORARY 默认 24 小时，SHORT_TERM 默认 14 天。
            user_trait_patches.profile_layer 只能是 CORE 或 DYNAMIC；稳定身份、稳定偏好、禁忌和重要沟通方式使用 CORE。
            重要关系事件可填写 emotion_at_event 和 current_appraisal，普通事实不要填写。
            emotion_at_event 和 current_appraisal 如果填写，必须是 JSON 对象（例如 {"value":"略烦躁"}），不能直接填写字符串。
            evidence_memory_ids 只能填写输入 active_memories 中真实存在的 mem_ id；没有可引用的记忆时必须是空数组，禁止使用 mem_pending 或其他占位 id。
            用户明确要求忘记时，从 active_memories 中选择准确 id 放入 deactivate_memory_ids；没有明确匹配时保持空数组，不得猜测。
            完整结构示例：{"memory_candidates":[{"type":"USER_PREFERENCE","subject":null,"content":"事实","summary":null,"importance":0.8,"confidence":0.9,"validity":"LONG_TERM","evidence_message_ids":["msg_..."],"source_message_id":"msg_...","valid_until":null,"supersedes_memory_id":null,"emotion_at_event":null,"current_appraisal":null}],"user_trait_patches":[{"key":"preference_key","operation":"UPSERT","value":{"description":"偏好"},"confidence":0.9,"profile_layer":"CORE","evidence_memory_ids":[]}],"behavior_patches":[],"deactivate_memory_ids":[],"pattern_observations":[]}
            没有对应结果时必须输出空数组。
            只能输出 JSON 对象，不能输出 Markdown 或解释文字。
        """.trimIndent()
    }
}
