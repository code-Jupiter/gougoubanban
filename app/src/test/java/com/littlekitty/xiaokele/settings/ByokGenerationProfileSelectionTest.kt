package com.littlekitty.xiaokele.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.littlekitty.xiaokele.provider.AgentModelRoute
import com.littlekitty.xiaokele.provider.ModelProvider
import com.littlekitty.xiaokele.provider.RouteComplexity
import com.littlekitty.xiaokele.provider.TaskKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ByokAgentRouteValidationTest {
    private lateinit var store: ByokSettingsStore
    private lateinit var routerCipher: TestCipher

    @Before
    fun resetSettings() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        routerCipher = TestCipher()
        store = ByokSettingsStore(context, TestCipher(), routerCipher)
        store.deleteBailianKey()
        store.deleteKey()
        context.getSharedPreferences(ByokSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences(ByokSettingsStore.VAULT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = ByokSettingsStore(context, TestCipher(), routerCipher)
    }

    @Test
    fun bailianKeyAloneDoesNotValidateBailianRoutes() {
        assertTrue(store.saveBailianKey("router-test-key"))

        assertFalse(store.bailianModelsValidated())
        assertEquals(ModelProvider.BAILIAN, store.agentModelRoute(TaskKind.A_INTAKE).provider)
    }

    @Test
    fun validatedBailianCapabilityPersistsAcrossStoreInstances() {
        val key = "router-test-key"
        assertTrue(store.saveBailianKey(key))
        assertTrue(
            store.markBailianValidation(
                keyFingerprint = KeyFingerprint.stableOf(key),
                flashModel = ByokSettingsStore.DEFAULT_BAILIAN_FLASH_MODEL,
                proModel = ByokSettingsStore.DEFAULT_BAILIAN_PRO_MODEL,
            ),
        )

        val reloaded = ByokSettingsStore(ApplicationProvider.getApplicationContext(), TestCipher(), routerCipher)
        assertTrue(reloaded.bailianModelsValidated())
    }

    @Test
    fun savingAnotherKeyInvalidatesBailianValidation() {
        val firstKey = "router-first-key"
        assertTrue(store.saveBailianKey(firstKey))
        assertTrue(
            store.markBailianValidation(
                keyFingerprint = KeyFingerprint.stableOf(firstKey),
                flashModel = ByokSettingsStore.DEFAULT_BAILIAN_FLASH_MODEL,
                proModel = ByokSettingsStore.DEFAULT_BAILIAN_PRO_MODEL,
            ),
        )

        assertTrue(store.saveBailianKey("router-second-key"))
        assertFalse(store.bailianModelsValidated())
    }

    @Test
    fun changingAnAgentProviderResetsItsComplexModelToTheSameProviderCatalog() {
        assertTrue(
            store.setAgentModelRoute(
                TaskKind.A_INTAKE,
                RouteComplexity.DEFAULT,
                AgentModelRoute(ModelProvider.MINIMAX_OFFICIAL, ByokSettingsStore.DEFAULT_MODEL),
            ),
        )

        val route = store.agentModelRoutes().getValue(TaskKind.A_INTAKE)
        assertEquals(ModelProvider.MINIMAX_OFFICIAL, route.provider)
        assertEquals(ByokSettingsStore.DEFAULT_MODEL, route.defaultModelId)
        assertEquals(ByokSettingsStore.DEFAULT_MODEL, route.complexModelId)
    }

    @Test
    fun legacyMiniMaxProfileMigratesEveryTaskToMiniMaxRoutes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(ByokSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(ByokSettingsStore.KEY_AGENT_ROUTE_MIGRATION_V1)
            .putString(
                ByokSettingsStore.KEY_LEGACY_GENERATION_PROFILE,
                ByokSettingsStore.LEGACY_MINIMAX_OFFICIAL_PROFILE,
            )
            .commit()

        val migrated = ByokSettingsStore(context, TestCipher(), routerCipher)

        TaskKind.values().forEach { task ->
            val route = migrated.agentModelRoutes().getValue(task)
            assertEquals(ModelProvider.MINIMAX_OFFICIAL, route.provider)
            assertEquals(
                if (task == TaskKind.B2_EXPRESSION) ByokSettingsStore.OFFICIAL_HER_MODEL
                else ByokSettingsStore.DEFAULT_MODEL,
                route.defaultModelId,
            )
            assertEquals(route.defaultModelId, route.complexModelId)
        }
        assertFalse(
            context.getSharedPreferences(ByokSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
                .contains(ByokSettingsStore.KEY_LEGACY_GENERATION_PROFILE),
        )
    }

    @Test
    fun orphanedDefaultBailianRoutesMigrateToTheSavedMiniMaxKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(store.saveKey("official-test-key"))
        TaskKind.values().forEach { task ->
            val defaults = com.littlekitty.xiaokele.provider.defaultAgentModelRoutes().getValue(task)
            assertTrue(
                store.setAgentModelRoute(
                    task,
                    RouteComplexity.DEFAULT,
                    AgentModelRoute(
                        defaults.provider,
                        defaults.defaultModelId,
                    ),
                ),
            )
            assertTrue(
                store.setAgentModelRoute(
                    task,
                    RouteComplexity.COMPLEX,
                    AgentModelRoute(
                        defaults.provider,
                        defaults.complexModelId,
                    ),
                ),
            )
        }
        context.getSharedPreferences(ByokSettingsStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ByokSettingsStore.KEY_AGENT_ROUTE_MIGRATION_V1, true)
            .remove(ByokSettingsStore.KEY_AGENT_ROUTE_MIGRATION_V2)
            .commit()

        val migrated = ByokSettingsStore(context, TestCipher(), routerCipher)

        TaskKind.values().forEach { task ->
            val route = migrated.agentModelRoutes().getValue(task)
            assertEquals(ModelProvider.MINIMAX_OFFICIAL, route.provider)
            assertEquals(
                if (task == TaskKind.B2_EXPRESSION) ByokSettingsStore.OFFICIAL_HER_MODEL
                else ByokSettingsStore.DEFAULT_MODEL,
                route.defaultModelId,
            )
            assertEquals(route.defaultModelId, route.complexModelId)
        }
    }

    private class TestCipher : ApiKeyCipher {
        override fun encrypt(plainText: String): String = plainText

        override fun decrypt(stored: String): String = stored
    }
}
