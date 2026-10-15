package com.littlekitty.xiaokele

import com.littlekitty.xiaokele.observability.PrivacyLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyLogTest {

    @Test
    fun redactRemovesSecretValuesAndUserContentBeforeDebugLogging() {
        val raw = """{"authorization":"sk-abcdef123456","token":"abcdef123456","content":"这是用户消息","payload":"mem-secret-memory"}"""
        val redacted = PrivacyLog.redact(raw)

        assertTrue(redacted.contains("sk-abcdef123456").not())
        assertTrue(redacted.contains("这是用户消息").not())
        assertTrue(redacted.contains("mem-secret-memory").not())
        assertEquals(4, redacted.split("[REDACTED]").size - 1)
    }

    @Test
    fun operationalSummaryWithoutSensitiveValuesIsUnchanged() {
        val summary = "startup_recovery expiredEvents=0 retryableMessages=1"
        assertEquals(summary, PrivacyLog.redact(summary))
    }
}
