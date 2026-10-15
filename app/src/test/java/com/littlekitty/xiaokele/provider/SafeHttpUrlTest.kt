package com.littlekitty.xiaokele.provider

import org.junit.Assert.assertTrue
import org.junit.Test

class SafeHttpUrlTest {

    @Test
    fun acceptsOnlyHttpAndHttpsUrlsWithHosts() {
        assertTrue(isSafeHttpUrl("https://example.com/a"))
        assertTrue(isSafeHttpUrl("http://10.0.2.2:8000/a"))
        assertTrue(isSafeHttpUrl("javascript:alert(1)").not())
        assertTrue(isSafeHttpUrl("file:///etc/passwd").not())
        assertTrue(isSafeHttpUrl("https:///missing-host").not())
    }
}
