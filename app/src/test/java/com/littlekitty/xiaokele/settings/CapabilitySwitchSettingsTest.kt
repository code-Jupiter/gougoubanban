package com.littlekitty.xiaokele.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.floating.FloatingWindowSettingsStore
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CapabilitySwitchSettingsTest {
    @Test
    fun embeddingDefaultsToEnabledAndCanBeDisabledAndReenabled() {
        val embedding = EmbeddingSettingsStore(ApplicationProvider.getApplicationContext())
        assertTrue(embedding.setApiHost("https://embedding.example.com"))
        assertTrue(embedding.isEnabled())

        assertTrue(embedding.setEnabled(false))
        assertFalse(embedding.embeddingConfig().isConfigured)

        assertTrue(embedding.setEnabled(true))
        assertTrue(embedding.isEnabled())
    }

    @Test
    fun embeddingSwitchBumpsTheBailianConfigurationVersion() {
        val embedding = EmbeddingSettingsStore(ApplicationProvider.getApplicationContext())
        val before = embedding.bailianConfigurationVersion()

        assertTrue(embedding.setEnabled(false))

        assertEquals(before + 1L, embedding.bailianConfigurationVersion())
    }

    @Test
    fun webSearchDefaultsToEnabledAndPersistsWhenDisabled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val byok = ByokSettingsStore(context)

        assertTrue(byok.webSearchEnabled())
        assertTrue(byok.setWebSearchEnabled(false))
        assertFalse(ByokSettingsStore(context).webSearchEnabled())
    }

    @Test
    fun oldExportWithoutCapabilityFieldsKeepsBothSwitchesOn() {
        val legacyJson = """
            {
              "schema_version": 2,
              "type": "littlekitty_settings",
              "exported_at": "2026-01-01T00:00:00Z",
              "minimax": {"api_key": "", "region": "CN", "endpoint": "", "model": "MiniMax-M2"},
              "qweather": {"api_key": "", "api_host": ""},
              "embedding": {"api_key": "", "api_host": "", "model": "qwen3.7-text-embedding"},
              "location": {},
              "floating_window": {"enabled": false},
              "discovery": {"notifications_enabled": true, "areas": [], "categories": []}
            }
        """.trimIndent()

        val decoded = JsonCodec.decode<AppSettingsTransferDocument>(legacyJson)

        assertTrue(decoded.embedding.enabled)
        assertTrue(decoded.discovery.webSearchEnabled)
    }

    @Test
    fun transferDocumentRoundTripsExplicitCapabilityValues() {
        val document = AppSettingsTransferDocument(
            embedding = EmbeddingSettings(enabled = false),
            discovery = DiscoverySettings(webSearchEnabled = false),
        )

        val decoded = JsonCodec.decode<AppSettingsTransferDocument>(JsonCodec.encode(document))

        assertFalse(decoded.embedding.enabled)
        assertFalse(decoded.discovery.webSearchEnabled)
    }

    @Test
    fun importAppliesBothCapabilitySwitches() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val byok = ByokSettingsStore(context)
        val embedding = EmbeddingSettingsStore(context)
        val service = AppSettingsTransferService(
            byokStore = byok,
            qWeatherStore = QWeatherSettingsStore(context),
            embeddingStore = embedding,
            locationSettingsStore = LocationRadiusSettingsStore(context),
            floatingWindowSettingsStore = FloatingWindowSettingsStore(context),
        )
        val document = AppSettingsTransferDocument(
            embedding = EmbeddingSettings(apiHost = "https://embedding.example.com", enabled = false),
            discovery = DiscoverySettings(areas = listOf("liandu"), categories = listOf("food"), webSearchEnabled = false),
        )

        applyAtomically(service, document)

        assertFalse(embedding.isEnabled())
        assertFalse(byok.webSearchEnabled())
    }

    private fun applyAtomically(service: AppSettingsTransferService, document: AppSettingsTransferDocument) {
        val method: Method = AppSettingsTransferService::class.java.getDeclaredMethod(
            "applyAtomically",
            AppSettingsTransferDocument::class.java,
        ).apply { isAccessible = true }
        method.invoke(service, document)
    }
}
