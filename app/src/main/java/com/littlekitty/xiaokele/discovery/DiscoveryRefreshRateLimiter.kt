package com.littlekitty.xiaokele.discovery

import android.content.Context

/**
 * Limits user-triggered local-life provider attempts with a persisted sliding
 * window. Capacity is reserved before I/O, so a failed or rate-limited provider
 * response cannot be used to bypass the refresh limit by repeated tapping.
 */
class DiscoveryRefreshRateLimiter(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Returns null when the refresh is allowed. Otherwise returns the number
     * of milliseconds until the oldest retained attempt leaves the window.
     */
    fun retryAfterMillis(): Long? = synchronized(LOCK) {
        val now = nowMillis()
        val active = loadTimestamps()
            .filter { timestamp -> timestamp <= now && now - timestamp < WINDOW_MILLIS }

        if (active.size >= MAX_REFRESHES) {
            return@synchronized (active.minOrNull()!! + WINDOW_MILLIS - now).coerceAtLeast(1L)
        }

        null
    }

    /**
     * Atomically reserves one provider attempt. Returns null when reserved, or
     * the wait duration when the persisted window is full.
     */
    fun tryAcquire(): Long? = synchronized(LOCK) {
        val now = nowMillis()
        val active = loadTimestamps()
            .filter { timestamp -> timestamp <= now && now - timestamp < WINDOW_MILLIS }
        if (active.size >= MAX_REFRESHES) {
            return@synchronized (active.minOrNull()!! + WINDOW_MILLIS - now).coerceAtLeast(1L)
        }
        saveTimestamps(active + now)
        null
    }

    private fun loadTimestamps(): List<Long> =
        prefs.getString(KEY_ATTEMPTS, null)
            .orEmpty()
            .split(',')
            .mapNotNull(String::toLongOrNull)

    private fun saveTimestamps(timestamps: List<Long>) {
        prefs.edit()
            .putString(KEY_ATTEMPTS, timestamps.sorted().joinToString(","))
            .commit()
    }

    companion object {
        const val PREFS = "little_kitty_discovery_refresh_rate"
        const val KEY_ATTEMPTS = "attempt_timestamps_v3"
        const val MAX_REFRESHES = 2
        const val WINDOW_MILLIS = 60L * 60L * 1000L
        private val LOCK = Any()
    }
}
