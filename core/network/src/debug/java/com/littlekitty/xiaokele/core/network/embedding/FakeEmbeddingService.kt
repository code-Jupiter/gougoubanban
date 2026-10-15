package com.littlekitty.xiaokele.core.network.embedding

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import kotlin.math.sqrt

enum class FakeEmbeddingMode {
    SUCCESS,
    FAILURE,
}

/**
 * Deterministic debug/test embedding double. Vectors are derived from character
 * bigrams so semantically related Chinese phrases share dimensions; the exact
 * values are local test fixtures, not a product embedding model.
 */
class FakeEmbeddingService(
    initialMode: FakeEmbeddingMode = FakeEmbeddingMode.SUCCESS,
    private val modelId: String = DEFAULT_MODEL_ID,
    private val dimension: Int = DEFAULT_DIMENSION,
) : EmbeddingService {

    @Volatile
    var mode: FakeEmbeddingMode = initialMode

    override val serviceName: String = "fake_embedding"

    override suspend fun embed(texts: List<String>): EmbeddingResult =
        when (mode) {
            FakeEmbeddingMode.SUCCESS -> {
                if (texts.isEmpty()) {
                    EmbeddingResult.Failure(
                        AppError(ErrorCode.INVALID_ARGUMENT, technicalDetail = "embedding_texts_empty"),
                    )
                } else {
                    EmbeddingResult.Success(
                        EmbeddingBatch(
                            modelId = modelId,
                            dimension = dimension,
                            vectors = texts.map { EmbeddingVector(textVector(it, dimension)) },
                        ),
                    )
                }
            }

            FakeEmbeddingMode.FAILURE -> EmbeddingResult.Failure(
                AppError(ErrorCode.EMBEDDING_FAILURE, technicalDetail = "fake_embedding_failure"),
            )
        }

    private fun textVector(text: String, dims: Int): List<Float> {
        val values = FloatArray(dims)
        val features = linkedSetOf<String>()
        text.trim().forEachIndexed { index, _ ->
            val bigram = text.substring(index, (index + 2).coerceAtMost(text.length))
            if (bigram.length >= 2) features += bigram
        }
        text.forEach { features += it.toString() }
        features.forEach { feature ->
            val hash = feature.hashCode()
            val index = ((hash % dims) + dims) % dims
            val sign = if ((hash ushr 16) and 1 == 0) 1.0f else -1.0f
            val weight = 0.55f + (kotlin.math.abs(hash % 17) / 17.0f) * 0.45f
            values[index] += sign * weight
        }
        val norm = sqrt(values.map { it * it }.sum().toDouble()).toFloat()
        if (norm <= 0.0f) {
            values[0] = 1.0f
            return values.toList()
        }
        return values.map { it / norm }
    }

    companion object {
        const val DEFAULT_MODEL_ID = "fake-embedding-v1"
        const val DEFAULT_DIMENSION = 16
    }
}
