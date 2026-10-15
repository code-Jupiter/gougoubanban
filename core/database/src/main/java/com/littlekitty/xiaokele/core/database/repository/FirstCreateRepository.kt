package com.littlekitty.xiaokele.core.database.repository

import androidx.room.withTransaction
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.AppMetadataEntity
import com.littlekitty.xiaokele.core.database.entity.CharacterCoreEntity
import com.littlekitty.xiaokele.core.database.entity.UserTraitEntity

/** One user-owned fact captured by the first-create flow. */
data class FirstCreateProfileFact(
    val key: String,
    val valueJson: String,
    val recordedAt: Long,
)

/** Everything the first-create confirmation commits. */
data class FirstCreateConfirmation(
    val characterId: String,
    val characterVersion: Int,
    val characterContentJson: String,
    val profileFacts: List<FirstCreateProfileFact>,
    val completionKey: String,
    val completionValue: String,
    val completedAt: Long,
)

data class FirstCreateCommitResult(
    val characterId: String,
    val factsWritten: Int,
)

/**
 * Atomic first-create commit.
 *
 * Confirmation is the single transition from "draft" to "usable product": the
 * user profile facts, the confirmed character core and the completion marker
 * either all land or none do. A partial commit would let the app run with a
 * persona but no confirmed profile (or the reverse), so this must not be split
 * across separate transactions.
 */
class FirstCreateRepository(
    private val db: AppDatabase,
) {

    suspend fun latestCharacter(): CharacterCoreEntity? = db.characterCoreDao().latest()

    suspend fun completionMarker(key: String): String? = db.metadataDao().get(key)

    suspend fun commit(confirmation: FirstCreateConfirmation): FirstCreateCommitResult =
        db.withTransaction {
            confirmation.profileFacts.forEach { fact ->
                val existing = db.userTraitDao().byKey(fact.key)
                db.userTraitDao().upsert(
                    UserTraitEntity(
                        id = existing?.id ?: traitIdFor(fact.key),
                        key = fact.key,
                        valueJson = fact.valueJson,
                        confidence = 1.0,
                        evidenceMemoryIdsJson = null,
                        profileLayer = PROFILE_LAYER_CORE,
                        status = TRAIT_STATUS_ACTIVE,
                        updatedAt = fact.recordedAt,
                    ),
                )
            }

            db.characterCoreDao().upsert(
                CharacterCoreEntity(
                    id = confirmation.characterId,
                    version = confirmation.characterVersion,
                    contentJson = confirmation.characterContentJson,
                    createdAt = confirmation.completedAt,
                ),
            )

            db.metadataDao().put(
                AppMetadataEntity(
                    key = confirmation.completionKey,
                    value = confirmation.completionValue,
                    updatedAtEpochMillis = confirmation.completedAt,
                ),
            )

            FirstCreateCommitResult(
                characterId = confirmation.characterId,
                factsWritten = confirmation.profileFacts.size,
            )
        }

    private fun traitIdFor(key: String): String = "trait_first_create_${key.lowercase()}"

    companion object {
        const val PROFILE_LAYER_CORE = "CORE"
        const val TRAIT_STATUS_ACTIVE = "ACTIVE"
    }
}
