package com.littlekitty.xiaokele.core.common.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AppErrorTest {

    @Test
    fun safeMessageNeverUsesTechnicalDetail() {
        val error = AppError(
            code = ErrorCode.NETWORK_UNAVAILABLE,
            userMessage = "网络暂时不可用",
            technicalDetail = "connect timeout to internal-host:443",
        )
        assertEquals("网络暂时不可用", error.safeUserMessage())
    }

    @Test
    fun fallsBackToCodeMessage() {
        assertEquals("出现了一点问题", AppError(ErrorCode.UNKNOWN).safeUserMessage())
    }

    @Test
    fun nullableUserMessageFallsBack() {
        assertEquals(
            "出现了一点问题",
            AppError(ErrorCode.NETWORK_UNAVAILABLE, userMessage = null).safeUserMessage(),
        )
    }

    @Test
    fun technicalDetailIsTracked() {
        val detail = "EOF at line 2"
        val error = AppError(ErrorCode.PARSE_FAILURE).withTechnicalDetail(detail)
        assertEquals(detail, error.technicalDetail)
        assertNotNull(error.code)
    }
}
