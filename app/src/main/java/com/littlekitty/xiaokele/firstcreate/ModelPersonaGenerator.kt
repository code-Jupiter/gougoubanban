package com.littlekitty.xiaokele.firstcreate

import com.littlekitty.xiaokele.provider.TaskKind
import com.littlekitty.xiaokele.provider.TaskModelGateway
import com.littlekitty.xiaokele.provider.TaskModelRequest
import com.littlekitty.xiaokele.provider.TaskModelResult

/**
 * Writes the user's persona with a model the user already verified.
 *
 * Routing: the request uses the D_MEMORY slot, whose job is closest to this one
 * (turn raw material into a structured artefact). That choice means a newcomer
 * does not have to validate an extra Agent just to create their companion, and
 * it keeps the "one verified connection is enough to get started" promise.
 *
 * It is deliberately not [TaskKind.B2_EXPRESSION]: that slot is a plain-text
 * model that cannot return our schema.
 */
class ModelPersonaGenerator(
    private val taskGateway: TaskModelGateway,
) : PersonaGenerator {

    override suspend fun generate(input: PersonaInput): PersonaGenerationResult {
        val request = TaskModelRequest(
            task = TaskKind.D_MEMORY,
            userPayload = PersonaPromptSpec.buildUserPayload(input),
            systemPrompt = PersonaPromptSpec.systemPrompt,
            structured = true,
        )
        return when (val result = taskGateway.generate(request)) {
            is TaskModelResult.Success ->
                // Parse and validate before the user ever sees it: model output
                // is untrusted input even when it is schema-shaped.
                PersonaPromptSpec.parse(result.content)

            is TaskModelResult.Failure ->
                PersonaGenerationResult.Failure(
                    result.error.safeUserMessage().ifBlank { "模型没有返回可用的设定。" },
                )
        }
    }
}

/**
 * A generator that always fails, used when no verified connection exists.
 *
 * It exists so the flow has no path that silently writes a persona with an
 * unverified credential; the UI turns this into the documented fallback.
 */
object UnavailablePersonaGenerator : PersonaGenerator {
    override suspend fun generate(input: PersonaInput): PersonaGenerationResult =
        PersonaGenerationResult.Failure("当前没有已验证的模型可用于生成设定。")
}
