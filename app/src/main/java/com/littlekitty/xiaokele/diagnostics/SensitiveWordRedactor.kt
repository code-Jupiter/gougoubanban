package com.littlekitty.xiaokele.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Applies the user-selected privacy rules to the string values in a diagnostic
 * JSON document. The words themselves are never included in the exported file.
 */
class SensitiveWordRedactor private constructor(
    private val words: List<String>,
) {

    val wordCount: Int
        get() = words.size

    fun redact(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            element.forEach { (key, value) -> put(key, redact(value)) }
        }
        is JsonArray -> JsonArray(element.map(::redact))
        is JsonPrimitive -> if (element.isString) {
            JsonPrimitive(redact(element.content))
        } else {
            element
        }
    }

    private fun redact(value: String): String = words.fold(value) { current, word ->
        current.replace(word, "*")
    }

    companion object {
        fun fromInput(input: String): SensitiveWordRedactor {
            val words = input
                .split(',', '，', '\n', '\r')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .sortedByDescending(String::length)
            return SensitiveWordRedactor(words)
        }
    }
}
