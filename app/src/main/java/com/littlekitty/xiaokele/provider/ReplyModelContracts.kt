package com.littlekitty.xiaokele.provider

import java.util.concurrent.atomic.AtomicLong

/** Legacy schema values retained only while importing old settings. */
enum class GenerationProfile {
    MINIMAX_OFFICIAL,
    BAILIAN,
}

/** Stable business task names. These are deliberately independent of vendors. */
enum class TaskKind {
    A_INTAKE,
    B1_DECISION,
    B2_EXPRESSION,
    C_REVIEW,
    D_MEMORY,
}

/** The same reply graph serves visible user turns and proactive/idle turns. */
enum class ReplyEntryPoint {
    FOREGROUND,
    /** A UI-owned idle follow-up still persists as a front-chat reply, but may choose NO_ACTION. */
    FOREGROUND_IDLE,
    PROACTIVE,
}

/** C is one task role with an explicit, object-specific review recipe. */
enum class ReviewKind {
    FINAL_REPLY,
    MEMORY_CANDIDATE,
    RELATIONSHIP_CONFIRMATION,
    STATE_UPDATE,
}

enum class ModelProvider {
    MINIMAX_OFFICIAL,
    BAILIAN,
}

data class AgentModelRoute(
    val provider: ModelProvider,
    val defaultModelId: String,
    val complexModelId: String = defaultModelId,
)

fun defaultAgentModelRoutes(): Map<TaskKind, AgentModelRoute> = mapOf(
    TaskKind.A_INTAKE to AgentModelRoute(ModelProvider.BAILIAN, "deepseek-v4-flash-0731", "deepseek-v4-pro"),
    TaskKind.B1_DECISION to AgentModelRoute(ModelProvider.BAILIAN, "deepseek-v4-flash-0731", "deepseek-v4-pro"),
    TaskKind.B2_EXPRESSION to AgentModelRoute(ModelProvider.MINIMAX_OFFICIAL, "M2-her", "M2-her"),
    TaskKind.C_REVIEW to AgentModelRoute(ModelProvider.BAILIAN, "deepseek-v4-flash-0731", "deepseek-v4-pro"),
    TaskKind.D_MEMORY to AgentModelRoute(ModelProvider.BAILIAN, "deepseek-v4-flash-0731", "deepseek-v4-pro"),
)

enum class RouteComplexity {
    DEFAULT,
    COMPLEX,
}

/** Capabilities are part of a route contract, not inferred from a model display name. */
data class CapabilityProfile(
    val supportsStructuredOutput: Boolean,
    val supportsTools: Boolean,
    val supportsThinking: Boolean,
    val maxOutputTokens: Int,
    val reportsUsage: Boolean,
    val plainTextOnly: Boolean,
    val isPro: Boolean = false,
) {
    init {
        require(maxOutputTokens > 0) { "maxOutputTokens must be positive" }
        require(!(plainTextOnly && supportsStructuredOutput)) {
            "plainTextOnly and supportsStructuredOutput are mutually exclusive"
        }
    }

    companion object {
        val MINIMAX_M2 = CapabilityProfile(
            // MiniMax M2 is schema-guided by prompt plus local validation.
            // by prompt plus local validation instead.
            supportsStructuredOutput = false,
            supportsTools = true,
            supportsThinking = true,
            maxOutputTokens = 8_192,
            reportsUsage = true,
            plainTextOnly = false,
        )
        val DEEPSEEK_FLASH = CapabilityProfile(
            supportsStructuredOutput = true,
            supportsTools = true,
            supportsThinking = true,
            maxOutputTokens = 8_192,
            reportsUsage = true,
            plainTextOnly = false,
        )
        val DEEPSEEK_PRO = DEEPSEEK_FLASH.copy(isPro = true)
        /** HER is never used for A/C/D and must not be asked for JSON or tools. */
        val HER = CapabilityProfile(
            supportsStructuredOutput = false,
            supportsTools = false,
            supportsThinking = false,
            maxOutputTokens = 2_048,
            reportsUsage = true,
            plainTextOnly = true,
        )
    }
}

data class ModelRouteSnapshot(
    val provider: ModelProvider,
    val modelId: String,
    val capabilityProfile: CapabilityProfile,
    val promptVersion: String,
    val routeVersion: Long,
)

/** Request accounting is kept separate from raw provider traces. */
data class ModelUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
)

enum class CostStatus {
    KNOWN,
    UNKNOWN,
    NOT_APPLICABLE,
}

data class ModelCallProvenance(
    val provider: ModelProvider,
    val modelId: String,
    val usage: ModelUsage = ModelUsage(),
    val costStatus: CostStatus = CostStatus.UNKNOWN,
    val errorCode: String? = null,
)

