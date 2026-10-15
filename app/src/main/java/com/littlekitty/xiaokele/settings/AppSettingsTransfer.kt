package com.littlekitty.xiaokele.settings

import android.content.ContentResolver
import android.net.Uri
import com.littlekitty.xiaokele.floating.FloatingWindowSettingsStore
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.littlekitty.xiaokele.provider.AgentModelRoute
import com.littlekitty.xiaokele.provider.ModelProvider
import com.littlekitty.xiaokele.provider.TaskKind
import com.littlekitty.xiaokele.provider.defaultAgentModelRoutes
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant

/** The editable settings represented by the settings screen at export time. */
data class SettingsTransferInput(
    val miniMaxApiKey: String,
    val miniMaxRegion: String,
    val miniMaxEndpoint: String,
    val miniMaxModel: String,
    val qWeatherApiHost: String,
    val qWeatherApiKey: String,
    val embeddingApiHost: String,
    val embeddingApiKey: String,
    val embeddingModel: String,
    /** Missing in older exports; both capability switches default to on. */
    val embeddingEnabled: Boolean = true,
    val webSearchEnabled: Boolean = true,
    val locationRadiusMeters: String,
    val floatingWindowEnabled: Boolean,
    val floatingWindowXOffset: Int?,
    val floatingWindowYOffset: Int?,
    val discoveryNotificationsEnabled: Boolean,
    val discoveryAreas: Set<String>,
    val discoveryCategories: Set<String>,
    val agentRoutes: Map<TaskKind, AgentModelRoute> = defaultAgentModelRoutes(),
    // Kept only as source compatibility for callers compiled against schema 1;
    // the new document never serializes or imports this legacy secret block.
    val generationProfile: String = com.littlekitty.xiaokele.provider.GenerationProfile.MINIMAX_OFFICIAL.name,
    val bailianApiKey: String = "",
    val bailianFlashModel: String = ByokSettingsStore.DEFAULT_BAILIAN_FLASH_MODEL,
    val bailianProModel: String = ByokSettingsStore.DEFAULT_BAILIAN_PRO_MODEL,
)

