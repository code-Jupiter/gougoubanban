package com.littlekitty.xiaokele.discovery

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DiscoveryRefreshRateLimiterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStoredAttempts() {
        context.getSharedPreferences(DiscoveryRefreshRateLimiter.PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun atomicallyReservesTwoAttemptsAndBlocksTheThirdWithinOneHour() {
        var now = 1_000_000L
        val limiter = DiscoveryRefreshRateLimiter(context) { now }

        assertNull(limiter.tryAcquire())
        assertNull(limiter.tryAcquire())
        assertEquals(DiscoveryRefreshRateLimiter.WINDOW_MILLIS, limiter.retryAfterMillis())

        now += DiscoveryRefreshRateLimiter.WINDOW_MILLIS
        assertNull(limiter.retryAfterMillis())
    }

    @Test
    fun limitPersistsAcrossLimiterInstances() {
        var now = 2_000_000L
        val first = DiscoveryRefreshRateLimiter(context) { now }
        assertNull(first.tryAcquire())
        assertNull(first.tryAcquire())

        val second = DiscoveryRefreshRateLimiter(context) { now }
        assertEquals(DiscoveryRefreshRateLimiter.WINDOW_MILLIS, second.retryAfterMillis())
    }

    @Test
    fun failedOrRateLimitedProviderAttemptStillConsumesQuota() {
        var now = 3_000_000L
        val limiter = DiscoveryRefreshRateLimiter(context) { now }

        // The limiter cannot observe the provider result. Reserving before I/O
        // makes the failed-attempt case behave exactly like a successful one.
        assertNull(limiter.tryAcquire())
        assertNull(limiter.tryAcquire())
        assertEquals(DiscoveryRefreshRateLimiter.WINDOW_MILLIS, limiter.tryAcquire())
    }
}
