package com.littlekitty.xiaokele.discovery

import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.settings.ByokRuntimeConfig

/** Local-life discovery uses only MiniMax official coding-plan search. */
class ProfiledDiscoveryFeedService(
    transport: HttpTransport,
    private val settings: ByokRuntimeConfig,
    diagnosticsLogger: com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger? = null,
) : DiscoveryFeedService {
    private val delegate = MiniMaxDiscoveryFeedService(transport, settings)

    override suspend fun fetch(
        areas: Set<String>,
        categories: Set<String>,
        since: String?,
    ): DiscoveryFeedResult {
        if (!settings.webSearchEnabled()) {
            return DiscoveryFeedResult.Failure(
                AppError(
                    code = ErrorCode.MODEL_NOT_CONFIGURED,
                    userMessage = "联网搜索已在设置中关闭；需要时可在“联网搜索”中重新启用",
                    technicalDetail = "web_search_disabled_by_user",
                ),
            )
        }
        return delegate.fetch(areas, categories, since)
    }
}
