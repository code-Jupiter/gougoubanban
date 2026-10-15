package com.littlekitty.xiaokele.settings

import android.content.Context

/**
 * Local-life search radius.
 *
 * This replaces the removed geofence store. After the public edition dropped
 * home/hospital places there is no geofence left to configure; the only
 * user-owned location setting is how wide a radius local-life search may use.
 */
class LocationRadiusSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun radiusMeters(): String = prefs.getString(KEY_RADIUS_METERS, "").orEmpty()

    /** Blank clears the override and lets the product default apply. */
    fun saveOrClear(radiusMeters: String): Boolean {
        val normalized = radiusMeters.trim()
        if (normalized.isBlank()) {
            return prefs.edit().remove(KEY_RADIUS_METERS).commit()
        }
        if (normalized.toDoubleOrNull()?.let { it in MIN_RADIUS..MAX_RADIUS } != true) return false
        return prefs.edit().putString(KEY_RADIUS_METERS, normalized).commit()
    }

    companion object {
        private const val PREFS_NAME = "little_kitty_location_settings"
        private const val KEY_RADIUS_METERS = "radius_meters"
        const val MIN_RADIUS = 100.0
        const val MAX_RADIUS = 50_000.0
    }
}
