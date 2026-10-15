package com.littlekitty.xiaokele.core.database.repository

import androidx.room.withTransaction
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.BehaviorPatch
import com.littlekitty.xiaokele.core.common.protocol.BehaviorPatchOperationNames
import com.littlekitty.xiaokele.core.common.protocol.BehaviorTendencyKeyNames
import com.littlekitty.xiaokele.core.common.protocol.ImmutableCharacterCoreNames
import com.littlekitty.xiaokele.core.common.protocol.UserTraitPatch
import com.littlekitty.xiaokele.core.common.protocol.UserTraitPatchOperationNames
import com.littlekitty.xiaokele.core.common.protocol.UserProfileLayerNames
import com.littlekitty.xiaokele.core.common.protocol.PatternObservation
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.dao.BehaviorTendencyDao
import com.littlekitty.xiaokele.core.database.dao.UserTraitDao
import com.littlekitty.xiaokele.core.database.entity.BehaviorTendencyEntity
import com.littlekitty.xiaokele.core.database.entity.UserTraitEntity
import com.littlekitty.xiaokele.core.database.state.BehaviorTendencyStatuses
import com.littlekitty.xiaokele.core.database.state.UserTraitStatuses
import kotlin.math.max
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class UserTraitPatchOutcome(
    val key: String,
    val applied: Boolean,
    val status: String?,
    val rejectedReason: String? = null,
)

data class BehaviorPatchOutcome(
    val key: String,
    val applied: Boolean,
    val previousStatus: String? = null,
    val newValue: Double? = null,
    val legacyCreated: Boolean = false,
    val rejectedReason: String? = null,
)

