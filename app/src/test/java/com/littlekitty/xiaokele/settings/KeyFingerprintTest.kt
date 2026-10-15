package com.littlekitty.xiaokele.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFingerprintTest {

    @Test
    fun fingerprintNeverContainsFullKey() {
        val raw = "sk-test-abcdef-1234567890"
        val fingerprint = KeyFingerprint.of(raw)
        assertTrue(fingerprint.contains(raw).not())
        assertTrue(fingerprint.endsWith("7890"))
    }

    @Test
    fun shortKeyIsMaskedWithoutUsefulLeak() {
        val fingerprint = KeyFingerprint.of("abc")
        assertEquals("末3位", fingerprint)
    }
}
