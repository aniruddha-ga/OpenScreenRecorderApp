package com.openscreenrecorder.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/*
 * Restores scheduled recording alarms after device reboot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            try {
                val configManager = ConfigManager(context)
                if (configManager.isScheduledRecordingEnabled) {
                    val targetMs = configManager.scheduledRecordingTimeMs
                    if (targetMs > System.currentTimeMillis()) {
                        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                        val alarmIntent = Intent(context, RecordingSchedulerReceiver::class.java).apply {
                            action = RecordingSchedulerReceiver.ACTION_SCHEDULED_RECORDING
                        }
                        val pendingIntent = PendingIntent.getBroadcast(
                            context,
                            0,
                            alarmIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        val showIntent = PendingIntent.getActivity(
                            context,
                            0,
                            Intent(context, SettingsActivity::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        try {
                            if (!alarmManager.canScheduleExactAlarms()) {
                                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMs, pendingIntent)
                            } else {
                                alarmManager.setAlarmClock(
                                    AlarmManager.AlarmClockInfo(targetMs, showIntent),
                                    pendingIntent
                                )
                            }
                        } catch (_: SecurityException) {
                            try {
                                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMs, pendingIntent)
                            } catch (e: Exception) {
                                Log.e("BootReceiver", "Failed fallback alarm scheduling: ${e.message}")
                            }
                        }
                        Log.d("BootReceiver", "Rescheduled alarm for $targetMs after reboot")
                    } else {
                        configManager.isScheduledRecordingEnabled = false
                        configManager.scheduledRecordingTimeMs = 0L
                    }
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to reschedule alarm after boot: ${e.message}")
            }
        }
    }
}
