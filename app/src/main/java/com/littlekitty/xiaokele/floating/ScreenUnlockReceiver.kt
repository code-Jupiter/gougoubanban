package com.littlekitty.xiaokele.floating

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.littlekitty.xiaokele.external.UnlockSceneWorker

/** Wakes the scene handoff immediately after the user unlocks the device. */
class ScreenUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_USER_PRESENT) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            UnlockSceneWorker.UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UnlockSceneWorker>().build(),
        )
    }
}
