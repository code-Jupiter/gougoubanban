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

/**
 * Best-effort launcher badge carrier: one silent, low-importance notification
 * whose number mirrors unread proactive messages. Stock launchers badge by
 * notification presence; OEM launchers that honor `setNumber` show the count.
 * The desktop widget renders the exact count regardless of launcher support.
 */
object UnreadBadgeNotifier {

    const val SUMMARY_NOTIFICATION_ID = 3102
    const val CHANNEL_ID = "little_kitty_unread_badge_v1"

    suspend fun refresh(context: Context, unreadCount: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (unreadCount <= 0) {
            manager.cancel(SUMMARY_NOTIFICATION_ID)
            return
        }
        // Inline checks so lint's MissingPermission sees the guard; the
        // NotificationPermission helper is not tracked across objects.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (NotificationManagerCompat.from(context).areNotificationsEnabled().not()) return
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.unread_badge_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.description = context.getString(R.string.unread_badge_channel_description)
            channel.setSound(null, null)
            channel.enableVibration(false)
            channel.setShowBadge(true)
            manager.createNotificationChannel(channel)
        }
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            SUMMARY_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dog_placeholder)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.unread_badge_text, unreadCount))
            .setNumber(unreadCount)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(SUMMARY_NOTIFICATION_ID, notification) }
    }
}
