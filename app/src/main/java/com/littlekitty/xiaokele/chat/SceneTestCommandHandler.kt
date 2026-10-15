package com.littlekitty.xiaokele.chat

/**
 * Optional debug-only bridge for scheduling deterministic external-scene tests
 * from the normal chat input. Release builds leave this unbound, so ordinary
 * user text continues through the real front-chat pipeline.
 */
interface SceneTestCommandHandler {
    /** Returns a user-facing notice when [text] is a recognized test command. */
    suspend fun trySchedule(text: String): String?
}
