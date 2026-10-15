package com.littlekitty.xiaokele.core.database.memory

import com.littlekitty.xiaokele.core.database.entity.MemoryEntity
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Local memory retrieval boundary (architecture section 28).
 * Implementations first filter by memory metadata, then optionally add cosine
 * similarity when a remote embedding service is configured.
 */
interface MemorySearch {
    suspend fun search(query: MemoryQuery): List<MemoryHit>
}

data class MemoryQuery(
    val text: String? = null,
    val types: Set<String>? = null,
    val sourceMessageId: String? = null,
    val sourceEventId: String? = null,
    val limit: Int = DEFAULT_RESULT_LIMIT,
) {
    companion object {
        const val DEFAULT_RESULT_LIMIT = 8
    }
}

data class MemoryHit(
    val memory: MemoryEntity,
    val score: Double,
    val matchedBy: MatchKind,
)

enum class MatchKind {
    LEXICAL,
    VECTOR,
}

/**
 * Portable BLOB codec for memory_embeddings.vector_blob.
 *
 * The on-disk format is a sequence of little-endian IEEE-754 float32 values;
 * the model id and dimension are persisted as table columns and are validated
 * by the caller before decoding. No external vector library is required.
 */
object VectorBlobCodec {

    fun encode(values: List<Float>): ByteArray {
        require(values.isNotEmpty()) { "vector must not be empty" }
        require(values.all { it.isFinite() }) { "vector must contain only finite floats" }
        val buffer = ByteBuffer.allocate(values.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    fun decode(blob: ByteArray): List<Float> {
        require(blob.size >= Float.SIZE_BYTES) { "vector blob is too short" }
        require(blob.size % Float.SIZE_BYTES == 0) { "vector blob length is not float-aligned" }
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return List(blob.size / Float.SIZE_BYTES) { buffer.float }
    }

    fun cosine(left: List<Float>, right: List<Float>): Double {
        require(left.size == right.size) { "vector dimensions differ: ${left.size} != ${right.size}" }
        require(left.isNotEmpty()) { "vector must not be empty" }
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            dot += left[index].toDouble() * right[index].toDouble()
            leftNorm += left[index].toDouble() * left[index].toDouble()
            rightNorm += right[index].toDouble() * right[index].toDouble()
        }
        if (leftNorm <= 0.0 || rightNorm <= 0.0) return 0.0
        return dot / (kotlin.math.sqrt(leftNorm) * kotlin.math.sqrt(rightNorm))
    }
}
