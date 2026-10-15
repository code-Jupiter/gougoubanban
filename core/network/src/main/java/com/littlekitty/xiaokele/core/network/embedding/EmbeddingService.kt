package com.littlekitty.xiaokele.core.network.embedding

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.network.ServiceAdapter
import java.net.URI

/**
 * Replaceable remote embedding boundary (FR-SERVICE-04, architecture 28/38).
 *
 * The supplier-specific implementation is replaceable. Release builds use a
 * runtime-configured provider adapter and fail closed when its settings are
 * missing; local memory search still retains lexical fallback for outages.
 */
interface EmbeddingService : ServiceAdapter {
    suspend fun embed(texts: List<String>): EmbeddingResult

    /** Embeds texts used as search queries; simple providers may share one mode. */
    suspend fun embedQuery(texts: List<String>): EmbeddingResult = embed(texts)

    /** Embeds texts persisted as searchable memory documents. */
    suspend fun embedDocuments(texts: List<String>): EmbeddingResult = embed(texts)
}

data class EmbeddingConfig(
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && model.isNotBlank() && runCatching {
            val uri = URI(endpoint)
            uri.scheme in setOf("http", "https") && uri.host.isNullOrBlank().not()
        }.getOrDefault(false)

    companion object {
        val UNCONFIGURED = EmbeddingConfig()
    }
}

data class EmbeddingVector(
    val values: List<Float>,
)

data class EmbeddingBatch(
    val modelId: String,
    val dimension: Int,
    val vectors: List<EmbeddingVector>,
)

sealed interface EmbeddingResult {
    data class Success(val batch: EmbeddingBatch) : EmbeddingResult

    data class Failure(val error: AppError) : EmbeddingResult
}
