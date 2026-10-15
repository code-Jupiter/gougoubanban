package com.littlekitty.xiaokele.core.common.id

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class EntityIdTest {

    @Test
    fun generatedIdHasExpectedShape() {
        val id = EntityId.generate("msg")
        assertTrue(id.value.startsWith("msg_"))
        assertEquals(36, id.value.length)
    }

    @Test
    fun generatedIdsAreUnique() {
        val a = EntityId.generate("evt")
        val b = EntityId.generate("evt")
        assertNotEquals(a, b)
    }

    @Test
    fun fromStringRoundTrips() {
        val id = EntityId.generate("todo")
        assertEquals(id, EntityId.fromString(id.value))
    }

    @Test
    fun invalidIdsAreRejected() {
        assertNull(EntityId.tryParse(""))
        assertNull(EntityId.tryParse("no-prefix"))
        assertNull(EntityId.tryParse("BadPrefix_0123456789abcdef0123456789abcdef"))
        assertNull(EntityId.tryParse("msg_short"))
        assertFalse(EntityId.isValid("msg_short"))
    }

    @Test
    fun typeSpecificValidationRejectsAnotherEntityPrefix() {
        val todoId = "todo_11111111111111111111111111111111"
        assertTrue(EntityId.isValidForPrefix(todoId, "todo"))
        assertFalse(EntityId.isValidForPrefix(todoId, "trg"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPrefixIsRejected() {
        EntityId.generate("Bad-Prefix")
    }

    @Test
    fun randomSourceIsUsed() {
        val fixed = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val id = EntityId.generate("evt", random = { fixed })
        assertEquals("evt_11111111111111111111111111111111", id.value)
    }
}
