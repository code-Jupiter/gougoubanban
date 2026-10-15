package com.littlekitty.xiaokele.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.littlekitty.xiaokele.diagnostics.DiagnosticsExportService
import com.littlekitty.xiaokele.floating.FloatingPetService
import com.littlekitty.xiaokele.floating.FloatingWindowSettingsStore
import com.littlekitty.xiaokele.provider.AgentModelRoute
import com.littlekitty.xiaokele.provider.BailianConnectionTester
import com.littlekitty.xiaokele.provider.BailianModelCatalogResult
import com.littlekitty.xiaokele.provider.BailianModelTestResult
import com.littlekitty.xiaokele.provider.MiniMaxConnectionTester
import com.littlekitty.xiaokele.provider.MiniMaxDirectConfig
import com.littlekitty.xiaokele.provider.ModelProvider
import com.littlekitty.xiaokele.provider.OfficialModelTestResult
import com.littlekitty.xiaokele.provider.RouteComplexity
import com.littlekitty.xiaokele.provider.TaskKind
import com.littlekitty.xiaokele.provider.CompanionFallbackReplyWarmup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class SettingsPage(val title: String) {
    HOME("AI 连接设置"),
    MINIMAX("MiniMax 官方"),
    BAILIAN("阿里百炼"),
    BAILIAN_MODELS("百炼 · 生成模型"),
    WEB_SEARCH("联网搜索"),
    EMBEDDING("记忆 Embedding"),
    AGENT_ROUTES("Agent 路由"),
    QWEATHER("和风天气"),
    TRANSFER("配置导入与导出"),
    DIAGNOSTICS("诊断日志"),
    ;

    val parent: SettingsPage
        get() = when (this) {
            HOME -> HOME
            BAILIAN_MODELS, WEB_SEARCH, EMBEDDING -> BAILIAN
            else -> HOME
        }
}

