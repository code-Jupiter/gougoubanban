package com.littlekitty.xiaokele.settings

import android.content.Context
import com.littlekitty.xiaokele.core.network.embedding.EmbeddingConfig
import com.littlekitty.xiaokele.core.network.embedding.dashScopeBaseUrl
import com.littlekitty.xiaokele.core.network.embedding.dashScopeEmbeddingEndpoint

/** Runtime DashScope embedding settings. The API key uses its own Keystore alias. */
interface BailianRuntimeConfig {
    fun bailianApiHost(): String
    fun bailianApiKeyOrNull(): String?
    fun bailianChatEndpoint(): String = dashScopeChatCompletionEndpoint(bailianApiHost())
    fun bailianConfigurationVersion(): Long = 0L
}

class EmbeddingSettingsStore(context: Context) : BailianRuntimeConfig {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val vaultPrefs = context.getSharedPreferences(VAULT_PREFS_NAME, Context.MODE_PRIVATE)
    private val cipher = AndroidKeystoreApiKeyCipher(KEY_ALIAS)

    fun apiHost(): String = prefs.getString(KEY_API_HOST, "").orEmpty()

    override fun bailianApiHost(): String = apiHost()
    override fun bailianApiKeyOrNull(): String? = loadApiKeyOrNull()
    override fun bailianConfigurationVersion(): Long = prefs.getLong(KEY_CONFIGURATION_VERSION, 0L)

    fun setApiHost(value: String): Boolean {
        val host = HttpsUrlPolicy.normalizeOrNull(value) ?: return false
        return prefs.edit()
            .putString(KEY_API_HOST, host.trimEnd('/'))
            .putLong(KEY_CONFIGURATION_VERSION, bailianConfigurationVersion() + 1L)
            .commit()
    }

    /**
     * Memory-vector retrieval is optional. When disabled the caller receives
     * an unconfigured service and continues through the existing lexical
     * memory-search fallback without issuing embedding requests.
     */
    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(value: Boolean): Boolean = prefs.edit()
        .putBoolean(KEY_ENABLED, value)
        .putLong(KEY_CONFIGURATION_VERSION, bailianConfigurationVersion() + 1L)
        .commit()

    fun model(): String = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setModel(value: String): Boolean {
        return prefs.edit()
            .putString(KEY_MODEL, value.trim())
            .putLong(KEY_CONFIGURATION_VERSION, bailianConfigurationVersion() + 1L)
            .commit()
    }

    fun saveApiKey(rawKey: String): Boolean = runCatching {
        val normalized = rawKey.trim().trim('"')
        require(normalized.isNotEmpty())
        vaultPrefs.edit()
            .putString(KEY_ENCRYPTED_API_KEY, cipher.encrypt(normalized))
            .commit().also {
                if (it) prefs.edit().putLong(KEY_CONFIGURATION_VERSION, bailianConfigurationVersion() + 1L).commit()
            }
    }.isSuccess

    fun loadApiKeyOrNull(): String? = runCatching {
        val stored = vaultPrefs.getString(KEY_ENCRYPTED_API_KEY, null) ?: return null
        cipher.decrypt(stored)
    }.getOrElse {
        deleteApiKey()
        null
    }

    fun fingerprint(): String? = loadApiKeyOrNull()?.let(KeyFingerprint::of)

    fun deleteApiKey() {
        vaultPrefs.edit().remove(KEY_ENCRYPTED_API_KEY).commit()
        cipher.deleteKeyMaterial()
        prefs.edit().putLong(KEY_CONFIGURATION_VERSION, bailianConfigurationVersion() + 1L).commit()
    }

    fun embeddingConfig(): EmbeddingConfig = if (!isEnabled()) {
        EmbeddingConfig.UNCONFIGURED
    } else {
        EmbeddingConfig(
            endpoint = dashScopeEmbeddingEndpoint(apiHost()),
            apiKey = loadApiKeyOrNull().orEmpty(),
            model = model(),
        )
    }

    companion object {
        const val PREFS_NAME = "little_kitty_embedding_settings"
        const val VAULT_PREFS_NAME = "little_kitty_embedding_vault"
        const val KEY_API_HOST = "api_host"
        const val KEY_MODEL = "model"
        const val KEY_ENABLED = "embedding_enabled"
        const val KEY_ENCRYPTED_API_KEY = "encrypted_api_key_v1"
        const val KEY_CONFIGURATION_VERSION = "configuration_version"
        const val KEY_ALIAS = "little_kitty_embedding_aes_gcm_v1"
        const val DEFAULT_MODEL = "qwen3.7-text-embedding"
    }
}

fun dashScopeChatCompletionEndpoint(apiHost: String): String {
    val normalized = dashScopeBaseUrl(apiHost)
    return if (normalized.endsWith("/compatible-mode/v1/chat/completions")) {
        normalized
    } else {
        "$normalized/compatible-mode/v1/chat/completions"
    }
}
