package com.littlekitty.xiaokele.debug

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class DebugRequestBodyValidatorTest {

    private val validBody = """
        {
          "model": "MiniMax-M2",
          "messages": [{"role":"user","content":"hello"}],
          "temperature": 0.3
        }
    """.trimIndent()

    @Test
    fun validBodyPassesAndEditedBodyRemainsOrdinaryJson() {
        assertTrue(DebugRequestBodyValidator.validate(validBody, "MiniMax-M2").isEmpty())
        assertTrue(DebugRequestBodyValidator.validate(validBody.replace("hello", "edited"), "MiniMax-M2").isEmpty())
    }

    @Test
    fun sensitiveFieldsAreRejected() {
        val body = validBody.replace("\"temperature\": 0.3", "\"authorization\": \"Bearer secret\"")
        assertFalse(DebugRequestBodyValidator.validate(body, "MiniMax-M2").isEmpty())
    }

    @Test
    fun wrongModelAndMissingMessagesAreRejected() {
        val wrongModel = validBody.replace("MiniMax-M2", "other-model")
        assertFalse(DebugRequestBodyValidator.validate(wrongModel, "MiniMax-M2").isEmpty())
        val missingMessages = validBody.replace(Regex("\\s*\"messages\":.*?,\\s*"), "")
        assertFalse(DebugRequestBodyValidator.validate(missingMessages, "MiniMax-M2").isEmpty())
    }
}