@Serializable
data class AppSettingsTransferDocument(
    @SerialName("schema_version") val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val type: String = DOCUMENT_TYPE,
    @SerialName("exported_at") val exportedAt: String = "",
    val minimax: MiniMaxSettings = MiniMaxSettings(),
    @kotlinx.serialization.Transient
    val bailian: BailianSettings = BailianSettings(),
    @SerialName("agent_models") val agentModels: List<AgentRouteSettings> = defaultAgentModelRoutes().map { (task, route) ->
        AgentRouteSettings(task.name, route.provider.name, route.defaultModelId, route.complexModelId)
    },
    val qweather: QWeatherSettings = QWeatherSettings(),
    val embedding: EmbeddingSettings = EmbeddingSettings(),
    val location: LocationSettings = LocationSettings(),
    @SerialName("floating_window") val floatingWindow: FloatingWindowSettings = FloatingWindowSettings(),
    val discovery: DiscoverySettings = DiscoverySettings(),
) {
    /**
     * Schema 1 predates per-Agent routing and may contain a retired MiniMax
     * model identifier. Treat those values as migration input only: use the
     * current approved M2 model and the current Agent defaults, never persist
     * the legacy route as an active configuration.
     */
    fun migratedForImport(): AppSettingsTransferDocument = if (schemaVersion == 1) {
        copy(
            minimax = minimax.copy(model = ByokSettingsStore.DEFAULT_MODEL),
            agentModels = defaultAgentModelRoutes().map { (task, route) ->
                AgentRouteSettings(task.name, route.provider.name, route.defaultModelId, route.complexModelId)
            },
            qweather = qweather.copy(apiHost = normalizeLegacyHttpsHost(qweather.apiHost)),
            embedding = embedding.copy(apiHost = normalizeLegacyHttpsHost(embedding.apiHost)),
        )
    } else {
        this
    }

    fun validate() {
        require(schemaVersion == 1 || schemaVersion == CURRENT_SCHEMA_VERSION) { "不支持的配置文件版本" }
        require(type == DOCUMENT_TYPE) { "不是狗狗伴伴配置文件" }
        require(minimax.region == ByokSettingsStore.REGION_CN || minimax.region == ByokSettingsStore.REGION_INTL) {
            "MiniMax 区域无效"
        }
        require(minimax.endpoint.length <= MAX_TEXT_LENGTH) { "MiniMax 地址过长" }
        require(minimax.model.length <= MAX_TEXT_LENGTH) { "MiniMax 模型名过长" }
        require(qweather.apiHost.length <= MAX_TEXT_LENGTH) { "天气 Host 过长" }
        require(embedding.apiHost.length <= MAX_TEXT_LENGTH) { "Embedding Host 过长" }
        require(embedding.model.length <= MAX_TEXT_LENGTH) { "Embedding 模型名过长" }
        require(minimax.model == ByokSettingsStore.DEFAULT_MODEL) {
            "MiniMax 必须使用已审核的官方模型 ID"
        }
        if (schemaVersion >= CURRENT_SCHEMA_VERSION) validateAgentModels()
        require(embedding.model.isNotBlank()) { "Embedding 模型名不能为空" }
        require(optionalHttpsUrl(minimax.endpoint)) { "MiniMax 地址必须是 HTTPS URL" }
        require(optionalHttpsUrl(qweather.apiHost)) { "天气 Host 必须是 HTTPS URL" }
        require(optionalHttpsUrl(embedding.apiHost)) { "Embedding Host 必须是 HTTPS URL" }
        require(floatingWindow.xOffset == null || floatingWindow.yOffset != null) { "悬浮窗位置必须同时提供横纵坐标" }
        require(floatingWindow.yOffset == null || floatingWindow.xOffset != null) { "悬浮窗位置必须同时提供横纵坐标" }
        require(discovery.areas.all(::isValidDiscoveryToken)) { "本地生活地区无效" }
        require(discovery.categories.all { it in DISCOVERY_CATEGORIES }) { "本地生活分类无效" }
        require(minimax.apiKey.length <= MAX_SECRET_LENGTH) { "MiniMax API Key 过长" }
        require(qweather.apiKey.length <= MAX_SECRET_LENGTH) { "天气 API Key 过长" }
        require(embedding.apiKey.length <= MAX_SECRET_LENGTH) { "Embedding API Key 过长" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 2
        const val DOCUMENT_TYPE = "littlekitty_settings"
        private const val MAX_TEXT_LENGTH = 4_096
        private const val MAX_SECRET_LENGTH = 16_384

        fun from(input: SettingsTransferInput): AppSettingsTransferDocument = AppSettingsTransferDocument(
            exportedAt = Instant.now().toString(),
            minimax = MiniMaxSettings(
                apiKey = normalizeSecret(input.miniMaxApiKey),
                region = input.miniMaxRegion.trim(),
                endpoint = input.miniMaxEndpoint.trim(),
                model = input.miniMaxModel.trim(),
            ),
            agentModels = input.agentRoutes.entries.map { (task, route) ->
                AgentRouteSettings(task.name, route.provider.name, route.defaultModelId, route.complexModelId)
            },
            qweather = QWeatherSettings(
                apiKey = normalizeSecret(input.qWeatherApiKey),
                apiHost = input.qWeatherApiHost.trim(),
            ),
            embedding = EmbeddingSettings(
                apiKey = normalizeSecret(input.embeddingApiKey),
                apiHost = input.embeddingApiHost.trim(),
                model = input.embeddingModel.trim(),
                enabled = input.embeddingEnabled,
            ),
            location = LocationSettings(radiusMeters = input.locationRadiusMeters.trim()),
            floatingWindow = FloatingWindowSettings(
                enabled = input.floatingWindowEnabled,
                xOffset = input.floatingWindowXOffset,
                yOffset = input.floatingWindowYOffset,
            ),
            discovery = DiscoverySettings(
                notificationsEnabled = input.discoveryNotificationsEnabled,
                areas = input.discoveryAreas.toList().sorted(),
                categories = input.discoveryCategories.toList().sorted(),
                webSearchEnabled = input.webSearchEnabled,
            ),
        )

        private fun normalizeSecret(value: String): String = value.trim().trim('"')

        private fun normalizeLegacyHttpsHost(value: String): String {
            val trimmed = value.trim()
            return if (trimmed.isBlank() || trimmed.startsWith("https://", ignoreCase = true)) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }

        private fun optionalHttpsUrl(value: String): Boolean {
            val trimmed = value.trim()
            if (trimmed.isBlank()) return true
            if (trimmed.startsWith("https://").not()) return false
            val rest = trimmed.removePrefix("https://")
            return rest.isNotBlank() && rest.none { it.isWhitespace() }
        }

        private fun isValidDiscoveryToken(value: String): Boolean =
            value.isNotBlank() && value.length <= 64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }

        private val DISCOVERY_CATEGORIES = setOf("events", "food")
    }

    private fun validateAgentModels() {
        require(agentModels.map { it.task }.toSet() == TaskKind.values().map(TaskKind::name).toSet()) { "Agent 路由必须覆盖 A/B1/B2/C/D" }
        agentModels.forEach { item ->
            val task = runCatching { TaskKind.valueOf(item.task) }.getOrElse { error("Agent 类型无效") }
            val provider = runCatching { ModelProvider.valueOf(item.provider) }.getOrElse { error("模型供应商无效") }
            val allowed = when (provider) {
                ModelProvider.MINIMAX_OFFICIAL -> setOf(ByokSettingsStore.DEFAULT_MODEL, ByokSettingsStore.OFFICIAL_HER_MODEL)
                ModelProvider.BAILIAN -> setOf(ByokSettingsStore.DEFAULT_BAILIAN_FLASH_MODEL, ByokSettingsStore.DEFAULT_BAILIAN_PRO_MODEL)
            }
            require(item.defaultModel in allowed && item.complexModel in allowed) { "${task.name} 使用了未知模型" }
        }
    }
}

