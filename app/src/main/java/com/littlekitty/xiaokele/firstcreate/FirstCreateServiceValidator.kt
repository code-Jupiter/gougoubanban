package com.littlekitty.xiaokele.firstcreate

import com.littlekitty.xiaokele.provider.AgentModelRoute
import com.littlekitty.xiaokele.provider.BailianConnectionTester
import com.littlekitty.xiaokele.provider.BailianModelTestResult
import com.littlekitty.xiaokele.provider.MiniMaxConnectionTester
import com.littlekitty.xiaokele.provider.ModelProvider
import com.littlekitty.xiaokele.provider.OfficialModelTestResult
import com.littlekitty.xiaokele.provider.RouteComplexity
import com.littlekitty.xiaokele.provider.TaskKind
import com.littlekitty.xiaokele.provider.TaskModelRouter
import com.littlekitty.xiaokele.settings.ByokSettingsStore
import com.littlekitty.xiaokele.settings.EmbeddingSettingsStore

/**
 * Real capability check for the single selected generation provider.
 *
 * Rules enforced here:
 * - exactly one provider is validated per attempt;
 * - the other provider's credential is never read, written or borrowed as a
 *   fallback, so a failing provider cannot silently run on the other key;
 * - the key is written to its own store only after the check succeeds, and a
 *   failed check keeps the user's input so the step is resumable.
 */
class FirstCreateServiceValidator(
    private val byokSettingsStore: ByokSettingsStore,
    private val embeddingSettingsStore: EmbeddingSettingsStore,
    private val miniMaxTester: MiniMaxConnectionTester,
    private val bailianTester: BailianConnectionTester,
) {

    sealed interface Result {
        data class Success(val validation: ServiceValidationState) : Result
        data class Failure(val reason: String) : Result
    }

    suspend fun validate(
        provider: SelectedGenerationProvider,
        rawKey: String,
        region: String = ByokSettingsStore.REGION_CN,
    ): Result {
        val key = rawKey.trim().trim('"')
        if (key.isBlank()) return Result.Failure("请先填写所选服务的 API Key。")

        return when (provider) {
            SelectedGenerationProvider.MINIMAX_OFFICIAL -> validateMiniMax(key, region)
            SelectedGenerationProvider.BAILIAN -> validateBailian(key)
        }
    }

    private suspend fun validateMiniMax(key: String, region: String): Result {
        val endpoint = if (region == ByokSettingsStore.REGION_INTL) {
            MiniMaxEndpoint.INTL
        } else {
            MiniMaxEndpoint.CN
        }
        val keyFingerprint = com.littlekitty.xiaokele.settings.KeyFingerprint.stableOf(key)

        // A single successful chat call is not enough. The chat graph needs a
        // structured-decision model (M2) *and* the plain-text expression model
        // (M2-her); the gateway fail-closes every Agent route until both are
        // proven for this exact key, region and endpoint.
        val profile = miniMaxTester.testOfficialProfile(
            rawKey = key,
            region = region,
            endpoint = endpoint,
        )
        if (profile is OfficialModelTestResult.Failure) {
            return Result.Failure(profile.userMessage)
        }

        if (!byokSettingsStore.saveKey(key)) return Result.Failure("API Key 保存失败，请重试。")
        if (!byokSettingsStore.setEndpoint("")) return Result.Failure("默认地址保存失败，请重试。")
        byokSettingsStore.setRegion(region)

        // Point every Agent at this provider first. Changing an Agent route
        // intentionally invalidates capability proof, so doing this after
        // recording the proof would silently wipe it and leave the first-run
        // gate permanently closed. Structured Agents must use the default
        // model: `M2-her` is plain-text-only and is refused for A/B1/C/D.
        val routesApplied = TaskKind.values().all { task ->
            val model = if (task == TaskKind.B2_EXPRESSION) {
                ByokSettingsStore.OFFICIAL_HER_MODEL
            } else {
                ByokSettingsStore.DEFAULT_MODEL
            }
            val route = AgentModelRoute(ModelProvider.MINIMAX_OFFICIAL, model, model)
            byokSettingsStore.setAgentModelRoute(task, RouteComplexity.DEFAULT, route) &&
                byokSettingsStore.setAgentModelRoute(task, RouteComplexity.COMPLEX, route)
        }
        if (!routesApplied) return Result.Failure("Agent 路由保存失败，请重试。")

        // Now record the proof, which is the last write so nothing clears it.
        val recorded = byokSettingsStore.markOfficialValidation(
            keyFingerprint = keyFingerprint,
            region = region,
            endpoint = byokSettingsStore.endpoint(),
        )
        if (!recorded) return Result.Failure("能力验证状态保存失败，请重试。")

        return Result.Success(
            ServiceValidationState(
                provider = SelectedGenerationProvider.MINIMAX_OFFICIAL.name,
                validated = true,
                validatedAt = System.currentTimeMillis(),
                modelId = ByokSettingsStore.DEFAULT_MODEL,
                endpointHost = hostOf(endpoint),
                credentialFingerprint = byokSettingsStore.fingerprint().orEmpty(),
            ),
        )
    }

    private suspend fun validateBailian(key: String): Result {
        val endpoint = TaskModelRouter.BAILIAN_ENDPOINT
        val flash = ByokSettingsStore.DEFAULT_BAILIAN_FLASH_MODEL
        val pro = ByokSettingsStore.DEFAULT_BAILIAN_PRO_MODEL
        val outcome = bailianTester.test(
            rawKey = key,
            endpoint = endpoint,
            modelIds = setOf(flash, pro),
        )
        if (outcome is BailianModelTestResult.Failure) {
            return Result.Failure(outcome.userMessage)
        }
        if (!embeddingSettingsStore.saveApiKey(key)) {
            return Result.Failure("API Key 保存失败，请重试。")
        }
        // Routes first, then the proof: changing an Agent route invalidates
        // capability proof, so the reverse order would wipe it.
        val routesApplied = TaskKind.values().all { task ->
            val route = AgentModelRoute(ModelProvider.BAILIAN, flash, pro)
            byokSettingsStore.setAgentModelRoute(task, RouteComplexity.DEFAULT, route) &&
                byokSettingsStore.setAgentModelRoute(task, RouteComplexity.COMPLEX, route)
        }
        if (!routesApplied) return Result.Failure("Agent 路由保存失败，请重试。")

        // Bailian Chat and Embedding intentionally share one credential; the
        // MiniMax key is never read or written on this path.
        byokSettingsStore.setBailianModelsValidated(true)

        return Result.Success(
            ServiceValidationState(
                provider = SelectedGenerationProvider.BAILIAN.name,
                validated = true,
                validatedAt = System.currentTimeMillis(),
                modelId = flash,
                endpointHost = hostOf(endpoint),
                credentialFingerprint = embeddingSettingsStore.fingerprint().orEmpty(),
            ),
        )
    }

    private fun hostOf(endpoint: String): String =
        runCatching { java.net.URI(endpoint).host.orEmpty() }.getOrDefault("")

    private object MiniMaxEndpoint {
        const val CN = "https://api.minimaxi.com/v1/text/chatcompletion_v2"
        const val INTL = "https://api.minimax.io/v1/text/chatcompletion_v2"
    }
}
