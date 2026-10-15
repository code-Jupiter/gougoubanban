package com.littlekitty.xiaokele.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Phase 5 widget provider: every update renders the persisted Room pet_state
 * through the shared semantic animation directory. The receiver itself performs
 * no Room writes and no model calls; failed widget refresh is isolated from
 * chat/pet-state transactions.
 */
class LittleDogWidgetProvider : AppWidgetProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // System call is intentionally outside every Room transaction; goAsync
        // keeps the broadcast alive until the Room read and RemoteViews render
        // have finished.
        val pending = goAsync()
        scope.launch {
            try {
                runCatching { GlobalContext.get().get<WidgetStateAdapter>() }
                    .getOrNull()
                    ?.refreshCurrentPetState()
            } finally {
                pending.finish()
            }
        }
    }
}
