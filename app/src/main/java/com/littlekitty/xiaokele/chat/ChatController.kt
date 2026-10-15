package com.littlekitty.xiaokele.chat

import com.littlekitty.xiaokele.animation.AnimationCatalog
import com.littlekitty.xiaokele.animation.AnimationCommandCatalog
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.MessageEntity
import com.littlekitty.xiaokele.core.database.entity.PetStateEntity
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.littlekitty.xiaokele.firstcreate.FirstCreateGate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.littlekitty.xiaokele.provider.CompanionFallback
import com.littlekitty.xiaokele.core.common.protocol.ContextPack
import com.littlekitty.xiaokele.core.common.protocol.CurrentContext
import com.littlekitty.xiaokele.core.common.protocol.CharacterContext
import com.littlekitty.xiaokele.core.common.error.AppError
import com.littlekitty.xiaokele.core.common.error.ErrorCode
import kotlinx.coroutines.CancellationException

/**
 * Compose-facing state holder for the single-session chat page.
 * Room flows are the local fact source; sending state is derived from the
 * persisted message status, so a process restart restores the same timeline.
 */
class ChatController(
    private val db: AppDatabase,
    private val pipeline: FrontChatPipeline,
    private val sceneTestCommandHandler: SceneTestCommandHandler? = null,
    /**
     * Runtime gate for the first-create flow. Until the user confirms their
     * companion, a send must not reach the model or persist a message.
     */
    private val firstCreateGate: FirstCreateGate? = null,
) {
    private val sendError = MutableStateFlow<String?>(null)
    private val transientReplies = MutableStateFlow<List<ChatMessageUi>>(emptyList())
    private var lastIdentityContext: ContextPack? = null
    private val companionName = MutableStateFlow<String?>(null)
    private val commandNotice = MutableStateFlow<String?>(null)
    private val foregroundReplyGenerations = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val idleFollowUpIds = MutableStateFlow<Set<String>>(emptySet())
    private val idleFollowUpAnchors = mutableSetOf<String>()
    private val idleFollowUpMutex = Mutex()
    private var nextForegroundReplyGeneration = 0L

    /**
     * The name the user gave their companion in the first-create flow. The chat
     * header shows this instead of a fixed product name, so a generic build does
     * not present one dog's identity as everyone's.
     */
    val companionDisplayName: StateFlow<String?> = companionName.asStateFlow()

    /** Re-reads the confirmed name; safe to call on every screen start. */
    suspend fun refreshCompanionName() {
        val core = runCatching { db.characterCoreDao().latest()?.contentJson }
            .onFailure { com.littlekitty.xiaokele.observability.PrivacyLog.warn("companion_name_read_failed:${it.javaClass.simpleName}") }
            .getOrNull()
        core?.let { raw -> runCatching {
            lastIdentityContext = ContextPack(currentContext = CurrentContext(""),
                character = CharacterContext(JsonCodec.json.parseToJsonElement(raw).jsonObject))
        } }
        val name = core
            ?.let { raw -> runCatching { JsonCodec.json.parseToJsonElement(raw).jsonObject }.getOrNull() }
            ?.let { obj ->
                (obj["display_name"] ?: obj["name"])
                    ?.jsonPrimitive?.contentOrNull
            }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        companionName.value = name
    }

    val state: Flow<ChatUiState> = combine(
        db.messageDao().observeAll(),
        db.petStateDao().observeCurrent(),
        sendError,
        commandNotice,
    ) { messages, petState, error, notice ->
        val uiMessages = messages.map { message ->
            ChatMessageUi(
                id = message.id,
                role = message.role,
                content = message.content,
                createdAt = message.createdAt,
                status = message.status,
                animationId = message.animationId,
                retryable = message.role == FrontChatPipeline.ROLE_USER &&
                    message.status == MessageStatuses.FAILED,
            )
        }
        ChatUiState(
            messages = uiMessages,
            animationId = resolveAnimation(messages, petState),
            animationKey = resolveAnimationKey(messages, petState),
            isSending = messages.any { it.status == MessageStatuses.SENDING },
            sendError = error,
            commandNotice = notice,
        )
    }.combine(transientReplies) { uiState, replies ->
        uiState.copy(messages = (uiState.messages + replies).sortedBy { it.createdAt })
    }.combine(foregroundReplyGenerations) { uiState, foregroundGenerations ->
        uiState.copy(
            messages = uiState.messages.map { message ->
                message.copy(
                    isForegroundReply = message.id in foregroundGenerations,
                    foregroundReplyGeneration = foregroundGenerations[message.id] ?: 0L,
                )
            },
        )
    }.combine(idleFollowUpIds) { uiState, idleIds ->
        uiState.copy(
            messages = uiState.messages.map { message ->
                message.copy(isIdleFollowUp = message.id in idleIds)
            },
        )
    }

    suspend fun send(text: String) {
        sendError.value = null
        commandNotice.value = null
        firstCreateGate?.blockReason()?.let { reason ->
            sendError.value = reason
            return
        }
        sceneTestCommandHandler?.trySchedule(text)?.let { notice ->
            commandNotice.value = notice
            return
        }
        when (val command = AnimationCommandCatalog.parse(text)) {
            null -> sendThroughPipeline(text)
            is AnimationCommandCatalog.ParseResult.Action -> {
                applyAnimationCommand(command.entry)
                commandNotice.value = "已切换为「${command.entry.label}」"
            }
            AnimationCommandCatalog.ParseResult.Help -> {
                commandNotice.value = AnimationCommandCatalog.helpText()
            }
            is AnimationCommandCatalog.ParseResult.Unknown -> {
                commandNotice.value = "未识别命令 ${command.command}，输入 /帮助查看可调用动作。"
            }
        }
    }

    suspend fun retry(messageId: String) {
        firstCreateGate?.blockReason()?.let { reason ->
            sendError.value = reason
            return
        }
        sendError.value = null
        when (val outcome = withCompanionFailure { pipeline.retry(messageId) }) {
            is ChatSendOutcome.Failure -> if (outcome.error.technicalDetail != FrontChatPipeline.SUPERSEDED_BY_NEW_INPUT) {
                showFailure(outcome)
            }
            is ChatSendOutcome.Success -> {
                markForegroundReply(outcome.assistantMessageId)
                sendError.value = null
            }
            is ChatSendOutcome.NoAction -> sendError.value = null
        }
    }

    /**
     * Sends at most one continuation for an assistant reply. This is a
     * foreground-only path: it is never scheduled by WorkManager and has no
     * effect after the chat screen has cancelled its coroutine.
     */
    suspend fun sendIdleFollowUp(anchorAssistantMessageId: String) {
        firstCreateGate?.blockReason()?.let { reason ->
            sendError.value = reason
            return
        }
        idleFollowUpMutex.withLock {
            if (!idleFollowUpAnchors.add(anchorAssistantMessageId)) return
            try {
                sendError.value = null
                when (val outcome = pipeline.sendIdleFollowUp(anchorAssistantMessageId)) {
                    is ChatSendOutcome.Failure -> {
                        sendError.value = outcome.error.safeUserMessage()
                    }
                    is ChatSendOutcome.Success -> {
                        markForegroundReply(outcome.assistantMessageId)
                        idleFollowUpIds.value += outcome.assistantMessageId
                        sendError.value = null
                    }
                    is ChatSendOutcome.NoAction -> sendError.value = null
                }
            } finally {
                idleFollowUpAnchors.remove(anchorAssistantMessageId)
            }
        }
    }

    /** Called before sending a user message so an in-flight auto-follow-up is discarded. */
    fun cancelActiveIdleFollowUps() {
        pipeline.cancelActiveIdleFollowUps()
    }

    fun clearError() {
        sendError.value = null
    }

    fun clearCommandNotice() {
        commandNotice.value = null
    }

    /** UI-visible stop path: cancels only the newest foreground attempt. */
    suspend fun cancelSending(expectedUserMessageId: String? = null) {
        sendError.value = null
        pipeline.cancelCurrentUserAttempt(expectedUserMessageId)
    }

    companion object {
        const val CANCELLED_BY_USER = "CANCELLED_BY_USER"
        const val ROLE_USER = FrontChatPipeline.ROLE_USER
        const val ROLE_ASSISTANT = FrontChatPipeline.ROLE_ASSISTANT
    }

    /** Generation changes only when this process creates a new foreground reply. */
    fun currentForegroundReplyGeneration(): Long = nextForegroundReplyGeneration

    private fun markForegroundReply(messageId: String) {
        synchronized(this) {
            nextForegroundReplyGeneration += 1
            foregroundReplyGenerations.value =
                foregroundReplyGenerations.value + (messageId to nextForegroundReplyGeneration)
        }
    }

    private suspend fun sendThroughPipeline(text: String) {
        when (val outcome = withCompanionFailure { pipeline.send(text) }) {
            is ChatSendOutcome.Failure -> if (outcome.error.technicalDetail != FrontChatPipeline.SUPERSEDED_BY_NEW_INPUT) {
                showFailure(outcome)
            }
            is ChatSendOutcome.Success -> {
                markForegroundReply(outcome.assistantMessageId)
                sendError.value = null
            }
            is ChatSendOutcome.NoAction -> sendError.value = null
        }
    }

    private suspend fun withCompanionFailure(block: suspend () -> ChatSendOutcome): ChatSendOutcome = try {
        block()
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) {
        val error = AppError(ErrorCode.LOCAL_STORAGE_FAILURE)
        ChatSendOutcome.Failure(error, companionReply = CompanionFallback.text(lastIdentityContext, error))
    }

    private fun showFailure(outcome: ChatSendOutcome.Failure) {
        val speech = outcome.companionReply
        if (speech == null) {
            sendError.value = outcome.error.safeUserMessage()
            return
        }
        // A display-only reply is never passed to memory, tools or Room retries.
        val id = "local-companion-${java.util.UUID.randomUUID()}"
        transientReplies.value = (transientReplies.value + ChatMessageUi(
            id = id, role = FrontChatPipeline.ROLE_ASSISTANT, content = speech,
            createdAt = System.currentTimeMillis(), status = MessageStatuses.COMPLETED,
            animationId = null, retryable = false,
        )).takeLast(8)
        markForegroundReply(id)
        sendError.value = null
    }

    private suspend fun applyAnimationCommand(entry: AnimationCommandCatalog.Entry) {
        val now = System.currentTimeMillis()
        val animationId = entry.animationId
        db.petStateDao().upsert(
            PetStateEntity(
                singletonId = PetStateEntity.SINGLETON_ID,
                stateId = entry.stateId,
                animationId = animationId,
                startsAt = now,
                expiresAt = if (animationId == AnimationCatalog.ID_DEFAULT_IDLE) {
                    null
                } else {
                    now + AnimationCatalog.durationMillisFor(animationId)
                },
                updatedAt = now,
            ),
        )
    }

    private fun resolveAnimation(
        messages: List<MessageEntity>,
        petState: PetStateEntity?,
    ): String? {
        val now = System.currentTimeMillis()
        val activePetState = petState?.takeIf { state ->
            val expiresAt = state.expiresAt
            expiresAt == null || expiresAt > now
        }
        val latestAssistant = messages
            .asReversed()
            .firstOrNull { it.role == FrontChatPipeline.ROLE_ASSISTANT && it.animationId != null }

        return when {
            activePetState != null && (latestAssistant == null || activePetState.updatedAt >= latestAssistant.createdAt) ->
                activePetState.animationId
            latestAssistant != null -> latestAssistant.animationId
            else -> null
        }
    }

    private fun resolveAnimationKey(
        messages: List<MessageEntity>,
        petState: PetStateEntity?,
    ): String? {
        val now = System.currentTimeMillis()
        val activePetState = petState?.takeIf { state ->
            val expiresAt = state.expiresAt
            expiresAt == null || expiresAt > now
        }
        val latestAssistant = messages
            .asReversed()
            .firstOrNull { it.role == FrontChatPipeline.ROLE_ASSISTANT && it.animationId != null }

        return when {
            activePetState != null && (latestAssistant == null || activePetState.updatedAt >= latestAssistant.createdAt) ->
                "pet:${activePetState.updatedAt}:${activePetState.animationId}"
            latestAssistant != null -> "message:${latestAssistant.id}:${latestAssistant.animationId}"
            else -> null
        }
    }
}