/** A route snapshot is valid only while its route and authorization version match. */
class TaskModelRouter(
    initialRouteVersion: Long = 1L,
    private val promptVersion: String = ReplyPromptPack.VERSION,
    private val configuredRoutes: Map<TaskKind, AgentModelRoute> = defaultAgentModelRoutes(),
) {
    @Volatile
    private var routes: Map<TaskKind, AgentModelRoute> = configuredRoutes
    private val routeVersion = AtomicLong(initialRouteVersion.coerceAtLeast(1L))
    @Volatile
    private var lastConfiguration: RouteConfiguration? = null

    /** Call this after a key is removed, authorization is revoked, or model availability changes. */
    @Synchronized
    fun invalidate(): Long {
        lastConfiguration = null
        return routeVersion.incrementAndGet()
    }

    fun currentRouteVersion(): Long = routeVersion.get()

    /**
     * Synchronizes persisted authorization/configuration. The monotonically
     * increasing version changes on every identity transition, including an
     * Bailian -> official -> Bailian ABA sequence.
     */
    @Synchronized
    fun synchronize(configuration: RouteConfiguration): Long {
        if (lastConfiguration != configuration) {
            routes = configuration.agentRoutes
            lastConfiguration = configuration
            routeVersion.incrementAndGet()
        }
        return routeVersion.get()
    }

    fun resolve(
        task: TaskKind,
        complexity: RouteComplexity = RouteComplexity.DEFAULT,
        flashModelOverride: String? = null,
        proModelOverride: String? = null,
    ): ModelRouteSnapshot {
        val selectedVersion = routeVersion.get()
        val route = routes[task] ?: defaultAgentModelRoutes().getValue(task)
        val modelId = if (complexity == RouteComplexity.COMPLEX) {
            proModelOverride ?: route.complexModelId
        } else {
            flashModelOverride ?: route.defaultModelId
        }
        val capability = when {
            modelId == MINIMAX_HER_MODEL -> CapabilityProfile.HER
            route.provider == ModelProvider.BAILIAN && modelId.contains("-pro") -> CapabilityProfile.DEEPSEEK_PRO
            route.provider == ModelProvider.BAILIAN -> CapabilityProfile.DEEPSEEK_FLASH
            else -> CapabilityProfile.MINIMAX_M2
        }
        return ModelRouteSnapshot(
            route.provider,
            modelId,
            capability,
            promptVersion,
            selectedVersion,
        )
    }

    fun isCurrent(snapshot: ModelRouteSnapshot): Boolean =
        snapshot.routeVersion == routeVersion.get()

    companion object {
        const val BAILIAN_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        /**
         * MiniMax model used for every Agent except the chat expression one.
         * MiniMax-M2 remains a selectable model for users who need it.
         */
        const val MINIMAX_M3_MODEL = "MiniMax-M3"
        const val MINIMAX_M2_MODEL = "MiniMax-M2"
        /** Official MiniMax Chat Model docs use the exact ID `M2-her`. */
        const val MINIMAX_HER_MODEL = "M2-her"
        const val DEFAULT_BAILIAN_FLASH_MODEL = "deepseek-v4-flash-0731"
        const val DEFAULT_BAILIAN_PRO_MODEL = "deepseek-v4-pro"
        const val DEFAULT_BAILIAN_DISCOVERY_MODEL = DEFAULT_BAILIAN_FLASH_MODEL
    }
}

data class RouteConfiguration(
    val authorizationVersion: Long,
    val keyFingerprint: String?,
    val endpoint: String,
    val flashModel: String,
    val proModel: String,
    val validationFingerprint: String?,
    val modelsValidated: Boolean,
    val agentRoutes: Map<TaskKind, AgentModelRoute> = defaultAgentModelRoutes(),
)

/** Lightweight commit guard used by coordinators before publishing a model result. */
class RouteAttemptGuard(
    private val router: TaskModelRouter,
    val snapshot: ModelRouteSnapshot,
) {
    fun canCommit(): Boolean = router.isCurrent(snapshot)
}

/** Per-attempt request accounting; Pro may be reserved at most once. */
class GenerationBudget(
    private val maxRequests: Int = 6,
) {
    private var requests = 0
    private var proRequests = 0

    init {
        require(maxRequests > 0) { "maxRequests must be positive" }
    }

    @Synchronized
    fun reserve(isPro: Boolean = false): Boolean {
        if (requests >= maxRequests || (isPro && proRequests >= 1)) return false
        requests++
        if (isPro) proRequests++
        return true
    }

    @Synchronized
    fun usedRequests(): Int = requests

    @Synchronized
    fun remainingRequests(): Int = maxRequests - requests

    @Synchronized
    fun proRequestsUsed(): Int = proRequests
}
