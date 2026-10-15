package com.littlekitty.xiaokele.info

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.littlekitty.xiaokele.R
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.provider.CompanionPromptIdentity
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.BehaviorTendencyEntity
import com.littlekitty.xiaokele.core.database.entity.MemoryEntity
import com.littlekitty.xiaokele.core.database.entity.UserTraitEntity
import com.littlekitty.xiaokele.core.database.state.BehaviorTendencyStatuses
import com.littlekitty.xiaokele.core.database.state.MemoryStatuses
import com.littlekitty.xiaokele.core.database.state.UserTraitStatuses
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * User-facing, read-only view of the parts of Xiao Ke Le's state that are
 * safe and useful to explain. It deliberately does not render raw prompts,
 * internal rules, confidence values, evidence ids, or edit controls.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun XiaoKeLeInfoScreen(
    database: AppDatabase,
    onBack: () -> Unit,
    onOpenDiary: () -> Unit = {},
    onImportRules: () -> Unit = {},
    onExportRules: () -> Unit = {},
    onRulesPeek: () -> Unit = {},
    rulesStatus: String? = null,
) {
    var snapshot by remember { mutableStateOf<XiaoKeLeInfoSnapshot?>(null) }
    var loadError by remember { mutableStateOf(false) }

    LaunchedEffect(database) {
        loadError = false
        snapshot = runCatching { loadSnapshot(database) }
            .onFailure { loadError = true }
            .getOrNull()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                ),
                title = {
                    Text(
                        text = stringResource(R.string.news_title),
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = onRulesPeek,
                        ),
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.news_back))
                    }
                },
            )
        },
    ) { padding ->
        when {
            loadError -> {
                Text(
                    text = "暂时无法读取状态。",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(padding).padding(16.dp),
                )
            }

            snapshot == null -> {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(padding)
                        .padding(24.dp)
                        .fillMaxSize()
                        .wrapContentWidth(Alignment.CenterHorizontally),
                )
            }

            else -> XiaoKeLeInfoContent(
                snapshot = snapshot!!,
                onOpenDiary = onOpenDiary,
                onImportRules = onImportRules,
                onExportRules = onExportRules,
                rulesStatus = rulesStatus,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun XiaoKeLeInfoContent(
    snapshot: XiaoKeLeInfoSnapshot,
    onOpenDiary: () -> Unit,
    onImportRules: () -> Unit,
    onExportRules: () -> Unit,
    rulesStatus: String?,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            InfoNote("这里展示的是当前愿意让你了解的状态，不会展示完整内部 Prompt，也不能在这里修改。")
        }

        item {
            DiaryNotebookEntry(onClick = onOpenDiary)
        }

        item {
            InfoSection(title = "规则配置") {
                Text("可以导出当前规则，修改 JSON 后再导入。导入成功后，新的触发条件会参与后续场景判断。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onImportRules) { Text("导入 JSON") }
                    OutlinedButton(onClick = onExportRules) { Text("导出当前规则") }
                }
                rulesStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        item {
            InfoSection(title = "现在是什么样") {
                InfoRow("角色", snapshot.character.name)
                InfoRow("和你的关系", snapshot.character.relationship)
                InfoRow("当前性格", snapshot.character.personaAnchor)
                InfoRow("当前陪伴状态", snapshot.petState)
                snapshot.petStateText?.let { InfoRow("此刻想说", it) }
            }
        }

        item {
            InfoSection(title = "它通常怎样和你说话") {
                snapshot.character.expressionDna.forEach { expression ->
                    Text("• $expression", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (snapshot.tendencies.isNotEmpty()) {
            item {
                InfoSection(title = "最近形成的陪伴方式") {
                    snapshot.tendencies.forEach { tendency ->
                        InfoRow(tendency.label, tendency.level)
                    }
                }
            }
        }

        if (snapshot.traits.isNotEmpty()) {
            item {
                InfoSection(title = "它对你的了解") {
                    snapshot.traits.forEach { trait ->
                        InfoRow(trait.layer, trait.text)
                    }
                }
            }
        }

        item {
            Text(
                text = "记忆",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (snapshot.memories.isEmpty()) {
            item { InfoNote("还没有形成可以展示的长期记忆。") }
        } else {
            items(snapshot.memories, key = { it.id }) { memory ->
                MemoryCard(memory)
            }
        }
    }
}

@Composable
private fun DiaryNotebookEntry(onClick: () -> Unit) {
    val notebookContentDescription = stringResource(R.string.diary_notebook_open)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .semantics { contentDescription = notebookContentDescription },
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_notebook),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.diary_notebook_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.diary_notebook_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun InfoSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InfoNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MemoryCard(memory: MemoryDisplay) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(memory.typeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
            memory.subject?.let {
                Text("关于：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private data class XiaoKeLeInfoSnapshot(
    val character: CharacterDisplay,
    val petState: String,
    val petStateText: String?,
    val tendencies: List<TendencyDisplay>,
    val traits: List<TraitDisplay>,
    val memories: List<MemoryDisplay>,
)

private data class CharacterDisplay(
    val name: String,
    val relationship: String,
    val personaAnchor: String,
    val expressionDna: List<String>,
)

private data class TendencyDisplay(val label: String, val level: String)

private data class TraitDisplay(val layer: String, val text: String)

private data class MemoryDisplay(
    val id: String,
    val typeLabel: String,
    val subject: String?,
    val content: String,
)

private suspend fun loadSnapshot(database: AppDatabase): XiaoKeLeInfoSnapshot {
    val now = System.currentTimeMillis()
    val characterJson = database.characterCoreDao().latest()
        ?.contentJson
        ?.let { runCatching { JsonCodec.json.parseToJsonElement(it).jsonObject }.getOrNull() }
        ?: JsonObject(emptyMap())
    val petState = database.petStateDao().get()
    val tendencies = database.behaviorTendencyDao().all()
        .filter { it.status == BehaviorTendencyStatuses.ACTIVE }
        .groupBy { it.key }
        .mapNotNull { (_, rows) -> rows.maxByOrNull { it.updatedAt } }
        .sortedBy { it.key }
        .map { it.toDisplay() }
    val traits = database.userTraitDao().all()
        .filter { it.status == UserTraitStatuses.ACTIVE }
        .sortedByDescending { it.updatedAt }
        .map { it.toDisplay() }
    val memories = database.memoryDao().activeAt(now)
        .filter { it.status == MemoryStatuses.ACTIVE }
        .map { it.toDisplay() }

    return XiaoKeLeInfoSnapshot(
        character = CharacterDisplay(
            name = characterJson.stringValue("display_name") ?: characterJson.stringValue("name")
                ?: CompanionPromptIdentity.FALLBACK_COMPANION_NAME,
            relationship = characterJson.stringValue("relationship") ?: "未确认",
            personaAnchor = characterJson.stringValue("persona_anchor") ?: "会认真听你说话，也会陪你把事情想清楚。",
            expressionDna = characterJson["expression_dna"]
                ?.let { element -> runCatching { element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull() }
                .orEmpty(),
        ),
        petState = petState?.stateId?.let(::petStateLabel) ?: "安静陪伴",
        petStateText = petState?.shortText?.takeIf { it.isNotBlank() },
        tendencies = tendencies,
        traits = traits,
        memories = memories,
    )
}

private fun JsonObject.stringValue(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun BehaviorTendencyEntity.toDisplay(): TendencyDisplay {
    val value = currentValue?.toDoubleOrNull()
    val level = when {
        value == null -> "正在形成"
        value < 0.34 -> "较少表现"
        value > 0.66 -> "比较明显"
        else -> "适度表现"
    }
    return TendencyDisplay(tendencyLabel(key), level)
}

private fun UserTraitEntity.toDisplay(): TraitDisplay {
    val value = runCatching { JsonCodec.json.parseToJsonElement(valueJson) }.getOrNull()
    val text = value?.readableValue().orEmpty().ifBlank { "正在了解中" }
    val layer = if (profileLayer == "CORE") "稳定了解" else "近期了解"
    return TraitDisplay(layer, "${traitLabel(key)}：$text")
}

private fun JsonElement.readableValue(): String = when (this) {
    is JsonPrimitive -> contentOrNull.orEmpty()
    is JsonObject -> listOf("observation", "preference", "description", "summary", "content", "value")
        .firstNotNullOfOrNull { key -> this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } }
        .orEmpty()
    else -> ""
}

private fun MemoryEntity.toDisplay(): MemoryDisplay = MemoryDisplay(
    id = id,
    typeLabel = memoryTypeLabel(type),
    subject = subject,
    content = summary?.takeIf { it.isNotBlank() } ?: content,
)

private fun memoryTypeLabel(type: String): String = when (type) {
    "TEMPORARY_STATE" -> "最近状态"
    "SHORT_TERM_MEMORY" -> "近期记忆"
    "USER_PREFERENCE" -> "你的偏好"
    "RELATIONSHIP_EVENT" -> "你们之间"
    else -> "记忆"
}

private fun tendencyLabel(key: String): String = when (key) {
    "reminder_intensity" -> "提醒频率"
    "pre_shift_reminder_intensity" -> "上班前提醒"
    "proactive_contact_level" -> "主动联系"
    "clinginess_level" -> "依赖和靠近"
    "wronged_expression_level" -> "委屈表达"
    "quiet_expression_level" -> "安静陪伴"
    "food_recommendation_level" -> "美食推荐"
    else -> "陪伴方式"
}

private fun traitLabel(key: String): String = key
    .substringAfterLast('.')
    .replace('_', ' ')
    .ifBlank { "这件事" }

private fun petStateLabel(state: String): String = when (state) {
    "IDLE" -> "安静陪伴"
    "WAITING" -> "在等你"
    "SLEEPING" -> "正在睡觉"
    "CURLED_UP" -> "蜷起来陪着你"
    "EXPECTING" -> "期待你的消息"
    "CARING" -> "正在关心你"
    "WORK_GUARD" -> "陪你工作"
    "WELCOME_BACK" -> "欢迎你回来"
    else -> "正在陪伴你"
}
