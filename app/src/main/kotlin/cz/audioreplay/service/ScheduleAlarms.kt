package cz.audioreplay.service

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cz.audioreplay.*
import cz.audioreplay.core.*
import kotlinx.coroutines.*
import java.time.*

object ScheduleAlarms {
    private const val BOUNDARY = "cz.audioreplay.SCHEDULE_BOUNDARY"
    private const val CHANNEL = "schedule"
    private const val NOTICE = 2
    private var scheduledAt: Long? = null
    fun canSchedule(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    private fun pending(context: Context) = PendingIntent.getBroadcast(context,92,Intent(context,ScheduleReceiver::class.java).setAction(BOUNDARY),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    @Synchronized fun refresh(context: Context, config: ScheduleConfig) {
        val next = ScheduleClock.evaluate(config,Instant.now(),ZoneId.systemDefault()).nextChange?.toEpochMilli()
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (next == null || !canSchedule(context)) {
            alarms.cancel(pending(context)); scheduledAt = null
        } else if (scheduledAt != next) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next,pending(context)); scheduledAt = next
        }
        if (!config.enabled) clearNotice(context)
    }
    @Synchronized fun invalidate() { scheduledAt = null }
    fun clearNotice(context: Context) { context.getSystemService(NotificationManager::class.java).cancel(NOTICE) }
    fun activationNotice(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Plánování záznamu",NotificationManager.IMPORTANCE_DEFAULT))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getForegroundService(context,82,Intent(context,ReplayService::class.java).setAction(ReplayService.ARM_SCHEDULE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(NOTICE,NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_replay)
            .setContentTitle("AudioReplay · Aktivujte plán")
            .setContentText("Plánovaná služba neběží. Klepnutím ji znovu aktivujete.")
            .setContentIntent(open).setAutoCancel(true).build())
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val result = goAsync()
        val g = context.graph
        g.scope.launch {
            try {
                g.scheduleReady.await()
                val config = g.schedules.value
                ScheduleAlarms.invalidate(); ScheduleAlarms.refresh(context,config)
                if (!config.enabled) return@launch
                val service = ReplayService.instance
                if (service != null && g.state.value.planArmed) service.onScheduledAlarm()
                else if (intent.action != "cz.audioreplay.SCHEDULE_BOUNDARY" || ScheduleClock.evaluate(config,Instant.now(),ZoneId.systemDefault()).record)
                    ScheduleAlarms.activationNotice(context)
            } catch (e: Exception) { g.message("Plánování: ${e.localizedMessage}") }
            finally { result.finish() }
        }
    }
}
