package com.littlekitty.xiaokele.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.littlekitty.xiaokele.MainActivity
import com.littlekitty.xiaokele.R
import com.littlekitty.xiaokele.animation.AnimationAdapter
import com.littlekitty.xiaokele.animation.AnimationCatalog
import com.littlekitty.xiaokele.animation.CatalogAnimationAdapter
import com.littlekitty.xiaokele.core.common.id.EntityId
import com.littlekitty.xiaokele.core.common.json.JsonCodec
import com.littlekitty.xiaokele.core.database.AppDatabase
import com.littlekitty.xiaokele.core.database.entity.PetStateEntity
import com.littlekitty.xiaokele.handoff.InternalIntentContract
import kotlinx.serialization.json.JsonObject

data class WidgetRenderModel(
    val stateId: String,
    val animationId: String,
    val shortText: String?,
    val showShortTextBubble: Boolean,
    val clickEventId: String?,
    val clickTodoId: String?,
)

sealed interface WidgetRefreshResult {
    data class Refreshed(val widgetIds: List<Int>) : WidgetRefreshResult
    data class NoWidgets(val detail: String) : WidgetRefreshResult
    data class Failure(val detail: String) : WidgetRefreshResult
}

interface WidgetStateAdapter {
    suspend fun refreshCurrentPetState(): WidgetRefreshResult
}

/**
 * Pure Phase 5 widget-state mapper shared by tests and the RemoteViews renderer.
 * D-06 final visual is intentionally not frozen: the current renderer is a
 * minimal candidate layout. Empty short_text hides the bubble; non-empty text
 * is limited to two lines. Unknown animations are already normalized to
 * default_idle before this mapper runs.
 */
object WidgetStateMapper {

    fun fromPetState(petState: PetStateEntity?, animationAdapter: AnimationAdapter): WidgetRenderModel {
        val normalizedAnimation = animationAdapter.normalize(petState?.animationId)
        val shortText = petState?.shortText?.takeIf { it.isNotBlank() }
        return WidgetRenderModel(
            stateId = petState?.stateId ?: "IDLE",
            animationId = normalizedAnimation,
            shortText = shortText,
            showShortTextBubble = shortText != null,
            clickEventId = petState?.reasonEventId?.takeIf { EntityId.isValid(it) },
            clickTodoId = petState?.clickTargetJson?.let { WidgetClickTarget.todoIdOrNull(it) },
        )
    }
}

object WidgetClickTarget {
    fun todoIdOrNull(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val obj = runCatching { JsonCodec.decode<JsonObject>(raw) }.getOrNull() ?: return null
        val todo = obj["todo_id"]?.toString()?.trim('"').orEmpty()
        return todo.takeIf { EntityId.isValid(it) }
    }
}

/** Standalone widget refresh entry for callers that only hold a database. */
object WidgetUnreadBadge {
    suspend fun refresh(context: Context, db: AppDatabase) {
        runCatching {
            AndroidWidgetStateAdapter(context, db, CatalogAnimationAdapter()).refreshCurrentPetState()
        }
    }
}

class AndroidWidgetStateAdapter(
    private val context: Context,
    private val db: AppDatabase,
    private val animationAdapter: AnimationAdapter,
) : WidgetStateAdapter {

    override suspend fun refreshCurrentPetState(): WidgetRefreshResult {
        return try {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, LittleDogWidgetProvider::class.java)
            val widgetIds = manager.getAppWidgetIds(component).toList()
            if (widgetIds.isEmpty()) {
                WidgetRefreshResult.NoWidgets("no home-screen widgets placed")
            } else {
                val petState = db.petStateDao().get()
                val unreadCount = db.messageDao().countUnreadProactive()
                widgetIds.forEach { widgetId ->
                    manager.updateAppWidget(widgetId, render(widgetId, petState, unreadCount))
                }
                WidgetRefreshResult.Refreshed(widgetIds)
            }
        } catch (throwable: Throwable) {
            WidgetRefreshResult.Failure(throwable.message ?: "widget refresh failed")
        }
    }

    fun render(widgetId: Int, petState: PetStateEntity?, unreadCount: Int = 0): RemoteViews {
        val model = WidgetStateMapper.fromPetState(petState, animationAdapter)
        val views = RemoteViews(context.packageName, R.layout.widget_little_dog)
        views.setViewVisibility(R.id.widget_status, if (model.showShortTextBubble) View.VISIBLE else View.GONE)
        views.setTextViewText(
            R.id.widget_status,
            model.shortText ?: context.getString(R.string.widget_status),
        )
        views.setInt(R.id.widget_status, "setMaxLines", 2)
        views.setTextViewText(R.id.widget_dog_label, model.stateId)
        views.setImageViewResource(R.id.widget_dog_image, AnimationCatalog.resourceFor(model.animationId))
        if (unreadCount > 0) {
            views.setViewVisibility(R.id.widget_unread_badge, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_unread_badge,
                context.getString(R.string.unread_badge_text, unreadCount),
            )
        } else {
            views.setViewVisibility(R.id.widget_unread_badge, View.GONE)
        }
        views.setOnClickPendingIntent(R.id.widget_root, buildClickPendingIntent(widgetId, model))
        return views
    }

    private fun buildClickPendingIntent(widgetId: Int, model: WidgetRenderModel): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        model.clickEventId?.let { intent.putExtra(InternalIntentContract.EXTRA_EVENT_ID, it) }
        model.clickTodoId?.let { intent.putExtra(InternalIntentContract.EXTRA_TODO_ID, it) }
        intent.putExtra(InternalIntentContract.EXTRA_SOURCE, InternalIntentContract.SOURCE_WIDGET)
        return PendingIntent.getActivity(
            context,
            widgetId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
