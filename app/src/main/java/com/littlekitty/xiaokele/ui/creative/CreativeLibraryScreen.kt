package com.littlekitty.xiaokele.ui.creative

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.littlekitty.xiaokele.R
import com.littlekitty.xiaokele.creative.CreativeLibraryAccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreativeLibraryScreen(
    access: CreativeLibraryAccess,
    onBack: () -> Unit,
) {
    var topics by remember { mutableStateOf<List<JsonObject>>(emptyList()) }
    var selectedWork by remember { mutableStateOf<JsonObject?>(null) }
    var selectedCandidate by remember { mutableStateOf<JsonObject?>(null) }
    var isUnlocked by remember { mutableStateOf(false) }
    var isUnlocking by remember { mutableStateOf(false) }
    var unlockProgress by remember { mutableStateOf(0f) }
    val animatedUnlockProgress by animateFloatAsState(
        targetValue = unlockProgress,
        animationSpec = tween(if (isUnlocking) 650 else 180),
        label = "diaryUnlockProgress",
    )
    val scope = rememberCoroutineScope()

    LaunchedEffect(isUnlocking) {
        if (isUnlocking) {
            delay(760)
            isUnlocked = true
            isUnlocking = false
        }
    }

    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            topics = access.listAvailableTopics(100)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            selectedWork != null -> stringResource(R.string.diary_notebook_content_title)
                            selectedCandidate != null -> stringResource(R.string.diary_notebook_idea_title)
                            else -> stringResource(R.string.diary_notebook_title)
                        },
                    )
                },
                navigationIcon = {
                    TextButton(onClick = {
                        when {
                            selectedWork != null -> selectedWork = null
                            selectedCandidate != null -> selectedCandidate = null
                            else -> onBack()
                        }
                    }) { Text("返回") }
                },
            )
        },
    ) { padding ->
        if (!isUnlocked) {
            DiaryUnlockGate(
                modifier = Modifier.padding(padding),
                progress = animatedUnlockProgress,
                isUnlocking = isUnlocking,
                onProgressChange = { unlockProgress = it },
                onUnlockRequest = {
                    if (!isUnlocking) {
                        unlockProgress = 1f
                        isUnlocking = true
                    }
                },
            )
        } else {
            AnimatedVisibility(
                visible = true,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(tween(360)) +
                    slideInVertically(tween(480)) { it / 6 } +
                    scaleIn(tween(420), initialScale = 0.97f),
            ) {
                if (selectedWork != null) {
            val work = selectedWork!!
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(work.string("title") ?: stringResource(R.string.diary_notebook_untitled_page), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(workTypeLabel(work.string("work_type")), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(work.string("content") ?: "", style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.diary_notebook_work_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
                } else if (selectedCandidate != null) {
            val candidate = selectedCandidate!!
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(candidate.string("theme") ?: "未命名灵感", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(candidate.string("idea_seed") ?: "", style = MaterialTheme.typography.bodyLarge)
                candidate.string("dog_feeling_seed")?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.diary_notebook_candidate_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
                } else if (topics.isEmpty()) {
            Text(
                text = stringResource(R.string.diary_notebook_empty),
                modifier = Modifier.padding(padding).padding(20.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
                } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(topics, key = { it.string("id") ?: it.hashCode().toString() }) { topic ->
                    Column(
                        modifier = Modifier.fillMaxWidth().clickable {
                            val workId = topic.string("work_id")
                            val candidateId = topic.string("candidate_id")
                            if (workId != null || candidateId != null) {
                                topics = topics.filterNot { it.string("id") == topic.string("id") }
                                scope.launch {
                                    selectedWork = workId?.let { access.readWork(it) }
                                    selectedCandidate = candidateId?.let { access.readCandidate(it) }
                                    access.markTopicDiscussed(topic.string("id").orEmpty())
                                }
                            }
                        }.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(topic.string("topic_summary") ?: "未命名话题", fontWeight = FontWeight.Medium)
                        Text(
                            topic.string("opening_hint") ?: "留下的一点念头",
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
                }
            }
        }
    }
}

@Composable
private fun DiaryUnlockGate(
    modifier: Modifier = Modifier,
    progress: Float,
    isUnlocking: Boolean,
    onProgressChange: (Float) -> Unit,
    onUnlockRequest: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(170.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_notebook),
                        contentDescription = null,
                        modifier = Modifier
                            .size(116.dp)
                            .align(Alignment.Center)
                            .offset(x = (-36).dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        painter = painterResource(
                            if (progress > 0.9f) R.drawable.ic_lock_open else R.drawable.ic_lock,
                        ),
                        contentDescription = null,
                        modifier = Modifier
                            .size(56.dp)
                            .align(Alignment.Center)
                            .offset(x = 56.dp)
                            .graphicsLayer {
                                rotationZ = progress * 62f
                                translationX = progress * 8.dp.toPx()
                            },
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    text = stringResource(R.string.diary_notebook_locked_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                UnlockTrack(
                    progress = progress,
                    isUnlocking = isUnlocking,
                    onProgressChange = onProgressChange,
                    onUnlockRequest = onUnlockRequest,
                )
                Button(
                    onClick = onUnlockRequest,
                    enabled = !isUnlocking,
                ) {
                    Text(stringResource(R.string.diary_notebook_unlock_button))
                }
                if (isUnlocking) {
                    Text(
                        text = stringResource(R.string.diary_notebook_unlocking),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnlockTrack(
    progress: Float,
    isUnlocking: Boolean,
    onProgressChange: (Float) -> Unit,
    onUnlockRequest: () -> Unit,
) {
    val currentProgress by rememberUpdatedState(progress)
    val currentOnProgressChange by rememberUpdatedState(onProgressChange)
    val currentOnUnlockRequest by rememberUpdatedState(onUnlockRequest)
    val unlockDescription = stringResource(R.string.diary_notebook_unlock_hint)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                contentDescription = unlockDescription
            }
            .pointerInput(isUnlocking) {
                var dragProgress = 0f
                detectDragGestures(
                    onDragStart = { dragProgress = currentProgress },
                    onDragEnd = {
                        if (dragProgress >= 0.78f) {
                            currentOnUnlockRequest()
                        } else {
                            currentOnProgressChange(0f)
                        }
                    },
                    onDragCancel = { currentOnProgressChange(0f) },
                ) { change, dragAmount ->
                    change.consume()
                    if (!isUnlocking) {
                        dragProgress = (dragProgress + dragAmount.x / size.width).coerceIn(0f, 1f)
                        currentOnProgressChange(dragProgress)
                    }
                }
            },
    ) {
        val density = LocalDensity.current
        val handleTravel = with(density) { (maxWidth - 64.dp).toPx() }
        val handleOffset = (handleTravel * progress).roundToInt()
        Box(
            modifier = Modifier
                .offset { IntOffset(handleOffset, 0) }
                .size(64.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.primary)
                .graphicsLayer {
                    rotationZ = progress * 10f
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_key),
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun workTypeLabel(type: String?): String = when (type) {
    "DAILY_DIARY" -> "每日日记"
    "WEEKLY_DIARY" -> "每周手记"
    "MONTHLY_DIARY" -> "月度手记"
    "DOG_LIFE_ESSAY" -> "小狗随笔"
    "FABLE" -> "小寓言"
    "MICRO_STORY" -> "微型故事"
    "INSPIRED_SHORT_STORY" -> "灵感短篇"
    "BEDTIME_STORY" -> "睡前故事"
    "USER_SAVED_POEM" -> "小诗"
    else -> "日记本内容"
}
