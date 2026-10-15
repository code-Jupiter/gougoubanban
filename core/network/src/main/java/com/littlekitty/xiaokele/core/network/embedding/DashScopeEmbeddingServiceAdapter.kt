package com.littlekitty.xiaokele.core.network.embedding

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * DashScope text-embedding adapter. A workspace host can override the public
 * DashScope domain, while the service path is stable for the native API.
 */
class DashScopeEmbeddingServiceAdapter(
    private val transport: com.littlekitty.xiaokele.core.network.HttpTransport,
    private val configProvider: () -> EmbeddingConfig,
) : EmbeddingService {

    override val serviceName: String = "dashscope_embedding"

    override suspend fun embed(texts: List<String>): EmbeddingResult = embedDocuments(texts)

    override suspend fun embedQuery(texts: List<String>): EmbeddingResult =
        embedWithType(texts, "query")

    override suspend fun embedDocuments(texts: List<String>): EmbeddingResult =
        embedWithType(texts, "document")

    private suspend fun embedWithType(texts: List<String>, textType: String): EmbeddingResult {
        val config = configProvider()
        val result = RemoteEmbeddingServiceAdapter(
            transport = transport,
            config = config,
            requestBodyBuilder = { input, currentConfig, json ->
                buildDashScopeRequestBody(input, currentConfig, json, textType)
            },
            responseParser = ::parseDashScopeResponse,
        ).embed(texts)
        return when (result) {
            is EmbeddingResult.Failure -> result
            is EmbeddingResult.Success -> result.copy(
                batch = result.batch.copy(modelId = config.model),
            )
        }
    }
}

const val DASHSCOPE_DEFAULT_HOST = "https://dashscope.aliyuncs.com"

/**
 * Normalizes a user-supplied DashScope host. A blank value deliberately means
 * the public Beijing DashScope domain rather than a malformed relative URL.
 */
fun dashScopeBaseUrl(apiHost: String): String {
    val raw = apiHost.trim().trimEnd('/').ifBlank { DASHSCOPE_DEFAULT_HOST }
    return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
}

fun dashScopeEmbeddingEndpoint(apiHost: String): String {
    val normalized = dashScopeBaseUrl(apiHost)
    return if (normalized.endsWith("/api/v1/services/embeddings/text-embedding/text-embedding")) {
        normalized
    } else {
        "$normalized/api/v1/services/embeddings/text-embedding/text-embedding"
    }
}

internal fun buildDashScopeRequestBody(
    texts: List<String>,
    config: EmbeddingConfig,
    _json: Json,
    textType: String = "document",
): String = buildJsonObject {
    put("model", config.model)
    put("input", buildJsonObject {
        put("texts", buildJsonArray { texts.forEach { add(JsonPrimitive(it)) } })
    })
    put("parameters", buildJsonObject {
        // The current service boundary is shared by stored memories and
        // queries. Keep the native API request valid; query/document-specific
        // tuning can be added when EmbeddingService gains an input-type field.
        put("text_type", textType)
    })
}.toString()

internal fun parseDashScopeResponse(body: String, json: Json): EmbeddingBatch {
    val root = json.parseToJsonElement(body).jsonObject
    val output = root["output"]?.jsonObject
        ?: error("dashscope_embedding_output_missing")
    val embeddings = output["embeddings"]?.jsonArray
        ?: error("dashscope_embedding_vectors_missing")
    require(embeddings.isNotEmpty()) { "dashscope_embedding_vectors_empty" }
    val vectors = embeddings.map { item ->
        val values = item.jsonObject["embedding"]?.jsonArray
            ?.map { value -> value.jsonPrimitive.float }
            ?: error("dashscope_embedding_vector_missing")
        require(values.isNotEmpty()) { "dashscope_embedding_vector_empty" }
        require(values.all(Float::isFinite)) { "dashscope_embedding_vector_non_finite" }
        EmbeddingVector(values)
    }
    val dimension = vectors.first().values.size
    require(vectors.all { it.values.size == dimension }) {
        "dashscope_embedding_dimension_mismatch"
    }
    return EmbeddingBatch(
        // The provider response does not repeat the model id. The adapter
        // replaces this marker with the configured model after parsing.
        modelId = "provider_response",
        dimension = dimension,
        vectors = vectors,
    )
}
