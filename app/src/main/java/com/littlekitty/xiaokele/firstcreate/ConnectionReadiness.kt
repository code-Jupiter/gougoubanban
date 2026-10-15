package com.littlekitty.xiaokele.firstcreate

import com.littlekitty.xiaokele.settings.ByokSettingsStore
import com.littlekitty.xiaokele.settings.EmbeddingSettingsStore

/**
 * Whether a provider is usable right now.
 *
 * "Has a key typed in" is deliberately not enough: a provider only counts as
 * ready once it has a saved credential *and* that exact credential passed the
 * real capability check. This is what stops a typo'd or unverified key from
 * letting the user into a flow that will fail on the first message.
 */
data class ProviderConnection(
    val provider: SelectedGenerationProvider,
    val label: String,
    val hasKey: Boolean,
    val validated: Boolean,
) {
    val ready: Boolean get() = hasKey && validated
}

data class ConnectionSnapshot(
    val connections: List<ProviderConnection>,
) {
    val readyProviders: List<SelectedGenerationProvider>
        get() = connections.filter { it.ready }.map { it.provider }

    val hasUsableProvider: Boolean get() = readyProviders.isNotEmpty()

    fun connectionFor(provider: SelectedGenerationProvider): ProviderConnection? =
        connections.firstOrNull { it.provider == provider }
}

/**
 * Reads the live connection state from the credential stores.
 *
 * It never invents a state and never falls back across providers: each provider
 * is judged only on its own key and its own validation marker.
 */
class ConnectionReadinessReader(
    private val byokSettingsStore: ByokSettingsStore,
    private val embeddingSettingsStore: EmbeddingSettingsStore,
) {

    fun read(): ConnectionSnapshot = ConnectionSnapshot(
        connections = SelectedGenerationProvider.entries.map { provider ->
            when (provider) {
                SelectedGenerationProvider.MINIMAX_OFFICIAL -> ProviderConnection(
                    provider = provider,
                    label = LABEL_MINIMAX,
                    hasKey = byokSettingsStore.hasSavedKey(),
                    validated = byokSettingsStore.officialModelsValidated(),
                )

                SelectedGenerationProvider.BAILIAN -> ProviderConnection(
                    provider = provider,
                    label = LABEL_BAILIAN,
                    // Bailian chat and embedding intentionally share one
                    // credential, so the embedding store is the single source.
                    hasKey = embeddingSettingsStore.loadApiKeyOrNull().isNullOrBlank().not(),
                    validated = byokSettingsStore.bailianModelsValidated(),
                )
            }
        },
    )

    /**
     * The models a provider may be asked to use, restricted to what the app is
     * willing to send. An Agent route can only name one of these.
     */
    fun modelsFor(provider: SelectedGenerationProvider): List<String> = when (provider) {
        SelectedGenerationProvider.MINIMAX_OFFICIAL -> listOf(
            ByokSettingsStore.DEFAULT_MODEL,
            ByokSettingsStore.MINIMAX_M2_MODEL,
            ByokSettingsStore.OFFICIAL_HER_MODEL,
        )

        SelectedGenerationProvider.BAILIAN -> listOf(
            ByokSettingsStore.DEFAULT_BAILIAN_FLASH_MODEL,
            ByokSettingsStore.DEFAULT_BAILIAN_PRO_MODEL,
        )
    }

    companion object {
        const val LABEL_MINIMAX = "MiniMax 官方"
        const val LABEL_BAILIAN = "阿里百炼 DeepSeek"
    }
}
