package com.littlekitty.xiaokele.discovery

import com.littlekitty.xiaokele.core.database.entity.TodoEntity
import com.littlekitty.xiaokele.core.database.state.TodoStatuses
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryMentionSelectionTest {

    @Test
    fun marksOnlyTheExactItemLinkedToTheVisibleChatMessage() {
        val candidates = listOf(item("food-1"), item("event-2"))

        val selected = selectDiscoveryItemsToMark(
            messageReachedChat = true,
            candidates = candidates,
            todos = listOf(todoWithItemId("event-2")),
        )

        assertEquals(listOf("event-2"), selected.map { it.id })
    }

    @Test
    fun marksNothingWhenBudgetOrDeliveryProducedNoChatMessage() {
        val selected = selectDiscoveryItemsToMark(
            messageReachedChat = false,
            candidates = listOf(item("food-1")),
            todos = listOf(todoWithItemId("food-1")),
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun marksNothingWhenTheModelDidNotPreserveAnExactItemId() {
        val todo = TodoEntity(
            id = "todo_missing_item",
            type = "DISCUSS_LATER",
            payloadJson = buildJsonObject { put("source_url", "https://example.com/food-1") }.toString(),
            status = TodoStatuses.READY,
            createdAt = 1L,
            updatedAt = 1L,
        )

        val selected = selectDiscoveryItemsToMark(
            messageReachedChat = true,
            candidates = listOf(item("food-1")),
            todos = listOf(todo),
        )

        assertTrue(selected.isEmpty())
    }

    private fun item(id: String) = DiscoveryItem(
        id = id,
        area = "上海",
        category = "food",
        title = id,
        summary = "summary",
        sourceName = "test",
        sourceUrl = "https://example.com/$id",
    )

    private fun todoWithItemId(itemId: String) = TodoEntity(
        id = "todo_$itemId",
        type = "DISCUSS_LATER",
        payloadJson = buildJsonObject { put("item_id", itemId) }.toString(),
        status = TodoStatuses.READY,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
