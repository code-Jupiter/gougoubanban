package com.littlekitty.xiaokele.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.littlekitty.xiaokele.MainActivity
import com.littlekitty.xiaokele.R
import com.littlekitty.xiaokele.handoff.InternalIntentContract

data class NotificationSendRequest(
    val executionId: String,
    val eventId: String?,
    val todoId: String?,
    val title: String,
    val body: String,
    /**
     * Silent delivery (wake weather brief): visible on the lockscreen but no
     * sound/vibration/heads-up, so a generation that lands while the user
     * still sleeps never wakes them.
     */
    val silent: Boolean = false,
)

sealed interface NotificationSendResult {
    data object Delivered : NotificationSendResult
    data class PermissionDenied(val detail: String) : NotificationSendResult
    data class AdapterFailure(val detail: String) : NotificationSendResult
}

/**
 * System-side notification boundary. The coordinator reserves delivery_history
 * before calling [send], marks the reservation invoked, and only writes
 * delivered_at after [NotificationSendResult.Delivered] is returned.
 */
interface NotificationAdapter {
    suspend fun send(request: NotificationSendRequest): NotificationSendResult
}

object NotificationPermission {
    fun canPost(context: Context): Boolean =
        hasRuntimePermission(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun hasRuntimePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    fun shouldRequestRuntimePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
}

/**
 * Stable positive notification id derived from the persisted execution id. The
 * same execution therefore always maps to the same OS notification id; two
 * retries can replace, never duplicate, a posted notification.
 */
object NotificationId {
    fun forExecutionId(executionId: String): Int {
        val hash = executionId.hashCode()
        return (hash and Int.MAX_VALUE).coerceAtLeast(1)
    }
}

class AndroidNotificationAdapter(
    private val context: Context,
) : NotificationAdapter {

    private val manager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override suspend fun send(request: NotificationSendRequest): NotificationSendResult {
        if (NotificationPermission.canPost(context).not()) {
            return NotificationSendResult.PermissionDenied(
                if (NotificationPermission.hasRuntimePermission(context).not()) {
                    "POST_NOTIFICATIONS permission denied"
                } else {
                    "notifications disabled by user"
                },
            )
        }

        return try {
            ensureChannel(request.silent)
            val pendingIntent = buildContentPendingIntent(request)
            val builder = NotificationCompat.Builder(
                context,
                if (request.silent) SILENT_CHANNEL_ID else CHANNEL_ID,
            )
                .setSmallIcon(R.drawable.ic_dog_placeholder)
                .setContentTitle(request.title.ifBlank { context.getString(R.string.app_name) })
                .setContentText(request.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(request.body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
            if (request.silent) {
                builder.setPriority(NotificationCompat.PRIORITY_LOW)
                    .setDefaults(0)
                    .setSilent(true)
            } else {
                builder.setPriority(NotificationCompat.PRIORITY_DEFAULT)
            }
            manager.notify(NotificationId.forExecutionId(request.executionId), builder.build())
            NotificationSendResult.Delivered
        } catch (security: SecurityException) {
            NotificationSendResult.PermissionDenied(security.message ?: "notification post denied")
        } catch (throwable: Throwable) {
            NotificationSendResult.AdapterFailure(throwable.message ?: "notification adapter failure")
        }
    }

    private fun ensureChannel(silent: Boolean) {
        if (Build.VERSION.SDK_INT < 26) return
        if (silent) {
            val channel = NotificationChannel(
                SILENT_CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = context.getString(R.string.notification_channel_description)
            channel.setSound(null, null)
            channel.enableVibration(false)
            channel.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            manager.createNotificationChannel(channel)
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = context.getString(R.string.notification_channel_description)
        manager.createNotificationChannel(channel)
    }

    private fun buildContentPendingIntent(request: NotificationSendRequest): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        request.eventId?.let { intent.putExtra(InternalIntentContract.EXTRA_EVENT_ID, it) }
        request.todoId?.let { intent.putExtra(InternalIntentContract.EXTRA_TODO_ID, it) }
        intent.putExtra(InternalIntentContract.EXTRA_EXECUTION_ID, request.executionId)
        return PendingIntent.getActivity(
            context,
            NotificationId.forExecutionId(request.executionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID = "little_kitty_proactive_v1"
        const val SILENT_CHANNEL_ID = "little_kitty_companion_silent_v1"
    }
}
