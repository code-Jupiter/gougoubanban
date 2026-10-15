package com.littlekitty.xiaokele.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SensitiveWordRedactorTest {

    @Test
    fun parsesCommonSeparatorsAndRedactsNestedStringValues() {
        val redactor = SensitiveWordRedactor.fromInput("手机号，Alice\n地址")
        val source = Json.parseToJsonElement(
            """{"message":"Alice 的手机号是 13800138000","nested":["地址：杭州"]}""",
        )

        val result = redactor.redact(source).jsonObject

        assertEquals("* 的*是 13800138000", result["message"]?.jsonPrimitive?.content)
        assertEquals("*：杭州", result["nested"]?.jsonArray?.first()?.jsonPrimitive?.content)
    }

    @Test
    fun doesNotPutSensitiveWordsIntoJsonKeysOrChangeNonStringValues() {
        val redactor = SensitiveWordRedactor.fromInput("secret")
        val source = Json.parseToJsonElement("""{"secret":"secret value","count":2}""")

        val result = redactor.redact(source).jsonObject

        assertEquals("* value", result["secret"]?.jsonPrimitive?.content)
        assertEquals("2", result["count"]?.jsonPrimitive?.content)
    }
}
