package com.littlekitty.xiaokele.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.floating.FloatingWindowSettingsStore
import com.littlekitty.xiaokele.provider.TaskKind
import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AppSettingsTransferTest {
    @Test
    fun documentRoundTripsAllUserConfigurableFields() {
        val document = AppSettingsTransferDocument.from(
            SettingsTransferInput(
                miniMaxApiKey = "mini-key",
                miniMaxRegion = ByokSettingsStore.REGION_INTL,
                miniMaxEndpoint = "https://example.com/chat",
                miniMaxModel = "MiniMax-M2",
                qWeatherApiHost = "https://weather.example.com",
                qWeatherApiKey = "weather-key",
                embeddingApiHost = "https://embedding.example.com",
                embeddingApiKey = "embedding-key",
                embeddingModel = "embedding-model",
                locationRadiusMeters = "500",
                floatingWindowEnabled = true,
                floatingWindowXOffset = 12,
                floatingWindowYOffset = -8,
                discoveryNotificationsEnabled = false,
                discoveryAreas = setOf("hangzhou", "liandu"),
                discoveryCategories = setOf("food"),
            ),
        )

        val decoded = JsonCodec.decode<AppSettingsTransferDocument>(JsonCodec.encode(document))

        assertEquals(document, decoded)
    }

    @Test
    fun legacySchemaUsesCurrentModelAndAgentDefaultsForImport() {
        val legacy = AppSettingsTransferDocument(
            schemaVersion = 1,
            minimax = MiniMaxSettings(model = "retired-model-id"),
            qweather = QWeatherSettings(apiHost = "weather.example.com"),
            embedding = EmbeddingSettings(apiHost = "embedding.example.com"),
            agentModels = listOf(
                AgentRouteSettings(
                    task = "A_INPUT",
                    provider = "MINIMAX_OFFICIAL",
                    defaultModel = "retired-model-id",
                    complexModel = "retired-model-id",
                ),
            ),
        )

        val migrated = legacy.migratedForImport()

        assertEquals(ByokSettingsStore.DEFAULT_MODEL, migrated.minimax.model)
        assertEquals("https://weather.example.com", migrated.qweather.apiHost)
        assertEquals("https://embedding.example.com", migrated.embedding.apiHost)
        assertEquals(
            TaskKind.values().map(TaskKind::name).toSet(),
            migrated.agentModels.map(AgentRouteSettings::task).toSet(),
        )
        migrated.validate()
    }

    @Test
    fun exportedDocumentCarriesOnlyTheSurvivingLocationAndNoScheduleBlock() {
        val document = AppSettingsTransferDocument.from(
            SettingsTransferInput(
                miniMaxApiKey = "",
                miniMaxRegion = ByokSettingsStore.REGION_CN,
                miniMaxEndpoint = "",
                miniMaxModel = ByokSettingsStore.DEFAULT_MODEL,
                qWeatherApiHost = "",
                qWeatherApiKey = "",
                embeddingApiHost = "",
                embeddingApiKey = "",
                embeddingModel = "embedding-model",
                locationRadiusMeters = "800",
                floatingWindowEnabled = false,
                floatingWindowXOffset = null,
                floatingWindowYOffset = null,
                discoveryNotificationsEnabled = true,
                discoveryAreas = emptySet(),
                discoveryCategories = emptySet(),
            ),
        )

        val encoded = JsonCodec.encode(document)
        document.validate()
        assertEquals(LocationSettings(radiusMeters = "800"), document.location)
        assertTrue(encoded.contains("radius_meters"))
        listOf(
            "schedule",
            "home_latitude",
            "home_longitude",
            "hospital_latitude",
            "hospital_longitude",
        ).forEach { removed ->
            assertFalse("removed field $removed must not be exported", encoded.contains(removed))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun validateRejectsUnknownDocumentType() {
        AppSettingsTransferDocument(type = "other_app").validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun validateRejectsNonHttpsServiceEndpoint() {
        AppSettingsTransferDocument(
            minimax = MiniMaxSettings(endpoint = "http://example.test/chat"),
        ).validate()
    }

    @Test(expected = IllegalArgumentException::class)
    fun validateRejectsUnknownAgentModel() {
        AppSettingsTransferDocument(
            agentModels = listOf(
                AgentRouteSettings("A_INTAKE", "BAILIAN", "unknown", "deepseek-v4-pro"),
            ) + com.littlekitty.xiaokele.provider.TaskKind.values().drop(1).map {
                AgentRouteSettings(it.name, "BAILIAN", "deepseek-v4-flash", "deepseek-v4-pro")
            },
        ).validate()
    }

    @Test
    fun applyRollbackRestoresAllStoresAfterInjectedCommitFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val byok = ByokSettingsStore(context)
        val qWeather = QWeatherSettingsStore(context)
        val embedding = EmbeddingSettingsStore(context)
        val location = LocationRadiusSettingsStore(context)
        val floating = FloatingWindowSettingsStore(context)
        val baseline = AppSettingsTransferDocument(
            minimax = MiniMaxSettings(
                region = ByokSettingsStore.REGION_INTL,
                endpoint = "https://baseline.example/minimax",
                model = ByokSettingsStore.DEFAULT_MODEL,
            ),
            qweather = QWeatherSettings(apiHost = "https://baseline.example/weather"),
            embedding = EmbeddingSettings(
                apiHost = "https://baseline.example/embedding",
                model = "baseline-embedding",
            ),
            location = LocationSettings(radiusMeters = "500"),
            floatingWindow = FloatingWindowSettings(enabled = true, xOffset = 12, yOffset = -8),
            discovery = DiscoverySettings(
                notificationsEnabled = false,
                areas = listOf("liandu"),
                categories = listOf("food"),
            ),
        )
        val changed = baseline.copy(
            minimax = baseline.minimax.copy(region = ByokSettingsStore.REGION_CN),
            qweather = QWeatherSettings(apiHost = "https://changed.example/weather"),
            embedding = baseline.embedding.copy(model = "changed-embedding"),
            location = LocationSettings(),
            floatingWindow = FloatingWindowSettings(enabled = false),
            discovery = DiscoverySettings(notificationsEnabled = true, areas = listOf("hangzhou"), categories = listOf("events")),
        )
        val service = AppSettingsTransferService(
            byokStore = byok,
            qWeatherStore = qWeather,
            embeddingStore = embedding,
            locationSettingsStore = location,
            floatingWindowSettingsStore = floating,
            afterApplyHook = { error("injected_settings_apply_failure") },
        )
        val apply = AppSettingsTransferService::class.java.getDeclaredMethod(
            "applyAtomically",
            AppSettingsTransferDocument::class.java,
        ).apply { isAccessible = true }

        apply.invoke(
            AppSettingsTransferService(
                byokStore = byok,
                qWeatherStore = qWeather,
                embeddingStore = embedding,
                locationSettingsStore = location,
                floatingWindowSettingsStore = floating,
            ),
            baseline,
        )
        val thrown = runCatching { apply.invoke(service, changed) }.exceptionOrNull()

        // The production path must surface the injected failure rather than
        // swallowing it: either wrapped (reflection) or thrown directly.
        assertTrue("expected a failure, got $thrown", thrown != null)
        val cause = (thrown as? InvocationTargetException)?.cause ?: thrown
        assertEquals("injected_settings_apply_failure", cause?.message)
        assertEquals(ByokSettingsStore.REGION_INTL, byok.region())
        assertEquals("https://baseline.example/weather", qWeather.apiHost())
        assertEquals("baseline-embedding", embedding.model())
        assertEquals("500", location.radiusMeters())
        assertEquals(12, floating.xOffsetOrNull())
        assertEquals(-8, floating.yOffsetOrNull())
        assertEquals(false, byok.notificationsEnabled())
        assertEquals(setOf("liandu"), byok.selectedAreas())
        assertEquals(setOf("food"), byok.selectedCategories())
    }
}
