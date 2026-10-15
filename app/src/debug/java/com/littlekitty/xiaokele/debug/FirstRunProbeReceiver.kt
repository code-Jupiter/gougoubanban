package com.littlekitty.xiaokele.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.protocol.CharacterContext
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.CurrentContext
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.firstcreate.ConnectionReadinessReader
import com.littlekitty.xiaokele.firstcreate.FirstCreateDraft
import com.littlekitty.xiaokele.firstcreate.FirstCreateRules
import com.littlekitty.xiaokele.firstcreate.FirstCreateStage
import com.littlekitty.xiaokele.firstcreate.FirstCreateStore
import com.littlekitty.xiaokele.firstcreate.FirstCreateValidation
import com.littlekitty.xiaokele.firstcreate.GenderExpression
import com.littlekitty.xiaokele.firstcreate.PersonaGenerator
import com.littlekitty.xiaokele.firstcreate.PersonaGenerationResult
import com.littlekitty.xiaokele.firstcreate.PersonaInput
import com.littlekitty.xiaokele.firstcreate.PersonaPromptSpec
import com.littlekitty.xiaokele.firstcreate.SelectedGenerationProvider
import com.littlekitty.xiaokele.provider.CompanionPromptIdentity
import com.littlekitty.xiaokele.provider.TaskKind
import com.littlekitty.xiaokele.provider.TaskModelGateway
import com.littlekitty.xiaokele.provider.TaskModelRequest
import com.littlekitty.xiaokele.provider.TaskModelResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.koin.core.context.GlobalContext

/**
 * Debug-only driver for the first-run flow.
 *
 * The AVD used for acceptance is 320x480, where the flow's bottom navigation row
 * is clipped to a few pixels and coordinate taps are unreliable. This drives the
 * same production APIs the UI calls — [PersonaGenerator], [FirstCreateStore],
 * [FirstCreateRules] — so the state machine, the real model call and the atomic
 * confirmation are exercised without depending on tap accuracy.
 *
 * It never logs credentials. The generated persona is UI-visible content by
 * design, so it is logged for review.
 */
class FirstRunProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                run(context.applicationContext)
            } catch (error: Throwable) {
                Log.i(TAG, "RESULT outcome=ERROR ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun run(context: Context) {
        val store = GlobalContext.get().get<FirstCreateStore>()
        val readiness = GlobalContext.get().get<ConnectionReadinessReader>()
        val generator = GlobalContext.get().get<PersonaGenerator>()

        val snapshot = readiness.read()
        Log.i(TAG, "readiness ready=${snapshot.readyProviders.joinToString(",")}")

        // 1. Build the brief exactly as the UI would, starting from the gate.
        var draft = FirstCreateDraft(
            stage = FirstCreateStage.PET_NAME,
            petName = "DouDou",
            userAddress = "XiaoYu",
            relationship = "a dog I live with",
            genderExpression = GenderExpression.UNDISCLOSED,
            declaredAge = 30,
            declaredAgeRecordedAt = System.currentTimeMillis(),
            desiredPersonality = "quiet, listens first, does not fuss or nag",
            desiredTowardUser = "treats me as a friend, never as an owner or a master",
            desiredStyle = "short sentences, few exclamation marks",
            avoid = "no clingy baby talk, no romantic or intimate framing",
        )
        draft = store.saveDraft(draft)

        // 2. Walk the stage machine the way the buttons do.
        listOf(
            FirstCreateStage.PET_NAME,
            FirstCreateStage.USER_PROFILE,
            FirstCreateStage.PERSONALITY_BRIEF,
        ).forEach { expected ->
            val check = FirstCreateRules.validateStage(draft)
            if (check is FirstCreateValidation.Invalid) {
                Log.i(TAG, "RESULT outcome=VALIDATION_FAILED stage=${draft.stage} reason=${check.reason}")
                return
            }
            draft = draft.copy(stage = draft.stage.next)
            draft = store.saveDraft(draft)
        }
        Log.i(TAG, "stage after brief=${draft.stage}")

        // 3. Choose a writer model from a provider that is actually verified.
        val provider = snapshot.readyProviders.firstOrNull()
        if (provider == null) {
            Log.i(TAG, "RESULT outcome=NO_READY_PROVIDER")
            return
        }
        val model = readiness.modelsFor(provider).first()
        draft = store.saveDraft(
            draft.copy(
                stage = FirstCreateStage.MODEL_CHOICE,
                personaGeneratorProvider = provider,
                personaGeneratorModel = model,
            ),
        )
        Log.i(TAG, "writer=$provider/$model")

        // 4. Real model call.
        val input = PersonaInput(
            petName = draft.petName.trim(),
            userAddress = draft.userAddress.trim(),
            relationship = draft.relationship.trim(),
            desiredPersonality = draft.desiredPersonality.trim(),
            desiredTowardUser = draft.desiredTowardUser.trim(),
            desiredStyle = draft.desiredStyle.trim(),
            avoid = draft.avoid.trim(),
        )
        val startedAt = System.currentTimeMillis()
        val generated = generator.generate(input)
        val latency = System.currentTimeMillis() - startedAt
        when (generated) {
            is PersonaGenerationResult.Failure -> {
                // A validation rejection only names the pattern, not the wording
                // that tripped it, so ask the writer once more and log the raw
                // draft. The persona is UI-visible by design and no credential is
                // involved, so this leaks nothing.
                Log.i(TAG, "PROBE_RAW_WRITER_OUTPUT ${rawWriterOutput(input)}")
                Log.i(TAG, "RESULT outcome=GENERATION_FAILED reason=${generated.reason}")
                return
            }

            is PersonaGenerationResult.Success -> {
                val persona = generated.persona
                Log.i(TAG, "generation ok latency_ms=$latency rules=${persona.expressionDna.size}")
                Log.i(TAG, "PERSONA_ANCHOR ${persona.personaAnchor}")
                persona.expressionDna.forEachIndexed { i, rule -> Log.i(TAG, "PERSONA_RULE[$i] $rule") }
                Log.i(TAG, "PERSONA_SEES_USER ${persona.howItSeesUser}")

                val text = PersonaPromptSpec.renderConfirmedText(input, persona)
                draft = store.saveDraft(
                    draft.copy(
                        stage = FirstCreateStage.PERSONA_EDIT,
                        personaDraft = text,
                        personaRules = persona.expressionDna,
                        personaGeneratedAt = System.currentTimeMillis(),
                    ),
                )
                // The user reviews and then confirms.
                draft = draft.copy(stage = FirstCreateStage.CONFIRM)
                draft = store.saveDraft(draft)
            }
        }

        // 5. Atomic confirmation.
        val result = store.confirm(draft)
        if (result.isFailure) {
            Log.i(TAG, "RESULT outcome=CONFIRM_FAILED reason=${result.exceptionOrNull()?.message}")
            return
        }
        Log.i(TAG, "RESULT outcome=CONFIRMED")

        // 6. Prove the confirmed persona actually reaches the prompt.
        val db = GlobalContext.get().get<AppDatabase>()
        val coreJson = db.characterCoreDao().latest()?.contentJson
            ?.let { runCatching { JsonCodec.json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: JsonObject(emptyMap())
        val resolved = CompanionPromptIdentity.resolve(
            ContextPack(
                currentContext = CurrentContext(localTime = "probe"),
                character = CharacterContext(immutableCore = coreJson),
            ),
        )
        Log.i(TAG, "resolved name=${resolved.companionName} address=${resolved.userAddress}")
        Log.i(TAG, "resolved relationship=${resolved.relationship}")
        Log.i(TAG, "resolved persona present=${resolved.personaText.isNullOrBlank().not()}")
        val section = CompanionPromptIdentity.personaSection(resolved)
        Log.i(TAG, "prompt_section_chars=${section.length}")
        Log.i(TAG, "prompt_has_name=${section.contains(resolved.companionName)}")
        Log.i(TAG, "prompt_has_persona=${section.contains(resolved.personaText.orEmpty().take(40))}")

        // What the chat header reads.
        val latest = db.characterCoreDao().latest()
        Log.i(TAG, "latest_core version=${latest?.version} id=${latest?.id}")
        val controller = GlobalContext.get().get<com.littlekitty.xiaokele.chat.ChatController>()
        controller.refreshCompanionName()
        Log.i(TAG, "header_name=${controller.companionDisplayName.value}")
    }

    /**
     * Debug-only: repeats the writer call to expose the raw draft behind a
     * validation rejection. The probe is the only caller, so a successful turn
     * never pays for a second request.
     */
    private suspend fun rawWriterOutput(input: PersonaInput): String {
        val gateway = GlobalContext.get().get<TaskModelGateway>()
        val result = gateway.generate(
            TaskModelRequest(
                task = TaskKind.D_MEMORY,
                userPayload = PersonaPromptSpec.buildUserPayload(input),
                systemPrompt = PersonaPromptSpec.systemPrompt,
                structured = true,
            ),
        )
        return when (result) {
            is TaskModelResult.Success -> result.content
            is TaskModelResult.Failure -> "unavailable: ${result.error.code.name}"
        }
    }

    companion object {
        const val TAG = "FirstRunProbe"
        const val ACTION = "com.littlekitty.xiaokele.debug.FIRST_RUN_PROBE"
    }
}
