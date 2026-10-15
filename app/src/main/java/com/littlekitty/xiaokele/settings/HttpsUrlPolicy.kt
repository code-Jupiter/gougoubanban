package com.littlekitty.xiaokele.settings

import java.net.URI

/**
 * HTTPS URL policy shared by every user-configured endpoint setting
 * (MiniMax endpoint, DashScope host, QWeather host).
 *
 * This was previously named after the removed schedule feature; the rule itself
 * is generic and unchanged: a blank value means "use the built-in default", a
 * non-blank value must be an absolute HTTPS URL with a host and no user-info or
 * fragment.
 */
object HttpsUrlPolicy {
    fun normalizeOrNull(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return ""
        return runCatching {
            val uri = URI(value)
            require(uri.scheme.equals("https", ignoreCase = true))
            require(uri.host.isNullOrBlank().not())
            require(uri.userInfo == null)
            require(uri.fragment == null)
            uri.toString()
        }.getOrNull()
    }

    fun isValid(raw: String): Boolean = normalizeOrNull(raw) != null
}
