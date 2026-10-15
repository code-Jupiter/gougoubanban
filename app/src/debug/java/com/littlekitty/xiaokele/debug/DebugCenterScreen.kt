package com.littlekitty.xiaokele.debug

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.littlekitty.xiaokele.BuildConfig
import com.littlekitty.xiaokele.core.common.protocol.UserProfileLayerNames
import com.littlekitty.xiaokele.floating.FloatingWindowSettingsStore
import com.littlekitty.xiaokele.settings.ByokSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class DebugCenterTab(val label: String) {
    OVERVIEW("概览"),
    REAL_AI("真实AI验收"),
    TASKS("后台任务"),
    DATA("数据管理"),
    LAB("高级实验室"),
}

@Composable
fun DebugCenterScreen(
    dataMaintenance: DebugDataMaintenanceService,
    taskInspector: BackgroundTaskInspector,
    byokSettingsStore: ByokSettingsStore,
    realAiAcceptance: RealAiAcceptanceService,
    onShowPreview: (String, String?) -> String,
    onClosePreview: () -> String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(DebugCenterTab.OVERVIEW) }
    var snapshot by remember { mutableStateOf<DebugDataSnapshot?>(null) }
    var memories by remember { mutableStateOf(emptyList<com.littlekitty.xiaokele.core.database.entity.MemoryEntity>()) }
    var tasks by remember { mutableStateOf(emptyList<DebugTaskStatus>()) }
    var refreshNotice by remember { mutableStateOf("") }
    var clearDialogVisible by remember { mutableStateOf(false) }
    var memoryDeleteId by remember { mutableStateOf<String?>(null) }
    var memoryText by remember { mutableStateOf("") }
    var memoryIntent by remember { mutableStateOf(DebugMemoryIntent.USER_FACT) }
    var memoryNotice by remember { mutableStateOf("") }
    var chatImportJson by remember { mutableStateOf("") }
    var chatImportNotice by remember { mutableStateOf("") }
    var chatImportBusy by remember { mutableStateOf(false) }

    val chatImportFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            chatImportBusy = true
            chatImportNotice = "读取文件中…"
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { readDebugChatImportFile(context, uri) }
                }.onSuccess { raw ->
                    chatImportJson = raw
                    chatImportNotice = "已读取 JSON，请检查后点击“校验并导入”。"
                }.onFailure { error ->
                    chatImportNotice = "读取失败：${error.message?.take(240).orEmpty()}"
                }
                chatImportBusy = false
            }
        }
    }

    suspend fun refreshData() {
        snapshot = dataMaintenance.snapshot()
        memories = dataMaintenance.memories()
        tasks = taskInspector.inspect()
    }

    fun refreshInBackground() {
        scope.launch {
            runCatching { refreshData() }
                .onFailure { refreshNotice = "刷新失败：${it.message?.take(240).orEmpty()}" }
        }
    }

    LaunchedEffect(Unit) { refreshData() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Debug 中心", style = MaterialTheme.typography.headlineSmall)
        Text(
            "按验收任务组织调试能力。这里的操作只存在于 debug 构建，不会出现在 release APK。",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("${BuildConfig.APPLICATION_ID} · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall)

        ScrollableTabRow(selectedTabIndex = selectedTab.ordinal) {
            DebugCenterTab.values().forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.label) },
                )
            }
        }

        when (selectedTab) {
            DebugCenterTab.OVERVIEW -> DebugOverview(
                context = context,
                snapshot = snapshot,
                tasks = tasks,
                byokSettingsStore = byokSettingsStore,
                onRefresh = ::refreshInBackground,
                onRunCore = { selectedTab = DebugCenterTab.REAL_AI },
                onOpenData = { selectedTab = DebugCenterTab.DATA },
            )
            DebugCenterTab.REAL_AI -> RealAiAcceptanceTab(
                service = realAiAcceptance,
                onShowPreview = onShowPreview,
                onClosePreview = onClosePreview,
            )
            DebugCenterTab.TASKS -> DebugTasks(
                tasks = tasks,
                notice = refreshNotice,
                onRefresh = ::refreshInBackground,
                onRunNow = { taskId ->
                    scope.launch {
                        refreshNotice = taskInspector.runNow(taskId)
                        tasks = taskInspector.inspect()
                    }
                },
            )
            DebugCenterTab.DATA -> DebugDataManagement(
                snapshot = snapshot,
                memories = memories,
                memoryText = memoryText,
                selectedIntent = memoryIntent,
                notice = memoryNotice,
                clearDialogVisible = clearDialogVisible,
                selectedMemoryId = memoryDeleteId,
                chatImportJson = chatImportJson,
                chatImportNotice = chatImportNotice,
                chatImportBusy = chatImportBusy,
                onMemoryTextChange = { memoryText = it },
                onIntentChange = { memoryIntent = it },
                onChatImportJsonChange = {
                    chatImportJson = it
                    chatImportNotice = ""
                },
                onSelectChatImportFile = {
                    chatImportFileLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
                },
                onLoadChatImportExample = {
                    chatImportJson = DebugChatHistoryImporter.EXAMPLE_JSON
                    chatImportNotice = "已填入格式示例；示例仅用于说明，请替换为要导入的历史。"
                },
                onImportChatHistory = {
                    if (!chatImportBusy) {
                        chatImportBusy = true
                        chatImportNotice = "校验并导入中…"
                        scope.launch {
                            val result = dataMaintenance.importChatHistory(chatImportJson)
                            chatImportNotice = result.description
                            if (result is DebugChatImportResult.Success) snapshot = dataMaintenance.snapshot()
                            chatImportBusy = false
                        }
                    }
                },
                onAddMemory = {
                    scope.launch {
                        memoryNotice = "保存中…"
                        val result = dataMaintenance.addMemory(memoryText, memoryIntent)
                        memoryNotice = result.message
                        if (result.success) memoryText = ""
                        snapshot = dataMaintenance.snapshot()
                        memories = dataMaintenance.memories()
                    }
                },
                onRequestClearChat = { clearDialogVisible = true },
                onDismissClearChat = { clearDialogVisible = false },
                onConfirmClearChat = {
                    clearDialogVisible = false
                    scope.launch {
                        memoryNotice = dataMaintenance.clearChat().description
                        snapshot = dataMaintenance.snapshot()
                    }
                },
                onArchive = { id ->
                    scope.launch {
                        memoryNotice = if (dataMaintenance.archiveMemory(id)) "记忆已停用。" else "记忆未改变。"
                        memories = dataMaintenance.memories()
                    }
                },
                onRestore = { id ->
                    scope.launch {
                        memoryNotice = if (dataMaintenance.restoreMemory(id)) "记忆已恢复为新的生效记录。" else "记忆恢复失败。"
                        memories = dataMaintenance.memories()
                    }
                },
                onRequestDelete = { memoryDeleteId = it },
                onDismissDelete = { memoryDeleteId = null },
                onConfirmDelete = {
                    val id = memoryDeleteId
                    if (id != null) {
                        memoryDeleteId = null
                        scope.launch {
                            memoryNotice = if (dataMaintenance.deleteMemory(id)) "记忆已删除。" else "记忆未找到。"
                            snapshot = dataMaintenance.snapshot()
                            memories = dataMaintenance.memories()
                        }
                    }
                },
            )
            DebugCenterTab.LAB -> DebugAdvancedLab(
                dataMaintenance = dataMaintenance,
            )
        }
    }
}