@Serializable
data class MiniMaxSettings(
    @SerialName("api_key") val apiKey: String = "",
    val region: String = ByokSettingsStore.REGION_CN,
    val endpoint: String = "",
    val model: String = ByokSettingsStore.DEFAULT_MODEL,
)

@Serializable
data class BailianSettings(
    @SerialName("api_key") val apiKey: String = "",
    val profile: String = com.littlekitty.xiaokele.provider.GenerationProfile.MINIMAX_OFFICIAL.name,
    val endpoint: String = ByokSettingsStore.BAILIAN_ENDPOINT,
    @SerialName("flash_model") val flashModel: String = ByokSettingsStore.DEFAULT_BAILIAN_FLASH_MODEL,
    @SerialName("pro_model") val proModel: String = ByokSettingsStore.DEFAULT_BAILIAN_PRO_MODEL,
)

@Serializable
data class AgentRouteSettings(
    val task: String,
    val provider: String,
    @SerialName("default_model") val defaultModel: String,
    @SerialName("complex_model") val complexModel: String,
)

@Serializable
data class QWeatherSettings(
    @SerialName("api_key") val apiKey: String = "",
    @SerialName("api_host") val apiHost: String = "",
)

@Serializable
data class EmbeddingSettings(
    @SerialName("api_key") val apiKey: String = "",
    @SerialName("api_host") val apiHost: String = "",
    val model: String = EmbeddingSettingsStore.DEFAULT_MODEL,
    val enabled: Boolean = true,
)

