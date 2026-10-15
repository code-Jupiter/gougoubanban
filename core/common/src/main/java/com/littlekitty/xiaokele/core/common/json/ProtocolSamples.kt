package com.littlekitty.xiaokele.core.common.json

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Phase 0 placeholders that freeze the wire shape conventions only.
 * The full NormalizedEvent / ContextPack / AgentDecision schemas are implemented
 * in their owning phases and are not part of Phase 0 scope.
 */
@Serializable
data class Phase0WireSample(
    val id: String,
    val occurred_at: String,
    val payload: JsonElement,
)
