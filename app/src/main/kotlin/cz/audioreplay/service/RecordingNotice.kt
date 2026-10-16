package cz.audioreplay.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object RecordingNotice {
    const val CHANNEL = "capture"
    fun ensure(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Nahrávání", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ovládání a stav průběžného záznamu"; setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
    }
    fun visible(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun settings(context: Context) {
        ensure(context)
        val intent = if (NotificationManagerCompat.from(context).areNotificationsEnabled()) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL) else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        context.startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
}