@Composable
private fun DebugOverview(
    context: Context,
    snapshot: DebugDataSnapshot?,
    tasks: List<DebugTaskStatus>,
    byokSettingsStore: ByokSettingsStore,
    onRefresh: () -> Unit,
    onRunCore: () -> Unit,
    onOpenData: () -> Unit,
) {
    DebugCard("当前配置") {
        DebugFact("MiniMax Key", if (byokSettingsStore.hasSavedKey()) "已配置 ${byokSettingsStore.fingerprint().orEmpty()}" else "未配置")
        DebugFact("定位", permissionLabel(context, Manifest.permission.ACCESS_COARSE_LOCATION))
        DebugFact("通知", if (Build.VERSION.SDK_INT < 33 || permissionGranted(context, Manifest.permission.POST_NOTIFICATIONS)) "已允许/不需要" else "未允许")
        DebugFact("悬浮窗", if (Settings.canDrawOverlays(context) && FloatingWindowSettingsStore(context).isEnabled()) "权限与开关均已开启" else "权限或开关未就绪")
    }
    DebugCard("开始验收") {
        Text("真实 AI 验收采用生成报文 → 人工确认/编辑 → 提交模型 → 临时预览的两阶段流程。", style = MaterialTheme.typography.bodySmall)
        Button(onClick = onRunCore, modifier = Modifier.fillMaxWidth()) { Text("打开真实 AI 验收") }
        OutlinedButton(onClick = onOpenData, modifier = Modifier.fillMaxWidth()) { Text("管理聊天与记忆") }
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("刷新本地事实") }
    }
    DebugCard("首次创建服务校验（真实接口）") {
        val probeReport = FirstCreateValidatorProbe.latestReport(context)
        var probeAppState by remember { mutableStateOf("app_state 读取中…") }
        LaunchedEffect(Unit) {
            probeAppState = withContext(Dispatchers.IO) { FirstCreateValidatorProbe.appStateSummary(context) }
        }
        Text(
            "对 MiniMax 官方与阿里百炼各执行一次真实 FirstCreateServiceValidator.validate；只展示非敏感结果。",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            probeReport.ifBlank {
                "尚未运行。adb 广播 ${FirstCreateValidatorProbe.ACTION}，或打开探针页面运行；" +
                    "结果同时写入 logcat:${FirstCreateValidatorProbe.TAG}。"
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "上次运行：${FirstCreateValidatorProbe.latestReportedAt(context).ifBlank { "—" }}",
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            probeAppState,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        OutlinedButton(
            onClick = { FirstCreateValidatorProbe.openProbeScreen(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开校验探针页面") }
    }
    DebugCard("后台健康") {
        val failures = tasks.filter { it.lastResult == "失败" }
        if (failures.isEmpty()) Text("最近没有观察到失败任务。", style = MaterialTheme.typography.bodySmall)
        else failures.take(4).forEach { Text("${it.spec.label}：${it.lastSummary ?: "失败"}", style = MaterialTheme.typography.bodySmall) }
        Text("任务已注册、立即运行和历史按期运行是三个不同结论；WorkManager 仍受 Doze 与厂商后台策略影响。", style = MaterialTheme.typography.labelSmall)
    }
    snapshot?.let {
        DebugCard("本地数据摘要") {
            Text("消息 ${it.messageCount}（仅展示最近 12 条） · 摘要 ${it.conversationSummaryCount} · 生效记忆 ${it.activeMemoryCount}/${it.memoryCount}")
            Text("待处理聊天事件 ${it.pendingChatEventCount}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DebugTasks(
    tasks: List<DebugTaskStatus>,
    notice: String,
    onRefresh: () -> Unit,
    onRunNow: (String) -> Unit,
) {
    DebugCard("后台任务") {
        Text("已注册、立即运行成功、过去按预期运行分别展示在不同字段。立即运行会先进入 WorkManager 队列。", style = MaterialTheme.typography.bodySmall)
        Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("刷新任务状态") }
        if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
    }
    tasks.forEach { task ->
        DebugCard(task.spec.label) {
            DebugFact("计划", task.spec.schedule)
            DebugFact("注册", task.registration)
            DebugFact("当前", task.workState)
            DebugFact("最近执行", task.lastRun)
            DebugFact("最近结果", task.lastResult)
            task.lastSummary?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(onClick = { onRunNow(task.spec.id) }, modifier = Modifier.fillMaxWidth()) { Text("立即运行") }
        }
    }
}

@Composable
private fun DebugDataManagement(
    snapshot: DebugDataSnapshot?,
    memories: List<com.littlekitty.xiaokele.core.database.entity.MemoryEntity>,
    memoryText: String,
    selectedIntent: DebugMemoryIntent,
    notice: String,
    clearDialogVisible: Boolean,
    selectedMemoryId: String?,
    chatImportJson: String,
    chatImportNotice: String,
    chatImportBusy: Boolean,
    onMemoryTextChange: (String) -> Unit,
    onIntentChange: (DebugMemoryIntent) -> Unit,
    onChatImportJsonChange: (String) -> Unit,
    onSelectChatImportFile: () -> Unit,
    onLoadChatImportExample: () -> Unit,
    onImportChatHistory: () -> Unit,
    onAddMemory: () -> Unit,
    onRequestClearChat: () -> Unit,
    onDismissClearChat: () -> Unit,
    onConfirmClearChat: () -> Unit,
    onArchive: (String) -> Unit,
    onRestore: (String) -> Unit,
    onRequestDelete: (String) -> Unit,
    onDismissDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    snapshot?.let { current ->
        DebugCard("聊天记录") {
            Text("当前消息 ${current.messageCount} 条（只展示最近 12 条），摘要 ${current.conversationSummaryCount} 条。", style = MaterialTheme.typography.bodySmall)
            Text("清空聊天不会清除已经保存的记忆、画像、人格设定或诊断日志。", style = MaterialTheme.typography.labelSmall)
            current.messages.forEach { message ->
                HorizontalDivider()
                Text(
                    "${if (message.role == "USER") "用户" else "角色"} · ${message.status} · ${debugMessageTime(message.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(message.content.take(240), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onRequestClearChat, modifier = Modifier.fillMaxWidth()) { Text("清空全部聊天记录") }
        }
    }
    DebugCard("导入模拟聊天历史") {
        Text(
            "仅追加到本机 debug 数据库，不调用 AI、不生成记忆或通知。支持粘贴固定 JSON，或从文件读取后检查再导入。",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "schema_version 固定为 1；role 只能是 USER/ASSISTANT；created_at 必须带时区并按时间升序。单次最多 ${DebugChatHistoryImporter.MAX_MESSAGES} 条。",
            style = MaterialTheme.typography.labelSmall,
        )
        OutlinedTextField(
            value = chatImportJson,
            onValueChange = onChatImportJsonChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("聊天历史 JSON") },
            minLines = 6,
            maxLines = 14,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onSelectChatImportFile, enabled = !chatImportBusy, modifier = Modifier.weight(1f)) {
                Text("选择 JSON 文件")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = onLoadChatImportExample, enabled = !chatImportBusy, modifier = Modifier.weight(1f)) {
                Text("填入格式示例")
            }
        }
        Button(
            onClick = onImportChatHistory,
            enabled = chatImportJson.isNotBlank() && !chatImportBusy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (chatImportBusy) "处理中…" else "校验并导入") }
        if (chatImportNotice.isNotBlank()) Text(chatImportNotice, style = MaterialTheme.typography.bodySmall)
    }
    DebugCard("自然语言增加记忆") {
        Text("输入一句话即可保存，不需要 JSON、英文类型或时间常量。", style = MaterialTheme.typography.bodySmall)
        DebugMemoryIntent.values().forEach { intent ->
            OutlinedButton(onClick = { onIntentChange(intent) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (intent == selectedIntent) "✓ ${intent.label}" else intent.label)
            }
            if (intent == selectedIntent) Text(intent.description, style = MaterialTheme.typography.labelSmall)
        }
        OutlinedTextField(
            value = memoryText,
            onValueChange = onMemoryTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("例如：我不喜欢被连续追问为什么不开心") },
            minLines = 2,
        )
        Button(onClick = onAddMemory, enabled = memoryText.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("保存这段记忆") }
        if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
    }
    DebugCard("记忆列表") {
        if (memories.isEmpty()) Text("暂无记忆。", style = MaterialTheme.typography.bodySmall)
        memories.take(20).forEach { memory ->
            HorizontalDivider()
            Text("${memoryStatusLabel(memory.status)} · ${memoryTypeLabel(memory.type)}", style = MaterialTheme.typography.labelSmall)
            Text(memory.content.take(260), style = MaterialTheme.typography.bodySmall)
            Row {
                if (memory.status == "ACTIVE") {
                    TextButton(onClick = { onArchive(memory.id) }) { Text("停用") }
                } else {
                    TextButton(onClick = { onRestore(memory.id) }) { Text("恢复") }
                }
                TextButton(onClick = { onRequestDelete(memory.id) }) { Text("删除") }
            }
        }
    }
    if (clearDialogVisible) {
        AlertDialog(
            onDismissRequest = onDismissClearChat,
            title = { Text("清空全部聊天记录？") },
            text = { Text("这会删除消息和对话摘要，并取消仍可能显示已删除内容的待投递项；记忆、画像和诊断日志会保留。") },
            confirmButton = { TextButton(onClick = onConfirmClearChat) { Text("确认清空") } },
            dismissButton = { TextButton(onClick = onDismissClearChat) { Text("取消") } },
        )
    }
    if (selectedMemoryId != null) {
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("删除这条记忆？") },
            text = { Text("删除后将同时移除该记忆的向量；聊天记录和其他记忆不受影响。") },
            confirmButton = { TextButton(onClick = onConfirmDelete) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = onDismissDelete) { Text("取消") } },
        )
    }
}

@Composable
private fun DebugAdvancedLab(
    dataMaintenance: DebugDataMaintenanceService,
) {
    val scope = rememberCoroutineScope()
    var profileKey by remember { mutableStateOf("") }
    var profileContent by remember { mutableStateOf("") }
    var profileLayer by remember { mutableStateOf(UserProfileLayerNames.DYNAMIC) }
    var characterName by remember { mutableStateOf("") }
    var characterContent by remember { mutableStateOf("") }
    var tendencyKey by remember { mutableStateOf("") }
    var tendencyContent by remember { mutableStateOf("") }
    var profileNotice by remember { mutableStateOf("") }
    var profileSnapshot by remember { mutableStateOf<DebugProfileSnapshot?>(null) }

    LaunchedEffect(Unit) { profileSnapshot = dataMaintenance.profileSnapshot() }

    DebugCard("高级实验室") {
        Text("运行时 Fake 模型、Fake 天气、固定定位、固定排班和“捕获即成功”通知判定已从人工验收装配中解除。以下数据维护表单只修改本地测试资料；安全回归替身仅由自动化测试使用。", style = MaterialTheme.typography.bodySmall)
        Text("如需真实 AI 验收，请使用“真实 AI 验收”页的两阶段报文流程。", style = MaterialTheme.typography.bodySmall)
    }
    DebugCard("画像 / 人格 / 行为倾向表单") {
        Text("主路径不再要求编辑原始 JSON；高级区仍允许维护这些持久化设定。", style = MaterialTheme.typography.bodySmall)
        profileSnapshot?.let { current ->
            Text("画像 ${current.traits.size} 条 · 人格版本 ${current.characterCore?.version ?: 0} · 生效倾向 ${current.tendencies.size} 条", style = MaterialTheme.typography.labelSmall)
            Text("已有画像：${current.traits.take(8).joinToString { it.key.ifBlank { "未命名" } }}", style = MaterialTheme.typography.labelSmall)
        }
        OutlinedTextField(profileKey, { profileKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("画像名称") })
        OutlinedTextField(profileContent, { profileContent = it }, modifier = Modifier.fillMaxWidth(), label = { Text("画像内容") }, minLines = 2)
        Row {
            OutlinedButton(onClick = { profileLayer = UserProfileLayerNames.DYNAMIC }) { Text(if (profileLayer == UserProfileLayerNames.DYNAMIC) "✓ 动态画像" else "动态画像") }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { profileLayer = UserProfileLayerNames.CORE }) { Text(if (profileLayer == UserProfileLayerNames.CORE) "✓ 稳定画像" else "稳定画像") }
        }
        Button(onClick = {
            scope.launch {
                profileNotice = dataMaintenance.saveProfileTrait(profileKey, profileContent, profileLayer)
                profileSnapshot = dataMaintenance.profileSnapshot()
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("保存画像") }
        OutlinedTextField(characterName, { characterName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("人格设定名称") })
        OutlinedTextField(characterContent, { characterContent = it }, modifier = Modifier.fillMaxWidth(), label = { Text("人格设定内容") }, minLines = 2)
        Button(onClick = {
            scope.launch {
                profileNotice = dataMaintenance.saveCharacterCore(characterName, characterContent)
                profileSnapshot = dataMaintenance.profileSnapshot()
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("保存人格设定") }
        OutlinedTextField(tendencyKey, { tendencyKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("行为倾向名称") })
        OutlinedTextField(tendencyContent, { tendencyContent = it }, modifier = Modifier.fillMaxWidth(), label = { Text("行为倾向建议") }, minLines = 2)
        Button(onClick = {
            scope.launch {
                profileNotice = dataMaintenance.saveTendency(tendencyKey, tendencyContent)
                profileSnapshot = dataMaintenance.profileSnapshot()
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("保存行为倾向") }
        if (profileNotice.isNotBlank()) Text(profileNotice, style = MaterialTheme.typography.bodySmall)
    }
    DebugCard("旧版即时预览") {
        Text("已停用：即时调用会绕过“生成报文—人工编辑—提交”确认边界。请使用“真实 AI 验收”页。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun <T> DebugModeButtons(labels: List<Pair<String, T>>, onSelect: (T) -> Unit) {
    labels.forEach { (label, value) ->
        OutlinedButton(onClick = { onSelect(value) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }
}

@Composable
private fun DebugRunSummary(report: DebugRunReport) {
    DebugCard("最近一次验收 · ${report.scenario.label}") {
        Text(
            "runId=${report.runId} · ${report.durationMillis}ms · " +
                if (report.isSuccessful) "最终显示已确认" else "存在未通过或未确认阶段",
            style = MaterialTheme.typography.labelSmall,
        )
        if (!report.isSuccessful) {
            Text("仅有模型/后台返回不代表验收通过；必须所有阶段均为 PASS。", style = MaterialTheme.typography.labelSmall)
        }
        report.stages.forEach { stage ->
            Text("${stageIcon(stage.status)} ${stage.name}：${stage.detail}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DebugCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun DebugFact(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.width(92.dp), style = MaterialTheme.typography.labelMedium)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DebugPayload(title: String, raw: String) {
    Text(title, style = MaterialTheme.typography.labelMedium)
    Text(raw, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth())
}

private fun readDebugChatImportFile(context: Context, uri: android.net.Uri): String {
    val input = context.contentResolver.openInputStream(uri)
        ?: error("无法打开所选文件。")
    return input.bufferedReader().use { reader ->
        val output = StringBuilder()
        val buffer = CharArray(8_192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (output.length + count > DebugChatHistoryImporter.MAX_JSON_CHARACTERS) {
                error("文件超过 ${DebugChatHistoryImporter.MAX_JSON_CHARACTERS / 1_000_000} MB 字符上限。")
            }
            output.append(buffer, 0, count)
        }
        output.toString()
    }
}

private val DEBUG_MESSAGE_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

private fun debugMessageTime(epochMillis: Long): String =
    DEBUG_MESSAGE_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis))

private fun permissionGranted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun permissionLabel(context: Context, permission: String): String =
    if (permissionGranted(context, permission)) "已允许" else "未允许"

private fun memoryStatusLabel(status: String): String = when (status) {
    "ACTIVE" -> "生效"
    "ARCHIVED" -> "已停用"
    "EXPIRED" -> "已过期"
    else -> status
}

private fun memoryTypeLabel(type: String): String = when (type) {
    "USER_PREFERENCE" -> "偏好/事实"
    "SHORT_TERM_MEMORY" -> "短期记忆"
    "RELATIONSHIP_EVENT" -> "关系事件"
    "TEMPORARY_STATE" -> "临时状态"
    else -> type
}

private fun stageIcon(status: DebugRunStageStatus): String = when (status) {
    DebugRunStageStatus.PASS -> "通过"
    DebugRunStageStatus.WARNING -> "警告"
    DebugRunStageStatus.FAIL -> "失败"
    DebugRunStageStatus.SKIPPED -> "跳过"
}

@Composable
private fun RealAiAcceptanceTab(
    service: RealAiAcceptanceService,
    onShowPreview: (String, String?) -> String,
    onClosePreview: () -> String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var env by remember { mutableStateOf<RealAiEnvironment?>(null) }
    var notice by remember { mutableStateOf("") }
    var busyAction by remember { mutableStateOf("") }
    var importLabel by remember { mutableStateOf("") }
    var importJson by remember { mutableStateOf("") }
    var aiLogs by remember { mutableStateOf(emptyList<RealAiLogLine>()) }
    var selectedScenario by remember { mutableStateOf(DebugAcceptanceScenario.INTERACTION_GAP) }
    var prepared by remember { mutableStateOf<DebugPreparedRequest?>(null) }
    var requestBody by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<DebugRealAiResult?>(null) }
    var showDiff by remember { mutableStateOf(false) }
    var humanPreviewConfirmed by remember { mutableStateOf(false) }

    fun refreshEnv() {
        scope.launch {
            env = service.environment()
            aiLogs = service.recentAiLogs()
        }
    }
    LaunchedEffect(Unit) { refreshEnv() }

    fun runAction(id: String, action: suspend () -> String) {
        if (busyAction.isNotEmpty()) return
        busyAction = id
        notice = "执行中…"
        scope.launch {
            try {
                notice = action()
            } catch (error: Throwable) {
                notice = "执行失败：${error.message?.take(300).orEmpty()}"
            } finally {
                busyAction = ""
                refreshEnv()
            }
        }
    }

    val importFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runAction("import_read") {
                val raw = withContext(Dispatchers.IO) { readDebugChatImportFile(context, uri) }
                importJson = raw
                "文件已读取（${raw.length} 字符）。请填写阶段标签后点击“校验并导入”。"
            }
        }
    }

    val envState = env
    val keyMissing = envState != null && !envState.keyConfigured

    DebugCard("当前环境（验收可信度基准）") {
        if (envState == null) {
            Text("读取中…", style = MaterialTheme.typography.bodySmall)
            return@DebugCard
        }
        Text(
            "Git commit: ${envState.gitCommit} · build: ${envState.buildType} · app: ${envState.appVersion}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("APK SHA-256: ${envState.apkSha256}", style = MaterialTheme.typography.bodySmall)
        Text("设备：${envState.deviceModel} · Android ${envState.androidVersion}", style = MaterialTheme.typography.bodySmall)
        Text("时间：${envState.nowText} · 时区 ${envState.timezone}", style = MaterialTheme.typography.bodySmall)
        Text(
            "模型方案：${envState.minimaxHost} · 区域 ${envState.minimaxRegion} · 模型 ${envState.minimaxModel}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Key：${if (envState.keyConfigured) "已配置（指纹 ${envState.keyFingerprint.orEmpty()}）" else "未配置"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "定位=${if (envState.locationFineGranted) "已允许" else "未允许"} · " +
                "后台定位=${if (envState.backgroundLocationGranted) "已允许" else "未允许"} · " +
                "通知=${if (envState.notificationGranted) "已允许" else "未允许"} · " +
                "悬浮窗权限=${if (envState.overlayGranted) "已允许" else "未允许"} · " +
                "悬浮窗开关=${if (envState.floatingWindowEnabled) "开" else "关"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "天气服务：${if (envState.weatherConfigured) "已配置" else "未配置"}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text("来源说明：${envState.sourceSummary}", style = MaterialTheme.typography.bodySmall)
        Text(
            "当前阶段：${envState.stage.ifBlank { "未设置" }} · Run ID：${envState.runId.ifBlank { "未创建" }}",
            style = MaterialTheme.typography.bodySmall,
        )
        if (envState.importedInputs.isEmpty()) {
            Text("已导入输入：无", style = MaterialTheme.typography.bodySmall)
        } else {
            envState.importedInputs.forEach { input ->
                Text(
                    "已导入 [${input.label}] ${input.insertedCount} 条（跳过 ${input.skippedCount}）" +
                        " sha256=${input.sha256.take(12)}… ${input.firstCreatedAt}~${input.lastCreatedAt} @${input.importedAt}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    DebugCard("真实 AI 验收模式") {
        Text("本页没有脚本模型、固定成功消息或 Fake 外部数据开关。所有提交都强制走当前配置的真实模型方案；场景条件若缺少真实数据会 fail-closed。", style = MaterialTheme.typography.bodySmall)
        Text("本次 Debug 测试不会写入聊天记录、事件、计划、todo、投递历史、未读数、主动预算或本地生活已提登记。", style = MaterialTheme.typography.bodySmall)
    }

    DebugCard("阶段与运行 ID") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("一", "二", "三").forEach { stage ->
                OutlinedButton(
                    onClick = {
                        service.setStage(stage)
                        notice = "当前阶段已设为 阶段$stage。"
                        refreshEnv()
                    },
                    enabled = busyAction.isEmpty(),
                ) { Text(if (envState?.stage == stage) "阶段$stage ✓" else "阶段$stage") }
            }
        }
        Button(
            onClick = { runAction("new_run") { val id = service.newRunId(); "已创建新运行 ID：$id" } },
            modifier = Modifier.fillMaxWidth(),
            enabled = busyAction.isEmpty(),
        ) { Text("创建新 Run ID（清空已导入登记，不动数据）") }
        OutlinedButton(
            onClick = { runAction("baseline") { service.captureBaselineSnapshot() } },
            modifier = Modifier.fillMaxWidth(),
            enabled = busyAction.isEmpty(),
        ) { Text("保留当前数据并导出基线快照（不清空）") }
    }

    RealAiActionCard(
        title = "重置验收基线（保留 Key 与设置）",
        purpose = "为每个阶段提供相同的干净数据库，隔离三个阶段的聊天、记忆、预算与去重键。",
        prerequisites = "无需权限；建议在导出上一阶段快照后执行。",
        userAction = "确认上一阶段证据已落盘后点击。",
        cost = "不调用 MiniMax，无费用。",
        dbImpact = "清空消息/摘要/事件/记忆/画像/行为/待办/投递/日志等表与相关缓存；保留 Key、区域、模型、排班 URL、天气 Key。",
        sourceMode = "模拟条件（数据清理），不产生 AI 输出。",
        resultLocation = "页面提示 + 数据管理页快照。",
        cleanup = "本身就是清理动作；不可恢复（仅限测试数据）。",
        disabledReason = if (busyAction.isNotEmpty()) "有操作执行中" else null,
        buttonText = "重置基线",
        busy = busyAction == "reset",
        enabled = busyAction.isEmpty(),
        onClick = { runAction("reset") { service.resetBaseline() } },
    )

    DebugCard("导入阶段聊天历史") {
        Text("目的：导入分阶段历史 JSON（schema 1），记录文件哈希与条数；导入本身不调用 AI。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = importLabel,
            onValueChange = { importLabel = it },
            label = { Text("阶段标签（如：阶段一 / 阶段二修复副本）") },
            modifier = Modifier.fillMaxWidth(),
        )
        if (importJson.isBlank()) {
            OutlinedButton(
                onClick = { importFileLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
                modifier = Modifier.fillMaxWidth(),
                enabled = busyAction.isEmpty(),
            ) { Text("选择 JSON 文件（Download/lk_stage*.json）") }
        } else {
            Text("已读取文件内容（${importJson.length} 字符）。", style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = {
                runAction("import") {
                    val label = importLabel.ifBlank { "未命名输入" }
                    val json = importJson
                    val result = service.importStage(json, label)
                    importJson = ""
                    result
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = busyAction.isEmpty() && importJson.isNotBlank(),
        ) { Text("校验并导入") }
        Text(
            "前置条件：文件符合 schema 1（USER/ASSISTANT、ISO-8601 带时区、升序）。\n" +
                "费用：无（不调用 MiniMax）。数据库：追加 messages。\n" +
                "结果位置：页面提示与已导入列表。清理：随“重置基线”。",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    RealAiActionCard(
        title = "运行真实归纳与记忆提取",
        purpose = "对全部未压缩历史强制执行一次当前模型方案的真实会话归纳 + 记忆/画像/行为提取。",
        prerequisites = "当前模型方案 Key 已配置；已导入聊天历史。",
        userAction = "点击后等待（多条历史可能持续数十秒到数分钟）。",
        cost = "调用当前模型方案，产生费用。",
        dbImpact = "写入 conversation_summaries、memories、user_traits、behavior_tendencies 等。",
        sourceMode = "当前模型方案真实直连；输入为已导入历史。",
        resultLocation = "页面提示；明细在诊断日志 CONVERSATION_CONSOLIDATION / MEMORY_ANALYSIS 与导出的 trace。",
        cleanup = "随“重置基线”清除。",
        disabledReason = when {
            busyAction.isNotEmpty() -> "有操作执行中"
            keyMissing -> "缺少当前模型方案 Key"
            else -> null
        },
        buttonText = "运行归纳与记忆提取",
        busy = busyAction == "consolidate",
        enabled = busyAction.isEmpty() && !keyMissing,
        onClick = { runAction("consolidate") { service.runConsolidationNow() } },
    )

    DebugCard("选择场景（全部只读 dry-run）") {
        Text("先选择场景并点击“生成报文（暂不发送）”。生成阶段不联网、不写业务表；只有你确认编辑器内容后，点击“提交真实模型”才会产生一次真实请求。", style = MaterialTheme.typography.bodySmall)
        listOf(
            DebugAcceptanceScenario.INTERACTION_GAP,
            DebugAcceptanceScenario.WAKE_WEATHER,
            DebugAcceptanceScenario.DISCOVERY_NUDGE,
            DebugAcceptanceScenario.FRONT_CHAT,
            DebugAcceptanceScenario.INVALID_BACKGROUND_OUTPUT,
            DebugAcceptanceScenario.DIRECT_CHAT,
            DebugAcceptanceScenario.PLAN_HISTORY,
            DebugAcceptanceScenario.EXPLICIT_ADVICE,
            DebugAcceptanceScenario.NO_ADVICE_BOUNDARY,
            DebugAcceptanceScenario.MEMORY_CORRECTION,
            DebugAcceptanceScenario.CLOSE_CONVERSATION,
            DebugAcceptanceScenario.NO_ACTION,
            DebugAcceptanceScenario.DIARY_SUMMARY,
            DebugAcceptanceScenario.CREATIVE_CANDIDATE,
            DebugAcceptanceScenario.MEMORY_SUMMARY,
            DebugAcceptanceScenario.INTERACTION_IDLE,
            DebugAcceptanceScenario.SCENE_GATE_BLOCKED,
            DebugAcceptanceScenario.PROACTIVE_BUDGET_EXHAUSTED,
            DebugAcceptanceScenario.LOCAL_FOOD_SEARCH,
            DebugAcceptanceScenario.LOCAL_EVENTS_SEARCH,
            DebugAcceptanceScenario.LOCATION_MISSING,
        ).forEach { scenario ->
            OutlinedButton(onClick = { selectedScenario = scenario; prepared = null; result = null; humanPreviewConfirmed = false }, modifier = Modifier.fillMaxWidth()) {
                Text(if (selectedScenario == scenario) "✓ ${scenario.label}" else scenario.label)
            }
        }
        Text("用途：${scenarioPurpose(selectedScenario)}", style = MaterialTheme.typography.bodySmall)
        Text("依赖：${scenarioDependencies(selectedScenario)}", style = MaterialTheme.typography.bodySmall)
        Text("当前来源：生成后以来源标签为准；缺失真实接口/权限不会自动替换为固定数据。", style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                runAction("prepare") {
                    val value = service.prepareScenario(selectedScenario).getOrElse { throw it }
                    prepared = value
                    requestBody = value.requestBody
                    result = null
                    humanPreviewConfirmed = false
                    "报文已生成：${value.sources.joinToString("、")}；尚未发送。"
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = busyAction.isEmpty() && !keyMissing,
        ) { Text("生成报文（暂不发送）") }
    }

    prepared?.let { current ->
        DebugCard("报文编辑器 · ${current.scenario.label}") {
            Text("模型：${envState?.minimaxModel.orEmpty()} · 区域：${envState?.minimaxRegion.orEmpty()} · endpoint host：${envState?.minimaxHost.orEmpty()}", style = MaterialTheme.typography.labelSmall)
            Text("生成时间：${current.generatedAt} · 来源：${current.sources.joinToString(", ")}", style = MaterialTheme.typography.labelSmall)
            OutlinedTextField(
                value = requestBody,
                onValueChange = { requestBody = it; result = null },
                label = { Text("实际发送的请求正文（不含 Authorization）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 12,
                maxLines = 24,
            )
            val errors = DebugRequestBodyValidator.validate(requestBody, service.expectedModel(current.frontChat))
            if (errors.isEmpty()) Text("JSON 与必填字段校验通过 · ${requestBody.length} 字符", style = MaterialTheme.typography.labelSmall)
            else errors.forEach { Text("校验失败：$it", style = MaterialTheme.typography.labelSmall) }
            if (requestBody != current.requestBody) {
                Text("已标记为 USER_EDITED：提交时使用编辑器当前内容，不会重新构造并丢弃你的修改。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { showDiff = !showDiff }) { Text(if (showDiff) "隐藏修改差异" else "查看修改差异") }
                if (showDiff) Text("系统版本 sha256=${shortHash(current.requestBody)}；当前版本 sha256=${shortHash(requestBody)}。差异以编辑器中的 USER_EDITED 正文为准。", style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { requestBody = current.requestBody; result = null }, enabled = requestBody != current.requestBody) { Text("恢复系统生成版本") }
                Button(
                    onClick = { runAction("submit") { result = service.submitPreparedRequest(current, requestBody); humanPreviewConfirmed = false; "提交完成：${result?.transportEvidence.orEmpty()}" } },
                    enabled = busyAction.isEmpty() && errors.isEmpty() && !keyMissing,
                ) { Text(if (busyAction == "submit") "提交中…" else "提交真实模型") }
            }
            Text("提交后只显示 Debug 页面和临时预览，不写入正式聊天、预算、未读、todo、plan、投递历史或已提登记。", style = MaterialTheme.typography.bodySmall)
        }
    }

    result?.let { submitted ->
        DebugCard("真实模型结果") {
            Text(submitted.transportEvidence, style = MaterialTheme.typography.bodySmall)
            submitted.checks.forEach { check -> Text("${check.status} · ${check.name}：${check.detail}", style = MaterialTheme.typography.bodySmall) }
            submitted.content?.let { Text("展示内容：$it", style = MaterialTheme.typography.bodySmall) }
            Text("animation_id：${submitted.animationId ?: "未返回（合法降级 default_idle）"}", style = MaterialTheme.typography.bodySmall)
            if (submitted.canPreview && submitted.content != null) {
                Button(onClick = { notice = onShowPreview(submitted.content, submitted.animationId) }, modifier = Modifier.fillMaxWidth()) { Text("在浮窗中预览本次结果") }
                OutlinedButton(onClick = { notice = onShowPreview("", submitted.animationId) }, modifier = Modifier.fillMaxWidth()) { Text("仅预览动画") }
                Text("点击后会立即显示 Debug 浮窗预览，无需等待后台任务。预览约 15 秒，不会进入 pending 队列。", style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = { notice = onClosePreview() }, modifier = Modifier.fillMaxWidth()) { Text("关闭预览") }
                Button(onClick = { humanPreviewConfirmed = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (humanPreviewConfirmed) "✓ 已人工看到浮窗与动效" else "我已看到浮窗与动效")
                }
                Text("自动检查只证明调用发生，不代替肉眼验收。", style = MaterialTheme.typography.bodySmall)
            }
            if (submitted.rawResponseBody.isNotBlank()) DebugPayload("原始响应正文（应用私有 Debug 页面）", submitted.rawResponseBody)
            if (submitted.decisionJson.isNotBlank()) DebugPayload("解析后的 AgentDecision", submitted.decisionJson)
        }
    }

    RealAiActionCard(
        title = "导出验收快照",
        purpose = "落盘本阶段按 Run ID 关联的脱敏 Agent 逐轮 trace，作为阶段证据。",
        prerequisites = "无；建议在阶段测试完成后、重置基线前执行。",
        userAction = "点击后通过 adb 拉取 /sdcard/Android/data/com.littlekitty.xiaokele/files/acceptance/。",
        cost = "无。",
        dbImpact = "只读导出；会先执行 WAL checkpoint。",
        sourceMode = "不产生 AI 输出。",
        resultLocation = "应用外部目录 acceptance/<runId>/<时间戳>/；不导出数据库、正文或精确位置。",
        cleanup = "无需清理（导出目录可保留为证据）。",
        disabledReason = if (busyAction.isNotEmpty()) "有操作执行中" else null,
        buttonText = "导出快照",
        busy = busyAction == "export",
        enabled = busyAction.isEmpty(),
        onClick = { runAction("export") { service.exportAcceptanceSnapshot() } },
    )

    DebugCard("最近 AI 记录（逐轮）") {
        if (aiLogs.isEmpty()) {
            Text("暂无记录。", style = MaterialTheme.typography.bodySmall)
        } else {
            aiLogs.forEach { line ->
                Text(
                    "${debugMessageTime(line.recordedAt)} [${line.name}] ${line.status}" +
                        (line.subjectId?.let { " · ${it.take(40)}" } ?: "") +
                        (line.summary?.let { " · ${it.take(80)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text("逐轮状态、哈希、工具名与耗时会写入脱敏 acceptance trace；正文仍留在应用私有存储。", style = MaterialTheme.typography.labelSmall)
        }
    }

    if (notice.isNotBlank()) {
        DebugCard("执行结果") { Text(notice, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun scenarioPurpose(scenario: DebugAcceptanceScenario): String = when (scenario) {
    DebugAcceptanceScenario.INTERACTION_GAP -> "验证久未互动场景的真实上下文与低压力承接。"
    DebugAcceptanceScenario.WAKE_WEATHER -> "验证未来 24 小时天气的无声通知语义。"
    DebugAcceptanceScenario.DISCOVERY_NUDGE -> "验证最多轻提一条真实本地生活候选并关联 DISCUSS_LATER。"
    DebugAcceptanceScenario.FRONT_CHAT -> "验证普通前台聊天请求，但不把 Debug 输入写入正式聊天。"
    DebugAcceptanceScenario.INVALID_BACKGROUND_OUTPUT -> "安全回归：确认非法模型输出被生产校验器拦截。"
    DebugAcceptanceScenario.DIRECT_CHAT -> "验证普通聊天 A 同次产出 Brief，再由 HER 表达，不固定调用 B1。"
    DebugAcceptanceScenario.PLAN_HISTORY -> "验证需要历史材料时才进入 A→B1→B2→C。"
    DebugAcceptanceScenario.EXPLICIT_ADVICE -> "验证用户明确要办法时任务优先，不强制安慰。"
    DebugAcceptanceScenario.NO_ADVICE_BOUNDARY -> "验证不要建议/不要追问边界立即生效。"
    DebugAcceptanceScenario.MEMORY_CORRECTION -> "验证用户纠正会进入事实与记忆审查。"
    DebugAcceptanceScenario.CLOSE_CONVERSATION -> "验证告别可返回 CLOSE，不制造接续。"
    DebugAcceptanceScenario.NO_ACTION -> "验证后台 NO_ACTION 不调用 HER。"
    DebugAcceptanceScenario.DIARY_SUMMARY -> "验证跨日原始对话才能形成日记总结候选。"
    DebugAcceptanceScenario.CREATIVE_CANDIDATE -> "验证虚构作品不反写成现实记忆。"
    DebugAcceptanceScenario.MEMORY_SUMMARY -> "验证 D_MEMORY→C_REVIEW→程序提交边界。"
    DebugAcceptanceScenario.INTERACTION_IDLE -> "验证空闲续话共用主动预算和表达账本。"
    DebugAcceptanceScenario.SCENE_GATE_BLOCKED -> "验证场景门禁拒绝不产生模型副作用。"
    DebugAcceptanceScenario.PROACTIVE_BUDGET_EXHAUSTED -> "验证每日主动预算耗尽时不投递但不伪造成功。"
    DebugAcceptanceScenario.LOCAL_FOOD_SEARCH -> "验证中文本地生活美食搜索与来源 URL。"
    DebugAcceptanceScenario.LOCAL_EVENTS_SEARCH -> "验证中文本地生活活动搜索与缓存。"
    DebugAcceptanceScenario.LOCATION_MISSING -> "验证没有真实定位时本地生活搜索 fail-closed。"
    else -> "兼容场景；请优先使用上方八项。"
}

private fun scenarioDependencies(scenario: DebugAcceptanceScenario): String = when (scenario) {
    DebugAcceptanceScenario.INTERACTION_GAP -> "本地历史上下文；间隔条件是明确标记的测试触发输入。"
    DebugAcceptanceScenario.WAKE_WEATHER -> "设备定位权限与可用天气缓存/接口；缺失时不补固定天气。"
    DebugAcceptanceScenario.DISCOVERY_NUDGE -> "48 小时内真实本地生活缓存及来源 URL；无缓存则 fail-closed。"
    DebugAcceptanceScenario.FRONT_CHAT -> "真实本地上下文；请求正文可人工编辑。"
    DebugAcceptanceScenario.INVALID_BACKGROUND_OUTPUT -> "仅用于安全回归，不计为真实 AI 内容验收。"
    DebugAcceptanceScenario.DIRECT_CHAT,
    DebugAcceptanceScenario.PLAN_HISTORY,
    DebugAcceptanceScenario.EXPLICIT_ADVICE,
    DebugAcceptanceScenario.NO_ADVICE_BOUNDARY,
    DebugAcceptanceScenario.MEMORY_CORRECTION,
    DebugAcceptanceScenario.CLOSE_CONVERSATION,
    DebugAcceptanceScenario.NO_ACTION,
    DebugAcceptanceScenario.DIARY_SUMMARY,
    DebugAcceptanceScenario.MEMORY_SUMMARY,
    DebugAcceptanceScenario.CREATIVE_CANDIDATE -> "当前原始中文对话；测试输入只进入 Debug 报文，不自动写正式表。"
    DebugAcceptanceScenario.INTERACTION_IDLE -> "已有正式聊天锚点与空闲续话策略。"
    DebugAcceptanceScenario.SCENE_GATE_BLOCKED -> "真实场景策略；门禁拒绝时不调用 HER。"
    DebugAcceptanceScenario.PROACTIVE_BUDGET_EXHAUSTED -> "持久主动预算状态；预算耗尽时不写主动消息。"
    DebugAcceptanceScenario.LOCAL_FOOD_SEARCH,
    DebugAcceptanceScenario.LOCAL_EVENTS_SEARCH -> "设备定位、MiniMax coding-plan search、48 小时缓存及来源 URL。"
    DebugAcceptanceScenario.LOCATION_MISSING -> "没有定位权限或定位失败；必须 fail-closed，不回退固定地区。"
    else -> "当前设备真实上下文"
}

private fun shortHash(raw: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

@Composable
private fun RealAiActionCard(
    title: String,
    purpose: String,
    prerequisites: String,
    userAction: String,
    cost: String,
    dbImpact: String,
    sourceMode: String,
    resultLocation: String,
    cleanup: String,
    disabledReason: String?,
    buttonText: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    DebugCard(title) {
        Text("目的：$purpose", style = MaterialTheme.typography.bodySmall)
        Text("前置条件：$prerequisites", style = MaterialTheme.typography.bodySmall)
        Text("需要你做的：$userAction", style = MaterialTheme.typography.bodySmall)
        Text("费用：$cost", style = MaterialTheme.typography.bodySmall)
        Text("数据库影响：$dbImpact", style = MaterialTheme.typography.bodySmall)
        Text("真实/模拟：$sourceMode", style = MaterialTheme.typography.bodySmall)
        Text("结果位置：$resultLocation", style = MaterialTheme.typography.bodySmall)
        Text("清理：$cleanup", style = MaterialTheme.typography.bodySmall)
        if (disabledReason != null) {
            Text("当前不可用：$disabledReason", style = MaterialTheme.typography.labelSmall)
        }
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        ) { Text(if (busy) "执行中…" else buttonText) }
    }
}
