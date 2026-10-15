package com.littlekitty.xiaokele.provider

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import kotlinx.serialization.json.jsonObject

/** A syntax-only repair is never allowed to create facts, fields or evidence IDs. */
internal object StructuredJsonRepair {
    private const val MAX_SOURCE_LENGTH = 12_000

    fun eligible(source: String): Boolean {
        if (source.isBlank() || source.length > MAX_SOURCE_LENGTH) return false
        val trimmed = source.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in trimmed) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                }
            } else {
                when (character) {
                    '"' -> quoted = true
                    '{' -> depth++
                    '}' -> if (--depth < 0) return false
                }
            }
        }
        return depth == 0 && !quoted && !escaped
    }

    fun lossless(source: String, repaired: String): Boolean {
        if (!eligible(source) || repaired.length > MAX_SOURCE_LENGTH) return false
        if (runCatching { JsonCodec.json.parseToJsonElement(repaired).jsonObject }.isFailure) return false
        // Only a known unambiguous edit is accepted: remove trailing commas.
        // Keep ALL structural tokens, so moving a value into another object or
        // array cannot pass merely because its words stayed in the same order.
        val original = tokens(source) ?: return false
        val candidate = tokens(repaired) ?: return false
        val normalized = original.filterIndexed { index, token ->
            !(token == "," && original.getOrNull(index + 1) in listOf("}", "]"))
        }
        return normalized == candidate && hasUniqueKeys(candidate)
    }

    /** Also reject duplicate object keys in otherwise syntactically valid output. */
    fun validObject(value: String): Boolean {
        if (runCatching { JsonCodec.json.parseToJsonElement(value).jsonObject }.isFailure) return false
        return tokens(value)?.let(::hasUniqueKeys) == true
    }

    private fun tokens(value: String): List<String>? {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var escaped = false
        fun flush() {
            if (current.isNotEmpty()) {
                tokens += current.toString()
                current.clear()
            }
        }
        for (character in value) {
            if (quoted) {
                current.append(character)
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> { quoted = false; flush() }
                }
            } else if (character == '"') {
                flush()
                quoted = true
                current.append(character)
            } else if (character.isWhitespace()) {
                flush()
            } else if (character in "{}[],:") {
                flush()
                tokens += character.toString()
            } else {
                current.append(character)
            }
        }
        flush()
        if (quoted || escaped) return null
        return tokens
    }

    private fun hasUniqueKeys(tokens: List<String>): Boolean {
        var index = 0
        fun consume(token: String): Boolean = if (tokens.getOrNull(index) == token) {
            index++; true
        } else false
        fun value(depth: Int): Boolean {
            if (depth > 64) return false
            return when (tokens.getOrNull(index)) {
                "{" -> {
                    index++
                    val keys = mutableSetOf<String>()
                    if (consume("}")) return true
                    while (true) {
                        val raw = tokens.getOrNull(index++) ?: return false
                        if (!raw.startsWith('"')) return false
                        val key = runCatching { JsonCodec.decode<String>(raw) }.getOrNull() ?: return false
                        if (!keys.add(key) || !consume(":") || !value(depth + 1)) return false
                        if (consume("}")) break
                        if (!consume(",")) return false
                    }
                    true
                }
                "[" -> {
                    index++
                    if (consume("]")) return true
                    while (true) {
                        if (!value(depth + 1)) return false
                        if (consume("]")) break
                        if (!consume(",")) return false
                    }
                    true
                }
                null, "}", "]", ":", "," -> false
                else -> { index++; true }
            }
        }
        return tokens.firstOrNull() == "{" && value(0) && index == tokens.size
    }
}
