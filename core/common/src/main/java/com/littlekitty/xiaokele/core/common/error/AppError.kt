package com.littlekitty.xiaokele.core.common.error

/**
 * Product rule: user-visible UI must never expose supplier/internal error details
 * (requirement 46, 51). [AppError] therefore separates a stable machine [code]
 * from a user-safe [userMessage] and a non-user-facing [technicalDetail].
 */
data class AppError(
    val code: ErrorCode,
    val userMessage: String? = code.defaultUserMessage,
    val technicalDetail: String? = null,
) {
    fun safeUserMessage(): String = userMessage ?: code.defaultUserMessage ?: "出现了一点问题"

    fun withTechnicalDetail(detail: String): AppError = copy(technicalDetail = detail)
}

enum class ErrorCode(
    val defaultUserMessage: String?,
) {
    UNKNOWN("出现了一点问题"),
    INVALID_ARGUMENT("输入的内容不正确"),
    NOT_IMPLEMENTED("这个功能还没有准备好"),
    NETWORK_UNAVAILABLE(null),
    TIMEOUT(null),
    PARSE_FAILURE(null),
    LOCAL_STORAGE_FAILURE(null),
    MODEL_NOT_CONFIGURED("回复功能还没准备好"),
    MODEL_FAILURE("刚才没回应上，请稍后再试"),
    MODEL_OUTPUT_INVALID("这次没有想好怎么回复，请再试一次"),
    MEMORY_EVALUATOR_NOT_CONFIGURED(null),
    MEMORY_EVALUATION_INVALID(null),
    EMBEDDING_NOT_CONFIGURED(null),
    EMBEDDING_FAILURE(null),
    EMBEDDING_OUTPUT_INVALID(null),
    SHIFT_NOT_CONFIGURED(null),
    SHIFT_DATA_FAILURE(null),
    SHIFT_INVALID_RESPONSE(null),
    WEATHER_NOT_CONFIGURED(null),
    WEATHER_FAILURE(null),
    WEATHER_INVALID_RESPONSE(null),
    NEARBY_NOT_CONFIGURED(null),
    NEARBY_FAILURE(null),
    NEARBY_INVALID_RESPONSE(null),
    LOCATION_CONFIG_INVALID(null),
    LOCATION_UNAVAILABLE(null),
}
