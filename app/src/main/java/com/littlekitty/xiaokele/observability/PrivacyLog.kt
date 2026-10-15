package com.littlekitty.xiaokele.observability

import android.util.Log
import com.littlekitty.xiaokele.BuildConfig

/**
 * Release-safe logging boundary. Debug builds may log redacted operational
 * summaries (failure codes, retry timestamps, counts); release builds never
 * emit user message content, memory content, keys or tokens. Persistent
 * failure reasons are kept in the Room audit columns instead of logcat.
 */
object PrivacyLog {

    private const val TAG = "LittleKitty"

    fun debug(operationalSummary: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, redact(operationalSummary))
        }
    }

    fun warn(operationalSummary: String) {
        if (BuildConfig.DEBUG) {
            Log.w(TAG, redact(operationalSummary))
        }
    }

    fun redact(raw: String): String {
        var result = raw
        SECRET_KEY_PATTERN.findAll(raw).forEach { match ->
            result = result.replace(match.groupValues[2], "[REDACTED]")
        }
        USER_CONTENT_PATTERN.findAll(raw).forEach { match ->
            result = result.replace(match.groupValues[2], "[REDACTED]")
        }
        return result
    }

    private val SECRET_KEY_PATTERN = Regex(
        "(?i)(authorization|token|api[_-]?key|secret|password)" +
            """["'\s:=]+["']?([A-Za-z0-9._\-]{6,})""",
    )

    private val USER_CONTENT_PATTERN = Regex(
        """("(?:content|memory|user_message|payload)"\s*:\s*")([^"]{4,})"""",
        RegexOption.IGNORE_CASE,
    )
}
