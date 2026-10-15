package com.littlekitty.xiaokele.settings

import android.content.Context

/** Runtime QWeather settings. The API key is encrypted with a separate Keystore alias. */
class QWeatherSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val vaultPrefs = context.getSharedPreferences(VAULT_PREFS_NAME, Context.MODE_PRIVATE)
    private val cipher = AndroidKeystoreApiKeyCipher(KEY_ALIAS)

    fun apiHost(): String = prefs.getString(KEY_API_HOST, "").orEmpty()

    fun setApiHost(value: String): Boolean {
        val host = HttpsUrlPolicy.normalizeOrNull(value) ?: return false
        return prefs.edit().putString(KEY_API_HOST, host.trimEnd('/')).commit()
    }

    fun hasSavedKey(): Boolean = vaultPrefs.contains(KEY_ENCRYPTED_API_KEY)

    fun saveApiKey(rawKey: String): Boolean = runCatching {
        val normalized = rawKey.trim().trim('"')
        require(normalized.isNotEmpty())
        vaultPrefs.edit()
            .putString(KEY_ENCRYPTED_API_KEY, cipher.encrypt(normalized))
            .commit()
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
    }

    companion object {
        const val PREFS_NAME = "little_kitty_qweather_settings"
        const val VAULT_PREFS_NAME = "little_kitty_qweather_vault"
        const val KEY_API_HOST = "api_host"
        const val KEY_ENCRYPTED_API_KEY = "encrypted_api_key_v1"
        const val KEY_ALIAS = "little_kitty_qweather_aes_gcm_v1"
    }
}
