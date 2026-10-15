package com.littlekitty.xiaokele.core.network.embedding

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.network.HttpMethod
import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import kotlinx.serialization.json.Json

/**
 * Remote embedding adapter skeleton only.
 *
 * No vendor URL, path, JSON envelope, model name or response schema is hard-coded
 * here: [requestBodyBuilder] and [responseParser] must be supplied by the future
 * D-04 integration. When config is incomplete the adapter fails closed before
 * touching the transport or either injection point.
 */
class RemoteEmbeddingServiceAdapter(
    private val transport: HttpTransport,
    private val config: EmbeddingConfig,
    private val requestBodyBuilder: (texts: List<String>, config: EmbeddingConfig, json: Json) -> String,
    private val responseParser: (body: String, json: Json) -> EmbeddingBatch,
) : EmbeddingService {

    override val serviceName: String = "embedding_remote"

    override suspend fun embed(texts: List<String>): EmbeddingResult {
        if (texts.isEmpty()) {
            return EmbeddingResult.Failure(
                AppError(ErrorCode.INVALID_ARGUMENT, technicalDetail = "embedding_texts_empty"),
            )
        }
        if (!config.isConfigured) {
            return EmbeddingResult.Failure(
                AppError(
                    code = ErrorCode.EMBEDDING_NOT_CONFIGURED,
                    technicalDetail = "embedding_config_incomplete",
                ),
            )
        }

        val body = try {
            requestBodyBuilder(texts, config, JsonCodec.json)
        } catch (t: Throwable) {
            return EmbeddingResult.Failure(
                AppError(ErrorCode.PARSE_FAILURE, technicalDetail = "embedding_request_build_failed:${t.message}"),
            )
        }

        return when (
            val result = transport.execute(
                HttpRequest(
                    method = HttpMethod.POST,
                    url = config.endpoint,
                    headers = mapOf(
                        "Authorization" to "Bearer ${config.apiKey}",
                        "Content-Type" to "application/json",
                    ),
                    body = body,
                ),
            )
        ) {
            is NetworkResult.Success -> {
                if (result.statusCode !in 200..299) {
                    EmbeddingResult.Failure(
                        AppError(
                            code = ErrorCode.EMBEDDING_FAILURE,
                            technicalDetail = "embedding_http_${result.statusCode}",
                        ),
                    )
                } else {
                    try {
                        EmbeddingResult.Success(responseParser(result.body, JsonCodec.json))
                    } catch (t: Throwable) {
                        EmbeddingResult.Failure(
                            AppError(
                                code = ErrorCode.EMBEDDING_OUTPUT_INVALID,
                                technicalDetail = "embedding_response_parse_failed:${t.message}",
                            ),
                        )
                    }
                }
            }

            is NetworkResult.Failure -> EmbeddingResult.Failure(result.error)
        }
    }
}

/**
 * Default release adapter while D-04 is unconfirmed. It never invents a vendor
 * and never performs network I/O.
 */
class UnconfiguredEmbeddingServiceAdapter : EmbeddingService {
    override val serviceName: String = "embedding_unconfigured"

    override suspend fun embed(texts: List<String>): EmbeddingResult =
        EmbeddingResult.Failure(
            AppError(
                code = ErrorCode.EMBEDDING_NOT_CONFIGURED,
                technicalDetail = "embedding_supplier_pending_d04",
            ),
        )
}
