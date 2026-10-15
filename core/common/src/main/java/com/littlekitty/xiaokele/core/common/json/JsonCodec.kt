package com.littlekitty.xiaokele.core.common.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/**
 * Single JSON codec for all local persistence payloads, agent protocol samples
 * and debug dumps. Modules must not create ad-hoc `Json {}` instances.
 */
object JsonCodec {
    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = false
        isLenient = false
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val prettyJson: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = true
        isLenient = false
    }

    inline fun <reified T> encode(value: T): String = json.encodeToString(serializer<T>(), value)

    inline fun <reified T> decode(raw: String): T = json.decodeFromString(serializer<T>(), raw)

    fun <T> encode(value: T, serializer: KSerializer<T>): String = json.encodeToString(serializer, value)

    fun <T> decode(raw: String, serializer: KSerializer<T>): T = json.decodeFromString(serializer, raw)

    /** Formats wire JSON for debug/export surfaces without changing persisted JSON. */
    fun pretty(raw: String): String = runCatching {
        prettyJson.encodeToString(JsonElement.serializer(), prettyJson.parseToJsonElement(raw))
    }.getOrDefault(raw)
}
