package com.littlekitty.xiaokele.di

import com.littlekitty.xiaokele.animation.AnimationAdapter
import com.littlekitty.xiaokele.animation.CatalogAnimationAdapter
import com.littlekitty.xiaokele.automation.AutomationRuleStore
import com.littlekitty.xiaokele.automation.AutomationTimeRuleScheduler
import com.littlekitty.xiaokele.channel.ChannelExecutionCoordinator
import com.littlekitty.xiaokele.channel.ChannelRetryScheduler
import com.littlekitty.xiaokele.channel.OutboxRetryScheduler
import com.littlekitty.xiaokele.chat.ChatContextBuilder
import com.littlekitty.xiaokele.chat.DefaultLocalChatToolExecutor
import com.littlekitty.xiaokele.chat.ConversationBudgetPolicy
import com.littlekitty.xiaokele.chat.ConversationConsolidator
import com.littlekitty.xiaokele.chat.ConversationContextManager
import com.littlekitty.xiaokele.chat.ConversationSessionCoordinator
import com.littlekitty.xiaokele.chat.MiniMaxConversationConsolidator
import com.littlekitty.xiaokele.chat.CoreProfileSynthesizer
import com.littlekitty.xiaokele.chat.MiniMaxCoreProfileSynthesizer
import com.littlekitty.xiaokele.chat.UserProfileContextManager
import com.littlekitty.xiaokele.discovery.DiscoveryFeedService
import com.littlekitty.xiaokele.discovery.DiscoveryKnowledgeProvider
import com.littlekitty.xiaokele.discovery.DiscoveryLocationResolver
import com.littlekitty.xiaokele.discovery.CurrentLocationResolver
import com.littlekitty.xiaokele.diagnostics.DiagnosticsExportService
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import com.littlekitty.xiaokele.firstcreate.FirstCreateGate
import com.littlekitty.xiaokele.firstcreate.ConnectionReadinessReader
import com.littlekitty.xiaokele.firstcreate.FirstCreateGatedMemoryAnalysisHook
import com.littlekitty.xiaokele.firstcreate.FirstCreateServiceValidator
import com.littlekitty.xiaokele.firstcreate.FirstCreateStore
import com.littlekitty.xiaokele.firstcreate.ModelPersonaGenerator
import com.littlekitty.xiaokele.firstcreate.PersonaGenerator
import com.littlekitty.xiaokele.chat.ChatController
import com.littlekitty.xiaokele.chat.FrontChatPipeline
import com.littlekitty.xiaokele.chat.FrontChatModelService
import com.littlekitty.xiaokele.chat.LocalChatToolExecutor
import com.littlekitty.xiaokele.creative.CreativeChatCoordinator
import com.littlekitty.xiaokele.creative.CreativeCatchUpCoordinator
import com.littlekitty.xiaokele.creative.CreativeContextProvider
import com.littlekitty.xiaokele.creative.CreativeEvaluationModelService
import com.littlekitty.xiaokele.creative.CreativeLibraryAccess
import com.littlekitty.xiaokele.creative.PublicSongLyricSearchService
import com.littlekitty.xiaokele.creative.SongLyricSearchService
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.delivery.DeliveryDedupGuard
import com.littlekitty.xiaokele.core.database.delivery.DeliveryOutbox
import com.littlekitty.xiaokele.core.database.memory.MemorySearch
import com.littlekitty.xiaokele.core.database.policy.PolicyResolver
import com.littlekitty.xiaokele.core.database.policy.ProductScenePolicyCatalog
import com.littlekitty.xiaokele.core.database.repository.ActionPlanRepository
import com.littlekitty.xiaokele.core.database.repository.BackgroundMutationExecutor
import com.littlekitty.xiaokele.core.database.repository.BehaviorTendencyRepository
import com.littlekitty.xiaokele.core.database.repository.BehaviorTrainer
import com.littlekitty.xiaokele.core.database.repository.EventRepository
import com.littlekitty.xiaokele.core.database.repository.MemoryRepository
import com.littlekitty.xiaokele.core.database.repository.ExternalCacheEmissionGuard
import com.littlekitty.xiaokele.core.database.repository.PetStateRepository
import com.littlekitty.xiaokele.core.database.repository.RecoveryManager
import com.littlekitty.xiaokele.core.database.repository.TodoClaimCoordinator
import com.littlekitty.xiaokele.core.database.repository.TodoManager
import com.littlekitty.xiaokele.core.database.repository.TodoRepository
import com.littlekitty.xiaokele.core.database.repository.TriggerManager
import com.littlekitty.xiaokele.core.database.repository.UserTraitRepository
import com.littlekitty.xiaokele.core.network.HttpTransport
import com.littlekitty.xiaokele.core.network.GovernedHttpTransport
import com.littlekitty.xiaokele.core.network.OkHttpHttpTransport
import com.littlekitty.xiaokele.core.network.embedding.DashScopeEmbeddingServiceAdapter
import com.littlekitty.xiaokele.core.network.embedding.EmbeddingService
import com.littlekitty.xiaokele.core.network.external.PendingWeatherService
import com.littlekitty.xiaokele.core.network.external.QWeatherServiceAdapter
import com.littlekitty.xiaokele.core.network.external.QWeatherServiceConfig
import com.littlekitty.xiaokele.core.network.external.WeatherService
import com.littlekitty.xiaokele.core.network.memory.MemoryEvaluator
import com.littlekitty.xiaokele.provider.AppModelRouter
import com.littlekitty.xiaokele.provider.MiniMaxConnectionTester
import com.littlekitty.xiaokele.provider.BailianConnectionTester
import com.littlekitty.xiaokele.provider.ProfiledTaskModelGateway
import com.littlekitty.xiaokele.provider.ReplyCoordinator
import com.littlekitty.xiaokele.provider.CompanionFallbackReplyCatalog
import com.littlekitty.xiaokele.provider.CompanionFallbackReplyWarmup
import com.littlekitty.xiaokele.provider.TaskModelGateway
import com.littlekitty.xiaokele.provider.SourceGroundedSummarizer
import com.littlekitty.xiaokele.core.network.model.ModelService
import com.littlekitty.xiaokele.event.BackgroundCompanionPipeline
import com.littlekitty.xiaokele.event.BackgroundContextBuilder
import com.littlekitty.xiaokele.event.BackgroundScheduler
import com.littlekitty.xiaokele.event.DueTriggerScheduler
import com.littlekitty.xiaokele.event.EventNormalizer
import com.littlekitty.xiaokele.event.LocalBackgroundDispatcher
import com.littlekitty.xiaokele.event.TriggerWakeScheduler
import com.littlekitty.xiaokele.scene.SceneCheckScheduler
import com.littlekitty.xiaokele.settings.ByokRuntimeConfig
import com.littlekitty.xiaokele.settings.ByokSettingsStore
import com.littlekitty.xiaokele.settings.EmbeddingConnectionTester
import com.littlekitty.xiaokele.settings.EmbeddingSettingsStore
import com.littlekitty.xiaokele.settings.QWeatherConnectionTester
import com.littlekitty.xiaokele.settings.QWeatherSettingsStore
import com.littlekitty.xiaokele.scene.ScenePreconditionGate
import com.littlekitty.xiaokele.core.database.repository.ExternalCacheRepository
import com.littlekitty.xiaokele.external.ExternalContextResolver
import com.littlekitty.xiaokele.external.ExternalDataCoordinator
import com.littlekitty.xiaokele.external.LocationFixProvider
import com.littlekitty.xiaokele.external.AndroidLocationFixProvider
import com.littlekitty.xiaokele.external.WeatherRefresh
import com.littlekitty.xiaokele.core.common.scene.FrozenSceneThresholdProvider
import com.littlekitty.xiaokele.core.common.scene.SceneThresholdProvider
import com.littlekitty.xiaokele.handoff.DeliveryEngagement
import com.littlekitty.xiaokele.handoff.DeliveryEngagementTracker
import com.littlekitty.xiaokele.handoff.IntentHandoffManager
import com.littlekitty.xiaokele.memory.AsyncMemoryAnalysisHook
import com.littlekitty.xiaokele.memory.LocalMemorySearch
import com.littlekitty.xiaokele.memory.MemoryAnalysisHook
import com.littlekitty.xiaokele.memory.MemoryAnalysisProcessor
import com.littlekitty.xiaokele.memory.MemoryReviewGateway
import com.littlekitty.xiaokele.memory.MiniMaxDirectMemoryEvaluator
import com.littlekitty.xiaokele.memory.ProfiledMemoryReviewGateway
import com.littlekitty.xiaokele.discovery.ProfiledDiscoveryFeedService
import com.littlekitty.xiaokele.notification.AndroidNotificationAdapter
import com.littlekitty.xiaokele.notification.NotificationAdapter
import com.littlekitty.xiaokele.widget.AndroidWidgetStateAdapter
import com.littlekitty.xiaokele.widget.WidgetStateAdapter
import kotlinx.serialization.json.Json
import org.koin.dsl.module

