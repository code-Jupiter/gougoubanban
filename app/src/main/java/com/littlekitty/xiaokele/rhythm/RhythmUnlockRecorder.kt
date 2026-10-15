package com.littlekitty.xiaokele.rhythm

import android.content.Context
import com.littlekitty.xiaokele.core.common.time.TimeProvider
import org.koin.java.KoinJavaComponent

/** Records one screen-unlock observation for the wake-window estimator. */
object RhythmUnlockRecorder {
    fun record(context: Context, atEpochMillis: Long = System.currentTimeMillis()) {
        RhythmObservationStore(context).recordUnlock(atEpochMillis, TimeProvider.DISPLAY_ZONE)
    }
}
