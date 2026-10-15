package com.littlekitty.xiaokele.core.common.json

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonCodecTest {

    @Serializable
    data class Sample(
        val id: String,
        val count: Int = 0,
    )

    @Test
    fun roundTripsValue() {
        val original = Sample(id = "msg_abc", count = 3)
        val raw = JsonCodec.encode(original)
        val decoded: Sample = JsonCodec.decode(raw)
        assertEquals(original, decoded)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val raw = """{"id":"msg_abc","count":2,"future_field":true}"""
        assertEquals(Sample("msg_abc", 2), JsonCodec.decode<Sample>(raw))
    }

    @Test
    fun encodeDefaultsAreIncluded() {
        val raw = JsonCodec.encode(Sample(id = "msg_abc"))
        assertEquals("""{"id":"msg_abc","count":0}""", raw)
    }

    @Test
    fun phase0WireSampleRoundTrips() {
        val payload = buildJsonObject { put("topic", "phase0") }
        val sample = Phase0WireSample(
            id = "evt_phase0",
            occurred_at = "2026-08-16T02:30:00Z",
            payload = payload,
        )
        assertEquals(sample, JsonCodec.decode<Phase0WireSample>(JsonCodec.encode(sample)))
    }
}
