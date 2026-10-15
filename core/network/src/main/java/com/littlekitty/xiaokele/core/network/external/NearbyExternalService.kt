package com.littlekitty.xiaokele.core.network.external

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.network.ServiceAdapter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Legacy nearby-candidate contract retained for historical event and debug
 * regression coverage. Current production local-life search does not use it.
 */
@Serializable
data class NearbyCandidateDto(
    @SerialName("merchant_id")
    val merchantId: String,
    val name: String,
    val category: String? = null,
    @SerialName("representative_dishes")
    val representativeDishes: List<String> = emptyList(),
    @SerialName("price_hint")
    val priceHint: String? = null,
    @SerialName("distance_meters")
    val distanceMeters: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val offer: NearbyOfferDto? = null,
    @SerialName("detail_url")
    val detailUrl: String? = null,
    val source: String,
    @SerialName("published_at")
    val publishedAt: String? = null,
    @SerialName("valid_until")
    val validUntil: String? = null,
    val verified: Boolean = false,
)

@Serializable
data class NearbyOfferDto(
    val title: String? = null,
    val description: String? = null,
    @SerialName("valid_until")
    val validUntil: String? = null,
)

data class NearbySearchRequest(
    val placeType: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Int? = null,
    @SerialName("meal_window_label")
    val mealWindowLabel: String? = null,
)

sealed interface NearbySearchResult {
    data class Success(val candidates: List<NearbyCandidateDto>) : NearbySearchResult
    data object Empty : NearbySearchResult
    data class Failure(val error: AppError) : NearbySearchResult
}

interface NearbyCandidateService : ServiceAdapter {
    suspend fun search(request: NearbySearchRequest): NearbySearchResult
}
