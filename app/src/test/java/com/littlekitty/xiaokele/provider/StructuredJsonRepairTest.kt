package com.littlekitty.xiaokele.provider

import org.junit.Assert.*
import org.junit.Test

class StructuredJsonRepairTest {
    @Test fun trailingCommaOnlyCanBeRepairedWithoutChangingEscapedText() {
        assertTrue(StructuredJsonRepair.lossless("""{"a":["x,}",1,],}""", """{"a":["x,}",1]}"""))
    }

    @Test fun structureAndFieldOwnershipCannotChangeEvenWithIdenticalWords() {
        assertFalse(StructuredJsonRepair.lossless("""{"a":[{"b":1},],}""", """{"a":{"b":[1]}}"""))
        assertFalse(StructuredJsonRepair.lossless("""{"a":{"b":1,"c":2},}""", """{"a":{"b":1},"c":2}"""))
    }

    @Test fun ambiguousMissingDelimitersAndDuplicateKeysAreNotAccepted() {
        assertFalse(StructuredJsonRepair.lossless("""{"a" "b"}""", """{"a":"b"}"""))
        assertFalse(StructuredJsonRepair.validObject("""{"a":1,"a":2}"""))
        assertFalse(StructuredJsonRepair.validObject("""{"a":1,"\u0061":2}"""))
        assertTrue(StructuredJsonRepair.validObject("""{"a":{"a":1},"b":2}"""))
    }
}
