package com.littlekitty.xiaokele.firstcreate

import android.content.Context
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.AppMetadataEntity
import com.littlekitty.xiaokele.core.database.repository.FirstCreateConfirmation
import com.littlekitty.xiaokele.core.database.repository.FirstCreateProfileFact
import com.littlekitty.xiaokele.core.database.repository.FirstCreateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the resumable first-create draft and performs the one atomic
 * confirmation.
 *
 * The draft lives in `app_metadata` so an abandoned or crashed flow keeps every
 * accepted input and can be resumed without a schema migration.
 */
class FirstCreateStore(
    private val db: AppDatabase,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val characterIdFactory: (Long) -> String = { now -> defaultCharacterId(now) },
) {

    private val repository = FirstCreateRepository(db)

    suspend fun loadDraft(): FirstCreateDraft {
        val raw = db.metadataDao().get(DRAFT_KEY) ?: return FirstCreateDraft()
        return runCatching { JsonCodec.decode<FirstCreateDraft>(raw) }
            .getOrElse { FirstCreateDraft() }
    }

    suspend fun saveDraft(draft: FirstCreateDraft): FirstCreateDraft {
        val stamped = draft.copy(updatedAt = timeProvider())
        db.metadataDao().put(
            AppMetadataEntity(
                key = DRAFT_KEY,
                value = JsonCodec.encode(stamped),
                updatedAtEpochMillis = stamped.updatedAt,
            ),
        )
        return stamped
    }

    suspend fun completionEpochMillis(): Long? =
        repository.completionMarker(COMPLETED_KEY)?.toLongOrNull()

    suspend fun isCompleted(): Boolean = completionEpochMillis() != null

    /**
     * Confirms the draft: user profile facts, the locked persona and the
     * completion marker commit in one database transaction.
     */
    suspend fun confirm(draft: FirstCreateDraft): Result<FirstCreateDraft> {
        when (val check = FirstCreateRules.canConfirm(draft)) {
            is FirstCreateValidation.Invalid ->
                return Result.failure(IllegalStateException(check.reason))
            FirstCreateValidation.Valid -> Unit
        }
        // The user-facing flow selects a persona writer from providers that have
        // a verified connection. `draft.provider` belongs to the superseded
        // single-service form and is no longer set by the UI, so requiring it
        // here made confirmation fail for every user.
        val writer = draft.personaGeneratorProvider
            ?: return Result.failure(IllegalStateException("缺少用来写设定的服务"))
        val now = timeProvider()
        val personaText = draft.personaDraft.trim()
        val core = NeutralPersonaTemplate.buildCharacterCore(draft, personaText, now)
        val characterId = characterIdFactory(now)

        val confirmation = FirstCreateConfirmation(
            characterId = characterId,
            characterVersion = CHARACTER_CORE_VERSION,
            characterContentJson = NeutralPersonaTemplate.encodeCharacterCore(core),
            profileFacts = listOf(
                fact(KEY_PET_NAME, draft.petName.trim(), now),
                fact(KEY_USER_ADDRESS, draft.userAddress.trim(), now),
                fact(KEY_RELATIONSHIP, draft.relationship.trim(), now),
                fact(KEY_GENDER_EXPRESSION, draft.effectiveGenderText, now),
                fact(KEY_DECLARED_AGE, draft.declaredAge.toString(), now),
                fact(
                    KEY_DECLARED_AGE_RECORDED_AT,
                    (draft.declaredAgeRecordedAt ?: now).toString(),
                    now,
                ),
                // Record which provider wrote the persona. No other provider's
                // credential is read or borrowed at any point.
                fact(KEY_SELECTED_PROVIDER, writer.name, now),
                fact(KEY_PERSONA_WRITER_MODEL, draft.personaGeneratorModel, now),
                fact(KEY_SELECTED_PROVIDER_VALIDATED_AT, now.toString(), now),
                fact(KEY_PERSONA_TEXT, personaText, now),
            ),
            completionKey = COMPLETED_KEY,
            completionValue = now.toString(),
            completedAt = now,
        )

        return runCatching {
            repository.commit(confirmation)
            val confirmed = saveDraft(draft.copy(stage = FirstCreateStage.COMPLETED, confirmed = true))
            confirmed
        }
    }

    private fun fact(key: String, value: String, now: Long): FirstCreateProfileFact =
        FirstCreateProfileFact(
            key = key,
            valueJson = JsonCodec.encode(ProfileFactValue(value)),
            recordedAt = now,
        )

    @kotlinx.serialization.Serializable
    private data class ProfileFactValue(val value: String)

    companion object {
        const val DRAFT_KEY = "first_create/draft"
        const val COMPLETED_KEY = "first_create/completed_at"

        /** Persona core version written by the first-create flow. */
        const val CHARACTER_CORE_VERSION = 12

        const val KEY_PET_NAME = "pet_display_name"
        const val KEY_USER_ADDRESS = "user_display_name"
        const val KEY_RELATIONSHIP = "companion_relationship"
        const val KEY_GENDER_EXPRESSION = "user_gender_expression"
        const val KEY_DECLARED_AGE = "user_declared_age"
        const val KEY_DECLARED_AGE_RECORDED_AT = "user_declared_age_recorded_at"
        const val KEY_SELECTED_PROVIDER = "generation_provider_selection"
        const val KEY_SELECTED_PROVIDER_VALIDATED_AT = "generation_provider_validated_at"
        const val KEY_PERSONA_WRITER_MODEL = "persona_writer_model"
        const val KEY_PERSONA_TEXT = "companion_persona_text"

        private fun defaultCharacterId(now: Long): String =
            "char_core_first_create_${now.toString(16)}"
    }
}

/**
 * Runtime gate. Chat, memory writes and background workers all consult this
 * before doing anything user-visible, so an incomplete first-create flow cannot
 * start the product in a half-configured state.
 */
class FirstCreateGate(
    private val store: FirstCreateStore,
    context: Context? = null,
) {
    private val _completed = MutableStateFlow(false)
    val completed: StateFlow<Boolean> = _completed.asStateFlow()

    @Volatile
    private var cached: Boolean? = null

    suspend fun refresh(): Boolean {
        val value = store.isCompleted()
        cached = value
        _completed.value = value
        return value
    }

    /**
     * Fail-closed: an unknown state is treated as "not completed" so a caller
     * can never accidentally run before the flow is confirmed.
     */
    suspend fun isCompleted(): Boolean = cached ?: refresh()

    /** Non-suspending check for cheap guards once the gate has been refreshed. */
    fun isCompletedCached(): Boolean = cached ?: false

    suspend fun markCompleted() {
        cached = true
        _completed.value = true
    }

    /** Reason string for a blocked attempt, surfaced instead of a silent no-op. */
    suspend fun blockReason(): String? =
        if (isCompleted()) null else "还没有完成首次创建，先确认小狗的名字和人格。"
}
