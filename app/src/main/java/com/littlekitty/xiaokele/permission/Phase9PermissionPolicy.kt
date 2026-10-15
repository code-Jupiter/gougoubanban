package com.littlekitty.xiaokele.permission

import android.Manifest
import android.content.Context
import android.os.Build

/**
 * First-run permission policy. Notification is requested first on API 33+;
 * foreground location uses the coarse+fine combination required by Android 12+.
 * Location is only used while the app is active to resolve the user's current
 * city for optional local-life features.
 */
object Phase9PermissionPolicy {

    const val PREFS_NAME = "little_kitty_permission_onboarding"
    const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    const val KEY_PERMISSION_PROMPT_SHOWN_AT = "permission_prompt_shown_at"

    fun permissionsToRequest(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = buildList {
        if (sdkInt >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (sdkInt >= 31) {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun shouldRequestNotification(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= 33

    fun shouldRequestForegroundLocation(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= 31
}

class PermissionOnboardingStore(context: Context) {
    private val prefs = context.getSharedPreferences(
        Phase9PermissionPolicy.PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun isCompleted(): Boolean = prefs.getBoolean(Phase9PermissionPolicy.KEY_ONBOARDING_COMPLETED, false)

    fun markCompleted(nowEpochMillis: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putBoolean(Phase9PermissionPolicy.KEY_ONBOARDING_COMPLETED, true)
            .putLong(Phase9PermissionPolicy.KEY_PERMISSION_PROMPT_SHOWN_AT, nowEpochMillis)
            .apply()
    }

    fun promptShownAt(): Long? =
        prefs.getLong(Phase9PermissionPolicy.KEY_PERMISSION_PROMPT_SHOWN_AT, -1L).takeIf { it >= 0 }
}
