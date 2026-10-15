package com.littlekitty.xiaokele.floating

/** Process-local foreground marker used to keep proactive overlays out of the chat page. */
object AppVisibilityState {
    private var startedActivities = 0

    @Synchronized
    fun onActivityStart() {
        startedActivities += 1
    }

    @Synchronized
    fun onActivityStop() {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
    }

    @get:Synchronized
    val isForeground: Boolean
        get() = startedActivities > 0
}
