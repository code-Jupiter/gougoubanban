package com.littlekitty.xiaokele.debug

import com.littlekitty.xiaokele.core.network.embedding.DashScopeEmbeddingServiceAdapter
import com.littlekitty.xiaokele.core.network.embedding.EmbeddingService
import com.littlekitty.xiaokele.core.network.embedding.FakeEmbeddingService
import com.littlekitty.xiaokele.core.network.memory.FakeMemoryEvaluator
import com.littlekitty.xiaokele.core.network.memory.MemoryEvaluator
import com.littlekitty.xiaokele.core.network.model.FakeModelService
import com.littlekitty.xiaokele.memory.MiniMaxDirectMemoryEvaluator
import com.littlekitty.xiaokele.notification.AndroidNotificationAdapter
import com.littlekitty.xiaokele.notification.NotificationAdapter
import com.littlekitty.xiaokele.notification.NotificationSendRequest
import com.littlekitty.xiaokele.notification.NotificationSendResult
import com.littlekitty.xiaokele.diagnostics.DiagnosticsLogger
import com.littlekitty.xiaokele.diagnostics.AcceptanceTraceRecorder
import com.littlekitty.xiaokele.settings.EmbeddingSettingsStore
import com.littlekitty.xiaokele.widget.AndroidWidgetStateAdapter
import com.littlekitty.xiaokele.widget.WidgetRefreshResult
import com.littlekitty.xiaokele.widget.WidgetStateAdapter
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

enum class DebugNotificationMode {
    DELIVER,
    PERMISSION_DENIED,
    ADAPTER_FAILURE,
}

/**
 * Debug-only channel adapters and controls. This class only exists in
 * app/src/debug and is loaded reflectively by LittleKittyApp; release APKs do
 * not contain this file, these fakes or any simulation control.
 *
 * When the real-AI acceptance "real chain mode" is enabled, the visible
 * channels (notification, widget) and the memory chain (evaluator, embedding)
 * bind to the production implementations so acceptance observes what a real
 * user sees. Fake adapters stay registered for the debug lab.
 */
object DebugChannelModule {

    val module = module {
        single<AcceptanceTraceRecorder> {
            DebugAcceptanceTraceStore(androidContext(), get())
        }
        // Debug acceptance runs tag every diagnostic record with the active
        // stage/run. Release keeps the standard logger with no such context.
        single<DiagnosticsLogger> {
            DiagnosticsLogger(
                db = get(),
                timeProvider = get(),
                traceContextProvider = { RealAiAcceptanceStore(androidContext()).traceFields() },
                acceptanceTraceRecorder = get(),
            )
        }
        single<NotificationAdapter> {
            AndroidNotificationAdapter(androidContext())
        }
        single<WidgetStateAdapter> {
            AndroidWidgetStateAdapter(androidContext(), get(), get())
        }
        single<MemoryEvaluator> {
            // Use the same profiled task gateway as release so an Bailian
            // debug acceptance can never fall through to the official key.
            MiniMaxDirectMemoryEvaluator(get(), get(), get())
        }
        single<EmbeddingService> {
            DashScopeEmbeddingServiceAdapter(
                transport = get(),
                configProvider = { get<EmbeddingSettingsStore>().embeddingConfig() },
            )
        }
    }
}

class DebugNotificationAdapter : NotificationAdapter {

    @Volatile
    var mode: DebugNotificationMode = DebugNotificationMode.DELIVER

    @Volatile
    var sentRequests: MutableList<NotificationSendRequest> = mutableListOf()

    override suspend fun send(request: NotificationSendRequest): NotificationSendResult {
        sentRequests += request
        return when (mode) {
            DebugNotificationMode.DELIVER -> NotificationSendResult.Delivered
            DebugNotificationMode.PERMISSION_DENIED ->
                NotificationSendResult.PermissionDenied("debug fake permission denied")
            DebugNotificationMode.ADAPTER_FAILURE ->
                NotificationSendResult.AdapterFailure("debug fake system adapter failure")
        }
    }
}

class DebugWidgetStateAdapter : WidgetStateAdapter {

    @Volatile
    var refreshCount: Int = 0

    override suspend fun refreshCurrentPetState(): WidgetRefreshResult {
        refreshCount++
        return WidgetRefreshResult.Refreshed(emptyList())
    }
}