/**
 * Application wiring. Phase 3 adds the local memory/cultivation closed loop:
 * debug builds use deterministic fakes for model, memory evaluator and
 * embedding; release builds use runtime-configured external adapters and
 * fail-closed behavior when settings are missing. No debug control is
 * compiled into release.
 */
object AppModule {
    val module = module {
        single<Json> { JsonCodec.json }
        single<TimeProvider> { TimeProvider.systemUtc() }
        single<AppDatabase> { AppDatabase.build(get()) }
        single { get<AppDatabase>().metadataDao() }
        single { DiagnosticsLogger(get(), get()) }
        single { DiagnosticsExportService(get(), get(), get()) }
        single { CreativeChatCoordinator(get(), get()) }
        single<CreativeContextProvider> { get<CreativeChatCoordinator>() }
        single<CreativeLibraryAccess> { get<CreativeChatCoordinator>() }
        single { AutomationRuleStore(get()) }
        single { AutomationTimeRuleScheduler(get(), get()) }

        single { EventRepository(get(), get<AppDatabase>().eventDao()) }
        single { TodoRepository(get(), get<AppDatabase>().todoDao()) }
        single { TodoManager(get(), get<AppDatabase>().todoDao(), get<AppDatabase>().triggerDao()) }
        single { TriggerManager(get(), get<AppDatabase>().triggerDao()) }
        single { TodoClaimCoordinator(get()) }
        single { BackgroundMutationExecutor(get(), get<AppDatabase>().todoDao(), get<AppDatabase>().triggerDao()) }
        single { DeliveryDedupGuard(get<AppDatabase>().deliveryHistoryDao()) }
        single { DeliveryOutbox(get(), get<AppDatabase>().deliveryHistoryDao()) }
        single { PolicyResolver(get()) }
        single { MemoryRepository(get(), get<AppDatabase>().memoryDao()) }
        single { UserTraitRepository(get(), get<AppDatabase>().userTraitDao()) }
        single { BehaviorTendencyRepository(get(), get<AppDatabase>().behaviorTendencyDao()) }
        single { BehaviorTrainer(get(), get<AppDatabase>().behaviorTendencyDao()) }
        single { PetStateRepository(get(), get<AppDatabase>().petStateDao()) }
        single { ActionPlanRepository(get(), get<AppDatabase>().actionPlanDao()) }
        single { RecoveryManager(get()) }

        single { ExternalCacheEmissionGuard(get<AppDatabase>().externalCacheDao()) }
        single { ExternalCacheRepository(get(), get<AppDatabase>().externalCacheDao(), get()) }
        single<SceneThresholdProvider> { FrozenSceneThresholdProvider() }

        // All supplier adapters share this transport, so provider cooldowns and
        // concurrency limits apply across chat, discovery, weather and settings
        // instead of each UI/Worker path maintaining its own partial guard.
        single<HttpTransport> { GovernedHttpTransport(OkHttpHttpTransport()) }
        single<SongLyricSearchService> { PublicSongLyricSearchService(get()) }
        single<AnimationAdapter> { CatalogAnimationAdapter() }
        single<NotificationAdapter> { AndroidNotificationAdapter(get()) }
        single<WidgetStateAdapter> { AndroidWidgetStateAdapter(get(), get(), get()) }
        single { ChannelExecutionCoordinator(get(), get(), get(), get(), get(), get(), get(), get()) }
        single<ChannelRetryScheduler> { OutboxRetryScheduler(get(), get(), get()) }
        single { IntentHandoffManager(get(), get()) }
        single<DeliveryEngagement> { DeliveryEngagementTracker(get()) }

        single<ByokSettingsStore> {
            ByokSettingsStore(
                get(),
                sharedBailianKeyProvider = { get<EmbeddingSettingsStore>().bailianApiKeyOrNull() },
                sharedBailianEndpointProvider = { get<EmbeddingSettingsStore>().bailianChatEndpoint() },
            )
        }
        single<ByokRuntimeConfig> { get<ByokSettingsStore>() }
        single { EmbeddingSettingsStore(get()) }
        single { EmbeddingConnectionTester(get()) }
        single<QWeatherSettingsStore> { QWeatherSettingsStore(get()) }
        single { QWeatherConnectionTester(get()) }
        single { MiniMaxConnectionTester(get(), get()) }
        single { BailianConnectionTester(get(), get()) }
        // First-create owns the transition from "installed" to "usable". The
        // gate is a singleton so chat, memory and background scheduling all see
        // the same completion state.
        single { FirstCreateStore(get()) }
        single { FirstCreateServiceValidator(get(), get(), get(), get()) }
        single { FirstCreateGate(get()) }
        single { ConnectionReadinessReader(get(), get()) }
        // The persona is written by a model the user already verified, through
        // the same task gateway every other structured job uses.
        single<PersonaGenerator> { ModelPersonaGenerator(get()) }
        single<TaskModelGateway> { ProfiledTaskModelGateway(get(), get(), diagnosticsLogger = get()) }
        single { CompanionFallbackReplyCatalog(get(), get(), get()) }
        single { CompanionFallbackReplyWarmup(get()) }
        single<com.littlekitty.xiaokele.provider.ReplyMemoryLookup> {
            com.littlekitty.xiaokele.provider.SourceGroundedReplyMemoryLookup(get(), get())
        }
        single { ReplyCoordinator(get(), get(), get(), get()) }
        single { SourceGroundedSummarizer(get(), taskGateway = get(), diagnosticsLogger = getOrNull()) }
        single { AppModelRouter(get(), get(), get(), get()) }
        single<ModelService> { get<AppModelRouter>() }
        single<FrontChatModelService> { get<AppModelRouter>() }
        single<CreativeEvaluationModelService> { get<AppModelRouter>() }
        single { CreativeCatchUpCoordinator(get(), get(), get()) }
        // Discovery is deliberately MiniMax-only and must never borrow the
        // shared Bailian Chat/Embedding credential.
        single<DiscoveryFeedService> { ProfiledDiscoveryFeedService(get(), get(), get()) }
        single { DiscoveryLocationResolver(get(), get()) }
        single<CurrentLocationResolver> { get<DiscoveryLocationResolver>() }
        single { com.littlekitty.xiaokele.discovery.DiscoveryCacheStore(get()) }
        single<DiscoveryKnowledgeProvider> { get<com.littlekitty.xiaokele.discovery.DiscoveryCacheStore>() }
        single<MemoryEvaluator> { MiniMaxDirectMemoryEvaluator(get(), get(), get()) }
        single<EmbeddingService> {
            DashScopeEmbeddingServiceAdapter(
                transport = get(),
                configProvider = { get<EmbeddingSettingsStore>().embeddingConfig() },
            )
        }

        single<MemorySearch> { LocalMemorySearch(get(), get(), get()) }
        single<MemoryReviewGateway> { ProfiledMemoryReviewGateway(get()) }
        single { MemoryAnalysisProcessor(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        // Resolved lazily: MemoryAnalysisProcessor depends on this hook, so an
        // eager delegate lookup would form a Koin cycle.
        single<MemoryAnalysisHook> {
            FirstCreateGatedMemoryAnalysisHook(
                delegateFactory = { AsyncMemoryAnalysisHook(get()) },
                gate = get(),
            )
        }
        single { ConversationBudgetPolicy() }
        single<ConversationConsolidator> { MiniMaxConversationConsolidator(get(), get(), get()) }
        single { ConversationContextManager(get(), get(), get(), get(), get(), get()) }
        single { ConversationSessionCoordinator(get(), get(), get(), get()) }
        single<CoreProfileSynthesizer> { MiniMaxCoreProfileSynthesizer(get(), get(), get()) }
        single { UserProfileContextManager(get(), get(), get(), diagnosticsLogger = get(), taskGateway = get()) }

        single<WeatherService> {
            val qWeatherSettings = get<QWeatherSettingsStore>()
            QWeatherServiceAdapter(
                transport = get(),
                configProvider = {
                    QWeatherServiceConfig(
                        apiHost = qWeatherSettings.apiHost(),
                        apiKey = qWeatherSettings.loadApiKeyOrNull().orEmpty(),
                    )
                },
            )
        }
        single<LocationFixProvider> { AndroidLocationFixProvider(get()) }

        single { ExternalContextResolver(get(), get()) }
        single<LocalChatToolExecutor> {
            DefaultLocalChatToolExecutor(
                weatherService = get(),
                contextResolver = get(),
                timeProvider = get(),
                currentLocationResolver = get(),
                creativeLibraryAccess = get(),
                songLyricSearchService = get(),
                // The production chat tool executor is intentionally read-only.
                // Do not resolve ExternalDataCoordinator here: it owns the
                // background dispatcher/pipeline and would create a Koin cycle
                // through AppModelRouter -> LocalChatToolExecutor. A durable
                // location refresh belongs to the external-data workers; this
                // executor already has allowDurableWrites=false.
                locationSnapshotRefresher = null,
                allowDurableWrites = false,
            )
        }
        single { ChatContextBuilder(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single {
            FrontChatPipeline(
                db = get(),
                modelService = get(),
                contextBuilder = get(),
                animationAdapter = get(),
                timeProvider = get(),
                memoryAnalysisHook = get(),
                todoClaimCoordinator = get(),
                deliveryEngagement = get(),
                diagnosticsLogger = get(),
                frontChatModelService = get(),
                localChatToolExecutor = get(),
                creativeChatCoordinator = get(),
                conversationSessionCoordinator = get(),
                replyCoordinator = get(),
                fallbackReplyCatalog = get(),
            )
        }
        single { ChatController(get(), get(), getOrNull(), get()) }

        single { BackgroundContextBuilder(get(), get(), get(), get(), get(), get(), get()) }
        single { ScenePreconditionGate(get(), get(), get(), get(), get()) }
        single { EventNormalizer(get<AppDatabase>().eventDao(), get()) }
        single {
            BackgroundCompanionPipeline(
                db = get(),
                modelService = get(),
                policyResolver = get(),
                contextBuilder = get(),
                mutationExecutor = get(),
                animationAdapter = get(),
                timeProvider = get(),
                channelCoordinator = get(),
                sceneGate = get(),
                triggerWakeReconcile = { TriggerWakeScheduler.reconcile(get()) },
                diagnosticsLogger = get(),
                automationRuleStore = get(),
                replyCoordinator = get(),
                useProfiledReplyCoordinator = true,
            )
        }
        single { LocalBackgroundDispatcher(get(), get(), get()) }
        single { WeatherRefresh(get(), get(), get(), get(), get()) }
        single { SceneCheckScheduler(get(), get(), get(), get(), get(), get(), get()) }
        single {
            ExternalDataCoordinator(
                externalCacheRepository = get(),
                contextResolver = get(),
                weatherRefresh = get(),
                timeProvider = get(),
                dispatcher = get(),
                sceneCheckScheduler = get(),
                locationFixProvider = get(),
            )
        }
        single<BackgroundScheduler> { DueTriggerScheduler(get(), get(), get(), get()) }
    }
}
