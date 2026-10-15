package com.littlekitty.xiaokele

import android.app.Application
import com.littlekitty.xiaokele.channel.ChannelRetryScheduler
import com.littlekitty.xiaokele.channel.OutboxRetryWorkScheduler
import com.littlekitty.xiaokele.chat.CharacterCoreBootstrap
import com.littlekitty.xiaokele.chat.ConversationSessionScheduler
import com.littlekitty.xiaokele.automation.AutomationTimeRuleScheduler
import com.littlekitty.xiaokele.discovery.DiscoverySyncWorker
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.policy.DevelopmentCandidatePolicies
import com.littlekitty.xiaokele.core.database.policy.ProductScenePolicyCatalog
import com.littlekitty.xiaokele.core.database.repository.BehaviorTrainer
import com.littlekitty.xiaokele.core.database.repository.RecoveryManager
import com.littlekitty.xiaokele.di.AppModule
import com.littlekitty.xiaokele.firstcreate.FirstCreateGate
import kotlinx.coroutines.flow.first
import com.littlekitty.xiaokele.event.BackgroundScheduler
import com.littlekitty.xiaokele.event.TriggerWakeScheduler
import com.littlekitty.xiaokele.observability.PrivacyLog
import com.littlekitty.xiaokele.external.WeatherRefreshWorker
import com.littlekitty.xiaokele.scene.SceneCheckScheduler
import com.littlekitty.xiaokele.scene.SceneCheckWorker
import com.littlekitty.xiaokele.widget.WidgetStateAdapter
import com.littlekitty.xiaokele.creative.CreativeCatchUpCoordinator
import com.littlekitty.xiaokele.creative.CreativeCatchUpWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import org.koin.core.module.Module

class LittleKittyApp : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val modules = mutableListOf<Module>()
        modules += AppModule.module
        // Debug-only override module is resolved reflectively. The debug source
        // set contains DebugChannelModule; release has no such class and no
        // debug fake/adapter/trigger is compiled in.
        debugChannelModule()?.let { modules += it }
        debugExternalDataModule()?.let { modules += it }
        val koin = startKoin {
            allowOverride(true)
            androidLogger(Level.ERROR)
            androidContext(this@LittleKittyApp)
            modules(modules)
        }
        applicationScope.launch {
            runCatching {
                val gate = koin.koin.get<FirstCreateGate>()
                // Fail-closed: background companionship is only armed once the
                // user has confirmed their companion in the first-create flow.
                gate.refresh()
                if (!gate.isCompleted()) {
                    com.littlekitty.xiaokele.observability.PrivacyLog.debug(
                        "first_create_incomplete: background scheduling withheld",
                    )
                    return@launch
                }
                WeatherRefreshWorker.enqueuePeriodic(this@LittleKittyApp)
                SceneCheckWorker.enqueuePeriodic(this@LittleKittyApp)
                DiscoverySyncWorker.enqueuePeriodic(this@LittleKittyApp)
                CreativeCatchUpWorker.enqueuePeriodic(this@LittleKittyApp)
                AutomationTimeRuleScheduler.register(this@LittleKittyApp)
                runCatching {
                    com.littlekitty.xiaokele.rhythm.WakeTopicScheduler.register(this@LittleKittyApp)
                }
            }
        }
        applicationScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                val db = koin.koin.get<AppDatabase>()
                CharacterCoreBootstrap.ensureSeeded(db, now)
                koin.koin.get<BehaviorTrainer>().ensureDefaults(now)
                ProductScenePolicyCatalog.ensureSeeded(db)
                if (BuildConfig.DEBUG) {
                    // DEV_CANDIDATE policies are debug-only test scaffolding and
                    // are never written to a release database.
                    DevelopmentCandidatePolicies.ensureSeeded(db)
                }
                val gate = koin.koin.get<FirstCreateGate>()
                gate.refresh()
                if (!gate.isCompleted()) {
                    // Seed data is required for the first-create flow itself; the
                    // background passes below must wait for confirmation.
                    return@launch
                }
                val startupInstant = java.time.Instant.ofEpochMilli(now)
                runCatching { koin.koin.get<CreativeCatchUpCoordinator>().catchUp(startupInstant) }
                val recovery = koin.koin.get<RecoveryManager>().run(now)
                PrivacyLog.debug("startup_recovery=${recovery.safeLogSummary()}")
                val scheduler = koin.koin.get<BackgroundScheduler>()
                scheduler.recoverFired(startupInstant)
                scheduler.dispatchDue(startupInstant)
                TriggerWakeScheduler.reconcile(this@LittleKittyApp)
                ConversationSessionScheduler.reconcile(this@LittleKittyApp)
                koin.koin.get<SceneCheckScheduler>().check()
                // Phase 5 persisted-outbox startup pass: only not-invoked
                // recovery rows and due known-failure retries are eligible.
                koin.koin.get<ChannelRetryScheduler>().dispatchRetries()
                OutboxRetryWorkScheduler.reconcile(this@LittleKittyApp, System.currentTimeMillis())
                koin.koin.get<WidgetStateAdapter>().refreshCurrentPetState()
            }
        }
    }

    private fun debugChannelModule(): Module? {
        if (BuildConfig.DEBUG.not()) return null
        return runCatching {
            val clazz = Class.forName("com.littlekitty.xiaokele.debug.DebugChannelModule")
            val instanceField = clazz.getField("INSTANCE")
            val instance = instanceField.get(null)
            clazz.getMethod("getModule").invoke(instance) as? Module
        }.getOrNull()
    }

    private fun com.littlekitty.xiaokele.core.database.repository.RecoveryReport.safeLogSummary(): String =
        "expiredEvents=$expiredEvents recoveredProcessing=$recoveredProcessingEvents " +
            "outcomeUnknown=$actionExecutionsOutcomeUnknown retryableMessages=$recoveredStaleMessages"

    private fun debugExternalDataModule(): Module? {
        if (BuildConfig.DEBUG.not()) return null
        return runCatching {
            val clazz = Class.forName("com.littlekitty.xiaokele.debug.DebugExternalDataModule")
            val instanceField = clazz.getField("INSTANCE")
            val instance = instanceField.get(null)
            clazz.getMethod("getModule").invoke(instance) as? Module
        }.getOrNull()
    }
}
