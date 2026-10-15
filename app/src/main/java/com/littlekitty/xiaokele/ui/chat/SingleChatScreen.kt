package com.littlekitty.xiaokele.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.littlekitty.xiaokele.R
import com.littlekitty.xiaokele.animation.AnimatedPetImage
import com.littlekitty.xiaokele.chat.ChatController
import com.littlekitty.xiaokele.chat.ChatMessageUi
import com.littlekitty.xiaokele.chat.ChatUiState
import com.littlekitty.xiaokele.chat.FrontChatPipeline
import com.littlekitty.xiaokele.chat.IdleFollowUpPolicy
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

private const val HIDDEN_DEBUG_TAP_COUNT = 7
private const val HIDDEN_DEBUG_TAP_WINDOW_MILLIS = 1_500L

/**
 * Phase UI polish single-session chat page.
 *
 * Role semantics are frozen by [ChatUiSemantics]: USER is the real user and
 * owns USER rows; COMPANION is the virtual dog character and owns ASSISTANT rows.
 * The role stage is a sticky list header so opening the IME shrinks the
 * message area without moving the top bar or hiding the character stage.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SingleChatScreen(
    chatController: ChatController,
    modifier: Modifier = Modifier,
    onDebugRequested: (() -> Unit)? = null,
    onDiagnosticsRequested: (() -> Unit)? = null,
    newsAction: (@Composable () -> Unit)? = null,
    settingsAction: (@Composable () -> Unit)? = null,
    discoveryAction: (@Composable () -> Unit)? = null,
) {
    val uiState by chatController.state.collectAsStateWithLifecycle(initialValue = ChatUiState())
    val companionName by chatController.companionDisplayName.collectAsStateWithLifecycle()
    val displayName = companionName ?: stringResource(R.string.chat_title)
    LaunchedEffect(Unit) { chatController.refreshCompanionName() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var sendJob by remember { mutableStateOf<Job?>(null) }
    var idleFollowUpJob by remember { mutableStateOf<Job?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var isChatResumed by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var cancelledReplyIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var hiddenDebugTapCount by remember { mutableStateOf(0) }
    val sessionReplyGeneration = remember { chatController.currentForegroundReplyGeneration() }
    val canSend = ChatUiSemantics.canSend(input, uiState.isSending)
    val isAtLatestMessage by remember {
        derivedStateOf {
            uiState.messages.isNotEmpty() &&
                listState.layoutInfo.visibleItemsInfo.any { it.index == uiState.messages.size }
        }
    }
    val latestAssistant = uiState.messages.lastOrNull()
        ?.takeIf { message ->
            message.role == ChatController.ROLE_ASSISTANT &&
                message.status == MessageStatuses.COMPLETED &&
                message.isForegroundReply &&
                !message.isIdleFollowUp &&
                message.foregroundReplyGeneration > sessionReplyGeneration &&
                IdleFollowUpPolicy.isEligible(message.content) &&
                message.id !in cancelledReplyIds
        }
    val hasEarlierUserMessage = uiState.messages.dropLast(1).any { message ->
        message.role == ChatController.ROLE_USER &&
            message.status == MessageStatuses.COMPLETED
    }

    LaunchedEffect(uiState.sendError) {
        uiState.sendError?.let { message ->
            snackbarHostState.showSnackbar(message)
            chatController.clearError()
        }
    }

    LaunchedEffect(hiddenDebugTapCount) {
        if (hiddenDebugTapCount == 0) return@LaunchedEffect
        if (hiddenDebugTapCount >= HIDDEN_DEBUG_TAP_COUNT) {
            hiddenDebugTapCount = 0
            (onDebugRequested ?: onDiagnosticsRequested)?.invoke()
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(HIDDEN_DEBUG_TAP_WINDOW_MILLIS)
        hiddenDebugTapCount = 0
    }

    LaunchedEffect(uiState.commandNotice) {
        uiState.commandNotice?.let { notice ->
            snackbarHostState.showSnackbar(notice)
            chatController.clearCommandNotice()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isChatResumed = true
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP,
                -> {
                    isChatResumed = false
                    idleFollowUpJob?.cancel()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(isChatResumed, latestAssistant?.id) {
        if (!isChatResumed) {
            latestAssistant?.id?.let { cancelledReplyIds = cancelledReplyIds + it }
        }
    }

    LaunchedEffect(input, latestAssistant?.id) {
        if (input.isNotBlank()) {
            latestAssistant?.id?.let { anchorId ->
                cancelledReplyIds = cancelledReplyIds + anchorId
                idleFollowUpJob?.cancel()
                chatController.cancelActiveIdleFollowUps()
            }
        }
    }

    LaunchedEffect(uiState.messages.size, uiState.isSending) {
        // The sticky role-stage header occupies index 0, so the latest message
        // is at messages.size. During a pending reply, the companion indicator
        // follows it and is one additional item below.
        if (uiState.messages.isNotEmpty()) {
            val targetIndex = uiState.messages.size + if (uiState.isSending) 1 else 0
            listState.scrollToItem(targetIndex)
        }
    }

    LaunchedEffect(latestAssistant?.id, isChatResumed) {
        val anchor = latestAssistant ?: return@LaunchedEffect
        if (!hasEarlierUserMessage) return@LaunchedEffect
        if (!isChatResumed || anchor.id in cancelledReplyIds) return@LaunchedEffect

        // A new reply auto-scrolls asynchronously. Wait until the user is at
        // the bottom before starting the reading timer. Scrolling away pauses
        // this timer; returning to the bottom starts a fresh full wait.
        snapshotFlow { isAtLatestMessage }.first { it }
        while (isChatResumed && input.isBlank() && anchor.id !in cancelledReplyIds) {
            val invalidated = withTimeoutOrNull(IdleFollowUpPolicy.delayMillis(anchor.content)) {
                snapshotFlow { input.isNotBlank() || !isAtLatestMessage }.first { it }
            }
            if (invalidated == null) {
                if (input.isBlank() && isAtLatestMessage && isChatResumed) {
                    cancelledReplyIds = cancelledReplyIds + anchor.id
                    // This screen-owned job survives cancellation of this
                    // keyed LaunchedEffect when cancelledReplyIds recomposes.
                    idleFollowUpJob = scope.launch {
                        chatController.sendIdleFollowUp(anchor.id)
                    }
                }
                return@LaunchedEffect
            }

            // User input and lifecycle changes are permanent cancellations;
            // scrolling away is only a temporary pause and is handled by the
            // next loop iteration after the user returns to the bottom.
            if (input.isNotBlank()) {
                cancelledReplyIds = cancelledReplyIds + anchor.id
                return@LaunchedEffect
            }
            snapshotFlow { isAtLatestMessage }.first { it }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                ),
                title = {
                    Column(
                        modifier = if (onDebugRequested == null && onDiagnosticsRequested == null) {
                            Modifier
                        } else {
                            Modifier.pointerInput(Unit) {
                                detectTapGestures(onTap = { hiddenDebugTapCount++ })
                            }
                        },
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.chat_subtitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    if (onDebugRequested != null) {
                        TextButton(onClick = { onDebugRequested.invoke() }) {
                            Text("Debug")
                        }
                    }
                    discoveryAction?.invoke()
                    newsAction?.invoke()
                    settingsAction?.invoke()
                },
            )
        },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(scaffoldPadding)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
                ),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                stickyHeader(key = "role_stage") {
                    RoleStageCard(
                        companionName = displayName,
                        animationId = uiState.animationId,
                        animationKey = uiState.animationKey,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (uiState.messages.isEmpty()) {
                    item(key = "empty_chat") {
                        EmptyChatCard(companionName = displayName, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }

                itemsIndexed(
                    items = uiState.messages,
                    key = { _, message -> message.id },
                ) { index, message ->
                    val previousCreatedAt = uiState.messages.getOrNull(index - 1)?.createdAt
                    val showTimestamp = ChatUiSemantics.shouldShowTimestamp(
                        previousCreatedAt = previousCreatedAt,
                        currentCreatedAt = message.createdAt,
                    )
                    ChatMessageRow(
                        companionName = displayName,
                        message = message,
                        timestampText = if (showTimestamp) {
                            ChatUiSemantics.formatTimestamp(message.createdAt)
                        } else {
                            null
                        },
                        onRetry = {
                            scope.launch { chatController.retry(message.id) }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                if (ChatUiSemantics.shouldShowCompanionReplyingIndicator(uiState.isSending)) {
                    item(key = "companion_replying") {
                        CompanionReplyingIndicator(
                            companionName = displayName,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }

            ChatInputDock(
                companionName = displayName,
                input = input,
                isSending = uiState.isSending,
                canSend = canSend,
                onInputChanged = { input = it },
                onSend = {
                    val text = input.trim()
                    if (text.isNotEmpty() && canSend) {
                        // A user send takes precedence over an automatic
                        // continuation that may still be waiting on the model.
                        chatController.cancelActiveIdleFollowUps()
                        idleFollowUpJob?.cancel()
                        sendJob = scope.launch {
                            input = ""
                            chatController.send(text)
                        }
                    } else if (uiState.isSending) {
                        // Stop is explicit only when the composer is empty;
                        // typing a new message never gets mistaken for cancel.
                        val expectedUserMessageId = uiState.messages
                            .asReversed()
                            .firstOrNull {
                                it.role == FrontChatPipeline.ROLE_USER &&
                                    it.status == MessageStatuses.SENDING
                            }
                            ?.id
                        sendJob?.cancel()
                        scope.launch { chatController.cancelSending(expectedUserMessageId) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RoleStageCard(
    companionName: String,
    animationId: String?,
    animationKey: String?,
    modifier: Modifier = Modifier,
) {
    val stageDescription = stringResource(R.string.role_stage_content_description, companionName)
    val stageGradient = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
            MaterialTheme.colorScheme.background,
        ),
    )
    // The stage is part of the page rather than a separate outlined card.
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 188.dp, max = 204.dp)
            .background(
                brush = stageGradient,
                shape = RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp),
            )
            .padding(bottom = 8.dp)
            .semantics { contentDescription = stageDescription },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            AnimatedPetImage(
                animationId = animationId,
                animationKey = animationKey,
                contentDescription = stageDescription,
                modifier = Modifier
                    .width(88.dp)
                    .height(130.dp),
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.chat_sender_companion, companionName),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.chat_companion_idle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EmptyChatCard(companionName: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.chat_empty_state, companionName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
        )
    }
}

@Composable
private fun ChatMessageRow(
    companionName: String,
    message: ChatMessageUi,
    timestampText: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUser = ChatUiSemantics.isUserMessage(message.role)
    val senderLabel = when (ChatUiSemantics.authorForRole(message.role)) {
        ChatMessageAuthor.USER -> stringResource(R.string.chat_sender_user)
        ChatMessageAuthor.COMPANION -> stringResource(R.string.chat_sender_companion, companionName)
        ChatMessageAuthor.UNKNOWN -> ""
    }
    val bubbleDescription = if (isUser) {
        stringResource(R.string.chat_user_bubble_description)
    } else {
        stringResource(R.string.chat_assistant_bubble_description, companionName)
    }

    Column(
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        timestampText?.let { timestamp ->
            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            )
        }

        if (senderLabel.isNotBlank()) {
            Text(
                text = senderLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Surface(
            shape = if (isUser) {
                RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
            } else {
                RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
            },
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth(0.86f)
                .semantics {
                    contentDescription = bubbleDescription
                },
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }

        // While a request is in progress, the companion-side indicator below
        // the conversation communicates progress. The user's bubble remains
        // in its normal delivered appearance; failures keep retry affordance.
        if (message.retryable) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.wrapContentWidth(),
            ) {
                Text(
                    text = stringResource(R.string.chat_message_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.padding(start = 4.dp),
                ) {
                    Text(stringResource(R.string.chat_retry))
                }
            }
        }
    }
}

@Composable
private fun CompanionReplyingIndicator(
    companionName: String,
    modifier: Modifier = Modifier,
) {
    val text = stringResource(R.string.chat_message_sending, companionName)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics { contentDescription = text },
    ) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier
                .width(18.dp)
                .height(18.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatInputDock(
    companionName: String,
    input: String,
    isSending: Boolean,
    canSend: Boolean,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inputDescription = stringResource(R.string.chat_input_content_description, companionName)
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        tonalElevation = 3.dp,
        modifier = modifier
            .padding(horizontal = 12.dp)
            .imePadding()
            .navigationBarsPadding()
            .padding(top = 4.dp, bottom = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChanged,
                singleLine = true,
                enabled = true,
                shape = RoundedCornerShape(22.dp),
                placeholder = { Text(stringResource(R.string.chat_placeholder, companionName)) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = inputDescription },
            )
            Button(
                onClick = onSend,
                enabled = canSend || isSending,
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                ),
                modifier = Modifier
                    .padding(start = 10.dp)
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    text = if (isSending && input.isBlank()) {
                        stringResource(R.string.chat_cancel)
                    } else {
                        stringResource(R.string.chat_send)
                    },
                )
            }
        }
    }
}
