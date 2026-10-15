package com.littlekitty.xiaokele.external

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.event.EventDispatchResult

data class ExternalSourceReport(
    val outcome: String,
    val error: AppError? = null,
    val cacheWritten: Boolean = false,
    val skippedStaleCache: Boolean = false,
    val dispatchResults: List<EventDispatchResult> = emptyList(),
    val generatedEventKeys: List<String> = emptyList(),
)

data class WeatherRefreshReport(
    val outcome: String,
    val error: AppError? = null,
    val cacheWritten: Boolean = false,
    val skippedStaleCache: Boolean = false,
    val skippedFailureCooldown: Boolean = false,
    val dispatchResults: List<EventDispatchResult> = emptyList(),
    val generatedEventKeys: List<String> = emptyList(),
)

data class NearbyCandidateReport(
    val outcome: String,
    val error: AppError? = null,
    val cacheWritten: Boolean = false,
    val skippedStaleCache: Boolean = false,
    val candidatesCached: Int = 0,
    val dispatchResults: List<EventDispatchResult> = emptyList(),
    val generatedEventKeys: List<String> = emptyList(),
)

data class LocationUpdateReport(
    val outcome: String,
    val error: AppError? = null,
    val cacheWritten: Boolean = false,
    val skippedStaleCache: Boolean = false,
    val dispatchResults: List<EventDispatchResult> = emptyList(),
    val generatedEventKeys: List<String> = emptyList(),
)
