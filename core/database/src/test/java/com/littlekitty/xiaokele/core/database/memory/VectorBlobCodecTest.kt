package com.littlekitty.xiaokele.core.database.memory

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorBlobCodecTest {

    @Test
    fun roundTripPreservesFloatValues() {
        val values = listOf(0.1f, -0.25f, 0.75f, 1.0f)

        val blob = VectorBlobCodec.encode(values)
        val decoded = VectorBlobCodec.decode(blob)

        assertEquals(values, decoded)
    }

    @Test
    fun sameVectorCosineIsOneAndOrthogonalVectorsAreZero() {
        assertEquals(1.0, VectorBlobCodec.cosine(listOf(1f, 0f), listOf(1f, 0f)), 0.0001)
        assertEquals(0.0, VectorBlobCodec.cosine(listOf(1f, 0f), listOf(0f, 1f)), 0.0001)
    }

    @Test
    fun dimensionMismatchIsRejected() {
        val error = runCatching {
            VectorBlobCodec.cosine(listOf(1f, 0f), listOf(1f, 0f, 0f))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun encodeIsLittleEndianFloatArray() {
        val blob = VectorBlobCodec.encode(listOf(1.0f))

        assertEquals(4, blob.size)
        // 1.0f IEEE-754 is 0x3f800000; little-endian bytes are 00 00 80 3f.
        assertArrayEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x3f), blob)
    }
}
