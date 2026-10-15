package com.littlekitty.xiaokele.external

import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.time.InstantJson
import kotlinx.serialization.json.JsonElement
import java.time.Instant

internal fun provenanceJson(
    provider: String,
    serviceProvenance: String,
    fetchedAtEpochMillis: Long,
    cacheKey: String,
    isDebugFake: Boolean = false,
): JsonElement = JsonCodec.json.parseToJsonElement(
    JsonCodec.encode(
        ExternalEventProvenance(
            provider = provider,
            serviceProvenance = serviceProvenance,
            fetchedAt = InstantJson.formatUtc(Instant.ofEpochMilli(fetchedAtEpochMillis)),
            cacheKey = cacheKey,
            isDebugFake = isDebugFake,
        ),
    ),
)

