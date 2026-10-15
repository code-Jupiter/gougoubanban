package com.littlekitty.xiaokele.core.common.id

import java.util.Locale
import java.util.UUID

/**
 * Unified entity id for every locally persisted entity and normalized event.
 *
 * Format: `<prefix>_<32 hex chars>` (for example `msg_7f8d...`).
 * The prefix is always lowercase and must match [PREFIX_REGEX] source.
 */
@JvmInline
value class EntityId private constructor(val value: String) {

    init {
        require(isValid(value)) { "Invalid EntityId: $value" }
    }

    companion object {
        private val UUID_PATTERN = Regex("^[a-z][a-z0-9_]{0,31}_[0-9a-f]{32}$")

        fun generate(prefix: String, random: () -> UUID = UUID::randomUUID): EntityId {
            val normalized = prefix.lowercase(Locale.ROOT)
            require(normalized.matches(Regex("^[a-z][a-z0-9_]{0,31}$"))) {
                "Invalid id prefix: $prefix"
            }
            val hex = random().toString().replace("-", "")
            return EntityId("${normalized}_$hex")
        }

        fun fromString(value: String): EntityId = EntityId(value)

        fun tryParse(value: String): EntityId? = if (isValid(value)) EntityId(value) else null

        fun isValid(value: String): Boolean = UUID_PATTERN.matches(value)

        /**
         * Validates both the common entity-id shape and the domain-owned prefix.
         * Callers that operate on a specific table must not accept another
         * entity type merely because its UUID suffix is well formed.
         */
        fun isValidForPrefix(value: String, prefix: String): Boolean =
            isValid(value) && value.startsWith("${prefix.lowercase(Locale.ROOT)}_")
    }

    override fun toString(): String = value
}
