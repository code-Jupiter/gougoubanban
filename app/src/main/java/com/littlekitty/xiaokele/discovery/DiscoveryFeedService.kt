package com.littlekitty.xiaokele.discovery

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DiscoveryItem(
    val id: String,
    val area: String,
    val category: String,
    val title: String,
    val summary: String,
    @SerialName("source_name")
    val sourceName: String,
    @SerialName("source_url")
    val sourceUrl: String,
    @SerialName("published_at")
    val publishedAt: String? = null,
    @SerialName("event_start")
    val eventStart: String? = null,
    @SerialName("event_end")
    val eventEnd: String? = null,
    @SerialName("discovered_at")
    val discoveredAt: String? = null,
    val score: Double = 0.0,
)

sealed interface DiscoveryFeedResult {
    data class Success(val items: List<DiscoveryItem>, val generatedAt: String?) : DiscoveryFeedResult
    data object Empty : DiscoveryFeedResult
    data class Failure(val error: AppError) : DiscoveryFeedResult
}

interface DiscoveryFeedService {
    suspend fun fetch(areas: Set<String>, categories: Set<String>, since: String? = null): DiscoveryFeedResult
}
