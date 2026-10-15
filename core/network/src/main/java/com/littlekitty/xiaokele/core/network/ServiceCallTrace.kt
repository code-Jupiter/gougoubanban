package com.littlekitty.xiaokele.core.network

/**
 * Non-secret wire facts captured for a user-exportable diagnostic trace.
 * Authorization headers are deliberately not part of this model.
 */
data class ServiceCallTrace(
    val serviceName: String,
    val requestBody: String? = null,
    val rawResponseBody: String? = null,
    val responseStatusCode: Int? = null,
    val latencyMillis: Long? = null,
    /** Non-secret model provenance; kept here so diagnostics can persist it without an app/database dependency. */
    val provider: String? = null,
    val modelId: String? = null,
    val task: String? = null,
    val promptVersion: String? = null,
    val routeVersion: Long? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
    val costStatus: String? = null,
)
