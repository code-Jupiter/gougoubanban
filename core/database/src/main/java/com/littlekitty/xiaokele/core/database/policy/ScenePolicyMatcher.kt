package com.littlekitty.xiaokele.core.database.policy

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.database.entity.EventEntity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Deterministic matcher_json semantics (Phase 7 freeze):
 *
 * - absent/blank matcher matches any event of the row event_type.
 * - `type` and `source` are exact string matches.
 * - `payload` is an exact-equality map for primitive payload fields.
 * - `payload_present` requires the listed payload fields to exist.
 * - `payload_absent` requires the listed payload fields to be absent.
 * - A payload value may be `{"in": ["a", "b"]}` for an enum-like membership
 *   check; this is the only compound operator and is intentionally small.
 * - A malformed non-blank matcher fails closed (no match).
 */
object ScenePolicyMatcher {

    fun matches(event: EventEntity, matcherJson: String?): Boolean {
        if (matcherJson.isNullOrBlank()) return true
        val matcher = runCatching { JsonCodec.json.parseToJsonElement(matcherJson) as? JsonObject }
            .getOrNull() ?: return false
        if (matcher == null) return false

        matcher["type"]?.let { expected ->
            if (expected !is JsonPrimitive || !expected.isString || expected.content != event.type) return false
        }
        matcher["source"]?.let { expected ->
            if (expected !is JsonPrimitive || !expected.isString || expected.content != event.source) return false
        }

        val eventPayload = runCatching { JsonCodec.json.parseToJsonElement(event.payloadJson) as? JsonObject }
            .getOrNull() ?: JsonObject(emptyMap())

        matcher["payload"]?.let { expected ->
            if (expected !is JsonObject) return false
            expected.forEach { (key, expectedValue) ->
                if (!primitiveMatches(eventPayload[key], expectedValue)) return false
            }
        }
        matcher["payload_present"]?.let { expected ->
            if (expected !is JsonArray) return false
            expected.forEach { field ->
                if (field !is JsonPrimitive || !field.isString || eventPayload.containsKey(field.content).not()) {
                    return false
                }
            }
        }
        matcher["payload_absent"]?.let { expected ->
            if (expected !is JsonArray) return false
            expected.forEach { field ->
                if (field is JsonPrimitive && field.isString && eventPayload.containsKey(field.content)) {
                    return false
                }
            }
        }
        return true
    }

    private fun primitiveMatches(actual: JsonElement?, expected: JsonElement): Boolean {
        if (actual == null || actual is JsonNull) return false
        if (expected is JsonObject) {
            val inValues = expected["in"]
            if (inValues != null && expected.size == 1 && inValues is JsonArray) {
                return inValues.any { primitiveEquals(actual, it) }
            }
            return primitiveEquals(actual, expected)
        }
        return primitiveEquals(actual, expected)
    }

    private fun primitiveEquals(actual: JsonElement, expected: JsonElement): Boolean = when (actual) {
        is JsonPrimitive -> when (expected) {
            is JsonPrimitive -> when {
                actual.isString && expected.isString -> actual.content == expected.content
                else -> runCatching { actual.content.toDouble() == expected.content.toDouble() }
                    .getOrDefault(false)
            }

            else -> false
        }

        else -> actual == expected
    }
}
