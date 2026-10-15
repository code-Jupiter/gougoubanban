package com.littlekitty.xiaokele.settings

import com.littlekitty.xiaokele.core.network.embedding.DASHSCOPE_DEFAULT_HOST
import com.littlekitty.xiaokele.core.network.embedding.dashScopeEmbeddingEndpoint
import org.junit.Assert.assertEquals
import org.junit.Test

class BailianHostDefaultTest {
    @Test
    fun blankHostUsesTheDocumentedPublicDashScopeEndpoints() {
        assertEquals(
            ByokSettingsStore.BAILIAN_ENDPOINT,
            dashScopeChatCompletionEndpoint(""),
        )
        assertEquals(
            "$DASHSCOPE_DEFAULT_HOST/api/v1/services/embeddings/text-embedding/text-embedding",
            dashScopeEmbeddingEndpoint(""),
        )
    }

    @Test
    fun workspaceHostStillBuildsTheCompatibleChatEndpoint() {
        assertEquals(
            "https://workspace.example/compatible-mode/v1/chat/completions",
            dashScopeChatCompletionEndpoint("workspace.example"),
        )
    }
}
