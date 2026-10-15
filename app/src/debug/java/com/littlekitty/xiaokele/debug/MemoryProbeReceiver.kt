package com.littlekitty.xiaokele.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.memory.MemoryAnalysisOutcome
import com.littlekitty.xiaokele.memory.MemoryAnalysisProcessor
import com.littlekitty.xiaokele.memory.MemoryAnalysisRequest
import com.littlekitty.xiaokele.provider.AgentModelRoute
import com.littlekitty.xiaokele.provider.ModelProvider
import com.littlekitty.xiaokele.provider.RouteComplexity
import com.littlekitty.xiaokele.provider.TaskKind
import com.littlekitty.xiaokele.settings.ByokSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Debug-only driver for the memory-learning path.
 *
 * Memory evaluation normally runs after a chat turn that also survives the C
 * review stage, so a failing chat turn hides whether memory itself works. This
 * probe runs the production [MemoryAnalysisProcessor] directly against the last
 * persisted user/assistant pair, which isolates the memory path from chat
 * reliability. It logs only counts and non-sensitive outcome names — never
 * message content.
 */
class MemoryProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SET_ROUTES) {
            setRoutes(intent)
            return
        }
        if (intent.action != ACTION) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                run()
            } catch (error: Throwable) {
                Log.i(TAG, "RESULT outcome=ERROR ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Debug-only route writer for cross-provider acceptance: `--es routes
     * "B2_EXPRESSION=MINIMAX_OFFICIAL:M2-her"` (comma-separated). It only calls
     * the production store API, so it cannot reach a state the settings screen
     * could not.
     */
    private fun setRoutes(intent: Intent) {
        val spec = intent.getStringExtra(EXTRA_ROUTES).orEmpty()
        if (spec.isBlank()) {
            Log.i(TAG, "ROUTES outcome=NO_SPEC")
            return
        }
        val store = GlobalContext.get().get<ByokSettingsStore>()
        Log.i(
            TAG,
            "ROUTES resolvedEndpoint=[${store.bailianEndpoint()}]" +
                " validatedMinimax=${store.officialModelsValidated()}" +
                " validatedBailian=${store.bailianModelsValidated()}" +
                " bailianKeyPresent=${!store.bailianKeyOrNull().isNullOrBlank()}",
        )
        spec.split(",").map(String::trim).filter(String::isNotEmpty).forEach { entry ->
            val task = runCatching { TaskKind.valueOf(entry.substringBefore("=").trim()) }.getOrNull()
            val route = entry.substringAfter("=", "").split(":")
            val provider = runCatching { ModelProvider.valueOf(route.firstOrNull().orEmpty().trim()) }.getOrNull()
            val model = route.getOrNull(1)?.trim().orEmpty()
            if (task == null || provider == null || model.isBlank()) {
                Log.i(TAG, "ROUTES outcome=SKIPPED entry=$entry")
                return@forEach
            }
            val applied = listOf(RouteComplexity.DEFAULT, RouteComplexity.COMPLEX).all { complexity ->
                store.setAgentModelRoute(task, complexity, AgentModelRoute(provider, model, model))
            }
            Log.i(TAG, "ROUTES $task applied=$applied provider=$provider model=$model")
        }
        store.agentModelRoutes().forEach { (task, route) ->
            Log.i(TAG, "ROUTE_NOW $task ${route.provider}:${route.defaultModelId}")
        }
        // Acceptance helper: writing a route clears that provider's proof by
        // design, exactly as the settings screen does. The production first-run
        // flow marks the proof *after* applying routes; a probe that writes
        // routes afterwards must re-mark it, or it tests a state the product
        // never produces.
        if (intent.getBooleanExtra(EXTRA_REMARK_PROOF, false)) {
            val bailian = store.setBailianModelsValidated(true)
            val official = store.markOfficialValidation(
                keyFingerprint = store.loadKeyOrNull()
                    ?.let { com.littlekitty.xiaokele.settings.KeyFingerprint.stableOf(it) }.orEmpty(),
                region = store.region(),
                endpoint = store.endpoint(),
            )
            Log.i(TAG, "REMARK bailian=$bailian official=$official")
        }
    }

    private suspend fun run() {
        val db = GlobalContext.get().get<AppDatabase>()
        val processor = GlobalContext.get().get<MemoryAnalysisProcessor>()

        val recent = db.messageDao().recentAsc(20)
        val user = recent.lastOrNull { it.role.equals("USER", ignoreCase = true) }
        val assistant = recent.lastOrNull { it.role.equals("ASSISTANT", ignoreCase = true) }
        if (user == null || assistant == null) {
            Log.i(TAG, "RESULT outcome=NO_TURN user=${user != null} assistant=${assistant != null}")
            return
        }
        Log.i(TAG, "turn user=${user.id} assistant=${assistant.id} before=${db.memoryDao().count()}")

        val outcome = processor.analyze(
            MemoryAnalysisRequest(
                userMessageId = user.id,
                assistantMessageId = assistant.id,
                eventId = "memory_probe_${System.currentTimeMillis()}",
                rawDecisionJson = "{}",
                analysisMode = "EXPLICIT_TURN",
            ),
        )
        val after = db.memoryDao().count()
        when (outcome) {
            is MemoryAnalysisOutcome.Applied -> Log.i(
                TAG,
                "RESULT outcome=APPLIED stored=${outcome.memoryWriteReport.memoryIds.size}" +
                    " embeddings=${outcome.embeddingCount} degraded=${outcome.embeddingDegraded}",
            )

            is MemoryAnalysisOutcome.Failure ->
                Log.i(TAG, "RESULT outcome=FAILED reason=${outcome.error.code.name} detail=${outcome.error.technicalDetail}")
        }
        Log.i(TAG, "memory_rows=$after")
    }

    private companion object {
        const val TAG = "MemoryProbe"
        const val ACTION = "com.littlekitty.xiaokele.debug.MEMORY_PROBE"
        const val ACTION_SET_ROUTES = "com.littlekitty.xiaokele.debug.SET_ROUTES"
        const val EXTRA_ROUTES = "routes"
        const val EXTRA_REMARK_PROOF = "remark_proof"
    }
}