class UserTraitRepository(
    private val db: AppDatabase,
    private val dao: UserTraitDao,
) {

    suspend fun active(): List<UserTraitEntity> =
        dao.all().filter { it.status == UserTraitStatuses.ACTIVE }

    suspend fun applyPatternObservation(
        observation: PatternObservation,
        now: Long,
    ): UserTraitPatchOutcome {
        val traitKey = "pattern.${observation.key}"
        val existing = dao.byKey(traitKey)
        val existingEvidence = existing?.evidenceMemoryIdsJson
            ?.let { runCatching { JsonCodec.decode<List<String>>(it) }.getOrNull() }
            .orEmpty()
        val evidence = (existingEvidence + observation.evidenceMessageIds).distinct()
        val existingValue = existing?.valueJson
            ?.let { runCatching { JsonCodec.json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val previousCount = existingValue?.get("evidence_count")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val evidenceCount = if (observation.evidenceMessageIds.isEmpty()) {
            previousCount + 1
        } else {
            max(previousCount, evidence.size)
        }
        val firstSeenAt = existingValue?.get("first_seen_at")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: now
        val profileLayer = if (evidenceCount >= PATTERN_CORE_EVIDENCE_THRESHOLD && observation.confidence >= 0.75) {
            UserProfileLayerNames.CORE
        } else {
            UserProfileLayerNames.DYNAMIC
        }
        return applyPatch(
            patch = UserTraitPatch(
                key = traitKey,
                value = buildJsonObject {
                    put("observation", observation.observation)
                    put("evidence_count", evidenceCount)
                    put("first_seen_at", firstSeenAt)
                    put("last_seen_at", now)
                },
                confidence = max(existing?.confidence ?: 0.0, observation.confidence),
                profileLayer = profileLayer,
            ),
            derivedEvidenceMemoryIds = evidence,
            now = now,
        )
    }

    suspend fun applyPatch(
        patch: UserTraitPatch,
        derivedEvidenceMemoryIds: List<String>,
        now: Long,
    ): UserTraitPatchOutcome {
        if (patch.operation == UserTraitPatchOperationNames.DEACTIVATE) {
            val existing = dao.byKey(patch.key)
                ?: return UserTraitPatchOutcome(patch.key, applied = false, status = null, rejectedReason = "trait_missing")
            val updated = existing.copy(
                status = UserTraitStatuses.INACTIVE,
                evidenceMemoryIdsJson = mergeEvidence(existing.evidenceMemoryIdsJson, derivedEvidenceMemoryIds),
                updatedAt = now,
            )
            dao.upsert(updated)
            return UserTraitPatchOutcome(patch.key, applied = true, status = updated.status)
        }

        val valueJson = patch.value
            ?: return UserTraitPatchOutcome(patch.key, applied = false, status = null, rejectedReason = "upsert_value_missing")
        val existing = dao.byKey(patch.key)
        val entity = if (existing == null) {
            UserTraitEntity(
                id = EntityId.generate("trait").value,
                key = patch.key,
                valueJson = valueJson.toString(),
                confidence = patch.confidence,
                evidenceMemoryIdsJson = JsonCodec.encode(derivedEvidenceMemoryIds.distinct()),
                profileLayer = patch.profileLayer,
                status = UserTraitStatuses.ACTIVE,
                updatedAt = now,
            )
        } else {
            val replaceValue = patch.confidence >= existing.confidence
            existing.copy(
                valueJson = if (replaceValue) valueJson.toString() else existing.valueJson,
                confidence = max(existing.confidence, patch.confidence),
                evidenceMemoryIdsJson = mergeEvidence(existing.evidenceMemoryIdsJson, derivedEvidenceMemoryIds),
                profileLayer = if (
                    patch.profileLayer == UserProfileLayerNames.CORE || existing.profileLayer == UserProfileLayerNames.CORE
                ) {
                    UserProfileLayerNames.CORE
                } else {
                    UserProfileLayerNames.DYNAMIC
                },
                status = UserTraitStatuses.ACTIVE,
                updatedAt = now,
            )
        }
        dao.upsert(entity)
        return UserTraitPatchOutcome(patch.key, applied = true, status = entity.status)
    }

    private fun mergeEvidence(existingJson: String?, additional: List<String>): String {
        val existing = existingJson
            ?.let { runCatching { JsonCodec.decode<List<String>>(it) }.getOrNull() }
            .orEmpty()
        return JsonCodec.encode((existing + additional).distinct())
    }

    companion object {
        const val PATTERN_CORE_EVIDENCE_THRESHOLD = 3
    }
}

/**
 * Read-side facade for training state. Current rows are the latest ACTIVE row
 * per tendency key; legacy rows remain SUPPRESSED_LEGACY forever and have no
 * path back to ACTIVE.
 */
class BehaviorTendencyRepository(
    private val db: AppDatabase,
    private val dao: BehaviorTendencyDao,
) {

    suspend fun activeTendencies(): List<BehaviorTendencyEntity> =
        dao.all()
            .groupBy { it.key }
            .mapNotNull { (_, rows) -> rows.firstOrNull { it.status == BehaviorTendencyStatuses.ACTIVE } }
            .sortedBy { it.key }

    suspend fun legacyTendencies(): List<BehaviorTendencyEntity> =
        dao.all()
            .filter { it.status == BehaviorTendencyStatuses.SUPPRESSED_LEGACY }
            .groupBy { it.key }
            .mapNotNull { (_, rows) -> rows.maxByOrNull { it.updatedAt } }
            .sortedBy { it.key }

    suspend fun all(): List<BehaviorTendencyEntity> = dao.all()

    private fun dao(): BehaviorTendencyDao = dao
}

/**
 * Deterministic Behavior Trainer (architecture section 22, FR-MEM-06/07/08).
 *
 * It never modifies character_core and never promotes a SUPPRESSED_LEGACY row
 * back to ACTIVE. Every accepted patch creates a new versioned row; the
 * previous ACTIVE row is archived, or moved to SUPPRESSED_LEGACY when the model
 * requested keep_as_legacy. Evidence memory ids are stored on the new row.
 */
class BehaviorTrainer(
    private val db: AppDatabase,
    private val dao: BehaviorTendencyDao,
) {

    suspend fun applyPatch(
        patch: BehaviorPatch,
        derivedEvidenceMemoryIds: List<String>,
        now: Long,
    ): BehaviorPatchOutcome = db.withTransaction {
        if (ImmutableCharacterCoreNames.isProtectedBehaviorKey(patch.key)) {
            return@withTransaction BehaviorPatchOutcome(
                patch.key,
                applied = false,
                rejectedReason = "immutable_character_core",
            )
        }
        if (patch.operation !in BehaviorPatchOperationNames.all) {
            return@withTransaction BehaviorPatchOutcome(
                patch.key,
                applied = false,
                rejectedReason = "unknown_operation",
            )
        }

        val rows = dao.forKey(patch.key)
        val currentActive = rows.firstOrNull { it.status == BehaviorTendencyStatuses.ACTIVE }
        val baseValue = currentActive?.currentValue?.toDoubleOrNull() ?: DEFAULT_TENDENCY_VALUE
        val newValue = when (patch.operation) {
            BehaviorPatchOperationNames.INCREASE -> (baseValue + patch.amount).coerceIn(0.0, 1.0)
            BehaviorPatchOperationNames.DECREASE -> (baseValue - patch.amount).coerceIn(0.0, 1.0)
            BehaviorPatchOperationNames.SET -> patch.amount.coerceIn(0.0, 1.0)
            else -> baseValue
        }

        // When a correction arrives before an explicit ACTIVE row was seeded, the
        // deterministic default is still the old tendency: preserve it as a
        // suppressed legacy baseline instead of pretending no old behavior existed.
        if (currentActive == null && patch.keepAsLegacy) {
            dao.upsert(
                BehaviorTendencyEntity(
                    id = EntityId.generate("tend").value,
                    key = patch.key,
                    originalValue = format(baseValue),
                    currentValue = format(baseValue),
                    suppressionLevel = DEFAULT_LEGACY_SUPPRESSION,
                    legacyExpressionRuleJson = defaultLegacyRule(patch.key, format(baseValue)),
                    sourceMemoryIdsJson = null,
                    status = BehaviorTendencyStatuses.SUPPRESSED_LEGACY,
                    updatedAt = now,
                ),
            )
        }

        val evidence = (patch.evidenceMemoryIds + derivedEvidenceMemoryIds).distinct()
        val newRow = BehaviorTendencyEntity(
            id = EntityId.generate("tend").value,
            key = patch.key,
            originalValue = currentActive?.originalValue ?: format(baseValue),
            currentValue = format(newValue),
            suppressionLevel = null,
            legacyExpressionRuleJson = null,
            sourceMemoryIdsJson = if (evidence.isEmpty()) null else JsonCodec.encode(evidence),
            status = BehaviorTendencyStatuses.ACTIVE,
            updatedAt = now,
        )

        val previousStatus = currentActive?.status
        if (currentActive != null) {
            val legacy = patch.keepAsLegacy
            val targetStatus = if (legacy) {
                BehaviorTendencyStatuses.SUPPRESSED_LEGACY
            } else {
                BehaviorTendencyStatuses.ARCHIVED
            }
            val suppressionLevel = if (legacy) DEFAULT_LEGACY_SUPPRESSION else null
            val legacyRule = if (legacy) defaultLegacyRule(patch.key, currentActive.currentValue) else null
            check(
                dao.transitionToLegacyOrArchived(
                    id = currentActive.id,
                    expectedStatus = currentActive.status,
                    newStatus = targetStatus,
                    suppressionLevel = suppressionLevel,
                    legacyExpressionRuleJson = legacyRule,
                    now = now,
                ) == 1,
            ) {
                "Behavior tendency transition failed: ${currentActive.id}"
            }
        }
        dao.upsert(newRow)

        BehaviorPatchOutcome(
            key = patch.key,
            applied = true,
            previousStatus = previousStatus,
            newValue = newValue,
            legacyCreated = patch.keepAsLegacy,
        )
    }

    suspend fun applyPatches(
        patches: List<BehaviorPatch>,
        derivedEvidenceMemoryIds: List<String>,
        now: Long,
    ): List<BehaviorPatchOutcome> = db.withTransaction {
        patches.map { patch ->
            applyPatch(patch, derivedEvidenceMemoryIds, now)
        }
    }

    suspend fun ensureDefault(key: String, now: Long): BehaviorTendencyEntity = db.withTransaction {
        val existing = dao.forKey(key).firstOrNull { it.status == BehaviorTendencyStatuses.ACTIVE }
        if (existing != null) return@withTransaction existing
        val entity = BehaviorTendencyEntity(
            id = EntityId.generate("tend").value,
            key = key,
            originalValue = format(DEFAULT_TENDENCY_VALUE),
            currentValue = format(DEFAULT_TENDENCY_VALUE),
            suppressionLevel = null,
            legacyExpressionRuleJson = null,
            sourceMemoryIdsJson = null,
            status = BehaviorTendencyStatuses.ACTIVE,
            updatedAt = now,
        )
        dao.upsert(entity)
        entity
    }

    suspend fun ensureDefaults(now: Long): List<BehaviorTendencyEntity> =
        BehaviorTendencyKeyNames.all.map { key ->
            ensureDefault(key, now)
        }

    private fun defaultLegacyRule(key: String, oldValue: String?): String =
        """
        {
          "never_primary": true,
          "max_exposure": "LOW",
          "allowed_condition": "explicit_nostalgia_or_user_mentions_past_behavior",
          "legacy_of": "$key",
          "legacy_value": ${oldValue ?: "null"}
        }
        """.trimIndent()

    private fun format(value: Double): String = "%.4f".format(value)

    companion object {
        const val DEFAULT_TENDENCY_VALUE = 0.5
        const val DEFAULT_LEGACY_SUPPRESSION = 0.85
    }
}
