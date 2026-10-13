package dev.howaboua.phonedigest

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

object CommandNotice {
    const val CHANNEL = "command_processing"
    const val ID = 1001
    fun isOperational(context: Context, notification: StatusBarNotification) = notification.packageName == context.packageName && notification.tag == null && notification.id == ID && notification.notification.channelId == CHANNEL
    fun notification(context: Context, result: String? = null): android.app.Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Bot request status", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, SetupActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_inbox)
            .setContentTitle("Phone inbox").setContentText(result ?: "Processing a bot request")
            .setContentIntent(open).setOnlyAlertOnce(true).setSilent(true).setTimeoutAfter(if (result == null) 120_000 else 5000)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
    }
    fun show(context: Context, result: String? = null) {
        val notification = notification(context, result)
        if (BackgroundAccess.notificationsEnabled(context)) context.getSystemService(NotificationManager::class.java).notify(ID, notification)
    }
}
