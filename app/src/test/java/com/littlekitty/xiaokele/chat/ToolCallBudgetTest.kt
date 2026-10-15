package com.littlekitty.xiaokele.chat

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallBudgetTest {

    @Test
    fun capsRoundAndReturnsOneStructuredErrorWithoutExecutingOverflow() {
        val assistantMessage = assistantMessage(callCount = 4)
        val calls = (0 until 4).map { index -> call(index, assistantMessage) }
        val budget = ToolCallBudget(maxCallsPerRound = 3, maxCallsPerSession = 6)

        val selection = budget.select(calls)

        assertEquals(3, selection.executions.size)
        assertEquals(2, selection.executions.count { it.allowed })
        val rejection = selection.executions.single { !it.allowed }
        assertEquals(ToolCallBudget.ROUND_LIMIT_REACHED, rejection.rejectionCode)
        assertEquals(
            ToolCallBudget.ROUND_LIMIT_REACHED,
            rejection.structuredRejectionResult()["result"]
                ?.jsonObject
                ?.get("error_code")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(3, selection.assistantMessage["tool_calls"]?.jsonArray?.size)
    }

    @Test
    fun suppressesDuplicateAcrossToolRoundsAndKeepsTheContinuationStructured() {
        val assistantMessage = assistantMessage(callCount = 1)
        val budget = ToolCallBudget(maxCallsPerRound = 3, maxCallsPerSession = 6)

        val first = budget.select(listOf(call(0, assistantMessage)))
        assertTrue(first.executions.single().allowed)

        val duplicate = budget.select(listOf(call(0, assistantMessage)))
        assertEquals(1, duplicate.executions.size)
        assertFalse(duplicate.executions.single().allowed)
        assertEquals(ToolCallBudget.DUPLICATE_TOOL_CALL, duplicate.executions.single().rejectionCode)
        assertEquals(1, duplicate.assistantMessage["tool_calls"]?.jsonArray?.size)
    }

    private fun call(index: Int, assistantMessage: kotlinx.serialization.json.JsonObject): FrontChatToolCall =
        FrontChatToolCall(
            id = "call_${index + 1}",
            name = "query_weather",
            arguments = buildJsonObject { put("place_type", "PLACE_$index") },
            assistantMessage = assistantMessage,
            assistantToolCallIndex = index,
        )

    private fun assistantMessage(callCount: Int) = buildJsonObject {
        put("role", "assistant")
        put("tool_calls", buildJsonArray {
            repeat(callCount) { index ->
                add(buildJsonObject {
                    put("id", "call_${index + 1}")
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "query_weather")
                        put("arguments", "{\"place_type\":\"PLACE_$index\"}")
                    })
                })
            }
        })
    }
}
