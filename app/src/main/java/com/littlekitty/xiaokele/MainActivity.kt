package com.littlekitty.xiaokele

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.littlekitty.xiaokele.chat.ChatController
import com.littlekitty.xiaokele.chat.FrontChatPipeline
import com.littlekitty.xiaokele.core.database.state.MessageStatuses
import com.littlekitty.xiaokele.automation.AutomationRuleImportResult
import com.littlekitty.xiaokele.automation.AutomationRuleStore
import com.littlekitty.xiaokele.automation.AutomationRulesScreen
import com.littlekitty.xiaokele.automation.AutomationTimeRuleScheduler
import com.littlekitty.xiaokele.floating.FloatingPetService
import com.littlekitty.xiaokele.floating.AppVisibilityState
import com.littlekitty.xiaokele.floating.FloatingWindowSettingsStore
import com.littlekitty.xiaokele.handoff.InternalHandoffRequest
import com.littlekitty.xiaokele.handoff.InternalIntentContract
import com.littlekitty.xiaokele.external.WeatherRefreshWorker
import com.littlekitty.xiaokele.external.UnlockSceneWorker
import com.littlekitty.xiaokele.scene.SceneCheckWorker
import com.littlekitty.xiaokele.creative.CreativeCatchUpWorker
import com.littlekitty.xiaokele.creative.CreativeLibraryAccess
import com.littlekitty.xiaokele.handoff.IntentHandoffManager
import com.littlekitty.xiaokele.discovery.DiscoveryFeedScreen
import com.littlekitty.xiaokele.diagnostics.DiagnosticsExportService
import com.littlekitty.xiaokele.firstcreate.ConnectionReadinessReader
import com.littlekitty.xiaokele.firstcreate.FirstCreateGate
import com.littlekitty.xiaokele.firstcreate.FirstCreateScreen
import com.littlekitty.xiaokele.firstcreate.FirstCreateServiceValidator
import com.littlekitty.xiaokele.firstcreate.FirstCreateStore
import com.littlekitty.xiaokele.firstcreate.PersonaGenerator
import com.littlekitty.xiaokele.discovery.DiscoveryFeedService
import com.littlekitty.xiaokele.discovery.DiscoveryLocationResolver
import com.littlekitty.xiaokele.permission.Phase9PermissionPolicy
import com.littlekitty.xiaokele.permission.PermissionOnboardingScreen
import com.littlekitty.xiaokele.permission.PermissionOnboardingStore
import com.littlekitty.xiaokele.provider.MiniMaxConnectionTester
import com.littlekitty.xiaokele.provider.BailianConnectionTester
import com.littlekitty.xiaokele.provider.SourceGroundedSummarizer
import com.littlekitty.xiaokele.provider.CompanionFallbackReplyWarmup
import com.littlekitty.xiaokele.settings.AiSettingsScreen
import com.littlekitty.xiaokele.settings.ByokSettingsStore
import com.littlekitty.xiaokele.settings.EmbeddingConnectionTester
import com.littlekitty.xiaokele.settings.EmbeddingSettingsStore
import com.littlekitty.xiaokele.settings.QWeatherConnectionTester
import com.littlekitty.xiaokele.settings.QWeatherSettingsStore
import com.littlekitty.xiaokele.notification.NotificationPermission
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.info.XiaoKeLeInfoScreen
import com.littlekitty.xiaokele.ui.chat.SingleChatScreen
import com.littlekitty.xiaokele.ui.creative.CreativeLibraryScreen
import com.littlekitty.xiaokele.ui.theme.XiaoKeLeTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val chatController: ChatController by inject()
    private val handoffManager: IntentHandoffManager by inject()
    private val database: AppDatabase by inject()
    private val creativeLibraryAccess: CreativeLibraryAccess by inject()
    private val discoveryFeedService: DiscoveryFeedService by inject()
    private val discoveryLocationResolver: DiscoveryLocationResolver by inject()
    private val byokSettingsStore: ByokSettingsStore by inject()
    private val embeddingSettingsStore: EmbeddingSettingsStore by inject()
    private val embeddingConnectionTester: EmbeddingConnectionTester by inject()
    private val qWeatherSettingsStore: QWeatherSettingsStore by inject()
    private val qWeatherConnectionTester: QWeatherConnectionTester by inject()
    private val connectionTester: MiniMaxConnectionTester by inject()
    private val bailianConnectionTester: BailianConnectionTester by inject()
    private val sourceGroundedSummarizer: SourceGroundedSummarizer by inject()
    private val automationRuleStore: AutomationRuleStore by inject()
    private val diagnosticsExportService: DiagnosticsExportService by inject()
    private val floatingWindowSettingsStore by lazy { FloatingWindowSettingsStore(applicationContext) }
    private val backgroundOverlayHandler = Handler(Looper.getMainLooper())
    private val showFloatingAfterBackground = Runnable {
        if (AppVisibilityState.isForeground.not() &&
            firstCreateCompleted &&
            floatingWindowSettingsStore.isEnabled() &&
            Settings.canDrawOverlays(this)
        ) {
            FloatingPetService.showPending(this)
        }
    }
    private val firstCreateGate: FirstCreateGate by inject()
    private val firstCreateStore: FirstCreateStore by inject()
    private val firstCreateValidator: FirstCreateServiceValidator by inject()
    private val connectionReadinessReader: ConnectionReadinessReader by inject()
    private val personaGenerator: PersonaGenerator by inject()
    private val fallbackReplyWarmup: CompanionFallbackReplyWarmup by inject()
    private lateinit var permissionStore: PermissionOnboardingStore
    private var showOnboarding by mutableStateOf(false)
    private var firstCreateCompleted by mutableStateOf(false)
    private var firstCreateResolved by mutableStateOf(false)
    private var currentScreen by mutableStateOf(MainScreen.CHAT)
    private var returnToNewsAfterCreativeLibrary by mutableStateOf(false)
    private var automationRuleStatus by mutableStateOf<String?>(null)
    private var openDiagnosticsOnEntry by mutableStateOf(false)

    private val onboardingLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        finishPermissionOnboarding()
    }

    private val automationRuleImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                automationRuleStatus = when (val result = automationRuleStore.importFromUri(contentResolver, uri)) {
                    is AutomationRuleImportResult.Success -> {
                        AutomationTimeRuleScheduler.register(this@MainActivity)
                        SceneCheckWorker.enqueueForegroundCatchUp(this@MainActivity)
                        "已导入 ${result.document.rules.size} 条规则"
                    }
                    is AutomationRuleImportResult.Failure -> "导入失败：${result.message}"
                }
            }
        }
    }

    private val automationRuleExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                automationRuleStatus = automationRuleStore.exportToUri(contentResolver, uri)
                    .fold(onSuccess = { "规则已导出" }, onFailure = { "导出失败：${it.message.orEmpty()}" })
            }
        }
    }

    private val onboardingNotificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        launchForegroundLocationPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionStore = PermissionOnboardingStore(applicationContext)
        showOnboarding = permissionStore.isCompleted().not()
        enableEdgeToEdge()
        lifecycleScope.launch {
            firstCreateCompleted = firstCreateGate.refresh()
            if (firstCreateCompleted) fallbackReplyWarmup.schedule()
            firstCreateResolved = true
        }
        setContent {
            XiaoKeLeTheme {
                BackHandler(
                    enabled = currentScreen != MainScreen.CHAT,
                ) {
                    when (currentScreen) {
                        // Rules are reached from the info page, so return there
                        // rather than leaving the app.
                        MainScreen.AUTOMATION_RULES -> currentScreen = MainScreen.NEWS
                        MainScreen.CREATIVE_LIBRARY -> closeCreativeLibrary()
                        else -> currentScreen = MainScreen.CHAT
                    }
                }
                when {
                    // The companion must exist before anything else: no chat,
                    // no memory, no permission prompts for a half-created dog.
                    firstCreateResolved.not() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    // Settings must remain available from the initial-create
                    // gate so a user can configure a provider before creating
                    // the companion.
                    currentScreen == MainScreen.SETTINGS -> AiSettingsScreen(
                        store = byokSettingsStore,
                        tester = connectionTester,
                        bailianTester = bailianConnectionTester,
                        embeddingStore = embeddingSettingsStore,
                        embeddingTester = embeddingConnectionTester,
                        qWeatherStore = qWeatherSettingsStore,
                        qWeatherTester = qWeatherConnectionTester,
                        onBack = {
                            openDiagnosticsOnEntry = false
                            currentScreen = MainScreen.CHAT
                        },
                        floatingWindowSettingsStore = floatingWindowSettingsStore,
                        diagnosticsExportService = diagnosticsExportService,
                        fallbackReplyWarmup = fallbackReplyWarmup,
                        openDiagnosticsOnEntry = openDiagnosticsOnEntry,
                    )

                    firstCreateCompleted.not() -> FirstCreateScreen(
                        store = firstCreateStore,
                        validator = firstCreateValidator,
                        readinessReader = connectionReadinessReader,
                        personaGenerator = personaGenerator,
                        onOpenSettings = { currentScreen = MainScreen.SETTINGS },
                        onCompleted = {
                            firstCreateCompleted = true
                            lifecycleScope.launch {
                                firstCreateGate.markCompleted()
                                fallbackReplyWarmup.schedule()
                            }
                        },
                    )

                    // Permission onboarding belongs at product entry, not in
                    // a persistent chat-toolbar action. The user can decline
                    // here and still use the chat; optional local features
                    // will then explain when location is unavailable.
                    showOnboarding -> PermissionOnboardingScreen(
                        onContinue = ::continueOnboarding,
                        onLater = ::finishPermissionOnboarding,
                    )

                    // All user-facing destinations must be rendered here.
                    // Previously only settings had a branch, so changing
                    // currentScreen for local life or companion information
                    // recomposed the same chat page and looked like a dead tap.
                    currentScreen == MainScreen.DISCOVERY -> DiscoveryFeedScreen(
                        service = discoveryFeedService,
                        store = byokSettingsStore,
                        locationResolver = discoveryLocationResolver,
                        onBack = { currentScreen = MainScreen.CHAT },
                        summarizer = sourceGroundedSummarizer,
                    )

                    currentScreen == MainScreen.NEWS -> XiaoKeLeInfoScreen(
                        database = database,
                        onBack = { currentScreen = MainScreen.CHAT },
                        onOpenDiary = {
                            returnToNewsAfterCreativeLibrary = true
                            currentScreen = MainScreen.CREATIVE_LIBRARY
                        },
                        onImportRules = {
                            automationRuleImportLauncher.launch(
                                arrayOf("application/json", "text/json", "text/plain"),
                            )
                        },
                        onExportRules = {
                            automationRuleExportLauncher.launch("dog-companion-rules.json")
                        },
                        onRulesPeek = { currentScreen = MainScreen.AUTOMATION_RULES },
                        rulesStatus = automationRuleStatus,
                    )

                    currentScreen == MainScreen.CREATIVE_LIBRARY -> CreativeLibraryScreen(
                        access = creativeLibraryAccess,
                        onBack = ::closeCreativeLibrary,
                    )

                    currentScreen == MainScreen.AUTOMATION_RULES -> AutomationRulesScreen(
                        store = automationRuleStore,
                        onBack = { currentScreen = MainScreen.NEWS },
                    )

                    else -> SingleChatScreen(
                        chatController = chatController,
                        onDebugRequested = if (BuildConfig.DEBUG) {
                            { openInternalDebugScreen() }
                        } else {
                            null
                        },
                        // Keep developer-only tools out of release APKs. A
                        // release build's hidden gesture instead reaches the
                        // production diagnostics exporter, which is safe to
                        // use with real data and supports redaction.
                        onDiagnosticsRequested = if (BuildConfig.DEBUG) {
                            null
                        } else {
                            {
                                openDiagnosticsOnEntry = true
                                currentScreen = MainScreen.SETTINGS
                            }
                        },
                        newsAction = { NewsEntryButton { currentScreen = MainScreen.NEWS } },
                        discoveryAction = { DiscoveryEntryButton { currentScreen = MainScreen.DISCOVERY } },
                        settingsAction = {
                            SettingsEntryButton {
                                openDiagnosticsOnEntry = false
                                currentScreen = MainScreen.SETTINGS
                            }
                        },
                    )
                }
            }
        }
        handleInternalIntent(intent)
        observeAssistantRepliesForFloatingWindow()
        observeMarkProactiveRead()
    }

    /**
     * Opening the app exposes the chat history, so background proactive
     * messages count as seen from here on; the launcher badge and the widget
     * unread counter are refreshed with the same pass.
     */
    private fun observeMarkProactiveRead() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val marked = database.messageDao().markProactiveRead(System.currentTimeMillis())
                if (marked > 0) {
                    com.littlekitty.xiaokele.widget.WidgetUnreadBadge.refresh(this@MainActivity, database)
                }
                com.littlekitty.xiaokele.notification.UnreadBadgeNotifier.refresh(
                    this@MainActivity,
                    database.messageDao().countUnreadProactive(),
                )
            }
        }
    }

    override fun onStart() {
        backgroundOverlayHandler.removeCallbacks(showFloatingAfterBackground)
        AppVisibilityState.onActivityStart()
        super.onStart()
        if (floatingWindowSettingsStore.isEnabled()) {
            if (Settings.canDrawOverlays(this)) {
                FloatingPetService.startHidden(this)
            } else {
                floatingWindowSettingsStore.setEnabled(false)
            }
        }
        // Foreground catch-up work is also part of the product runtime, so it
        // must not be armed before the user has confirmed their companion.
        // Without this check, a first-run launch would drive the background
        // pipeline with no locked persona.
        if (firstCreateCompleted) {
            WeatherRefreshWorker.enqueueForegroundCatchUp(this)
            SceneCheckWorker.enqueueForegroundCatchUp(this)
            CreativeCatchUpWorker.enqueueForegroundCatchUp(this)
            UnlockSceneWorker.enqueue(this)
        }
    }

    override fun onStop() {
        // Proactive bubbles are allowed only after the app has actually left
        // the foreground. This also lets a pending debug/test bubble appear
        // immediately when the user returns to the launcher.
        AppVisibilityState.onActivityStop()
        backgroundOverlayHandler.removeCallbacks(showFloatingAfterBackground)
        backgroundOverlayHandler.postDelayed(showFloatingAfterBackground, 250L)
        super.onStop()
    }

    override fun onDestroy() {
        backgroundOverlayHandler.removeCallbacks(showFloatingAfterBackground)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInternalIntent(intent)
    }

    /**
     * Internal explicit-extras handoff only. There is intentionally no public
     * URI scheme and no exported deep-link intent-filter in this phase. Extras
     * are treated as untrusted data even though the sender is internal.
     */
    private fun handleInternalIntent(intent: Intent) {
        if (intent.data != null) return
        val request = parseInternalRequest(intent) ?: return
        lifecycleScope.launch {
            handoffManager.handle(request)
        }
    }

    private fun observeAssistantRepliesForFloatingWindow() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var baselineEstablished = false
                var lastAssistantId: String? = null
                chatController.state.collect { state ->
                    val latestAssistant = state.messages.asReversed().firstOrNull {
                        it.role == FrontChatPipeline.ROLE_ASSISTANT &&
                            it.status == MessageStatuses.COMPLETED
                    }
                    if (!baselineEstablished) {
                        baselineEstablished = true
                        lastAssistantId = latestAssistant?.id
                    } else if (latestAssistant != null && latestAssistant.id != lastAssistantId) {
                        lastAssistantId = latestAssistant.id
                        // A bubble may only surface once the companion has been
                        // confirmed; before that the chat page is not reachable.
                        if (firstCreateCompleted) {
                            FloatingPetService.showBubble(this@MainActivity, latestAssistant.content)
                        }
                    }
                }
            }
        }
    }

    private fun continueOnboarding() {
        val notificationNeeded = Phase9PermissionPolicy.shouldRequestNotification() &&
            NotificationPermission.hasRuntimePermission(this).not()
        if (notificationNeeded) {
            onboardingNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchForegroundLocationPermissions()
        }
    }

    private fun launchForegroundLocationPermissions() {
        if (Phase9PermissionPolicy.shouldRequestForegroundLocation().not()) {
            finishPermissionOnboarding()
            return
        }
        onboardingLocationLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
        )
    }

    private fun finishPermissionOnboarding() {
        permissionStore.markCompleted()
        showOnboarding = false
    }

    private fun parseInternalRequest(intent: Intent): InternalHandoffRequest? {
        val eventId = intent.getStringExtra(InternalIntentContract.EXTRA_EVENT_ID)
        val todoId = intent.getStringExtra(InternalIntentContract.EXTRA_TODO_ID)
        val executionId = intent.getStringExtra(InternalIntentContract.EXTRA_EXECUTION_ID)
        val deliveryId = intent.getStringExtra(InternalIntentContract.EXTRA_DELIVERY_ID)
        val source = intent.getStringExtra(InternalIntentContract.EXTRA_SOURCE)
        if (eventId == null && todoId == null) return null
        return InternalHandoffRequest(
            eventId = eventId,
            todoId = todoId,
            executionId = executionId,
            deliveryId = deliveryId,
            source = source,
        )
    }

    private fun openInternalDebugScreen() {
        val debugActivityName = getString(R.string.internal_debug_activity_name)
        if (debugActivityName.isBlank()) return
        startActivity(Intent().setClassName(this, debugActivityName))
    }

    private fun closeCreativeLibrary() {
        if (returnToNewsAfterCreativeLibrary) {
            returnToNewsAfterCreativeLibrary = false
            currentScreen = MainScreen.NEWS
        } else {
            currentScreen = MainScreen.CHAT
        }
    }
}

private enum class MainScreen {
    CHAT,
    NEWS,
    SETTINGS,
    DISCOVERY,
    CREATIVE_LIBRARY,
    AUTOMATION_RULES,
}

@Composable
private fun DiscoveryEntryButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(stringResource(R.string.top_action_discovery))
    }
}

@Composable
private fun SettingsEntryButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(stringResource(R.string.top_action_settings))
    }
}

@Composable
private fun NewsEntryButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(stringResource(R.string.news_title))
    }
}