/** Owns the settings drill-down stack and editable fields outside Compose UI. */
@Stable
internal class SettingsScreenState(
    private val context: Context,
    private val scope: CoroutineScope,
    val store: ByokSettingsStore,
    private val miniMaxTester: MiniMaxConnectionTester,
    private val bailianTester: BailianConnectionTester,
    val embeddingStore: EmbeddingSettingsStore,
    private val embeddingTester: EmbeddingConnectionTester,
    val qWeatherStore: QWeatherSettingsStore,
    private val qWeatherTester: QWeatherConnectionTester,
    val floatingWindowSettingsStore: FloatingWindowSettingsStore,
    val diagnosticsExportService: DiagnosticsExportService,
    private val fallbackReplyWarmup: CompanionFallbackReplyWarmup? = null,
) {
    private val locationRadiusSettingsStore = LocationRadiusSettingsStore(context)

    var page by mutableStateOf(SettingsPage.HOME)
        private set
    var testOutcome by mutableStateOf<SettingsTestOutcome?>(null)
    val snackbarHostState = SnackbarHostState()

    var region by mutableStateOf(store.region())
    var endpoint by mutableStateOf(store.endpoint())
    var miniMaxKeyInput by mutableStateOf("")
    var miniMaxKeyVisible by mutableStateOf(false)
    var miniMaxFingerprint by mutableStateOf(store.fingerprint())
    var miniMaxTesting by mutableStateOf(false)
    var officialModelsValidated by mutableStateOf(store.officialModelsValidated())

    var bailianHost by mutableStateOf(embeddingStore.apiHost())
    var bailianKeyInput by mutableStateOf("")
    var bailianKeyVisible by mutableStateOf(false)
    var bailianFingerprint by mutableStateOf(embeddingStore.fingerprint())
    var bailianTesting by mutableStateOf(false)
    var bailianModelsValidated by mutableStateOf(store.bailianModelsValidated())
    var bailianAvailableModels by mutableStateOf(store.bailianAvailableModels().toList())

    var embeddingModel by mutableStateOf(embeddingStore.model())
    var embeddingEnabled by mutableStateOf(embeddingStore.isEnabled())
    var embeddingTesting by mutableStateOf(false)
    var webSearchEnabled by mutableStateOf(store.webSearchEnabled())
    var agentRoutes by mutableStateOf(store.agentModelRoutes())

    var qWeatherHost by mutableStateOf(qWeatherStore.apiHost())
    var qWeatherKeyInput by mutableStateOf("")
    var qWeatherKeyVisible by mutableStateOf(false)
    var qWeatherFingerprint by mutableStateOf(qWeatherStore.fingerprint())
    var qWeatherTesting by mutableStateOf(false)

    var floatingEnabled by mutableStateOf(
        floatingWindowSettingsStore.isEnabled() && Settings.canDrawOverlays(context),
    )
    var waitingForOverlayPermission by mutableStateOf(false)
    var configTransferBusy by mutableStateOf(false)
    var showConfigExportWarning by mutableStateOf(false)
    var diagnosticsExporting by mutableStateOf(false)
    var showDiagnosticsPrivacyDialog by mutableStateOf(false)
    var sensitiveWordInput by mutableStateOf("")

    val settingsTransferService = AppSettingsTransferService(
        byokStore = store,
        qWeatherStore = qWeatherStore,
        embeddingStore = embeddingStore,
        locationSettingsStore = locationRadiusSettingsStore,
        floatingWindowSettingsStore = floatingWindowSettingsStore,
    )

    fun navigate(target: SettingsPage) {
        page = target
    }

    fun navigateUp() {
        page = page.parent
    }

    fun notify(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    private fun showTestOutcome(title: String, message: String) {
        testOutcome = SettingsTestOutcome(title = title, message = message)
    }

    /** Refreshes durable status without discarding text currently being edited. */
    fun refresh() {
        agentRoutes = store.agentModelRoutes()
        officialModelsValidated = store.officialModelsValidated()
        bailianModelsValidated = store.bailianModelsValidated()
        bailianAvailableModels = store.bailianAvailableModels().toList()
        webSearchEnabled = store.webSearchEnabled()
        miniMaxFingerprint = store.fingerprint()
        bailianFingerprint = embeddingStore.fingerprint()
        embeddingEnabled = embeddingStore.isEnabled()
        qWeatherFingerprint = qWeatherStore.fingerprint()
        floatingEnabled = floatingWindowSettingsStore.isEnabled() && Settings.canDrawOverlays(context)
    }

    fun currentSettingsTransferInput(): SettingsTransferInput = SettingsTransferInput(
        miniMaxApiKey = miniMaxKeyInput.ifBlank { store.loadKeyOrNull().orEmpty() },
        miniMaxRegion = region,
        miniMaxEndpoint = endpoint,
        miniMaxModel = MiniMaxDirectConfig.DEFAULT_OFFICIAL_MODEL,
        qWeatherApiHost = qWeatherHost,
        qWeatherApiKey = qWeatherKeyInput.ifBlank { qWeatherStore.loadApiKeyOrNull().orEmpty() },
        embeddingApiHost = bailianHost,
        embeddingApiKey = bailianKeyInput.ifBlank { embeddingStore.loadApiKeyOrNull().orEmpty() },
        embeddingModel = embeddingModel,
        embeddingEnabled = embeddingEnabled,
        webSearchEnabled = webSearchEnabled,
        locationRadiusMeters = locationRadiusSettingsStore.radiusMeters(),
        floatingWindowEnabled = floatingEnabled,
        floatingWindowXOffset = floatingWindowSettingsStore.xOffsetOrNull(),
        floatingWindowYOffset = floatingWindowSettingsStore.yOffsetOrNull(),
        discoveryNotificationsEnabled = store.notificationsEnabled(),
        discoveryAreas = store.selectedAreas(),
        discoveryCategories = store.selectedCategories(),
        agentRoutes = agentRoutes,
    )

    fun applyImportedDocument(document: AppSettingsTransferDocument) {
        region = document.minimax.region
        endpoint = document.minimax.endpoint
        miniMaxKeyInput = ""
        miniMaxKeyVisible = false
        qWeatherHost = document.qweather.apiHost
        qWeatherKeyInput = ""
        qWeatherKeyVisible = false
        bailianHost = document.embedding.apiHost
        bailianKeyInput = ""
        bailianKeyVisible = false
        embeddingModel = document.embedding.model
        embeddingEnabled = document.embedding.enabled
        webSearchEnabled = document.discovery.webSearchEnabled
        // Imported credentials can be the first usable configuration on an
        // existing installation. Warmup is idempotent and only calls a model
        // when the confirmed persona has no cached fixed replies.
        fallbackReplyWarmup?.schedule()
        if (!document.floatingWindow.enabled) {
            FloatingPetService.stop(context)
        } else if (Settings.canDrawOverlays(context)) {
            FloatingPetService.startHidden(context)
        }
        refresh()
    }

    fun saveMiniMaxSettings() {
        if (!store.setEndpoint(endpoint)) {
            notify("MiniMax 地址必须是 HTTPS URL")
            return
        }
        store.setRegion(region)
        store.setModel(MiniMaxDirectConfig.DEFAULT_OFFICIAL_MODEL)
        refresh()
        notify("MiniMax 区域与地址已保存")
    }

    fun saveMiniMaxKey() {
        if (miniMaxKeyInput.isBlank()) {
            notify("Key 不能为空")
            return
        }
        scope.launch {
            if (store.saveKey(miniMaxKeyInput)) {
                miniMaxKeyInput = ""
                miniMaxKeyVisible = false
                refresh()
                notify("MiniMax Key 已保存，仅显示末 4 位")
            } else {
                notify("Key 保存失败")
            }
        }
    }

    fun deleteMiniMaxKey() {
        store.deleteKey()
        miniMaxKeyInput = ""
        miniMaxKeyVisible = false
        refresh()
        notify("MiniMax Key 已删除")
    }

    fun runMiniMaxTest() {
        scope.launch {
            miniMaxTesting = true
            val candidateKey = miniMaxKeyInput.ifBlank { store.loadKeyOrNull().orEmpty() }
            when (val result = miniMaxTester.testOfficialProfile(candidateKey, region, endpoint)) {
                is OfficialModelTestResult.Success -> {
                    val saved = store.setEndpoint(endpoint) &&
                        store.setRegion(region) &&
                        store.setModel(MiniMaxDirectConfig.DEFAULT_OFFICIAL_MODEL) &&
                        store.saveKey(candidateKey) &&
                        store.markOfficialValidation(
                            keyFingerprint = result.keyFingerprint,
                            region = region,
                            endpoint = endpoint,
                            m2Model = result.m2Model,
                            herModel = result.herModel,
                        )
                    miniMaxTesting = false
                    if (saved) {
                        miniMaxKeyInput = ""
                        miniMaxKeyVisible = false
                        refresh()
                        fallbackReplyWarmup?.schedule()
                        showTestOutcome(
                            "MiniMax 连接成功",
                            "MiniMax M2 与 M2-her 双模型能力均已验证；当前 Key、区域和地址已绑定。耗时 ${result.latencyMillis} ms。",
                        )
                    } else {
                        showTestOutcome("测试成功但绑定失败", "双模型测试成功，但配置绑定失败；请重新测试。")
                    }
                }
                is OfficialModelTestResult.Failure -> {
                    miniMaxTesting = false
                    showTestOutcome("MiniMax 测试失败", result.userMessage)
                }
            }
        }
    }

    fun saveBailianCredentials() {
        if (!embeddingStore.setApiHost(bailianHost)) {
            notify("百炼 Host 必须是 HTTPS 域名")
            return
        }
        if (bailianKeyInput.isNotBlank() && !embeddingStore.saveApiKey(bailianKeyInput)) {
            notify("百炼 API Key 保存失败")
            return
        }
        if (bailianKeyInput.isNotBlank()) {
            bailianKeyInput = ""
            bailianKeyVisible = false
        }
        refresh()
        notify("百炼凭据已保存，Chat 与 Embedding 共用")
    }

    fun deleteBailianKey() {
        embeddingStore.deleteApiKey()
        store.setBailianModelsValidated(false)
        bailianKeyInput = ""
        bailianKeyVisible = false
        refresh()
        notify("百炼 Key 已删除；Chat 与 Embedding 同时失效")
    }

    fun fetchBailianDeepSeekModels() {
        scope.launch {
            bailianTesting = true
            val candidateKey = bailianKeyInput.ifBlank { embeddingStore.loadApiKeyOrNull().orEmpty() }
            when (val result = bailianTester.listDeepSeekModels(
                rawKey = candidateKey,
                endpoint = dashScopeChatCompletionEndpoint(bailianHost),
            )) {
                is BailianModelCatalogResult.Success -> {
                    val saved = embeddingStore.setApiHost(bailianHost) &&
                        embeddingStore.saveApiKey(candidateKey) &&
                        store.saveBailianModelCatalog(result.modelIds)
                    bailianTesting = false
                    if (saved) {
                        bailianKeyInput = ""
                        bailianKeyVisible = false
                        refresh()
                        showTestOutcome("百炼连接成功", "已获取 ${result.modelIds.size} 个可用 DeepSeek 模型；请为各 Agent 选择模型后再测试。")
                    } else {
                        showTestOutcome("连接成功但保存失败", "模型目录已读取，但 Host、Key 或模型目录未能保存。")
                    }
                }
                is BailianModelCatalogResult.Failure -> {
                    bailianTesting = false
                    showTestOutcome("百炼连接失败", result.userMessage)
                }
            }
        }
    }

    fun runBailianChatTest() {
        scope.launch {
            bailianTesting = true
            val candidateKey = bailianKeyInput.ifBlank { embeddingStore.loadApiKeyOrNull().orEmpty() }
            val selectedModels = agentRoutes.values
                .filter { it.provider == ModelProvider.BAILIAN }
                .flatMap { listOf(it.defaultModelId, it.complexModelId) }
                .filter { it in bailianAvailableModels }
                .toSet()
            when (val result = bailianTester.test(
                rawKey = candidateKey,
                endpoint = dashScopeChatCompletionEndpoint(bailianHost),
                modelIds = selectedModels,
            )) {
                is BailianModelTestResult.Success -> {
                    bailianTesting = false
                    val saved = embeddingStore.setApiHost(bailianHost) &&
                        embeddingStore.saveApiKey(candidateKey) &&
                        store.markBailianValidation(
                            keyFingerprint = result.keyFingerprint,
                            modelIds = selectedModels,
                        )
                    if (saved) {
                        bailianKeyInput = ""
                        bailianKeyVisible = false
                        refresh()
                        fallbackReplyWarmup?.schedule()
                        showTestOutcome(
                            "百炼生成模型可用",
                            "已选 DeepSeek 模型能力已验证，共享 Key 已绑定。耗时 ${result.latencyMillis} ms。",
                        )
                    } else {
                        showTestOutcome("测试通过，但凭据未绑定", "模型测试通过，但 Host、Key 或验证状态保存失败；请检查后重试。")
                    }
                }
                is BailianModelTestResult.Failure -> {
                    bailianTesting = false
                    showTestOutcome("百炼 Chat 测试失败", result.userMessage)
                }
            }
        }
    }

    fun changeEmbeddingEnabled(value: Boolean) {
        if (!embeddingStore.setEnabled(value)) {
            notify("Embedding 开关保存失败")
            return
        }
        embeddingEnabled = value
        notify(if (value) "记忆向量检索已启用" else "记忆向量检索已关闭，已退回词法匹配")
    }

    fun changeWebSearchEnabled(value: Boolean) {
        if (!store.setWebSearchEnabled(value)) {
            notify("联网搜索开关保存失败")
            return
        }
        webSearchEnabled = value
        notify(if (value) "联网搜索已启用" else "联网搜索已关闭，不再发起搜索请求")
    }

    fun saveEmbeddingSettings() {
        if (!embeddingStore.setModel(embeddingModel)) {
            notify("Embedding 模型保存失败")
            return
        }
        notify("Embedding 模型已保存")
    }

    fun runEmbeddingTest() {
        scope.launch {
            embeddingTesting = true
            val result = embeddingTester.test(
                apiHost = bailianHost,
                apiKey = bailianKeyInput.ifBlank { embeddingStore.loadApiKeyOrNull().orEmpty() },
                model = embeddingModel,
            )
            embeddingTesting = false
            when (result) {
                is EmbeddingTestResult.Success -> showTestOutcome(
                    "Embedding 连接成功",
                    "模型 ${result.model}，维度 ${result.dimension}，延迟 ${result.latencyMillis} ms。",
                )
                is EmbeddingTestResult.Failure -> showTestOutcome("Embedding 连接失败", result.message)
            }
        }
    }

    fun saveQWeather() {
        if (!qWeatherStore.setApiHost(qWeatherHost)) {
            notify("天气 Host 必须是 HTTPS URL")
            return
        }
        if (qWeatherKeyInput.isNotBlank() && !qWeatherStore.saveApiKey(qWeatherKeyInput)) {
            notify("天气 API Key 保存失败")
            return
        }
        if (qWeatherKeyInput.isNotBlank()) {
            qWeatherKeyInput = ""
            qWeatherKeyVisible = false
        }
        refresh()
        notify("和风天气配置已保存")
    }

    fun deleteQWeatherKey() {
        qWeatherStore.deleteApiKey()
        qWeatherKeyInput = ""
        qWeatherKeyVisible = false
        refresh()
        notify("和风天气 Key 已删除")
    }

    fun runQWeatherTest() {
        scope.launch {
            qWeatherTesting = true
            val result = qWeatherTester.test(
                apiHost = qWeatherHost,
                apiKey = qWeatherKeyInput.ifBlank { qWeatherStore.loadApiKeyOrNull().orEmpty() },
            )
            qWeatherTesting = false
            when (result) {
                is QWeatherTestResult.Success -> showTestOutcome(
                    "天气连接成功",
                    "${result.summary}，延迟 ${result.latencyMillis} ms。",
                )
                is QWeatherTestResult.Failure -> showTestOutcome("天气连接失败", result.message)
            }
        }
    }

    fun setFloatingWindow(requested: Boolean) {
        if (!requested) {
            floatingWindowSettingsStore.setEnabled(false)
            FloatingPetService.stop(context)
            floatingEnabled = false
            waitingForOverlayPermission = false
            notify("悬浮窗已关闭")
        } else if (Settings.canDrawOverlays(context)) {
            floatingWindowSettingsStore.setEnabled(true)
            FloatingPetService.startHidden(context)
            floatingEnabled = true
            notify("悬浮窗已开启，退出应用后显示")
        } else {
            waitingForOverlayPermission = true
            notify("请在系统页面允许“显示在其他应用上层”，返回后自动开启")
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        }
    }

    fun runDiagnosticsExport(sensitiveWords: String) {
        scope.launch {
            diagnosticsExporting = true
            runCatching {
                val uri = diagnosticsExportService.export(context, sensitiveWords)
                context.startActivity(Intent.createChooser(diagnosticsExportService.shareIntent(context, uri), "发送诊断日志"))
            }.fold(
                onSuccess = { notify("诊断日志已脱敏，请选择发送方式") },
                onFailure = { error -> notify("诊断日志导出失败：${error.message ?: "未知错误"}") },
            )
            diagnosticsExporting = false
        }
    }

    /** Only refresh state that can change while the settings UI is backgrounded. */
    fun onResumed() {
        agentRoutes = store.agentModelRoutes()
        val permissionGranted = Settings.canDrawOverlays(context)
        if (waitingForOverlayPermission && permissionGranted) {
            floatingWindowSettingsStore.setEnabled(true)
            FloatingPetService.startHidden(context)
            floatingEnabled = true
            waitingForOverlayPermission = false
            notify("悬浮窗已开启，退出应用后显示")
        } else if (!permissionGranted && floatingEnabled) {
            floatingWindowSettingsStore.setEnabled(false)
            FloatingPetService.stop(context)
            floatingEnabled = false
        } else {
            floatingEnabled = floatingWindowSettingsStore.isEnabled() && permissionGranted
        }
    }

    fun updateAgentRoute(task: TaskKind, provider: ModelProvider, modelId: String, complexity: RouteComplexity) {
        val current = agentRoutes.getValue(task)
        val updated = if (complexity == RouteComplexity.DEFAULT) {
            current.copy(provider = provider, defaultModelId = modelId)
        } else {
            current.copy(provider = provider, complexModelId = modelId)
        }
        if (store.setAgentModelRoute(task, complexity, AgentModelRoute(provider, modelId, modelId))) {
            agentRoutes = agentRoutes + (task to updated)
            notify("${taskLabel(task)} 路由已保存；请重新测试对应供应商能力")
        } else {
            notify("${taskLabel(task)} 路由保存失败")
        }
    }
}
