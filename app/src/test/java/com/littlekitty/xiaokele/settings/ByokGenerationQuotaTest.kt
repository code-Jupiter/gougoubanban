package com.littlekitty.xiaokele.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ByokGenerationQuotaTest {
    @Test
    fun rollingRequestsPersistAcrossStoreInstancesAndRejectTheNextRequestAtTheLimit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = ByokSettingsStore(context)
        val start = 1_800_000_000_000L
        repeat(ByokSettingsStore.ROLLING_GENERATION_LIMIT) {
            assertTrue(first.reserveGenerationRequest(isPro = false, nowMillis = start + it))
        }

        val second = ByokSettingsStore(context)
        assertFalse(
            second.reserveGenerationRequest(
                isPro = false,
                nowMillis = start + ByokSettingsStore.ROLLING_GENERATION_LIMIT,
            ),
        )
        assertTrue(second.reserveGenerationRequest(isPro = false, nowMillis = start + ByokSettingsStore.ROLLING_WINDOW_MILLIS + 1))
    }

    @Test
    fun proRequestsUseTheSameRollingLimitWithoutADailyCap() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ByokSettingsStore(context)
        val day = 1_800_000_000_000L
        repeat(ByokSettingsStore.ROLLING_GENERATION_LIMIT) { offset ->
            assertTrue(store.reserveGenerationRequest(isPro = true, nowMillis = day + offset))
        }
        assertFalse(
            store.reserveGenerationRequest(
                isPro = true,
                nowMillis = day + ByokSettingsStore.ROLLING_GENERATION_LIMIT,
            ),
        )
    }
}