/**
 * Public-edition location settings.
 *
 * Only the local-life search radius is user-configurable. Home/hospital
 * coordinates were part of the removed geofence feature and are deliberately
 * absent: they are neither exported nor imported, so no accepted document can
 * carry fixed private coordinates.
 */
@Serializable
data class LocationSettings(
    @SerialName("radius_meters") val radiusMeters: String = "",
)

@Serializable
data class FloatingWindowSettings(
    val enabled: Boolean = false,
    @SerialName("x_offset") val xOffset: Int? = null,
    @SerialName("y_offset") val yOffset: Int? = null,
)

@Serializable
data class DiscoverySettings(
    @SerialName("notifications_enabled") val notificationsEnabled: Boolean = true,
    val areas: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    @SerialName("web_search_enabled") val webSearchEnabled: Boolean = true,
)

/** Reads and writes the user-selected local JSON file and applies validated settings. */
class AppSettingsTransferService(
    private val byokStore: ByokSettingsStore,
    private val qWeatherStore: QWeatherSettingsStore,
    private val embeddingStore: EmbeddingSettingsStore,
    private val locationSettingsStore: LocationRadiusSettingsStore,
    private val floatingWindowSettingsStore: FloatingWindowSettingsStore,
    /** Test seam for injecting a failure after the staged stores have changed. */
    private val afterApplyHook: (() -> Unit)? = null,
) {
    suspend fun exportToUri(
        contentResolver: ContentResolver,
        uri: Uri,
        input: SettingsTransferInput,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val document = AppSettingsTransferDocument.from(input)
            document.validate()
            val output = contentResolver.openOutputStream(uri) ?: error("无法写入所选文件")
            output.bufferedWriter(Charsets.UTF_8).use {
                it.write(JsonCodec.pretty(JsonCodec.encode(document)))
            }
        }
    }

    suspend fun importFromUri(
        contentResolver: ContentResolver,
        uri: Uri,
    ): Result<AppSettingsTransferDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val input = contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
            val raw = readBoundedUtf8(input)
            val document = JsonCodec.decode<AppSettingsTransferDocument>(raw).migratedForImport()
            document.validate()
            applyAtomically(document)
            document
        }
    }

    private fun applyAtomically(document: AppSettingsTransferDocument) = byokStore.withRouteWrite {
        val previous = currentDocument()
        try {
            apply(document)
            afterApplyHook?.invoke()
        } catch (error: Throwable) {
            val rollback = runCatching {
                apply(previous)
            }
            if (rollback.isFailure) {
                throw IllegalStateException(
                    "settings_import_rollback_failed: ${rollback.exceptionOrNull()?.message}",
                    error,
                )
            }
            throw error
        }
    }

    private fun apply(document: AppSettingsTransferDocument) {
        check(byokStore.setRegion(document.minimax.region)) { "MiniMax 区域保存失败" }
        check(byokStore.setEndpoint(document.minimax.endpoint)) { "MiniMax 地址保存失败" }
        check(byokStore.setModel(document.minimax.model)) { "MiniMax 模型保存失败" }
        applySecret(document.minimax.apiKey, byokStore::saveKey, byokStore::deleteKey)
        document.agentModels.forEach { item ->
            val task = TaskKind.valueOf(item.task)
            val provider = ModelProvider.valueOf(item.provider)
            check(byokStore.setAgentModelRoute(task, com.littlekitty.xiaokele.provider.RouteComplexity.DEFAULT,
                AgentModelRoute(provider, item.defaultModel, item.defaultModel))) { "${task.name} 普通路由保存失败" }
            check(byokStore.setAgentModelRoute(task, com.littlekitty.xiaokele.provider.RouteComplexity.COMPLEX,
                AgentModelRoute(provider, item.complexModel, item.complexModel))) { "${task.name} 复杂路由保存失败" }
        }

        check(qWeatherStore.setApiHost(document.qweather.apiHost)) { "天气 Host 保存失败" }
        applySecret(document.qweather.apiKey, qWeatherStore::saveApiKey, qWeatherStore::deleteApiKey)

        check(embeddingStore.setApiHost(document.embedding.apiHost)) { "Embedding Host 保存失败" }
        check(embeddingStore.setModel(document.embedding.model)) { "Embedding 模型保存失败" }
        check(embeddingStore.setEnabled(document.embedding.enabled)) { "Embedding 启用开关保存失败" }
        applySecret(document.embedding.apiKey, embeddingStore::saveApiKey, embeddingStore::deleteApiKey)
        check(byokStore.setBailianModelsValidated(false)) { "百炼能力验证状态清除失败" }

        check(locationSettingsStore.saveOrClear(document.location.radiusMeters)) { "搜索半径保存失败" }

        check(floatingWindowSettingsStore.setEnabled(document.floatingWindow.enabled)) { "悬浮窗开关保存失败" }
        if (document.floatingWindow.xOffset != null && document.floatingWindow.yOffset != null) {
            check(floatingWindowSettingsStore.setPosition(
                document.floatingWindow.xOffset,
                document.floatingWindow.yOffset,
            )) { "悬浮窗位置保存失败" }
        } else {
            check(floatingWindowSettingsStore.clearPosition()) { "悬浮窗位置清除失败" }
        }

        check(byokStore.setNotificationsEnabled(document.discovery.notificationsEnabled)) { "本地生活通知保存失败" }
        check(byokStore.setWebSearchEnabled(document.discovery.webSearchEnabled)) { "联网搜索开关保存失败" }
        check(byokStore.setSelectedAreas(document.discovery.areas.toSet())) { "本地生活地区保存失败" }
        check(byokStore.setSelectedCategories(document.discovery.categories.toSet())) { "本地生活分类保存失败" }
    }

    private fun applySecret(value: String, save: (String) -> Boolean, delete: () -> Unit) {
        if (value.isBlank()) {
            delete()
        } else {
            check(save(value)) { "API Key 保存失败" }
        }
    }

    private fun currentDocument(): AppSettingsTransferDocument = AppSettingsTransferDocument.from(
        SettingsTransferInput(
            miniMaxApiKey = byokStore.loadKeyOrNull().orEmpty(),
            miniMaxRegion = byokStore.region(),
            miniMaxEndpoint = byokStore.endpoint(),
            miniMaxModel = byokStore.model(),
            agentRoutes = byokStore.agentModelRoutes(),
            qWeatherApiHost = qWeatherStore.apiHost(),
            qWeatherApiKey = qWeatherStore.loadApiKeyOrNull().orEmpty(),
            embeddingApiHost = embeddingStore.apiHost(),
            embeddingApiKey = embeddingStore.loadApiKeyOrNull().orEmpty(),
            embeddingModel = embeddingStore.model(),
            embeddingEnabled = embeddingStore.isEnabled(),
            webSearchEnabled = byokStore.webSearchEnabled(),
            locationRadiusMeters = locationSettingsStore.radiusMeters(),
            floatingWindowEnabled = floatingWindowSettingsStore.isEnabled(),
            floatingWindowXOffset = floatingWindowSettingsStore.xOffsetOrNull(),
            floatingWindowYOffset = floatingWindowSettingsStore.yOffsetOrNull(),
            discoveryNotificationsEnabled = byokStore.notificationsEnabled(),
            discoveryAreas = byokStore.selectedAreas(),
            discoveryCategories = byokStore.selectedCategories(),
        ),
    )

    private fun readBoundedUtf8(input: InputStream): String {
        val output = ByteArrayOutputStream()
        input.use { source ->
            val buffer = ByteArray(8_192)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                require(output.size() + read <= MAX_IMPORT_BYTES) { "配置文件过大" }
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 1_000_000
    }
}
