package com.littlekitty.xiaokele.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.littlekitty.xiaokele.R
import com.littlekitty.xiaokele.diagnostics.DiagnosticsExportService
import com.littlekitty.xiaokele.floating.FloatingWindowSettingsStore
import com.littlekitty.xiaokele.provider.AgentModelRoute
import com.littlekitty.xiaokele.provider.BailianConnectionTester
import com.littlekitty.xiaokele.provider.MiniMaxConnectionTester
import com.littlekitty.xiaokele.provider.ModelProvider
import com.littlekitty.xiaokele.provider.TaskKind
import com.littlekitty.xiaokele.provider.CompanionFallbackReplyWarmup
import kotlinx.coroutines.launch

/**
 * Settings hub for the public product. Provider credentials, tests and
 * diagnostics are separated into drill-down pages instead of one long scroll.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    store: ByokSettingsStore,
    tester: MiniMaxConnectionTester,
    bailianTester: BailianConnectionTester,
    embeddingStore: EmbeddingSettingsStore,
    embeddingTester: EmbeddingConnectionTester,
    qWeatherStore: QWeatherSettingsStore,
    qWeatherTester: QWeatherConnectionTester,
    onBack: () -> Unit,
    floatingWindowSettingsStore: FloatingWindowSettingsStore,
    diagnosticsExportService: DiagnosticsExportService,
    fallbackReplyWarmup: CompanionFallbackReplyWarmup? = null,
    openDiagnosticsOnEntry: Boolean = false,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val state = remember(
        store,
        embeddingStore,
        qWeatherStore,
        floatingWindowSettingsStore,
        fallbackReplyWarmup,
        openDiagnosticsOnEntry,
    ) {
        SettingsScreenState(
            context = context,
            scope = scope,
            store = store,
            miniMaxTester = tester,
            bailianTester = bailianTester,
            embeddingStore = embeddingStore,
            embeddingTester = embeddingTester,
            qWeatherStore = qWeatherStore,
            qWeatherTester = qWeatherTester,
            floatingWindowSettingsStore = floatingWindowSettingsStore,
            diagnosticsExportService = diagnosticsExportService,
            fallbackReplyWarmup = fallbackReplyWarmup,
        ).also { state ->
            if (openDiagnosticsOnEntry) state.navigate(SettingsPage.DIAGNOSTICS)
        }
    }

    val configImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            state.configTransferBusy = false
        } else {
            scope.launch {
                state.settingsTransferService.importFromUri(context.contentResolver, uri).fold(
                    onSuccess = { document ->
                        state.applyImportedDocument(document)
                        state.notify("配置已导入，API Key 已恢复到系统安全存储")
                    },
                    onFailure = { error -> state.notify("配置导入失败：${error.message ?: "文件格式无效"}") },
                )
                state.configTransferBusy = false
            }
        }
    }
    val configExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) {
            state.configTransferBusy = false
        } else {
            scope.launch {
                state.settingsTransferService.exportToUri(
                    context.contentResolver,
                    uri,
                    state.currentSettingsTransferInput(),
                ).fold(
                    onSuccess = { state.notify("配置已导出，请妥善保管包含 API Key 的文件") },
                    onFailure = { error -> state.notify("配置导出失败：${error.message ?: "未知错误"}") },
                )
                state.configTransferBusy = false
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.onResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = state.page != SettingsPage.HOME) { state.navigateUp() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(state.snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f),
                ),
                title = {
                    Text(if (state.page == SettingsPage.HOME) stringResource(R.string.byok_settings_title) else state.page.title)
                },
                navigationIcon = {
                    TextButton(onClick = { if (state.page == SettingsPage.HOME) onBack() else state.navigateUp() }) {
                        Text(stringResource(R.string.news_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.page) {
                SettingsPage.HOME -> SettingsHomePage(state)
                SettingsPage.MINIMAX -> MiniMaxSettingsPage(state)
                SettingsPage.BAILIAN -> BailianSettingsPage(state)
                SettingsPage.BAILIAN_MODELS -> BailianChatModelPage(state)
                SettingsPage.WEB_SEARCH -> WebSearchSettingsPage(state)
                SettingsPage.EMBEDDING -> EmbeddingSettingsPage(state)
                SettingsPage.AGENT_ROUTES -> AgentRouteSettingsPage(state)
                SettingsPage.QWEATHER -> QWeatherSettingsPage(state)
                SettingsPage.TRANSFER -> ConfigTransferSettingsPage(
                    state = state,
                    onImport = {
                        state.configTransferBusy = true
                        configImportLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
                    },
                    onExport = {
                        state.configTransferBusy = true
                        configExportLauncher.launch("dog-companion-settings.json")
                    },
                )
                SettingsPage.DIAGNOSTICS -> DiagnosticsSettingsPage(state)
            }
        }
    }
    SettingsTestOutcomeDialog(state.testOutcome) { state.testOutcome = null }
}

@Composable
private fun SettingsHomePage(state: SettingsScreenState) {
    SettingsPageColumn {
        Text(stringResource(R.string.byok_mode_title), style = MaterialTheme.typography.titleMedium)
        SettingsHint(
            "狗狗伴伴只调用你自己配置的供应商，不会在服务之间偷偷切换 Key。" +
                "可以只配一家，也可以多家都配；配好后在「Agent 路由」里为每个 Agent 分别选择模型。",
        )

        SettingsSectionTitle("AI 服务")
        SettingsEntryRow("MiniMax 官方", miniMaxSummary(state), onClick = { state.navigate(SettingsPage.MINIMAX) })
        SettingsEntryRow("阿里百炼", bailianSummary(state), onClick = { state.navigate(SettingsPage.BAILIAN) })
        SettingsEntryRow("Agent 路由", agentRouteSummary(state.agentRoutes), onClick = { state.navigate(SettingsPage.AGENT_ROUTES) })

        SettingsSectionTitle("外部数据")
        SettingsEntryRow(
            "和风天气",
            state.qWeatherFingerprint?.let { "已保存 Key（$it）" } ?: "未配置，天气与预警不可用",
            onClick = { state.navigate(SettingsPage.QWEATHER) },
        )

        SettingsSectionTitle("体验")
        SettingsCard {
            SettingsSwitchRow(
                title = stringResource(R.string.floating_window_settings_title),
                summary = stringResource(R.string.floating_window_settings_note),
                checked = state.floatingEnabled,
                onCheckedChange = state::setFloatingWindow,
            )
        }

        SettingsSectionTitle("数据与诊断")
        SettingsEntryRow("配置导入与导出", "导出文件包含 API Key 明文，请妥善保管", onClick = { state.navigate(SettingsPage.TRANSFER) })
        SettingsEntryRow("诊断日志导出", "脱敏聊天、AI 与后台任务日志后分享", onClick = { state.navigate(SettingsPage.DIAGNOSTICS) })
        Text(stringResource(R.string.byok_risk_note), style = MaterialTheme.typography.bodySmall)
    }
}

private fun miniMaxSummary(state: SettingsScreenState): String {
    val key = state.miniMaxFingerprint ?: return "未配置 Key，需填写后才能使用"
    return "Key $key · " + if (state.officialModelsValidated) "M2 + M2-her 已验证" else "能力待验证"
}

private fun bailianSummary(state: SettingsScreenState): String {
    val credential = state.bailianFingerprint?.let { "共享 Key $it" } ?: "未配置 Key"
    val chat = if (state.bailianModelsValidated) "生成模型已验证" else "生成模型未验证"
    val search = if (state.webSearchEnabled) "联网搜索已启用" else "联网搜索已关闭"
    val embedding = if (state.embeddingEnabled) "Embedding 已启用" else "Embedding 已关闭"
    return listOf(credential, chat, search, embedding).joinToString(" · ")
}

internal fun agentRouteSummary(routes: Map<TaskKind, AgentModelRoute>): String {
    if (routes.isEmpty()) return "使用默认路由"
    val grouped = routes.entries.groupBy({ it.value.provider }, { slotLabel(it.key) })
    return grouped.entries.joinToString("，") { (provider, tasks) -> "${tasks.joinToString("/")} → ${providerLabel(provider)}" }
}

private fun providerLabel(provider: ModelProvider): String = when (provider) {
    ModelProvider.MINIMAX_OFFICIAL -> "MiniMax 官方"
    ModelProvider.BAILIAN -> "阿里百炼"
}

internal fun taskLabel(task: TaskKind): String = when (task) {
    TaskKind.A_INTAKE -> "A · 输入理解"
    TaskKind.B1_DECISION -> "B1 · 决策"
    TaskKind.B2_EXPRESSION -> "B2 · 表达"
    TaskKind.C_REVIEW -> "C · 审查"
    TaskKind.D_MEMORY -> "D · 记忆"
}

private fun slotLabel(task: TaskKind): String = when (task) {
    TaskKind.A_INTAKE -> "A"
    TaskKind.B1_DECISION -> "B1"
    TaskKind.B2_EXPRESSION -> "B2"
    TaskKind.C_REVIEW -> "C"
    TaskKind.D_MEMORY -> "D"
}
