package com.littlekitty.xiaokele.discovery

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.network.HttpMethod
import com.littlekitty.xiaokele.core.network.HttpRequest
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.NetworkResult
import com.littlekitty.xiaokele.provider.MiniMaxDirectConfig
import com.littlekitty.xiaokele.provider.isSafeHttpUrl
import com.littlekitty.xiaokele.settings.ByokRuntimeConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Direct MiniMax Token Plan web-search client for local-life results. */
class MiniMaxDiscoveryFeedService(
    private val transport: HttpTransport,
    private val settings: ByokRuntimeConfig,
) : DiscoveryFeedService {

    override suspend fun fetch(
        areas: Set<String>,
        categories: Set<String>,
        since: String?,
    ): DiscoveryFeedResult {
        if (!settings.officialModelsValidated() && !settings.officialLegacyCompatibilityAllowed()) {
            return failure(
                ErrorCode.MODEL_NOT_CONFIGURED,
                "请先完成 MiniMax M2 与 M2-her 双模型能力测试后再使用本地生活搜索",
                "official_discovery_not_validated",
            )
        }
        val key = settings.loadKeyOrNull().orEmpty()
        val configuredEndpoint = settings.endpoint().ifBlank {
            if (settings.region() == "INTL") MiniMaxDirectConfig.INTL_ENDPOINT
            else MiniMaxDirectConfig.CN_ENDPOINT
        }
        if (key.isBlank() || configuredEndpoint.isBlank()) {
            return failure(ErrorCode.MODEL_NOT_CONFIGURED, "请先在设置中填写并保存 MiniMax Key", "byok_not_configured")
        }

        val normalizedCategories = CATEGORY_ORDER.filter(categories::contains)
        if (normalizedCategories.isEmpty()) return DiscoveryFeedResult.Empty
        // Discovery is a paid model entry point too. Reserve the durable
        // rolling/day generation slot before the search request; a denied
        // reservation must not fall through to an unmetered MiniMax call.
        if (!settings.reserveGenerationRequest(isPro = false)) {
            return failure(
                ErrorCode.MODEL_FAILURE,
                "本轮模型请求达到滚动或每日额度，请稍后重试",
                "persistent_generation_quota_exhausted",
            )
        }
        val query = buildSearchQuery(areas, normalizedCategories, since)
        val searchEndpoint = codingPlanSearchEndpoint(configuredEndpoint)
        return when (val result = transport.execute(
            HttpRequest(
                method = HttpMethod.POST,
                url = searchEndpoint,
                headers = mapOf(
                    "Authorization" to "Bearer $key",
                    "Content-Type" to "application/json",
                ),
                body = JsonCodec.encode(SearchRequest(query)),
            ),
        )) {
            is NetworkResult.Failure -> failure(
                result.error.code,
                "网络暂时不可用，请稍后再试",
                result.error.technicalDetail,
            )

            is NetworkResult.Success -> when {
                result.statusCode == 401 || result.statusCode == 403 ->
                    failure(ErrorCode.MODEL_FAILURE, "MiniMax Key 无效或没有网页搜索权限，请到设置中更新", "minimax_search_auth_${result.statusCode}")
                result.statusCode == 429 ->
                    failure(ErrorCode.MODEL_FAILURE, "MiniMax 请求太频繁，请稍后重试", "minimax_search_rate_limited")
                result.statusCode !in 200..299 ->
                    failure(ErrorCode.MODEL_FAILURE, "MiniMax 搜索暂时不可用，请稍后重试", "minimax_search_http_${result.statusCode}")
                else -> decode(result.body, areas, normalizedCategories, Instant.now().toString())
            }
        }
    }

    private fun decode(
        body: String,
        areas: Set<String>,
        categories: List<String>,
        generatedAt: String,
    ): DiscoveryFeedResult = runCatching {
        val response = JsonCodec.decode<SearchResponse>(body)
        val status = response.baseResp?.statusCode ?: 0
        if (status != 0) error("minimax_search_base_resp_$status")
        val category = categories.singleOrNull() ?: categories.joinToString("+")
        val items = response.organic
            .filter { it.isSafeAndComplete() }
            .distinctBy { it.link }
            .take(10)
            .map { it.toDiscoveryItem(areas, category, generatedAt) }
        if (items.isEmpty()) DiscoveryFeedResult.Empty
        else DiscoveryFeedResult.Success(items, generatedAt)
    }.getOrElse { throwable ->
        failure(ErrorCode.NEARBY_FAILURE, "本地生活数据格式不正确，请稍后重试", "minimax_search_json_invalid:${throwable.message}")
    }

    private fun SearchItem.isSafeAndComplete(): Boolean =
        title.isNotBlank() && snippet.isNotBlank() && isSafeHttpUrl(link)

    private fun SearchItem.toDiscoveryItem(
        areas: Set<String>,
        category: String,
        discoveredAt: String,
    ): DiscoveryItem = DiscoveryItem(
        id = UUID.nameUUIDFromBytes("$category|$link".toByteArray(Charsets.UTF_8)).toString(),
        area = inferArea(areas, "$title $snippet"),
        category = category,
        title = title.trim(),
        summary = snippet.trim(),
        sourceName = runCatching { java.net.URI(link).host.orEmpty() }
            .getOrDefault("")
            .ifBlank { "MiniMax 网页搜索" },
        sourceUrl = link.trim(),
        publishedAt = date?.trim()?.takeIf(String::isNotBlank),
        discoveredAt = discoveredAt,
    )

    private fun buildSearchQuery(areas: Set<String>, categories: List<String>, since: String?): String {
        val areaText = areas.map(::areaSearchKeywords).distinct().joinToString(" ")
        val categoryText = categories.map(::categorySearchKeyword).distinct().joinToString(" ")
        val dateText = since?.takeIf(String::isNotBlank)
            ?: LocalDate.now(ZoneId.of("Asia/Shanghai")).year.toString()
        return "$areaText $categoryText $dateText 官方"
    }

    private fun codingPlanSearchEndpoint(endpoint: String): String {
        val normalized = endpoint.trim().trimEnd('/')
        val host = normalized.substringBefore("/v1/")
        return if (host == normalized) "$normalized/v1/coding_plan/search"
        else "$host/v1/coding_plan/search"
    }

    private fun failure(code: ErrorCode, message: String, detail: String?): DiscoveryFeedResult.Failure =
        DiscoveryFeedResult.Failure(AppError(code = code, userMessage = message, technicalDetail = detail))

    private fun inferArea(areas: Set<String>, text: String): String {
        val matched = areas.firstOrNull { area -> areaSearchTerms(area).any(text::contains) }
        return matched ?: areas.firstOrNull().orEmpty()
    }

    private fun areaSearchKeywords(area: String): String = when (area) {
        "liandu" -> "丽水 莲都"
        "hangzhou" -> "杭州"
        else -> area
    }

    private fun areaSearchTerms(area: String): List<String> = when (area) {
        "liandu" -> listOf("莲都", "丽水")
        "hangzhou" -> listOf("杭州")
        else -> listOf(area)
    }

    private fun categorySearchKeyword(category: String): String = when (category) {
        "food" -> "美食"
        "events" -> "活动"
        else -> category
    }

    @Serializable
    private data class SearchRequest(val q: String)

    @Serializable
    private data class SearchResponse(
        val organic: List<SearchItem> = emptyList(),
        @SerialName("base_resp") val baseResp: SearchBaseResponse? = null,
    )

    @Serializable
    private data class SearchBaseResponse(@SerialName("status_code") val statusCode: Int = 0)

    @Serializable
    private data class SearchItem(
        val title: String = "",
        val link: String = "",
        val snippet: String = "",
        val date: String? = null,
    )

    companion object {
        private val CATEGORY_ORDER = listOf("food", "events")

        internal fun searchEndpointForTests(endpoint: String): String = codingPlanSearchEndpointStatic(endpoint)

        private fun codingPlanSearchEndpointStatic(endpoint: String): String {
            val normalized = endpoint.trim().trimEnd('/')
            val host = normalized.substringBefore("/v1/")
            return if (host == normalized) "$normalized/v1/coding_plan/search"
            else "$host/v1/coding_plan/search"
        }
    }
}
