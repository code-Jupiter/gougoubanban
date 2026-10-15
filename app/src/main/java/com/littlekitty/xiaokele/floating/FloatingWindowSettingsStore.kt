package com.littlekitty.xiaokele.floating

import android.content.Context

class FloatingWindowSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean): Boolean {
        return prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()
    }

    fun xOffsetOrNull(): Int? = if (prefs.contains(KEY_X_OFFSET)) {
        prefs.getInt(KEY_X_OFFSET, 0)
    } else {
        null
    }

    fun yOffsetOrNull(): Int? = if (prefs.contains(KEY_Y_OFFSET)) {
        prefs.getInt(KEY_Y_OFFSET, 0)
    } else {
        null
    }

    fun setPosition(xOffset: Int, yOffset: Int): Boolean {
        return prefs.edit()
            .putInt(KEY_X_OFFSET, xOffset)
            .putInt(KEY_Y_OFFSET, yOffset)
            .commit()
    }

    /** Clears an exported position when the imported document omits it. */
    fun clearPosition(): Boolean = prefs.edit()
        .remove(KEY_X_OFFSET)
        .remove(KEY_Y_OFFSET)
        .commit()

    private companion object {
        const val PREFS_NAME = "little_kitty_floating_window"
        const val KEY_ENABLED = "enabled"
        const val KEY_X_OFFSET = "x_offset"
        const val KEY_Y_OFFSET = "y_offset"
    }
}
